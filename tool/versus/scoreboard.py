#!/usr/bin/env python3
"""Where the decoder in this tree stands, in a minute: tool/versus/scoreboard.py [--save NAME] [--against NAME] [--bench]

For working on autocorrect. Types two sets of sentences through the decoder on the JVM (tool/versus/replay.sh) and
says how many words are left wrong, by thumb:

  seen     the 900 typings of the run of 2026-11-11 (tool/versus/runs/2026-11-11), tap for tap as the emulator was
           given them, with what Gboard made of the same taps beside it. The words lost to Gboard there are what
           the changes were made from, so it flatters them.
  unseen   900 typings of 300 other held-out sentences (from line 100,001 of the file on, which nothing reads), the
           same three thumbs laid on the same keys. Nothing was looked at there but these totals: what a change
           does to these is what it does.

  --final          also types a third set, kept for the end: 300 more held-out sentences from line 110,001 on.
                   Only its totals are ever printed (no --show, no --save of its words), so that nothing can be
                   fitted to it: look at it when the work is done, not while it is being done

Where the unseen or the final set has been typed into Gboard on the emulator since (tool/versus/other-sets.sh, kept
under tool/versus/runs/other-sets or still in build/versus/runs/other-sets), its line is printed beside that set's
too, over the typings it has got through.
  --save NAME      keeps every word's verdict under build/versus/scoreboard/NAME.json
  --against NAME   and says, set by set, how many words are mended and how many broken since then (--show N of each)
  --bench          also runs the typing bench's own tap test (tool/typing_bench.sh taps, 12,000 words): words right,
                   right words spoiled, and right words no list has that were spoiled, which these sentences are
                   too plain to show (they have few names)
  --overlay DIR, --java OPTS, --set NAME=VALUE, --marks-without-taps    as tool/versus/whatif.py has them

Ghost Keyboard alone, no emulator. The replay is the emulator's to the character on the seen set (score.py's
count: 900 of 900), which is what lets a JVM stand in for it.
"""
import argparse
import gzip
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import score  # noqa: E402
import thumb as th  # noqa: E402

RUN = os.path.join(HERE, "runs", "2026-11-11")
GEOMETRY = os.path.join(RUN, "ghost.geometry.json")
THUMBS = ["careful", "leaning", "sloppy"]
UNSEEN_FIRST = 100000
FINAL_FIRST = 110000


def kept(name):
    with gzip.open(os.path.join(RUN, name), "rt", encoding="utf-8") as f:
        return [r for r in (json.loads(line) for line in f) if not r.get("stalled")]


def other(name, keyboard="gboard"):
    """What another keyboard made of the unseen or the final set (tool/versus/other-sets.sh), as verdicts; None where it was never typed."""
    for folder in (os.path.join(HERE, "runs", "other-sets", name), os.path.join(ROOT, "build", "versus", "runs", "other-sets", name)):
        for file, opener in ((keyboard + ".jsonl.gz", gzip.open), (keyboard + ".jsonl", open)):
            path = os.path.join(folder, file)
            if not os.path.exists(path):
                continue
            records = {}
            with opener(path, "rt", encoding="utf-8") as f:
                for line in f:
                    try:
                        r = json.loads(line)
                    except ValueError:
                        continue
                    # A typing that stalled was typed again: the later record of it is the one that counts.
                    if not r.get("stalled"):
                        records[(r["thumb"], r["line"])] = r
            if records:
                return {"%s/%d" % k: [[p == w, True, "", p or "", w] for p, w in zip(score.align(r["words"], score.out_words(r["out"]))[0], r["words"])] for k, r in records.items()}
    return None


def unseen(first, count, seed="7"):
    geo = json.load(open(GEOMETRY))
    board = th.Board(geo)
    sents, _ = th.sentences(count, first=first)
    out = []
    for name in THUMBS:
        for s in sents:
            taps, slips = th.plan(s, th.THUMBS[name], seed)
            out.append({"thumb": name, "line": s.line_no, "target": s.text, "words": s.words, "touches": board.place(taps, "answers"), "slips": slips})
    return out


def verdicts(records, texts):
    """{"thumb/line": [(right, typed as meant, what was typed, what stands), ...]} for every word."""
    out = {}
    for r in records:
        text = texts[(r["thumb"], r["line"])][0] if isinstance(texts[(r["thumb"], r["line"])], tuple) else texts[(r["thumb"], r["line"])]
        clean, typed, _, _, _ = score.classes(r, score.landed(r))
        pairs, _ = score.align(r["words"], score.out_words(text))
        out["%s/%d" % (r["thumb"], r["line"])] = [[p == w, c, t.strip(), p or "", w] for p, w, c, t in zip(pairs, r["words"], clean, typed)]
    return out


def tally(v):
    rows = {t: [0, 0, 0, 0] for t in THUMBS}
    for key, words in v.items():
        row = rows[key.split("/")[0]]
        for right, clean, _, _, _ in words:
            row[0] += 1
            row[1] += 0 if right else 1
            row[2] += 1 if not right and not clean else 0
            row[3] += 1 if not right and clean else 0
    return rows


