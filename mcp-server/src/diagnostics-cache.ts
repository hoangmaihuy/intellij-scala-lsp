import { Diagnostic, PublishDiagnosticsParams } from 'vscode-languageserver-protocol';
import { LspClient } from './lsp-client.js';
import { logger } from './logger.js';

export class DiagnosticsCache {
  private cache = new Map<string, Diagnostic[]>();
  private waiters = new Map<string, Array<() => void>>();

  constructor(client: LspClient) {
    client.onNotification('textDocument/publishDiagnostics', (params) => {
      const p = params as PublishDiagnosticsParams;
      this.cache.set(p.uri, p.diagnostics);
      logger.debug(`Cached ${p.diagnostics.length} diagnostics for ${p.uri}`);
      const waiting = this.waiters.get(p.uri);
      if (waiting) {
        for (const resolve of waiting) resolve();
        this.waiters.delete(p.uri);
      }
    });
  }

  get(uri: string): Diagnostic[] {
    return this.cache.get(uri) || [];
  }

  async waitFor(uri: string, timeoutMs = 5000): Promise<Diagnostic[]> {
    const existing = this.cache.get(uri);
    if (existing && existing.length > 0) return existing;

    return new Promise<Diagnostic[]>((resolve) => {
      const timer = setTimeout(() => {
        const waiting = this.waiters.get(uri);
        if (waiting) {
          const idx = waiting.indexOf(done);
          if (idx !== -1) waiting.splice(idx, 1);
          if (waiting.length === 0) this.waiters.delete(uri);
        }
        resolve(this.cache.get(uri) || []);
      }, timeoutMs);

      const done = () => {
        clearTimeout(timer);
        resolve(this.cache.get(uri) || []);
      };

      if (!this.waiters.has(uri)) this.waiters.set(uri, []);
      this.waiters.get(uri)!.push(done);
    });
  }
}
