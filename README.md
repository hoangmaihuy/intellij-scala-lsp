# intellij-scala-lsp

An LSP server that runs IntelliJ IDEA headless + the [intellij-scala](https://github.com/JetBrains/intellij-scala) plugin as its backend. LSP clients (Claude Code, VS Code, etc.) get the same Scala intelligence that IntelliJ IDEA provides — PSI-based resolution, type inference, refactoring, and documentation — over the standard Language Server Protocol.

## Why

IntelliJ-Scala's static analysis engine is significantly faster than compiler-based approaches. It uses PSI (Program Structure Interface) — a lightweight, incremental AST that resolves types, references, and implicits without running the Scala compiler. This makes operations like go-to-definition, find-references, and code completion near-instant even on large codebases, where compiler-based tools like Metals can struggle with latency.

Metals, the existing Scala LSP, builds on the Scala compiler (via the presentation compiler and SemanticDB). While accurate, this ties its performance to compilation speed and requires re-compiling on every change. IntelliJ-Scala avoids this by maintaining an always-up-to-date PSI index that updates incrementally as you type.

This project exposes IntelliJ's analysis engine over LSP, bringing its performance to any editor — not just IntelliJ IDEA.

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

## Install

```bash
curl -fsSL https://github.com/hoangmaihuy/intellij-scala-lsp/releases/latest/download/install.sh | bash
```

This downloads the launcher, LSP server JARs, and sets up the IntelliJ SDK automatically. If you have IntelliJ installed, it reuses your installation. Otherwise, it downloads IntelliJ Community Edition (~800MB, first run only).

### Requirements

- **macOS** or **Linux**
- **socat** (`brew install socat` on macOS, `apt install socat` on Linux) — required for daemon mode
- **python3** — used by the launcher to parse IntelliJ's `product-info.json`

## Usage

### Daemon Mode (recommended)

```bash
# Start daemon with project pre-warming
intellij-scala-lsp --daemon /path/to/project1 /path/to/project2

# Stop the daemon
intellij-scala-lsp --stop
```

### Project Import

If your project doesn't have an `.idea/` folder yet, import it first:

```bash
# Auto-detects Mill or sbt
intellij-scala-lsp --import /path/to/project
```

- **Mill** (`build.mill`/`build.sc`): runs `mill mill.idea/` (prefers local `./mill` or `./millw`)
- **sbt** (`build.sbt`): uses IntelliJ's sbt import API to generate `.idea/`

Re-running `--import` on an already-imported project refreshes the project configuration.

### Editor Setup

```bash
# Claude Code (MCP server — recommended, 22 tools)
intellij-scala-lsp --setup-mcp

# Claude Code (LSP plugin — 9 built-in LSP operations)
intellij-scala-lsp --setup-claude-code

# VS Code
intellij-scala-lsp --setup-vscode

# Neovim (manual config)
# cmd = { "intellij-scala-lsp" }
```

#### Claude Code: MCP vs LSP

There are two ways to connect Claude Code to this server:

| | MCP Server (`--setup-mcp`) | LSP Plugin (`--setup-claude-code`) |
|---|---|---|
| **Tools** | 22 MCP tools (definition, references, hover, rename, code actions, completion, formatting, hierarchy, etc.) | 9 built-in LSP operations |
| **Interface** | Symbol-name-based (e.g. `definition("MyClass")`) | File+position-based |
| **Diagnostics** | On-demand via `diagnostics` tool | Push-based (automatic after edits) |
| **Setup** | Adds MCP server to `~/.claude/settings.json` | Installs Claude Code LSP plugin |

**Recommended:** Use `--setup-mcp` for the broadest feature coverage. The MCP server connects to the same daemon and exposes all LSP features as Claude Code tools with better ergonomics for AI use.

### Updating

The launcher checks for updates daily. To update manually:

```bash
intellij-scala-lsp --update
```

## Development

```bash
# Build (first run downloads ~1.5GB of IntelliJ SDK + plugins)
sbt lsp-server/compile

# Run tests
sbt lsp-server/test

# Build and run the LSP server
sbt "lsp-server/runLsp --daemon"

# Or run the launcher directly
sbt lsp-server/packageArtifact
./launcher/intellij-scala-lsp --daemon

# Set up editor for local dev
./launcher/intellij-scala-lsp --setup-mcp-dev        # Claude Code (MCP)
./launcher/intellij-scala-lsp --setup-claude-code-dev # Claude Code (LSP)
./launcher/intellij-scala-lsp --setup-vscode-dev      # VS Code
```

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

## Acknowledgements

This project stands on the shoulders of:

- **[IntelliJ IDEA Community Edition](https://github.com/JetBrains/intellij-community)** — the open-source IDE platform that provides the PSI framework, indexing infrastructure, and code analysis pipeline that powers this server.
- **[intellij-scala](https://github.com/JetBrains/intellij-scala)** — JetBrains' Scala plugin for IntelliJ, whose type inference, reference resolution, and refactoring engine is the core of everything this LSP exposes. Without the years of work that went into this plugin, this project would not exist.
- **[Metals](https://github.com/scalameta/metals)** — the Scala language server that pioneered Scala LSP support and defined the feature expectations for Scala tooling in non-IntelliJ editors. Its protocol design and feature set served as a reference for this project's LSP implementation.

## License

MIT — see [LICENSE](LICENSE).
