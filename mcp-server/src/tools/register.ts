import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { SessionManager } from '../session-manager.js';
import { registerNavigationTools } from './navigation.js';
import { registerDisplayTools } from './display.js';
import { registerEditingTools } from './editing.js';
import { registerWorkspaceTools } from './workspace.js';
import { registerFormattingTools } from './formatting.js';

export function registerTools(
  mcp: McpServer,
  sessionManager: SessionManager,
): void {
  registerNavigationTools(mcp, sessionManager);
  registerDisplayTools(mcp, sessionManager);
  registerEditingTools(mcp, sessionManager);
  registerWorkspaceTools(mcp, sessionManager);
  registerFormattingTools(mcp, sessionManager);
}
