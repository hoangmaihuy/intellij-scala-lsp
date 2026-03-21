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
| `intellij-scala-lsp-<version>.tar.gz` | LSP server JARs (lsp-server.jar, lsp4j, gson, error_prone_annotations, junit, hamcrest — JUnit is a runtime dependency for `TestApplicationManager` bootstrap) | ~2MB |
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

1. **Local IntelliJ detected** — Scan standard install locations (`/Applications/IntelliJ*` on macOS, `/opt/idea-*` on Linux). **Version check**: read `build.txt` from the installation and verify the major version prefix matches the pinned build (e.g., `253.x`). If it matches, symlink `sdk/<build>` to the installation. Find Scala plugin from IntelliJ's plugins or JetBrains config dirs, matching the version pattern for the build prefix (e.g., `253` -> `2025.3.*`).
2. **Version mismatch or no IntelliJ found** — Download IntelliJ Community ZIP from `download.jetbrains.com`. URL format: `https://download.jetbrains.com/idea/ideaIC-<version>.tar.gz` (Linux) or `.dmg`/`.tar.gz` (macOS). Extract to `sdk/`. Download Scala plugin ZIP from JetBrains marketplace plugin API.

---

## Runtime Classpath Assembly

The classpath is assembled from multiple sources — `product-info.json` alone is not sufficient.

### Entry point

`org.jetbrains.scalalsP.ScalaLspMain`

### Classpath components

1. **All IntelliJ platform JARs** — all `*.jar` files from `sdk/<build>/lib/` (not just the boot classpath from `product-info.json`). This includes `testFramework.jar` which is needed at runtime for `TestApplicationManager` bootstrap, plus many other platform JARs that IntelliJ services depend on.
2. **Scala plugin JARs** — `sdk/<build>/plugins/scala/lib/scalaCommunity.jar`, `scala-library.jar`, `scala3-library_3.jar`
3. **Java plugin JARs** — `sdk/<build>/plugins/java/lib/java-impl-frontend.jar`
4. **LSP server JARs** — all JARs from the `lsp-server/` directory. Note: the `lsp-server.jar` must be **stripped** of `META-INF/plugin.xml` before adding to classpath (to avoid IntelliJ's "jarFiles is not set" fatal assertion when the plugin is discovered from both classpath and `-Dplugin.path`). The launcher creates a `lsp-server-stripped.jar` by copying and removing the `plugin.xml` entry.

### Plugin path (`-Dplugin.path`)

A colon-separated list of plugin directories (not individual JARs):
- The LSP plugin directory (containing the unstripped `lsp-server.jar` with `plugin.xml`)
- The Scala plugin directory (`sdk/<build>/plugins/scala/`)
- The Java plugin directory (`sdk/<build>/plugins/java/`)

IntelliJ's plugin loader needs all three directories to discover their `plugin.xml` descriptors.

### JVM flags

- **From `product-info.json`**: `launch[0].additionalJvmArguments` (includes `--add-opens` flags)
- **Hardcoded by launcher**:
  - `-Djava.awt.headless=true`
  - `-Xlog:cds=off` (suppress CDS warnings)
  - `-Xmx${LSP_HEAP_SIZE:-2g}`
  - `-Didea.home.path=<sdk-path>` (IntelliJ's `PathManager` root — required for JNA and SDK-relative lookups)
  - `-Didea.system.path=~/.cache/intellij-scala-lsp/system`
  - `-Didea.config.path=~/.cache/intellij-scala-lsp/config`
  - `-Didea.log.path=~/.cache/intellij-scala-lsp/system/log`
  - `-Didea.plugins.path=~/.cache/intellij-scala-lsp/config/plugins`
  - `-Didea.classpath.index.enabled=false`
  - `-Dide.native.launcher=true`
  - `-Dplugin.path=<lsp-plugin-dir>:<scala-plugin-dir>:<java-plugin-dir>`

### JDK table copying

On first run, the launcher copies `jdk.table.xml` from the user's IntelliJ config directory (if it exists) into the isolated config dir at `~/.cache/intellij-scala-lsp/config/options/`. This allows projects to resolve their configured JDKs without requiring users to reconfigure them.

### Runtime dependency: `socat`

Daemon mode uses `socat` for stdio-to-TCP proxying. The installer checks for `socat` in PATH and prints installation instructions if missing (`brew install socat` on macOS, `apt install socat` on Linux). This is only needed for daemon mode — stdio mode works without it.

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
3. Assemble full classpath (see "Runtime Classpath Assembly" section)
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

1. `sbt lsp-server/packageArtifact` (existing sbt-idea-plugin task) — build LSP server JARs
2. Tar JARs into `intellij-scala-lsp-<version>.tar.gz`
3. Create GitHub Release with all three artifacts
4. Release description includes IntelliJ SDK build number and Scala plugin version

### Version Pinning

The launcher script contains hardcoded constants:
- `LSP_VERSION` — the release version (e.g., `0.1.0`), used to download matching JARs from GitHub Releases
- `INTELLIJ_BUILD` — the IntelliJ SDK build number (e.g., `253.32098.37`)
- `SCALA_PLUGIN_VERSION` — the Scala plugin version (e.g., `2025.3.1`)

These are substituted by CI during the release build. When the launcher self-updates, new constants may trigger an SDK re-download.

The `install.sh` always fetches the `latest` release from GitHub, which includes the launcher with the correct version constants baked in. So version information flows: `build.sbt` -> CI -> launcher constants -> runtime downloads.

---

## sbt Task for Local Development

**`sbt lsp-server/runLsp`**:

Rather than duplicating classpath assembly logic, the sbt task invokes the launcher script in local-development mode:

1. Runs `packageArtifact` (existing sbt-idea-plugin task) to ensure JARs are built
2. Exec `launcher/intellij-scala-lsp` with any passed-through arguments
3. The launcher auto-detects it's in the repo and uses local build output

Usage: `sbt "lsp-server/runLsp --daemon"`

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
