# MCP Tools

The MCP server exposes 12 tools that translate to LSP requests against the intellij-scala-lsp daemon. All line and column numbers are **1-indexed**.

## Navigation Tools

These accept either `symbolName` or `filePath`+`line`+`column`. Prefer `symbolName`; use file+position for external/library symbols or to disambiguate overloads.

| Tool | Description |
|------|-------------|
| `definition` | Read the source code where a symbol is defined. Returns full implementation with line numbers. |
| `references` | Find all usages of a symbol across the codebase. Returns locations with surrounding context. |
| `implementations` | Find all implementations of a trait, class, or abstract method. Returns source code (up to 10 in full). |

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `symbolName` | string, optional | Symbol name (e.g. `"MyClass"`, `"MyClass.myMethod"`) |
| `filePath` | string, optional | Absolute path to the file (use with line+column) |
| `line` | number, optional | Line number, 1-indexed (use with filePath+column) |
| `column` | number, optional | Column number, 1-indexed (use with filePath+line) |

### How `definition` works

Returns full source code, not just a location:

1. Resolve symbol via `workspace/symbol` (or use provided position)
2. Call `textDocument/definition` at the resolved location
3. Call `textDocument/documentSymbol` on the target file to find the enclosing symbol's full range
4. Read and return source lines with line numbers

## Display Tools

### `hover`

Get complete info about a symbol: type signature, documentation, supertypes, subtypes, and type definition location.

Accepts either `symbolName` or `filePath`+`line`+`column`.

| Parameter | Type | Description |
|-----------|------|-------------|
| `symbolName` | string, optional | Symbol name |
| `filePath` | string, optional | Absolute path to the file |
| `line` | number, optional | Line number, 1-indexed |
| `column` | number, optional | Column number, 1-indexed |

Internally combines `textDocument/hover`, `textDocument/typeDefinition`, and `typeHierarchy/supertypes`+`subtypes` into a single response.

### `diagnostics`

Get errors and warnings for a file from the Scala analysis engine.

| Parameter | Type | Description |
|-----------|------|-------------|
| `filePath` | string | Absolute path to the file |

Diagnostics are cached from `textDocument/publishDiagnostics` notifications. After opening a file for the first time, the tool waits up to 5s for the daemon to analyze the file before returning results.

### `document_symbols`

List all symbols (classes, methods, vals, etc.) in a file as a hierarchical outline.

| Parameter | Type | Description |
|-----------|------|-------------|
| `filePath` | string | Absolute path to the file |

## Editing Tools

### `rename_symbol`

Rename a symbol at the specified position and update all references across the codebase. Applies edits to disk.

| Parameter | Type | Description |
|-----------|------|-------------|
| `filePath` | string | Absolute path to the file |
| `line` | number | Line number, 1-indexed |
| `column` | number | Column number, 1-indexed |
| `newName` | string | New name for the symbol |

### `code_actions`

Get available quick fixes, refactorings, and code actions for a range.

| Parameter | Type | Description |
|-----------|------|-------------|
| `filePath` | string | Absolute path to the file |
| `startLine` | number | Start line, 1-indexed |
| `startColumn` | number | Start column, 1-indexed |
| `endLine` | number | End line, 1-indexed |
| `endColumn` | number | End column, 1-indexed |

Returns a numbered list. Use `apply_code_action` with the index to apply one.

### `apply_code_action`

Apply a code action from the most recent `code_actions` result.

| Parameter | Type | Description |
|-----------|------|-------------|
| `filePath` | string | Absolute path to the file |
| `actionIndex` | number | Index of the action to apply (1-indexed, from `code_actions` output) |

The cache is replaced entirely on each `code_actions` call.

## Formatting Tools

### `format`

Format Scala code using IntelliJ code style. Formats the entire file, or a specific line range if `startLine` and `endLine` are provided.

| Parameter | Type | Description |
|-----------|------|-------------|
| `filePath` | string | Absolute path to the file |
| `startLine` | number, optional | Start line, 1-indexed |
| `endLine` | number, optional | End line, 1-indexed |

### `organize_imports`

Remove unused imports and sort remaining imports.

| Parameter | Type | Description |
|-----------|------|-------------|
| `filePath` | string | Absolute path to the file |

## Workspace Tools

### `workspace_symbols`

Search for symbols across the entire project.

| Parameter | Type | Description |
|-----------|------|-------------|
| `query` | string | Search query (e.g. `"MyClass"`, `"process"`) |

## LSP Method Mapping

| MCP Tool | LSP Method(s) |
|----------|--------------|
| `definition` | `workspace/symbol` → `textDocument/definition` → `textDocument/documentSymbol` |
| `references` | `workspace/symbol` → `textDocument/references` |
| `implementations` | `workspace/symbol` → `textDocument/implementation` |
| `hover` | `textDocument/hover` + `textDocument/typeDefinition` + `typeHierarchy/supertypes` + `typeHierarchy/subtypes` |
| `diagnostics` | cached from `textDocument/publishDiagnostics` |
| `document_symbols` | `textDocument/documentSymbol` |
| `rename_symbol` | `textDocument/rename` |
| `code_actions` | `textDocument/codeAction` |
| `apply_code_action` | `codeAction/resolve` |
| `format` | `textDocument/formatting` or `textDocument/rangeFormatting` |
| `organize_imports` | `workspace/executeCommand` (`scala.organizeImports`) |
| `workspace_symbols` | `workspace/symbol` |
