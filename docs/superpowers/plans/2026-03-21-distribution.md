# Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a one-line installer (`curl | bash`) so end users can run the IntelliJ Scala LSP server without cloning the repo or running sbt.

**Architecture:** A single smart bash launcher script replaces the current `launch-lsp.sh`. It handles install, run, update, and stop modes. On first run it downloads pre-built LSP JARs from GitHub Releases and either reuses a local IntelliJ installation or downloads IntelliJ Community Edition. A GitHub Actions workflow publishes releases on version tags.

**Tech Stack:** Bash (launcher + installer), GitHub Actions (CI), sbt (build task)

**Spec:** `docs/superpowers/specs/2026-03-21-distribution-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `launcher/intellij-scala-lsp` | Smart launcher: install, run, update, stop modes. SDK resolution, classpath assembly, daemon management |
| Create | `launcher/install.sh` | Thin installer: downloads launcher from GitHub Releases, places in PATH, runs `--install` |
| Create | `.github/workflows/release.yml` | CI: build JARs on `v*` tags, publish GitHub Release with 3 artifacts |
| Modify | `build.sbt` | Add `runLsp` sbt task that delegates to the launcher |
| Modify | `vscode-extension/extension.js` | Use `intellij-scala-lsp` from PATH instead of hardcoded repo paths |
| Modify | `claude-code/intellij-scala-lsp/.claude-plugin/plugin.json` | Use `intellij-scala-lsp` command instead of hardcoded paths |
| Modify | `README.md` | Replace build/setup instructions with `curl \| bash` install |
| Delete | `launcher/launch-lsp.sh` | Replaced by `launcher/intellij-scala-lsp` |
| Delete | `setup-vscode.sh` | No longer needed |
| Delete | `setup-claude-code.sh` | No longer needed |
| Delete | `claude-code/setup-intellij-scala-lsp.sh` | No longer needed |

---

## Task 1: Create the Smart Launcher Script

The core of the distribution. Refactor `launcher/launch-lsp.sh` into `launcher/intellij-scala-lsp` with new capabilities.

**Files:**
- Create: `launcher/intellij-scala-lsp`
- Reference: `launcher/launch-lsp.sh` (existing, will be deleted later)

- [ ] **Step 1: Create launcher with version constants and utility functions**

Create `launcher/intellij-scala-lsp` with the shebang, `set -euo pipefail`, version constants, logging, cache dir setup, and platform detection.

```bash
#!/bin/bash
# intellij-scala-lsp — smart launcher for IntelliJ Scala LSP server
# Handles: install, run, update, stop, and SDK resolution
set -euo pipefail

# --- Version constants (substituted by CI on release) ---
LSP_VERSION="dev"
INTELLIJ_BUILD="253.32098.37"
SCALA_PLUGIN_VERSION="2025.3.26"
GITHUB_REPO="nicholasgasior/intellij-scala-lsp"  # TODO: update to actual repo

# --- Paths ---
CACHE_DIR="${HOME}/.cache/intellij-scala-lsp"
LSP_LOG="${CACHE_DIR}/lsp-server.log"
INSTALL_DIR="${HOME}/.local/bin"

# Detect if running from repo (local dev mode)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." 2>/dev/null && pwd)" || PROJECT_ROOT=""
if [ -f "$PROJECT_ROOT/build.sbt" ]; then
  DEV_MODE=true
  LSP_LIB_DIR="$PROJECT_ROOT/lsp-server/target/plugin/intellij-scala-lsp/lib"
else
  DEV_MODE=false
  LSP_LIB_DIR="$CACHE_DIR/lsp-server"
fi

mkdir -p "$CACHE_DIR"

# --- Logging ---
log() { echo "[intellij-scala-lsp] $*" >&2; }

# --- Platform detection ---
detect_platform() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"
  case "$os" in
    Darwin) PLATFORM="macos" ;;
    Linux)  PLATFORM="linux" ;;
    *)      log "ERROR: Unsupported OS: $os"; exit 1 ;;
  esac
  case "$arch" in
    arm64|aarch64) ARCH="aarch64" ;;
    x86_64|amd64)  ARCH="amd64" ;;
    *)             log "ERROR: Unsupported architecture: $arch"; exit 1 ;;
  esac
}
detect_platform
```

- [ ] **Step 2: Add SDK resolution functions**

Add `find_intellij_home()` (scan standard locations + version check), `find_scala_plugin()`, and `download_sdk()` functions. Port the logic from the existing `launch-lsp.sh` lines 26-113, adding version prefix matching and download capability.

```bash
# --- SDK Resolution ---

