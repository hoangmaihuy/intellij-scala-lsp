# MCP Tool Consolidation Design

**Date:** 2026-03-22
**Status:** Draft
**Goal:** Reduce 22 MCP tools to 10, optimized for Claude Code experience.

## Problem

The current MCP server exposes 22 tools. This causes two issues for Claude Code:

1. **Too many tools** — Claude gets confused picking the right one from the large tool list.
2. **Too many round-trips** — Tools return location pointers instead of source code, forcing Claude to chain 3-4 calls (e.g., workspace_symbols → definition → read file) to get what it needs.

**Example:** When searching for `TableL`'s definition, Claude currently calls `workspace_symbols` (gets a list), then `definition` (returns a location, not code), then falls back to `references` and `implementations`. This should be a single call.

## Reference

Inspired by [mcp-language-server](https://github.com/isaacphi/mcp-language-server), which wraps any LSP server with just 6 tools. Its key insight: `definition("TableL")` returns the **full source code** inline, not a file:line pointer.

## Design Principles

1. **Discovery tools return source code**, not location pointers — eliminates read-file round-trips.
2. **Symbol-name input only** for discovery tools — no dual-mode (symbol name OR file+position) confusion.
3. **File+position input** for mutation tools — Claude already has the file open when mutating.
4. **Each tool is self-contained** — Claude gets what it needs in 1-2 calls, not 3-4.
5. **Plain-text output** with file paths and line numbers — optimized for LLM consumption, no JSON wrapping.

## Tool Inventory: 22 → 10

### Kept & Enhanced (5 tools)

| Tool | Enhancement |
|------|-------------|
| `definition` | Returns full source code with line numbers, not just a location |
| `references` | Returns context lines (±2) around each usage, grouped by file |
| `implementations` | Returns source code of each implementation |
| `hover` | Absorbs `type_definition`, `supertypes`/`subtypes`, `signature_help` into one response |
| `diagnostics` | Unchanged |

### Kept As-Is (5 tools)

| Tool | Notes |
|------|-------|
| `workspace_symbols` | Unchanged |
| `document_symbols` | Unchanged |
| `rename_symbol` | File+position based, unchanged |
| `code_actions` | Unchanged |
| `apply_code_action` | Unchanged |

### Dropped (12 tools)

| Tool | Reason |
|------|--------|
| `type_definition` | Merged into `hover` |
| `supertypes` | Merged into `hover` |
| `subtypes` | Merged into `hover` |
| `signature_help` | Merged into `hover` |
| `incoming_calls` | Rarely used; `references` covers the common case |
| `outgoing_calls` | Rarely used; read the function body instead |
| `completion` | Claude writes code directly, doesn't need LSP completion |
| `format_file` | Claude can use `code_actions` or edit directly |
| `format_range` | Claude can use `code_actions` or edit directly |
| `organize_imports` | Claude can use `code_actions` or edit directly |
| `inlay_hints` | Display-only, not useful for Claude |
| `code_lens` | Display-only, not useful for Claude |

## Tool Specifications

### Discovery Tools (symbol-name based)

#### 1. `definition`

- **Description:** Read the source code where a symbol is defined. Returns full implementation with line numbers.
- **Input:** `{ symbolName: string }`
- **Output:** Full source code of the symbol with line numbers, file path, and symbol kind. If multiple matches (e.g., companion object + class), return all.
- **LSP calls:** `workspace/symbol` → `textDocument/definition` → `textDocument/documentSymbol` (to get full range) → read source
- **Example:**

```
definition("TableL")
→
// src/main/scala/com/example/TableL.scala (lines 15-42, class)
15| case class TableL(
16|   name: String,
17|   columns: List[Column],
...
42| }
```

#### 2. `references`

- **Description:** Find all usages of a symbol across the codebase. Returns locations with surrounding context.
- **Input:** `{ symbolName: string }`
- **Output:** All usages grouped by file, with ±2 context lines around each reference. Shows total count and file count.
- **LSP calls:** `workspace/symbol` → `textDocument/references`
- **Example:**

```
references("TableL")
→
Found 7 references in 3 files

// src/main/scala/Service.scala
23|   val table = TableL("users", cols)
         ^^^^^

// src/main/scala/Repository.scala
45|   def save(t: TableL): Unit = {
                  ^^^^^^
67|   tables.collect { case t: TableL => t }
                            ^^^^^^
```

#### 3. `implementations`

- **Description:** Find all implementations of a trait, class, or abstract method. Returns source code of each implementation.
- **Input:** `{ symbolName: string }`
- **Output:** Source code of each implementation, same format as `definition`.
- **LSP calls:** `workspace/symbol` → `textDocument/implementation` → `textDocument/documentSymbol` (per impl) → read source

#### 4. `hover`

- **Description:** Get complete info about a symbol: type signature, documentation, supertypes, subtypes.
- **Input:** `{ symbolName: string }`
- **Output:** Combined view with type signature, ScalaDoc, supertypes, subtypes, and method signatures (if class/trait/object).
- **LSP calls:** `workspace/symbol` → `textDocument/hover` + `typeHierarchy/supertypes` + `typeHierarchy/subtypes`
- **Example:**

```
hover("TableL")
→
case class TableL(name: String, columns: List[Column])

Supertypes: Product, Serializable
Subtypes: none
Defined in: com.example.schema

/**
 * Represents a logical table in the schema.
 * @param name table identifier
 * @param columns ordered list of columns
 */
```

### File-Based Tools

#### 5. `diagnostics`

- **Description:** Get errors and warnings for a file from the Scala analysis engine.
- **Input:** `{ filePath: string }`
- **Output:** Errors and warnings with severity, line number, message, and context line.

#### 6. `workspace_symbols`

- **Description:** Search for symbols (classes, methods, vals) across the entire project by name.
- **Input:** `{ query: string }`
- **Output:** Matching symbols with kind, container, file:line.

#### 7. `document_symbols`

- **Description:** List all symbols in a file with their kinds and line ranges.
- **Input:** `{ filePath: string }`
- **Output:** Hierarchical symbol outline with kinds and line ranges.

### Mutation Tools (file+position based)

#### 8. `rename_symbol`

- **Description:** Rename a symbol at a position and update all references across the codebase.
- **Input:** `{ filePath: string, line: number, column: number, newName: string }`
- **Output:** Summary of files changed and occurrence count.

#### 9. `code_actions`

- **Description:** Get available quick fixes and refactorings for a code range.
- **Input:** `{ filePath: string, startLine: number, startColumn: number, endLine: number, endColumn: number }`
- **Output:** Numbered list of available actions with descriptions.

#### 10. `apply_code_action`

- **Description:** Apply a code action from the most recent code_actions result.
- **Input:** `{ filePath: string, actionIndex: number }`
- **Output:** Summary of edits applied.

## Output Format Conventions

### Source code output (definition, implementations)

```
// path/to/File.scala (lines 15-42, class)
15| case class TableL(
16|   name: String,
17|   columns: List[Column],
...
42| }
```

### Reference output (references)

```
Found 7 references in 3 files

// src/main/scala/Service.scala
23|   val table = TableL("users", cols)
         ^^^^^

// src/main/scala/Repository.scala
45|   def save(t: TableL): Unit = {
                  ^^^^^^
67|   tables.collect { case t: TableL => t }
                            ^^^^^^
```

### Hover output (hover)

```
case class TableL(name: String, columns: List[Column])

Supertypes: Product, Serializable
Subtypes: none
Defined in: com.example.schema

/**
 * Represents a logical table in the schema.
 */
```

### Rules

- Always include file paths (relative to project root when possible)
- Always include line numbers in source code
- Symbol kind in parentheses (class, trait, object, def, val)
- No JSON wrapping — plain text optimized for LLM readability
- Group references by file
- Show ±2 context lines around references

## Implementation Notes

### Symbol Resolution

All discovery tools (definition, references, implementations, hover) share the same symbol resolution path:

1. Call `workspace/symbol` with the symbol name
2. Filter results using smart matching (exact match, companion object `Foo$` → `Foo`, qualified `Foo.bar`)
3. Open the file via FileManager
4. Call the appropriate LSP method at the resolved position

The existing `SymbolResolver` class handles this. No changes needed to the resolution logic.

### Source Code Extraction

For `definition` and `implementations`, after getting the LSP location, we need to extract the full symbol body:

1. Call `textDocument/documentSymbol` on the target file
2. Find the symbol whose range contains the definition position
3. Read the source code for that range
4. Format with line numbers

### Hover Enrichment

The new `hover` tool makes 3 parallel LSP calls after resolving the symbol:

1. `textDocument/hover` — type signature + docs
2. `typeHierarchy/prepare` → `typeHierarchy/supertypes` — parent types
3. `typeHierarchy/prepare` → `typeHierarchy/subtypes` — child types

Results are combined into a single formatted response.

### Files to Modify

- `mcp-server/src/tools/navigation.ts` — Rewrite: 4 tools → 3 tools (definition, references, implementations), all returning source code
- `mcp-server/src/tools/display.ts` — Rewrite: 5 tools → 3 tools (hover, diagnostics, document_symbols)
- `mcp-server/src/tools/editing.ts` — Remove completion and signature_help, keep rename/code_actions/apply_code_action
- `mcp-server/src/tools/workspace.ts` — Unchanged
- `mcp-server/src/tools/hierarchy.ts` — Delete (supertypes/subtypes merged into hover, calls dropped)
- `mcp-server/src/tools/formatting.ts` — Delete
- `mcp-server/src/tools/register.ts` — Update registration to match new tool set
