#!/usr/bin/env python3
"""Where the keyboard takes each thumb to be landing, sentence by sentence: tool/versus/leanlog.py [--set NAME=VALUE ...]

Types the scoreboard's two sets of sentences through the decoder on the JVM (tool/versus/replay.sh with
JAVA_OPTS=-Dlean.log=1, which says for every word the lean it was read by and the estimate once it was settled) and
says two things of ThumbLean's estimate, for each thumb:

  - how many sentences had their first word read by a lean. A sentence is a text field, and the estimate begins at
    nothing in every field: this must be none of them (it is not, with --set ThumbLean.KEEP=true, which is there
    to be measured);
  - where the estimate stood at each sentence's end: its mean and spread across and down, in key widths, how large
    it was, and in how many sentences it was over a tenth of a key. The leaning thumb lands 0.24 of a key right and
    0.18 of a key's height down (0.229 of a width, on the run's keys); the careful and the sloppy thumb do not
    lean, so anything they are given is the estimate wandering.

  leanlog.py --words          and every word of every sentence: the lean it was read by, the estimate after it
  leanlog.py --set ThumbLean.ODDS=16
"""
import argparse
import os
import statistics
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import score  # noqa: E402
import scoreboard  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--set", action="append", default=[], metavar="NAME=VALUE", help="one of the decoder's dials for this run")
    ap.add_argument("--words", action="store_true", help="print every word's line of the log")
    args = ap.parse_args()
    board = score.board_file(scoreboard.GEOMETRY)
    env = dict(os.environ)
    env["JAVA_OPTS"] = "-Dlean.log=1"
    extra = ["--board", board] + [x for d in args.set for x in ("--set", d)]
    for which in ("seen", "unseen"):
        records = scoreboard.kept("ghost.jsonl.gz") if which == "seen" else scoreboard.unseen(scoreboard.UNSEEN_FIRST, 300)
        lines = ["%s/%d\t%s" % (r["thumb"], r["line"], score.events_line(r)) for r in records]
        p = subprocess.run(["sh", os.path.join(HERE, "replay.sh"), "replay", "1248", "1972", "420"] + extra, input="\n".join(lines) + "\n",
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, env=env)
        if p.returncode != 0:
            raise SystemExit("leanlog: replay.sh failed: " + p.stderr[-600:])
        # The log: "LEAN <id>" as a line of taps begins, then "LEAN <typed> <stands> read by <x> <y> then <x> <y> of <n> taps" a word.
        ends, began, sentence, last, first = {}, {}, None, None, True
        for row in p.stderr.splitlines():
            f = row.split("\t")
            if f[0] != "LEAN":
                continue
            if len(f) == 2:
                if sentence is not None and last is not None:
                    ends.setdefault(sentence.split("/")[0], []).append(last)
                sentence, last, first = f[1], None, True
                if args.words:
                    print(row)
                continue
            if args.words:
                print(row)
            thumb = sentence.split("/")[0]
            if first:
                began.setdefault(thumb, [0, 0])
                began[thumb][0] += 1
                if float(f[4]) != 0.0 or float(f[5]) != 0.0:
                    began[thumb][1] += 1
            first = False
            last = (float(f[7]), float(f[8]))
        if sentence is not None and last is not None:
            ends.setdefault(sentence.split("/")[0], []).append(last)
        print(which)
        for thumb in ("careful", "leaning", "sloppy"):
            v = ends.get(thumb, [])
            if not v:
                continue
            xs = [a for a, _ in v]
            ys = [b for _, b in v]
            size = sorted((a * a + b * b) ** 0.5 for a, b in v)
            print("  %-8s first words read by a lean: %d of %d sentences.  At a sentence's end: across %+.3f (spread %.3f), down %+.3f (spread %.3f); its size at the middle %.3f, nine in ten under %.3f, the most %.3f; over a tenth of a key in %d of %d" % (
                thumb, began[thumb][1], began[thumb][0], statistics.mean(xs), statistics.pstdev(xs), statistics.mean(ys), statistics.pstdev(ys),
                size[len(size) // 2], size[int(0.9 * len(size))], size[-1], sum(1 for s in size if s > 0.1), len(size)))


if __name__ == "__main__":
    main()
