#!/bin/sh
# A change to the keyboard, held to the emulator: tool/versus/device-check.sh <debug apk of this tree> <base commit> [name]
#
# The JVM replay says what a change to autocorrect does; this says whether a phone agrees. It installs the build
# over Ghost Keyboard on the emulator, types the sentences this tree reads otherwise than <base commit> did (all of
# them, shortest first) and forty it reads the same, and holds the emulator's text against the JVM's for this tree
# and for the base, sentence by sentence. Then it puts the emulator back as it found it (prepare.py restore: the
# build that was installed and its data, the keyboard in use, the launcher in front), whatever happened.
#
#   flutter build apk --debug
#   tool/versus/device-check.sh build/app/outputs/flutter-apk/app-debug.apk e10ab2e
#
# It waits, up to half an hour, for the emulator to keep time before it touches anything (timing.py --calm): on a
# busy Mac taps are held for seconds, and run.py throws those sentences away. VERSUS_MEASURE=1 measures the build's
# keys by tapping (ten minutes, on a calm emulator); without it the letters are taken as the run of 2026-11-11
# measured them and the rest from the layout code (calibrate.py --no-taps), and the typed sentences bear that out.
# Only ever an emulator ($VERSUS_SERIAL, default emulator-5584), and only when its launcher is in front. Say in the
# shared notes that you have it before you start, and that it is free when this ends.
set -e
cd "$(dirname "$0")/../.."
apk="$1"
base="$2"
name="${3:-device-check-$(date +%Y%m%d-%H%M)}"
[ -f "$apk" ] && [ -n "$base" ] || { echo "usage: tool/versus/device-check.sh <debug apk of this tree> <base commit> [name]" >&2; exit 2; }
serial="${VERSUS_SERIAL:-emulator-5584}"
free=$(df -m / | tail -1 | awk '{print $4}')
[ "$free" -ge 2048 ] || { echo "device-check: only $free MB free on this Mac; not starting" >&2; exit 1; }
out="build/versus/runs/$name"
mkdir -p "$out"

# The decoder, and the replay's model of the service, as they were at the base: the JVM types as that tree did.
old="build/versus/base-$base"
mkdir -p "$old"
src=android/app/src/main/kotlin/com/infamousvague/devkeyboard
for f in $src/Languages.kt $src/Layouts.kt $src/Hangul.kt $src/Autocorrect.kt $src/SwipeDecoder.kt $src/TapDecoder.kt $src/MistakeMemory.kt $src/SampleCleaner.kt $src/NextWordModel.kt tool/versus/replay/Replay.kt; do
  git show "$base:$f" > "$old/$(basename "$f")"
done
# A base from before there was a ThumbLean.kt gets an empty one: the overlay must not take this tree's, which is
# written for this tree's Taps.
git show "$base:$src/ThumbLean.kt" > "$old/ThumbLean.kt" 2>/dev/null || : > "$old/ThumbLean.kt"

# Nothing is touched until the emulator keeps time and its launcher is in front.
n=0
until python3 tool/versus/timing.py --calm; do
  n=$((n + 1))
  [ "$n" -lt 90 ] || { echo "device-check: the emulator has not kept time for half an hour; nothing was touched" >&2; exit 3; }
  sleep 20
done
python3 -c "
import sys
sys.path.insert(0, 'tool/versus')
import device
front = device.front()
sys.exit(0 if 'launcher' in front.lower() else 'device-check: %s is in front on %s; not touching it' % (front, device.SERIAL))"

[ -f build/versus/backup/state.json ] || python3 tool/versus/prepare.py snapshot
trap 'python3 tool/versus/prepare.py restore' EXIT
sh tool/versus/field/build.sh install
df -m / | tail -1
adb -s "$serial" install -r "$apk" | tail -1
python3 tool/versus/prepare.py clear ghost

# Where this build's keys are.
size=$(adb -s "$serial" shell wm size | awk '{print $NF}' | tr -d '\r')
dpi=$(adb -s "$serial" shell wm density | awk '{print $NF}' | tr -d '\r')
sh tool/versus/replay.sh geometry "$(echo "$size" | cut -dx -f1)" "$(echo "$size" | cut -dx -f2)" "$dpi" > build/versus/ghost-hint.json
geo="$out/ghost.geometry.json"
if [ "${VERSUS_MEASURE:-0}" = 1 ]; then
  python3 tool/versus/calibrate.py ghost --hint build/versus/ghost-hint.json --out "$geo"
  sh tool/versus/replay.sh probes "$geo"
else
  python3 tool/versus/calibrate.py ghost --hint build/versus/ghost-hint.json --no-taps tool/versus/runs/2026-11-11/ghost.geometry.json --out "$geo"
fi

# What the change alters on these keys (this tree against the base), and forty typings it leaves alone.
python3 tool/versus/whatif.py --geometry "$geo" --overlay "$old" --changed "$out/altered.txt" | grep "typings come out otherwise"
python3 - "$out" <<'PY'
import os, random, sys
sys.path.insert(0, "tool/versus")
import thumb as th
out = sys.argv[1]
altered = [tuple(line.split()) for line in open(os.path.join(out, "altered.txt"))]
sents, _ = th.sentences(300)
text = {str(s.line_no): s.text for s in sents}
altered.sort(key=lambda k: len(text[k[1]]))
rest = [(t, str(s.line_no)) for t in ("careful", "leaning", "sloppy") for s in sents if (t, str(s.line_no)) not in set(altered)]
random.Random(20261112).shuffle(rest)
# The altered ones first and the shortest of them first: a short sentence gets through a busy emulator soonest.
with open(os.path.join(out, "only.txt"), "w") as f:
    f.write("".join("%s %s\n" % k for k in altered + sorted(rest[:40], key=lambda k: len(text[k[1]]))))
print("%d altered typings and 40 others to type" % len(altered))
PY

python3 tool/versus/run.py ghost --geometry "$geo" --out "$out" --sentences 300 --only "$out/only.txt"

# The emulator's text against the JVM's, for this tree and for the base.
python3 - "$out" "$old" <<'PY'
import json, os, sys
sys.path.insert(0, "tool/versus")
import score
out, old = sys.argv[1], sys.argv[2]
records = {}
for line in open(os.path.join(out, "ghost.jsonl")):
    r = json.loads(line)
    if not r.get("stalled"):
        records[(r["thumb"], r["line"])] = r
records = list(records.values())
altered = {tuple(line.split()) for line in open(os.path.join(out, "altered.txt"))}
keys = score.board_file(os.path.join(out, "ghost.geometry.json"))
tree = score.replay(records, (1248, 1972, 420), board=keys)
base = score.replay(records, (1248, 1972, 420), board=keys, overlay=old)
mine = [r for r in records if (r["thumb"], str(r["line"])) in altered]
rest = [r for r in records if (r["thumb"], str(r["line"])) not in altered]
def same(rs, texts):
    return sum(1 for r in rs if r["out"] == texts[(r["thumb"], r["line"])][0])
print("typed: %d of the altered typings, %d others" % (len(mine), len(rest)))
print("  altered: the emulator's text is this tree's in %d of %d, and the base's in %d" % (same(mine, tree), len(mine), same(mine, base)))
print("  others:  the emulator's text is this tree's in %d of %d" % (same(rest, tree), len(rest)))
for r in records:
    if r["out"] != tree[(r["thumb"], r["line"])][0]:
        print("  NOT THE TREE'S  %s %d: %s\n      emulator %s\n      tree     %s" % (r["thumb"], r["line"], r["target"], r["out"], tree[(r["thumb"], r["line"])][0]))
PY
