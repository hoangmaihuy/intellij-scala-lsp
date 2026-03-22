# MCP Tool Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce 22 MCP tools to 12, making each tool self-contained so Claude Code gets what it needs in 1-2 calls.

**Architecture:** Rewrite navigation tools to return full source code instead of locations. Merge hierarchy info into hover. Consolidate formatting tools. Delete unused tools. No changes to core infrastructure (lsp-client, file-manager, symbol-resolver, etc.).

**Tech Stack:** TypeScript, @modelcontextprotocol/sdk, vscode-languageserver-protocol, zod

**Spec:** `docs/superpowers/specs/2026-03-22-mcp-tool-consolidation-design.md`

---

### Task 1: Enhance `definition` tool to return full source code

**Files:**
- Modify: `mcp-server/src/tools/navigation.ts`

The `definition` tool already has the `findEnclosingSymbol` + `documentSymbol` logic to get full ranges (lines 73-93). The current output format is already good. The main change needed: update the tool description to match the spec.

- [ ] **Step 1: Update `definition` tool description**

In `mcp-server/src/tools/navigation.ts`, change the tool description on line 47 from:
```typescript
'Go to definition of a symbol. Provide EITHER symbolName OR filePath+line+column. Use filePath+line+column when you have a specific position (works for external/library symbols too).',
```
to:
```typescript
'Read the source code where a symbol is defined. Returns full implementation with line numbers. Prefer symbolName; use filePath+line+column for external/library symbols or to disambiguate overloaded methods.',
```

- [ ] **Step 2: Verify definition already returns source code**

The current implementation (lines 73-93) already calls `documentSymbol` to find the enclosing symbol range, reads the file, and returns source with line numbers. Confirm no code changes needed to the implementation itself.

- [ ] **Step 3: Build and verify**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`
Expected: Clean build

- [ ] **Step 4: Commit**

```bash
git add mcp-server/src/tools/navigation.ts
git commit -m "refactor(mcp): update definition tool description for clarity"
```

---

### Task 2: Enhance `references` tool description

**Files:**
- Modify: `mcp-server/src/tools/navigation.ts`

The current `references` tool already shows ±2 context lines grouped by file (lines 134-151). Just update the description.

- [ ] **Step 1: Update `references` tool description**

Change line 103 from:
```typescript
'Find all references of a symbol. Provide EITHER symbolName OR filePath+line+column.',
```
to:
```typescript
'Find all usages of a symbol across the codebase. Returns locations with surrounding context. Prefer symbolName; use filePath+line+column for external/library symbols or to disambiguate overloaded methods.',
```

- [ ] **Step 2: Build and verify**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`

- [ ] **Step 3: Commit**

```bash
git add mcp-server/src/tools/navigation.ts
git commit -m "refactor(mcp): update references tool description"
```

---

### Task 3: Enhance `implementations` to return full source code

**Files:**
- Modify: `mcp-server/src/tools/navigation.ts`

Currently `implementations` only shows 5-line snippets (line 187: `startLine + 4`). We need to use the same `findEnclosingSymbol` + `documentSymbol` approach as `definition` to return the full body. Cap at 10 full implementations.

- [ ] **Step 1: Update `implementations` tool description**

Change line 164 from:
```typescript
'Find all implementations of a trait, class, or abstract method. Provide EITHER symbolName OR filePath+line+column.',
```
to:
```typescript
'Find all implementations of a trait, class, or abstract method. Returns source code of each implementation (up to 10 in full). Prefer symbolName; use filePath+line+column for external/library symbols.',
```

- [ ] **Step 2: Rewrite implementations handler to return full source**

Replace the implementation body (lines 172-197) with code that uses `documentSymbol` to extract full symbol ranges, similar to what `definition` does. For implementations beyond 10, show just a one-line summary with file:line.

Replace the inner loop (lines 181-189):
```typescript
for (const impl of Array.isArray(impls) ? impls : [impls]) {
  const filePath = uriToPath(impl.uri);
  await fileManager.ensureOpen(filePath);
  const content = fs.readFileSync(filePath, 'utf-8');
  const lines = content.split('\n');
  const startLine = impl.range.start.line;
  const endLine = Math.min(startLine + 4, lines.length - 1);
  const snippet = addLineNumbers(lines.slice(startLine, endLine + 1).join('\n'), startLine + 1);
  allImpls.push(`---\n${filePath}:L${startLine + 1}\n\n${snippet}`);
}
```

