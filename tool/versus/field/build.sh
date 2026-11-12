#!/bin/sh
# Builds the versus field (tool/versus/field/Main.java) into build/versus/versus-field.apk with the Android SDK's
# own tools: no Gradle, no Flutter, a second or two, about 20 KB. Signed with this Mac's debug key
# (~/.android/debug.keystore, whose password is Android's published default), so it only ever installs as a debug app.
#
#   tool/versus/field/build.sh            # build
#   tool/versus/field/build.sh install    # build and install on $VERSUS_SERIAL (default emulator-5584)
set -e
cd "$(dirname "$0")/../../.."
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
plat=$(ls -d "$sdk"/platforms/android-* | sort -V | tail -1)/android.jar
[ -x "$bt/aapt2" ] && [ -f "$plat" ] || { echo "versus field: no build-tools or platform under $sdk" >&2; exit 2; }
out=build/versus/field
mkdir -p "$out/classes" "$out/dex"
javac -nowarn -source 11 -target 11 -classpath "$plat" -d "$out/classes" tool/versus/field/Main.java 2>&1 | grep -v "^warning:\|^Note:\|^1 warning" || true
"$bt/d8" --lib "$plat" --min-api 26 --output "$out/dex" $(find "$out/classes" -name '*.class')
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$plat" --manifest tool/versus/field/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 --version-code 1 --version-name 1
(cd "$out/dex" && zip -q -j ../unsigned.apk classes.dex)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$bt/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out build/versus/versus-field.apk "$out/aligned.apk"
ls -l build/versus/versus-field.apk
if [ "$1" = "install" ]; then
  df -m / | tail -1
  adb -s "${VERSUS_SERIAL:-emulator-5584}" install -r build/versus/versus-field.apk
fi
