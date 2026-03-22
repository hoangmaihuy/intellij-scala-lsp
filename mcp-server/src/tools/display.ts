import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { LspClient } from '../lsp-client.js';
import { FileManager } from '../file-manager.js';
import { DiagnosticsCache } from '../diagnostics-cache.js';
import { SymbolResolver } from '../symbol-resolver.js';
import { uriToPath, toPosition } from '../utils.js';
import {
  HoverParams, Hover, DocumentSymbolParams, DocumentSymbol, SymbolInformation, SymbolKind,
  Location, TypeHierarchyItem, TypeHierarchyPrepareParams,
} from 'vscode-languageserver-protocol';
import * as fs from 'fs';

export function registerDisplayTools(
  mcp: McpServer,
  lsp: LspClient,
  fileManager: FileManager,
  diagnostics: DiagnosticsCache,
  symbolResolver: SymbolResolver,
): void {

  mcp.tool(
    'hover',
    'Get complete info about a symbol: type signature, documentation, supertypes, subtypes, and type definition location. Prefer symbolName; use filePath+line+column for positional context.',
    {
      symbolName: z.string().optional().describe('Symbol name (e.g. "MyClass", "MyClass.myMethod")'),
      filePath: z.string().optional().describe('Absolute path to the file (use with line+column)'),
      line: z.number().optional().describe('Line number, 1-indexed (use with filePath+column)'),
      column: z.number().optional().describe('Column number, 1-indexed (use with filePath+line)'),
    },
    async (args) => {
      // Resolve to URI + position
      let uri: string;
      let position: { line: number; character: number };

      if (args.filePath && args.line !== undefined && args.column !== undefined) {
        uri = await fileManager.ensureOpen(args.filePath);
        position = toPosition(args.line, args.column);
      } else if (args.symbolName) {
        const symbols = await symbolResolver.resolve(args.symbolName);
        if (symbols.length === 0) {
          return { content: [{ type: 'text' as const, text: `No symbol found matching '${args.symbolName}'. Try workspace_symbols to search.` }] };
        }
        uri = symbols[0].location.uri;
        position = symbols[0].location.range.start;
        // Ensure file is open for LSP requests
        await fileManager.ensureOpen(uriToPath(uri));
      } else {
        return { content: [{ type: 'text' as const, text: 'Provide either symbolName or filePath+line+column.' }] };
      }

      const sections: string[] = [];

      // 1. Hover info (type signature + docs)
      const hoverResult = await lsp.request<Hover | null>('textDocument/hover', {
        textDocument: { uri }, position,
      } as HoverParams);

      if (hoverResult?.contents) {
        let text: string;
        if (typeof hoverResult.contents === 'string') {
          text = hoverResult.contents;
        } else if ('value' in hoverResult.contents) {
          text = hoverResult.contents.value;
        } else if (Array.isArray(hoverResult.contents)) {
          text = hoverResult.contents.map(c => typeof c === 'string' ? c : c.value).join('\n\n');
        } else {
          text = JSON.stringify(hoverResult.contents);
        }
        sections.push(text);
      }

      // 2. Type definition location (best-effort)
      try {
        const typeDef = await lsp.request<Location | Location[]>('textDocument/typeDefinition', {
          textDocument: { uri }, position,
        });
        const locs = Array.isArray(typeDef) ? typeDef : typeDef ? [typeDef] : [];
        if (locs.length > 0) {
          const typeDefPaths = locs.map(l => `${uriToPath(l.uri)}:${l.range.start.line + 1}`);
          sections.push(`Type definition: ${typeDefPaths.join(', ')}`);
        }
      } catch { /* best-effort */ }

      // 3. Supertypes + Subtypes (best-effort, single prepare call)
      try {
        const hierarchyItems = await lsp.request<TypeHierarchyItem[]>('textDocument/prepareTypeHierarchy', {
          textDocument: { uri }, position,
        } as TypeHierarchyPrepareParams);

        if (hierarchyItems && hierarchyItems.length > 0) {
          // Supertypes
          try {
            const allSupers: string[] = [];
            for (const item of hierarchyItems) {
              const supers = await lsp.request<TypeHierarchyItem[]>('typeHierarchy/supertypes', { item });
              if (supers) for (const s of supers) allSupers.push(s.name);
            }
            if (allSupers.length > 0) sections.push(`Supertypes: ${allSupers.join(', ')}`);
          } catch { /* best-effort */ }

          // Subtypes
          try {
            const allSubs: string[] = [];
            for (const item of hierarchyItems) {
              const subs = await lsp.request<TypeHierarchyItem[]>('typeHierarchy/subtypes', { item });
              if (subs) for (const s of subs) allSubs.push(s.name);
            }
            if (allSubs.length > 0) sections.push(`Subtypes: ${allSubs.join(', ')}`);
          } catch { /* best-effort */ }
        }
      } catch { /* best-effort: prepareTypeHierarchy not supported */ }

      if (sections.length === 0) {
        return { content: [{ type: 'text' as const, text: `No hover information for '${args.symbolName || `${args.filePath}:${args.line}:${args.column}`}'` }] };
      }

      return { content: [{ type: 'text' as const, text: sections.join('\n\n') }] };
    },
  );

  mcp.tool(
    'diagnostics',
    'Get errors and warnings for a file from the Scala analysis engine.',
    {
      filePath: z.string().describe('Absolute path to the file'),
    },
    async ({ filePath }) => {
      const uri = await fileManager.ensureOpen(filePath);
      const diags = await diagnostics.waitFor(uri, 5000);

      if (diags.length === 0) {
        return { content: [{ type: 'text' as const, text: `No diagnostics for ${filePath}` }] };
      }

      const lines = fs.readFileSync(filePath, 'utf-8').split('\n');
      const output: string[] = [`${filePath}: ${diags.length} diagnostic(s)\n`];

      for (const diag of diags) {
        const severity = ['', 'ERROR', 'WARNING', 'INFO', 'HINT'][diag.severity || 0] || 'UNKNOWN';
        const loc = `L${diag.range.start.line + 1}:C${diag.range.start.character + 1}`;
        let line = `${severity} at ${loc}: ${diag.message}`;
        if (diag.source) line += ` (${diag.source})`;
        output.push(line);

        const lineIdx = diag.range.start.line;
        if (lineIdx >= 0 && lineIdx < lines.length) {
          output.push(`  ${lineIdx + 1}|${lines[lineIdx]}`);
        }
      }

      return { content: [{ type: 'text' as const, text: output.join('\n') }] };
    },
  );

  mcp.tool(
    'document_symbols',
    'List all symbols (classes, methods, vals, etc.) in a file.',
    {
      filePath: z.string().describe('Absolute path to the file'),
    },
    async ({ filePath }) => {
      const uri = await fileManager.ensureOpen(filePath);
      const result = await lsp.request<DocumentSymbol[] | SymbolInformation[]>(
        'textDocument/documentSymbol',
        { textDocument: { uri } } as DocumentSymbolParams,
      );

      if (!result || result.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbols found in ${filePath}` }] };
      }

      const output: string[] = [`Symbols in ${filePath}:\n`];
      formatSymbols(result, output, 0);
      return { content: [{ type: 'text' as const, text: output.join('\n') }] };
    },
  );

}

const kindNames: Record<number, string> = {
  [SymbolKind.File]: 'file', [SymbolKind.Module]: 'module', [SymbolKind.Namespace]: 'namespace',
  [SymbolKind.Package]: 'package', [SymbolKind.Class]: 'class', [SymbolKind.Method]: 'method',
  [SymbolKind.Property]: 'property', [SymbolKind.Field]: 'field', [SymbolKind.Constructor]: 'constructor',
  [SymbolKind.Enum]: 'enum', [SymbolKind.Interface]: 'interface', [SymbolKind.Function]: 'function',
  [SymbolKind.Variable]: 'variable', [SymbolKind.Constant]: 'constant', [SymbolKind.String]: 'string',
  [SymbolKind.Number]: 'number', [SymbolKind.Boolean]: 'boolean', [SymbolKind.Array]: 'array',
  [SymbolKind.Object]: 'object', [SymbolKind.Key]: 'key', [SymbolKind.Null]: 'null',
  [SymbolKind.EnumMember]: 'enum member', [SymbolKind.Struct]: 'struct', [SymbolKind.Event]: 'event',
  [SymbolKind.Operator]: 'operator', [SymbolKind.TypeParameter]: 'type param',
};

function formatSymbols(
  symbols: (DocumentSymbol | SymbolInformation)[],
  output: string[],
  indent: number,
): void {
  for (const sym of symbols) {
    const ds = sym as DocumentSymbol;
    const kind = kindNames[sym.kind] || 'unknown';
    const line = ds.range ? ds.range.start.line + 1 : (sym as SymbolInformation).location?.range.start.line + 1;
    const prefix = '  '.repeat(indent);
    output.push(`${prefix}${kind} ${sym.name} (L${line})`);
    if (ds.children) {
      formatSymbols(ds.children, output, indent + 1);
    }
  }
}
