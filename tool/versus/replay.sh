#!/bin/sh
# tool/versus/replay/Replay.kt: Ghost Keyboard's own decoder on the JVM, fed taps at screen pixels.
# Needs kotlinc and java; no emulator, no Gradle. The jar is kept in build/versus/ and rebuilt when a source is newer.
#
#   tool/versus/replay.sh geometry 1248 1972 420          # the keys' cells, from the keyboard's layout code
#   tool/versus/replay.sh probes tool/versus/geometry/ghost-1248x1972-420dpi.json
#   tool/versus/replay.sh replay 1248 1972 420 < taps.tsv # what the keyboard would leave in the field
set -e
cd "$(dirname "$0")/../.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
jar=build/versus/replay.jar
sources="$src/Languages.kt $src/Layouts.kt $src/Hangul.kt $src/Autocorrect.kt $src/SwipeDecoder.kt $src/TapDecoder.kt $src/MistakeMemory.kt $src/SampleCleaner.kt $src/NextWordModel.kt tool/lang_check/stubs/android/graphics/PointF.kt tool/versus/replay/Replay.kt"
stale=0
[ -f "$jar" ] || stale=1
for f in $sources; do [ "$f" -nt "$jar" ] && stale=1; done
if [ "$stale" = 1 ]; then
  mkdir -p build/versus
  rm -f "$jar"
  kotlinc $sources -include-runtime -d "$jar" 2>&1 | grep -v "^warning:" >&2 || true
  [ -f "$jar" ] || { echo "versus replay: did not compile" >&2; exit 1; }
fi
exec java -Xmx2g $JAVA_OPTS -jar "$jar" . "$@"
