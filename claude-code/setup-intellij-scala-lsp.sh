#!/usr/bin/env bash
# Setup IntelliJ Scala LSP for Claude Code
# Run once per machine: ./claude-code/setup-intellij-scala-lsp.sh
#
# This configures Claude Code to use IntelliJ IDEA's Scala engine (via LSP)
# for code intelligence (goToDefinition, findReferences, hover, etc.) on .scala files.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LAUNCHER="$PROJECT_ROOT/launcher/launch-lsp.sh"

MARKETPLACE_DIR="$HOME/.claude/plugins/marketplaces/claude-plugins-official"
MARKETPLACE_JSON="$MARKETPLACE_DIR/.claude-plugin/marketplace.json"
PLUGIN_DIR="$MARKETPLACE_DIR/plugins/intellij-scala-lsp/.claude-plugin"

echo "=== Setting up IntelliJ Scala LSP for Claude Code ==="

# Step 1: Build the LSP server
echo ""
echo "[1/3] Building LSP server JAR..."
if command -v mill &>/dev/null; then
  (cd "$PROJECT_ROOT" && mill lsp-server.assembly)
  echo "  LSP server built successfully."
else
  echo "  ERROR: Mill build tool not found. Install it first:"
  echo "    curl -L https://raw.githubusercontent.com/lefou/millw/0.4.12/millw > mill && chmod +x mill"
  exit 1
fi

# Step 2: Register plugin in marketplace
echo ""
echo "[2/3] Registering intellij-scala-lsp plugin in marketplace..."
if [ ! -f "$MARKETPLACE_JSON" ]; then
  echo "  ERROR: Claude Code marketplace not found at $MARKETPLACE_JSON"
  echo "  Make sure Claude Code is installed and has been run at least once."
  exit 1
fi

if grep -q '"intellij-scala-lsp"' "$MARKETPLACE_JSON" 2>/dev/null; then
  echo "  intellij-scala-lsp already registered in marketplace."
else
  mkdir -p "$PLUGIN_DIR"
  cat > "$PLUGIN_DIR/plugin.json" << PLUGIN_EOF
{
  "name": "intellij-scala-lsp",
  "description": "IntelliJ IDEA Scala language server for code intelligence",
  "version": "0.1.0",
  "author": {
    "name": "JetBrains",
    "email": "scala-plugin-dev@jetbrains.com"
  },
  "lspServers": {
    "intellij-scala": {
      "command": "$LAUNCHER",
      "extensionToLanguage": {
        ".scala": "scala",
        ".sc": "scala",
        ".sbt": "sbt"
      },
      "startupTimeout": 300000
    }
  }
}
PLUGIN_EOF

  python3 - "$MARKETPLACE_JSON" << 'PYEOF'
import json, sys

mp_path = sys.argv[1]
with open(mp_path, "r") as f:
    data = json.load(f)

entry = {
    "name": "intellij-scala-lsp",
    "description": "IntelliJ IDEA Scala language server for code intelligence",
    "version": "0.1.0",
    "author": {"name": "JetBrains", "email": "scala-plugin-dev@jetbrains.com"},
    "source": "./plugins/intellij-scala-lsp",
    "category": "development",
    "strict": False,
    "lspServers": {
        "intellij-scala": {
            "command": "$LAUNCHER",
            "extensionToLanguage": {
                ".scala": "scala",
                ".sc": "scala",
                ".sbt": "sbt"
            },
            "startupTimeout": 300000
        }
    }
}

plugins = data["plugins"]
# Insert before agent-sdk-dev if present, otherwise append
insert_idx = len(plugins)
for i, p in enumerate(plugins):
    if p.get("name") == "agent-sdk-dev":
        insert_idx = i
        break

plugins.insert(insert_idx, entry)

with open(mp_path, "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
PYEOF
  echo "  intellij-scala-lsp registered in marketplace."
fi

# Step 3: Install via Claude CLI
echo ""
echo "[3/3] Installing intellij-scala-lsp plugin..."
claude plugin install intellij-scala-lsp@claude-plugins-official --scope user 2>/dev/null || true

echo ""
echo "=== Setup complete ==="
echo ""
echo "Restart Claude Code for changes to take effect."
echo "The LSP server provides: goToDefinition, findReferences, hover,"
echo "documentSymbol, and workspaceSymbol for .scala files."
echo ""
echo "NOTE: First launch will take longer due to IntelliJ indexing."
echo "Startup timeout is set to 300s (5 minutes)."
