import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { LspClient } from '../lsp-client.js';
import { FileManager } from '../file-manager.js';
import { DiagnosticsCache } from '../diagnostics-cache.js';
import { SymbolResolver } from '../symbol-resolver.js';
import { registerNavigationTools } from './navigation.js';
import { registerDisplayTools } from './display.js';
import { registerEditingTools } from './editing.js';
import { registerWorkspaceTools } from './workspace.js';
import { registerFormattingTools } from './formatting.js';

export function registerTools(
  mcp: McpServer,
  lsp: LspClient,
  fileManager: FileManager,
  diagnostics: DiagnosticsCache,
  symbolResolver: SymbolResolver,
): void {
  registerNavigationTools(mcp, lsp, fileManager, symbolResolver);
  registerDisplayTools(mcp, lsp, fileManager, diagnostics, symbolResolver);
  registerEditingTools(mcp, lsp, fileManager);
  registerWorkspaceTools(mcp, lsp);
  registerFormattingTools(mcp, lsp, fileManager);
}
