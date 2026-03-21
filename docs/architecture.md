# Architecture

## Overview

intellij-scala-lsp runs IntelliJ IDEA headless as a persistent daemon, exposing its Scala analysis engine over the Language Server Protocol. LSP clients connect via TCP; a `socat` proxy bridges stdio-based clients (like Claude Code) to the daemon.

```
                          ┌─────────────────────────────────────────────┐
                          │  Daemon JVM (one process, multiple projects)│
                          │                                             │
Claude Code ──socat──┐    │  ┌──────────────┐    ┌──────────────────┐  │
                     ├──TCP──▶ DaemonServer  ├───▶│ ProjectRegistry  │  │
VS Code ─────socat──┘    │  │  (port 5007)  │    │ osiris: Project  │  │
                          │  └──────┬───────┘    │ jsoniter: Project │  │
                          │         │            └──────────────────┘  │
                          │   per-connection                           │
                          │         │                                  │
                          │  ┌──────▼───────┐                         │
                          │  │ScalaLspServer │ (one per session)       │
                          │  │  TextDoc Svc  │                         │
                          │  │  Workspace Svc│                         │
                          │  └──────┬───────┘                         │
                          │         │                                  │
                          │  ┌──────▼───────┐                         │
                          │  │   Providers   │ (Hover, Definition,...) │
                          │  └──────┬───────┘                         │
                          │         │                                  │
                          │  ┌──────▼────────────────────────────────┐ │
                          │  │ IntelliJ Platform + Scala Plugin      │ │
                          │  │ PSI, Indexes, DaemonCodeAnalyzer, VFS │ │
                          │  └──────────────────────────────────────┘ │
                          └─────────────────────────────────────────────┘
```

## Daemon Lifecycle

### Startup

1. `launch-lsp.sh --daemon [projects...]` starts the JVM
2. `ScalaLspMain.daemonMode()` installs a global lenient `LoggedErrorProcessor` (via reflection on the static `ourInstance` field) to prevent `TestLoggerFactory` from converting `LOG.error()` into fatal exceptions
3. `IntellijBootstrap.initialize()` calls `TestApplicationManager.getInstance()` which bootstraps the full IntelliJ platform: EDT, VFS, plugin loading, kernel, services
4. Pre-warm projects are opened via `ProjectRegistry.openProject()` — each waits for `DumbService.waitForSmartMode()`
5. `DaemonServer.bind(port)` creates the TCP `ServerSocket`
6. State files (`daemon.pid`, `daemon.port`) are written to `~/.cache/intellij-scala-lsp/`
7. `DaemonServer.acceptLoop()` blocks, accepting connections

### Per-Connection

1. TCP connection accepted, new daemon thread spawned
2. First JSON-RPC message read and buffered (`readFirstMessage`)
3. If method is `"shutdown"` — daemon stops (used by `--stop`)
4. Otherwise, `rootUri` extracted from `initialize` params to determine project path
5. Consumed bytes replayed via `SequenceInputStream` so lsp4j receives the complete message
6. `ProjectRegistry.openProject(path)` returns the `Project` (cached or newly opened)
7. Per-session `IntellijProjectManager` created with shared `Project` reference
8. Per-session `ScalaLspServer(path, projectManager, daemonMode=true)` created
9. `LspLauncher.startAndAwait()` wires TCP streams to lsp4j JSON-RPC — blocks until disconnect

### Shutdown

- `launch-lsp.sh --stop` sends a `shutdown` JSON-RPC message to the daemon port
- `DaemonServer.stop()` closes the `ServerSocket`, calls `ProjectRegistry.closeAll()`
- JVM shutdown hook deletes state files

### Auto-Start (connect mode)

When `launch-lsp.sh` is called without `--daemon`:
1. Checks `daemon.pid` + `daemon.port` — if daemon alive, proxies via `socat`
2. If not running, starts daemon in background, waits for `daemon.port` file (up to 60s), then proxies

## Project Management

`ProjectRegistry` is a thread-safe singleton (`ConcurrentHashMap`) shared across all sessions:

- `openProject(path)` — `computeIfAbsent` prevents double-open races. Opens the project, registers missing JDKs, waits for smart mode, returns the `Project` instance.
- `getProject(path)` — lookup only, returns `Option[Project]`
- `closeAll()` — daemon shutdown only
- Projects are never closed during normal operation — kept warm for future sessions

`IntellijProjectManager` is per-session:
- Holds a reference to the shared `Project` from the registry
- Manages per-session state: `virtualFileCache` (URI-to-VirtualFile mapping)
- `smartReadAction()` wraps operations in `DumbService.runReadActionInSmartMode` to block until indexing completes
- In daemon mode, `closeProject()` clears per-session state without disposing the shared `Project`

## LSP Provider Architecture

