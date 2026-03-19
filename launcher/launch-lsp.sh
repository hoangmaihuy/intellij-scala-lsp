#!/bin/bash
# Launch IntelliJ Scala LSP server
# Usage: launch-lsp.sh [projectPath]
#
# Environment variables:
#   INTELLIJ_HOME  - Path to IntelliJ Community installation (auto-detected if not set)
#   JAVA_HOME      - Path to JDK (uses IntelliJ's bundled JBR if not set)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="${HOME}/.cache/intellij-scala-lsp"

INTELLIJ_VERSION="261.22158.121"
INTELLIJ_BUILD="2026.1"
SCALA_PLUGIN_VERSION="2026.1.7"

# --- Locate or download IntelliJ ---

find_intellij_home() {
  # 1. Explicit env var
  if [ -n "${INTELLIJ_HOME:-}" ] && [ -d "$INTELLIJ_HOME/lib" ]; then
    echo "$INTELLIJ_HOME"
    return 0
  fi

  # 2. Cached download
  local cached="$CACHE_DIR/idea-IC-$INTELLIJ_VERSION"
  if [ -d "$cached/lib" ]; then
    echo "$cached"
    return 0
  fi

  # 3. Common macOS locations
  for app in \
    "/Applications/IntelliJ IDEA CE.app/Contents" \
    "/Applications/IntelliJ IDEA Community Edition.app/Contents" \
    "$HOME/Applications/IntelliJ IDEA CE.app/Contents"; do
    if [ -d "$app/lib" ]; then
      echo "$app"
      return 0
    fi
  done

  return 1
}

download_intellij() {
  echo "[launch-lsp] Downloading IntelliJ Community $INTELLIJ_BUILD..." >&2
  mkdir -p "$CACHE_DIR"

  local os_name
  os_name="$(uname -s)"
  local arch
  arch="$(uname -m)"

  local url
  if [ "$os_name" = "Darwin" ]; then
    if [ "$arch" = "arm64" ] || [ "$arch" = "aarch64" ]; then
      url="https://download.jetbrains.com/idea/ideaIC-${INTELLIJ_BUILD}-aarch64.tar.gz"
    else
      url="https://download.jetbrains.com/idea/ideaIC-${INTELLIJ_BUILD}.tar.gz"
    fi
  else
    if [ "$arch" = "aarch64" ]; then
      url="https://download.jetbrains.com/idea/ideaIC-${INTELLIJ_BUILD}-aarch64.tar.gz"
    else
      url="https://download.jetbrains.com/idea/ideaIC-${INTELLIJ_BUILD}.tar.gz"
    fi
  fi

  local tarball="$CACHE_DIR/ideaIC-${INTELLIJ_BUILD}.tar.gz"
  if [ ! -f "$tarball" ]; then
    echo "[launch-lsp] URL: $url" >&2
    curl -fSL -o "$tarball" "$url"
  fi

  echo "[launch-lsp] Extracting..." >&2
  tar xzf "$tarball" -C "$CACHE_DIR"

  # Find the extracted directory
  local extracted
  extracted=$(find "$CACHE_DIR" -maxdepth 1 -type d -name "idea-IC*" | head -1)
  if [ -z "$extracted" ]; then
    extracted=$(find "$CACHE_DIR" -maxdepth 1 -type d -name "*idea*" | head -1)
  fi

  local target="$CACHE_DIR/idea-IC-$INTELLIJ_VERSION"
  if [ "$extracted" != "$target" ] && [ -n "$extracted" ]; then
    mv "$extracted" "$target"
  fi

  echo "[launch-lsp] IntelliJ installed at $target" >&2
  echo "$target"
}

