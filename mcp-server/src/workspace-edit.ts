import * as fs from 'fs';
import {
  WorkspaceEdit, TextEdit, TextDocumentEdit,
  CreateFile, RenameFile, DeleteFile,
} from 'vscode-languageserver-protocol';
import { uriToPath } from './utils.js';
import { logger } from './logger.js';

export function applyWorkspaceEdit(edit: WorkspaceEdit): string[] {
  const modifiedUris: string[] = [];

  if (edit.changes) {
    for (const [uri, edits] of Object.entries(edit.changes)) {
      applyTextEdits(uri, edits);
      modifiedUris.push(uri);
    }
  }

  if (edit.documentChanges) {
    for (const change of edit.documentChanges) {
      if ('textDocument' in change && 'edits' in change) {
        const tde = change as TextDocumentEdit;
        const edits = tde.edits.map(e => 'range' in e ? e as TextEdit : e as TextEdit);
        applyTextEdits(tde.textDocument.uri, edits);
        modifiedUris.push(tde.textDocument.uri);
      } else if ('kind' in change) {
        if (change.kind === 'create') {
          const c = change as CreateFile;
          const p = uriToPath(c.uri);
          if (!c.options?.ignoreIfExists || !fs.existsSync(p)) {
            fs.writeFileSync(p, '', 'utf-8');
          }
          modifiedUris.push(c.uri);
        } else if (change.kind === 'rename') {
          const r = change as RenameFile;
          fs.renameSync(uriToPath(r.oldUri), uriToPath(r.newUri));
          modifiedUris.push(r.newUri);
        } else if (change.kind === 'delete') {
          const d = change as DeleteFile;
          const p = uriToPath(d.uri);
          if (d.options?.recursive) {
            fs.rmSync(p, { recursive: true });
          } else {
            fs.unlinkSync(p);
          }
        }
      }
    }
  }

  return modifiedUris;
}

function applyTextEdits(uri: string, edits: TextEdit[]): void {
  const filePath = uriToPath(uri);
  const content = fs.readFileSync(filePath, 'utf-8');
  const lineEnding = content.includes('\r\n') ? '\r\n' : '\n';
  const endsWithNewline = content.endsWith(lineEnding);
  let lines = content.split(lineEnding);

  const sorted = [...edits].sort((a, b) => {
    if (a.range.start.line !== b.range.start.line) {
      return b.range.start.line - a.range.start.line;
    }
    return b.range.start.character - a.range.start.character;
  });

  for (const edit of sorted) {
    lines = applySingleEdit(lines, edit);
  }

  let result = lines.join(lineEnding);
  if (endsWithNewline && !result.endsWith(lineEnding)) {
    result += lineEnding;
  }
  fs.writeFileSync(filePath, result, 'utf-8');
  logger.debug(`Applied ${edits.length} edits to ${filePath}`);
}

function applySingleEdit(lines: string[], edit: TextEdit): string[] {
  const startLine = edit.range.start.line;
  const endLine = Math.min(edit.range.end.line, lines.length - 1);
  const startChar = edit.range.start.character;
  const endChar = edit.range.end.character;

  const prefix = lines[startLine].substring(0, startChar);
  const suffix = lines[endLine].substring(endChar);
  const newLines = edit.newText.split('\n');

  const result = lines.slice(0, startLine);

  if (newLines.length === 1) {
    result.push(prefix + newLines[0] + suffix);
  } else {
    result.push(prefix + newLines[0]);
    for (let i = 1; i < newLines.length - 1; i++) {
      result.push(newLines[i]);
    }
    result.push(newLines[newLines.length - 1] + suffix);
  }

  result.push(...lines.slice(endLine + 1));
  return result;
}
