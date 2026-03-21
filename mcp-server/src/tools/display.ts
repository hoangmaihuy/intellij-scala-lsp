import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { LspClient } from '../lsp-client.js';
import { FileManager } from '../file-manager.js';
import { DiagnosticsCache } from '../diagnostics-cache.js';
import { toPosition } from '../utils.js';
import {
  HoverParams, Hover, DocumentSymbolParams, DocumentSymbol, SymbolInformation, SymbolKind,
  InlayHint, CodeLens,
} from 'vscode-languageserver-protocol';
import * as fs from 'fs';

export function registerDisplayTools(
  mcp: McpServer,
  lsp: LspClient,
  fileManager: FileManager,
  diagnostics: DiagnosticsCache,
): void {

  mcp.tool(
    'hover',
    'Get type info and documentation for a symbol at the specified position in a file.',
    {
      filePath: z.string().describe('Absolute path to the file'),
      line: z.number().describe('Line number (1-indexed)'),
      column: z.number().describe('Column number (1-indexed)'),
    },
    async ({ filePath, line, column }) => {
      const uri = await fileManager.ensureOpen(filePath);
      const result = await lsp.request<Hover | null>('textDocument/hover', {
        textDocument: { uri },
        position: toPosition(line, column),
      } as HoverParams);

      if (!result || !result.contents) {
        return { content: [{ type: 'text' as const, text: `No hover information at ${filePath}:${line}:${column}` }] };
      }

      let text: string;
      if (typeof result.contents === 'string') {
        text = result.contents;
      } else if ('value' in result.contents) {
        text = result.contents.value;
      } else if (Array.isArray(result.contents)) {
        text = result.contents.map(c => typeof c === 'string' ? c : c.value).join('\n\n');
      } else {
        text = JSON.stringify(result.contents);
      }

      return { content: [{ type: 'text' as const, text }] };
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

  mcp.tool('inlay_hints', 'Get inferred type hints (type annotations, parameter names) for a range of lines.',
    { filePath: z.string().describe('Absolute path to the file'), startLine: z.number().describe('Start line (1-indexed)'), endLine: z.number().describe('End line (1-indexed)') },
    async ({ filePath, startLine, endLine }) => {
      const uri = await fileManager.ensureOpen(filePath);
      const hints = await lsp.request<InlayHint[]>('textDocument/inlayHint', {
        textDocument: { uri }, range: { start: toPosition(startLine, 1), end: toPosition(endLine, 999) },
      });
      if (!hints || hints.length === 0) return { content: [{ type: 'text' as const, text: `No inlay hints in ${filePath}:${startLine}-${endLine}` }] };
      const output: string[] = [`Inlay hints in ${filePath}:${startLine}-${endLine}:\n`];
      for (const hint of hints) {
        const pos = `L${hint.position.line + 1}:C${hint.position.character + 1}`;
        const label = typeof hint.label === 'string' ? hint.label : Array.isArray(hint.label) ? hint.label.map(p => p.value).join('') : '';
        const kind = hint.kind === 1 ? 'type' : hint.kind === 2 ? 'parameter' : '';
        output.push(`  ${pos} ${kind}: ${label}`);
      }
      return { content: [{ type: 'text' as const, text: output.join('\n') }] };
    },
  );

  mcp.tool('code_lens', 'Get code lens annotations (e.g. "Overrides" markers) for a file.',
    { filePath: z.string().describe('Absolute path to the file') },
    async ({ filePath }) => {
      const uri = await fileManager.ensureOpen(filePath);
      const lenses = await lsp.request<CodeLens[]>('textDocument/codeLens', { textDocument: { uri } });
      if (!lenses || lenses.length === 0) return { content: [{ type: 'text' as const, text: `No code lenses in ${filePath}` }] };
      const output: string[] = [`Code lenses in ${filePath}:\n`];
      for (let i = 0; i < lenses.length; i++) {
        const lens = lenses[i];
        output.push(`  [${i + 1}] L${lens.range.start.line + 1}: ${lens.command?.title || '(unresolved)'}`);
      }
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
