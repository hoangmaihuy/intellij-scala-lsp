# Claude Code Setup

There are two ways to connect Claude Code to intellij-scala-lsp:

| | MCP Server (`--setup-claude-code-mcp`) | LSP Plugin (`--setup-claude-code-lsp`) |
|---|---|---|
| **Tools** | 12 MCP tools (definition, references, hover, rename, code actions, formatting, etc.) | 9 built-in LSP operations |
| **Interface** | Symbol-name-based (e.g. `definition("MyClass")`) | File+position-based |
| **Diagnostics** | On-demand via `diagnostics` tool | Push-based (automatic after edits) |
| **Setup** | Adds MCP server to `~/.claude/settings.json` | Installs Claude Code LSP plugin |

**Recommended:** Use the MCP server for the broadest feature coverage.

## MCP Server (Recommended)

```bash
intellij-scala-lsp --setup-claude-code-mcp
```

This adds the MCP server to `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "intellij-scala-lsp": {
      "command": "node",
      "args": ["/path/to/mcp-server/dist/index.js"]
    }
  }
}
```

The MCP server auto-starts the daemon and picks up `cwd` from Claude Code automatically.

### Verify

Inside Claude Code, run `/mcp` to check the server is connected. Then try:

```
> go to the definition of MyClass
> find all references to the process method
> what is the type of this variable?
```

### Environment Variables

| Variable | Description | Default |
|---|---|---|
| `LOG_LEVEL` | MCP server log verbosity: `debug`, `info`, `warn`, `error` | `info` |

## LSP Plugin

```bash
intellij-scala-lsp --setup-claude-code-lsp
```

This installs the LSP plugin to Claude Code. Restart Claude Code (or run `/reload-plugins`) after setup.

### Verify

Inside Claude Code, run `/plugin` and check the **Installed** tab for `intellij-scala-lsp@intellij-scala-lsp`. Check the **Errors** tab for any issues.

Run `/reload-plugins` — you should see `1 LSP server` in the output.

### Per-Project Activation

Enable the LSP plugin for a specific project:

```bash
cd /path/to/your/scala/project
claude plugin install intellij-scala-lsp@intellij-scala-lsp --scope project
```

This writes to `.claude/settings.json` in the project. Commit this file so teammates get it too.

### Team Setup

1. **Each developer** runs `intellij-scala-lsp --setup-claude-code-lsp` once
2. **One developer** runs `claude plugin install intellij-scala-lsp@intellij-scala-lsp --scope project` and commits `.claude/settings.json`
3. **Everyone else** gets the plugin activated automatically

## First Launch

On first launch, the daemon:

1. Downloads IntelliJ (~1.5 GB, cached)
2. Downloads Scala plugin (~200 MB, cached)
3. Starts the daemon on TCP port 5007
4. Indexes the project on first `initialize` (1-5 min depending on project size)

Subsequent sessions connect to the already-running daemon instantly. The `startupTimeout` is 300s by default.

Pre-warm projects: `intellij-scala-lsp --daemon /path/to/project`

## Troubleshooting

### Server doesn't start

```bash
# Test the launcher directly
intellij-scala-lsp --daemon
```

Common issues:
- **JAR not found**: Run `sbt "lsp-server/packageArtifact"`
- **Java not found**: Set `JAVA_HOME` or install JDK 21+
- **socat not found**: `brew install socat` (macOS) or `apt install socat` (Linux)

### Daemon not running

```bash
lsof -i :5007
```

If not running: `intellij-scala-lsp --daemon`

### Port conflict

```bash
LSP_PORT=5008 intellij-scala-lsp --daemon
```

### Memory issues

```bash
LSP_HEAP_SIZE=4g intellij-scala-lsp --daemon
```

### Diagnostics not appearing

Diagnostics are push-based and only fire for files opened via `textDocument/didOpen`. If using the MCP server, call the `diagnostics` tool explicitly.

## Uninstall

```bash
# MCP server
# Remove the "intellij-scala-lsp" entry from ~/.claude/settings.json mcpServers

# LSP plugin
claude plugin uninstall intellij-scala-lsp@intellij-scala-lsp
claude plugin marketplace remove intellij-scala-lsp

# Cached data
rm -rf ~/.cache/intellij-scala-lsp
```