find_intellij_home() {
  # Check INTELLIJ_HOME env var first
  if [ -n "${INTELLIJ_HOME:-}" ] && [ -d "$INTELLIJ_HOME/lib" ]; then
    echo "$INTELLIJ_HOME"; return 0
  fi
  # Check cached/downloaded SDK
  local sdk_dir="$CACHE_DIR/sdk/$INTELLIJ_BUILD"
  if [ -d "$sdk_dir/lib" ]; then
    echo "$sdk_dir"; return 0
  fi
  # Scan standard install locations
  local candidates=()
  if [ "$PLATFORM" = "macos" ]; then
    candidates=(
      "$HOME/Applications/IntelliJ IDEA.app/Contents"
      "$HOME/Applications/IntelliJ IDEA CE.app/Contents"
      "$HOME/Applications/IntelliJ IDEA Community Edition.app/Contents"
      "/Applications/IntelliJ IDEA.app/Contents"
      "/Applications/IntelliJ IDEA CE.app/Contents"
      "/Applications/IntelliJ IDEA Community Edition.app/Contents"
    )
  else
    candidates=(
      /opt/idea-IC /opt/idea-IU
      /snap/intellij-idea-community/current
      /snap/intellij-idea-ultimate/current
    )
  fi
  local required_prefix="${INTELLIJ_BUILD%%.*}"  # e.g. "253"
  for app in "${candidates[@]}"; do
    if [ -d "$app/lib" ]; then
      # Version check: read build number and compare prefix
      local build_txt=""
      if [ -f "$app/Resources/build.txt" ]; then
        build_txt=$(cat "$app/Resources/build.txt")
      elif [ -f "$app/build.txt" ]; then
        build_txt=$(cat "$app/build.txt")
      fi
      local found_prefix="${build_txt%%.*}"
      if [ "$found_prefix" = "$required_prefix" ]; then
        # Symlink into cache for consistent paths
        mkdir -p "$CACHE_DIR/sdk"
        ln -sfn "$app" "$CACHE_DIR/sdk/$INTELLIJ_BUILD"
        echo "$app"; return 0
      else
        log "Skipping $app (build $build_txt, need $required_prefix.x)"
      fi
    fi
  done
  return 1
}

find_scala_plugin() {
  local idea_home="$1"
  # Check within the SDK (downloaded SDK bundles plugins)
  if [ -d "$idea_home/plugins/Scala/lib" ]; then
    echo "$idea_home/plugins/Scala"; return 0
  fi
  # sbt-idea-plugin SDK layout
  if [ -d "$idea_home/custom-plugins/Scala/lib" ]; then
    echo "$idea_home/custom-plugins/Scala"; return 0
  fi
  # Map build prefix to version pattern for JetBrains config dirs
  local ide_prefix="${INTELLIJ_BUILD%%.*}"
  local major="${ide_prefix:0:2}"
  local minor="${ide_prefix:2:1}"
  local config_pattern="20${major}.${minor}"
  log "Looking for Scala plugin matching version $config_pattern"
  # Search JetBrains config dirs (prefer version match)
  local found_any=""
  local search_dirs=()
  if [ "$PLATFORM" = "macos" ]; then
    search_dirs=("$HOME/Library/Application Support/JetBrains"/*/plugins/Scala)
  else
    search_dirs=("$HOME/.config/JetBrains"/*/plugins/Scala)
  fi
  for d in "${search_dirs[@]}"; do
    if [ -d "$d/lib" ]; then
      if [[ "$d" == *"$config_pattern"* ]]; then
        echo "$d"; return 0
      fi
      [ -z "$found_any" ] && found_any="$d"
    fi
  done
  if [ -n "$found_any" ]; then
    log "WARNING: No version-matched Scala plugin, using: $found_any"
    echo "$found_any"; return 0
  fi
  return 1
}

download_sdk() {
  log "Downloading IntelliJ Community Edition (build $INTELLIJ_BUILD)..."
  local sdk_dir="$CACHE_DIR/sdk/$INTELLIJ_BUILD"
  mkdir -p "$sdk_dir"
  local tmp_dir="$CACHE_DIR/sdk-download-tmp"
  rm -rf "$tmp_dir"
  mkdir -p "$tmp_dir"

  # Determine download URL
  # IntelliJ build 253.32098.37 -> version 2025.3.4 (mapped in release notes)
  # We use the no-jbr variant and rely on detecting JBR separately
  local version_suffix=""
  local url=""
  if [ "$PLATFORM" = "macos" ]; then
    if [ "$ARCH" = "aarch64" ]; then
      url="https://download.jetbrains.com/idea/ideaIC-${INTELLIJ_BUILD}-aarch64.tar.gz"
    else
      url="https://download.jetbrains.com/idea/ideaIC-${INTELLIJ_BUILD}.tar.gz"
    fi
  else
    url="https://download.jetbrains.com/idea/ideaIC-${INTELLIJ_BUILD}.tar.gz"
  fi

  log "Downloading from: $url"
  curl -fSL --progress-bar -o "$tmp_dir/idea.tar.gz" "$url"
  log "Extracting..."
  tar xzf "$tmp_dir/idea.tar.gz" -C "$tmp_dir" --strip-components=1
  # Move to final location
  rm -rf "$sdk_dir"
  mv "$tmp_dir" "$sdk_dir"
  log "IntelliJ SDK installed to $sdk_dir"
}

