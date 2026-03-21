import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { LspClient } from '../lsp-client.js';
import { FileManager } from '../file-manager.js';
import { applyWorkspaceEdit } from '../workspace-edit.js';
import { toPosition } from '../utils.js';
import { DocumentFormattingParams, DocumentRangeFormattingParams, TextEdit, ExecuteCommandParams } from 'vscode-languageserver-protocol';

export function registerFormattingTools(mcp: McpServer, lsp: LspClient, fileManager: FileManager): void {
  mcp.tool('format_file', 'Format an entire Scala file using IntelliJ code style.',
    { filePath: z.string().describe('Absolute path to the file') },
    async ({ filePath }) => {
      const uri = await fileManager.ensureOpen(filePath);
      const edits = await lsp.request<TextEdit[]>('textDocument/formatting', {
        textDocument: { uri }, options: { tabSize: 2, insertSpaces: true },
      } as DocumentFormattingParams);
      if (!edits || edits.length === 0) return { content: [{ type: 'text' as const, text: 'File is already formatted.' }] };
      applyWorkspaceEdit({ changes: { [uri]: edits } });
      fileManager.notifySaved(uri);
      return { content: [{ type: 'text' as const, text: `Formatted ${filePath} (${edits.length} edit(s) applied)` }] };
    },
  );

  mcp.tool('format_range', 'Format a range of lines in a Scala file.',
    { filePath: z.string().describe('Absolute path to the file'), startLine: z.number().describe('Start line (1-indexed)'), endLine: z.number().describe('End line (1-indexed)') },
    async ({ filePath, startLine, endLine }) => {
      const uri = await fileManager.ensureOpen(filePath);
      const edits = await lsp.request<TextEdit[]>('textDocument/rangeFormatting', {
        textDocument: { uri }, range: { start: toPosition(startLine, 1), end: toPosition(endLine, 999) }, options: { tabSize: 2, insertSpaces: true },
      } as DocumentRangeFormattingParams);
      if (!edits || edits.length === 0) return { content: [{ type: 'text' as const, text: 'Range is already formatted.' }] };
      applyWorkspaceEdit({ changes: { [uri]: edits } });
      fileManager.notifySaved(uri);
      return { content: [{ type: 'text' as const, text: `Formatted lines ${startLine}-${endLine} (${edits.length} edit(s))` }] };
    },
  );

  mcp.tool('organize_imports', 'Remove unused imports and sort remaining imports in a Scala file.',
    { filePath: z.string().describe('Absolute path to the file') },
    async ({ filePath }) => {
      const uri = await fileManager.ensureOpen(filePath);
      await lsp.request<unknown>('workspace/executeCommand', {
        command: 'scala.organizeImports', arguments: [{ uri }],
      } as ExecuteCommandParams);
      fileManager.notifySaved(uri);
      return { content: [{ type: 'text' as const, text: `Organized imports in ${filePath}` }] };
    },
  );
}
