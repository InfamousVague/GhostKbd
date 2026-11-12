#!/bin/sh
# The sets nothing was made from, given to another keyboard: tool/versus/other-sets.sh [keyboard] [name]
#
# scoreboard.py types three sets of sentences through Ghost Keyboard's decoder on the JVM: the 900 typings of the
# run of 2026-11-11 ("seen"), 900 typings of 300 other held-out sentences ("unseen", from line 100,001 of the
# held-out file on) and 900 more kept for the end ("final", from line 110,001 on). Only the first was ever typed
# into Gboard, and the changes to autocorrect were made from the words lost there. This types the other two into
# the keyboard on the emulator, with the same thumbs and the same seed, so that "as good as Gboard" can be said of
# sentences no change was made from. scoreboard.py reads what it leaves (build/versus/runs/<name>/unseen and
# /final) and prints the keyboard's line beside its own.
#
#   tool/versus/other-sets.sh                 # gboard, into build/versus/runs/other-sets
#   VERSUS_SETS="unseen" tool/versus/other-sets.sh gboard mine
#
# The keys are the ones the run of 2026-11-11 measured (tool/versus/runs/2026-11-11/<keyboard>.geometry.json);
# run.py refuses if the keyboard's build has changed since. The keyboard's learned words are deleted first, as
# versus.sh does. About eight seconds a sentence when the Mac is quiet: four hours for both sets. A run that was
# stopped goes on from where it was. It waits for the emulator to keep time before it touches anything, takes a
# snapshot if there is none, and puts the emulator back at the end whatever happened (prepare.py restore).
# Only ever an emulator ($VERSUS_SERIAL, default emulator-5584), and only when its launcher is in front. Say in
# the shared notes that you have it before you start, and that it is free when this ends.
set -e
cd "$(dirname "$0")/../.."
kb="${1:-gboard}"
name="${2:-other-sets}"
sets="${VERSUS_SETS:-unseen final}"
geo="tool/versus/runs/2026-11-11/$kb.geometry.json"
[ -f "$geo" ] || { echo "other-sets: no $geo (the keys $kb was measured with)" >&2; exit 2; }
free=$(df -m / | tail -1 | awk '{print $4}')
[ "$free" -ge 1024 ] || { echo "other-sets: only $free MB free on this Mac; not starting" >&2; exit 1; }

n=0
until python3 tool/versus/timing.py --calm; do
  n=$((n + 1))
  [ "$n" -lt 90 ] || { echo "other-sets: the emulator has not kept time for half an hour; nothing was touched" >&2; exit 3; }
  sleep 20
done
python3 -c "
import sys
sys.path.insert(0, 'tool/versus')
import device
front = device.front()
sys.exit(0 if 'launcher' in front.lower() or front.startswith(device.FIELD) else 'other-sets: %s is in front on %s; not touching it' % (front, device.SERIAL))"

[ -f build/versus/backup/state.json ] || python3 tool/versus/prepare.py snapshot
trap 'python3 tool/versus/prepare.py restore' EXIT
sh tool/versus/field/build.sh install
case "$kb" in
  ghost) python3 tool/versus/prepare.py clear ghost ;;
  gboard) python3 tool/versus/prepare.py clear gboard ;;
  *) echo "other-sets: delete what $kb has learned in its own settings before this (nothing here does it)" ;;
esac
for set in $sets; do
  case "$set" in
    unseen) first=100000 ;;
    final) first=110000 ;;
    *) echo "other-sets: no set called $set (unseen, final)" >&2; exit 2 ;;
  esac
  python3 tool/versus/run.py "$kb" --geometry "$geo" --out "build/versus/runs/$name/$set" --sentences 300 --first "$first"
done
