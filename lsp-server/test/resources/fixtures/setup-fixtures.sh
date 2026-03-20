#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SHARED_SRC="$SCRIPT_DIR/shared-src/src"

echo "=== Setting up E2E test fixtures ==="

# Copy shared sources into both projects
for project in sbt-project mill-project; do
  dest="$SCRIPT_DIR/$project/src"
  echo "Copying shared sources to $project..."
  rm -rf "$dest"
  cp -r "$SHARED_SRC" "$dest"
done

# Build sbt project
echo "Building sbt-project..."
cd "$SCRIPT_DIR/sbt-project"
sbt --batch compile

# Build mill project
echo "Building mill-project..."
cd "$SCRIPT_DIR/mill-project"
mill root.compile

echo "=== Fixture setup complete ==="
