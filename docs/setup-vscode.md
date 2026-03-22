# VS Code Setup

## Install

```bash
intellij-scala-lsp --setup-vscode
```

This creates a VS Code extension at `~/.vscode/extensions/intellij-scala-lsp/` that:
- Activates on `.scala` and `.sc` files
- Starts the LSP server via the `intellij-scala-lsp` launcher
- Shows a status bar item with server state (Starting → Indexing → Ready)
- Logs all LSP requests to the "IntelliJ Scala LSP" output channel

## What You Get

All standard VS Code language features for Scala:
- Go to Definition / Type Definition / Implementation
- Find All References
- Hover (type info + ScalaDoc)
- Code Completion with auto-import
- Signature Help
- Rename Symbol
- Code Actions (quick fixes, refactorings)
- Document / Workspace Symbols
- Diagnostics (errors and warnings)
- Formatting (document and range)
- Semantic Tokens (syntax highlighting)
- Code Lens ("Overrides" annotations)
- Folding Ranges
- Selection Ranges
- Inlay Hints (inferred types)
- Call Hierarchy / Type Hierarchy

## Configuration

In VS Code settings (`Cmd+,`):

| Setting | Description | Default |
|---|---|---|
| `intellijScalaLsp.launcher` | Path to the `intellij-scala-lsp` launcher | Auto-detected from `$PATH` |

## First Launch

On first launch:
1. The daemon starts and downloads IntelliJ + Scala plugin if needed (~1.5 GB, cached)
2. Project indexing runs on first open (1-5 min depending on project size)
3. The status bar shows "Scala LSP: Indexing" during this time

Subsequent VS Code windows reuse the running daemon instantly.

## Troubleshooting

### Extension not activating

1. Open a `.scala` file
2. Check the "IntelliJ Scala LSP" output channel (`Cmd+Shift+U` → select from dropdown)
3. Look for errors in the log

### Server timeout

For very large projects, the default timeout may not be enough. Start the daemon manually first:

```bash
intellij-scala-lsp --daemon /path/to/project
```

Then reload VS Code.

### Disabling Metals

If you have Metals installed, you may want to disable it to avoid conflicts:

1. Open Extensions (`Cmd+Shift+X`)
2. Find "Scala (Metals)" and disable it for the workspace

## Uninstall

```bash
rm -rf ~/.vscode/extensions/intellij-scala-lsp
```
