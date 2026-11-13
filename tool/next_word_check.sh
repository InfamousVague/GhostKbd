#!/bin/sh
# Next-word prediction's self-check (tool/next_word_check/NextWordCheck.kt): the tokenizer against the fixtures the
# generator shares (tool/next_words_fixtures.tsv), the blend of starter tables and personal counts, fading and every
# bound, the learned file's round trip, every bundled table against its word list, context in autocorrect's
# suggestions and corrections, and how long a lookup takes on the real English table. Needs kotlinc and java; no
# emulator, no Gradle.
#
#   tool/next_word_check.sh
#
# The generator's side of the fixtures is `python3 tool/next_words.py --selftest`.
set -e
cd "$(dirname "$0")/.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
out="${TMPDIR:-/tmp}/ghost-kbd-next-word-check-$$.jar"
trap 'rm -f "$out"' EXIT
kotlinc "$src/Languages.kt" "$src/Layouts.kt" "$src/Hangul.kt" "$src/Autocorrect.kt" "$src/SwipeDecoder.kt" "$src/TapDecoder.kt" "$src/ThumbLean.kt" "$src/MistakeMemory.kt" "$src/SampleCleaner.kt" "$src/NextWordModel.kt" \
  tool/lang_check/stubs/android/graphics/PointF.kt tool/next_word_check/NextWordCheck.kt \
  -include-runtime -d "$out" 2>&1 | grep -v "^warning:" || true
[ -f "$out" ] || { echo "next_word_check: the keyboard's next-word code did not compile"; exit 1; }
java -jar "$out" .
