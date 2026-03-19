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
    | stdin/stdout JSON-RPC
    v
ScalaLspServer (lsp4j)
    |
    v
IntelliJ Adapters (DefinitionProvider, ReferencesProvider, HoverProvider, SymbolProvider)
    |
    v
IntelliJ Platform (headless) + intellij-scala plugin (PSI, Indexing, Resolve)
```

## Prerequisites

- **JDK 21+**
- **sbt 1.11+**

The build uses [sbt-idea-plugin](https://github.com/JetBrains/sbt-idea-plugin) which automatically downloads the IntelliJ SDK, JBR, and Scala plugin on first build. No manual IntelliJ installation required for building.

## Build

```bash
# Compile (first run downloads ~1.5GB of IntelliJ SDK + plugins)
sbt lsp-server/compile

# Run tests (105 tests)
sbt lsp-server/test

# Package plugin
sbt lsp-server/packageArtifact
```

## Usage

### Standalone

```bash
./launcher/launch-lsp.sh /path/to/your/scala/project
```

The launcher:
1. Locates IntelliJ (or downloads it to `~/.cache/intellij-scala-lsp/`)
2. Locates the Scala plugin (or downloads it)
3. Builds the classpath and starts IntelliJ headless with the LSP server
4. Communicates via stdin/stdout JSON-RPC

### With Claude Code

```bash
./claude-code/setup-intellij-scala-lsp.sh
```

This registers the LSP server as a Claude Code plugin. Restart Claude Code after running. The startup timeout is 300 seconds to allow for initial IntelliJ indexing.

### Environment Variables

| Variable | Description |
|---|---|
| `INTELLIJ_HOME` | Path to IntelliJ installation (directory containing `lib/`) |
| `JAVA_HOME` | Path to JDK (falls back to IntelliJ's bundled JBR) |

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
│   │   └── protocol/
│   │       └── LspConversions.scala            # IntelliJ <-> LSP type conversions
│   ├── test/src/                               # 105 tests (JUnit 4)
│   └── resources/META-INF/plugin.xml           # IntelliJ plugin descriptor
├── launcher/
│   └── launch-lsp.sh                           # Runtime launcher
└── claude-code/
    └── setup-intellij-scala-lsp.sh             # Claude Code plugin registration
```

## How It Works

1. IntelliJ IDEA starts in headless mode via `ApplicationStarter`
2. The project is opened with `ProjectManager.loadAndOpenProject()`
3. Indexing completes via `DumbService.waitForSmartMode()`
4. The lsp4j `Launcher` connects JSON-RPC on stdin/stdout
5. LSP requests are dispatched to provider classes that wrap IntelliJ APIs:
   - All PSI reads run inside `ReadAction.compute()`
   - Document mutations run on the EDT via `WriteCommandAction`
   - Scala-specific types are accessed via reflection to decouple from plugin compile-time dependencies

## Technical Notes

- **Threading**: IntelliJ requires PSI reads under read lock and document writes on the EDT. All providers use `ReadAction.compute()` and `WriteCommandAction` respectively.
- **Symbol detection**: Uses class-name-based checks (e.g. `className.contains("ScClass")`) rather than direct type references to avoid compile-time coupling to Scala plugin internals.
- **Hover**: Type info is retrieved via reflection on `getType()` methods; documentation via `LanguageDocumentation.INSTANCE.forLanguage()`.
- **Diagnostics**: Push-based via `DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC` — diagnostics publish when IntelliJ's analysis actually completes, not on a timer.
- **Memory**: Expect ~1-2GB for IntelliJ + project indices. The launcher sets `-Xmx2g`.
- **First launch**: Initial indexing can take several minutes depending on project size. Subsequent launches use cached indices.

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
