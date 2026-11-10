#!/bin/sh
# Scores autocorrect and swipe typing, as they are in this tree, against a record of real typing (Settings › Typing
# stats › Accuracy record › Save to Downloads, then copy the file to this Mac, or pull it straight from the phone):
#
#   tool/accuracy-replay.sh ~/Downloads/ghost-kbd-accuracy-2026-11-10.json
#   tool/accuracy-replay.sh --pull            # newest record in the phone's Downloads, over adb
#
# Run it before and after a change to autocorrect or the swipe decoder: the replay numbers are the ones to compare.
# The report is printed and kept in android/app/build/accuracy-report.txt. The record never leaves this Mac.
set -e
cd "$(dirname "$0")/.."
if [ "$1" = "--pull" ]; then
  name=$(adb shell 'ls -t /sdcard/Download/ghost-kbd-accuracy-*.json* 2>/dev/null' | head -1 | tr -d '\r')
  [ -n "$name" ] || { echo "No ghost-kbd-accuracy-*.json in the phone's Downloads" >&2; exit 2; }
  mkdir -p build/accuracy
  adb pull "$name" build/accuracy/ >/dev/null
  set -- "build/accuracy/$(basename "$name")"
fi
[ -n "$1" ] && [ -f "$1" ] || { echo "usage: tool/accuracy-replay.sh <record.json> | --pull" >&2; exit 2; }
log=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
cd android
./gradlew -q testDebugUnitTest --tests 'com.infamousvague.devkeyboard.AccuracyReplay' -PaccuracyLog="$log" >/dev/null
cat app/build/accuracy-report.txt
