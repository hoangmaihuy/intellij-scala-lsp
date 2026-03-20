#!/bin/bash
# Integration tests for launch-lsp.sh
# Usage: bash lsp-server/test/launcher/test-launch-lsp.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LAUNCH_SCRIPT="$PROJECT_ROOT/launcher/launch-lsp.sh"
PASS=0
FAIL=0
SKIP=0

# Use sbt-idea-plugin's SDK as INTELLIJ_HOME
SDK_DIR=""
for candidate in "$PROJECT_ROOT"/target/aarch64/idea/*/Contents \
                  "$PROJECT_ROOT"/target/idea/*/Contents \
                  "$PROJECT_ROOT"/target/aarch64/idea/* \
                  "$PROJECT_ROOT"/target/idea/*; do
  if [ -d "$candidate/lib" ]; then
    SDK_DIR="$candidate"
    break
  fi
done

if [ -z "$SDK_DIR" ]; then
  echo "SKIP: IntelliJ SDK not found. Run 'sbt updateIntellij' first."
  exit 0
fi

echo "Using SDK: $SDK_DIR"

# --- Test helpers ---

send_jsonrpc() {
  local msg="$1"
  printf "Content-Length: %d\r\n\r\n%s" "${#msg}" "$msg"
}

pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }
skip() { echo "  SKIP: $1"; SKIP=$((SKIP + 1)); }

# --- Test 1: Bad INTELLIJ_HOME ---
echo ""
echo "Test 1: Bad INTELLIJ_HOME"
stderr=$(INTELLIJ_HOME="/nonexistent/path" bash "$LAUNCH_SCRIPT" 2>&1 || true)
if echo "$stderr" | grep -q "No IntelliJ installation found"; then
  pass "Reports missing IntelliJ"
else
  fail "Expected 'No IntelliJ installation found' in stderr"
fi

# --- Test 2: Missing package artifact ---
echo ""
echo "Test 2: Missing package artifact"
TEMP_PROJECT=$(mktemp -d)
mkdir -p "$TEMP_PROJECT/launcher"
cp "$LAUNCH_SCRIPT" "$TEMP_PROJECT/launcher/"
stderr=$(INTELLIJ_HOME="$SDK_DIR" bash "$TEMP_PROJECT/launcher/launch-lsp.sh" 2>&1 || true)
rm -rf "$TEMP_PROJECT"
if echo "$stderr" | grep -q "LSP server JARs not found"; then
  pass "Reports missing JARs"
else
  fail "Expected 'LSP server JARs not found' in stderr"
fi

# --- Test 3: Stop with no daemon ---
echo ""
echo "Test 3: Stop with no daemon"
TEMP_CACHE=$(mktemp -d)
stderr=$(INTELLIJ_HOME="$SDK_DIR" HOME="$TEMP_CACHE" bash "$LAUNCH_SCRIPT" --stop 2>&1 || true)
rm -rf "$TEMP_CACHE"
if echo "$stderr" | grep -q "No daemon running"; then
  pass "Handles --stop with no daemon"
else
  fail "Expected 'No daemon running' in stderr"
fi

# --- Test 4: Environment detection ---
echo ""
echo "Test 4: Environment detection"
stderr=$(INTELLIJ_HOME="$SDK_DIR" bash "$LAUNCH_SCRIPT" --stop 2>&1 || true)
if echo "$stderr" | grep -q "IntelliJ:"; then
  pass "Detects IntelliJ installation"
else
  fail "Should detect IntelliJ at $SDK_DIR"
fi

# --- Summary ---
echo ""
echo "=== Launcher Test Results ==="
echo "PASS: $PASS  FAIL: $FAIL  SKIP: $SKIP"
if [ $FAIL -gt 0 ]; then
  exit 1
fi
