import { LspClient } from './lsp-client.js';
import { FileManager } from './file-manager.js';
import { logger } from './logger.js';
import {
  SymbolInformation, Location, SymbolKind,
} from 'vscode-languageserver-protocol';

export interface ResolvedSymbol {
  name: string;
  kind: SymbolKind;
  containerName?: string;
  location: Location;
}

export class SymbolResolver {
  constructor(
    private client: LspClient,
    private fileManager: FileManager,
  ) {}

  async resolve(symbolName: string): Promise<ResolvedSymbol[]> {
    const raw = await this.client.request<SymbolInformation[]>(
      'workspace/symbol',
      { query: symbolName },
    );

    if (!raw || !Array.isArray(raw)) return [];

    const results: ResolvedSymbol[] = [];
    for (const sym of raw) {
      const info = sym as SymbolInformation;
      if (!this.matchesName(info.name, symbolName, info.kind)) continue;

      const location = info.location;
      if (!location) continue;

      const filePath = decodeURIComponent(location.uri.replace('file://', ''));
      try {
        await this.fileManager.ensureOpen(filePath);
      } catch (err) {
        logger.warn(`Could not open file for symbol ${info.name}: ${err}`);
        continue;
      }

      results.push({
        name: info.name,
        kind: info.kind,
        containerName: info.containerName,
        location,
      });
    }

    return results;
  }

  private matchesName(symbolName: string, query: string, kind: SymbolKind): boolean {
    if (symbolName === query) return true;

    if (query.includes('.')) {
      if (symbolName === query) return true;
      const parts = query.split('.');
      const methodName = parts[parts.length - 1];
      if (kind === SymbolKind.Method || kind === SymbolKind.Function) {
        if (symbolName === methodName) return true;
        if (symbolName.endsWith('.' + methodName)) return true;
        if (symbolName.endsWith('::' + methodName)) return true;
      }
      return false;
    }

    if (kind === SymbolKind.Method || kind === SymbolKind.Function) {
      if (symbolName.endsWith('.' + query) || symbolName.endsWith('::' + query)) return true;
    }

    return false;
  }
}
