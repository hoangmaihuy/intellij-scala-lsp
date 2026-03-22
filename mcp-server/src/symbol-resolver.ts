import { LspClient } from './lsp-client.js';
import { FileManager } from './file-manager.js';
import { uriToPath } from './utils.js';
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

      const filePath = uriToPath(location.uri);
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
    // Exact match
    if (symbolName === query) return true;

    // Scala companion object: "Foo$" matches query "Foo"
    if (symbolName === query + '$') return true;

    // Suffix match for types: "SupportingDocsTableL" matches query "TableL"
    // This handles the common case where users search by class name suffix
    if (kind === SymbolKind.Class || kind === SymbolKind.Interface ||
        kind === SymbolKind.Enum || kind === SymbolKind.Struct ||
        kind === SymbolKind.Object) {
      if (symbolName.endsWith(query) || symbolName.endsWith(query + '$')) return true;
    }

    // Qualified name: "Container.method"
    if (query.includes('.')) {
      const parts = query.split('.');
      const methodName = parts[parts.length - 1];
      if (kind === SymbolKind.Method || kind === SymbolKind.Function) {
        if (symbolName === methodName) return true;
        if (symbolName.endsWith('.' + methodName)) return true;
        if (symbolName.endsWith('::' + methodName)) return true;
      }
      return false;
    }

    // Method suffix match: symbol "Foo.bar" matches query "bar"
    if (kind === SymbolKind.Method || kind === SymbolKind.Function) {
      if (symbolName.endsWith('.' + query) || symbolName.endsWith('::' + query)) return true;
    }

    return false;
  }
}
