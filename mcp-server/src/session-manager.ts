import * as path from 'path';
import { LspClient } from './lsp-client.js';
import { FileManager } from './file-manager.js';
import { SymbolResolver } from './symbol-resolver.js';
import { logger } from './logger.js';
import { pathToUri } from './utils.js';
import { applyWorkspaceEdit } from './workspace-edit.js';
import { WorkspaceEdit } from 'vscode-languageserver-protocol';

export interface LspSession {
  lsp: LspClient;
  fileManager: FileManager;
  symbolResolver: SymbolResolver;
}

export class SessionManager {
  private sessions = new Map<string, LspSession>();
  private connecting = new Map<string, Promise<LspSession>>();
  private port: number;

  constructor(port: number) {
    this.port = port;
  }

  async getSession(projectPath: string): Promise<LspSession> {
    const normalized = path.resolve(projectPath);
    const existing = this.sessions.get(normalized);
    if (existing) return existing;

    // Deduplicate concurrent connections to the same project
    const inflight = this.connecting.get(normalized);
    if (inflight) return inflight;

    const promise = this.createSession(normalized);
    this.connecting.set(normalized, promise);
    try {
      const session = await promise;
      return session;
    } finally {
      this.connecting.delete(normalized);
    }
  }

  private async createSession(projectPath: string): Promise<LspSession> {
    logger.info(`Creating LSP session for project: ${projectPath}`);
    const rootUri = pathToUri(projectPath);

    const lsp = new LspClient();
    await lsp.connect(this.port);

    const fileManager = new FileManager(lsp);
    lsp.onRequest('workspace/applyEdit', async (params: unknown) => {
      const p = params as { edit: WorkspaceEdit };
      const modifiedUris = applyWorkspaceEdit(p.edit);
      for (const uri of modifiedUris) {
        fileManager.notifySaved(uri);
      }
      return { applied: true };
    });
    lsp.onRequest('workspace/configuration', async () => [{}]);
    lsp.onRequest('client/registerCapability', async () => null);
    lsp.onNotification('window/showMessage', (params) => {
      const p = params as { type: number; message: string };
      logger.info(`[LSP:${projectPath}] ${p.message}`);
    });
    lsp.onNotification('window/logMessage', (params) => {
      const p = params as { type: number; message: string };
      logger.debug(`[LSP:${projectPath}] ${p.message}`);
    });

    await lsp.initialize(rootUri);

    const symbolResolver = new SymbolResolver(lsp, fileManager);
    const session: LspSession = { lsp, fileManager, symbolResolver };
    this.sessions.set(projectPath, session);
    logger.info(`LSP session ready for project: ${projectPath}`);
    return session;
  }

  async closeAll(): Promise<void> {
    for (const [projectPath, session] of this.sessions) {
      logger.info(`Closing session for: ${projectPath}`);
      session.fileManager.closeAll();
      await session.lsp.shutdown().catch(() => {});
    }
    this.sessions.clear();
  }
}
