#!/bin/sh
# What Scorer.Here makes of sentences it never saw, beside the words' own frequencies: tool/versus/likelihood.sh
# [--from N] [--to M] [--set Scorer.NAME=VALUE ...]   (tool/versus/likelihood/Likelihood.kt says what it prints)
#
# Held-out lines N+1 to M of build/typing-bench/en-heldout.txt, M at most 90,000: the lines after that are the
# ones tool/versus types. Needs kotlinc and java; no emulator, no Gradle.
set -e
cd "$(dirname "$0")/../.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
jar=build/versus/likelihood.jar
sources="$src/Languages.kt $src/Layouts.kt $src/Hangul.kt $src/Autocorrect.kt $src/SwipeDecoder.kt $src/TapDecoder.kt $src/ThumbLean.kt $src/MistakeMemory.kt $src/SampleCleaner.kt $src/NextWordModel.kt tool/lang_check/stubs/android/graphics/PointF.kt tool/versus/likelihood/Likelihood.kt"
stale=0
[ -f "$jar" ] || stale=1
for f in $sources; do [ "$f" -nt "$jar" ] && stale=1; done
if [ "$stale" = 1 ]; then
  mkdir -p build/versus
  rm -f "$jar"
  kotlinc $sources -include-runtime -d "$jar" 2>&1 | grep -v "^warning:" >&2 || true
  [ -f "$jar" ] || { echo "versus likelihood: did not compile" >&2; exit 1; }
fi
exec java -Xmx2g $JAVA_OPTS -jar "$jar" . "$@"
