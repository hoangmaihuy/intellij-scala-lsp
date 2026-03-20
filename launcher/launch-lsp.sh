#!/bin/bash
# Launch IntelliJ Scala LSP server in headless mode
# Usage: launch-lsp.sh [projectPath]
#
# Detects IntelliJ installation, reads its product-info.json to get the
# exact JVM arguments, classpath, and class loader config needed for headless launch.
#
# Environment variables (optional):
#   INTELLIJ_HOME  - Path to IntelliJ's Contents dir (e.g., /Applications/IntelliJ IDEA CE.app/Contents)
#   JAVA_HOME      - Override JDK (default: IntelliJ's bundled JBR)
#   LSP_HEAP_SIZE  - JVM heap size (default: 2g)
set -euo pipefail

# Log stderr to file for debugging (tail -f this file to watch)
LSP_LOG="${HOME}/.cache/intellij-scala-lsp/lsp-server.log"
mkdir -p "$(dirname "$LSP_LOG")"
exec 2> >(tee -a "$LSP_LOG" >&2)
echo "[launch-lsp] === Starting at $(date) ===" >&2

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="${HOME}/.cache/intellij-scala-lsp"

# --- Locate IntelliJ ---

find_intellij_home() {
  if [ -n "${INTELLIJ_HOME:-}" ] && [ -d "$INTELLIJ_HOME/lib" ]; then
    echo "$INTELLIJ_HOME"; return 0
  fi
  for app in \
    "$HOME/Applications/IntelliJ IDEA.app/Contents" \
    "$HOME/Applications/IntelliJ IDEA CE.app/Contents" \
    "$HOME/Applications/IntelliJ IDEA Community Edition.app/Contents" \
    "/Applications/IntelliJ IDEA.app/Contents" \
    "/Applications/IntelliJ IDEA CE.app/Contents" \
    "/Applications/IntelliJ IDEA Community Edition.app/Contents"; do
    if [ -d "$app/lib" ]; then echo "$app"; return 0; fi
  done
  # Linux
  for dir in /opt/idea-IC /opt/idea-IU /snap/intellij-idea-community/current /snap/intellij-idea-ultimate/current; do
    if [ -d "$dir/lib" ]; then echo "$dir"; return 0; fi
  done
  return 1
}

IDEA_HOME=$(find_intellij_home) || {
  echo "[launch-lsp] ERROR: No IntelliJ installation found." >&2
  echo "[launch-lsp] Install IntelliJ IDEA (Community or Ultimate) or set INTELLIJ_HOME." >&2
  exit 1
}
echo "[launch-lsp] IntelliJ: $IDEA_HOME" >&2

# --- Locate Scala plugin ---

find_scala_plugin() {
  if [ -d "$IDEA_HOME/plugins/Scala/lib" ]; then
    echo "$IDEA_HOME/plugins/Scala"; return 0
  fi
  # sbt-idea-plugin SDK layout
  if [ -d "$IDEA_HOME/custom-plugins/Scala/lib" ]; then
    echo "$IDEA_HOME/custom-plugins/Scala"; return 0
  fi

  # Determine IntelliJ major version prefix (e.g. "253" -> "2025.3", "261" -> "2026.1")
  # to pick a compatible Scala plugin from user config dirs
  local ide_build=""
  local pi="$IDEA_HOME/Resources/product-info.json"
  [ ! -f "$pi" ] && pi="$IDEA_HOME/product-info.json"
  if [ -f "$pi" ]; then
    ide_build=$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d.get('buildNumber',''))" "$pi" 2>/dev/null)
  fi
  local ide_prefix="${ide_build%%.*}"  # e.g. "253"

  # Map build prefix to JetBrains config dir name pattern
  # 253 -> 2025.3, 252 -> 2025.2, 251 -> 2025.1, 261 -> 2026.1, etc.
  local config_pattern=""
  if [ -n "$ide_prefix" ]; then
    local major="${ide_prefix:0:2}"  # "25" or "26"
    local minor="${ide_prefix:2:1}"  # "3" or "1"
    config_pattern="20${major}.${minor}"
    echo "[launch-lsp] Looking for Scala plugin matching IDE build $ide_build (version $config_pattern)" >&2
  fi

  # macOS JetBrains config — prefer matching version, fall back to any
  local found_any=""
  while IFS= read -r d; do
    if [ -d "$d/lib" ]; then
      if [ -n "$config_pattern" ] && [[ "$d" == *"$config_pattern"* ]]; then
        echo "$d"; return 0
      fi
      [ -z "$found_any" ] && found_any="$d"
    fi
  done < <(ls -dt "$HOME/Library/Application Support/JetBrains"/*/plugins/Scala 2>/dev/null)
  # Linux config
  while IFS= read -r d; do
    if [ -d "$d/lib" ]; then
      if [ -n "$config_pattern" ] && [[ "$d" == *"$config_pattern"* ]]; then
        echo "$d"; return 0
      fi
      [ -z "$found_any" ] && found_any="$d"
    fi
  done < <(ls -dt "$HOME/.config/JetBrains"/*/plugins/Scala 2>/dev/null)
  # Fall back to any found plugin if no version match
  if [ -n "$found_any" ]; then
    echo "[launch-lsp] WARNING: No version-matched Scala plugin found, using: $found_any" >&2
    echo "$found_any"; return 0
  fi
  # Cached download
  for d in "$CACHE_DIR"/scala-plugin-*; do
    if [ -d "$d/lib" ]; then echo "$d"; return 0; fi
  done
  return 1
}

