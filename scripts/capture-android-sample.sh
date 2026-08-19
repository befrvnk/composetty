#!/usr/bin/env bash
set -euo pipefail

workspace="$(pwd)"
if [ ! -x "$workspace/gradlew" ]; then
  echo "Run capture-android-sample.sh from the repository root" >&2
  exit 1
fi
if [ ! -d "$workspace/docs" ]; then
  echo "Expected docs directory in the repository root" >&2
  exit 1
fi

output="$workspace/docs/images"
mkdir -p "$output"

if ! command -v adb >/dev/null; then
  echo "Run this script through the project environment: devenv shell -- scripts/capture-android-sample.sh" >&2
  exit 1
fi

adb_command=(adb)
if [ -n "${ANDROID_SERIAL:-}" ]; then
  adb_command+=(-s "$ANDROID_SERIAL")
fi
if ! "${adb_command[@]}" get-state >/dev/null 2>&1; then
  echo "Connect and authorize one Android device, or set ANDROID_SERIAL" >&2
  exit 1
fi

./gradlew :samples:android:assembleDebug
"${adb_command[@]}" install -r "$workspace/samples/android/build/outputs/apk/debug/android-debug.apk"
"${adb_command[@]}" shell am start -S -W -n dev.befrvnk.composetty.sample/.MainActivity >/dev/null
echo "Foreground the Composetty sample on the device to begin capture."
for _ in $(seq 1 15); do
  resumed_activity="$("${adb_command[@]}" shell dumpsys activity activities | tr -d '\r' | grep 'topResumedActivity=' || true)"
  case "$resumed_activity" in
    *"dev.befrvnk.composetty.sample/.MainActivity"*) break ;;
  esac
  sleep 1
done
case "$resumed_activity" in
  *"dev.befrvnk.composetty.sample/.MainActivity"*) ;;
  *)
    echo "The sample did not resume within 15 seconds." >&2
    exit 1
    ;;
esac
ime_state="$("${adb_command[@]}" shell dumpsys input_method | tr -d '\r' | grep 'mInputShown=' || true)"
if [[ "$ime_state" == *"mInputShown=true"* ]]; then
  "${adb_command[@]}" shell input keyevent KEYCODE_BACK
  for _ in $(seq 1 5); do
    ime_state="$("${adb_command[@]}" shell dumpsys input_method | tr -d '\r' | grep 'mInputShown=' || true)"
    [[ "$ime_state" != *"mInputShown=true"* ]] && break
    sleep 1
  done
fi
sleep 2
"${adb_command[@]}" exec-out screencap -p >"$output/android-loopback.png"

echo "Captured $output/android-loopback.png"
