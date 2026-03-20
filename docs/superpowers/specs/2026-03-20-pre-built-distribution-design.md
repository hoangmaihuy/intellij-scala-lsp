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
├── version.txt                   # e.g. "0.6.0"
└── ide-version.txt               # e.g. "253.32098.37"
```

### GitHub Actions Workflow

File: `.github/workflows/release.yml`

- **Trigger:** Push of a git tag matching `v*`
- **Runner:** `ubuntu-latest` with JDK 21
- **Steps:**
  1. Checkout code
  2. Run `sbt lsp-server/packageArtifact` (downloads ~800 MB IntelliJ SDK + Scala plugin on CI; budget ~5 min)
  3. Generate `version.txt` from the git tag (strip `v` prefix)
  4. Generate `ide-version.txt` from the IntelliJ build number in `build.sbt`
  5. Package `target/plugin/intellij-scala-lsp/lib/` JARs + `launcher/launch-lsp.sh` + `version.txt` + `ide-version.txt` into tarball
  6. Create GitHub Release with the tarball attached

### Versioning

Tags like `v0.5.0`, `v0.6.0`. The **git tag** is the source of truth for the version. The CI workflow strips the `v` prefix and writes it to `version.txt` in the tarball.

## Section 2: Install Script

### One-Liner

```bash
curl -fsSL https://raw.githubusercontent.com/<owner>/intellij-scala-lsp/main/install.sh | bash
```

(`<owner>` is a placeholder — will be the actual GitHub org/user at implementation time.)

### What `install.sh` Does

1. **Detect platform** — `uname -s` / `uname -m` (macOS + Linux, arm64 + x86_64). JARs are platform-independent but the script needs to know where to look for IntelliJ.

2. **Download latest release** — queries GitHub API (`repos/<owner>/intellij-scala-lsp/releases/latest`) to get the tarball URL, downloads and extracts to `$XDG_DATA_HOME/intellij-scala-lsp/` (defaults to `~/.local/share/intellij-scala-lsp/`).

3. **Add to PATH** — creates a symlink `~/.local/bin/intellij-scala-lsp` pointing to the launcher script. Prints a message if `~/.local/bin` isn't in PATH.

4. **Register with Claude Code** (if installed) — detects if `claude` CLI is available, then:
   - Writes a plugin manifest (`plugin.json`) pointing to the installed launcher
   - Registers via Claude Code's local marketplace mechanism (same as current `setup-claude-code.sh`)

5. **Print editor config snippets** — for Neovim, VS Code, Helix, etc. showing how to point their LSP client at `~/.local/bin/intellij-scala-lsp`.

6. **Dependency check** — warns if `socat` or `python3` are not installed and suggests install commands (`brew install socat` / `apt install socat`). `python3` is needed by the launcher to parse IntelliJ's `product-info.json`.

### Uninstall

Documented in the install script output and README:

```bash
# Remove installation
rm -rf ~/.local/share/intellij-scala-lsp
rm -f ~/.local/bin/intellij-scala-lsp
# Remove runtime cache (indexes, config, daemon state)
rm -rf ~/.cache/intellij-scala-lsp
# Remove Claude Code plugin registration (if applicable)
# Users should uninstall via Claude Code's plugin management
```

## Section 3: Auto-Update on Launch

Added to the existing `launch-lsp.sh` launcher script.

### Flow

1. Read `version.txt` (relative to install dir) for current installed version.

2. Check `.last-update-check` — only query GitHub API if last check was >1 hour ago (rate limiting).

3. Query `https://api.github.com/repos/<owner>/intellij-scala-lsp/releases/latest` with a **3-second timeout**. Uses `GITHUB_TOKEN` env var for authentication if available (avoids 60 req/hr unauthenticated rate limit on shared IPs). On failure/timeout, silently skip and proceed.

4. Compare remote tag against local version. If newer:
   - Download new tarball to a **temp directory**
   - Extract to temp directory, verify contents are valid
   - **Atomic swap:** `mv` the old `lib/`, `launcher/`, `version.txt`, `ide-version.txt` to a backup dir, then `mv` the new ones in. On failure, restore from backup.
   - Remove backup on success
   - Stop running daemon (if any) since JARs changed
   - If the new `ide-version.txt` differs from the old one, mark auto-downloaded IDE for re-download on next launch
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

