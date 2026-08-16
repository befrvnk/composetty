#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
version="${1:-0.1.0-alpha01}"

if ! command -v op >/dev/null 2>&1; then
  echo "The 1Password CLI (op) is required" >&2
  exit 1
fi
if ! op whoami >/dev/null 2>&1; then
  echo "Sign in to 1Password CLI before running this preflight" >&2
  exit 1
fi

op run --env-file=scripts/release.env -- \
  devenv shell -- ./gradlew \
    -PVERSION_NAME="$version" \
    releasePreflight \
    --no-daemon