With:
```typescript
const implArray = Array.isArray(impls) ? impls : [impls];
for (let idx = 0; idx < implArray.length; idx++) {
  const impl = implArray[idx];
  const filePath = uriToPath(impl.uri);
  await fileManager.ensureOpen(filePath);

  if (idx >= 10) {
    // Beyond 10: show just location summary
    const content = fs.readFileSync(filePath, 'utf-8');
    const firstLine = content.split('\n')[impl.range.start.line] || '';
    allImpls.push(`---\n${filePath}:L${impl.range.start.line + 1}\n${firstLine.trim()}`);
    continue;
  }

  // Get full symbol range via documentSymbol
  const docSymbols = await lsp.request<DocumentSymbol[] | SymbolInformation[]>(
    'textDocument/documentSymbol',
    { textDocument: { uri: impl.uri } } as DocumentSymbolParams,
  );

  let fullRange = impl.range;
  if (docSymbols && Array.isArray(docSymbols)) {
    const enclosing = findEnclosingSymbol(docSymbols, impl.range.start);
    if (enclosing) fullRange = enclosing;
  }

  const content = fs.readFileSync(filePath, 'utf-8');
  const lines = content.split('\n');
  const sourceLines = lines.slice(fullRange.start.line, fullRange.end.line + 1);
  const source = addLineNumbers(sourceLines.join('\n'), fullRange.start.line + 1);
  allImpls.push(`---\n${filePath}:L${fullRange.start.line + 1}-L${fullRange.end.line + 1}\n\n${source}`);
}
```

Note: `DocumentSymbolParams` import already exists on line 10. `findEnclosingSymbol` is defined at line 237 in the same file.

- [ ] **Step 3: Build and verify**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`
Expected: Clean build

- [ ] **Step 4: Commit**

```bash
git add mcp-server/src/tools/navigation.ts
git commit -m "feat(mcp): implementations tool returns full source code with 10-item cap"
```

---

### Task 4: Remove `type_definition` tool from navigation.ts

**Files:**
- Modify: `mcp-server/src/tools/navigation.ts`

- [ ] **Step 1: Delete the `type_definition` tool registration**

Remove lines 200-234 (the entire `mcp.tool('type_definition', ...)` block).

- [ ] **Step 2: Build and verify**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`

- [ ] **Step 3: Commit**

```bash
git add mcp-server/src/tools/navigation.ts
git commit -m "refactor(mcp): remove type_definition tool (merged into hover)"
```

---

### Task 5: Rewrite `hover` tool with enriched output + update register.ts

**Files:**
- Modify: `mcp-server/src/tools/display.ts`
- Modify: `mcp-server/src/tools/register.ts`

The current `hover` only calls `textDocument/hover`. The new version adds type definition location, supertypes, and subtypes. It also changes from file+position-only to dual-mode (symbolName OR file+position).

**Important:** We must also update `register.ts` in this same task to pass `symbolResolver` to `registerDisplayTools`, otherwise the build breaks.

- [ ] **Step 1: Replace the import section of display.ts (lines 1-11)**

Replace the entire import block with:
```typescript
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { LspClient } from '../lsp-client.js';
import { FileManager } from '../file-manager.js';
import { DiagnosticsCache } from '../diagnostics-cache.js';
import { SymbolResolver } from '../symbol-resolver.js';
import { uriToPath, toPosition } from '../utils.js';
import {
  HoverParams, Hover, DocumentSymbolParams, DocumentSymbol, SymbolInformation, SymbolKind,
  Location, TypeHierarchyItem, TypeHierarchyPrepareParams,
} from 'vscode-languageserver-protocol';
import * as fs from 'fs';
```

- [ ] **Step 2: Update `registerDisplayTools` signature**

Change the function signature to accept `symbolResolver`:
```typescript
export function registerDisplayTools(
  mcp: McpServer,
  lsp: LspClient,
  fileManager: FileManager,
  diagnostics: DiagnosticsCache,
  symbolResolver: SymbolResolver,
): void {
```

- [ ] **Step 3: Rewrite the `hover` tool**

Replace the entire `hover` tool registration (lines 20-52) with:

