#!/bin/sh
# Puts a kept run's records where score.py and run.py read them: tool/versus/unpack.sh 2026-11-11
#
# tool/versus/runs/<name>/ holds what a run recorded (every tap as it was placed, the field after every key press,
# what was thrown away for a stall), gzipped, with its tables. build/ is where the tools work, and is not kept.
# After this:  tool/versus/score.py build/versus/runs/<name>            scores it again
#              tool/versus/versus.sh --keyboards swiftkey --name <name>  adds a keyboard to it
# Nothing already in build/versus/runs/<name> is written over.
set -e
cd "$(dirname "$0")/../.."
name="$1"
[ -n "$name" ] && [ -d "tool/versus/runs/$name" ] || { echo "usage: tool/versus/unpack.sh <name>   (one of: $(ls tool/versus/runs 2>/dev/null | tr '\n' ' '))" >&2; exit 2; }
out="build/versus/runs/$name"
mkdir -p "$out"
for f in "tool/versus/runs/$name"/*; do
  base=$(basename "$f")
  case "$base" in
    *.typed-twice.jsonl.gz) continue ;;
    *.gz) to="$out/${base%.gz}"; [ -e "$to" ] || gunzip -c "$f" > "$to" ;;
    *.meta.json) [ -e "$out/$base" ] || cp "$f" "$out/$base" ;;
  esac
done
echo "$out: $(ls "$out" | tr '\n' ' ')"