def line(name, v, beside=None):
    rows = tally(v)
    words = sum(r[0] for r in rows.values())
    wrong = sum(r[1] for r in rows.values())
    text = "%-8s %s   all %5.2f%% (%d of %d)   spoiled %d" % (name, "  ".join("%s %5.2f%%" % (t, 100.0 * rows[t][1] / max(1, rows[t][0])) for t in THUMBS), 100.0 * wrong / max(1, words), wrong, words, sum(r[3] for r in rows.values()))
    if beside is not None:
        other = tally(beside)
        text += "   | Gboard %s  all %.2f%%" % ("  ".join("%.2f%%" % (100.0 * other[t][1] / max(1, other[t][0])) for t in THUMBS), 100.0 * sum(r[1] for r in other.values()) / max(1, sum(r[0] for r in other.values())))
        lost = sum(1 for k in v for a, b in zip(v[k], beside.get(k, [])) if not a[0] and b[0])
        won = sum(1 for k in v for a, b in zip(v[k], beside.get(k, [])) if a[0] and not b[0])
        text += "   | words Gboard got and this did not: %d, the other way: %d" % (lost, won)
        if len(beside) < len(v):
            # Gboard has not been given every typing of this set yet: the same typings, side by side.
            mine = tally({k: v[k] for k in beside if k in v})
            text += "\n         over the %d typings Gboard has had: this %s  all %.2f%% (%d of %d)" % (
                len(beside), "  ".join("%.2f%%" % (100.0 * mine[t][1] / max(1, mine[t][0])) for t in THUMBS),
                100.0 * sum(r[1] for r in mine.values()) / max(1, sum(r[0] for r in mine.values())), sum(r[1] for r in mine.values()), sum(r[0] for r in mine.values()))
    return text


def changes(now, then, show):
    mended, broken = [], []
    for key in now:
        for a, b in zip(now[key], then.get(key, [])):
            if a[0] and not b[0]:
                mended.append("%s: typed %s, was %s" % (a[4], a[2], b[3] or "(nothing)"))
            if b[0] and not a[0]:
                broken.append("%s: typed %s, now %s" % (a[4], a[2], a[3] or "(nothing)"))
    text = "%d mended, %d broken" % (len(mended), len(broken))
    if show:
        text += "".join("\n        mended  " + m for m in mended[:show]) + "".join("\n        BROKEN  " + m for m in broken[:show])
    return text


def bench(overlay, java, dials):
    """The typing bench's tap test on its own sentences: the "by the taps" lines."""
    src = "android/app/src/main/kotlin/com/infamousvague/devkeyboard"
    files = [src + "/" + n for n in ("Languages.kt", "Layouts.kt", "Hangul.kt", "Autocorrect.kt", "SwipeDecoder.kt", "TapDecoder.kt", "MistakeMemory.kt", "SampleCleaner.kt", "NextWordModel.kt")]
    if overlay:
        files = [os.path.join(overlay, os.path.basename(f)) if os.path.exists(os.path.join(overlay, os.path.basename(f))) else f for f in files]
    jar = os.path.join("build", "versus", "bench-%s.jar" % (os.path.basename(overlay.rstrip("/")) if overlay else "tree"))
    stale = not os.path.exists(os.path.join(ROOT, jar)) or any(os.path.getmtime(os.path.join(ROOT, f)) > os.path.getmtime(os.path.join(ROOT, jar)) for f in files + ["tool/typing_bench/Bench.kt"])
    if stale:
        subprocess.run(["kotlinc"] + files + ["tool/lang_check/stubs/android/graphics/PointF.kt", "tool/typing_bench/Bench.kt", "-include-runtime", "-d", jar], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    sets = ",".join(("A." if d.startswith("Autocorrect.") else "T.") + d.split(".", 1)[1] for d in dials)
    cmd = ["java", "-Xmx2g", "-Dwords=12000"] + (["-Dset=" + sets] if sets else []) + (java.split() if java else []) + ["-jar", jar, ".", "taps"]
    out = subprocess.run(cmd, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True).stdout
    return [ln.split("  [")[0] for ln in out.splitlines() if "by the taps" in ln]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--save")
    ap.add_argument("--against")
    ap.add_argument("--show", type=int, default=0)
    ap.add_argument("--bench", action="store_true")
    ap.add_argument("--overlay")
    ap.add_argument("--java")
    ap.add_argument("--set", action="append", default=[])
    ap.add_argument("--marks-without-taps", action="store_true")
    ap.add_argument("--final", action="store_true")
    ap.add_argument("--unseen-first", type=int, default=UNSEEN_FIRST)
    ap.add_argument("--sentences", type=int, default=300)
    args = ap.parse_args()
    board = score.board_file(GEOMETRY)
    flags = ["--marks-without-taps"] if args.marks_without_taps else []
    size = (1248, 1972, 420)

    def typed(records):
        return verdicts(records, score.replay(records, size, board=board, flags=flags, dials=args.set, overlay=args.overlay, java=args.java))
    seen_records = kept("ghost.jsonl.gz")
    now = {"seen": typed(seen_records), "unseen": typed(unseen(args.unseen_first, args.sentences))}
    gboard = {"%s/%d" % (r["thumb"], r["line"]): [[p == w, True, "", p or "", w] for p, w in zip(score.align(r["words"], score.out_words(r["out"]))[0], r["words"])] for r in kept("gboard.jsonl.gz")}
    print(line("seen", now["seen"], gboard))
    print(line("unseen", now["unseen"], other("unseen") if args.unseen_first == UNSEEN_FIRST else None))
    if args.final:
        print(line("final", typed(unseen(FINAL_FIRST, args.sentences)), other("final")))
    folder = os.path.join(ROOT, "build", "versus", "scoreboard")
    if args.against:
        then = json.load(open(os.path.join(folder, args.against + ".json")))
        for name in ("seen", "unseen"):
            print("    %s, against %s: %s" % (name, args.against, changes(now[name], then[name], args.show)))
    if args.save:
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, args.save + ".json"), "w") as f:
            json.dump(now, f)
    if args.bench:
        for ln in bench(args.overlay, args.java, args.set):
            print("bench    " + " ".join(ln.split()))


if __name__ == "__main__":
    main()
