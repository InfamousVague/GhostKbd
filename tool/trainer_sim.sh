#!/bin/sh
# The typing trainer's simulated thumb (tool/trainer_sim/Main.kt): a thumb that always lands off the keys' middles
# the same way types the trainer's stories through the app's own autocorrect, scoring and fixes, on the trainer's
# fixes and on the keyboard as it is; and the trainer's test of those fixes, and its layout trials, tried on many
# simulated people to see how often each says "proven". Needs kotlinc and java; no emulator, no Gradle.
#
#   tool/trainer_sim.sh
#   JAVA_OPTS="-Druns=16 -Dseeds=40 -Dthumbs=1000 -Dlean=0.3,0.1" tool/trainer_sim.sh
set -e
cd "$(dirname "$0")/.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
out="${TMPDIR:-/tmp}/ghost-kbd-trainer-sim-$$.jar"
trap 'rm -f "$out"' EXIT
kotlinc "$src/Languages.kt" "$src/Layouts.kt" "$src/Hangul.kt" "$src/Autocorrect.kt" "$src/SwipeDecoder.kt" "$src/TapDecoder.kt" "$src/MistakeMemory.kt" "$src/SampleCleaner.kt" "$src/NextWordModel.kt" \
  "$src/TrainerText.kt" "$src/TrainerRun.kt" "$src/TrainerAims.kt" "$src/TrainerFix.kt" "$src/TrainerBook.kt" "$src/TrainerStories.kt" "$src/LayoutTrials.kt" \
  tool/lang_check/stubs/android/graphics/PointF.kt android/app/src/test/kotlin/com/infamousvague/devkeyboard/TrainerSim.kt tool/trainer_sim/Main.kt \
  -include-runtime -d "$out" 2>&1 | grep -v "^warning:" || true
[ -f "$out" ] || { echo "trainer_sim: did not compile"; exit 1; }
java -Xmx2g $JAVA_OPTS -jar "$out" .
