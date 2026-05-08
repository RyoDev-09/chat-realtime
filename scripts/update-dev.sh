#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="/opt/raize/chat-realtime"
BRANCH="dev"

if [ ! -d "$REPO_DIR/.git" ]; then
  echo "[ERROR] Repo not found at $REPO_DIR"
  exit 1
fi

cd "$REPO_DIR"

echo "[INFO] Repo: $REPO_DIR"
echo "[INFO] Current branch: $(git branch --show-current)"

echo "[STEP] Fetch latest..."
git fetch --all --prune

echo "[STEP] Checkout $BRANCH..."
git checkout "$BRANCH"

echo "[STEP] Pull fast-forward..."
git pull --ff-only origin "$BRANCH"

echo "[STEP] Submodule sync (if any)..."
git submodule sync --recursive || true
git submodule update --init --recursive || true

echo "[DONE] Updated branch $BRANCH"
echo "[INFO] Latest commit:"
git log -1 --oneline
