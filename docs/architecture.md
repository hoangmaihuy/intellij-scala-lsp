# Architecture

## Overview

intellij-scala-lsp is an LSP server that runs IntelliJ IDEA headless as a persistent daemon, exposing its Scala analysis engine over the Language Server Protocol. LSP clients connect via TCP; a `socat` proxy bridges stdio-based clients (like Claude Code) to the daemon.

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

| Provider | IntelliJ API | Notes |
|---|---|---|
| `DefinitionProvider` | `PsiReference.resolve()`, `PsiPolyVariantReference.multiResolve()` | Standard PSI resolution |
| `TypeDefinitionProvider` | `TypeDeclarationProvider.EP_NAME` extension point | Iterates registered handlers including Scala plugin's |
| `ImplementationProvider` | `DefinitionsScopedSearch.search()` | Scoped to `GlobalSearchScope.projectScope` |
| `ReferencesProvider` | `ReferencesSearch.search()` | Standard IntelliJ search |
| `HoverProvider` | `LanguageDocumentation.INSTANCE.forLanguage()` | Returns Scala's `ScalaDocumentationProvider` which generates type info + ScalaDoc |
| `DiagnosticsProvider` | `DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC` | Push-based: publishes when analysis completes |
| `SymbolProvider` | `ChooseByNameContributor` EP for workspace, PSI tree walk for document | Document symbols use PSI children; workspace uses search contributors |
| `CompletionProvider` | `CompletionContributor.forLanguage()` | Trusts registered contributors (Scala plugin's) |
| `FoldingRangeProvider` | `LanguageFolding.INSTANCE.forLanguage()` | Uses `LanguageFolding.buildFoldingDescriptors()` |
| `SelectionRangeProvider` | PSI tree walk (leaf to root) | No IntelliJ equivalent — LSP-specific feature |
| `CallHierarchyProvider` | `CallReferenceProcessor`, PSI reference resolution | Outgoing calls: walks PSI subtree for references |
| `TypeHierarchyProvider` | `PsiClass.getSupers()`, `DefinitionsScopedSearch` | Standard `PsiClass` API — no reflection |
| `InlayHintProvider` | `PsiNameIdentifierOwner`, `NavigationItem.getPresentation()` | Type text from presentation; definition detection via class name matching |
| `CodeActionProvider` | `IntentionManager.getInstance()` | Standard intention/quickfix API |
| `RenameProvider` | `RefactoringFactory`, `ReferencesSearch` | Standard rename + reference update |
| `DocumentSyncManager` | `FileDocumentManager`, `WriteCommandAction` | Document edits on EDT under write lock |

### Shared Utilities

`PsiUtils` provides shared helpers used by all providers:
- `positionToOffset` / `offsetToPosition` — LSP position ↔ IntelliJ offset conversion
- `elementToLocation` / `elementToRange` — PSI element → LSP Location/Range
- `findReferenceElementAt` — finds the reference-bearing element at an offset
- `resolveToDeclaration` — resolves an element at an offset to its declaration (shared by References, Implementation providers)
- `getSymbolKind` — maps PSI class names to LSP `SymbolKind` (shared by Symbol, Completion, CallHierarchy providers)
- `vfToUri` — VirtualFile → `file://` URI

### Design Decisions

**Compile-time Scala plugin dependency.** The Scala plugin's `scalaCommunity.jar` is on the compile classpath (added as `unmanagedJars` in `build.sbt`). This gives type-safe access to `ScClass`, `ScTrait`, `ScFunction`, etc. via `instanceof` / pattern matching instead of fragile class name string matching. The plugin is already a runtime dependency (loaded via `-Dplugin.path` and declared in `plugin.xml` as `<depends>org.intellij.scala</depends>`), so this adds no new coupling. Additionally:
- IntelliJ extension points: `LanguageDocumentation`, `TypeDeclarationProvider`, `CompletionContributor` — these dispatch to the Scala plugin at runtime
- Standard `PsiClass` API: works for Scala types since `ScClass`/`ScTrait` implement `PsiClass`

**Smart mode gating.** All providers use `DumbService.runReadActionInSmartMode` (via `projectManager.smartReadAction`) instead of plain `ReadAction.compute`. This blocks the request until indexing completes rather than throwing `IndexNotReadyException`.

**Daemon mode guards.** `ScalaLspServer` accepts a `daemonMode: Boolean` flag. In daemon mode:
- `shutdown()` clears per-session state only (does not close the shared `Project`)
- `exit()` is a no-op (does not call `System.exit`)

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
- `TestApplicationManager` is proven to work (236 integration tests use it)
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
  system/                     # IntelliJ indexes and caches (persistent across restarts)
    caches/                   # VFS, stub indexes, file-based indexes
    log/idea.log              # IntelliJ's internal log
  config/                     # IntelliJ config (isolated from user's IDE)
    options/jdk.table.xml     # copied from user's IntelliJ config on first start
  lsp-server-stripped.jar     # cached copy of lsp-server.jar without plugin.xml
```

The `system/` directory persists indexes across daemon restarts. On warm restart, IntelliJ scans 92k+ files but finds 0 needing re-indexing — startup is fast.
