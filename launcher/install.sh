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
