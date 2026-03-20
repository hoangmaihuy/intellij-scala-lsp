# Daemon Launcher for Multi-Project LSP Server

## Problem

The IntelliJ Scala LSP server takes ~25s to bootstrap on first request (IntelliJ platform init + project indexing). Claude Code starts the server lazily on first `.scala` file access, so every new session pays this cost. Users working on multiple projects across multiple Claude Code sessions amplify the problem.

## Solution

A persistent daemon that bootstraps IntelliJ once, manages multiple projects in one JVM, and serves LSP sessions over TCP. Claude Code connects instantly to the already-warm daemon.

## Architecture

One daemon JVM manages all LSP sessions:

```
launch-lsp.sh --daemon ~/Work/osiris ~/Work/jsoniter
       │
       ▼
┌─────────────────────────────────────┐
│  Daemon JVM (port 5007)             │
│                                     │
│  IntelliJ Platform (singleton)      │
│  ├── Project "osiris"     (warm)    │
│  ├── Project "jsoniter"   (warm)    │
│  └── Project "foo"  (on-demand)     │
│                                     │
│  TCP Server                         │
│  ├── Client 1 ↔ Session(osiris)     │
│  ├── Client 2 ↔ Session(osiris)     │
│  └── Client 3 ↔ Session(jsoniter)   │
└─────────────────────────────────────┘
```

IntelliJ natively supports multiple open projects in one JVM. Each project gets its own `Project` instance, `DumbService`, indexes, and module structure. They share the `Application` singleton, plugins, VFS, and EDT — same as IntelliJ IDE itself.

## Connection Flow

### Launcher modes

```
launch-lsp.sh --daemon [project...]   # Start daemon, pre-warm listed projects
launch-lsp.sh --stop                  # Stop the daemon
launch-lsp.sh [project]               # Connect to daemon (auto-start if needed)
```

### Connect mode (what Claude Code calls)

1. Read `~/.cache/intellij-scala-lsp/daemon.pid` and `daemon.port`
2. Verify daemon is alive: check PID exists **and** `daemon.port` file was written by this PID (prevents connecting to wrong process on the same port)
3. If not running: start daemon in background, wait for `daemon.port` file to appear (up to 60s) — do NOT poll the port blindly
4. Proxy stdio to TCP: `socat STDIO TCP:localhost:$PORT` (required dependency; see Proxy section)

### Daemon startup

1. Bootstrap IntelliJ platform via `TestApplicationManager` with **globally installed** lenient error processor (not thread-local `executeWith`)
2. Open and index pre-warmed projects (passed as args)
3. Bind TCP server to port 5007 (override with `LSP_PORT` env var)
4. **After successful bind:** write `daemon.pid` and `daemon.port` to `~/.cache/intellij-scala-lsp/`
5. Accept connections in loop

### Per-connection handling (inside daemon)

1. Accept TCP socket
2. Read bytes from socket until a complete JSON-RPC message (`Content-Length: N\r\n\r\n{...}`) is parsed to extract `initialize` params
3. **Replay consumed bytes:** wrap remaining socket `InputStream` in `SequenceInputStream(parsedBytes, socketInputStream)` so lsp4j receives the full `initialize` message
4. Extract `rootUri` → project path
5. Look up project in `ProjectRegistry`, or open it on demand (with JDK registration, smart mode wait)
6. Create a per-session `IntellijProjectManager` (with reference to shared `Project`)
7. Create a new `ScalaLspServer(path, projectManager, daemonMode=true)` for this session
8. Wire the replayed input stream + socket output stream to lsp4j `Launcher` (same bridge method workarounds as current `LspLauncher`)
9. On disconnect: clean up session state only; project stays open in registry

## Components

### ProjectRegistry (new)

Shared singleton that manages `Project` instances:

```
ProjectRegistry
  openProjects: ConcurrentHashMap[String, Project]  # canonical path → Project
  openProject(path: String): Project                # open or return cached
  getProject(path: String): Option[Project]         # lookup only
  closeAll(): Unit                                   # for daemon shutdown
```

