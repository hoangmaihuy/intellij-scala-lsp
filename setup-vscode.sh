#!/usr/bin/env bash
# Setup intellij-scala-lsp for VS Code
#
# Usage: ./setup-vscode.sh
#
# This script:
#   1. Builds the LSP server JAR with sbt
#   2. Detects IntelliJ SDK path
#   3. Creates a minimal VS Code extension that connects to the LSP server
#   4. Installs the extension into VS Code
#
# After running, reload VS Code for the extension to take effect.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LAUNCHER="$SCRIPT_DIR/launcher/launch-lsp.sh"
EXT_ID="intellij-scala-lsp"
EXT_DIR="$SCRIPT_DIR/vscode-extension"

echo "=== Setting up intellij-scala-lsp for VS Code ==="
echo ""

# --- Step 1: Check prerequisites ---

if ! command -v sbt &>/dev/null; then
  echo "ERROR: sbt not found. Install it first:"
  echo "  macOS:  brew install sbt"
  echo "  Linux:  https://www.scala-sbt.org/download/"
  exit 1
fi

if ! command -v socat &>/dev/null; then
  echo "WARNING: socat not found. Required for daemon mode."
  echo "  macOS:  brew install socat"
  echo "  Linux:  apt install socat"
fi

# --- Step 2: Build the LSP server ---

echo "[1/4] Building LSP server JAR..."
(cd "$SCRIPT_DIR" && sbt -no-colors "lsp-server/packageArtifact" 2>&1 | tail -5)

LSP_LIB_DIR="$SCRIPT_DIR/lsp-server/target/plugin/intellij-scala-lsp/lib"
if [ ! -f "$LSP_LIB_DIR/lsp-server.jar" ]; then
  echo "ERROR: Build failed. lsp-server.jar not found at $LSP_LIB_DIR"
  exit 1
fi
echo "  Built successfully."

# --- Step 3: Detect IntelliJ SDK path ---

echo ""
echo "[2/4] Detecting IntelliJ SDK..."

IDEA_HOME=""
SBT_SDK=$(cd "$SCRIPT_DIR" && sbt -no-colors "show ThisBuild / intellijBaseDirectory" 2>/dev/null | tail -1 | awk '{print $NF}')
if [ -n "$SBT_SDK" ] && [ -d "$SBT_SDK/lib" ]; then
  IDEA_HOME="$SBT_SDK"
fi

if [ -z "$IDEA_HOME" ]; then
  echo "ERROR: Could not detect IntelliJ SDK. Run 'sbt compile' first to download the SDK."
  exit 1
fi
echo "  IntelliJ SDK: $IDEA_HOME"

# --- Step 4: Create VS Code extension ---

echo ""
echo "[3/4] Creating VS Code extension..."

rm -rf "$EXT_DIR"
mkdir -p "$EXT_DIR"

# package.json
cat > "$EXT_DIR/package.json" << 'PKGJSON'
{
  "name": "intellij-scala-lsp",
  "displayName": "IntelliJ Scala LSP",
  "description": "Scala code intelligence powered by IntelliJ IDEA's analysis engine",
  "version": "0.5.0",
  "publisher": "intellij-scala-lsp",
  "engines": {
    "vscode": "^1.75.0"
  },
  "categories": ["Programming Languages"],
  "activationEvents": [
    "onLanguage:scala"
  ],
  "main": "./extension.js",
  "contributes": {
    "languages": [
      {
        "id": "scala",
        "extensions": [".scala", ".sc"]
      }
    ],
    "configuration": {
      "title": "IntelliJ Scala LSP",
      "properties": {
        "intellijScalaLsp.launcher": {
          "type": "string",
          "default": "",
          "description": "Path to launch-lsp.sh (auto-detected if empty)"
        },
        "intellijScalaLsp.intellijHome": {
          "type": "string",
          "default": "",
          "description": "Path to IntelliJ SDK (auto-detected if empty)"
        }
      }
    }
  },
  "dependencies": {
    "vscode-languageclient": "^9.0.1"
  }
}
PKGJSON

# extension.js
cat > "$EXT_DIR/extension.js" << EXTJS
const { LanguageClient, TransportKind } = require("vscode-languageclient/node");
const vscode = require("vscode");
const path = require("path");

let client;

function activate(context) {
  const config = vscode.workspace.getConfiguration("intellijScalaLsp");
  const launcher = config.get("launcher") || "$LAUNCHER";
  const intellijHome = config.get("intellijHome") || "$IDEA_HOME";

  const serverOptions = {
    command: "bash",
    args: [launcher],
    options: {
      env: {
        ...process.env,
        INTELLIJ_HOME: intellijHome,
      },
    },
    transport: TransportKind.stdio,
  };

  const clientOptions = {
    documentSelector: [
      { scheme: "file", language: "scala" },
      { scheme: "file", pattern: "**/*.scala" },
      { scheme: "file", pattern: "**/*.sc" },
    ],
    outputChannelName: "IntelliJ Scala LSP",
  };

  client = new LanguageClient(
    "intellijScalaLsp",
    "IntelliJ Scala LSP",
    serverOptions,
    clientOptions
  );

  client.start();
  context.subscriptions.push(client);

  vscode.window.showInformationMessage("IntelliJ Scala LSP starting...");
}

function deactivate() {
  if (client) {
    return client.stop();
  }
}

module.exports = { activate, deactivate };
EXTJS

# Install npm dependencies
echo "  Installing vscode-languageclient..."
(cd "$EXT_DIR" && npm install --silent 2>&1 | tail -3)

echo "  Extension created at: $EXT_DIR"

# --- Step 5: Install extension into VS Code ---

echo ""
echo "[4/4] Installing extension into VS Code..."

# Symlink into VS Code extensions directory
VSCODE_EXT_DIR="$HOME/.vscode/extensions/$EXT_ID"
rm -rf "$VSCODE_EXT_DIR"
ln -sf "$EXT_DIR" "$VSCODE_EXT_DIR"

chmod +x "$LAUNCHER"
echo "  Extension installed (symlinked to $VSCODE_EXT_DIR)"

# --- Done ---

echo ""
echo "=== Setup complete ==="
echo ""
echo "Reload VS Code for the extension to take effect."
echo "  Cmd+Shift+P -> 'Developer: Reload Window'"
echo ""
echo "The LSP server runs as a daemon (TCP port 5007) and provides:"
echo "  goToDefinition, findReferences, hover, documentSymbol, workspaceSymbol,"
echo "  diagnostics, and more for .scala files."
echo ""
echo "The daemon starts automatically when you open a .scala file."
echo "To pre-warm: ./launcher/launch-lsp.sh --daemon /path/to/project"
echo "To stop:     ./launcher/launch-lsp.sh --stop"
echo "Logs:        tail -f ~/.cache/intellij-scala-lsp/lsp-server.log"
echo ""
echo "To uninstall:"
echo "  rm -rf ~/.vscode/extensions/$EXT_ID"
