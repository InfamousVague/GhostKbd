#!/usr/bin/env python3
"""Saved scoreboards read again, by thumb and by where in its sentence a word stands: tool/versus/saved.py NAME [NAME ...]

tool/versus/scoreboard.py --save NAME keeps every word's verdict under build/versus/scoreboard. Its own --against
says what was mended and broken over the three thumbs together; a change that helps one thumb and costs another
(an estimate of where a thumb is landing, which a thumb with no lean can only lose by) needs them apart:

  saved.py base done               words wrong for each thumb in "base" and in "done", with the words mended and
                                   broken from the first to each of the others, on the seen set and the unseen
  saved.py base done --places      and words wrong by the word's place in its sentence (the first word of a field
                                   is read with nothing known of the thumb in it; the tenth is not)
  saved.py base done --show 20     and the first 20 words mended and broken, for each thumb
  saved.py base done --thumb leaning --set seen

Nothing is typed: this reads what scoreboard.py saved, in no time.
"""
import argparse
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FOLDER = os.path.join(ROOT, "build", "versus", "scoreboard")
THUMBS = ("careful", "leaning", "sloppy")
# A word's place in its sentence, from 1: the first four each alone, then in stretches.
PLACES = ((1, 1), (2, 2), (3, 3), (4, 4), (5, 6), (7, 9), (10, 99))


def load(name):
    with open(os.path.join(FOLDER, name + ".json")) as f:
        return json.load(f)


def wrong(board, thumb, low=1, high=99):
    """Words wrong and words in all for one thumb, among the words standing [low]th to [high]th in their sentences."""
    bad = total = 0
    for key, words in board.items():
        if key.split("/")[0] != thumb:
            continue
        for i, word in enumerate(words, 1):
            if low <= i <= high:
                total += 1
                bad += 0 if word[0] else 1
    return bad, total


def changes(then, now, thumb):
    """The words right in [now] and wrong in [then], and the other way, for one thumb."""
    mended, broken = [], []
    for key in now:
        if key.split("/")[0] != thumb:
            continue
        for i, (a, b) in enumerate(zip(now[key], then.get(key, [])), 1):
            if a[0] and not b[0]:
                mended.append("%s (word %d of %s): typed %s, was %s" % (a[4], i, key, a[2], b[3] or "(nothing)"))
            if b[0] and not a[0]:
                broken.append("%s (word %d of %s): typed %s, now %s" % (a[4], i, key, a[2], a[3] or "(nothing)"))
    return mended, broken


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("names", nargs="+", help="saved scoreboards; the first is what the others are held against")
    ap.add_argument("--places", action="store_true", help="words wrong by the word's place in its sentence")
    ap.add_argument("--show", type=int, default=0, help="list this many of the words mended and broken, for each thumb")
    ap.add_argument("--thumb", choices=THUMBS, help="one thumb only")
    ap.add_argument("--set", dest="only", choices=("seen", "unseen"), help="one set only")
    args = ap.parse_args()
    boards = [(name, load(name)) for name in args.names]
    thumbs = (args.thumb,) if args.thumb else THUMBS
    for which in ((args.only,) if args.only else ("seen", "unseen")):
        print(which)
        first = boards[0][1][which]
        for name, board in boards:
            cells = []
            for thumb in thumbs:
                bad, total = wrong(board[which], thumb)
                cell = "%s %5.2f%% (%d of %d)" % (thumb, 100.0 * bad / max(1, total), bad, total)
                if board is not boards[0][1]:
                    mended, broken = changes(first, board[which], thumb)
                    cell += " +%d -%d" % (len(mended), len(broken))
                cells.append(cell)
            everyone = [wrong(board[which], t) for t in THUMBS]
            all_of = "" if args.thumb else "   all %5.2f%% (%d of %d)" % (100.0 * sum(b for b, _ in everyone) / max(1, sum(t for _, t in everyone)), sum(b for b, _ in everyone), sum(t for _, t in everyone))
            print("  %-12s %s%s" % (name, "   ".join(cells), all_of))
        if args.places:
            print("  by the word's place in its sentence, words wrong:")
            for thumb in thumbs:
                for name, board in boards:
                    row = []
                    for low, high in PLACES:
                        bad, total = wrong(board[which], thumb, low, high)
                        label = str(low) if low == high else ("%d-%d" % (low, high) if high < 99 else "%d on" % low)
                        row.append("%s: %5.2f%% (%d of %d)" % (label, 100.0 * bad / max(1, total), bad, total))
                    print("    %-8s %-12s %s" % (thumb, name, "   ".join(row)))
        if args.show:
            for name, board in boards[1:]:
                for thumb in thumbs:
                    mended, broken = changes(first, board[which], thumb)
                    for m in mended[:args.show]:
                        print("    %s, %s, mended  %s" % (name, thumb, m))
                    for m in broken[:args.show]:
                        print("    %s, %s, BROKEN  %s" % (name, thumb, m))


if __name__ == "__main__":
    main()
