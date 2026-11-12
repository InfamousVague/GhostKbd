#!/usr/bin/env python3
"""Ghost Keyboard alone, on the JVM, in seconds: tool/versus/whatif.py [--sentences 300] [--aim answers,drawn] [--first N].

The same sentences, thumbs and seed as a versus run, typed through the decoder as it stands in this tree
(tool/versus/replay.sh), with no emulator. For trying a change to autocorrect before spending hours on the
emulator, and for asking things the emulator run did not:

  --aim drawn     the thumb aims at the middle of each key as it is drawn, not at the middle of where the key
                  answers. Ghost Keyboard moves every touch before reading it, by where Matt's thumb was measured
                  to land on each key (KeyboardView.LETTER_OFFSETS_DP), so a thumb that lands where it aims is read
                  as off its key: this says what that costs.
  --lean x,y      another lean for the leaning thumb (key widths right, key heights down)
  --marks-without-taps
                  the word before a question mark, an exclamation mark or a comma (typed from the ?123 page) is
                  read without its taps, as the app read it until 2026-11-12 (0.15.0, which the run of 2026-11-11
                  was typed on): what keeping them is worth
  --set NAME=VALUE
                  one of the decoder's own dials for this run, any number of times: TapDecoder.BUDGET=8,
                  Autocorrect.UNKNOWN_LONG=0, Autocorrect.MARGIN_COMMON=1.0 (the numbers tool/typing_bench.sh tunes)
  --changed FILE  with either of the two above: writes the typings whose text the change alters, a
                  "<thumb> <line>" a line, for typing just those on the emulator (tool/versus/run.py --only FILE)

It scores as score.py does (words wrong, left wrong, spoiled), with the same resampling interval. With
--marks-without-taps or --set it also types the sentences through the decoder as it is and says how many words the
change mends and how many it breaks. It trusts the replay only as far as score.py's agreement count says (900 of
900 sentences on 2026-11-11): run that on an emulator run first.
"""
import argparse
import json
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import score  # noqa: E402
import thumb as th  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sentences", type=int, default=300)
    ap.add_argument("--first", type=int, default=th.FIRST_LINE)
    ap.add_argument("--file", default=th.HELD_OUT, help="the sentences, one a line")
    ap.add_argument("--aim", default="answers")
    ap.add_argument("--thumbs", default="careful,leaning,sloppy")
    ap.add_argument("--lean", help="lean of the leaning thumb, as x,y in key sizes")
    ap.add_argument("--seed", default="7")
    ap.add_argument("--geometry", default=os.path.join(HERE, "geometry", "ghost-1248x1972-420dpi.json"))
    ap.add_argument("--resamples", type=int, default=2000)
    ap.add_argument("--show", type=int, default=0, help="print this many of the words left wrong")
    ap.add_argument("--marks-without-taps", action="store_true")
    ap.add_argument("--changed", metavar="FILE")
    ap.add_argument("--set", action="append", default=[], metavar="NAME=VALUE")
    args = ap.parse_args()
    geo = json.load(open(args.geometry))
    board = th.Board(geo)
    sents, _ = th.sentences(args.sentences, path=args.file, first=args.first)
    thumbs = [th.THUMBS[n] for n in args.thumbs.split(",")]
    if args.lean:
        x, y = [float(v) for v in args.lean.split(",")]
        thumbs = [th.Thumb(t.name, t.sx, t.sy, x, y) if t.name == "leaning" else t for t in thumbs]
    size = (geo["device"]["width"], geo["device"]["height"], geo["device"]["dpi"])
    print("%d sentences from line %d, %d words; Ghost Keyboard's decoder as it is in this tree" % (len(sents), args.first + 1, sum(len(s.words) for s in sents)))
    for aim in args.aim.split(","):
        records = []
        for t in thumbs:
            for s in sents:
                taps, slips = th.plan(s, t, args.seed)
                records.append({"thumb": t.name, "line": s.line_no, "target": s.text, "words": s.words, "touches": board.place(taps, aim), "slips": slips})
        # The taps are laid on the keys of [--geometry] and read on the same keys, whatever this tree's are now.
        keys = score.board_file(args.geometry)
        out = score.replay(records, size, board=keys, flags=["--marks-without-taps"] if args.marks_without_taps else [], dials=args.set)
        changed = args.marks_without_taps or args.set
        as_it_is = score.replay(records, size, board=keys) if changed else None
        if changed and args.changed:
            moved = [k for k in sorted(out) if out[k][0] != as_it_is[k][0]]
            with open(args.changed, "w") as f:
                f.write("".join("%s %d\n" % k for k in moved))
            print("%d of %d typings come out otherwise: %s" % (len(moved), len(out), args.changed))
        rnd = random.Random(20261111)
        for t in thumbs:
            rows, wrongs = [], []
            mended = broken = 0
            for r in records:
                if r["thumb"] != t.name:
                    continue
                text = out[(r["thumb"], r["line"])][0]
                clean, typed, off, space_trouble, order = score.classes(r, score.landed(r))
                pairs, extra = score.align(r["words"], score.out_words(text))
                right = [p == w for p, w in zip(pairs, r["words"])]
                if changed:
                    was, _ = score.align(r["words"], score.out_words(as_it_is[(r["thumb"], r["line"])][0]))
                    mended += sum(1 for p, q, w in zip(pairs, was, r["words"]) if p == w and q != w)
                    broken += sum(1 for p, q, w in zip(pairs, was, r["words"]) if p != w and q == w)
                n = len(right)
                rows.append((n, sum(1 for x in right if not x), sum(1 for x, c in zip(right, clean) if not x and not c), sum(1 for x, c in zip(right, clean) if not x and c), sum(1 for c in clean if not c)))
                wrongs += ["%s (typed %s, left %s) in: %s" % (w, typed[j], pairs[j], r["target"]) for j, w in enumerate(r["words"]) if not right[j]]
            total = sum(r[0] for r in rows)

            def rate(sample, k):
                return 100.0 * sum(r[k] for r in sample) / max(1, sum(r[0] for r in sample))
            boot = sorted(rate([rows[rnd.randrange(len(rows))] for _ in rows], 1) for _ in range(args.resamples))
            slipped = sum(r[4] for r in rows)
            moved = sum(1 for r in records if r["thumb"] == t.name for u in r["touches"] if u.get("moved"))
            taps_n = sum(1 for r in records if r["thumb"] == t.name for u in r["touches"] if u["kind"] != "mark")
            print("aim %-8s %-8s words wrong %5.2f%% (%.1f to %.1f)  left wrong %5.2f%%  spoiled %5.2f%%  slips %d, mended %.0f%%  [%d words; %.1f%% of taps moved off a key that is no letter]" % (
                aim, t.name, rate(rows, 1), boot[int(0.025 * (len(boot) - 1))], boot[int(0.975 * (len(boot) - 1))], rate(rows, 2), rate(rows, 3),
                slipped, 100.0 * (slipped - sum(r[2] for r in rows)) / max(1, slipped), total, 100.0 * moved / max(1, taps_n)) + ("  against the decoder as it is: %d words mended, %d broken" % (mended, broken) if changed else ""))
            for w in wrongs[:args.show]:
                print("      " + w)


if __name__ == "__main__":
    main()
