import * as net from 'net';
import { logger } from './logger.js';
import {
  InitializeParams, InitializeResult,
  ClientCapabilities,
} from 'vscode-languageserver-protocol';

interface JsonRpcMessage {
  jsonrpc: '2.0';
  id?: number;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { code: number; message: string; data?: unknown };
}

type NotificationHandler = (params: unknown) => void;
type ServerRequestHandler = (params: unknown) => Promise<unknown>;

export class LspClient {
  private socket: net.Socket | null = null;
  private buffer = Buffer.alloc(0);
  private nextId = 1;
  private pendingRequests = new Map<number, {
    resolve: (result: unknown) => void;
    reject: (error: Error) => void;
    timer: NodeJS.Timeout;
  }>();
  private notificationHandlers = new Map<string, NotificationHandler>();
  private serverRequestHandlers = new Map<string, ServerRequestHandler>();
  private requestTimeout = 30_000;

  async connect(port: number, host = '127.0.0.1'): Promise<void> {
    return new Promise((resolve, reject) => {
      this.socket = net.createConnection({ port, host }, () => {
        logger.info(`Connected to LSP daemon on port ${port}`);
        resolve();
      });
      this.socket.on('error', (err) => {
        reject(new Error(`TCP connection failed: ${err.message}`));
      });
      this.socket.on('data', (data) => this.onData(data));
      this.socket.on('close', () => {
        logger.warn('LSP connection closed');
        this.rejectAllPending('Connection closed');
      });
    });
  }

  onNotification(method: string, handler: NotificationHandler): void {
    this.notificationHandlers.set(method, handler);
  }

  onRequest(method: string, handler: ServerRequestHandler): void {
    this.serverRequestHandlers.set(method, handler);
  }

  async request<T>(method: string, params?: unknown): Promise<T> {
    const id = this.nextId++;
    logger.debug(`-> request ${method} id=${id}`);
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRequests.delete(id);
        reject(new Error(`Request ${method} (id=${id}) timed out after ${this.requestTimeout}ms`));
      }, this.requestTimeout);
      this.pendingRequests.set(id, {
        resolve: resolve as (result: unknown) => void,
        reject,
        timer,
      });
      this.send({ jsonrpc: '2.0', id, method, params });
    });
  }

  notify(method: string, params?: unknown): void {
    logger.debug(`-> notify ${method}`);
    this.send({ jsonrpc: '2.0', method, params });
  }

  async initialize(rootUri: string): Promise<InitializeResult> {
    const params: InitializeParams = {
      processId: process.pid,
      rootUri,
      capabilities: {
        workspace: {
          didChangeWatchedFiles: { dynamicRegistration: true },
          didChangeConfiguration: { dynamicRegistration: true },
          workspaceFolders: true,
        },
        textDocument: {
          synchronization: { didSave: true, dynamicRegistration: true },
          completion: { completionItem: {} },
          codeAction: {
            codeActionLiteralSupport: {
              codeActionKind: { valueSet: [] },
            },
          },
          publishDiagnostics: { versionSupport: true },
        },
      } as ClientCapabilities,
      workspaceFolders: [{ uri: rootUri, name: rootUri.split('/').pop() || 'workspace' }],
    };
    const result = await this.request<InitializeResult>('initialize', params);
    this.notify('initialized', {});
    logger.info('LSP session initialized');
    return result;
  }

  async reconnect(port: number, rootUri: string, host = '127.0.0.1'): Promise<boolean> {
    logger.info('Attempting reconnect...');
    this.close();
    try {
      await this.connect(port, host);
      await this.initialize(rootUri);
      logger.info('Reconnected successfully');
      return true;
    } catch (err) {
      logger.error(`Reconnect failed: ${err}`);
      return false;
    }
  }

  async shutdown(): Promise<void> {
    try {
      await this.request<void>('shutdown');
      this.notify('exit');
    } catch {
      logger.warn('Shutdown request failed, forcing close');
    }
    this.socket?.destroy();
  }

  close(): void {
    this.rejectAllPending('Client closing');
    this.socket?.destroy();
    this.socket = null;
  }

  private send(msg: JsonRpcMessage): void {
    if (!this.socket) throw new Error('Not connected');
    const json = JSON.stringify(msg);
    const header = `Content-Length: ${Buffer.byteLength(json)}\r\n\r\n`;
    logger.debug(`-> wire: ${json.substring(0, 200)}`);
    this.socket.write(header + json);
  }

  private onData(data: Buffer): void {
    this.buffer = Buffer.concat([this.buffer, data]);
    while (true) {
      const headerEnd = this.buffer.indexOf('\r\n\r\n');
      if (headerEnd === -1) break;
      const header = this.buffer.subarray(0, headerEnd).toString();
      const match = header.match(/Content-Length:\s*(\d+)/i);
      if (!match) {
        logger.error('Invalid LSP header:', header);
        this.buffer = this.buffer.subarray(headerEnd + 4);
        continue;
      }
      const contentLength = parseInt(match[1], 10);
      const contentStart = headerEnd + 4;
      if (this.buffer.length < contentStart + contentLength) break;
      const content = this.buffer.subarray(contentStart, contentStart + contentLength).toString();
      this.buffer = this.buffer.subarray(contentStart + contentLength);
      logger.debug(`<- wire: ${content.substring(0, 200)}`);
      try {
        this.handleMessage(JSON.parse(content));
      } catch (err) {
        logger.error('Failed to parse JSON-RPC message:', err);
      }
    }
  }

  private handleMessage(msg: JsonRpcMessage): void {
    // Response to our request
    if (msg.id !== undefined && !msg.method) {
      const pending = this.pendingRequests.get(msg.id);
      if (pending) {
        clearTimeout(pending.timer);
        this.pendingRequests.delete(msg.id);
        if (msg.error) {
          pending.reject(new Error(`${msg.error.message} (code: ${msg.error.code})`));
        } else {
          pending.resolve(msg.result);
        }
      }
      return;
    }
    // Server-to-client request (has both method and id)
    if (msg.method && msg.id !== undefined) {
      const handler = this.serverRequestHandlers.get(msg.method);
      if (handler) {
        handler(msg.params).then(
          (result) => this.send({ jsonrpc: '2.0', id: msg.id, result }),
          (err) => this.send({ jsonrpc: '2.0', id: msg.id, error: { code: -32603, message: String(err) } }),
        );
      } else {
        logger.warn(`Unhandled server request: ${msg.method}`);
        this.send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: `Method not found: ${msg.method}` } });
      }
      return;
    }
    // Notification (has method but no id)
    if (msg.method && msg.id === undefined) {
      const handler = this.notificationHandlers.get(msg.method);
      if (handler) {
        handler(msg.params);
      } else {
        logger.debug(`Unhandled notification: ${msg.method}`);
      }
    }
  }

  private rejectAllPending(reason: string): void {
    for (const [, pending] of this.pendingRequests) {
      clearTimeout(pending.timer);
      pending.reject(new Error(reason));
    }
    this.pendingRequests.clear();
  }
}
