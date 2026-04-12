import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { SessionManager } from '../session-manager.js';
import { FileManager } from '../file-manager.js';
import { SymbolResolver } from '../symbol-resolver.js';
import { uriToPath, addLineNumbers, toPosition } from '../utils.js';
import { withToolLogging } from '../tool-logging.js';
import {
  scoreLocation,
  sortByScore,
  formatLocationSummary,
  MAX_DETAILED_RESULTS,
} from '../result-scoring.js';
import * as fs from 'fs';
import {
  Location, DocumentSymbol, SymbolInformation,
  DefinitionParams, ImplementationParams, DocumentSymbolParams,
  ReferenceParams, Position, Range,
} from 'vscode-languageserver-protocol';

/** Resolve input to URI + Position pairs. Accepts either symbolName or filePath+line+column. */
async function resolveTargets(
  args: { symbolName?: string; filePath?: string; line?: number; column?: number },
  symbolResolver: SymbolResolver,
  fileManager: FileManager,
): Promise<{ uri: string; position: Position; label: string; matchQuality?: string }[]> {
  if (args.filePath && args.line !== undefined && args.column !== undefined) {
    const uri = await fileManager.ensureOpen(args.filePath);
    return [{ uri, position: toPosition(args.line, args.column), label: `${args.filePath}:${args.line}:${args.column}` }];
  }
  if (args.symbolName) {
    const symbols = await symbolResolver.resolve(args.symbolName);
    const hasExact = symbols.some(s => s.matchQuality === 'exact' || s.matchQuality === 'companion');
    // If exact/companion matches exist, drop suffix matches to avoid processing irrelevant symbols
    const filtered = hasExact
      ? symbols.filter(s => s.matchQuality === 'exact' || s.matchQuality === 'companion')
      : symbols;
    // Deduplicate by URI+position — same symbol can appear from multiple contributors
    const seen = new Set<string>();
    const deduped = filtered.filter(s => {
      const key = `${s.location.uri}:${s.location.range.start.line}:${s.location.range.start.character}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    return deduped.map(s => {
      const position = refinePosition(s.location, s.name);
      return { uri: s.location.uri, position, label: s.name, matchQuality: s.matchQuality };
    });
  }
  return [];
}

/**
 * workspace/symbol returns range.start at column 0 (beginning of line), which can land on
 * a modifier keyword (e.g. `private` in `private[builder] object RawJsonOps`). IntelliJ then
 * resolves to the wrong PSI element (e.g. the `builder` package qualifier).
 * Scan the source line to find the actual column of the identifier.
 */
function refinePosition(location: Location, symbolName: string): Position {
  const simpleName = symbolName.replace(/\$$/, '').split(/\.|::/).pop()!;
  const filePath = uriToPath(location.uri);
  const startPos = location.range.start;
  // Only refine when column is 0 — a non-zero column is already precise
  if (startPos.character !== 0) return startPos;
  try {
    const content = fs.readFileSync(filePath, 'utf-8');
    const line = content.split('\n')[startPos.line];
    if (line) {
      const col = line.indexOf(simpleName);
      if (col !== -1) return { line: startPos.line, character: col };
    }
  } catch {
    // fall through to original position
  }
  return startPos;
}

const navigationParams = {
  projectPath: z.string().describe('Absolute path to the project root'),
  symbolName: z.string().optional().describe('Symbol name (e.g. "MyClass", "MyClass.myMethod")'),
  filePath: z.string().optional().describe('Absolute path to the file (use with line+column)'),
  line: z.number().optional().describe('Line number, 1-indexed (use with filePath+column)'),
  column: z.number().optional().describe('Column number, 1-indexed (use with filePath+line)'),
};

export function registerNavigationTools(
  mcp: McpServer,
  sessionManager: SessionManager,
): void {

  mcp.tool(
    'definition',
    'Read the full source code where a symbol is defined (returns the enclosing class/method body with line numbers, up to 10 matches sorted by relevance). Use this instead of Read for Scala files. Prefer symbolName (e.g. "MyClass", "pkg.MyClass.method"); use filePath+line+column to disambiguate overloads or resolve from a usage site.',
    navigationParams,
    withToolLogging('definition', async (args) => {
      const { lsp, fileManager, symbolResolver } = await sessionManager.getSession(args.projectPath);
      const targets = await resolveTargets(args, symbolResolver, fileManager);
      if (targets.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbol found matching '${args.symbolName}'. Try with filePath+line+column instead to resolve from a usage site.` }] };
      }

      const results: string[] = [];

      if (args.symbolName && targets.every(t => t.matchQuality === 'suffix')) {
        results.push(`Note: No exact match for '${args.symbolName}'. Showing suffix matches. Use filePath+line+column from a usage site for external/library symbols.`);
      }

      // Score and sort targets by relevance
      const scored = sortByScore(targets, (t) => {
        const filePath = uriToPath(t.uri);
        let score = 0;
        if (filePath.startsWith(args.projectPath)) score += 100;
        if (t.matchQuality === 'exact') score += 50;
        else if (t.matchQuality === 'companion') score += 40;
        else if (t.matchQuality === 'suffix') score += 10;
        return score;
      });

      const detailed = scored.slice(0, MAX_DETAILED_RESULTS);
      const remaining = scored.slice(MAX_DETAILED_RESULTS);

      if (remaining.length > 0) {
        results.push(`Showing ${detailed.length} of ${scored.length} matches.`);
      }

      for (const target of detailed) {
        const defResult = await lsp.request<Location | Location[]>('textDocument/definition', {
          textDocument: { uri: target.uri },
          position: target.position,
        } as DefinitionParams);

        const defLocations = Array.isArray(defResult) ? defResult : defResult ? [defResult] : [];
        if (defLocations.length === 0) {
          // If called via symbolName, the symbol location IS the definition
          if (args.symbolName) {
            defLocations.push({ uri: target.uri, range: { start: target.position, end: target.position } });
          } else {
            results.push(`No definition found at ${target.label}`);
            continue;
          }
        }

        for (const defLoc of defLocations) {
          const defPath = uriToPath(defLoc.uri);
          await fileManager.ensureOpen(defPath);

          const docSymbols = await lsp.request<DocumentSymbol[] | SymbolInformation[]>(
            'textDocument/documentSymbol',
            { textDocument: { uri: defLoc.uri } } as DocumentSymbolParams,
          );

          let fullRange = defLoc.range;
          if (docSymbols && Array.isArray(docSymbols)) {
            const enclosing = findEnclosingSymbol(docSymbols, defLoc.range.start);
            if (enclosing) fullRange = enclosing;
          }

          const content = fs.readFileSync(defPath, 'utf-8');
          const lines = content.split('\n');
          const sourceLines = lines.slice(fullRange.start.line, fullRange.end.line + 1);
          const source = addLineNumbers(sourceLines.join('\n'), fullRange.start.line + 1);

          results.push(`---\nSymbol: ${target.label}\nFile: ${defPath}\nRange: L${fullRange.start.line + 1}-L${fullRange.end.line + 1}\n\n${source}`);
        }
      }

      // Summary for remaining
      if (remaining.length > 0) {
        results.push(`\n--- Additional locations (${remaining.length}) ---`);
        for (const t of remaining) {
          const filePath = uriToPath(t.uri);
          const line = t.position.line + 1;
          results.push(`  ${filePath}:${line}`);
        }
      }

      return { content: [{ type: 'text' as const, text: results.join('\n\n') }] };
    }),
  );

  mcp.tool(
    'references',
    'Find all usages of a symbol across the codebase (excludes the declaration). Returns locations grouped by file with 2 lines of surrounding context for top results. Prefer symbolName; use filePath+line+column to disambiguate overloads or resolve from a usage site.',
    navigationParams,
    withToolLogging('references', async (args) => {
      const { lsp, fileManager, symbolResolver } = await sessionManager.getSession(args.projectPath);
      const targets = await resolveTargets(args, symbolResolver, fileManager);
      if (targets.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbol found matching '${args.symbolName}'. Try with filePath+line+column instead.` }] };
      }

      // Filter like definition does: if exact/companion matches exist, drop suffix matches
      const hasExact = targets.some(t => t.matchQuality === 'exact' || t.matchQuality === 'companion');
      const effectiveTargets = hasExact
        ? targets.filter(t => t.matchQuality === 'exact' || t.matchQuality === 'companion')
        : targets;

      // Pass 1: Gather all reference locations
      const allLocations: Location[] = [];
      for (const target of effectiveTargets) {
        const refs = await lsp.request<Location[]>('textDocument/references', {
          textDocument: { uri: target.uri },
          position: target.position,
          context: { includeDeclaration: false },
        } as ReferenceParams);

        if (refs) allLocations.push(...refs);
      }

      // Deduplicate by URI+line+column (multiple targets may return same reference)
      const seen = new Set<string>();
      const uniqueLocations = allLocations.filter(loc => {
        const key = `${loc.uri}:${loc.range.start.line}:${loc.range.start.character}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      });

      if (uniqueLocations.length === 0) {
        return { content: [{ type: 'text' as const, text: `No references found for '${args.symbolName || targets[0]?.label}'` }] };
      }

      // Score and sort by relevance
      const sorted = sortByScore(uniqueLocations, (loc) =>
        scoreLocation(loc, args.projectPath),
      );

      const detailed = sorted.slice(0, MAX_DETAILED_RESULTS);
      const remaining = sorted.slice(MAX_DETAILED_RESULTS);

      const output: string[] = [];
      if (remaining.length > 0) {
        output.push(`Found ${uniqueLocations.length} reference(s) (showing details for top ${detailed.length}):\n`);
      } else {
        output.push(`Found ${uniqueLocations.length} reference(s):\n`);
      }

      // Pass 2: Fetch context only for top results
      const byFile = new Map<string, Location[]>();
      for (const ref of detailed) {
        const arr = byFile.get(ref.uri) || [];
        arr.push(ref);
        byFile.set(ref.uri, arr);
      }

      for (const [uri, fileRefs] of byFile) {
        const filePath = uriToPath(uri);
        const locs = fileRefs.map(r => `L${r.range.start.line + 1}:C${r.range.start.character + 1}`);

        let content: string;
        try {
          content = fs.readFileSync(filePath, 'utf-8');
        } catch {
          output.push(`---\n${filePath}\nReferences: ${fileRefs.length} at ${locs.join(', ')} (file unreadable)`);
          continue;
        }
        const lines = content.split('\n');

        const shownLines = new Set<number>();
        for (const ref of fileRefs) {
          const refLine = ref.range.start.line;
          for (let i = Math.max(0, refLine - 2); i <= Math.min(lines.length - 1, refLine + 2); i++) {
            shownLines.add(i);
          }
        }
        const sortedLines = [...shownLines].sort((a, b) => a - b);
        const chunks: string[] = [];
        let prevLine = -2;
        for (const ln of sortedLines) {
          if (ln > prevLine + 1) chunks.push('...');
          const num = String(ln + 1).padStart(4);
          chunks.push(`${num}|${lines[ln]}`);
          prevLine = ln;
        }

        output.push(`---\n${filePath}\nReferences: ${fileRefs.length} at ${locs.join(', ')}\n\n${chunks.join('\n')}`);
      }

      // Summary for remaining
      if (remaining.length > 0) {
        output.push(`\n--- Additional locations (${remaining.length}) ---`);
        for (const ref of remaining) {
          output.push(`  ${formatLocationSummary(ref)}`);
        }
      }

      return { content: [{ type: 'text' as const, text: output.join('\n\n') }] };
    }),
  );

  mcp.tool(
    'implementations',
    'Find all implementations of a trait, abstract class, or abstract method. Returns full source code of each implementation (up to 10 sorted by relevance, then summaries). Prefer symbolName; use filePath+line+column to disambiguate.',
    navigationParams,
    withToolLogging('implementations', async (args) => {
      const { lsp, fileManager, symbolResolver } = await sessionManager.getSession(args.projectPath);
      const targets = await resolveTargets(args, symbolResolver, fileManager);
      if (targets.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbol found matching '${args.symbolName}'. Try with filePath+line+column instead.` }] };
      }

      // Gather all implementation locations
      const allImpls: Location[] = [];
      for (const target of targets) {
        const impls = await lsp.request<Location[]>('textDocument/implementation', {
          textDocument: { uri: target.uri },
          position: target.position,
        } as ImplementationParams);

        if (impls) {
          const implArray = Array.isArray(impls) ? impls : [impls];
          allImpls.push(...implArray);
        }
      }

      // Deduplicate by URI+line+column (multiple targets may return same implementation)
      const seen = new Set<string>();
      const uniqueImpls = allImpls.filter(loc => {
        const key = `${loc.uri}:${loc.range.start.line}:${loc.range.start.character}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      });

      if (uniqueImpls.length === 0) {
        return { content: [{ type: 'text' as const, text: `No implementations found for '${args.symbolName || targets[0]?.label}'` }] };
      }

      // Score and sort by relevance
      const sorted = sortByScore(uniqueImpls, (loc) =>
        scoreLocation(loc, args.projectPath),
      );

      const detailed = sorted.slice(0, MAX_DETAILED_RESULTS);
      const remaining = sorted.slice(MAX_DETAILED_RESULTS);

      const output: string[] = [];
      if (remaining.length > 0) {
        output.push(`Found ${uniqueImpls.length} implementation(s) (showing details for top ${detailed.length}):\n`);
      } else {
        output.push(`Found ${uniqueImpls.length} implementation(s):\n`);
      }

      for (const impl of detailed) {
        const filePath = uriToPath(impl.uri);
        await fileManager.ensureOpen(filePath);

        // Get full symbol range via documentSymbol
        const docSymbols = await lsp.request<DocumentSymbol[] | SymbolInformation[]>(
          'textDocument/documentSymbol',
          { textDocument: { uri: impl.uri } } as DocumentSymbolParams,
        );

        let fullRange = impl.range;
        if (docSymbols && Array.isArray(docSymbols)) {
          const enclosing = findEnclosingSymbol(docSymbols, impl.range.start);
          if (enclosing) fullRange = enclosing;
        }

        let content: string;
        try {
          content = fs.readFileSync(filePath, 'utf-8');
        } catch {
          output.push(`---\n${filePath}:L${impl.range.start.line + 1} (file unreadable)`);
          continue;
        }
        const lines = content.split('\n');
        const sourceLines = lines.slice(fullRange.start.line, fullRange.end.line + 1);
        const source = addLineNumbers(sourceLines.join('\n'), fullRange.start.line + 1);
        output.push(`---\n${filePath}:L${fullRange.start.line + 1}-L${fullRange.end.line + 1}\n\n${source}`);
      }

      // Summary for remaining
      if (remaining.length > 0) {
        output.push(`\n--- Additional locations (${remaining.length}) ---`);
        for (const impl of remaining) {
          output.push(`  ${formatLocationSummary(impl)}`);
        }
      }

      return { content: [{ type: 'text' as const, text: output.join('\n\n') }] };
    }),
  );

}

function findEnclosingSymbol(symbols: (DocumentSymbol | SymbolInformation)[], pos: Position): Range | null {
  let best: Range | null = null;
  for (const sym of symbols) {
    const ds = sym as DocumentSymbol;
    if (!ds.range) continue;
    if (containsPosition(ds.range, pos)) {
      if (!best || rangeSize(ds.range) < rangeSize(best)) {
        best = ds.range;
      }
      if (ds.children) {
        const child = findEnclosingSymbol(ds.children, pos);
        if (child && (!best || rangeSize(child) < rangeSize(best))) {
          best = child;
        }
      }
    }
  }
  return best;
}

function containsPosition(range: Range, pos: Position): boolean {
  if (pos.line < range.start.line || pos.line > range.end.line) return false;
  if (pos.line === range.start.line && pos.character < range.start.character) return false;
  if (pos.line === range.end.line && pos.character > range.end.character) return false;
  return true;
}

function rangeSize(range: Range): number {
  return (range.end.line - range.start.line) * 10000 + (range.end.character - range.start.character);
}
