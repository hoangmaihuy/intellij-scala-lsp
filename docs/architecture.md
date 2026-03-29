# Architecture

## Overview

intellij-scala-lsp runs IntelliJ IDEA headless as a persistent daemon, exposing its Scala analysis engine over the Language Server Protocol. Clients connect in two ways:

- **LSP clients** (VS Code, Zed, Neovim) connect via `socat` stdio-to-TCP proxy
- **Claude Code** connects via an MCP server that translates MCP tool calls into LSP requests over TCP

```
                        +------------------------------------------------+
                        |  Daemon JVM (one process, multiple projects)   |
                        |                                                |
Claude Code --MCP--+    |  +--------------+    +-------------------+     |
                   +-TCP--> DaemonServer  +--->| ProjectRegistry   |     |
VS Code ---socat---+    |  | (port 5007)  |    |  osiris: Project  |     |
Zed -------socat---+    |  +---------+----+    |  jsoniter: Project|     |
                        |            |         +-------------------+     |
                        |      per-connection                            |
                        |            |                                   |
                        |  +---------v--------+                          |
                        |  | ScalaLspServer    | (one per session)       |
                        |  |   TextDoc Svc     |                         |
                        |  |   Workspace Svc   |                         |
                        |  +---------+--------+                          |
                        |            |                                   |
                        |  +---------v--------+                          |
                        |  |    Providers      | (Hover, Definition,...) |
                        |  +---------+--------+                          |
                        |            |                                   |
                        |  +---------v------------------------------+    |
                        |  | IntelliJ Platform + Scala Plugin       |    |
                        |  | PSI, Indexes, DaemonCodeAnalyzer, VFS  |    |
                        |  +----------------------------------------+    |
                        +------------------------------------------------+
```

### MCP Server (Claude Code)

The MCP server is a Node.js process that bridges Claude Code to the daemon. It translates MCP tool calls into LSP requests over persistent TCP connections, exposing 12 tools backed by IntelliJ's analysis engine. The server supports multiple projects simultaneously — each project gets its own LSP session (TCP connection) to the daemon. See [MCP Tools](mcp-tools.md) for the full tool reference.

```
Claude Code
    | (MCP over stdio)
    v
+-----------------------------------------------------------+
|  MCP Server (Node.js)                                     |
|                                                           |
|  +---------------+  +------------------+  +------------+  |
|  | McpServer     |  | SessionManager   |  | FileManager|  |
|  | (stdio)       |  | (project→session)|  | (mtime)    |  |
|  +------+--------+  +------+----------+   +------------+  |
|         |                  |                              |
|  +------v--------+  +-----v-----------+  +------------+   |
|  | 12 MCP Tools  |  | LspSession[]    |  | Diagnostics|   |
|  | (5 groups)    |  |  project A:5007 |  | Cache      |   |
|  +---------------+  |  project B:5007 |  +------------+   |
|                     +------+----------+                   |
+----------------------------+------------------------------+
                             | (LSP JSON-RPC over TCP, one per project)
                             v
                     DaemonServer (JVM)
```

#### MCP Startup Sequence

1. **Daemon detection.** Read port from `~/.cache/intellij-scala-lsp/daemon.port`, try TCP probe. If daemon isn't running, spawn `scalij --daemon` and poll (up to 60s).
2. **Session creation.** For each project, open a TCP connection and perform LSP `initialize` with the project's `rootUri`. Sessions are created on demand when a tool targets a file in a new project.
3. **Register handlers.** Set up `publishDiagnostics`, `workspace/applyEdit`, `window/showMessage`, etc. per session.
4. **Register MCP tools.** All 12 tools registered on the `McpServer`.
5. **Serve.** Connect to `StdioServerTransport`, begin accepting tool calls.
6. **Route by project.** Each tool call resolves the target file to a project, then dispatches to the corresponding LSP session.

#### MCP Modules

