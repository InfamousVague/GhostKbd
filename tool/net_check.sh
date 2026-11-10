#!/bin/sh
# The Offline switch's self-check (tool/net_check/NetCheck.kt): the ledger's rule tried on real addresses, and the
# sources read for any connection that does not go through Net.kt. Needs kotlinc and java; no emulator, no Gradle.
#
#   tool/net_check.sh
set -e
cd "$(dirname "$0")/.."
out="${TMPDIR:-/tmp}/ghost-kbd-net-check-$$.jar"
trap 'rm -f "$out"' EXIT
kotlinc android/app/src/main/kotlin/com/infamousvague/devkeyboard/NetLedger.kt tool/net_check/NetCheck.kt -include-runtime -d "$out" 2>&1 | grep -v "^warning:" || true
[ -f "$out" ] || { echo "net_check: the ledger did not compile"; exit 1; }
java -jar "$out" .
