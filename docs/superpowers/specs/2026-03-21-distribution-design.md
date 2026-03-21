# Distribution Design: intellij-scala-lsp

## Problem

Users must clone the repo, run sbt, and use a machine-specific bash launcher to run the LSP server. The launcher only works on the author's machine due to hardcoded paths and macOS-only assumptions.

## Goals

- One-line install for end users (`curl | bash`)
- Zero manual dependency management (SDK, plugin, JRE all auto-resolved)
- Works for users with or without IntelliJ installed
- Self-updating
- macOS and Linux support (Windows out of scope)
- Local development without setup scripts

## Non-Goals

- Windows support
- Package manager distribution (future work)
- Bundling IntelliJ SDK in the release archive

---

## Architecture

### Distribution Artifacts (GitHub Releases)

Each release (triggered by `v*` git tags) publishes:

| Artifact | Contents | Size |
|---|---|---|
| `intellij-scala-lsp-<version>.tar.gz` | LSP server JARs (lsp-server.jar, lsp4j, gson) | ~1.5MB |
| `intellij-scala-lsp` | The smart launcher script | ~20KB |
| `install.sh` | Thin installer, delegates to launcher | ~2KB |

The IntelliJ SDK and Scala plugin are **not** bundled — they are downloaded on first run.

### Directory Layout on User's Machine

```
~/.local/bin/
  intellij-scala-lsp              # Launcher script (in PATH)

~/.cache/intellij-scala-lsp/
  version                         # Installed version string
  last-update-check               # Timestamp for daily update check
  lsp-server/                     # LSP server JARs
    lsp-server.jar
    org.eclipse.lsp4j-*.jar
    org.eclipse.lsp4j.jsonrpc-*.jar
    gson-*.jar
  sdk/                            # IntelliJ SDK (downloaded or symlinked)
    <build-number>/
      lib/                        # IntelliJ platform JARs
      plugins/
        java/                     # Java plugin
        scala/                    # Scala plugin
      jbr/                        # Bundled JBR (JDK 21)
      product-info.json
  daemon.pid                      # Daemon state
  daemon.port
  system/                         # IntelliJ system dir
  config/                         # IntelliJ config dir
```

### SDK Resolution (in order)

1. **Local IntelliJ detected** — Scan standard install locations (`/Applications/IntelliJ*` on macOS, `/opt/idea-*` on Linux). Symlink `sdk/<build>` to the installation. Find Scala plugin from IntelliJ's plugins or JetBrains config dirs.
2. **No IntelliJ found** — Download IntelliJ Community ZIP from `download.jetbrains.com`, extract to `sdk/`. Download Scala plugin ZIP from JetBrains marketplace.

---

## Launcher Script Modes

### `intellij-scala-lsp --install`

Called by `install.sh` after placing the launcher in PATH:

1. Download LSP server JARs from GitHub Release to `~/.cache/intellij-scala-lsp/lsp-server/`
2. Run SDK setup (detect local IntelliJ or download)
3. Write `~/.cache/intellij-scala-lsp/version`

### `intellij-scala-lsp [--daemon [projects...]]`

Normal run mode:

1. Check version against GitHub Releases API (non-blocking, cached — at most once per 24 hours via `last-update-check` timestamp). If update available, print notice but continue.
2. Resolve SDK (local IntelliJ symlink or cached download)
3. Assemble classpath from `product-info.json`
4. Launch JVM in daemon mode (TCP, using `socat` for stdio-to-TCP proxy) or stdio mode

### `intellij-scala-lsp --update`

1. Download new launcher script, replacing itself
2. Download new LSP server JARs
3. If IntelliJ SDK version changed, re-download SDK
4. Restart daemon if running

### `intellij-scala-lsp --stop`

Gracefully shut down daemon (existing behavior).

### Local Development Detection

The launcher detects if it's running from the repo (checks for `build.sbt` relative to its own location). If so, uses local build output (`lsp-server/target/plugin/`) for LSP JARs instead of `~/.cache/`. SDK resolution works the same either way.

---

## Installer Script (`install.sh`)

Invoked via:
```bash
curl -fsSL https://github.com/<owner>/intellij-scala-lsp/releases/latest/download/install.sh | bash
```

Steps:
1. Detect platform (`uname -s` / `uname -m`) — fail on unsupported
2. Download `intellij-scala-lsp` launcher from latest GitHub Release
3. Place in `~/.local/bin/`, create dir if needed, warn if not in PATH
4. `chmod +x`
5. Run `intellij-scala-lsp --install`
6. Print editor configuration instructions

---

## CI / Release Pipeline

**GitHub Actions workflow** triggered on `v*` tags:

1. `sbt lsp-server/packageArtifact` — build LSP server JARs
2. Tar JARs into `intellij-scala-lsp-<version>.tar.gz`
3. Create GitHub Release with all three artifacts
4. Release description includes IntelliJ SDK build number and Scala plugin version

### Version Pinning

The launcher script contains constants for the IntelliJ SDK build number and Scala plugin version it was built against. When the launcher self-updates, new constants may trigger an SDK re-download.

---

## sbt Task for Local Development

**`sbt lsp-server/runLsp`**:

1. Runs `packageArtifact` to ensure JARs are built
2. Resolves the SDK from sbt-idea-plugin's cache (`~/.intellij-scala-lspPluginIC/sdk/`)
3. Parses `product-info.json` for boot classpath and `--add-opens` flags
4. Forks a JVM with the correct classpath and flags
5. Passes through arguments: `sbt "lsp-server/runLsp --daemon"`

---

## Editor Integration

No setup scripts. Users configure their editor to run `intellij-scala-lsp` as the LSP server command.

**VS Code** (in `settings.json`):
```json
{ "scala.lsp.serverCommand": "intellij-scala-lsp" }
```

**Neovim** (in LSP config):
```lua
cmd = { "intellij-scala-lsp" }
```

**Claude Code** (in plugin.json):
```json
{ "command": "intellij-scala-lsp" }
```

**Local development**: Point at the repo's launcher path instead:
```
/path/to/repo/launcher/intellij-scala-lsp
```

---

## Files to Create / Modify / Delete

| Action | File |
|---|---|
| **Create** | `launcher/intellij-scala-lsp` (new smart launcher) |
| **Create** | `launcher/install.sh` |
| **Create** | `.github/workflows/release.yml` |
| **Create** | Custom sbt task in `build.sbt` |
| **Delete** | `launcher/launch-lsp.sh` (replaced by new launcher) |
| **Delete** | `setup-vscode.sh` |
| **Delete** | `setup-claude-code.sh` |
| **Modify** | `README.md` — install/usage instructions |
| **Modify** | Claude Code plugin.json — use `intellij-scala-lsp` command |
| **Modify** | VS Code extension — use `intellij-scala-lsp` command |