SCALA_PLUGIN_DIR=$(find_scala_plugin) || {
  echo "[launch-lsp] ERROR: Scala plugin not found. Install it in IntelliJ first." >&2
  exit 1
}
echo "[launch-lsp] Scala plugin: $SCALA_PLUGIN_DIR" >&2

# --- Read product-info.json for correct launch config ---

PRODUCT_INFO="$IDEA_HOME/Resources/product-info.json"
if [ ! -f "$PRODUCT_INFO" ]; then
  PRODUCT_INFO="$IDEA_HOME/product-info.json"
fi
if [ ! -f "$PRODUCT_INFO" ]; then
  echo "[launch-lsp] ERROR: product-info.json not found in $IDEA_HOME" >&2
  exit 1
fi

# Extract boot classpath JARs and JVM arguments from product-info.json
read_product_info() {
  python3 - "$PRODUCT_INFO" "$IDEA_HOME" << 'PYEOF'
import json, sys, os

product_info_path = sys.argv[1]
idea_home = sys.argv[2]

with open(product_info_path) as f:
    info = json.load(f)

# Find the matching launch config (prefer current arch)
import platform
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

# Resolve $APP_PACKAGE
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

# Boot classpath
boot_jars = launch.get("bootClassPathJarNames", [])
boot_cp = ":".join(os.path.join(idea_home, "lib", j) for j in boot_jars)
print("BOOT_CP=" + boot_cp)

# Additional JVM arguments
jvm_args = []
for arg in launch.get("additionalJvmArguments", []):
    resolved = resolve(arg)
    # Skip splash screen
    if "splash" in resolved.lower():
        continue
    jvm_args.append(resolved)
print("JVM_ARGS=" + "|||".join(jvm_args))

# Java executable
java_path = resolve(launch.get("javaExecutablePath", "java"))
print("JAVA_PATH=" + java_path)

# Main class
print("MAIN_CLASS=" + launch.get("mainClass", "com.intellij.idea.Main"))
PYEOF
}

LAUNCH_INFO=$(read_product_info)
BOOT_CP=$(echo "$LAUNCH_INFO" | grep "^BOOT_CP=" | cut -d= -f2-)
MAIN_CLASS=$(echo "$LAUNCH_INFO" | grep "^MAIN_CLASS=" | cut -d= -f2-)
JAVA_DEFAULT=$(echo "$LAUNCH_INFO" | grep "^JAVA_PATH=" | cut -d= -f2-)
JVM_ARGS_RAW=$(echo "$LAUNCH_INFO" | grep "^JVM_ARGS=" | cut -d= -f2-)

# --- Find Java (prefer IntelliJ's bundled JBR) ---

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif [ -x "$IDEA_HOME/jbr/Contents/Home/bin/java" ]; then
  JAVA="$IDEA_HOME/jbr/Contents/Home/bin/java"
elif [ -x "$IDEA_HOME/jbr/bin/java" ]; then
  JAVA="$IDEA_HOME/jbr/bin/java"
elif [ -x "$JAVA_DEFAULT" ]; then
  JAVA="$JAVA_DEFAULT"
else
  JAVA="java"
fi
echo "[launch-lsp] Java: $JAVA" >&2

# --- LSP server JARs ---

LSP_LIB_DIR="$PROJECT_ROOT/lsp-server/target/plugin/intellij-scala-lsp/lib"
if [ ! -d "$LSP_LIB_DIR" ] || [ -z "$(ls "$LSP_LIB_DIR"/*.jar 2>/dev/null)" ]; then
  echo "[launch-lsp] ERROR: LSP server JARs not found at $LSP_LIB_DIR" >&2
  echo "[launch-lsp] Run: cd $PROJECT_ROOT && sbt 'lsp-server/packageArtifact'" >&2
  exit 1
fi

# --- Build classpath ---

