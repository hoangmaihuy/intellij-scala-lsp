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
2. Verify daemon is alive: check PID exists and port responds
3. If not running: start daemon in background, poll port until ready (up to 60s)
4. Proxy stdio to TCP: `socat STDIO TCP:localhost:$PORT` (fallback to built-in bash proxy if socat unavailable)

### Daemon startup

1. Bootstrap IntelliJ platform via `TestApplicationManager` with lenient error processor
2. Open and index pre-warmed projects (passed as args)
3. Start TCP server on port 5007 (override with `LSP_PORT` env var)
4. Write `daemon.pid` and `daemon.port` to `~/.cache/intellij-scala-lsp/`
5. Accept connections in loop

### Per-connection handling (inside daemon)

1. Accept TCP socket
2. Intercept the first JSON-RPC message to extract `initialize` params
3. Extract `rootUri` → project path
4. Look up project in registry, or open it on demand (with JDK registration)
5. Create a new `ScalaLspServer(path, projectManager)` for this session
6. Wire TCP socket streams to lsp4j `Launcher` (same bridge method workarounds as current `LspLauncher`)
7. On disconnect: clean up session, keep project open for future clients

## Components

### ProjectRegistry (new)

Shared singleton that manages `Project` instances:

```
ProjectRegistry
  openProjects: Map[String, Project]          # path → Project
  openProject(path: String): Project          # open or return cached
  getProject(path: String): Option[Project]   # lookup only
```

- Thread-safe (multiple sessions may request projects concurrently)
- Handles JDK registration on first open
- Waits for smart mode before returning from `openProject`
- Projects are never closed (kept warm for future clients)

### DaemonServer (new)

TCP server that accepts connections and creates LSP sessions:

```
DaemonServer
  start(port: Int): Unit
  stop(): Unit
```

- Each accepted connection runs in its own thread
- Reads the first JSON-RPC message to get `initialize` params
- Creates per-session `IntellijProjectManager` sharing the `Project` from registry
- Creates per-session `ScalaLspServer` with the session's project manager
- Wires socket streams to lsp4j `Launcher` using the same pattern as `LspLauncher.java`

Written in Java (same reason as `LspLauncher.java` — needs to work with lsp4j's `Launcher.Builder` and avoid Scala 3 bridge method issues).

### ScalaLspMain changes

Add daemon mode to existing entry point:

```
args = "--daemon proj1 proj2"  →  boot IntelliJ, pre-warm projects, start DaemonServer
args = "--stop"                →  connect to daemon port, send shutdown
args = "projectPath"           →  current stdio mode (unchanged, for testing)
```

### IntellijProjectManager changes

Extract project open/close logic into `ProjectRegistry`. `IntellijProjectManager` becomes a per-session wrapper that holds:
- Reference to shared `Project` (from registry)
- Per-session `virtualFileCache`
- Per-session document sync state

### launcher/launch-lsp.sh changes

Add `--daemon` and `--stop` modes. In connect mode:

```bash
# Check daemon health
if daemon_running; then
  proxy_stdio_to_tcp "$DAEMON_PORT"
else
  start_daemon_background
  wait_for_port "$DAEMON_PORT" 60
  proxy_stdio_to_tcp "$DAEMON_PORT"
fi
```

Proxy implementation (in priority order):
1. `socat STDIO TCP:localhost:$PORT` (if installed)
2. Bash `/dev/tcp` fallback: `exec 3<>/dev/tcp/localhost/$PORT; cat <&3 & cat >&3`

## State Files

```
~/.cache/intellij-scala-lsp/
  daemon.pid           # PID of daemon process
  daemon.port          # TCP port number
  system/              # IntelliJ indexes (shared, persistent)
  config/              # IntelliJ config (JDK table, etc.)
  lsp-server-stripped.jar  # Stripped JAR cache
```

## Error Handling

| Scenario | Behavior |
|---|---|
| Daemon crash | Launcher detects stale PID, cleans pidfile, auto-restarts |
| Port in use | Daemon fails with clear error; user overrides with `LSP_PORT` |
| Client disconnect | Session cleaned up; project stays open for future clients |
| New project on demand | Daemon opens + indexes on first `initialize`; first request slow, subsequent instant |
| Multiple clients same project | Independent sessions sharing same `Project` instance |
| `--stop` | Daemon closes all projects and exits gracefully |

## Files Changed

| File | Change |
|---|---|
| `lsp-server/src/org/jetbrains/scalalsP/DaemonServer.java` | **New.** TCP server, per-connection session creation |
| `lsp-server/src/org/jetbrains/scalalsP/ProjectRegistry.scala` | **New.** Shared project instance management |
| `lsp-server/src/ScalaLspMain.scala` | Add `--daemon` and `--stop` arg handling |
| `lsp-server/src/intellij/IntellijProjectManager.scala` | Use `ProjectRegistry` for project open; keep per-session state |
| `launcher/launch-lsp.sh` | Add `--daemon`, `--stop` modes; add stdio↔TCP proxy in connect mode |
| `claude-code/intellij-scala-lsp/.claude-plugin/plugin.json` | No change needed (launcher handles daemon transparently) |

## What Stays the Same

All LSP providers, `ScalaLspServer`, `LspLauncher.java` (used as reference pattern), `ScalaTextDocumentService`, `ScalaWorkspaceService`, `IntellijBootstrap.java`, and all tests. The session-level code is identical — only the transport layer changes from stdio to TCP socket streams.