```typescript
  mcp.tool(
    'hover',
    'Get complete info about a symbol: type signature, documentation, supertypes, subtypes, and type definition location. Prefer symbolName; use filePath+line+column for positional context.',
    {
      symbolName: z.string().optional().describe('Symbol name (e.g. "MyClass", "MyClass.myMethod")'),
      filePath: z.string().optional().describe('Absolute path to the file (use with line+column)'),
      line: z.number().optional().describe('Line number, 1-indexed (use with filePath+column)'),
      column: z.number().optional().describe('Column number, 1-indexed (use with filePath+line)'),
    },
    async (args) => {
      // Resolve to URI + position
      let uri: string;
      let position: { line: number; character: number };

      if (args.filePath && args.line !== undefined && args.column !== undefined) {
        uri = await fileManager.ensureOpen(args.filePath);
        position = toPosition(args.line, args.column);
      } else if (args.symbolName) {
        const symbols = await symbolResolver.resolve(args.symbolName);
        if (symbols.length === 0) {
          return { content: [{ type: 'text' as const, text: `No symbol found matching '${args.symbolName}'. Try workspace_symbols to search.` }] };
        }
        uri = symbols[0].location.uri;
        position = symbols[0].location.range.start;
        // Ensure file is open for LSP requests
        await fileManager.ensureOpen(uriToPath(uri));
      } else {
        return { content: [{ type: 'text' as const, text: 'Provide either symbolName or filePath+line+column.' }] };
      }

      const sections: string[] = [];

      // 1. Hover info (type signature + docs)
      const hoverResult = await lsp.request<Hover | null>('textDocument/hover', {
        textDocument: { uri }, position,
      } as HoverParams);

      if (hoverResult?.contents) {
        let text: string;
        if (typeof hoverResult.contents === 'string') {
          text = hoverResult.contents;
        } else if ('value' in hoverResult.contents) {
          text = hoverResult.contents.value;
        } else if (Array.isArray(hoverResult.contents)) {
          text = hoverResult.contents.map(c => typeof c === 'string' ? c : c.value).join('\n\n');
        } else {
          text = JSON.stringify(hoverResult.contents);
        }
        sections.push(text);
      }

      // 2. Type definition location (best-effort)
      try {
        const typeDef = await lsp.request<Location | Location[]>('textDocument/typeDefinition', {
          textDocument: { uri }, position,
        });
        const locs = Array.isArray(typeDef) ? typeDef : typeDef ? [typeDef] : [];
        if (locs.length > 0) {
          const typeDefPaths = locs.map(l => `${uriToPath(l.uri)}:${l.range.start.line + 1}`);
          sections.push(`Type definition: ${typeDefPaths.join(', ')}`);
        }
      } catch { /* best-effort */ }

      // 3. Supertypes + Subtypes (best-effort, single prepare call)
      try {
        const hierarchyItems = await lsp.request<TypeHierarchyItem[]>('textDocument/prepareTypeHierarchy', {
          textDocument: { uri }, position,
        } as TypeHierarchyPrepareParams);

        if (hierarchyItems && hierarchyItems.length > 0) {
          // Supertypes
          try {
            const allSupers: string[] = [];
            for (const item of hierarchyItems) {
              const supers = await lsp.request<TypeHierarchyItem[]>('typeHierarchy/supertypes', { item });
              if (supers) for (const s of supers) allSupers.push(s.name);
            }
            if (allSupers.length > 0) sections.push(`Supertypes: ${allSupers.join(', ')}`);
          } catch { /* best-effort */ }

          // Subtypes
          try {
            const allSubs: string[] = [];
            for (const item of hierarchyItems) {
              const subs = await lsp.request<TypeHierarchyItem[]>('typeHierarchy/subtypes', { item });
              if (subs) for (const s of subs) allSubs.push(s.name);
            }
            if (allSubs.length > 0) sections.push(`Subtypes: ${allSubs.join(', ')}`);
          } catch { /* best-effort */ }
        }
      } catch { /* best-effort: prepareTypeHierarchy not supported */ }

      if (sections.length === 0) {
        return { content: [{ type: 'text' as const, text: `No hover information for '${args.symbolName || `${args.filePath}:${args.line}:${args.column}`}'` }] };
      }

      return { content: [{ type: 'text' as const, text: sections.join('\n\n') }] };
    },
  );
```

- [ ] **Step 4: Update register.ts to pass symbolResolver to registerDisplayTools**

In `mcp-server/src/tools/register.ts`, change line 21 from:
```typescript
  registerDisplayTools(mcp, lsp, fileManager, diagnostics);
```
to:
```typescript
  registerDisplayTools(mcp, lsp, fileManager, diagnostics, symbolResolver);
```