CLASSPATH="$BOOT_CP"
# Add all lib JARs not already in boot classpath
for jar in "$IDEA_HOME/lib/"*.jar; do
  case "$CLASSPATH" in
    *"$(basename "$jar")"*) ;; # Already in boot classpath
    *) CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar" ;;
  esac
done

# Add LSP server JARs to classpath (for ScalaLspMain entry point).
# IMPORTANT: lsp-server.jar contains META-INF/plugin.xml which causes
# TestApplicationManager's plugin loader to discover it from the classpath
# AND from -Dplugin.path, leading to "jarFiles is not set" fatal assertion.
# Fix: create a stripped copy without plugin.xml for the classpath.
STRIPPED_JAR="$CACHE_DIR/lsp-server-stripped.jar"
if [ "$LSP_LIB_DIR/lsp-server.jar" -nt "$STRIPPED_JAR" ] 2>/dev/null; then
  cp "$LSP_LIB_DIR/lsp-server.jar" "$STRIPPED_JAR"
  zip -qd "$STRIPPED_JAR" "META-INF/plugin.xml" 2>/dev/null || true
fi
for jar in "$LSP_LIB_DIR/"*.jar; do
  if [ "$(basename "$jar")" = "lsp-server.jar" ]; then
    CLASSPATH="${CLASSPATH:+$CLASSPATH:}$STRIPPED_JAR"
  else
    CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
  fi
done

# Add Scala runtime JARs to classpath (needed by our Scala code).
# Only the runtime JARs, not the full Scala plugin (which loads via -Dplugin.path).
for jar in "scala-library.jar" "scala3-library_3.jar"; do
  if [ -f "$SCALA_PLUGIN_DIR/lib/$jar" ]; then
    CLASSPATH="${CLASSPATH:+$CLASSPATH:}$SCALA_PLUGIN_DIR/lib/$jar"
  fi
done

# --- Isolated config/system dirs ---

CONFIG_DIR="$CACHE_DIR/config"
SYSTEM_DIR="$CACHE_DIR/system"
mkdir -p "$CONFIG_DIR/options" "$SYSTEM_DIR"

# Copy JDK table from real IntelliJ config so projects can resolve their configured JDKs
if [ ! -f "$CONFIG_DIR/options/jdk.table.xml" ]; then
  while IFS= read -r cfg; do
    if [ -f "$cfg/options/jdk.table.xml" ]; then
      cp "$cfg/options/jdk.table.xml" "$CONFIG_DIR/options/jdk.table.xml"
      echo "[launch-lsp] Copied JDK table from $cfg" >&2
      break
    fi
  done < <(ls -dt "$HOME/Library/Application Support/JetBrains"/*/  "$HOME/.config/JetBrains"/*/ 2>/dev/null)
fi

# --- Build JVM arguments array ---

HEAP_SIZE="${LSP_HEAP_SIZE:-2g}"

# Collect all JVM args
JVM_ARGS=()
JVM_ARGS+=("-Xmx${HEAP_SIZE}")
JVM_ARGS+=("-Djava.awt.headless=true")
JVM_ARGS+=("-Dide.native.launcher=true")
# Suppress JVM CDS warning on stdout which corrupts the JSON-RPC stream
JVM_ARGS+=("-Xlog:cds=off")

# Add product-info JVM args
IFS='|||' read -ra SPLIT_ARGS <<< "$JVM_ARGS_RAW"
for arg in "${SPLIT_ARGS[@]}"; do
  [ -n "$arg" ] && JVM_ARGS+=("$arg")
done

# Override paths for isolation
JVM_ARGS+=("-Didea.home.path=$IDEA_HOME")
JVM_ARGS+=("-Didea.config.path=$CONFIG_DIR")
JVM_ARGS+=("-Didea.system.path=$SYSTEM_DIR")
JVM_ARGS+=("-Didea.log.path=$SYSTEM_DIR/log")
JVM_ARGS+=("-Didea.plugins.path=$CONFIG_DIR/plugins")
JVM_ARGS+=("-Didea.classpath.index.enabled=false")

# Tell plugin loader where plugins are (same approach as test framework in build.sbt)
PLUGIN_PATH="$LSP_LIB_DIR/.."
PLUGIN_PATH="${PLUGIN_PATH}:${SCALA_PLUGIN_DIR}"
if [ -d "$IDEA_HOME/plugins/java" ]; then
  PLUGIN_PATH="${PLUGIN_PATH}:${IDEA_HOME}/plugins/java"
fi
JVM_ARGS+=("-Dplugin.path=$PLUGIN_PATH")

# --- Launch ---

exec "$JAVA" \
  "${JVM_ARGS[@]}" \
  -cp "$CLASSPATH" \
  org.jetbrains.scalalsP.ScalaLspMain "$@"
