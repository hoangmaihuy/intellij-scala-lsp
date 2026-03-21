import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { LspClient } from '../lsp-client.js';
import { FileManager } from '../file-manager.js';
import { SymbolResolver } from '../symbol-resolver.js';
import { uriToPath, pathToUri, addLineNumbers, toPosition } from '../utils.js';
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
): Promise<{ uri: string; position: Position; label: string }[]> {
  if (args.filePath && args.line !== undefined && args.column !== undefined) {
    const uri = await fileManager.ensureOpen(args.filePath);
    return [{ uri, position: toPosition(args.line, args.column), label: `${args.filePath}:${args.line}:${args.column}` }];
  }
  if (args.symbolName) {
    const symbols = await symbolResolver.resolve(args.symbolName);
    return symbols.map(s => ({ uri: s.location.uri, position: s.location.range.start, label: s.name }));
  }
  return [];
}

const navigationParams = {
  symbolName: z.string().optional().describe('Symbol name (e.g. "MyClass", "MyClass.myMethod")'),
  filePath: z.string().optional().describe('Absolute path to the file (use with line+column)'),
  line: z.number().optional().describe('Line number, 1-indexed (use with filePath+column)'),
  column: z.number().optional().describe('Column number, 1-indexed (use with filePath+line)'),
};

export function registerNavigationTools(
  mcp: McpServer,
  lsp: LspClient,
  fileManager: FileManager,
  symbolResolver: SymbolResolver,
): void {

  mcp.tool(
    'definition',
    'Go to definition of a symbol. Provide EITHER symbolName OR filePath+line+column. Use filePath+line+column when you have a specific position (works for external/library symbols too).',
    navigationParams,
    async (args) => {
      const targets = await resolveTargets(args, symbolResolver, fileManager);
      if (targets.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbol found matching '${args.symbolName}'. Try with filePath+line+column instead to resolve from a usage site.` }] };
      }

      const results: string[] = [];
      for (const target of targets) {
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

      return { content: [{ type: 'text' as const, text: results.join('\n\n') }] };
    },
  );

  mcp.tool(
    'references',
    'Find all references of a symbol. Provide EITHER symbolName OR filePath+line+column.',
    navigationParams,
    async (args) => {
      const targets = await resolveTargets(args, symbolResolver, fileManager);
      if (targets.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbol found matching '${args.symbolName}'. Try with filePath+line+column instead.` }] };
      }

      const allRefs: string[] = [];
      for (const target of targets) {
        const refs = await lsp.request<Location[]>('textDocument/references', {
          textDocument: { uri: target.uri },
          position: target.position,
          context: { includeDeclaration: false },
        } as ReferenceParams);

        if (!refs || refs.length === 0) continue;

        const byFile = new Map<string, Location[]>();
        for (const ref of refs) {
          const arr = byFile.get(ref.uri) || [];
          arr.push(ref);
          byFile.set(ref.uri, arr);
        }

        for (const [uri, fileRefs] of byFile) {
          const filePath = uriToPath(uri);
          const content = fs.readFileSync(filePath, 'utf-8');
          const lines = content.split('\n');
          const locs = fileRefs.map(r => `L${r.range.start.line + 1}:C${r.range.start.character + 1}`);

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

          allRefs.push(`---\n${filePath}\nReferences: ${fileRefs.length} at ${locs.join(', ')}\n\n${chunks.join('\n')}`);
        }
      }

      if (allRefs.length === 0) {
        return { content: [{ type: 'text' as const, text: `No references found for '${args.symbolName || targets[0]?.label}'` }] };
      }
      return { content: [{ type: 'text' as const, text: allRefs.join('\n\n') }] };
    },
  );

  mcp.tool(
    'implementations',
    'Find all implementations of a trait, class, or abstract method. Provide EITHER symbolName OR filePath+line+column.',
    navigationParams,
    async (args) => {
      const targets = await resolveTargets(args, symbolResolver, fileManager);
      if (targets.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbol found matching '${args.symbolName}'. Try with filePath+line+column instead.` }] };
      }

      const allImpls: string[] = [];
      for (const target of targets) {
        const impls = await lsp.request<Location[]>('textDocument/implementation', {
          textDocument: { uri: target.uri },
          position: target.position,
        } as ImplementationParams);

        if (!impls || impls.length === 0) continue;

        for (const impl of Array.isArray(impls) ? impls : [impls]) {
          const filePath = uriToPath(impl.uri);
          await fileManager.ensureOpen(filePath);
          const content = fs.readFileSync(filePath, 'utf-8');
          const lines = content.split('\n');
          const startLine = impl.range.start.line;
          const endLine = Math.min(startLine + 4, lines.length - 1);
          const snippet = addLineNumbers(lines.slice(startLine, endLine + 1).join('\n'), startLine + 1);
          allImpls.push(`---\n${filePath}:L${startLine + 1}\n\n${snippet}`);
        }
      }

      if (allImpls.length === 0) {
        return { content: [{ type: 'text' as const, text: `No implementations found for '${args.symbolName || targets[0]?.label}'` }] };
      }
      return { content: [{ type: 'text' as const, text: `Found ${allImpls.length} implementation(s):\n\n${allImpls.join('\n\n')}` }] };
    },
  );

  mcp.tool(
    'type_definition',
    'Go to the type definition of a symbol (e.g. from a variable to its type\'s class). Provide EITHER symbolName OR filePath+line+column.',
    navigationParams,
    async (args) => {
      const targets = await resolveTargets(args, symbolResolver, fileManager);
      if (targets.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbol found matching '${args.symbolName}'. Try with filePath+line+column instead.` }] };
      }

      const results: string[] = [];
      for (const target of targets) {
        const typeDef = await lsp.request<Location | Location[]>('textDocument/typeDefinition', {
          textDocument: { uri: target.uri },
          position: target.position,
        });
        const locs = Array.isArray(typeDef) ? typeDef : typeDef ? [typeDef] : [];
        for (const loc of locs) {
          const filePath = uriToPath(loc.uri);
          await fileManager.ensureOpen(filePath);
          const content = fs.readFileSync(filePath, 'utf-8');
          const lines = content.split('\n');
          const startLine = loc.range.start.line;
          const endLine = Math.min(startLine + 4, lines.length - 1);
          const snippet = addLineNumbers(lines.slice(startLine, endLine + 1).join('\n'), startLine + 1);
          results.push(`---\nType of ${target.label}:\n${filePath}:L${startLine + 1}\n\n${snippet}`);
        }
      }

      if (results.length === 0) {
        return { content: [{ type: 'text' as const, text: `No type definition found for '${args.symbolName || targets[0]?.label}'` }] };
      }
      return { content: [{ type: 'text' as const, text: results.join('\n\n') }] };
    },
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
