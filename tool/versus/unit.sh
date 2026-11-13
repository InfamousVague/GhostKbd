#!/bin/sh
# The decoder's own unit tests in a minute, without Gradle: tool/versus/unit.sh [TestClass ...]
#
# Compiles the plain-Kotlin part of the keyboard (the same sources tool/typing_bench.sh takes) with the unit tests
# that need nothing else (TypingAccuracyTest, LetterModelTest, PlaceWorthTest, ThumbLeanTest, MistakeMemoryTest,
# SwipeDecoderTest, LayoutsTest, TapKeysSourceTest, WideWordsTest, AgainTest, AgainSourceTest, NamesSourceTest) and runs
# them with JUnit from Gradle's own cache; then, with no test named, tool/versus/again_check.py (the second look typed
# through the replay). For working on autocorrect: `./gradlew :app:testDebugUnitTest` is still what says the whole
# app's tests pass, and takes ten minutes on a busy Mac.
set -e
cd "$(dirname "$0")/../.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
tst=android/app/src/test/kotlin/com/infamousvague/devkeyboard
junit=$(find "$HOME/.gradle/caches/modules-2/files-2.1/junit/junit" -name 'junit-4.13.2.jar' 2>/dev/null | head -1)
hamcrest=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core" -name 'hamcrest-core-1.3.jar' 2>/dev/null | head -1)
[ -n "$junit" ] && [ -n "$hamcrest" ] || { echo "unit: no junit 4.13.2 in ~/.gradle/caches (run the Gradle tests once)" >&2; exit 2; }
tests="${*:-TypingAccuracyTest LetterModelTest PlaceWorthTest ThumbLeanTest MistakeMemoryTest SwipeDecoderTest LayoutsTest TapKeysSourceTest WideWordsTest AgainTest AgainSourceTest NamesSourceTest}"
files=""
for t in $tests; do files="$files $tst/$t.kt"; done
jar=build/versus/unit.jar
mkdir -p build/versus
rm -f "$jar"
kotlinc "$src/Languages.kt" "$src/Layouts.kt" "$src/Hangul.kt" "$src/Autocorrect.kt" "$src/SwipeDecoder.kt" "$src/TapDecoder.kt" "$src/ThumbLean.kt" "$src/MistakeMemory.kt" "$src/SampleCleaner.kt" "$src/NextWordModel.kt" \
  tool/lang_check/stubs/android/graphics/PointF.kt $files -cp "$junit:$hamcrest" -include-runtime -d "$jar" 2>&1 | grep -v "warning:" | grep -E "error:|^e: " >&2 || true
[ -f "$jar" ] || { echo "unit: did not compile" >&2; exit 1; }
classes=""
for t in $tests; do classes="$classes com.infamousvague.devkeyboard.$t"; done
java -Xmx2g -cp "$jar:$junit:$hamcrest" org.junit.runner.JUnitCore $classes
# With no tests named: the second look end to end, through the replay's Typist (tool/versus/again_check.py).
[ -n "$*" ] || python3 tool/versus/again_check.py