- Thread-safe (multiple sessions may request projects concurrently)
- Uses `synchronized` or `computeIfAbsent` to prevent double-open races
- Handles JDK registration on first open
- Waits for smart mode before returning from `openProject`
- Projects are never closed during normal operation (kept warm for future clients)
- `closeAll()` called only during daemon shutdown

### DaemonServer (new)

TCP server that accepts connections and creates LSP sessions:

```
DaemonServer
  start(port: Int): Unit
  stop(): Unit                    # stops accepting, closes all sessions
  activeSessions: AtomicInteger   # for monitoring
```

- Each accepted connection runs in its own daemon thread
- Parses first JSON-RPC message, replays bytes via `SequenceInputStream`
- Creates per-session `IntellijProjectManager` sharing the `Project` from registry
- Creates per-session `ScalaLspServer` in daemon mode
- Wires socket streams to lsp4j `Launcher` using the same pattern as `LspLauncher.java`
- On `stop()`: closes server socket, interrupts session threads, calls `ProjectRegistry.closeAll()`

Written in Java (same reason as `LspLauncher.java` — needs to work with lsp4j's `Launcher.Builder` and avoid Scala 3 bridge method issues).

### ScalaLspMain changes

Add mode dispatch **before** starting any threads:

```
--daemon proj1 proj2  →  install global error processor, boot IntelliJ, pre-warm, start DaemonServer
--stop                →  read daemon.port, send TCP shutdown signal, exit (NO bootstrap)
projectPath (or no args) → current stdio mode (unchanged, for testing/fallback)
```

The `bootstrapComplete` latch moves from `ScalaLspMain` to a shared `BootstrapState` object so both `ScalaLspMain` (stdio mode) and `DaemonServer` (daemon mode) can reference it without coupling.

**Critical: `--stop` mode must NOT start the bootstrap thread or initialize IntelliJ.** It only reads the port file, sends a shutdown message over TCP, and exits.

### ScalaLspServer changes

Add `daemonMode: Boolean` constructor parameter (default `false` for backward compatibility):

- **`shutdown()`**: in daemon mode, cleans up per-session state only (does NOT call `projectManager.closeProject()`). In stdio mode, behavior unchanged.
- **`exit()`**: in daemon mode, no-op (does NOT call `System.exit`). In stdio mode, behavior unchanged.

### IntellijProjectManager changes

Split into two concerns:

1. **`ProjectRegistry`** (new, shared): manages `Project` instances, JDK registration, smart mode wait
2. **`IntellijProjectManager`** (per-session): holds reference to shared `Project` from registry, manages per-session state:
   - `virtualFileCache` (per-session URI → VirtualFile mapping)
   - All `findVirtualFile`, `findPsiFile`, `smartReadAction` methods stay here
   - `openProject()` delegates to `ProjectRegistry.openProject()`
   - `closeProject()` in daemon mode: clears per-session state only, does NOT close the shared `Project`

### Document sync and concurrent sessions

**Limitation:** `DocumentSyncManager.updateDocument()` writes to IntelliJ's shared `Document` model (VFS-backed). Two sessions on the same project editing the same file will overwrite each other's in-memory document state.

**Design decision:** Allow only **one active LSP session per project** at a time. When a second session connects with the same `rootUri`:
- The existing session continues (it already has the project open)
- The new session gets the same `Project` instance
- Both sessions share the same document state (last write wins)

This matches how IntelliJ itself works — one editor per file, shared state. In practice, multiple Claude Code sessions on the same project are rare, and when they happen the files being edited are usually different.

### launcher/launch-lsp.sh changes

Add `--daemon` and `--stop` modes. In connect mode:

```bash
case "$1" in
  --daemon) shift; start_daemon "$@" ;;
  --stop)   stop_daemon ;;
  *)        connect_or_start "$@" ;;
esac
```

**Connect flow:**
```bash
connect_or_start() {
  if daemon_running; then
    proxy_stdio_to_tcp "$DAEMON_PORT"
  else
    start_daemon_background "$@"
    wait_for_port_file 60   # wait for daemon.port file, not blind port poll
    DAEMON_PORT=$(cat "$CACHE_DIR/daemon.port")
    proxy_stdio_to_tcp "$DAEMON_PORT"
  fi
}
```

**Proxy:** `socat` is a required dependency. Document this in setup instructions. The bash `/dev/tcp` fallback has known issues (orphaned background `cat` process on disconnect) and is not reliable enough for production use.

```bash
proxy_stdio_to_tcp() {
  exec socat STDIO TCP:localhost:$1
}
```

### Lenient error processor (global install)

The current `LoggedErrorProcessor.executeWith()` is thread-local — it only covers the calling thread. In daemon mode, lsp4j session threads, EDT, and IntelliJ worker threads also need lenient error handling.

**Fix:** Replace `executeWith` with a global install before `TestApplicationManager.getInstance()`:

```scala
// Install globally, not per-thread
LoggedErrorProcessor.setNewInstance(new LoggedErrorProcessor { ... })
```

If `setNewInstance` is not available, use `executeWith` on the bootstrap thread and verify that `TestApplicationManager` propagates it to child threads (or install it as a JVM-wide default via reflection on the `LoggedErrorProcessor` default field).

## State Files

```
~/.cache/intellij-scala-lsp/
  daemon.pid           # PID of daemon process (written AFTER port bind)
  daemon.port          # TCP port number (written AFTER successful bind)
  system/              # IntelliJ indexes (shared, persistent)
  config/              # IntelliJ config (JDK table, etc.)
  lsp-server-stripped.jar  # Stripped JAR cache
```

**Important:** `daemon.pid` and `daemon.port` are written **only after** the TCP port is successfully bound. The launcher waits for these files to appear, not for the port to respond. This prevents connecting to a wrong process that happens to occupy the same port.

## Error Handling

| Scenario | Behavior |
|---|---|
| Daemon crash | Launcher detects stale PID, cleans pidfile, auto-restarts |
| Port in use | Daemon fails with clear error; user overrides with `LSP_PORT` |
| Client disconnect | Session cleaned up; project stays open for future clients |
| New project on demand | Daemon opens + indexes on first `initialize`; first request slow, subsequent instant |
| Multiple clients same project | Share same `Project` and document state (last write wins) |
| `--stop` | Daemon closes all projects and exits gracefully |
| `--stop` then `--daemon` | Requires new JVM (TestApplicationManager is single-use per JVM) |
| JDK table stale | JDK table copied at first daemon start; restart daemon after adding new JDKs in IntelliJ |

## Files Changed

| File | Change |
|---|---|
| `lsp-server/src/org/jetbrains/scalalsP/DaemonServer.java` | **New.** TCP server, per-connection session creation, initialize intercept with stream replay |
| `lsp-server/src/org/jetbrains/scalalsP/ProjectRegistry.scala` | **New.** Thread-safe shared project instance management |
| `lsp-server/src/org/jetbrains/scalalsP/BootstrapState.scala` | **New.** Shared bootstrap latch (replaces `ScalaLspMain.bootstrapComplete`) |
| `lsp-server/src/ScalaLspMain.scala` | Mode dispatch (`--daemon`/`--stop`/stdio); global error processor install |
| `lsp-server/src/ScalaLspServer.scala` | Add `daemonMode` flag; guard `shutdown()`/`exit()` behavior |
| `lsp-server/src/intellij/IntellijProjectManager.scala` | Delegate to `ProjectRegistry`; per-session state only; daemon-aware `closeProject()` |
| `launcher/launch-lsp.sh` | Add `--daemon`, `--stop` modes; `socat` proxy; `wait_for_port_file` |

## What Stays the Same

All LSP providers, `LspLauncher.java` (used as reference pattern for `DaemonServer`), `ScalaTextDocumentService`, `ScalaWorkspaceService`, `IntellijBootstrap.java`, and all existing tests. The session-level code is identical — only the transport layer and lifecycle management change.

## Dependencies

- `socat` — required for stdio↔TCP proxy. Install via `brew install socat` (macOS) or `apt install socat` (Linux). Documented in setup instructions.
