#!/bin/sh
# The typing benchmark (tool/typing_bench/Bench.kt): autocorrect, swipe typing and next-word prediction scored on
# held-out sentences typed by a simulated thumb, for comparing one version of the keyboard with another.
# Needs kotlinc and java; no emulator, no Gradle.
#
#   tool/typing_bench.sh              # everything
#   tool/typing_bench.sh taps         # tap typing and autocorrect only (also: swipe, row, next)
#   tool/typing_bench.sh mistakes     # what learning from a person's corrections does for their habits
#   tool/typing_bench.sh voice        # next words with a writer's own earlier sentences (Mockingbird's samples)
#   JAVA_OPTS="-Dwords=60000 -Dverbose=true" tool/typing_bench.sh taps
#   tool/typing_bench.sh versus       # the words lost to another keyboard on the emulator, typed again by their
#                                     # own taps (tool/versus/fixtures.py, docs/versus.md); takes its options
#
# The sentences are Tatoeba's held-out English ones (every 20th of the export, never counted for the starter
# tables), read from build/typing-bench/en-heldout.txt. `python3 tool/next_words.py en` fetches the export into
# build/ngram-src/; this script cuts the held-out sentences from it the first time.
set -e
cd "$(dirname "$0")/.."
if [ "$1" = versus ]; then shift; exec python3 tool/versus/fixtures.py "$@"; fi
held=build/typing-bench/en-heldout.txt
if [ ! -f "$held" ]; then
  export_file=build/ngram-src/eng_sentences_detailed.tsv.bz2
  [ -f "$export_file" ] || { echo "typing_bench: no $held and no $export_file (run: python3 tool/next_words.py en)"; exit 2; }
  mkdir -p build/typing-bench
  bzcat "$export_file" | awk -F'\t' 'NF >= 4 { n++; if (n % 20 == 0) print $3 }' > "$held"
fi
# The same held-out sentences with who wrote each, for the contributors with enough of them to stand for a person.
authors=build/typing-bench/en-authors.tsv
if [ ! -f "$authors" ] && [ -f build/ngram-src/eng_sentences_detailed.tsv.bz2 ]; then
  bzcat build/ngram-src/eng_sentences_detailed.tsv.bz2 | awk -F'\t' 'NF >= 4 { n++; if (n % 20 == 0 && $4 != "\\N") { c[$4]++; line[n] = $4 "\t" $3 } } END { for (i = 20; i <= n; i += 20) { split(line[i], f, "\t"); if (c[f[1]] >= 2500) print line[i] } }' > "$authors"
fi
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
out="${TMPDIR:-/tmp}/ghost-kbd-typing-bench-$$.jar"
trap 'rm -f "$out"' EXIT
kotlinc "$src/Languages.kt" "$src/Layouts.kt" "$src/Hangul.kt" "$src/Autocorrect.kt" "$src/SwipeDecoder.kt" "$src/TapDecoder.kt" "$src/MistakeMemory.kt" "$src/SampleCleaner.kt" "$src/NextWordModel.kt" \
  tool/lang_check/stubs/android/graphics/PointF.kt tool/typing_bench/Bench.kt \
  -include-runtime -d "$out" 2>&1 | grep -v "^warning:" || true
[ -f "$out" ] || { echo "typing_bench: did not compile"; exit 1; }
java -Xmx2g $JAVA_OPTS -jar "$out" . "$@"
