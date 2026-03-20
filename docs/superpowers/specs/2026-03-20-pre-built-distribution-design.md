# Pre-Built Distribution Design

**Date:** 2026-03-20
**Status:** Draft
**Goal:** Allow users to use intellij-scala-lsp without cloning the repo or building from source.

## Overview

Provide a one-liner install script that downloads pre-built JARs from GitHub Releases, with auto-update on launch and automatic IntelliJ SDK download when no local installation is found. Targets Claude Code users primarily, with documented support for other LSP clients (Neovim, VS Code, Helix, etc.).

## Section 1: Release Artifacts & CI

### What Gets Released

A tarball `intellij-scala-lsp-<version>.tar.gz` containing:

```
intellij-scala-lsp/
├── lib/
│   ├── lsp-server.jar
│   ├── org.eclipse.lsp4j-0.23.1.jar
│   ├── org.eclipse.lsp4j.jsonrpc-0.23.1.jar
│   ├── gson-2.13.2.jar
│   └── (other runtime deps)
├── launcher/
│   └── launch-lsp.sh
└── version.txt
```

### GitHub Actions Workflow

File: `.github/workflows/release.yml`

- **Trigger:** Push of a git tag matching `v*`
- **Runner:** `ubuntu-latest` with JDK 21
- **Steps:**
  1. Checkout code
  2. Run `sbt lsp-server/packageArtifact`
  3. Package `target/plugin/intellij-scala-lsp/lib/` JARs + `launcher/launch-lsp.sh` + `version.txt` into tarball
  4. Create GitHub Release with the tarball attached

### Versioning

Tags like `v0.5.0`, `v0.6.0`. The `version` field in `build.sbt` (currently `0.5.0`) is the source of truth.

## Section 2: Install Script

### One-Liner

```bash
curl -fsSL https://raw.githubusercontent.com/<owner>/intellij-scala-lsp/main/install.sh | bash
```

### What `install.sh` Does

1. **Detect platform** — `uname -s` / `uname -m` (macOS + Linux, arm64 + x86_64). JARs are platform-independent but the script needs to know where to look for IntelliJ.

2. **Download latest release** — queries GitHub API (`repos/<owner>/intellij-scala-lsp/releases/latest`) to get the tarball URL, downloads and extracts to `~/.local/share/intellij-scala-lsp/`.

3. **Add to PATH** — creates a symlink `~/.local/bin/intellij-scala-lsp` pointing to the launcher script. Prints a message if `~/.local/bin` isn't in PATH.

4. **Register with Claude Code** (if installed) — detects if `claude` CLI is available, then:
   - Writes a plugin manifest (`plugin.json`) pointing to the installed launcher
   - Registers via Claude Code's local marketplace mechanism (same as current `setup-claude-code.sh`)

5. **Print editor config snippets** — for Neovim, VS Code, Helix, etc. showing how to point their LSP client at `~/.local/bin/intellij-scala-lsp`.

6. **Dependency check** — warns if `socat` is not installed and suggests `brew install socat` / `apt install socat`.

## Section 3: Auto-Update on Launch

Added to the existing `launch-lsp.sh` launcher script.

### Flow

1. Read `~/.local/share/intellij-scala-lsp/version.txt` for current installed version.

2. Check `~/.local/share/intellij-scala-lsp/.last-update-check` — only query GitHub API if last check was >1 hour ago (rate limiting).

3. Query `https://api.github.com/repos/<owner>/intellij-scala-lsp/releases/latest` with a **3-second timeout**. On failure/timeout, silently skip and proceed.

4. Compare remote tag against local version. If newer:
   - Download new tarball to temp directory
   - Extract and replace `~/.local/share/intellij-scala-lsp/` contents (preserving `ide/`, `cache/`, `.last-update-check`)
   - Stop running daemon (if any) since JARs changed
   - Log: `Updated intellij-scala-lsp from X.Y.Z to A.B.C`

5. Update `.last-update-check` with current timestamp.

### Opt-Out

`--no-update-check` flag to bypass for users who want full control.

### Edge Case

If the daemon is already running and serving other connections, the update stops it. Next connection auto-starts a fresh daemon with new JARs (existing behavior).

## Section 4: IntelliJ SDK Auto-Download

### When It Triggers

The launcher already searches for IntelliJ installations (env var → installed app → sbt-idea-plugin cache). If none found, instead of failing, it downloads IntelliJ Community SDK.

### Flow

1. Run existing IntelliJ detection. If found, use it.

2. If not found, download to `~/.local/share/intellij-scala-lsp/ide/`:
   - Use hardcoded URL for the specific IntelliJ Community version (`253.32098.37`)
   - Download `.tar.gz` for Linux, `.tar.gz` for macOS — ~800 MB compressed
   - Extract to `~/.local/share/intellij-scala-lsp/ide/idea-IC-253.32098.37/`
   - Show download progress

3. **Scala plugin:** If not found in user's JetBrains dirs, download from JetBrains Plugin Repository to `~/.local/share/intellij-scala-lsp/ide/plugins/`

4. **Version pinning:** Required IntelliJ version embedded in `ide-version.txt` shipped in the release tarball. When LSP server updates require a newer IDE, auto-update triggers a new IDE download.

5. **Disk space warning:** First install with IDE download is ~1.5 GB. Install script warns upfront.

## Section 5: File Layout & Integration Points

### Installed File Structure

```
~/.local/share/intellij-scala-lsp/
├── lib/                          # LSP server JARs (from release tarball)
│   ├── lsp-server.jar
│   ├── org.eclipse.lsp4j-*.jar
│   └── ...
├── launcher/
│   └── launch-lsp.sh            # Enhanced launcher
├── version.txt                   # e.g. "0.6.0"
├── ide-version.txt               # e.g. "253.32098.37"
├── .last-update-check            # Unix timestamp
├── ide/                          # Auto-downloaded (only if no local IntelliJ)
│   ├── idea-IC-253.32098.37/
│   └── plugins/
│       └── scala/
└── cache/                        # Runtime state
    ├── daemon.pid
    ├── daemon.port
    ├── lsp-server-stripped.jar
    ├── system/                   # IntelliJ indexes
    └── config/                   # Isolated IntelliJ config

~/.local/bin/
└── intellij-scala-lsp -> ~/.local/share/intellij-scala-lsp/launcher/launch-lsp.sh
```

### Claude Code Integration

- Install script writes the plugin manifest at the Claude Code plugin location
- The manifest's `command` field points to `~/.local/bin/intellij-scala-lsp`
- Unaffected by updates since the symlink stays stable

### Other Editors (Documented, Not Automated)

- **Neovim:** `vim.lsp.start({ cmd = {"intellij-scala-lsp"}, ... })`
- **VS Code:** Point language server path to `intellij-scala-lsp`
- **Helix:** `[language-server.intellij-scala-lsp] command = "intellij-scala-lsp"`

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Hosting | GitHub Releases | Simplest, free, works with curl/gh |
| Install UX | One-liner curl pipe bash | Lowest friction |
| Update mechanism | Auto-check on launch | Stays current without user action |
| IntelliJ SDK | Use local if found, auto-download as fallback | Works for both IntelliJ users and non-IntelliJ users |
| Update rate limit | Once per hour | Avoids GitHub API rate limits and launch latency |
| Update timeout | 3 seconds | Doesn't block launch when offline |

## Out of Scope (Future)

- Homebrew tap (thin wrapper over GitHub Releases)
- Nix flake
- Docker distribution
- VS Code extension marketplace packaging
- Neovim Mason package
