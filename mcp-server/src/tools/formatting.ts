import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { SessionManager } from '../session-manager.js';
import { applyWorkspaceEdit } from '../workspace-edit.js';
import { withToolLogging } from '../tool-logging.js';
import { toPosition } from '../utils.js';
import { DocumentFormattingParams, DocumentRangeFormattingParams, TextEdit, ExecuteCommandParams } from 'vscode-languageserver-protocol';

export function registerFormattingTools(mcp: McpServer, sessionManager: SessionManager): void {
  mcp.tool('format', 'Format Scala code using IntelliJ code style. Formats the entire file, or a specific line range if startLine and endLine are provided. Applies changes to disk immediately.',
    {
      projectPath: z.string().describe('Absolute path to the project root'),
      filePath: z.string().describe('Absolute path to the file'),
      startLine: z.number().optional().describe('Start line (1-indexed, optional)'),
      endLine: z.number().optional().describe('End line (1-indexed, optional)'),
    },
    withToolLogging('format', async ({ projectPath, filePath, startLine, endLine }) => {
      const { lsp, fileManager } = await sessionManager.getSession(projectPath);
      const uri = await fileManager.ensureOpen(filePath);

      let edits: TextEdit[];
      if (startLine !== undefined && endLine !== undefined) {
        edits = await lsp.request<TextEdit[]>('textDocument/rangeFormatting', {
          textDocument: { uri },
          range: { start: toPosition(startLine, 1), end: toPosition(endLine, 999) },
          options: { tabSize: 2, insertSpaces: true },
        } as DocumentRangeFormattingParams) || [];
      } else {
        edits = await lsp.request<TextEdit[]>('textDocument/formatting', {
          textDocument: { uri },
          options: { tabSize: 2, insertSpaces: true },
        } as DocumentFormattingParams) || [];
      }

      if (edits.length === 0) {
        return { content: [{ type: 'text' as const, text: 'Already formatted.' }] };
      }

      applyWorkspaceEdit({ changes: { [uri]: edits } });
      fileManager.notifySaved(uri);

      const rangeMsg = startLine !== undefined ? ` lines ${startLine}-${endLine}` : '';
      return { content: [{ type: 'text' as const, text: `Formatted ${filePath}${rangeMsg} (${edits.length} edit(s))` }] };
    }),
  );

  mcp.tool('organize_imports', 'Remove unused imports and sort remaining imports in a Scala file. Run after adding or removing code to keep imports clean.',
    {
      projectPath: z.string().describe('Absolute path to the project root'),
      filePath: z.string().describe('Absolute path to the file'),
    },
    withToolLogging('organize_imports', async ({ projectPath, filePath }) => {
      const { lsp, fileManager } = await sessionManager.getSession(projectPath);
      const uri = await fileManager.ensureOpen(filePath);
      await lsp.request<unknown>('workspace/executeCommand', {
        command: 'scala.organizeImports', arguments: [{ uri }],
      } as ExecuteCommandParams);
      fileManager.notifySaved(uri);
      return { content: [{ type: 'text' as const, text: `Organized imports in ${filePath}` }] };
    }),
  );
}
