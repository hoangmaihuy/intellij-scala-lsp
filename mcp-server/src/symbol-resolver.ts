import { LspClient } from './lsp-client.js';
import { FileManager } from './file-manager.js';
import { uriToPath } from './utils.js';
import { logger } from './logger.js';
import {
  SymbolInformation, Location, SymbolKind,
} from 'vscode-languageserver-protocol';

export type MatchQuality = 'exact' | 'companion' | 'suffix' | 'qualified';

export interface ResolvedSymbol {
  name: string;
  kind: SymbolKind;
  containerName?: string;
  location: Location;
  matchQuality: MatchQuality;
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
      const quality = this.matchQuality(info.name, symbolName, info.kind);
      if (!quality) continue;

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
        matchQuality: quality,
      });
    }

    // Sort by match quality: exact > companion > qualified > suffix
    const qualityOrder: Record<MatchQuality, number> = { exact: 0, companion: 1, qualified: 2, suffix: 3 };
    results.sort((a, b) => qualityOrder[a.matchQuality] - qualityOrder[b.matchQuality]);

    return results;
  }

  private matchQuality(symbolName: string, query: string, kind: SymbolKind): MatchQuality | null {
    // Exact match
    if (symbolName === query) return 'exact';

    // Scala companion object: "Foo$" matches query "Foo"
    if (symbolName === query + '$') return 'companion';

    // Qualified name: "Container.method"
    if (query.includes('.')) {
      const parts = query.split('.');
      const methodName = parts[parts.length - 1];
      if (kind === SymbolKind.Method || kind === SymbolKind.Function) {
        if (symbolName === methodName) return 'qualified';
        if (symbolName.endsWith('.' + methodName)) return 'qualified';
        if (symbolName.endsWith('::' + methodName)) return 'qualified';
      }
      return null;
    }

    // Suffix match for types: "SupportingDocsTableL" matches query "TableL"
    if (kind === SymbolKind.Class || kind === SymbolKind.Interface ||
        kind === SymbolKind.Enum || kind === SymbolKind.Struct ||
        kind === SymbolKind.Object) {
      if (symbolName.endsWith(query) || symbolName.endsWith(query + '$')) return 'suffix';
    }

    // Method suffix match: symbol "Foo.bar" matches query "bar"
    if (kind === SymbolKind.Method || kind === SymbolKind.Function) {
      if (symbolName.endsWith('.' + query) || symbolName.endsWith('::' + query)) return 'suffix';
    }

    return null;
  }
}
