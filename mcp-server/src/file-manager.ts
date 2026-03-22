import * as fs from 'fs';
import { LspClient } from './lsp-client.js';
import { pathToUri, uriToPath } from './utils.js';
import { logger } from './logger.js';
import {
  TextDocumentItem, VersionedTextDocumentIdentifier,
  TextDocumentIdentifier, FileChangeType,
} from 'vscode-languageserver-protocol';

interface OpenFile {
  uri: string;
  version: number;
  mtime: number;
}

export class FileManager {
  private openFiles = new Map<string, OpenFile>();
  private client: LspClient;

  constructor(client: LspClient) {
    this.client = client;
  }

  async ensureOpen(filePath: string): Promise<string> {
    const uri = pathToUri(filePath);
    let stat: fs.Stats;
    try {
      stat = fs.statSync(filePath);
    } catch {
      throw new Error(`File not found: ${filePath}`);
    }
    const currentMtime = stat.mtimeMs;

    const existing = this.openFiles.get(uri);
    if (existing) {
      if (existing.mtime !== currentMtime) {
        const content = fs.readFileSync(filePath, 'utf-8');
        existing.version++;
        existing.mtime = currentMtime;
        this.client.notify('textDocument/didChange', {
          textDocument: { uri, version: existing.version } as VersionedTextDocumentIdentifier,
          contentChanges: [{ text: content }],
        });
        this.client.notify('textDocument/didSave', {
          textDocument: { uri } as TextDocumentIdentifier,
        });
        logger.debug(`File changed on disk, sent didChange+didSave: ${filePath}`);
      }
      return uri;
    }

    const content = fs.readFileSync(filePath, 'utf-8');
    const item: TextDocumentItem = {
      uri,
      languageId: 'scala',
      version: 1,
      text: content,
    };
    this.client.notify('textDocument/didOpen', { textDocument: item });
    this.openFiles.set(uri, { uri, version: 1, mtime: currentMtime });
    logger.debug(`Opened file: ${filePath}`);
    return uri;
  }

  notifySaved(uri: string): void {
    const existing = this.openFiles.get(uri);
    if (existing) {
      const filePath = uriToPath(uri);
      try {
        existing.mtime = fs.statSync(filePath).mtimeMs;
      } catch { /* file may not exist after delete */ }
    }
    this.client.notify('textDocument/didSave', {
      textDocument: { uri } as TextDocumentIdentifier,
    });
  }

  closeAll(): void {
    for (const [uri] of this.openFiles) {
      this.client.notify('textDocument/didClose', {
        textDocument: { uri } as TextDocumentIdentifier,
      });
    }
    this.openFiles.clear();
    logger.info(`Closed all open files`);
  }

  isOpen(uri: string): boolean {
    return this.openFiles.has(uri);
  }
}
