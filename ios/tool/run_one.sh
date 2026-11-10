#!/bin/sh
# ui_test.sh with a time limit: xcodebuild sometimes stays up after a failed UI test. run_one.sh <udid> <seconds> <tests...>
UDID="$1"; LIMIT="$2"; shift; shift
"$(dirname "$0")/ui_test.sh" "$UDID" "$@" &
RUN=$!
( sleep "$LIMIT"; for p in $(pgrep -f "xcodebuild test-without-building.*$UDID"); do kill "$p"; done ) &
WATCH=$!
wait $RUN
kill $WATCH 2>/dev/null
