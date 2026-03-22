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
    // For fully qualified names like "io.circe.Json", extract the simple name
    // and use it as the query, then filter by containerName/package.
    const { simpleName, packagePrefix } = this.parseQualifiedName(symbolName);
    const query = simpleName;

    logger.info(`[SymbolResolver] resolve("${symbolName}") → query="${query}", packagePrefix=${packagePrefix ?? 'null'}`);

    const raw = await this.client.request<SymbolInformation[]>(
      'workspace/symbol',
      { query },
    );

    logger.info(`[SymbolResolver] workspace/symbol returned ${raw?.length ?? 0} results`);
    if (raw && Array.isArray(raw)) {
      for (const s of raw.slice(0, 10)) {
        const info = s as SymbolInformation;
        logger.info(`[SymbolResolver]   - name="${info.name}" container="${info.containerName}" kind=${info.kind}`);
      }
    }

    if (!raw || !Array.isArray(raw)) return [];

    const results: ResolvedSymbol[] = [];
    for (const sym of raw) {
      const info = sym as SymbolInformation;
      const quality = this.matchQuality(info.name, query, info.kind, info.containerName, packagePrefix);
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

  /** Split a potentially qualified name into simple name and package prefix. */
  private parseQualifiedName(symbolName: string): { simpleName: string; packagePrefix: string | null } {
    const lastDot = symbolName.lastIndexOf('.');
    if (lastDot === -1) return { simpleName: symbolName, packagePrefix: null };
    return {
      simpleName: symbolName.substring(lastDot + 1),
      packagePrefix: symbolName.substring(0, lastDot),
    };
  }

  private matchQuality(
    symbolName: string, query: string, kind: SymbolKind,
    containerName?: string, packagePrefix?: string | null,
  ): MatchQuality | null {
    // Check if containerName matches the expected package prefix.
    // Used when resolving fully qualified names like "io.circe.Json".
    const packageMatches = !packagePrefix || this.containerMatchesPackage(containerName, packagePrefix);

    // Exact match (considering package prefix if present)
    if (symbolName === query) {
      if (packagePrefix) {
        return packageMatches ? 'exact' : null;
      }
      return 'exact';
    }

    // Scala companion object: "Foo$" matches query "Foo"
    if (symbolName === query + '$') {
      if (packagePrefix) {
        return packageMatches ? 'companion' : null;
      }
      return 'companion';
    }

    // Qualified name: "Container.method" (no packagePrefix — old-style dotted query)
    if (!packagePrefix && query.includes('.')) {
      const parts = query.split('.');
      const methodName = parts[parts.length - 1];
      if (kind === SymbolKind.Method || kind === SymbolKind.Function) {
        if (symbolName === methodName) return 'qualified';
        if (symbolName.endsWith('.' + methodName)) return 'qualified';
        if (symbolName.endsWith('::' + methodName)) return 'qualified';
      }
      return null;
    }

    // If we had a package prefix but it didn't match, don't fall through to suffix matches
    if (packagePrefix && !packageMatches) return null;

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

  /** Check if a symbol's containerName matches the expected package prefix. */
  private containerMatchesPackage(containerName: string | undefined, packagePrefix: string): boolean {
    if (!containerName) return false;
    // containerName may be "io.circe" or "io.circe.Json" (for companion) or just "circe"
    // Accept if containerName ends with or equals the package prefix
    return containerName === packagePrefix
      || containerName.endsWith('.' + packagePrefix)
      || packagePrefix.endsWith('.' + containerName)
      || packagePrefix === containerName;
  }
}
