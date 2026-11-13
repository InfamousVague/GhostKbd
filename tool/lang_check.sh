#!/bin/sh
# The languages' self-check (tool/lang_check/LangCheck.kt): every layout, each script's plain spelling, the Korean
# composer, the language switch, every word list against its keyboard, and autocorrect against what it said before
# there were layouts (tool/lang_check/golden). Needs kotlinc and java; no emulator, no Gradle.
#
#   tool/lang_check.sh
#   JAVA_OPTS=-Drecord=swipes tool/lang_check.sh   # write the swipe readings as they are now (look at the count it prints)
#
# The word lists themselves are checked against wordfreq by `tool/dictionaries.py --check`.
set -e
cd "$(dirname "$0")/.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
out="${TMPDIR:-/tmp}/ghost-kbd-lang-check-$$.jar"
trap 'rm -f "$out"' EXIT
kotlinc "$src/Languages.kt" "$src/Layouts.kt" "$src/Hangul.kt" "$src/Autocorrect.kt" "$src/SwipeDecoder.kt" "$src/TapDecoder.kt" "$src/ThumbLean.kt" "$src/MistakeMemory.kt" "$src/SampleCleaner.kt" "$src/NextWordModel.kt" \
  tool/lang_check/stubs/android/graphics/PointF.kt tool/lang_check/Golden.kt tool/lang_check/LangCheck.kt \
  -include-runtime -d "$out" 2>&1 | grep -v "^warning:" || true
[ -f "$out" ] || { echo "lang_check: the keyboard's language code did not compile"; exit 1; }
java $JAVA_OPTS -jar "$out" .