Each LSP method is handled by a dedicated provider class. All providers follow the same pattern:

```scala
class XxxProvider(projectManager: IntellijProjectManager):
  def doSomething(uri: String, position: Position): Result =
    projectManager.smartReadAction: () =>
      for
        psiFile <- projectManager.findPsiFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        // delegate to IntelliJ API
```

### Provider → IntelliJ API Mapping

#### Navigation

| Provider | LSP Method | IntelliJ API | Notes |
|---|---|---|---|
| `DefinitionProvider` | `textDocument/definition` | `PsiReference.resolve()`, `PsiPolyVariantReference.multiResolve()` | Standard PSI resolution; `DefinitionOrReferencesProvider` wraps this with a fallback to references when definition resolves to itself |
| `TypeDefinitionProvider` | `textDocument/typeDefinition` | `TypeDeclarationProvider.EP_NAME` extension point | Iterates registered handlers including Scala plugin's |
| `ImplementationProvider` | `textDocument/implementation` | `DefinitionsScopedSearch.search()` | Scoped to `GlobalSearchScope.projectScope` |
| `ReferencesProvider` | `textDocument/references` | `ReferencesSearch.search()` | Includes declaration if `context.includeDeclaration` is true |
| `SymbolProvider` | `textDocument/documentSymbol` | PSI tree walk | Walks PSI children to collect classes, traits, methods, vals into hierarchical `DocumentSymbol` list |
| `SymbolProvider` | `workspace/symbol` | `ChooseByNameContributor` EP | Searches across all project files via registered contributors |
| `DocumentLinkProvider` | `textDocument/documentLink` | PSI reference walk | Creates clickable links to imported types |
| `DocumentHighlightProvider` | `textDocument/documentHighlight` | `ReferencesSearch.search()` | Finds references at position, returns highlight ranges |

#### Hierarchy

| Provider | LSP Method | IntelliJ API | Notes |
|---|---|---|---|
| `CallHierarchyProvider` | `callHierarchy/prepare` | PSI reference resolution | Returns `CallHierarchyItem` for the symbol at position |
| `CallHierarchyProvider` | `callHierarchy/incomingCalls` | `CallReferenceProcessor` | Finds all call sites of the target method |
| `CallHierarchyProvider` | `callHierarchy/outgoingCalls` | PSI subtree walk | Walks the method body for reference expressions to other methods |
| `TypeHierarchyProvider` | `typeHierarchy/prepare` | PSI hierarchy | Returns `TypeHierarchyItem` at position |
| `TypeHierarchyProvider` | `typeHierarchy/supertypes` | `PsiClass.getSupers()` | Standard PsiClass API — works for Scala types since `ScClass`/`ScTrait` implement `PsiClass` |
| `TypeHierarchyProvider` | `typeHierarchy/subtypes` | `DefinitionsScopedSearch` | Finds all subtypes within project scope |

#### Editing

| Provider | LSP Method | IntelliJ API | Notes |
|---|---|---|---|
| `CompletionProvider` | `textDocument/completion` | `CompletionContributor.forLanguage()` | Lazy resolve with 30s cache TTL; generates snippet insert text for methods with parameters |
| `CompletionProvider` | `completionItem/resolve` | `LanguageDocumentation` | Populates detail, documentation, and auto-import `additionalTextEdits` |
| `SignatureHelpProvider` | `textDocument/signatureHelp` | `ParameterInfoHandler` EP | Trigger chars: `(`, `,`; returns parameter info for method calls |
| `CodeActionProvider` | `textDocument/codeAction` | `IntentionManager.getInstance()`, `DaemonCodeAnalyzer` | Collects quick fixes from highlighting + intention actions; supports QuickFix, Refactor, Source, RefactorExtract, RefactorInline kinds |
| `CodeActionProvider` | `codeAction/resolve` | Intention action execution | Computes workspace edits by applying fix on PSI copy and diffing the result |
| `RenameProvider` | `textDocument/rename` | `RefactoringFactory`, `ReferencesSearch` | Supports `prepareRename` to validate and return the rename range |
| `FormattingProvider` | `textDocument/formatting` | `CodeStyleManager.reformat()` | Reformats entire document |
| `FormattingProvider` | `textDocument/rangeFormatting` | `CodeStyleManager.reformat()` | Reformats selected range |
| `OnTypeFormattingProvider` | `textDocument/onTypeFormatting` | `CodeStyleManager`, PSI edit tracking | Trigger chars: `\n`, `"`, `}`; handles indentation, triple-quote close, brace block reformat |

#### Display

