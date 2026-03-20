# intellij-scala-lsp

An LSP server that uses IntelliJ IDEA headless + the [intellij-scala](https://github.com/JetBrains/intellij-scala) plugin as its backend. This gives LSP clients (Claude Code, VS Code, etc.) the same Scala intelligence that IntelliJ IDEA provides.

## Why

Metals (the existing Scala LSP) uses its own compiler infrastructure, which diverges from IntelliJ-Scala's analysis engine. This project bridges that gap by exposing IntelliJ's PSI-based resolution, type inference, and documentation through the standard LSP protocol.

## Supported Features

| LSP Method | Description |
|---|---|
| `textDocument/definition` | Go to definition via PSI reference resolution |
| `textDocument/typeDefinition` | Go to the type's definition (e.g. from a variable to its type's class) |
| `textDocument/implementation` | Find all implementations of a trait, class, or abstract method |
| `textDocument/references` | Find all references via `ReferencesSearch` |
| `textDocument/hover` | Type info + ScalaDoc documentation |
| `textDocument/publishDiagnostics` | Errors and warnings from IntelliJ's analysis engine |
| `textDocument/documentSymbol` | Outline of classes, traits, objects, methods, vals |
| `workspace/symbol` | Search symbols across the project |
| `callHierarchy/prepare,incomingCalls,outgoingCalls` | Call hierarchy (who calls this, what does this call) |
| `textDocument/foldingRange` | Code folding regions from language-specific `FoldingBuilder` |
| `textDocument/selectionRange` | Structural selection ranges by walking PSI tree |
| `textDocument/didOpen,didChange,didClose,didSave` | Full text document synchronization |

## Prerequisites

- **JDK 21+**
- **sbt 1.11+**
- **socat** (`brew install socat` on macOS, `apt install socat` on Linux) -- required for stdio-to-TCP proxying

The build uses [sbt-idea-plugin](https://github.com/JetBrains/sbt-idea-plugin) which automatically downloads the IntelliJ SDK, JBR, and Scala plugin on first build. No manual IntelliJ installation required for building.

## Build

```bash
# Compile (first run downloads ~1.5GB of IntelliJ SDK + plugins)
sbt lsp-server/compile

# Run tests (236 tests)
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

Claude Code connects automatically -- the launcher starts the daemon if it's not running and proxies stdio-to-TCP via socat.

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

See [docs/architecture.md](docs/architecture.md) for detailed architecture documentation covering the daemon lifecycle, provider-to-IntelliJ API mapping, bootstrap mechanism, and design decisions.

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

Apache 2.0 - see [LICENSE](LICENSE).
