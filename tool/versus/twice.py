#!/usr/bin/env python3
"""Does a keyboard type the same thing twice? tool/versus/twice.py <records> <records typed again>

Each argument is a <keyboard>.jsonl of run.py's, plain or gzipped. For every thumb and sentence in both, the taps
must be the very same (else they are not the same typing, and it says so), and the text the keyboard left is held
against the first time's. Ghost Keyboard needs no such check, its decoder being replayed on the JVM for every
sentence; another keyboard has only this.

    tool/versus/run.py gboard --out build/versus/runs/again --sentences 20
    tool/versus/twice.py build/versus/runs/2026-11-11/gboard.jsonl build/versus/runs/again/gboard.jsonl
"""
import gzip
import json
import sys


def records(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as f:
        return {(r["thumb"], r["line"]): r for r in (json.loads(line) for line in f) if not r.get("stalled")}


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    first, again = records(sys.argv[1]), records(sys.argv[2])
    both = sorted(set(first) & set(again))
    other_taps = [k for k in both if [(t["x"], t["y"]) for t in first[k]["touches"]] != [(t["x"], t["y"]) for t in again[k]["touches"]]]
    same = [k for k in both if k not in other_taps and first[k]["out"] == again[k]["out"]]
    print("%d sentences typed twice by the same taps: the same text %d times" % (len(both) - len(other_taps), len(same)))
    if other_taps:
        print("%d more were not the same taps (another geometry, seed or aim), and are left out" % len(other_taps))
    for k in both:
        if k not in other_taps and k not in same:
            print("  %s %d: %s\n      first %s\n      again %s" % (k[0], k[1], first[k]["target"], first[k]["out"], again[k]["out"]))


if __name__ == "__main__":
    main()
