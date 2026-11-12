#!/bin/sh
# Ghost Keyboard against the other keyboards on the emulator, from nothing to the tables: docs/versus.md.
#
#   tool/versus/versus.sh                      # everything, into build/versus/runs/<today>
#   tool/versus/versus.sh --keyboards "gboard ghost swiftkey" --sentences 300 --name mine
#   tool/versus/versus.sh --score build/versus/runs/2026-11-11     # only score a run that is there
#   tool/versus/versus.sh --restore            # only put the emulator back as the last run found it
#
# What it does, in order: builds and installs the versus field; saves Ghost Keyboard's data and notes the keyboard
# in use (prepare.py snapshot); for each keyboard clears what it has learned, makes it the keyboard in use, measures
# where its keys are (calibrate.py) and types the sentences with each thumb (run.py); scores the lot (score.py);
# and puts the emulator back as it was (prepare.py restore), whether or not the run got to its end.
#
# Only ever an emulator ($VERSUS_SERIAL, default emulator-5584), and only when its launcher or the versus field is
# in front. A keyboard that is not installed is left out, and the tables say so; SwiftKey has to be put on the
# emulator from Google Play by hand, signed in to nothing. About eight seconds a sentence: 300 sentences, three
# thumbs and two keyboards are four hours, measuring the keys included. A run that was stopped goes on from where
# it was (same --name). Keep this Mac quiet meanwhile: when it is busy the emulator stalls in the middle of a tap,
# and though run.py notices and types the sentence again (timing.py), every stall costs a sentence's time.
set -e
cd "$(dirname "$0")/../.."
keyboards="gboard ghost swiftkey"
sentences=300
name=$(date +%Y-%m-%d)
thumbs="careful,leaning,sloppy"
only_score=""
only_restore=0
while [ $# -gt 0 ]; do
  case "$1" in
    --keyboards) keyboards="$2"; shift 2 ;;
    --sentences) sentences="$2"; shift 2 ;;
    --thumbs) thumbs="$2"; shift 2 ;;
    --name) name="$2"; shift 2 ;;
    --score) only_score="$2"; shift 2 ;;
    --restore) only_restore=1; shift ;;
    *) echo "versus: unknown option $1" >&2; exit 2 ;;
  esac
done
if [ -n "$only_score" ]; then exec python3 tool/versus/score.py "$only_score" --fixtures; fi
if [ "$only_restore" = 1 ]; then exec python3 tool/versus/prepare.py restore; fi

serial="${VERSUS_SERIAL:-emulator-5584}"
out="build/versus/runs/$name"
mkdir -p "$out"
# Room for the build: it is 20 KB, but a Mac with under a gigabyte free is not the place to start a three-hour run.
free=$(df -m / | tail -1 | awk '{print $4}')
[ "$free" -ge 1024 ] || { echo "versus: only $free MB free on this Mac; not starting" >&2; exit 1; }
sh tool/versus/field/build.sh install
[ -f build/versus/backup/state.json ] || python3 tool/versus/prepare.py snapshot
trap 'python3 tool/versus/prepare.py restore' EXIT
size=$(adb -s "$serial" shell wm size | awk '{print $NF}' | tr -d '\r')
dpi=$(adb -s "$serial" shell wm density | awk '{print $NF}' | tr -d '\r')
for kb in $keyboards; do
  package=$(python3 -c "import sys; sys.path.insert(0, 'tool/versus'); import device; print(device.KEYBOARDS['$kb']['package'])")
  if ! adb -s "$serial" shell pm list packages "$package" | grep -q "package:$package"; then
    echo "versus: $kb is not installed on $serial; left out"
    continue
  fi
  case "$kb" in
    ghost) python3 tool/versus/prepare.py clear ghost ;;
    gboard) python3 tool/versus/prepare.py clear gboard ;;
    *) echo "versus: delete what $kb has learned in its own settings before this (nothing here does it)" ;;
  esac
  hint=""
  if [ "$kb" = ghost ]; then
    sh tool/versus/replay.sh geometry "$(echo "$size" | cut -dx -f1)" "$(echo "$size" | cut -dx -f2)" "$dpi" > build/versus/ghost-hint.json
    hint="--hint build/versus/ghost-hint.json"
  fi
  python3 tool/versus/calibrate.py "$kb" $hint
  [ "$kb" = ghost ] && sh tool/versus/replay.sh probes "tool/versus/geometry/ghost-$size-${dpi}dpi.json"
  python3 tool/versus/run.py "$kb" --out "$out" --sentences "$sentences" --thumbs "$thumbs"
done
python3 tool/versus/score.py "$out" --fixtures
