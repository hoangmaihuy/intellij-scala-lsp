#!/bin/bash
# One-line installer for scallij
# Usage: curl -fsSL https://github.com/hoangmaihuy/intellij-scala-lsp/releases/latest/download/install.sh | bash
set -euo pipefail

GITHUB_REPO="hoangmaihuy/intellij-scala-lsp"
INSTALL_DIR="${HOME}/.local/bin"

echo "=== Installing scallij ==="
echo ""

# Platform check
case "$(uname -s)" in
  Darwin|Linux) ;;
  *) echo "ERROR: Unsupported OS: $(uname -s). Only macOS and Linux are supported."; exit 1 ;;
esac

# Download launcher script
echo "[1/2] Downloading launcher..."
mkdir -p "$INSTALL_DIR"
LAUNCHER_URL="https://github.com/${GITHUB_REPO}/releases/latest/download/scallij"
curl -fSL --progress-bar -o "$INSTALL_DIR/scallij" "$LAUNCHER_URL"
chmod +x "$INSTALL_DIR/scallij"

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
"$INSTALL_DIR/scallij" --install

echo ""
echo "=== Installation complete ==="
echo ""
echo "Run 'scallij --help' to get started."