- [ ] **Step 5: Build and verify**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`
Expected: Clean build

- [ ] **Step 6: Commit**

```bash
git add mcp-server/src/tools/display.ts mcp-server/src/tools/register.ts
git commit -m "feat(mcp): enrich hover with type definition, supertypes, subtypes"
```

---

### Task 6: Remove `inlay_hints` and `code_lens` from display.ts

**Files:**
- Modify: `mcp-server/src/tools/display.ts`

- [ ] **Step 1: Delete `inlay_hints` tool**

Search for `mcp.tool('inlay_hints'` in display.ts and remove the entire tool registration block. (Line numbers have shifted after Task 5's hover rewrite — find by tool name, not line number.)

- [ ] **Step 2: Delete `code_lens` tool**

Search for `mcp.tool('code_lens'` in display.ts and remove the entire tool registration block.

- [ ] **Step 3: Remove unused imports**

The imports were already cleaned up in Task 5 Step 1 (which replaced the import block without `InlayHint` or `CodeLens`). Verify no unused imports remain.

- [ ] **Step 4: Build and verify**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`

- [ ] **Step 5: Commit**

```bash
git add mcp-server/src/tools/display.ts
git commit -m "refactor(mcp): remove inlay_hints and code_lens tools"
```

---

### Task 7: Remove `completion` and `signature_help` from editing.ts

**Files:**
- Modify: `mcp-server/src/tools/editing.ts`

- [ ] **Step 1: Delete `completion` tool**

Remove the `mcp.tool('completion', ...)` block (lines 142-161).

- [ ] **Step 2: Delete `signature_help` tool**

Remove the `mcp.tool('signature_help', ...)` block (lines 164-193).

- [ ] **Step 3: Remove unused imports**

Remove `CompletionItem`, `CompletionList`, and `SignatureHelp` from the vscode-languageserver-protocol import on line 9.

- [ ] **Step 4: Build and verify**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`

- [ ] **Step 5: Commit**

```bash
git add mcp-server/src/tools/editing.ts
git commit -m "refactor(mcp): remove completion and signature_help tools"
```

---

### Task 8: Consolidate formatting tools (format_file + format_range → format)

**Files:**
- Modify: `mcp-server/src/tools/formatting.ts`

- [ ] **Step 1: Rewrite formatting.ts**

Replace the entire file content with:

```typescript
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { LspClient } from '../lsp-client.js';
import { FileManager } from '../file-manager.js';
import { applyWorkspaceEdit } from '../workspace-edit.js';
import { toPosition } from '../utils.js';
import { DocumentFormattingParams, DocumentRangeFormattingParams, TextEdit, ExecuteCommandParams } from 'vscode-languageserver-protocol';

export function registerFormattingTools(mcp: McpServer, lsp: LspClient, fileManager: FileManager): void {
  mcp.tool('format', 'Format Scala code using IntelliJ code style. Formats the entire file, or a specific line range if startLine and endLine are provided.',
    {
      filePath: z.string().describe('Absolute path to the file'),
      startLine: z.number().optional().describe('Start line (1-indexed, optional)'),
      endLine: z.number().optional().describe('End line (1-indexed, optional)'),
    },
    async ({ filePath, startLine, endLine }) => {
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
```

- [ ] **Step 2: Build and verify**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`

- [ ] **Step 3: Commit**

```bash
git add mcp-server/src/tools/formatting.ts
git commit -m "refactor(mcp): merge format_file + format_range into single format tool"
```

---

### Task 9: Delete hierarchy.ts and clean up register.ts

**Files:**
- Delete: `mcp-server/src/tools/hierarchy.ts`
- Modify: `mcp-server/src/tools/register.ts`

Note: Task 5 already updated the `registerDisplayTools` call in register.ts to pass `symbolResolver`. This task only needs to remove the hierarchy import and call.

- [ ] **Step 1: Delete hierarchy.ts**

```bash
rm mcp-server/src/tools/hierarchy.ts
```

- [ ] **Step 2: Remove hierarchy import and call from register.ts**

Remove the import line:
```typescript
import { registerHierarchyTools } from './hierarchy.js';
```

Remove the call:
```typescript
  registerHierarchyTools(mcp, lsp, fileManager, symbolResolver);
```

- [ ] **Step 3: Build and verify**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`
Expected: Clean build with no errors

- [ ] **Step 4: Commit**

```bash
git add -A mcp-server/src/tools/hierarchy.ts mcp-server/src/tools/register.ts
git commit -m "refactor(mcp): delete hierarchy tools, update tool registration (22 → 12 tools)"
```

---

### Task 10: Smoke test the full MCP server

**Files:** None (testing only)

- [ ] **Step 1: Build the full project**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`
Expected: Clean build, no errors

- [ ] **Step 2: Verify tool count by listing registered tools**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && grep "mcp.tool(" src/tools/*.ts | wc -l`
Expected: 12 lines (definition, references, implementations, hover, diagnostics, document_symbols, rename_symbol, code_actions, apply_code_action, workspace_symbols, format, organize_imports)

- [ ] **Step 3: Verify no references to deleted tools remain**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && grep -r "type_definition\|supertypes\|subtypes\|incoming_calls\|outgoing_calls\|completion\|signature_help\|inlay_hints\|code_lens\|format_file\|format_range\|hierarchy" src/`
Expected: No matches (except possibly in comments or unrelated strings)

- [ ] **Step 4: Final commit if any cleanup needed**

```bash
git add -A mcp-server/
git commit -m "chore(mcp): final cleanup after tool consolidation"
```
