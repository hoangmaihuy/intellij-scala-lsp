# MCP Server Architecture

## Overview

The MCP server is a Node.js process that bridges Claude Code to the intellij-scala-lsp daemon. It translates MCP tool calls into LSP requests over a persistent TCP connection, giving Claude Code access to 22 tools backed by IntelliJ's Scala analysis engine.

```
Claude Code
    | (MCP over stdio)
    v
┌─────────────────────────────────────────────────────────┐
│  MCP Server (Node.js)                                   │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐ │
│  │  McpServer    │  │  LspClient   │  │  FileManager  │ │
│  │  (stdio)      │  │  (TCP:5007)  │  │  (mtime track)│ │
│  └──────┬───────┘  └──────┬───────┘  └───────────────┘ │
│         │                 │                              │
│  ┌──────▼───────┐  ┌─────▼────────┐  ┌───────────────┐ │
│  │  22 MCP Tools │  │SymbolResolver│  │  Diagnostics  │ │
│  │  (6 groups)   │  │              │  │  Cache        │ │
│  └──────────────┘  └──────────────┘  └───────────────┘ │
└───────────────────────────┬─────────────────────────────┘
                            │ (LSP JSON-RPC over TCP)
                            v
                    DaemonServer (existing JVM)
                            │
                    ScalaLspServer → IntelliJ Platform
```

## Startup Sequence

