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

- **JDK 21+** (or IntelliJ's bundled JBR)
- **IntelliJ IDEA Community Edition** installed locally (auto-detected from standard locations)
- **Scala plugin** installed in IntelliJ (or downloaded separately)

The build auto-detects IntelliJ from these locations:
- `$INTELLIJ_HOME` environment variable
- `/Applications/IntelliJ IDEA CE.app/Contents/` (macOS)
- `~/Applications/IntelliJ IDEA.app/Contents/` (macOS)
- `~/.cache/intellij-scala-lsp/idea-IC-261.22158.121/` (cached download)

## Build

```bash
# Download the Mill bootstrap script
curl -L https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.1.3/mill-dist-1.1.3-mill.sh -o mill
chmod +x mill

# Compile
./mill lsp-server.compile

# Run tests (105 tests)
./mill lsp-server.test

# Build assembly JAR
./mill lsp-server.assembly
```

If IntelliJ is not at a standard location, set the environment variable:

```bash
INTELLIJ_HOME=/path/to/idea ./mill lsp-server.compile
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
| `SCALA_PLUGIN_HOME` | Path to Scala plugin (directory containing `lib/`) |
| `JAVA_HOME` | Path to JDK (falls back to IntelliJ's bundled JBR) |

## Project Structure

```
intellij-scala-lsp/
├── build.mill                              # Mill build definition (Scala 3.8, lsp4j 0.23.1)
├── mill-build/src/
│   └── IntellijSdkModule.scala             # Auto-locates IntelliJ SDK + Scala plugin JARs
├── lsp-server/
│   ├── src/
│   │   ├── ScalaLspApplicationStarter.scala    # IntelliJ appStarter extension point
│   │   ├── ScalaLspMain.scala                  # Debug entry point
│   │   ├── ScalaLspServer.scala                # lsp4j LanguageServer implementation
│   │   ├── ScalaTextDocumentService.scala      # textDocument/* handlers
│   │   ├── ScalaWorkspaceService.scala         # workspace/* handlers
│   │   ├── intellij/
│   │   │   ├── IntellijProjectManager.scala    # Project lifecycle (open/close/index)
│   │   │   ├── DocumentSyncManager.scala       # LSP <-> IntelliJ VFS/Document sync
│   │   │   ├── DefinitionProvider.scala        # Go to definition
│   │   │   ├── TypeDefinitionProvider.scala   # Go to type definition
│   │   │   ├── ImplementationProvider.scala   # Find implementations
│   │   │   ├── ReferencesProvider.scala        # Find references
│   │   │   ├── HoverProvider.scala             # Hover (type + docs)
│   │   │   ├── DiagnosticsProvider.scala       # Publish diagnostics via DaemonListener
│   │   │   ├── FoldingRangeProvider.scala     # Code folding ranges
│   │   │   ├── SelectionRangeProvider.scala   # Structural selection ranges
│   │   │   ├── SymbolProvider.scala            # Document + workspace symbols
│   │   │   └── PsiUtils.scala                  # Offset <-> Position conversion
│   │   └── protocol/
│   │       └── LspConversions.scala            # IntelliJ <-> LSP type conversions
│   ├── test/src/                               # 105 tests (munit)
│   └── resources/META-INF/plugin.xml           # IntelliJ plugin descriptor
├── launcher/
│   └── launch-lsp.sh                           # Runtime launcher (auto-download, classpath)
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
- **Hover**: Type info is retrieved via reflection on `getType()` methods; documentation via `ScalaDocumentationProvider.generateDoc()`.
- **Memory**: Expect ~1-2GB for IntelliJ + project indices. The launcher sets `-Xmx2g`.
- **First launch**: Initial indexing can take several minutes depending on project size. Subsequent launches use cached indices.

## Version Compatibility

| Component | Version |
|---|---|
| IntelliJ Platform | 261.22158.121 (2026.1) |
| Scala Plugin | 2026.1.7 |
| Scala (build) | 3.8.2 |
| Mill | 1.1.3 |
| lsp4j | 0.23.1 |
| munit (test) | 1.1.0 |

## License

Apache 2.0 - see [LICENSE](LICENSE).
