#!/bin/sh
# Mockingbird's sample cleaner and its spelling instructions, checked on this computer: no phone, no emulator.
#
# Compiles the app's own SampleCleaner.kt, VoiceRules.kt and Autocorrect.kt with kotlinc, beside three stand-ins for
# the Android classes Autocorrect names (tool/sample_cleaner_check/stubs), and runs tool/sample_cleaner_check/Check.kt
# against the app's dictionaries (android/app/src/main/assets/dict).
#
#   tool/check_sample_cleaner.sh            the checks; exits 1 if one fails
#   tool/check_sample_cleaner.sh --rules    the same, and prints the instruction texts the AIs are sent
#
# Needs kotlinc (brew install kotlin) and java.
set -e
cd "$(dirname "$0")/.."
KOTLINC="$(command -v kotlinc || echo /opt/homebrew/bin/kotlinc)"
SRC=android/app/src/main/kotlin/com/infamousvague/devkeyboard
OUT="${TMPDIR:-/tmp}/ghost-kbd-sample-cleaner-check"
mkdir -p "$OUT"
"$KOTLINC" -nowarn "$SRC/Autocorrect.kt" "$SRC/SampleCleaner.kt" "$SRC/VoiceRules.kt" \
  tool/sample_cleaner_check/stubs/*.kt tool/sample_cleaner_check/Check.kt \
  -include-runtime -d "$OUT/check.jar"
java -jar "$OUT/check.jar" android/app/src/main/assets "$@"
