import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { LspClient } from '../lsp-client.js';
import { uriToPath } from '../utils.js';
import { withToolLogging } from '../tool-logging.js';
import { SymbolInformation, SymbolKind, WorkspaceSymbolParams } from 'vscode-languageserver-protocol';

export function registerWorkspaceTools(
  mcp: McpServer,
  lsp: LspClient,
): void {
  mcp.tool(
    'workspace_symbols',
    'Search for symbols (classes, traits, methods, objects, vals, etc.) by name across the entire project. Returns kind, container, file path, and line. Use for discovery when you don\'t know the exact location.',
    { query: z.string().describe('Search query (e.g. "MyClass", "process") — fuzzy matched against symbol names') },
    withToolLogging('workspace_symbols', async ({ query }) => {
      const result = await lsp.request<SymbolInformation[]>(
        'workspace/symbol',
        { query } as WorkspaceSymbolParams,
      );

      if (!result || result.length === 0) {
        return { content: [{ type: 'text' as const, text: `No symbols found matching '${query}'` }] };
      }

      const kindNames: Record<number, string> = {
        [SymbolKind.Class]: 'class', [SymbolKind.Interface]: 'trait',
        [SymbolKind.Method]: 'method', [SymbolKind.Function]: 'function',
        [SymbolKind.Field]: 'field', [SymbolKind.Variable]: 'val',
        [SymbolKind.Constant]: 'val', [SymbolKind.Object]: 'object',
        [SymbolKind.Enum]: 'enum', [SymbolKind.Property]: 'property',
        [SymbolKind.Package]: 'package', [SymbolKind.Module]: 'module',
        [SymbolKind.Constructor]: 'constructor', [SymbolKind.TypeParameter]: 'type param',
      };

      const output: string[] = [`Found ${result.length} symbol(s):\n`];
      for (const sym of result) {
        const kind = kindNames[sym.kind] || 'symbol';
        const file = uriToPath(sym.location.uri);
        const line = sym.location.range.start.line + 1;
        const container = sym.containerName ? ` in ${sym.containerName}` : '';
        output.push(`  ${kind} ${sym.name}${container} — ${file}:${line}`);
      }

      return { content: [{ type: 'text' as const, text: output.join('\n') }] };
    }),
  );
}
