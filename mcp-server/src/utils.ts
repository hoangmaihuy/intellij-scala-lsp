import { Position, Range, Location } from 'vscode-languageserver-protocol';
import * as fs from 'fs';
import * as path from 'path';

/** Convert absolute file path to file:// URI */
export function pathToUri(filePath: string): string {
  const abs = path.resolve(filePath);
  return `file://${abs}`;
}

/** Convert file:// URI to absolute file path */
export function uriToPath(uri: string): string {
  return decodeURIComponent(uri.replace('file://', ''));
}

/** Convert 1-indexed line/column (MCP tool params) to 0-indexed LSP Position */
export function toPosition(line: number, column: number): Position {
  return Position.create(line - 1, column - 1);
}

/** Convert 0-indexed LSP Position to 1-indexed line/column */
export function fromPosition(pos: Position): { line: number; column: number } {
  return { line: pos.line + 1, column: pos.character + 1 };
}

/** Extract text from a file for a given LSP Range */
export function extractTextFromRange(filePath: string, range: Range): string {
  const content = fs.readFileSync(filePath, 'utf-8');
  const lines = content.split('\n');
  const startLine = range.start.line;
  const endLine = range.end.line;

  if (startLine === endLine) {
    return lines[startLine].substring(range.start.character, range.end.character);
  }

  const result: string[] = [];
  result.push(lines[startLine].substring(range.start.character));
  for (let i = startLine + 1; i < endLine; i++) {
    result.push(lines[i]);
  }
  result.push(lines[endLine].substring(0, range.end.character));
  return result.join('\n');
}

/** Add line numbers to text, starting from the given 1-indexed line */
export function addLineNumbers(text: string, startLine: number): string {
  const lines = text.split('\n');
  const lastLineNum = startLine + lines.length - 1;
  const padding = String(lastLineNum).length;
  return lines
    .map((line, i) => `${String(startLine + i).padStart(padding)}|${line}`)
    .join('\n');
}

/** Format a Location for human-readable output */
export function formatLocation(loc: Location): string {
  const file = uriToPath(loc.uri);
  const start = fromPosition(loc.range.start);
  const end = fromPosition(loc.range.end);
  return `${file}:L${start.line}:C${start.column}-L${end.line}:C${end.column}`;
}