| Provider | LSP Method | IntelliJ API | Notes |
|---|---|---|---|
| `HoverProvider` | `textDocument/hover` | `LanguageDocumentation.INSTANCE.forLanguage()` | Returns type info + ScalaDoc as markdown; converts IntelliJ's HTML output to clean markdown |
| `DiagnosticsProvider` | `textDocument/publishDiagnostics` | `DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC` | Push-based: registers a topic listener, publishes asynchronously when analysis completes |
| `InlayHintProvider` | `textDocument/inlayHint` | `PsiNameIdentifierOwner`, `NavigationItem.getPresentation()` | Type hints for vals, parameters, return types; resolve support for documentation |
| `SemanticTokensProvider` | `textDocument/semanticTokens/full` | PSI element classification via `ScalaTypes` | 16 token types (keyword, type, class, interface, enum, method, property, variable, parameter, typeParameter, string, number, comment, function, operator, regexp); 8 modifiers (declaration, static, abstract, readonly, modification, documentation, lazy, deprecated) |
| `SemanticTokensProvider` | `textDocument/semanticTokens/range` | PSI element classification via `ScalaTypes` | Same as full, filtered to range |
| `CodeLensProvider` | `textDocument/codeLens` | `SuperMethodCodeLens` | Shows "Overrides" annotation on methods that implement trait/class members |
| `FoldingRangeProvider` | `textDocument/foldingRange` | `LanguageFolding.INSTANCE.forLanguage()` | Uses `LanguageFolding.buildFoldingDescriptors()` |
| `SelectionRangeProvider` | `textDocument/selectionRange` | PSI tree walk (leaf to root) | No IntelliJ equivalent — LSP-specific feature |

#### Document Sync & Workspace

| Provider | LSP Method | IntelliJ API | Notes |
|---|---|---|---|
| `DocumentSyncManager` | `didOpen/didChange/didClose/didSave` | `FileDocumentManager`, `WriteCommandAction` | Full text sync; document edits on EDT under write lock |
| `ScalaWorkspaceService` | `workspace/executeCommand` | `OptimizeImportsProcessor`, `CodeStyleManager` | Three commands: `scala.organizeImports`, `scala.reformat`, `scala.gotoLocation` |
| `ScalaWorkspaceService` | `workspace/willRenameFiles` | PSI type definition walk | When a `.scala` file is renamed, finds type definitions matching the old filename and generates rename edits |
| `ScalaWorkspaceService` | `workspace/didChangeWatchedFiles` | `VfsUtil.markDirtyAndRefresh()` | Notifies IntelliJ VFS of external file changes |
| `ScalaWorkspaceService` | `workspace/didChangeWorkspaceFolders` | `IntellijProjectManager` | Opens/closes projects for added/removed workspace folders |

### Shared Utilities

**`PsiUtils`** provides shared helpers used by all providers:
- `positionToOffset` / `offsetToPosition` — LSP position ↔ IntelliJ offset conversion
- `elementToLocation` / `elementToRange` — PSI element → LSP Location/Range
- `findReferenceElementAt` — finds the reference-bearing element at an offset
- `resolveToDeclaration` — resolves an element at an offset to its declaration
- `getSymbolKind` — maps PSI class names to LSP `SymbolKind`
- `vfToUri` — VirtualFile → `file://` URI
- JAR source handling: for `.class` files in JARs, tries to find original source from source JARs, falls back to IntelliJ's decompiled PSI text, caches to `~/.cache/intellij-scala-lsp/sources/`

**`LspConversions`** converts between IntelliJ and LSP types:
- Severity mapping (IntelliJ `HighlightSeverity` → LSP `DiagnosticSeverity`)
- Symbol kind mapping
- Range/Position conversions

## Classloader Safety: ScalaTypes

IntelliJ loads the Scala plugin via `PluginClassLoader`, while our LSP server code runs on the boot classpath. This means `instanceof ScTypeDefinition` always fails — the class objects differ across classloaders even though the fully-qualified name is the same.

`ScalaTypes` solves this with runtime class name matching through the element's own classloader:

```scala
private def isInstanceOfScala(e: PsiElement, fqn: String): Boolean =
  loadClass(e.getClass.getClassLoader, fqn) match
    case Some(cls) => cls.isInstance(e)  // Works across classloaders
    case None => false
```

This provides 50+ type-safe predicates used throughout the providers:
- **Type definitions**: `isTypeDefinition`, `isClass`, `isTrait`, `isObject`, `isEnum`, `isTemplateDefinition`
- **Members**: `isFunction`, `isFunctionDefinition`, `isValue`, `isVariable`, `isTypeAlias`, `isGiven`
- **Parameters**: `isParameter`, `isTypeParam`, `isBindingPattern`, `isFieldId`
- **References**: `isReference`, `isReferenceExpression`, `isMethodCall`
- **Types**: `isSimpleTypeElement`, `isParameterizedTypeElement`
- **Expressions**: `isExpression`, `isImplicitArgumentsOwner`

Results are cached in a `ConcurrentHashMap[(ClassLoader, FQN)] → Option[Class[?]]` to avoid repeated `Class.forName` calls.