1. **Daemon detection.** Read port from `~/.cache/intellij-scala-lsp/daemon.port`, try a TCP probe. If the daemon isn't running, spawn `intellij-scala-lsp --daemon` as a detached process and poll the port file (up to 60s).
2. **TCP connect.** Open a persistent TCP socket to the daemon port.
3. **Register handlers.** Set up notification and server-to-client request handlers on the `LspClient` before initializing — `publishDiagnostics`, `workspace/applyEdit`, `window/showMessage`, etc.
4. **LSP initialize.** Send `initialize` with `rootUri` from `process.cwd()` (Claude Code's working directory). The daemon opens the project and enters smart mode.
5. **Register MCP tools.** All 22 tools are registered on the `McpServer`.
6. **Serve.** Connect the `McpServer` to `StdioServerTransport` and begin accepting MCP tool calls.

## Module Structure

```
mcp-server/src/
├── index.ts              Entry point: daemon auto-start, wiring, shutdown
├── lsp-client.ts         TCP connection, JSON-RPC framing, request routing
├── file-manager.ts       Open file tracking with mtime-based staleness
├── diagnostics-cache.ts  Cache for push diagnostics with wait-for support
├── symbol-resolver.ts    workspace/symbol → Location resolution
├── workspace-edit.ts     Apply WorkspaceEdit to disk files
├── logger.ts             Stderr logger with LOG_LEVEL control
├── utils.ts              URI conversion, position mapping, text helpers
└── tools/
    ├── register.ts       Wires all tool groups to the McpServer
    ├── navigation.ts     definition, references, implementations, type_definition
    ├── display.ts        hover, diagnostics, document_symbols, inlay_hints, code_lens
    ├── editing.ts        rename_symbol, code_actions, apply_code_action, completion, signature_help
    ├── hierarchy.ts      supertypes, subtypes, incoming_calls, outgoing_calls
    ├── formatting.ts     format_file, format_range, organize_imports
    └── workspace.ts      workspace_symbols
```

## LspClient

`LspClient` manages the TCP connection to the daemon and implements LSP's JSON-RPC framing (`Content-Length` headers). It handles three message flows:

**Client → Server requests** (`request<T>(method, params)`): Assigns an auto-incrementing ID, sends the message, returns a `Promise<T>` that resolves when the daemon responds. Times out after 30s.

**Client → Server notifications** (`notify(method, params)`): Fire-and-forget messages (e.g., `didOpen`, `didChange`, `initialized`).

**Server → Client requests/notifications**: The daemon can send requests (`workspace/applyEdit`, `client/registerCapability`) and notifications (`publishDiagnostics`, `window/showMessage`). Handlers are registered via `onRequest()` and `onNotification()`.

The client includes a `reconnect(port, rootUri)` method that closes the current connection, re-connects, and re-initializes the LSP session.

## FileManager

Tracks open files as a map of `URI → { version, mtime }`. Before any tool touches a file, it calls `ensureOpen(filePath)`:

1. **Not yet open** — reads file content, records `mtime`, sends `textDocument/didOpen`.
2. **Open but stale** — compares current `mtime` with recorded. If changed (Claude Code edited the file via its Edit tool between MCP calls), reads fresh content, increments version, sends `textDocument/didChange` and `workspace/didChangeWatchedFiles` so IntelliJ refreshes its VFS.
3. **Open and current** — no-op, returns the URI.

After workspace edits are applied to disk (`rename_symbol`, `apply_code_action`), `notifySaved(uri)` updates the recorded mtime and sends `textDocument/didSave`.

On shutdown, sends `textDocument/didClose` for all tracked files.

## DiagnosticsCache

Registers a handler for `textDocument/publishDiagnostics` and caches diagnostics per-URI. The `diagnostics` tool reads from this cache.

When `waitFor(uri)` is called:
- If the URI is already in the cache (even with an empty array — meaning the file was analyzed and has no errors), returns immediately.
- If the URI has never been seen (no notification received yet), waits up to 5s for a `publishDiagnostics` notification to arrive, then returns whatever is cached.

## SymbolResolver

Translates symbol names (e.g., `"ShapeService"`, `"Shape.area"`) into LSP `Location`s via `workspace/symbol`:

1. Send `workspace/symbol` with the query.
2. Filter results for exact matches — handles qualified names (`MyClass.myMethod`), method suffixes (`.method`, `::method`), and simple names.
3. Open each matched file via `FileManager`.
4. Return an array of `ResolvedSymbol` with name, kind, container, and location.

Used by all symbol-name-based tools (definition, references, implementations, supertypes, subtypes, incoming_calls, outgoing_calls, type_definition).

## WorkspaceEdit Applicator

Applies `WorkspaceEdit` objects to disk. Handles both `changes` (URI → TextEdit[]) and `documentChanges` (TextDocumentEdit, CreateFile, RenameFile, DeleteFile).

Text edits are sorted in reverse document order (bottom-up) before application to preserve offsets. Line endings (`\n` vs `\r\n`) are detected from the existing file and preserved.

## Tools

### Tool Categories

**Symbol-name-based** — accept `symbolName`, resolve via `SymbolResolver`, then call LSP:

| Tool | LSP Method(s) | Description |
|------|--------------|-------------|
| `definition` | `workspace/symbol` → `textDocument/definition` → `textDocument/documentSymbol` | Find and read source code of a symbol's definition |
| `references` | `workspace/symbol` → `textDocument/references` | Find all usages across the project |
| `implementations` | `workspace/symbol` → `textDocument/implementation` | Find all implementations of a trait/class/method |
| `type_definition` | `workspace/symbol` → `textDocument/typeDefinition` | Navigate from a symbol to its type's definition |
| `supertypes` | `workspace/symbol` → `typeHierarchy/prepare` → `typeHierarchy/supertypes` | Get parent classes/traits |
| `subtypes` | `workspace/symbol` → `typeHierarchy/prepare` → `typeHierarchy/subtypes` | Get subclasses/implementations |
| `incoming_calls` | `workspace/symbol` → `callHierarchy/prepare` → `callHierarchy/incomingCalls` | Find all callers |
| `outgoing_calls` | `workspace/symbol` → `callHierarchy/prepare` → `callHierarchy/outgoingCalls` | Find all callees |

**File+position-based** — accept `filePath`, `line`, `column` (1-indexed, converted to 0-indexed internally):

| Tool | LSP Method | Description |
|------|-----------|-------------|
| `hover` | `textDocument/hover` | Type info + ScalaDoc at position |
| `rename_symbol` | `textDocument/rename` | Rename across all usages, applies edits to disk |
| `code_actions` | `textDocument/codeAction` | List available quick fixes/refactorings |
| `apply_code_action` | `codeAction/resolve` | Apply a code action by index (from prior `code_actions` call) |
| `completion` | `textDocument/completion` + `completionItem/resolve` | Code completions with documentation |
| `signature_help` | `textDocument/signatureHelp` | Parameter info for method calls |
| `inlay_hints` | `textDocument/inlayHint` | Inferred type hints for a range |

**File-based** — accept `filePath`:

| Tool | LSP Method | Description |
|------|-----------|-------------|
| `diagnostics` | cached from `publishDiagnostics` | Errors and warnings |
| `document_symbols` | `textDocument/documentSymbol` | File outline (hierarchical) |
| `format_file` | `textDocument/formatting` | Format entire file |
| `format_range` | `textDocument/rangeFormatting` | Format a line range |
| `organize_imports` | `workspace/executeCommand` (`scala.organizeImports`) | Remove unused, sort imports |
| `code_lens` | `textDocument/codeLens` | Code lens annotations |

**Workspace-level**:

| Tool | LSP Method | Description |
|------|-----------|-------------|
| `workspace_symbols` | `workspace/symbol` | Search symbols across the project |

### Definition Tool — Source Extraction

The `definition` tool returns the full source code of a definition, not just a location:

1. Resolve symbol via `workspace/symbol`.
2. Call `textDocument/definition` at the symbol's location.
3. If `textDocument/definition` returns empty (already at the definition site), fall back to the symbol's own location.
4. Call `textDocument/documentSymbol` on the target file to find the enclosing symbol's full range.
5. Read source lines from disk for that range, add line numbers, return.

### Code Action Two-Phase Flow

1. `code_actions` calls `textDocument/codeAction`, caches the returned `CodeAction` objects.
2. `apply_code_action` takes an index, retrieves the cached action, calls `codeAction/resolve` to get the `WorkspaceEdit`, and applies it to disk.

The cache is replaced entirely on each `code_actions` call.

## Server-to-Client Handlers

| Method | Handler |
|--------|---------|
| `workspace/applyEdit` | Applies the workspace edit to disk, sends `didSave` for modified files |
| `workspace/configuration` | Returns `[{}]` (empty config) |
| `client/registerCapability` | Acknowledges (returns `null`) |
| `window/showMessage` | Logs at info level |
| `window/logMessage` | Logs at debug level |
| `textDocument/publishDiagnostics` | Cached by `DiagnosticsCache` |

## Shutdown

When Claude Code terminates the MCP server (SIGINT/SIGTERM):

1. `FileManager.closeAll()` sends `textDocument/didClose` for all tracked files.
2. `LspClient.shutdown()` sends `shutdown` request + `exit` notification to the daemon.
3. The daemon closes this LSP session but stays alive for other clients.

## Configuration

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `LOG_LEVEL` | Log verbosity: `debug`, `info`, `warn`, `error` | `info` |

The MCP server is configured in `~/.claude/settings.json` under `mcpServers`:

```json
{
  "mcpServers": {
    "intellij-scala-lsp": {
      "command": "node",
      "args": ["/path/to/mcp-server/dist/index.js"]
    }
  }
}
```

Set up via `intellij-scala-lsp --setup-claude-code-mcp`.

## Dependencies

| Package | Purpose |
|---------|---------|
| `@modelcontextprotocol/sdk` | MCP server framework (stdio transport) |
| `vscode-languageserver-protocol` | LSP type definitions only (Position, Location, etc.) |
| `zod` | Tool parameter schema validation (used by MCP SDK) |
