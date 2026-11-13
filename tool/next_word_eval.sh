#!/bin/sh
# Next-word prediction measured on Tatoeba sentences held out of the counting (tool/next_word_eval/Eval.kt says how).
# Run `python3 tool/next_words.py <language>` first: it writes the held-out words to build/ngram-eval/.
#
#   tool/next_word_eval.sh                       # the bundled English table
#   tool/next_word_eval.sh old.tsv new.tsv       # tables side by side, on the same words
#   JAVA_OPTS="-Dlang=de -Devents=5000 -Dpriors=10,20,40" tool/next_word_eval.sh
set -e
cd "$(dirname "$0")/.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
out="${TMPDIR:-/tmp}/ghost-kbd-next-word-eval-$$.jar"
trap 'rm -f "$out"' EXIT
kotlinc "$src/Languages.kt" "$src/Layouts.kt" "$src/Hangul.kt" "$src/Autocorrect.kt" "$src/SwipeDecoder.kt" "$src/TapDecoder.kt" "$src/ThumbLean.kt" "$src/MistakeMemory.kt" "$src/SampleCleaner.kt" "$src/NextWordModel.kt" \
  tool/lang_check/stubs/android/graphics/PointF.kt tool/next_word_eval/Eval.kt \
  -include-runtime -d "$out" 2>&1 | grep -v "^warning:" || true
[ -f "$out" ] || { echo "next_word_eval: did not compile"; exit 1; }
java $JAVA_OPTS -jar "$out" . "$@"
