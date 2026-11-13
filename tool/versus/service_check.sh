#!/bin/sh
# Does the Android side compile? tool/versus/service_check.sh, without Gradle, in a minute or so.
#
# unit.sh, lang_check and the scoreboard build the plain-Kotlin decoder alone, so a break in DevKeyboardService or
# KeyboardView (two `val after` in one function, 2026-11-13) passed them all. This compiles every file of the app's
# main source set but MainActivity (its FlutterActivity needs androidx.lifecycle's whole tree; a stub stands in for it) against the SDK's
# android.jar and the jars Gradle has already put in ~/.gradle/caches, with a stub BuildConfig. It runs nothing and
# builds no app: an error here is an error Gradle's compileDebugKotlin would give, but passing here is not a build.
set -e
cd "$(dirname "$0")/../.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
android_jar=$(ls "$sdk"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
gc="$HOME/.gradle/caches"
flutter=$(find "$gc/modules-2/files-2.1/io.flutter" -name 'flutter_embedding_debug-*.jar' 2>/dev/null | head -1)
annotation=$(find "$gc/modules-2/files-2.1/androidx.annotation/annotation-jvm" -name 'annotation-jvm-*.jar' 2>/dev/null | sort -V | tail -1)
autofill=$(find "$gc" -path '*transformed/autofill-1.1.0/jars/classes.jar' 2>/dev/null | head -1)
for j in "$android_jar" "$flutter" "$annotation" "$autofill"; do
  [ -n "$j" ] || { echo "service_check: a jar is missing (android.jar, flutter_embedding, androidx annotation or autofill); run the Gradle build once" >&2; exit 2; }
done
out=build/versus/service-check
mkdir -p "$out"
cat > "$out/BuildConfig.kt" <<'EOF'
package com.infamousvague.devkeyboard
object BuildConfig { const val DEBUG = true; const val GIPHY_KEY = "" }
class MainActivity : android.app.Activity() { companion object { const val EXTRA_OPEN = "open" } }
EOF
files=$(ls "$src"/*.kt | grep -v '/MainActivity\.kt$')
log="$out/kotlinc.log"
JAVA_OPTS="-Xmx3g" kotlinc $files "$out/BuildConfig.kt" -cp "$android_jar:$flutter:$annotation:$autofill" -d "$out/classes" > "$log" 2>&1 || true
errors=$(grep -E '(^|: )error:' "$log" || true)
if [ -n "$errors" ]; then echo "$errors" >&2; echo "service_check: did not compile" >&2; exit 1; fi
echo "service_check: $(echo "$files" | wc -l | tr -d ' ') files compiled"
