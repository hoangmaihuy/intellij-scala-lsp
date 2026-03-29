#!/bin/bash
# One-line installer for scalij
# Usage: curl -fsSL https://github.com/hoangmaihuy/intellij-scala-lsp/releases/latest/download/install.sh | bash
set -euo pipefail

GITHUB_REPO="hoangmaihuy/intellij-scala-lsp"
INSTALL_DIR="${HOME}/.local/bin"

echo "=== Installing scalij ==="
echo ""

# Platform check
case "$(uname -s)" in
  Darwin|Linux) ;;
  *) echo "ERROR: Unsupported OS: $(uname -s). Only macOS and Linux are supported."; exit 1 ;;
esac

# Install socat if missing
if ! command -v socat &>/dev/null; then
  echo "[0/2] Installing socat..."
  case "$(uname -s)" in
    Darwin)
      if command -v brew &>/dev/null; then
        brew install socat
      else
        echo "ERROR: socat is required but not installed. Install Homebrew (https://brew.sh) then run: brew install socat"; exit 1
      fi
      ;;
    Linux)
      if command -v apt-get &>/dev/null; then
        sudo apt-get update -qq && sudo apt-get install -y -qq socat
      elif command -v dnf &>/dev/null; then
        sudo dnf install -y socat
      elif command -v pacman &>/dev/null; then
        sudo pacman -S --noconfirm socat
      else
        echo "ERROR: socat is required but not installed. Install it with your package manager."; exit 1
      fi
      ;;
  esac
fi

# Install python3 if missing
if ! command -v python3 &>/dev/null; then
  echo "[0/2] Installing python3..."
  case "$(uname -s)" in
    Darwin)
      if command -v brew &>/dev/null; then
        brew install python3
      else
        echo "ERROR: python3 is required but not installed. Install Homebrew (https://brew.sh) then run: brew install python3"; exit 1
      fi
      ;;
    Linux)
      if command -v apt-get &>/dev/null; then
        sudo apt-get update -qq && sudo apt-get install -y -qq python3
      elif command -v dnf &>/dev/null; then
        sudo dnf install -y python3
      elif command -v pacman &>/dev/null; then
        sudo pacman -S --noconfirm python
      else
        echo "ERROR: python3 is required but not installed. Install it with your package manager."; exit 1
      fi
      ;;
  esac
fi

# Download launcher script
echo "[1/2] Downloading launcher..."
mkdir -p "$INSTALL_DIR"
LAUNCHER_URL="https://github.com/${GITHUB_REPO}/releases/latest/download/scalij"
curl -fSL --progress-bar -o "$INSTALL_DIR/scalij" "$LAUNCHER_URL"
chmod +x "$INSTALL_DIR/scalij"

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
"$INSTALL_DIR/scalij" --install

echo ""
echo "=== Installation complete ==="
echo ""
echo "Run 'scalij --help' to get started."
