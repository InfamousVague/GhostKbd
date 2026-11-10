#!/bin/sh
# The Undo and redo history's self-check, off the device: compiles EditHistory.kt and FieldEditor.kt (no Android in
# either) with their JUnit tests and runs them. Needs kotlinc and a JUnit 4 jar, which Gradle has fetched once the
# app has been built (or set JUNIT_JAR and HAMCREST_JAR).
#
#   tool/undo-selfcheck.sh
#
# The same tests run under Gradle: cd android && ./gradlew :app:testDebugUnitTest --tests '*FieldEditorTest*'
set -e
cd "$(dirname "$0")/.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
junit=${JUNIT_JAR:-$(find "$HOME/.gradle/caches" -name 'junit-4.13.2.jar' 2>/dev/null | head -1)}
hamcrest=${HAMCREST_JAR:-$(find "$HOME/.gradle/caches" -name 'hamcrest-core-1.3.jar' 2>/dev/null | head -1)}
[ -n "$junit" ] && [ -n "$hamcrest" ] || { echo "JUnit 4 not found: build the app once, or set JUNIT_JAR and HAMCREST_JAR" >&2; exit 2; }
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
kotlinc -nowarn -cp "$junit" "$src/EditHistory.kt" "$src/FieldEditor.kt" android/app/src/test/kotlin/com/infamousvague/devkeyboard/FieldEditorTest.kt -d "$out/t.jar"
kotlin -cp "$out/t.jar:$junit:$hamcrest" org.junit.runner.JUnitCore com.infamousvague.devkeyboard.FieldEditorTest
