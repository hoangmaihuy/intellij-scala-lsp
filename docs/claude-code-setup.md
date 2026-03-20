# Setting Up intellij-scala-lsp for Claude Code

This guide walks you through setting up the IntelliJ Scala LSP server as a Claude Code plugin, giving Claude Code deep Scala code intelligence powered by IntelliJ IDEA's analysis engine.

## What You Get

Once configured, Claude Code gains these capabilities for `.scala` files:

| Capability | LSP Method | What It Does |
|------------|-----------|--------------|
| Go to Definition | `textDocument/definition` | Navigate to where a symbol is defined |
| Find References | `textDocument/references` | Find all usages of a symbol across files |
| Hover | `textDocument/hover` | Get type info and ScalaDoc for any symbol |
| Diagnostics | `textDocument/publishDiagnostics` | Real-time error detection after edits |
| Completion | `textDocument/completion` | Auto-complete with IntelliJ-quality suggestions |
| Rename | `textDocument/rename` | Safe cross-file symbol renaming |
| Type Hierarchy | `typeHierarchy/*` | Explore Scala class/trait inheritance |
| Call Hierarchy | `callHierarchy/*` | Trace who calls what |
| Code Actions | `textDocument/codeAction` | Quick fixes and refactoring suggestions |
| Document Symbols | `textDocument/documentSymbol` | File outline (classes, methods, vals) |
| Workspace Symbols | `workspace/symbol` | Project-wide symbol search |
| Type Definition | `textDocument/typeDefinition` | Navigate from value to its type's definition |
| Implementation | `textDocument/implementation` | Find all implementations of a trait/class |
| Inlay Hints | `textDocument/inlayHint` | Inferred type and parameter name hints |
| Execute Command | `workspace/executeCommand` | Organize imports, reformat code |

## Prerequisites

