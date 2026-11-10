#!/bin/sh
# Swipe typing's self-check, off the device: compiles SwipeDecoder.kt (no Android in it) with its JUnit tests and runs
# them against the real English list (assets/dict/en.tsv). Needs kotlinc and a JUnit 4 jar, which Gradle has fetched
# once the app has been built (or set JUNIT_JAR and HAMCREST_JAR).
#
#   tool/swipe-selfcheck.sh
set -e
cd "$(dirname "$0")/.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
junit=${JUNIT_JAR:-$(find "$HOME/.gradle/caches" -name 'junit-4.13.2.jar' 2>/dev/null | head -1)}
hamcrest=${HAMCREST_JAR:-$(find "$HOME/.gradle/caches" -name 'hamcrest-core-1.3.jar' 2>/dev/null | head -1)}
[ -n "$junit" ] && [ -n "$hamcrest" ] || { echo "JUnit 4 not found: build the app once, or set JUNIT_JAR and HAMCREST_JAR" >&2; exit 2; }
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
kotlinc -nowarn -cp "$junit" "$src/SwipeDecoder.kt" android/app/src/test/kotlin/com/infamousvague/devkeyboard/SwipeDecoderTest.kt -d "$out/t.jar"
kotlin -cp "$out/t.jar:$junit:$hamcrest" org.junit.runner.JUnitCore com.infamousvague.devkeyboard.SwipeDecoderTest
