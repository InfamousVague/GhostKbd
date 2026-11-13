#!/usr/bin/env python3
"""Why a word was left or changed: tool/versus/why.py [--set seen|unseen] [--lost] [--typed RE] [--meant RE] [--top N]

For every word of the 2026-11-11 run that the decoder in this tree leaves wrong (the "seen" set of
tool/versus/scoreboard.py; --lost keeps those Gboard got right from the same taps), prints what Autocorrect.byTaps
weighed: the typed word, what it is worth as it stands and what a reading needs to take its place; then each
reading with its frequency, what it is worth at that place (and whether a row of the starter tables lists it
there), what its slips cost and what that leaves, the best first. The word meant is always there, with what its
slips cost by the taps, whether or not the search came by it ("not among the readings": out of the search's reach,
or under its floor). Two scraps that Autocorrect.rejoin was asked to put together again are told the same way.

  --typed RE, --meant RE   only words whose typed letters, or whose meant word, match
  --right                  the words it gets right too (with --typed or --meant: why "the" is safe)
  --top N                  how many readings of each (6)
  --dial NAME=VALUE, --overlay DIR, --java OPTS     as tool/versus/whatif.py has --set, --overlay and --java
                           (--dial, since --set picks the sentences here)

Ghost Keyboard alone, on the JVM (tool/versus/replay.sh why). Nothing is changed by it: the decoder tells a
listener what it weighed (Autocorrect.TRACE), and on a phone nobody listens.
"""
import argparse
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import score  # noqa: E402
import scoreboard as sb  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--set", dest="which", default="seen")
    ap.add_argument("--lost", action="store_true")
    ap.add_argument("--right", action="store_true")
    ap.add_argument("--typed")
    ap.add_argument("--meant")
    ap.add_argument("--top", type=int, default=6)
    ap.add_argument("--dial", action="append", default=[])
    ap.add_argument("--overlay")
    ap.add_argument("--java")
    ap.add_argument("--unseen-first", type=int, default=sb.UNSEEN_FIRST)
    args = ap.parse_args()
    board = score.board_file(sb.GEOMETRY)
    size = (1248, 1972, 420)
    records = sb.kept("ghost.jsonl.gz") if args.which == "seen" else sb.unseen(args.unseen_first, 300)
    now = sb.verdicts(records, score.replay(records, size, board=board, dials=args.dial, overlay=args.overlay, java=args.java))
    gboard = {}
    if args.which == "seen":
        gboard = {"%s/%d" % (r["thumb"], r["line"]): [p == w for p, w in zip(score.align(r["words"], score.out_words(r["out"]))[0], r["words"])] for r in sb.kept("gboard.jsonl.gz")}
    asks = []
    by_key = {"%s/%d" % (r["thumb"], r["line"]): r for r in records}
    for key, words in now.items():
        for j, (right, clean, typed, stands, meant) in enumerate(words):
            if right and not args.right:
                continue
            if args.lost and not (gboard.get(key) and j < len(gboard[key]) and gboard[key][j]):
                continue
            if args.typed and not re.search(args.typed, typed):
                continue
            if args.meant and not re.search(args.meant, meant):
                continue
            asks.append((key, j, typed, stands, meant, right))
    lines = ["%s#%d\t%s\t%s" % (key, j, score.events_line(by_key[key]), meant) for key, j, _, _, meant, _ in asks]
    env = dict(os.environ)
    env.pop("VERSUS_OVERLAY", None)
    if args.overlay:
        env["VERSUS_OVERLAY"] = args.overlay
    if args.java:
        env["JAVA_OPTS"] = args.java
    extra = ["--board", board] + [x for d in args.dial for x in ("--set", d)]
    p = subprocess.run(["sh", os.path.join(HERE, "replay.sh"), "why"] + [str(v) for v in size] + extra, input="\n".join(lines) + "\n", stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, env=env)
    if p.returncode != 0:
        raise SystemExit("why: replay.sh failed: " + p.stderr[-600:])
    blocks = {}
    block = None
    for line in p.stdout.splitlines():
        if line.startswith("\t"):
            if block is not None:
                block[3].append(line[1:])
        else:
            f = line.split("\t")
            block = (f[1] if len(f) > 1 else "", f[2] if len(f) > 2 else "", f[3] if len(f) > 3 else "", [])
            blocks.setdefault(f[0], []).append(block)
    for key, j, typed, stands, meant, right in asks:
        rec = by_key[key]
        print("%s  meant %s, typed %s, stands %s%s   | %s" % (key, meant, typed or "(nothing)", stands or "(nothing)", "" if right else "  WRONG", rec["target"]))
        tokens = typed.lower().split()
        found = [b for b in blocks.get("%s#%d" % (key, j), []) if b[0].lower().strip() in tokens or b[0].lower().strip() == typed.lower()] if tokens else []
        if not found:
            print("      (autocorrect was not asked about these letters as a word: the space went astray)")
        for word, fixed, before, told in found:
            head = [t for t in told if not t.startswith("  ")]
            meant_line = [t for t in told if t.startswith("  meant ")]
            chosen = [t for t in told if t.startswith("  chosen")]
            readings = [t for t in told if t.startswith("  ") and not t.startswith("  meant ") and not t.startswith("  chosen")]

            def leaves(t):
                m = re.search(r"leaves (-?[\d.]+(?:E-?\d+)?)", t)
                return float(m.group(1)) if m else -99.0
            readings.sort(key=leaves, reverse=True)
            print("      %s after \"%s\": %s" % (word.strip(), before.strip()[-40:], "; ".join(head) if head else "read without taps, or not by byTaps"))
            for t in meant_line:
                among = any(r.strip().startswith(meant + ":") for r in readings)
                print("    " + t + ("" if among else "   (not among the readings)"))
            for t in readings[:args.top]:
                print("    " + t)
            if len(readings) > args.top:
                print("        ... and %d more" % (len(readings) - args.top))
            for t in chosen:
                print("    " + t)
    print("%d words" % len(asks))


if __name__ == "__main__":
    main()
