#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THINGIFIER_TAG="${THINGIFIER_TAG:-1.5.5}"
TARGET_DIR="${1:-"$ROOT_DIR/.external/thingifier-$THINGIFIER_TAG"}"
REPO_URL="${THINGIFIER_REPO_URL:-https://github.com/eviltester/thingifier.git}"

mkdir -p "$(dirname "$TARGET_DIR")"

if [[ -d "$TARGET_DIR/.git" ]]; then
  CURRENT_REF="$(git -C "$TARGET_DIR" rev-parse --abbrev-ref HEAD || true)"
  echo "Existing checkout detected at $TARGET_DIR (ref: ${CURRENT_REF:-detached})."
else
  echo "Cloning Thingifier tag $THINGIFIER_TAG into $TARGET_DIR"
  git clone --branch "$THINGIFIER_TAG" --depth 1 "$REPO_URL" "$TARGET_DIR"
fi

git -C "$TARGET_DIR" fetch --tags --depth 1 origin "$THINGIFIER_TAG" >/dev/null 2>&1 || true
git -C "$TARGET_DIR" checkout "$THINGIFIER_TAG" >/dev/null 2>&1

RESOLVED_COMMIT="$(git -C "$TARGET_DIR" rev-parse HEAD)"
echo "Thingifier source is ready at $TARGET_DIR"
echo "Resolved commit: $RESOLVED_COMMIT"