- **macOS** or **Linux** (aarch64 or x86_64)
- **JDK 21+** (or IntelliJ's bundled JBR will be used)
- **sbt** installed (`brew install sbt` on macOS)
- **socat** installed (`brew install socat` on macOS, `apt install socat` on Linux) -- required for stdio-to-TCP proxying in daemon mode
- **Claude Code** installed and working

## Setup (One-Time, Per Machine)

Each developer runs this once to build the LSP server and register it with Claude Code:

```bash
git clone <repo-url> intellij-scala-lsp
cd intellij-scala-lsp
./setup-claude-code.sh
```

This script:
1. Builds the LSP server JAR with sbt
2. Generates the `.lsp.json` config with the correct launcher path
3. Adds a local marketplace to Claude Code
4. Installs the `intellij-scala-lsp` plugin to user scope

Restart Claude Code (or run `/reload-plugins`) after it completes.

## Enable for a Scala Project

Once the plugin is installed on your machine, enable it for your Scala project so it activates automatically:

```bash
cd /path/to/your/scala/project
claude plugin install intellij-scala-lsp@intellij-scala-lsp --scope project
```

This adds the plugin to `.claude/settings.json` in the project. Commit this file to version control so teammates get it too.

**What `--scope project` does:** It writes the plugin reference into `.claude/settings.json` at the project root. When any developer opens this project with Claude Code, the plugin activates — provided they've already run `./setup-claude-code.sh` on their machine.

### Team Setup

For a team working on a Scala project:

1. **Each developer** runs `./setup-claude-code.sh` once (builds the JAR, registers the marketplace)
2. **One developer** runs `claude plugin install intellij-scala-lsp@intellij-scala-lsp --scope project` and commits `.claude/settings.json`
3. **Everyone else** gets the plugin activated automatically when they open the project

## Verify It Works

### Check plugin status

Inside Claude Code, run:

```
/plugin
```

Go to the **Installed** tab. You should see `intellij-scala-lsp@intellij-scala-lsp` listed and enabled.

Go to the **Errors** tab to check for any issues. Common errors:
- `Executable not found in $PATH` — the launcher script isn't executable or can't be found
- Server timeout — IntelliJ is still downloading/indexing (first launch takes 2-5 minutes)

### Check LSP is connected

Run `/reload-plugins` and look for the LSP server count in the output:

```
/reload-plugins
```

You should see something like: `Reloaded: ... 1 LSP server`

### Test diagnostics

The easiest way to confirm the LSP is working — edit a `.scala` file to introduce a type error:

```
# Ask Claude to edit a file with an intentional error
> change the return type of method foo to String but leave the implementation returning Int
```

Claude should see the diagnostic (type mismatch error) immediately after the edit and fix it in the same turn. You can press **Ctrl+O** when "diagnostics found" appears to see them inline.

### Test code navigation

Try these prompts to exercise different LSP features:

```
# Go to definition
> go to the definition of MyClass

# Find references
> find all references to the process method

# Hover/type info
> what is the type of this variable?

# Rename
> rename the method oldName to newName across all files
```

### Test from the command line

You can also test the daemon directly outside Claude Code:

```bash
# Start the daemon (if not already running)
./launcher/launch-lsp.sh --daemon

# Send an initialize request via socat
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"rootUri":"file:///path/to/project"}}' | \
  socat - TCP:localhost:5007 | head -c 500
```

If working, you'll see a JSON response with `serverCapabilities`.

## First Launch Behavior

The first time the LSP server starts, the launcher automatically starts the daemon if it is not already running:

1. **IntelliJ download** (~1.5 GB) -- cached at `~/.cache/intellij-scala-lsp/` for future use
2. **Scala plugin download** (~200 MB) -- also cached
3. **Daemon startup** -- the IntelliJ platform boots and `DaemonServer` begins listening on TCP port 5007
4. **Project indexing** -- IntelliJ builds its code model on first `initialize` request (1-5 minutes depending on project size)

Subsequent sessions connect to the already-running daemon instantly. Projects that have been opened before reuse cached indices and the already-loaded `ProjectRegistry` entry, so responses begin within seconds.

The `startupTimeout` is set to 300 seconds (5 minutes) by default to accommodate first-launch indexing. You can pre-warm projects by starting the daemon with project paths: `./launcher/launch-lsp.sh --daemon /path/to/project`.

## Troubleshooting

### Server doesn't start

Test the launcher directly:

```bash
./launcher/launch-lsp.sh --daemon
```

Look for errors on stderr. Common issues:
- **JAR not found**: Run `sbt "lsp-server/packageArtifact"` in the intellij-scala-lsp directory
- **Java not found**: Set `JAVA_HOME` or install JDK 21+
- **Download fails**: Check network connectivity; IntelliJ and Scala plugin download from JetBrains servers
- **socat not found**: Install socat (`brew install socat` on macOS, `apt install socat` on Linux)

### Daemon not running

Check if the daemon is listening:

```bash
# Check if daemon is running on the default port
lsof -i :5007
```

If not running, start it manually:

```bash
./launcher/launch-lsp.sh --daemon
```

### Port conflict

If port 5007 is already in use by another process:

```bash
# Use a different port
LSP_PORT=5008 ./launcher/launch-lsp.sh --daemon
```

### Plugin not loading

Run Claude Code with debug output:

```bash
claude --debug
```

Or within the TUI, use `/debug` to toggle debug mode. Check `/plugin` Errors tab for LSP-specific errors.

### Startup timeout

For very large projects, increase the timeout. Edit the `.lsp.json` inside the plugin cache:

```bash
# Find and edit the cached plugin's .lsp.json
find ~/.claude/plugins/cache -name ".lsp.json" -path "*intellij-scala-lsp*"
```

Change `startupTimeout` to a higher value (in milliseconds):

```json
"startupTimeout": 600000
```

### Memory issues

The daemon shares a single JVM across all sessions. By default the launcher allocates 2 GB heap. For large projects, set the `LSP_HEAP_SIZE` environment variable:

```bash
LSP_HEAP_SIZE=4g ./launcher/launch-lsp.sh --daemon
```

### Diagnostics not appearing

Diagnostics are push-based via IntelliJ's `DaemonCodeAnalyzer`. They only fire for files opened via `textDocument/didOpen`. If Claude edits a file but doesn't see diagnostics, the file may not have been opened with the LSP server yet.

## Uninstall

```bash
# Remove the plugin
claude plugin uninstall intellij-scala-lsp@intellij-scala-lsp

# Remove the marketplace
claude plugin marketplace remove intellij-scala-lsp

# (Optional) Remove cached IntelliJ and Scala plugin downloads
rm -rf ~/.cache/intellij-scala-lsp
```

## Architecture

```
Claude Code (or any LSP client)
    | (stdio)
    v
launch-lsp.sh
    | socat stdio<->TCP proxy
    v
DaemonServer (TCP port 5007)
    | per-session LSP (lsp4j JSON-RPC)
    v
ScalaLspServer <-> ScalaTextDocumentService (21 LSP methods)
               <-> ScalaWorkspaceService (symbol, executeCommand)
    | (delegates to providers)
    v
ProjectRegistry (shared project instances)
    v
IntelliJ Platform (headless)
    |-- PSI (Abstract Syntax Tree)
    |-- Indexing & Smart Mode
    |-- DaemonCodeAnalyzer (diagnostics)
    +-- intellij-scala plugin (Scala-specific resolution)
```

The LSP server runs as a daemon inside a headless IntelliJ IDEA process. The `DaemonServer` accepts TCP connections and creates a new `ScalaLspServer` session for each client. The `ProjectRegistry` ensures projects are opened once and shared across sessions, so multiple Claude Code windows working on the same project share indices and memory. All code intelligence comes from the same engine as IntelliJ IDEA's Scala plugin -- same accuracy for type inference, implicit resolution, macro expansion, and cross-file analysis.

## Available Commands

Claude Code can invoke these via `workspace/executeCommand`:

| Command | Purpose |
|---------|---------|
| `scala.organizeImports` | Remove unused imports, sort remaining |
| `scala.reformat` | Reformat file using IntelliJ's code style |

## Development

### Running Tests

```bash
sbt test
```

236 tests (integration + unit) verify all LSP features with real IntelliJ PSI and Scala plugin loaded.

### Project Structure

```
intellij-scala-lsp/
|-- setup-claude-code.sh              # One-command setup for Claude Code
|-- launcher/launch-lsp.sh            # Runtime launcher (downloads IntelliJ, starts server)
|-- claude-code/                       # Claude Code plugin + marketplace
|   |-- .claude-plugin/marketplace.json
|   +-- intellij-scala-lsp/           # The plugin itself
|       |-- .claude-plugin/plugin.json
|       +-- .lsp.json
|-- lsp-server/
|   |-- src/                           # Main source
|   |   |-- ScalaLspServer.scala
|   |   |-- ScalaTextDocumentService.scala
|   |   |-- ScalaWorkspaceService.scala
|   |   +-- intellij/                  # One provider per LSP feature (18 files)
|   |-- resources/META-INF/plugin.xml
|   +-- test/src/integration/          # Integration tests with real PSI
+-- build.sbt
```