## JSON-RPC Wiring (Scala 3 Bridge Method Workaround)

`LspLauncher.java` wraps the Scala LSP server in Java delegate classes before handing it to lsp4j's `Launcher.Builder`. This is necessary because Scala 3 generates bridge methods that copy `@JsonNotification`/`@JsonRequest` annotations, causing lsp4j's reflection-based method scanning to find duplicate RPC method names.

The workaround:
1. `JavaLanguageServer` wraps `ScalaLspServer`
2. `JavaTextDocumentService` wraps `ScalaTextDocumentService`
3. `JavaWorkspaceService` wraps `ScalaWorkspaceService`
4. `getSupportedMethods()` is overridden to scan only the Java interfaces
5. `createRemoteEndpoint()` uses pre-computed methods from Java interfaces

## Bootstrap

`IntellijBootstrap.java` uses `TestApplicationManager.getInstance()` from IntelliJ's test framework to bootstrap the platform in headless mode. This is a pragmatic choice:
- The production `com.intellij.idea.Main` rejects custom commands (hardcoded `WellKnownCommands` whitelist in 2025.3)
- Custom `ApplicationImpl` bootstrap hits edge cases with EDT, VFS locks, Fleet kernel
- `TestApplicationManager` is proven to work (all integration tests use it)
- The `@TestOnly` annotation is just a marker — the APIs set boolean flags, acceptable trade-off

## Launcher Script

`launch-lsp.sh` handles:
- IntelliJ installation detection (installed app or sbt-idea-plugin SDK)
- Scala plugin version matching (extracts IDE build number, picks compatible plugin)
- JVM args from `product-info.json` (boot classpath, `--add-opens`, etc.)
- Stripped JAR creation (removes `META-INF/plugin.xml` from classpath copy to avoid plugin loader conflict)
- Scala runtime JARs on classpath (from Scala plugin dir)
- JDK table copy from user's IntelliJ config
- CDS warning suppression (`-Xlog:cds=off`)
- Daemon management (`--daemon`, `--stop`, auto-start connect mode)
- `socat` stdio↔TCP proxy

## State and Caching

```
~/.cache/intellij-scala-lsp/
  daemon.pid                  # daemon process ID (written after port bind)
  daemon.port                 # TCP port (written after port bind)
  sources/                    # cached JAR source files (file:// URIs)
  system/                     # IntelliJ indexes and caches (persistent across restarts)
    caches/                   # VFS, stub indexes, file-based indexes
    log/idea.log              # IntelliJ's internal log
  config/                     # IntelliJ config (isolated from user's IDE)
    options/jdk.table.xml     # copied from user's IntelliJ config on first start
  lsp-server-stripped.jar     # cached copy of lsp-server.jar without plugin.xml
```

The `system/` directory persists indexes across daemon restarts. On warm restart, IntelliJ scans 92k+ files but finds 0 needing re-indexing — startup is fast.

## Design Decisions

**Daemon over single-process.** Allows pre-warming projects and reusing indexes across editor sessions. A cold start requires full indexing (~30s); warm start takes <5s.

**Compile-time Scala plugin dependency.** `scalaCommunity.jar` is on the compile classpath (`unmanagedJars` in `build.sbt`). This gives type-safe access to Scala plugin types for compilation, while `ScalaTypes` handles the runtime classloader boundary. The plugin is already a runtime dependency, so this adds no new coupling.

**Extension points over direct calls.** Providers use IntelliJ's EP system (`LanguageDocumentation`, `TypeDeclarationProvider`, `CompletionContributor`, etc.) which dispatch to the Scala plugin at runtime. Standard `PsiClass` API works for Scala types since `ScClass`/`ScTrait` implement `PsiClass`.

**Smart mode gating.** All providers use `DumbService.runReadActionInSmartMode` (via `projectManager.smartReadAction`) instead of plain `ReadAction.compute`. This blocks the request until indexing completes rather than throwing `IndexNotReadyException`.

**Daemon mode guards.** `ScalaLspServer` accepts a `daemonMode: Boolean` flag. In daemon mode:
- `shutdown()` clears per-session state only (does not close the shared `Project`)
- `exit()` is a no-op (does not call `System.exit`)

**Push diagnostics.** Rather than polling, `DiagnosticsProvider` registers a `DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC` listener that publishes diagnostics asynchronously when IntelliJ's analysis completes.

**Lazy completion resolve.** Completion items are returned lean (label + basic info). Detail, documentation, and auto-import edits are resolved on demand via `completionItem/resolve`, keeping initial response sizes small.

**Code action two-phase resolve.** `codeAction` collects available actions quickly in a read action. `codeAction/resolve` computes the actual workspace edit by applying the fix on a PSI copy and diffing — avoiding write actions during browsing.
