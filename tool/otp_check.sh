#!/bin/sh
# The one-time code finder (OtpCodes.kt) against its table of text messages (OtpCodesTable.kt), in a second and
# with no Gradle: needs kotlinc and java. With arguments, each is a message and what the finder makes of it.
#
#   tool/otp_check.sh
#   tool/otp_check.sh "Your code is 482913" "Call 555 0142"
set -e
cd "$(dirname "$0")/.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
out="${TMPDIR:-/tmp}/ghost-kbd-otp-check-$$.jar"
trap 'rm -f "$out"' EXIT
kotlinc "$src/OtpCodes.kt" android/app/src/test/kotlin/com/infamousvague/devkeyboard/OtpCodesTable.kt tool/otp_check/Main.kt \
  -include-runtime -d "$out" 2>&1 | grep -v "^warning:" || true
[ -f "$out" ] || { echo "otp_check: did not compile"; exit 1; }
java -jar "$out" "$@"
