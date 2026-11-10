#!/bin/sh
# Runs the keyboard's UI tests on a booted Simulator: ios/tool/ui_test.sh <simulator udid> [test name ...]
# Start `python3 ios/tool/stub_server.py` first (the page typed in, and the stand-in AI provider).
set -e
cd "$(dirname "$0")/.."
UDID="$1"; shift
ONLY=""
for t in "$@"; do ONLY="$ONLY -only-testing:RunnerUITests/KeyboardUITests/$t"; done
rm -rf ../build/ui-result
xcodebuild build-for-testing -workspace Runner.xcworkspace -scheme RunnerUITests -destination "id=$UDID" -derivedDataPath ../build/ios-dd 2>&1 \
  | grep --line-buffered -E "error:|\*\* TEST BUILD (FAILED|SUCCEEDED)" | awk '{ print substr($0, 1, 600); fflush() }' | tee ../build/ui-build.log
grep -q "TEST BUILD SUCCEEDED" ../build/ui-build.log || { echo "build failed"; exit 1; }
xcrun simctl install "$UDID" ../build/ios-dd/Build/Products/Debug-iphonesimulator/Runner.app
# iOS keeps a keyboard's process alive between fields: end the old one, by PID, so the new build is what runs.
for pid in $(pgrep -f "$UDID.*GhostKeyboard.appex/GhostKeyboard" || true); do kill "$pid" 2>/dev/null || true; done
xcodebuild test-without-building -workspace Runner.xcworkspace -scheme RunnerUITests -destination "id=$UDID" -derivedDataPath ../build/ios-dd \
  -resultBundlePath ../build/ui-result $ONLY 2>&1 | grep --line-buffered -E "error:|Test Case|\*\* TEST|Executed" | awk '{ print substr($0, 1, 900); fflush() }'
