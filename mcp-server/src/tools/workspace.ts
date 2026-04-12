import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { SessionManager } from '../session-manager.js';
import { uriToPath } from '../utils.js';
import { withToolLogging } from '../tool-logging.js';
import { SymbolInformation, SymbolKind, WorkspaceSymbolParams } from 'vscode-languageserver-protocol';
import {
  sortByScore,
  formatLocationSummary,
  MAX_DETAILED_RESULTS,
} from '../result-scoring.js';

export function registerWorkspaceTools(
  mcp: McpServer,
  sessionManager: SessionManager,
): void {
  mcp.tool(
    'workspace_symbols',
    'Search for symbols (classes, traits, methods, objects, vals, etc.) by name across the entire project. Returns kind, container, file path, and line. Use for discovery when you don\'t know the exact location.',
    {
      projectPath: z.string().describe('Absolute path to the project root'),
      query: z.string().describe('Search query (e.g. "MyClass", "process") — fuzzy matched against symbol names'),
    },
    withToolLogging('workspace_symbols', async ({ projectPath, query }) => {
      const { lsp } = await sessionManager.getSession(projectPath);
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

      // Score and sort by relevance
      const scored = sortByScore(result, (sym) => {
        const isExact = sym.name === query || sym.name === query + '$';
        const isProject = uriToPath(sym.location.uri).startsWith(projectPath);
        let score = 0;
        if (isProject) score += 100;
        if (isExact) score += 50;
        else if (sym.name.endsWith(query) || sym.name.endsWith(query + '$')) score += 10;
        return score;
      });

      const detailed = scored.slice(0, MAX_DETAILED_RESULTS);
      const remaining = scored.slice(MAX_DETAILED_RESULTS);

      const output: string[] = [];
      if (remaining.length > 0) {
        output.push(`Found ${result.length} symbol(s) (showing details for top ${detailed.length}):\n`);
      } else {
        output.push(`Found ${result.length} symbol(s):\n`);
      }

      // Detailed output for top results
      for (const sym of detailed) {
        const kind = kindNames[sym.kind] || 'symbol';
        const file = uriToPath(sym.location.uri);
        const line = sym.location.range.start.line + 1;
        const container = sym.containerName ? ` in ${sym.containerName}` : '';
        output.push(`  ${kind} ${sym.name}${container} — ${file}:${line}`);
      }

      // Summary output for remaining
      if (remaining.length > 0) {
        output.push(`\n--- Additional locations (${remaining.length}) ---`);
        for (const sym of remaining) {
          output.push(`  ${formatLocationSummary(sym.location)}`);
        }
      }

      return { content: [{ type: 'text' as const, text: output.join('\n') }] };
    }),
  );
}
