import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { LspClient } from '../lsp-client.js';
import { FileManager } from '../file-manager.js';
import { SymbolResolver } from '../symbol-resolver.js';
import { uriToPath, addLineNumbers } from '../utils.js';
import * as fs from 'fs';
import {
  Location, DocumentSymbol, SymbolInformation,
  DefinitionParams, ImplementationParams, DocumentSymbolParams,
  ReferenceParams, Position, Range,
} from 'vscode-languageserver-protocol';

export function registerNavigationTools(
  mcp: McpServer,
  lsp: LspClient,
  fileManager: FileManager,
  symbolResolver: SymbolResolver,
): void {

  mcp.tool(
    'definition',
    'Find and read the source code definition of a symbol. Returns the complete implementation.',
    { symbolName: z.string().describe('Symbol name (e.g. "MyClass", "MyClass.myMethod")') },
    async ({ symbolName }) => {
      const symbols = await symbolResolver.resolve(symbolName);
      if (symbols.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbol found matching '${symbolName}'` }] };
      }

      const results: string[] = [];
      for (const sym of symbols) {
        const defResult = await lsp.request<Location | Location[]>('textDocument/definition', {
          textDocument: { uri: sym.location.uri },
          position: sym.location.range.start,
        } as DefinitionParams);

        const defLocations = Array.isArray(defResult) ? defResult : defResult ? [defResult] : [];
        if (defLocations.length === 0) {
          results.push(`${sym.name}: definition not found`);
          continue;
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

          results.push(`---\nSymbol: ${sym.name}\nFile: ${defPath}\nRange: L${fullRange.start.line + 1}-L${fullRange.end.line + 1}\n\n${source}`);
        }
      }

      return { content: [{ type: 'text' as const, text: results.join('\n\n') }] };
    },
  );

  mcp.tool(
    'references',
    'Find all usages and references of a symbol throughout the codebase.',
    { symbolName: z.string().describe('Symbol name (e.g. "MyClass", "myMethod")') },
    async ({ symbolName }) => {
      const symbols = await symbolResolver.resolve(symbolName);
      if (symbols.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbol found matching '${symbolName}'` }] };
      }

      const allRefs: string[] = [];
      for (const sym of symbols) {
        const refs = await lsp.request<Location[]>('textDocument/references', {
          textDocument: { uri: sym.location.uri },
          position: sym.location.range.start,
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
            const line = ref.range.start.line;
            for (let i = Math.max(0, line - 2); i <= Math.min(lines.length - 1, line + 2); i++) {
              shownLines.add(i);
            }
          }
          const sortedLines = [...shownLines].sort((a, b) => a - b);
          const chunks: string[] = [];
          let prevLine = -2;
          for (const line of sortedLines) {
            if (line > prevLine + 1) chunks.push('...');
            const num = String(line + 1).padStart(4);
            chunks.push(`${num}|${lines[line]}`);
            prevLine = line;
          }

          allRefs.push(`---\n${filePath}\nReferences: ${fileRefs.length} at ${locs.join(', ')}\n\n${chunks.join('\n')}`);
        }
      }

      if (allRefs.length === 0) {
        return { content: [{ type: 'text' as const, text: `No references found for '${symbolName}'` }] };
      }
      return { content: [{ type: 'text' as const, text: allRefs.join('\n\n') }] };
    },
  );

  mcp.tool(
    'implementations',
    'Find all implementations of a trait, class, or abstract method.',
    { symbolName: z.string().describe('Symbol name (e.g. "MyTrait", "MyTrait.myMethod")') },
    async ({ symbolName }) => {
      const symbols = await symbolResolver.resolve(symbolName);
      if (symbols.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbol found matching '${symbolName}'` }] };
      }

      const allImpls: string[] = [];
      for (const sym of symbols) {
        const impls = await lsp.request<Location[]>('textDocument/implementation', {
          textDocument: { uri: sym.location.uri },
          position: sym.location.range.start,
        } as ImplementationParams);

        if (!impls || impls.length === 0) continue;

        for (const impl of Array.isArray(impls) ? impls : [impls]) {
          const filePath = uriToPath(impl.uri);
          await fileManager.ensureOpen(filePath);
          const content = fs.readFileSync(filePath, 'utf-8');
          const lines = content.split('\n');
          const startLine = impl.range.start.line;
          const endLine = Math.min(startLine + 4, lines.length - 1);
          const snippet = addLineNumbers(
            lines.slice(startLine, endLine + 1).join('\n'),
            startLine + 1,
          );
          allImpls.push(`---\n${filePath}:L${startLine + 1}\n\n${snippet}`);
        }
      }

      if (allImpls.length === 0) {
        return { content: [{ type: 'text' as const, text: `No implementations found for '${symbolName}'` }] };
      }
      return { content: [{ type: 'text' as const, text: `Found ${allImpls.length} implementation(s):\n\n${allImpls.join('\n\n')}` }] };
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