2. If not found, download to `$XDG_DATA_HOME/intellij-scala-lsp/ide/`:
   - Read `ide-version.txt` for the required build number (e.g., `253.32098.37`)
   - Download IntelliJ Community `.tar.gz` from JetBrains CDN (available as `.tar.gz` for both Linux and macOS)
   - The download **includes JBR (JetBrains Runtime)** — this is critical since users without IntelliJ likely don't have JDK 21+ either
   - Extract to `$XDG_DATA_HOME/intellij-scala-lsp/ide/idea-IC-<build>/`
   - Show download progress (~800 MB compressed, ~1.5 GB extracted)

3. **Scala plugin:** If not found in user's JetBrains dirs, download from JetBrains Plugin Repository to `$XDG_DATA_HOME/intellij-scala-lsp/ide/plugins/`. The compatible Scala plugin version is resolved via the JetBrains Plugin Repository API using the IntelliJ build number from `ide-version.txt`.

4. **Version pinning:** When auto-update changes `ide-version.txt`, the next launch detects the mismatch and re-downloads the matching IDE version.

5. **Disk space warning:** First install with IDE download is ~1.5 GB. The install script and launcher warn upfront before downloading.

## Section 5: Launcher Dual-Mode Detection

### Problem

The current launcher computes paths relative to itself, expecting the git checkout layout (`$PROJECT_ROOT/lsp-server/target/plugin/.../lib`). In installed mode, JARs are at `$INSTALL_DIR/lib/`.

### Solution

The launcher detects which mode it's running in:

```
if [ -f "$SCRIPT_DIR/../lib/lsp-server.jar" ]; then
  # Installed mode: JARs are at ../lib/ relative to launcher/launch-lsp.sh
  INSTALL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
  LSP_JARS_DIR="$INSTALL_DIR/lib"
else
  # Dev mode: built from source, JARs at standard sbt output path
  LSP_JARS_DIR="$PROJECT_ROOT/lsp-server/target/plugin/intellij-scala-lsp/lib"
fi
```

This keeps the launcher working in both the git checkout (for developers) and the installed layout (for end users).

## Section 6: File Layout & Integration Points

### Installed File Structure

```
$XDG_DATA_HOME/intellij-scala-lsp/     # defaults to ~/.local/share/intellij-scala-lsp/
├── lib/                          # LSP server JARs (from release tarball)
│   ├── lsp-server.jar
│   ├── org.eclipse.lsp4j-*.jar
│   └── ...
├── launcher/
│   └── launch-lsp.sh            # Enhanced launcher
├── version.txt                   # e.g. "0.6.0"
├── ide-version.txt               # e.g. "253.32098.37"
├── .last-update-check            # Unix timestamp
└── ide/                          # Auto-downloaded (only if no local IntelliJ)
    ├── idea-IC-253.32098.37/     # Includes bundled JBR
    └── plugins/
        └── scala/

~/.cache/intellij-scala-lsp/            # Runtime state (existing XDG cache location, unchanged)
├── daemon.pid
├── daemon.port
├── lsp-server-stripped.jar
├── system/                       # IntelliJ indexes
└── config/                       # Isolated IntelliJ config

~/.local/bin/
└── intellij-scala-lsp -> $XDG_DATA_HOME/intellij-scala-lsp/launcher/launch-lsp.sh
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
| Version source of truth | Git tag | No need to maintain version in build.sbt |
| Update mechanism | Auto-check on launch | Stays current without user action |
| Update strategy | Atomic swap via mv | Prevents corrupted state on interrupted update |
| IntelliJ SDK | Use local if found, auto-download as fallback | Works for both IntelliJ users and non-IntelliJ users |
| SDK download includes JBR | Yes | Users without IntelliJ likely lack JDK 21+ |
| Cache location | `~/.cache/intellij-scala-lsp/` (unchanged) | XDG-compliant, no breaking change for existing users |
| Data location | `$XDG_DATA_HOME/intellij-scala-lsp/` | XDG-compliant |
| Update rate limit | Once per hour | Avoids GitHub API rate limits and launch latency |
| Update timeout | 3 seconds | Doesn't block launch when offline |
| GitHub auth | Use `GITHUB_TOKEN` if available | Handles shared IP rate limiting |

## Out of Scope (Future)

- Homebrew tap (thin wrapper over GitHub Releases)
- Nix flake
- Docker distribution
- VS Code extension marketplace packaging
- Neovim Mason package
- SHA256 checksum verification for downloads
