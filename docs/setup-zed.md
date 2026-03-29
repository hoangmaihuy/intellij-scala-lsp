# Zed Setup

## Prerequisites

- **Rust** with `wasm32-wasip2` target (the extension compiles to WASM)

```bash
# Install Rust if needed
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# The setup script installs the wasm target automatically
```

## Install

```bash
scalij --setup-zed
```

This:
1. Creates a Zed extension at `~/.local/share/zed/extensions/intellij-scala-lsp/`
2. Configures `~/.config/zed/settings.json` with the LSP server settings
3. Sets `scalij` as the Scala language server (disables Metals)

After setup:
1. Open Zed
2. `Cmd+Shift+P` → "zed: install dev extension"
3. Select the extension directory shown in the setup output

## What You Get

All standard Zed language features for Scala:
- Go to Definition / Type Definition / Implementation
- Find All References
- Hover (type info + ScalaDoc)
- Code Completion with auto-import
- Signature Help
- Rename Symbol
- Code Actions
- Document / Workspace Symbols
- Diagnostics
- Formatting
- Semantic Tokens
- Code Lens
- Inlay Hints
- Call Hierarchy / Type Hierarchy

## Configuration

The setup script adds this to `~/.config/zed/settings.json`:

```json
{
  "lsp": {
    "intellij-scala-lsp": {
      "binary": {
        "env": {
          "PATH": "~/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
        }
      }
    }
  },
  "languages": {
    "Scala": {
      "language_servers": ["intellij-scala-lsp", "!metals"]
    },
    "Java": {
      "language_servers": ["intellij-scala-lsp", "..."]
    }
  }
}
```

To customize the launcher path, set the `INTELLIJ_SCALA_LSP_PATH` environment variable.

## First Launch

Same as other editors — the daemon downloads IntelliJ + Scala plugin on first run (~1.5 GB, cached) and indexes the project (1-5 min). Subsequent sessions reuse the running daemon.

## Troubleshooting

### Extension not loading

Make sure you installed the dev extension via `Cmd+Shift+P` → "zed: install dev extension" after running setup.

### Language server not starting

Check that `scalij` is on your `PATH`:

```bash
which scalij
```

If not, the `PATH` in the Zed settings may need updating.

### Conflicts with Metals

The setup script disables Metals for Scala (`"!metals"` in the config). If you want both, change `"!metals"` to `"metals"` in `~/.config/zed/settings.json`.

## Uninstall

```bash
rm -rf ~/.local/share/zed/extensions/intellij-scala-lsp
```

Then remove the `intellij-scala-lsp` entries from `~/.config/zed/settings.json`.
