# intellij-scala-lsp

An LSP server that runs IntelliJ IDEA headless + the [intellij-scala](https://github.com/JetBrains/intellij-scala) plugin as its backend. LSP clients (Claude Code, VS Code, etc.) get the same Scala intelligence that IntelliJ IDEA provides — PSI-based resolution, type inference, refactoring, and documentation — over the standard Language Server Protocol.

## Why

Metals (the existing Scala LSP) uses its own compiler infrastructure, which diverges from IntelliJ-Scala's analysis engine. This project bridges that gap by exposing IntelliJ's PSI-based analysis through LSP.

## Supported Features

### Navigation

| LSP Method | Description |
|---|---|
| `textDocument/definition` | Go to definition via PSI reference resolution |
| `textDocument/typeDefinition` | Go to the type's definition (e.g. from a variable to its type's class) |
| `textDocument/implementation` | Find all implementations of a trait, class, or abstract method |
| `textDocument/references` | Find all references across the project |
| `textDocument/documentSymbol` | Outline of classes, traits, objects, methods, vals in a file |
| `workspace/symbol` | Search symbols across the entire project |
| `textDocument/documentLink` | Clickable links to imported types |
| `textDocument/documentHighlight` | Highlight all occurrences of a symbol in a file |

### Hierarchy

| LSP Method | Description |
|---|---|
| `callHierarchy/prepare,incomingCalls,outgoingCalls` | Call hierarchy — who calls this, what does this call |
| `typeHierarchy/prepare,supertypes,subtypes` | Type hierarchy — supertypes and subtypes of a class/trait |

### Editing

| LSP Method | Description |
|---|---|
| `textDocument/completion` | Code completion with lazy resolve, snippets, and auto-import |
| `completionItem/resolve` | Resolve detail, documentation, and additional edits on demand |
| `textDocument/signatureHelp` | Parameter info for method calls (triggers on `(` and `,`) |
| `textDocument/codeAction` | Quick fixes, intention actions, and refactorings |
| `codeAction/resolve` | Compute workspace edits for a code action |
| `textDocument/rename` | Rename symbol across all usages (with `prepareRename` support) |
| `textDocument/formatting` | Format entire document via IntelliJ's code style |
| `textDocument/rangeFormatting` | Format selected range |
| `textDocument/onTypeFormatting` | Auto-indent, triple-quote close, brace block reformat (triggers on `\n`, `"`, `}`) |
| `workspace/executeCommand` | `scala.organizeImports`, `scala.reformat`, `scala.gotoLocation` |
| `workspace/willRenameFiles` | Auto-rename types when renaming `.scala` files |

### Display

| LSP Method | Description |
|---|---|
| `textDocument/hover` | Type info + ScalaDoc as markdown |
| `textDocument/publishDiagnostics` | Errors and warnings pushed from IntelliJ's analysis engine |
| `textDocument/inlayHint` | Type annotations for vals, parameters, return types (with resolve) |
| `textDocument/semanticTokens/full` | Full semantic token classification (16 token types, 8 modifiers) |
| `textDocument/semanticTokens/range` | Semantic tokens for a range |
| `textDocument/codeLens` | "Overrides" annotations on trait implementations |
| `textDocument/foldingRange` | Code folding regions |
| `textDocument/selectionRange` | Structural selection ranges by walking PSI tree |

### Document Sync

| LSP Method | Description |
|---|---|
| `textDocument/didOpen,didChange,didClose,didSave` | Full text document synchronization |
| `workspace/didChangeWorkspaceFolders` | Multi-root workspace support |
| `workspace/didChangeWatchedFiles` | External file change notification |

## Prerequisites

- **JDK 21+**
- **sbt 1.11+**
- **socat** (`brew install socat` on macOS, `apt install socat` on Linux) — required for stdio-to-TCP proxying

The build uses [sbt-idea-plugin](https://github.com/JetBrains/sbt-idea-plugin) which automatically downloads the IntelliJ SDK, JBR, and Scala plugin on first build. No manual IntelliJ installation required for building.

## Build

```bash
# Compile (first run downloads ~1.5GB of IntelliJ SDK + plugins)
sbt lsp-server/compile

# Run tests
sbt lsp-server/test

# Package plugin
sbt lsp-server/packageArtifact
```

## Usage

### Daemon Mode (recommended)

Pre-warm the daemon for instant responses:

```bash
# Start daemon with project pre-warming (add to .zshrc for auto-start)
./launcher/launch-lsp.sh --daemon /path/to/project1 /path/to/project2

# Stop the daemon
./launcher/launch-lsp.sh --stop
```

Claude Code connects automatically — the launcher starts the daemon if it's not running and proxies stdio-to-TCP via socat.

### Direct Mode (for testing)

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"rootUri":"file:///path/to/project"}}' | \
  socat - TCP:localhost:5007
```

### With Claude Code

```bash
./setup-claude-code.sh
```

This registers the LSP server as a Claude Code plugin. The daemon starts automatically on first use. Requires `socat` (`brew install socat` on macOS).

### Environment Variables

| Variable | Description |
|---|---|
| `INTELLIJ_HOME` | Path to IntelliJ installation (directory containing `lib/`) |
| `JAVA_HOME` | Path to JDK (falls back to IntelliJ's bundled JBR) |
| `LSP_PORT` | TCP port for the daemon (default: 5007) |
| `LSP_HEAP_SIZE` | JVM heap size (default: `2g`, e.g. `4g` for large projects) |

## Architecture

See [docs/architecture.md](docs/architecture.md) for detailed architecture documentation covering the daemon lifecycle, provider-to-IntelliJ API mapping, classloader safety, and design decisions.

## Version Compatibility

| Component | Version |
|---|---|
| IntelliJ Platform | 253.32098.37 (2025.3.4) |
| Scala Plugin | auto-resolved by sbt-idea-plugin |
| Scala (build) | 3.8.2 |
| sbt | 1.11.7 |
| sbt-idea-plugin | 5.1.3 |
| lsp4j | 0.23.1 |

## License

Apache 2.0 — see [LICENSE](LICENSE).