download_scala_plugin() {
  local idea_home="$1"
  local plugins_dir="$idea_home/plugins"
  mkdir -p "$plugins_dir"
  log "Downloading Scala plugin ${SCALA_PLUGIN_VERSION}..."

  local tmp_dir="$CACHE_DIR/scala-plugin-tmp"
  rm -rf "$tmp_dir"
  mkdir -p "$tmp_dir"

  # JetBrains marketplace API for Scala plugin
  local url="https://plugins.jetbrains.com/plugin/download?pluginId=org.intellij.scala&version=${SCALA_PLUGIN_VERSION}&build=IC-${INTELLIJ_BUILD}"
  curl -fSL --progress-bar -L -o "$tmp_dir/scala-plugin.zip" "$url"
  unzip -qo "$tmp_dir/scala-plugin.zip" -d "$plugins_dir/"
  rm -rf "$tmp_dir"
  log "Scala plugin installed to $plugins_dir/Scala"
}

resolve_sdk() {
  IDEA_HOME=$(find_intellij_home) || {
    download_sdk
    IDEA_HOME="$CACHE_DIR/sdk/$INTELLIJ_BUILD"
  }
  log "IntelliJ: $IDEA_HOME"

  SCALA_PLUGIN_DIR=$(find_scala_plugin "$IDEA_HOME") || {
    download_scala_plugin "$IDEA_HOME"
    SCALA_PLUGIN_DIR=$(find_scala_plugin "$IDEA_HOME") || {
      log "ERROR: Scala plugin installation failed"
      exit 1
    }
  }
  log "Scala plugin: $SCALA_PLUGIN_DIR"
}
```

- [ ] **Step 3: Add classpath assembly and JVM flag functions**

Port from `launch-lsp.sh` lines 122-330. Read `product-info.json`, collect all `lib/*.jar`, add Scala/Java plugin JARs, create stripped `lsp-server.jar`, assemble JVM flags.

```bash
# --- Classpath & JVM Flags Assembly ---

read_product_info() {
  local product_info=""
  if [ -f "$IDEA_HOME/Resources/product-info.json" ]; then
    product_info="$IDEA_HOME/Resources/product-info.json"
  elif [ -f "$IDEA_HOME/product-info.json" ]; then
    product_info="$IDEA_HOME/product-info.json"
  else
    log "ERROR: product-info.json not found in $IDEA_HOME"
    exit 1
  fi

  python3 - "$product_info" "$IDEA_HOME" << 'PYEOF'
import json, sys, os, platform

product_info_path = sys.argv[1]
idea_home = sys.argv[2]

with open(product_info_path) as f:
    info = json.load(f)

machine = platform.machine()
arch_map = {"arm64": "aarch64", "x86_64": "amd64", "aarch64": "aarch64"}
current_arch = arch_map.get(machine, machine)

launch = None
for l in info.get("launch", []):
    if l.get("arch") == current_arch:
        launch = l
        break
if launch is None and info.get("launch"):
    launch = info["launch"][0]
if launch is None:
    print("ERROR: No launch config found", file=sys.stderr)
    sys.exit(1)

if os.path.basename(idea_home) == "Contents":
    app_package = os.path.dirname(idea_home)
else:
    app_package = idea_home

def resolve(s):
    return (s
        .replace("$APP_PACKAGE/Contents", idea_home)
        .replace("$APP_PACKAGE", app_package)
        .replace("%IDE_HOME%", idea_home)
        .replace("$IDE_HOME", idea_home))

jvm_args = []
for arg in launch.get("additionalJvmArguments", []):
    resolved = resolve(arg)
    if "splash" in resolved.lower():
        continue
    jvm_args.append(resolved)
print("JVM_ARGS=" + "|||".join(jvm_args))

java_path = resolve(launch.get("javaExecutablePath", "java"))
print("JAVA_PATH=" + java_path)
PYEOF
}

find_java() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    echo "$JAVA_HOME/bin/java"; return
  fi
  # IntelliJ's bundled JBR
  for jbr in "$IDEA_HOME/jbr/Contents/Home/bin/java" "$IDEA_HOME/jbr/bin/java"; do
    if [ -x "$jbr" ]; then echo "$jbr"; return; fi
  done
  if [ -x "$JAVA_DEFAULT" ]; then echo "$JAVA_DEFAULT"; return; fi
  echo "java"
}

assemble_classpath_and_flags() {
  # Read product-info
  local launch_info
  launch_info=$(read_product_info)
  JVM_ARGS_RAW=$(echo "$launch_info" | grep "^JVM_ARGS=" | cut -d= -f2-)
  JAVA_DEFAULT=$(echo "$launch_info" | grep "^JAVA_PATH=" | cut -d= -f2-)

  JAVA=$(find_java)
  log "Java: $JAVA"

  # Check LSP JARs exist
  if [ ! -d "$LSP_LIB_DIR" ] || [ -z "$(ls "$LSP_LIB_DIR"/*.jar 2>/dev/null)" ]; then
    if [ "$DEV_MODE" = true ]; then
      log "ERROR: LSP server JARs not found at $LSP_LIB_DIR"
      log "Run: cd $PROJECT_ROOT && sbt 'lsp-server/packageArtifact'"
    else
      log "ERROR: LSP server JARs not found. Run: intellij-scala-lsp --install"
    fi
    exit 1
  fi

  # Build classpath: all lib/*.jar
  CLASSPATH=""
  for jar in "$IDEA_HOME/lib/"*.jar; do
    [ -f "$jar" ] && CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
  done

  # Stripped lsp-server.jar (remove plugin.xml to avoid "jarFiles is not set" assertion)
  local stripped_jar="$CACHE_DIR/lsp-server-stripped.jar"
  if [ "$LSP_LIB_DIR/lsp-server.jar" -nt "$stripped_jar" ] 2>/dev/null; then
    local strip_tmp="$CACHE_DIR/strip-tmp"
    rm -rf "$strip_tmp"
    mkdir -p "$strip_tmp"
    (cd "$strip_tmp" && jar xf "$LSP_LIB_DIR/lsp-server.jar" && rm -f META-INF/plugin.xml && jar cf "$stripped_jar" .)
    rm -rf "$strip_tmp"
  fi

  # Add LSP JARs (stripped lsp-server.jar, others as-is)
  for jar in "$LSP_LIB_DIR/"*.jar; do
    if [ "$(basename "$jar")" = "lsp-server.jar" ]; then
      CLASSPATH="${CLASSPATH:+$CLASSPATH:}$stripped_jar"
    else
      CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
    fi
  done

  # Scala plugin JARs
  for jar in "scalaCommunity.jar" "scala-library.jar" "scala3-library_3.jar"; do
    if [ -f "$SCALA_PLUGIN_DIR/lib/$jar" ]; then
      CLASSPATH="${CLASSPATH:+$CLASSPATH:}$SCALA_PLUGIN_DIR/lib/$jar"
    fi
  done

  # Java plugin JARs
  local java_plugin_dir=""
  if [ -d "$IDEA_HOME/plugins/java/lib" ]; then
    java_plugin_dir="$IDEA_HOME/plugins/java"
    CLASSPATH="${CLASSPATH:+$CLASSPATH:}$IDEA_HOME/plugins/java/lib/java-impl-frontend.jar"
  fi

  # --- JVM flags ---
  local heap="${LSP_HEAP_SIZE:-2g}"
  local config_dir="$CACHE_DIR/config"
  local system_dir="$CACHE_DIR/system"
  mkdir -p "$config_dir/options" "$system_dir"

  # Copy JDK table from real IntelliJ config on first run
  if [ ! -f "$config_dir/options/jdk.table.xml" ]; then
    local search_dirs=()
    if [ "$PLATFORM" = "macos" ]; then
      search_dirs=("$HOME/Library/Application Support/JetBrains"/*/)
    else
      search_dirs=("$HOME/.config/JetBrains"/*/)
    fi
    for cfg in "${search_dirs[@]}"; do
      if [ -f "${cfg}options/jdk.table.xml" ]; then
        cp "${cfg}options/jdk.table.xml" "$config_dir/options/jdk.table.xml"
        log "Copied JDK table from $cfg"
        break
      fi
    done
  fi

  JVM_ARGS=()
  JVM_ARGS+=("-Xmx${heap}")
  JVM_ARGS+=("-Djava.awt.headless=true")
  JVM_ARGS+=("-Dide.native.launcher=true")
  JVM_ARGS+=("-Xlog:cds=off")

  # Product-info JVM args
  IFS='|||' read -ra SPLIT_ARGS <<< "$JVM_ARGS_RAW"
  for arg in "${SPLIT_ARGS[@]}"; do
    [ -n "$arg" ] && JVM_ARGS+=("$arg")
  done

  # Isolation paths
  JVM_ARGS+=("-Didea.home.path=$IDEA_HOME")
  JVM_ARGS+=("-Didea.config.path=$config_dir")
  JVM_ARGS+=("-Didea.system.path=$system_dir")
  JVM_ARGS+=("-Didea.log.path=$system_dir/log")
  JVM_ARGS+=("-Didea.plugins.path=$config_dir/plugins")
  JVM_ARGS+=("-Didea.classpath.index.enabled=false")

  # Plugin path: LSP plugin + Scala plugin + Java plugin
  local plugin_path="$LSP_LIB_DIR/.."
  plugin_path="${plugin_path}:${SCALA_PLUGIN_DIR}"
  [ -n "$java_plugin_dir" ] && plugin_path="${plugin_path}:${java_plugin_dir}"
  JVM_ARGS+=("-Dplugin.path=$plugin_path")
}
```

- [ ] **Step 4: Add install mode**

The `--install` flag downloads LSP server JARs from GitHub Releases and sets up the SDK.

```bash
# --- Install Mode ---

download_lsp_jars() {
  local version="${1:-$LSP_VERSION}"
  if [ "$version" = "dev" ]; then
    log "ERROR: Cannot download JARs in dev mode. Build with: sbt lsp-server/packageArtifact"
    exit 1
  fi
  log "Downloading LSP server JARs v${version}..."
  local tmp_dir="$CACHE_DIR/download-tmp"
  rm -rf "$tmp_dir"
  mkdir -p "$tmp_dir"
  local url="https://github.com/${GITHUB_REPO}/releases/download/v${version}/intellij-scala-lsp-${version}.tar.gz"
  curl -fSL --progress-bar -o "$tmp_dir/lsp.tar.gz" "$url"
  rm -rf "$CACHE_DIR/lsp-server"
  mkdir -p "$CACHE_DIR/lsp-server"
  tar xzf "$tmp_dir/lsp.tar.gz" -C "$CACHE_DIR/lsp-server"
  rm -rf "$tmp_dir"
  echo "$version" > "$CACHE_DIR/version"
  log "LSP server JARs v${version} installed."
}

do_install() {
  log "Installing intellij-scala-lsp..."
  download_lsp_jars
  resolve_sdk
  # Check socat
  if ! command -v socat >/dev/null 2>&1; then
    log "WARNING: socat not found (required for daemon mode)."
    if [ "$PLATFORM" = "macos" ]; then
      log "  Install with: brew install socat"
    else
      log "  Install with: apt install socat"
    fi
  fi
  log "Installation complete!"
  log ""
  log "Configure your editor:"
  log "  VS Code:     Set \"intellijScalaLsp.launcher\" to \"intellij-scala-lsp\""
  log "  Neovim:      cmd = { \"intellij-scala-lsp\" }"
  log "  Claude Code:  The plugin uses intellij-scala-lsp automatically"
}
```

- [ ] **Step 5: Add update check and update mode**

Daily update check (cached in `last-update-check`), `--update` flag for explicit update.

```bash
# --- Update ---

check_for_updates() {
  local check_file="$CACHE_DIR/last-update-check"
  # Skip in dev mode
  [ "$DEV_MODE" = true ] && return
  [ "$LSP_VERSION" = "dev" ] && return
  # Rate limit: once per 24h
  if [ -f "$check_file" ]; then
    local last_check
    last_check=$(cat "$check_file")
    local now
    now=$(date +%s)
    local diff=$((now - last_check))
    [ $diff -lt 86400 ] && return
  fi
  # Non-blocking check
  (
    date +%s > "$check_file"
    local latest
    latest=$(curl -fsSL --max-time 5 "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>/dev/null \
      | python3 -c "import json,sys; print(json.load(sys.stdin).get('tag_name','').lstrip('v'))" 2>/dev/null) || return
    if [ -n "$latest" ] && [ "$latest" != "$LSP_VERSION" ]; then
      log "Update available: v${LSP_VERSION} -> v${latest}"
      log "Run: intellij-scala-lsp --update"
    fi
  ) &
}

do_update() {
  if [ "$LSP_VERSION" = "dev" ]; then
    log "ERROR: Cannot update in dev mode."
    exit 1
  fi
  log "Checking for updates..."
  local latest
  latest=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('tag_name','').lstrip('v'))")
  if [ -z "$latest" ]; then
    log "ERROR: Could not fetch latest version."
    exit 1
  fi
  if [ "$latest" = "$LSP_VERSION" ]; then
    log "Already up to date (v${LSP_VERSION})."
    return
  fi
  log "Updating v${LSP_VERSION} -> v${latest}..."
  # Download new launcher (replaces self)
  local new_launcher
  new_launcher=$(curl -fsSL "https://github.com/${GITHUB_REPO}/releases/download/v${latest}/intellij-scala-lsp")
  local self_path
  self_path="$(realpath "$0")"
  echo "$new_launcher" > "$self_path"
  chmod +x "$self_path"
  # Download new JARs
  download_lsp_jars "$latest"
  # Check if SDK version changed (new launcher might have different INTELLIJ_BUILD)
  local new_build
  new_build=$(grep "^INTELLIJ_BUILD=" "$self_path" | cut -d'"' -f2)
  if [ -n "$new_build" ] && [ "$new_build" != "$INTELLIJ_BUILD" ]; then
    log "IntelliJ SDK version changed ($INTELLIJ_BUILD -> $new_build), re-resolving..."
    INTELLIJ_BUILD="$new_build"
    resolve_sdk
  fi
  # Restart daemon if running
  if [ -f "$CACHE_DIR/daemon.pid" ]; then
    local pid
    pid=$(cat "$CACHE_DIR/daemon.pid")
    if kill -0 "$pid" 2>/dev/null; then
      log "Restarting daemon..."
      kill "$pid" 2>/dev/null || true
      sleep 2
      log "Daemon stopped. It will restart on next connection."
    fi
  fi
  log "Updated to v${latest}!"
}
```

- [ ] **Step 6: Add daemon management and main entry point**

Port daemon functions from `launch-lsp.sh` lines 332-412, add the `case` dispatch for `--install`, `--update`, `--stop`, `--daemon`, and default stdio-proxy mode.

```bash
# --- Daemon Management ---

daemon_running() {
  local pidfile="$CACHE_DIR/daemon.pid"
  local portfile="$CACHE_DIR/daemon.port"
  [ -f "$pidfile" ] && [ -f "$portfile" ] || return 1
  local pid
  pid=$(cat "$pidfile")
  kill -0 "$pid" 2>/dev/null
}

wait_for_port_file() {
  local timeout=$1
  local portfile="$CACHE_DIR/daemon.port"
  local elapsed=0
  while [ ! -f "$portfile" ] && [ $elapsed -lt $timeout ]; do
    sleep 1
    elapsed=$((elapsed + 1))
  done
  [ -f "$portfile" ]
}

start_daemon_background() {
  log "Starting daemon in background..."
  "$JAVA" "${JVM_ARGS[@]}" -cp "$CLASSPATH" \
    org.jetbrains.scalalsP.ScalaLspMain --daemon "$@" \
    >> "$LSP_LOG" 2>&1 &
  log "Daemon PID: $!"
}

proxy_stdio_to_tcp() {
  local port=$1
  if ! command -v socat >/dev/null 2>&1; then
    log "ERROR: socat is required for daemon mode."
    if [ "$PLATFORM" = "macos" ]; then
      log "Install with: brew install socat"
    else
      log "Install with: apt install socat"
    fi
    exit 1
  fi
  local retries=0
  while [ $retries -lt 10 ]; do
    if socat -T0.5 /dev/null TCP:localhost:"$port" 2>/dev/null; then
      break
    fi
    retries=$((retries + 1))
    log "Waiting for daemon to accept connections (attempt $retries)..."
    sleep 1
  done
  exec socat STDIO TCP:localhost:"$port"
}

# --- Main ---

# Log stderr to file
mkdir -p "$(dirname "$LSP_LOG")"
exec 2> >(tee -a "$LSP_LOG" >&2)
log "=== Starting at $(date) ==="

case "${1:-}" in
  --install)
    do_install
    ;;
  --update)
    do_update
    ;;
  --stop)
    resolve_sdk
    assemble_classpath_and_flags
    exec "$JAVA" "${JVM_ARGS[@]}" -cp "$CLASSPATH" \
      org.jetbrains.scalalsP.ScalaLspMain --stop
    ;;
  --daemon)
    shift
    resolve_sdk
    assemble_classpath_and_flags
    check_for_updates
    exec "$JAVA" "${JVM_ARGS[@]}" -cp "$CLASSPATH" \
      org.jetbrains.scalalsP.ScalaLspMain --daemon "$@"
    ;;
  --version)
    echo "intellij-scala-lsp $LSP_VERSION (IntelliJ $INTELLIJ_BUILD)"
    ;;
  --help)
    echo "Usage: intellij-scala-lsp [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --daemon [projects...]  Start daemon, optionally pre-warm projects"
    echo "  --stop                  Stop the daemon"
    echo "  --install               Download LSP JARs and SDK"
    echo "  --update                Update to latest version"
    echo "  --version               Print version info"
    echo "  --help                  Show this help"
    echo ""
    echo "Default (no args): proxy stdio to daemon (auto-starts daemon if needed)"
    ;;
  *)
    # Connect mode: proxy to daemon, auto-start if needed
    resolve_sdk
    assemble_classpath_and_flags
    check_for_updates
    if daemon_running; then
      DAEMON_PORT=$(cat "$CACHE_DIR/daemon.port")
      log "Connecting to daemon on port $DAEMON_PORT"
      proxy_stdio_to_tcp "$DAEMON_PORT"
    else
      start_daemon_background "$@"
      if wait_for_port_file 60; then
        DAEMON_PORT=$(cat "$CACHE_DIR/daemon.port")
        log "Daemon started, connecting on port $DAEMON_PORT"
        proxy_stdio_to_tcp "$DAEMON_PORT"
      else
        log "ERROR: Daemon failed to start within 60s. Check $LSP_LOG"
        exit 1
      fi
    fi
    ;;
esac
```

- [ ] **Step 7: Make launcher executable and test locally**

```bash
chmod +x launcher/intellij-scala-lsp
```

Run: `./launcher/intellij-scala-lsp --version`
Expected: `intellij-scala-lsp dev (IntelliJ 253.32098.37)`

Run: `./launcher/intellij-scala-lsp --help`
Expected: usage text

Run: `sbt lsp-server/packageArtifact && ./launcher/intellij-scala-lsp --daemon`
Expected: daemon starts successfully (Ctrl+C to stop)

- [ ] **Step 8: Commit**

```bash
git add launcher/intellij-scala-lsp
git commit -m "feat: add smart launcher with install, update, and SDK resolution"
```

---

## Task 2: Create the Installer Script

**Files:**
- Create: `launcher/install.sh`

- [ ] **Step 1: Write `install.sh`**

```bash
#!/bin/bash
# One-line installer for intellij-scala-lsp
# Usage: curl -fsSL https://github.com/<owner>/intellij-scala-lsp/releases/latest/download/install.sh | bash
set -euo pipefail

GITHUB_REPO="nicholasgasior/intellij-scala-lsp"  # TODO: update to actual repo
INSTALL_DIR="${HOME}/.local/bin"

echo "=== Installing intellij-scala-lsp ==="
echo ""

# Platform check
case "$(uname -s)" in
  Darwin|Linux) ;;
  *) echo "ERROR: Unsupported OS: $(uname -s). Only macOS and Linux are supported."; exit 1 ;;
esac

# Download launcher script
echo "[1/2] Downloading launcher..."
mkdir -p "$INSTALL_DIR"
LAUNCHER_URL="https://github.com/${GITHUB_REPO}/releases/latest/download/intellij-scala-lsp"
curl -fSL --progress-bar -o "$INSTALL_DIR/intellij-scala-lsp" "$LAUNCHER_URL"
chmod +x "$INSTALL_DIR/intellij-scala-lsp"

# Check if in PATH
case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *)
    echo ""
    echo "WARNING: $INSTALL_DIR is not in your PATH."
    echo "Add this to your shell profile (~/.zshrc or ~/.bashrc):"
    echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
    echo ""
    ;;
esac

# Run install
echo "[2/2] Setting up SDK and downloading LSP server..."
"$INSTALL_DIR/intellij-scala-lsp" --install

echo ""
echo "=== Installation complete ==="
echo ""
echo "Run 'intellij-scala-lsp --help' to get started."
```

- [ ] **Step 2: Make executable and commit**

```bash
chmod +x launcher/install.sh
git add launcher/install.sh
git commit -m "feat: add one-line installer script"
```

---

## Task 3: Create GitHub Actions Release Workflow

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Write the release workflow**

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Set up sbt
        uses: sbt/setup-sbt@v1

      - name: Build LSP server JARs
        run: sbt lsp-server/packageArtifact

      - name: Package artifacts
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          # Tar the LSP server JARs
          cd lsp-server/target/plugin/intellij-scala-lsp/lib
          tar czf "$GITHUB_WORKSPACE/intellij-scala-lsp-${VERSION}.tar.gz" *.jar
          cd "$GITHUB_WORKSPACE"

      - name: Stamp version in launcher
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          sed -i "s/^LSP_VERSION=\"dev\"/LSP_VERSION=\"${VERSION}\"/" launcher/intellij-scala-lsp

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: |
            intellij-scala-lsp-*.tar.gz
            launcher/intellij-scala-lsp
            launcher/install.sh
          generate_release_notes: true
```

- [ ] **Step 2: Commit**

```bash
mkdir -p .github/workflows
git add .github/workflows/release.yml
git commit -m "ci: add GitHub Actions release workflow"
```

---

## Task 4: Add `runLsp` sbt Task

**Files:**
- Modify: `build.sbt`

- [ ] **Step 1: Add the `runLsp` task to `build.sbt`**

Add at the end of the `lsp-server` settings block:

```scala
    // Custom task: build and run the LSP server via the launcher script
    lazy val runLsp = inputKey[Unit]("Build and run the LSP server via the launcher script")
    `lsp-server` / runLsp := {
      val args = sbt.Def.spaceDelimited("<args>").parsed
      val _ = (`lsp-server` / packageArtifact).value  // ensure JARs are built
      val launcher = (ThisBuild / baseDirectory).value / "launcher" / "intellij-scala-lsp"
      val cmd = Seq(launcher.absolutePath) ++ args
      val exitCode = scala.sys.process.Process(cmd).!
      if (exitCode != 0) sys.error(s"Launcher exited with code $exitCode")
    }
```

- [ ] **Step 2: Test the sbt task**

Run: `sbt "lsp-server/runLsp --version"`
Expected: `intellij-scala-lsp dev (IntelliJ 253.32098.37)`

- [ ] **Step 3: Commit**

```bash
git add build.sbt
git commit -m "feat: add runLsp sbt task for local development"
```

---

## Task 5: Update Editor Integrations

**Files:**
- Modify: `claude-code/intellij-scala-lsp/.claude-plugin/plugin.json`
- Modify: `vscode-extension/extension.js`

- [ ] **Step 1: Update Claude Code plugin.json**

Replace the hardcoded paths with the launcher command:

```json
{
  "name": "intellij-scala-lsp",
  "description": "Scala code intelligence powered by IntelliJ IDEA's analysis engine",
  "version": "0.5.0",
  "lspServers": {
    "intellij-scala": {
      "command": "intellij-scala-lsp",
      "args": [],
      "extensionToLanguage": {
        ".scala": "scala",
        ".sc": "scala"
      },
      "startupTimeout": 300000
    }
  }
}
```

- [ ] **Step 2: Update VS Code extension to use launcher from PATH**

In `vscode-extension/extension.js`, replace the `findProjectRoot` + hardcoded launcher logic with a simpler approach: use `intellij-scala-lsp` from PATH by default, fall back to config setting.

Replace the `activate` function's server options section (around lines 136-171):

```javascript
  const config = vscode.workspace.getConfiguration("intellijScalaLsp");

  // Use configured launcher, or fall back to 'intellij-scala-lsp' in PATH
  const launcher = config.get("launcher") || "intellij-scala-lsp";

  log(`Launcher: ${launcher}`);

  const serverOptions = {
    command: launcher,
    args: [],
    transport: TransportKind.stdio,
  };
```

Remove the `findProjectRoot` and `findIntellijSdk` functions (lines 21-46) — they are no longer needed.

Update `package.json` settings to remove `intellijScalaLsp.intellijHome` (no longer needed) and update the description for `intellijScalaLsp.launcher`:

```json
"intellijScalaLsp.launcher": {
  "type": "string",
  "default": "",
  "description": "Path to intellij-scala-lsp launcher (auto-detected from PATH if empty)"
}
```

- [ ] **Step 3: Commit**

```bash
git add claude-code/intellij-scala-lsp/.claude-plugin/plugin.json vscode-extension/extension.js vscode-extension/package.json
git commit -m "feat: update editor integrations to use launcher from PATH"
```

---

## Task 6: Delete Old Files

**Files:**
- Delete: `launcher/launch-lsp.sh`
- Delete: `setup-vscode.sh`
- Delete: `setup-claude-code.sh`
- Delete: `claude-code/setup-intellij-scala-lsp.sh`

- [ ] **Step 1: Delete old launcher and setup scripts**

```bash
git rm launcher/launch-lsp.sh setup-vscode.sh setup-claude-code.sh claude-code/setup-intellij-scala-lsp.sh
```

- [ ] **Step 2: Commit**

```bash
git commit -m "chore: remove old launcher and setup scripts"
```

---

## Task 7: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace Prerequisites, Build, and Usage sections**

Replace the "Prerequisites" section (lines 72-78) with:

```markdown
## Install

```bash
curl -fsSL https://github.com/<owner>/intellij-scala-lsp/releases/latest/download/install.sh | bash
```

This downloads the launcher, LSP server JARs, and sets up the IntelliJ SDK automatically. If you have IntelliJ installed, it reuses your installation. Otherwise, it downloads IntelliJ Community Edition (~800MB, first run only).

### Requirements

- **macOS** or **Linux**
- **socat** (`brew install socat` on macOS, `apt install socat` on Linux) — required for daemon mode
- **python3** — used by the launcher to parse IntelliJ's `product-info.json`
```

Replace the "Build" section (lines 80-91) with:

```markdown
## Usage

### Daemon Mode (recommended)

```bash
# Start daemon with project pre-warming
intellij-scala-lsp --daemon /path/to/project1 /path/to/project2

# Stop the daemon
intellij-scala-lsp --stop
```

### Editor Setup

**VS Code:** Install the extension from `vscode-extension/`, or set `"intellijScalaLsp.launcher"` to `"intellij-scala-lsp"`.

**Neovim:** `cmd = { "intellij-scala-lsp" }`

**Claude Code:** Install the plugin from `claude-code/`. The daemon starts automatically.

### Updating

The launcher checks for updates daily. To update manually:

```bash
intellij-scala-lsp --update
```
```

Replace the "Usage" section (lines 93-131) with:

```markdown
## Development

```bash
# Build (first run downloads ~1.5GB of IntelliJ SDK + plugins)
sbt lsp-server/compile

# Run tests
sbt lsp-server/test

# Build and run the LSP server
sbt "lsp-server/runLsp --daemon"

# Or run the launcher directly
sbt lsp-server/packageArtifact
./launcher/intellij-scala-lsp --daemon
```
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: update README with install instructions and development workflow"
```

---

## Task 8: End-to-End Local Testing

- [ ] **Step 1: Build and run in dev mode**

```bash
sbt lsp-server/packageArtifact
./launcher/intellij-scala-lsp --version
./launcher/intellij-scala-lsp --daemon
```

Expected: daemon starts, logs to stderr, creates `daemon.pid` and `daemon.port` in `~/.cache/intellij-scala-lsp/`.

- [ ] **Step 2: Test stdio proxy**

In another terminal:

```bash
./launcher/intellij-scala-lsp
```

Expected: connects to daemon via socat, ready for JSON-RPC on stdio.

- [ ] **Step 3: Test stop**

```bash
./launcher/intellij-scala-lsp --stop
```

Expected: daemon stops, PID file removed.

- [ ] **Step 4: Test sbt task**

```bash
sbt "lsp-server/runLsp --version"
```

Expected: prints version string.

- [ ] **Step 5: Verify no regressions in existing tests**

```bash
sbt lsp-server/test
```

Expected: all tests pass.

- [ ] **Step 6: Commit any fixes from testing**

If any issues found during testing, fix and commit.
