#!/usr/bin/env bash
# Setup intellij-scala-lsp for Claude Code
#
# Usage: ./setup-claude-code.sh
#
# This script:
#   1. Builds the LSP server JAR with sbt
#   2. Detects IntelliJ SDK path and updates plugin.json with correct paths
#   3. Adds the local marketplace to Claude Code
#   4. Installs the plugin
#
# After running, restart Claude Code for the plugin to take effect.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LAUNCHER="$SCRIPT_DIR/launcher/launch-lsp.sh"
MARKETPLACE_DIR="$SCRIPT_DIR/claude-code"
PLUGIN_DIR="$MARKETPLACE_DIR/intellij-scala-lsp"
PLUGIN_JSON="$PLUGIN_DIR/.claude-plugin/plugin.json"

echo "=== Setting up intellij-scala-lsp for Claude Code ==="
echo ""

# --- Step 1: Check prerequisites ---

if ! command -v sbt &>/dev/null; then
  echo "ERROR: sbt not found. Install it first:"
  echo "  macOS:  brew install sbt"
  echo "  Linux:  https://www.scala-sbt.org/download/"
  exit 1
fi

if ! command -v claude &>/dev/null; then
  echo "ERROR: claude not found. Is Claude Code installed?"
  echo "  Install: https://claude.ai/claude-code"
  exit 1
fi

# --- Step 2: Build the LSP server ---

echo "[1/5] Building LSP server JAR..."
(cd "$SCRIPT_DIR" && sbt -no-colors "lsp-server/packageArtifact" 2>&1 | tail -5)

LSP_LIB_DIR="$SCRIPT_DIR/lsp-server/target/plugin/intellij-scala-lsp/lib"
if [ ! -f "$LSP_LIB_DIR/lsp-server.jar" ]; then
  echo "ERROR: Build failed. lsp-server.jar not found at $LSP_LIB_DIR"
  exit 1
fi
echo "  Built successfully."

# --- Step 3: Detect IntelliJ SDK path ---

echo ""
echo "[2/5] Detecting IntelliJ SDK..."

IDEA_HOME=""
# sbt-idea-plugin SDK location
SBT_SDK=$(cd "$SCRIPT_DIR" && sbt -no-colors "show ThisBuild / intellijBaseDirectory" 2>/dev/null | tail -1 | awk '{print $NF}')
if [ -n "$SBT_SDK" ] && [ -d "$SBT_SDK/lib" ]; then
  IDEA_HOME="$SBT_SDK"
fi

if [ -z "$IDEA_HOME" ]; then
  echo "ERROR: Could not detect IntelliJ SDK. Run 'sbt compile' first to download the SDK."
  exit 1
fi
echo "  IntelliJ SDK: $IDEA_HOME"

# --- Step 4: Generate plugin.json with correct paths ---

echo ""
echo "[3/5] Generating plugin configuration..."

# Remove any stale .lsp.json (config is now inline in plugin.json)
rm -f "$PLUGIN_DIR/.lsp.json"

cat > "$PLUGIN_JSON" << PLUGINJSON
{
  "name": "intellij-scala-lsp",
  "description": "Scala code intelligence powered by IntelliJ IDEA's analysis engine",
  "version": "0.5.0",
  "lspServers": {
    "intellij-scala": {
      "command": "bash",
      "args": ["$LAUNCHER"],
      "env": {
        "INTELLIJ_HOME": "$IDEA_HOME"
      },
      "extensionToLanguage": {
        ".scala": "scala",
        ".sc": "scala"
      },
      "startupTimeout": 300000
    }
  }
}
PLUGINJSON

chmod +x "$LAUNCHER"
echo "  Created: $PLUGIN_JSON"

# --- Step 5: Add local marketplace ---

echo ""
echo "[4/5] Adding local marketplace to Claude Code..."

if claude plugin marketplace list 2>/dev/null | grep -q "intellij-scala-lsp"; then
  echo "  Marketplace already added. Updating..."
  claude plugin marketplace update intellij-scala-lsp 2>/dev/null || true
else
  claude plugin marketplace add "$MARKETPLACE_DIR"
  echo "  Marketplace added."
fi

# --- Step 6: Install plugin ---

echo ""
echo "[5/5] Installing intellij-scala-lsp plugin..."

claude plugin install intellij-scala-lsp@intellij-scala-lsp --scope user 2>/dev/null || true
echo "  Plugin installed."

# --- Done ---

echo ""
echo "=== Setup complete ==="
echo ""
echo "Restart Claude Code for the plugin to take effect."
echo ""
echo "The LSP server provides: goToDefinition, findReferences, hover,"
echo "documentSymbol, workspaceSymbol, completion, rename, type hierarchy,"
echo "call hierarchy, code actions, diagnostics, and more for .scala files."
echo ""
echo "First launch takes ~30 seconds to bootstrap the IntelliJ platform."
echo ""
echo "To uninstall:"
echo "  claude plugin uninstall intellij-scala-lsp@intellij-scala-lsp"
echo "  claude plugin marketplace remove intellij-scala-lsp"
