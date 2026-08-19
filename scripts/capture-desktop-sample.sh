#!/usr/bin/env bash
set -euo pipefail

workspace="$(pwd)"
if [ ! -x "$workspace/gradlew" ]; then
  echo "Run capture-desktop-sample.sh from the repository root" >&2
  exit 1
fi

output="$workspace/docs/images"
mkdir -p "$output"

devenv shell -- ./gradlew :samples:remote:run &
launcher=$!
cleanup() {
  kill "$launcher" 2>/dev/null || true
  wait "$launcher" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "Wait for the Composetty window, then select it in the macOS capture picker."
sleep 8
screencapture -i -W -o -x "$output/desktop-loopback.png"

echo "Captured $output/desktop-loopback.png"