| Module | Purpose |
|--------|---------|
| `session-manager.ts` | Maps projects to LSP sessions, creates sessions on demand, routes requests |
| `lsp-client.ts` | Per-session TCP connection, JSON-RPC framing, request/response routing |
| `file-manager.ts` | Open file tracking with mtime-based staleness detection |
| `diagnostics-cache.ts` | Cache for push diagnostics with wait-for support |
| `symbol-resolver.ts` | `workspace/symbol` → Location resolution with filtering |
| `workspace-edit.ts` | Apply `WorkspaceEdit` to disk files (reverse-order offset preservation) |
| `tools/*.ts` | 12 tools in 5 groups: navigation, display, editing, formatting, workspace |

#### File Tracking

The MCP server maintains a map of open files (`URI → { version, mtime }`). Before any tool touches a file:
1. **Not yet open** — reads content, records `mtime`, sends `didOpen`
2. **Open but stale** — detects Claude Code edits via mtime change, sends `didChange` + `didChangeWatchedFiles`
3. **Open and current** — no-op

---

## Daemon Lifecycle

### Startup

1. `scalij --daemon [projects...]` starts the JVM via `com.intellij.idea.Main scala-lsp --daemon`
2. IntelliJ's production startup pipeline initializes the platform: plugin loading, kernel, EDT, VFS, services
3. `ScalaLspApplicationStarter.main()` is invoked after full platform initialization — registered as `com.intellij.appStarter` extension with `id="scala-lsp"` in `plugin.xml`
4. `BootstrapState.bootstrapComplete.countDown()` signals that the platform is ready — `ScalaLspServer.initialize()` waits on this latch (5-minute timeout)
5. Pre-warm projects are opened via `ProjectRegistry.openProject()` — each triggers external system refresh (sbt/Mill) and waits for `DumbService.waitForSmartMode()`
6. `DaemonServer.bind(port)` creates the TCP `ServerSocket`
7. State files (`daemon.pid`, `daemon.port`, `daemon.projects`) written to `~/.cache/intellij-scala-lsp/`
8. `DaemonServer.acceptLoop()` blocks, accepting connections

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

- `scalij --stop` sends a `shutdown` JSON-RPC message to the daemon port
- `DaemonServer.stop()` closes the `ServerSocket`, calls `ProjectRegistry.closeAll()`
- JVM shutdown hook deletes state files

### Auto-Start (connect mode)

When `scalij` is called without `--daemon`:
1. Checks `daemon.pid` + `daemon.port` — if daemon alive, proxies via `socat`
2. If not running, starts daemon in background, waits for `daemon.port` file (up to 60s), then proxies

## Project Management

`ProjectRegistry` is a thread-safe singleton (`ConcurrentHashMap`) shared across all sessions:

- `openProject(path)` — `computeIfAbsent` prevents double-open races. Opens the project, registers missing JDKs, triggers external system refresh (sbt/Mill), waits for smart mode, returns the `Project` instance.
- `acquireProject(path)` — increments reference count for a project (used by per-session managers)
- `releaseProject(path)` — decrements reference count; schedules project close after a 30-second grace period if count reaches zero
- `listProjects()` — returns all open projects
- `closeAll()` — daemon shutdown only
- Open project list persisted to `~/.cache/intellij-scala-lsp/daemon.projects`

`IntellijProjectManager` is per-session:
- Holds a reference to the shared `Project` from the registry
- Manages per-session state: `virtualFileCache` (URI-to-VirtualFile mapping)
- `smartReadAction()` wraps operations in `DumbService.runReadActionInSmartMode` to block until indexing completes
- In daemon mode, `closeProject()` releases the project reference without disposing the shared `Project`

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

**MCP over direct LSP for Claude Code.** Claude Code's built-in LSP support exposes only 9 operations. The MCP server bridges LSP methods as 12 MCP tools with better ergonomics for AI use (symbol-name-based lookup, 1-indexed positions, full source extraction for definitions).

---

For LSP provider mappings, shared utilities, classloader details, JSON-RPC wiring, bootstrap process, launcher internals, and state/caching layout, see [Implementation Details](implementation.md).
