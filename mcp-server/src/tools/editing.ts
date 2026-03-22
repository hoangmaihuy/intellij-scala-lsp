import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { LspClient } from '../lsp-client.js';
import { FileManager } from '../file-manager.js';
import { toPosition, uriToPath } from '../utils.js';
import { applyWorkspaceEdit } from '../workspace-edit.js';
import {
  RenameParams, WorkspaceEdit, CodeActionParams, CodeAction, Command,
} from 'vscode-languageserver-protocol';

export function registerEditingTools(
  mcp: McpServer,
  lsp: LspClient,
  fileManager: FileManager,
): void {
  let cachedCodeActions: CodeAction[] = [];

  mcp.tool(
    'rename_symbol',
    'Rename a symbol at the specified position and update all references across the codebase.',
    {
      filePath: z.string().describe('Absolute path to the file'),
      line: z.number().describe('Line number (1-indexed)'),
      column: z.number().describe('Column number (1-indexed)'),
      newName: z.string().describe('New name for the symbol'),
    },
    async ({ filePath, line, column, newName }) => {
      const uri = await fileManager.ensureOpen(filePath);
      const edit = await lsp.request<WorkspaceEdit>('textDocument/rename', {
        textDocument: { uri },
        position: toPosition(line, column),
        newName,
      } as RenameParams);

      if (!edit || (!edit.changes && !edit.documentChanges)) {
        return { content: [{ type: 'text' as const, text: 'Rename returned no changes. Symbol may not be renameable at this position.' }] };
      }

      const modifiedUris = applyWorkspaceEdit(edit);
      for (const modUri of modifiedUris) {
        fileManager.notifySaved(modUri);
      }

      let changeCount = 0;
      const files = new Set<string>();
      if (edit.changes) {
        for (const [uri, edits] of Object.entries(edit.changes)) {
          changeCount += edits.length;
          files.add(uriToPath(uri));
        }
      }
      if (edit.documentChanges) {
        for (const change of edit.documentChanges) {
          if ('textDocument' in change && 'edits' in change) {
            changeCount += (change as any).edits.length;
            files.add(uriToPath((change as any).textDocument.uri));
          }
        }
      }

      const fileList = [...files].map(f => `  ${f}`).join('\n');
      return { content: [{ type: 'text' as const, text: `Renamed to '${newName}': ${changeCount} occurrence(s) in ${files.size} file(s):\n${fileList}` }] };
    },
  );

  mcp.tool(
    'code_actions',
    'Get available quick fixes, refactorings, and code actions for a range in a file.',
    {
      filePath: z.string().describe('Absolute path to the file'),
      startLine: z.number().describe('Start line (1-indexed)'),
      startColumn: z.number().describe('Start column (1-indexed)'),
      endLine: z.number().describe('End line (1-indexed)'),
      endColumn: z.number().describe('End column (1-indexed)'),
    },
    async ({ filePath, startLine, startColumn, endLine, endColumn }) => {
      const uri = await fileManager.ensureOpen(filePath);
      const result = await lsp.request<(CodeAction | Command)[]>('textDocument/codeAction', {
        textDocument: { uri },
        range: {
          start: toPosition(startLine, startColumn),
          end: toPosition(endLine, endColumn),
        },
        context: { diagnostics: [] },
      } as CodeActionParams);

      if (!result || result.length === 0) {
        cachedCodeActions = [];
        return { content: [{ type: 'text' as const, text: 'No code actions available at this location.' }] };
      }

      cachedCodeActions = result.filter((r): r is CodeAction => 'kind' in r || 'edit' in r || 'diagnostics' in r);

      const output: string[] = [`${cachedCodeActions.length} code action(s) available:\n`];
      for (let i = 0; i < cachedCodeActions.length; i++) {
        const action = cachedCodeActions[i];
        const kind = action.kind || 'unknown';
        output.push(`[${i + 1}] ${action.title} (${kind})`);
      }
      output.push('\nUse apply_code_action with the index number to apply.');

      return { content: [{ type: 'text' as const, text: output.join('\n') }] };
    },
  );

  mcp.tool(
    'apply_code_action',
    'Apply a code action from the most recent code_actions result.',
    {
      filePath: z.string().describe('Absolute path to the file (must match the file from code_actions)'),
      actionIndex: z.number().describe('Index of the action to apply (1-indexed, from code_actions output)'),
    },
    async ({ filePath, actionIndex }) => {
      if (cachedCodeActions.length === 0) {
        return { content: [{ type: 'text' as const, text: 'No cached code actions. Call code_actions first.' }] };
      }
      if (actionIndex < 1 || actionIndex > cachedCodeActions.length) {
        return { content: [{ type: 'text' as const, text: `Invalid index ${actionIndex}. Range: 1-${cachedCodeActions.length}` }] };
      }

      const action = cachedCodeActions[actionIndex - 1];

      let resolved = action;
      if (!action.edit) {
        resolved = await lsp.request<CodeAction>('codeAction/resolve', action);
      }

      if (!resolved.edit) {
        return { content: [{ type: 'text' as const, text: `Code action '${action.title}' produced no workspace edit.` }] };
      }

      const modifiedUris = applyWorkspaceEdit(resolved.edit);
      for (const uri of modifiedUris) {
        fileManager.notifySaved(uri);
      }

      return { content: [{ type: 'text' as const, text: `Applied: ${action.title}\nModified ${modifiedUris.length} file(s): ${modifiedUris.map(uriToPath).join(', ')}` }] };
    },
  );

}
