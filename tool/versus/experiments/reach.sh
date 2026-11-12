#!/bin/sh
# An experiment, not a change: how far TapDecoder.search may go from the letters typed (docs/versus.md, "The search's
# reach"). Writes build/versus/experiments/reach/TapDecoder.kt, the tree's own file with two things read from the
# JVM's properties in place of fixed rules, for tool/versus/whatif.py --overlay to compile and try:
#
#   -Dversus.reach=N   N more letters of a word may be read as another key than `changes` allows now (1 in a word of
#                      three letters or fewer, 2 up to five, 3 up to eight, 4 beyond; 1 and 2 where the letters
#                      typed spell a common word). 99: as many as the cost allows.
#   -Dversus.free=C    a letter read as another key for less than C (a tap nearly as near the key meant as the key
#                      typed: SPATIAL a key width squared of extra distance is 1.5) is not counted against it at all.
#
#   tool/versus/experiments/reach.sh
#   tool/versus/whatif.py --geometry tool/versus/runs/2026-11-11/ghost.geometry.json \
#       --overlay build/versus/experiments/reach --java "-Dversus.reach=1 -Dversus.free=0.75"
#
# It stops if the lines it changes are no longer in TapDecoder.kt as they were on 2026-11-12.
set -e
cd "$(dirname "$0")/../../.."
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard/TapDecoder.kt
out=build/versus/experiments/reach
mkdir -p "$out"
python3 - "$src" "$out/TapDecoder.kt" <<'PY'
import sys
s = open(sys.argv[1], encoding="utf-8").read()
cap = "        changes = if (slipsOnly) (if (len <= 4) 1 else 2) else when { len <= 3 -> 1; len <= 5 -> 2; len <= 8 -> 3; else -> 4 }\n"
count = "if (cost + s <= budget && (same || (more && !(only && s >= far)))) walk(q, i + 1, cost + s, omits, inserts, c, budget, if (same) changed else changed + 1)"
if s.count(cap) != 1 or s.count(count) != 1:
    sys.exit("reach.sh: TapDecoder.search or walk is not as it was on 2026-11-12; read it and change this script")
s = s.replace(cap, cap.rstrip("\n") + "\n        changes += (System.getProperty(\"versus.reach\") ?: \"0\").toInt()\n        free = (System.getProperty(\"versus.free\") ?: \"0\").toFloat()\n")
s = s.replace(count, "if (cost + s <= budget && (same || s < free || (more && !(only && s >= far)))) walk(q, i + 1, cost + s, omits, inserts, c, budget, if (same || s < free) changed else changed + 1)")
s = s.replace("    private var changes = 4\n", "    private var changes = 4\n    private var free = 0f\n")
if "private var free = 0f" not in s:
    sys.exit("reach.sh: no `private var changes = 4` in TapDecoder.kt to put `free` beside")
open(sys.argv[2], "w", encoding="utf-8").write(s)
PY
echo "$out/TapDecoder.kt"