download_scala_plugin() {
  local plugin_dir="$CACHE_DIR/scala-plugin-$SCALA_PLUGIN_VERSION"
  if [ -d "$plugin_dir/lib" ]; then
    echo "$plugin_dir"
    return 0
  fi

  echo "[launch-lsp] Downloading Scala plugin $SCALA_PLUGIN_VERSION..." >&2
  mkdir -p "$CACHE_DIR"

  local zipfile="$CACHE_DIR/scala-plugin-${SCALA_PLUGIN_VERSION}.zip"
  if [ ! -f "$zipfile" ]; then
    local url="https://plugins.jetbrains.com/plugin/download?pluginId=org.intellij.scala&version=${SCALA_PLUGIN_VERSION}&build=IC-${INTELLIJ_VERSION}"
    curl -fSL -o "$zipfile" "$url"
  fi

  mkdir -p "$plugin_dir"
  unzip -o "$zipfile" -d "$plugin_dir" >/dev/null

  # Move Scala/ contents up if needed
  if [ -d "$plugin_dir/Scala/lib" ] && [ ! -d "$plugin_dir/lib" ]; then
    mv "$plugin_dir/Scala/"* "$plugin_dir/"
    rmdir "$plugin_dir/Scala" 2>/dev/null || true
  fi

  echo "[launch-lsp] Scala plugin installed at $plugin_dir" >&2
  echo "$plugin_dir"
}

# --- Main ---

IDEA_HOME=$(find_intellij_home || download_intellij)
SCALA_PLUGIN_DIR=$(download_scala_plugin)

echo "[launch-lsp] IntelliJ: $IDEA_HOME" >&2
echo "[launch-lsp] Scala plugin: $SCALA_PLUGIN_DIR" >&2

# Find Java runtime
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif [ -d "$IDEA_HOME/jbr/Contents/Home" ]; then
  # macOS bundled JBR
  JAVA="$IDEA_HOME/jbr/Contents/Home/bin/java"
elif [ -d "$IDEA_HOME/jbr" ]; then
  # Linux bundled JBR
  JAVA="$IDEA_HOME/jbr/bin/java"
else
  JAVA="java"
fi

echo "[launch-lsp] Java: $JAVA" >&2

# Build classpath
CLASSPATH=""

# IntelliJ platform JARs
for jar in "$IDEA_HOME/lib/"*.jar; do
  CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
done

# Scala plugin JARs
if [ -d "$SCALA_PLUGIN_DIR/lib" ]; then
  for jar in "$SCALA_PLUGIN_DIR/lib/"*.jar; do
    CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
  done
fi

# LSP server JAR (built by Mill)
LSP_JAR="$PROJECT_ROOT/out/lsp-server/assembly.dest/out.jar"
if [ ! -f "$LSP_JAR" ]; then
  echo "[launch-lsp] LSP server JAR not found at $LSP_JAR" >&2
  echo "[launch-lsp] Building with Mill..." >&2
  (cd "$PROJECT_ROOT" && mill lsp-server.assembly)
  if [ ! -f "$LSP_JAR" ]; then
    echo "[launch-lsp] ERROR: Build failed. Run 'mill lsp-server.assembly' first." >&2
    exit 1
  fi
fi
CLASSPATH="${CLASSPATH:+$CLASSPATH:}$LSP_JAR"

# Use dedicated config/system dirs to avoid polluting user's IntelliJ settings
CONFIG_DIR="$CACHE_DIR/config"
SYSTEM_DIR="$CACHE_DIR/system"
mkdir -p "$CONFIG_DIR" "$SYSTEM_DIR"

# Install our plugin into the config
PLUGIN_INSTALL_DIR="$CONFIG_DIR/plugins/intellij-scala-lsp/lib"
mkdir -p "$PLUGIN_INSTALL_DIR"
cp -f "$LSP_JAR" "$PLUGIN_INSTALL_DIR/"

exec "$JAVA" \
  -Xmx2g \
  -Djava.awt.headless=true \
  -Didea.is.internal=false \
  -Didea.config.path="$CONFIG_DIR" \
  -Didea.system.path="$SYSTEM_DIR" \
  -Didea.log.path="$SYSTEM_DIR/log" \
  -Didea.plugins.path="$CONFIG_DIR/plugins" \
  -Didea.classpath.index.enabled=false \
  -cp "$CLASSPATH" \
  com.intellij.idea.Main scala-lsp "$@"
