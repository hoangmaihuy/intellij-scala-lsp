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

## Architecture

```
LSP Client (Claude Code, VS Code, etc.)
    |
    | socat stdio<->TCP proxy
    v
DaemonServer (TCP port 5007)
    |
    | per-session LSP (lsp4j JSON-RPC)
    v
ScalaLspServer --> ProjectRegistry (shared projects)
    |
    v
IntelliJ Platform (headless) + intellij-scala plugin
```

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

## Project Structure

```
intellij-scala-lsp/
├── build.sbt                               # sbt build with sbt-idea-plugin
├── project/
│   ├── build.properties                    # sbt version
│   └── plugins.sbt                         # sbt-idea-plugin dependency
├── lsp-server/
│   ├── src/
│   │   ├── ScalaLspApplicationStarter.scala    # IntelliJ appStarter extension point
│   │   ├── ScalaLspMain.scala                  # Debug entry point
│   │   ├── ScalaLspServer.scala                # lsp4j LanguageServer implementation
│   │   ├── ScalaTextDocumentService.scala      # textDocument handlers
│   │   ├── ScalaWorkspaceService.scala         # workspace handlers
│   │   ├── intellij/
│   │   │   ├── IntellijProjectManager.scala    # Project lifecycle (open/close/index)
│   │   │   ├── ProjectRegistry.scala           # Shared project registry across sessions
│   │   │   ├── DocumentSyncManager.scala       # LSP <-> IntelliJ VFS/Document sync
│   │   │   ├── DefinitionProvider.scala        # Go to definition
│   │   │   ├── TypeDefinitionProvider.scala    # Go to type definition
│   │   │   ├── ImplementationProvider.scala    # Find implementations
│   │   │   ├── ReferencesProvider.scala        # Find references
│   │   │   ├── HoverProvider.scala             # Hover (type + docs)
│   │   │   ├── DiagnosticsProvider.scala       # Publish diagnostics via DaemonListener
│   │   │   ├── CallHierarchyProvider.scala     # Call hierarchy (incoming + outgoing)
│   │   │   ├── FoldingRangeProvider.scala      # Code folding ranges
│   │   │   ├── SelectionRangeProvider.scala    # Structural selection ranges
│   │   │   ├── SymbolProvider.scala            # Document + workspace symbols
│   │   │   └── PsiUtils.scala                  # Offset <-> Position conversion
│   │   ├── org/jetbrains/scalalsP/
│   │   │   ├── BootstrapState.java             # Platform bootstrap state machine
│   │   │   └── DaemonServer.java               # TCP daemon accepting LSP sessions
│   │   └── protocol/
│   │       └── LspConversions.scala            # IntelliJ <-> LSP type conversions
│   ├── test/src/                               # 236 tests (JUnit 4)
│   └── resources/META-INF/plugin.xml           # IntelliJ plugin descriptor
├── launcher/
│   └── launch-lsp.sh                           # Runtime launcher (daemon + socat proxy)
└── claude-code/
    └── setup-intellij-scala-lsp.sh             # Claude Code plugin registration
```

## How It Works

1. The launcher starts IntelliJ IDEA in headless mode as a daemon (`DaemonServer`) listening on a TCP port (default 5007)
2. Each LSP client connection is accepted as a new session with its own `ScalaLspServer` instance
3. The `ProjectRegistry` manages shared project instances -- multiple sessions reuse already-opened projects
4. On `initialize`, the server opens the project via `ProjectManager.loadAndOpenProject()` (or reuses an existing one)
5. Indexing completes via `DumbService.waitForSmartMode()`
6. LSP requests are dispatched to provider classes that wrap IntelliJ APIs:
   - All PSI reads run inside `ReadAction.compute()`
   - Document mutations run on the EDT via `WriteCommandAction`
   - Scala-specific types are accessed via reflection to decouple from plugin compile-time dependencies
7. The launcher uses `socat` to proxy stdio-to-TCP, so LSP clients that expect stdio transport work transparently

## Technical Notes

- **Daemon mode**: The `DaemonServer` listens on a TCP port and accepts multiple concurrent LSP sessions. The `ProjectRegistry` ensures each project is opened only once and shared across sessions, reducing memory usage.
- **Threading**: IntelliJ requires PSI reads under read lock and document writes on the EDT. All providers use `ReadAction.compute()` and `WriteCommandAction` respectively.
- **Symbol detection**: Uses class-name-based checks (e.g. `className.contains("ScClass")`) rather than direct type references to avoid compile-time coupling to Scala plugin internals.
- **Hover**: Type info is retrieved via reflection on `getType()` methods; documentation via `LanguageDocumentation.INSTANCE.forLanguage()`.
- **Diagnostics**: Push-based via `DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC` -- diagnostics publish when IntelliJ's analysis actually completes, not on a timer.
- **Memory**: The daemon shares a single JVM across all sessions. Expect ~1-2GB base for IntelliJ + additional memory per project's indices. Configure via `LSP_HEAP_SIZE` (default `2g`).
- **First launch**: Initial indexing can take several minutes depending on project size. Use `--daemon` with project paths to pre-warm indices before clients connect.

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
