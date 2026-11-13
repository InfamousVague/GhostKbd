#!/usr/bin/env python3
"""Tries Ghost Keyboard's decoder, as it is in this tree, on the words it lost: tool/versus/fixtures.py [file ...]
[--marks-without-taps] [--set NAME=VALUE ...] (the last two as tool/versus/whatif.py has them) [--still] [--names].

Each line of tool/versus/fixtures/*.tsv is a word another keyboard got and Ghost Keyboard did not, with the taps of
the whole sentence it was in, exactly as they were injected on the emulator. This types every such sentence through
the decoder on the JVM again (tool/versus/replay.sh; a few seconds for all of them) and says which of the lost words
come out right now, by what went wrong, and which sentences changed at all. Run it before and after a change to
autocorrect: "mended" should go up, and "sentences that changed" says what else moved.

  --still   lists the words that are still lost, by what the decoder's limit was when the list was made: the
            word meant, what was typed, what stands now, and the words around it
  --names   types with names given their capital, as the keyboard now does in a message field. Unsaid, it types
            without (Replay.kt's --no-names): the emulator's text in these files is of a build that gave none, so
            with them every sentence that has a name's capital would count as changed (17 of the 241 did, and
            "sentences whose text is not what the emulator's was" read 136 where the decoder's letters make it 119).
            The words mended are the same either way: they are held to their letters

No emulator, and no other keyboard: what Gboard typed is in the file.
"""
import collections
import glob
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import score  # noqa: E402


def main():
    argv = sys.argv[1:]
    extra = []
    still = "--still" in argv
    if still:
        argv.remove("--still")
    if "--marks-without-taps" in argv:
        argv.remove("--marks-without-taps")
        extra.append("--marks-without-taps")
    if "--names" in argv:
        argv.remove("--names")
    else:
        extra.append("--no-names")
    while "--set" in argv:
        i = argv.index("--set")
        extra += argv[i:i + 2]
        del argv[i:i + 2]
    files = argv or sorted(glob.glob(os.path.join(HERE, "fixtures", "*.tsv")))
    if not files:
        raise SystemExit("fixtures: no tool/versus/fixtures/*.tsv (tool/versus/score.py <run> --fixtures writes them)")
    rows = []
    boards = set()
    for path in files:
        cols = None
        for line in open(path, encoding="utf-8"):
            if line.startswith("# board: "):
                # The keyboard these taps were made on (its geometry file): they are read on that, not on this tree's.
                boards.add(score.board_file(os.path.join(score.ROOT, line[len("# board: "):].strip())))
            if line.startswith("#") or not line.strip():
                continue
            f = line.rstrip("\n").split("\t")
            if cols is None:
                cols = f
                continue
            rows.append(dict(zip(cols, f)))
    size = os.environ.get("VERSUS_SCREEN", "1248 1972 420").split()
    if len(boards) > 1:
        raise SystemExit("fixtures: these files were typed on %d different keyboards; give them one at a time" % len(boards))
    extra = [x for b in boards if b for x in ("--board", b)] + extra
    sentences = {}
    for r in rows:
        sentences[(r["thumb"], r["line"])] = r
    lines = ["%s/%s\t%s" % (k[0], k[1], r["events"]) for k, r in sentences.items()]
    p = subprocess.run(["sh", os.path.join(HERE, "replay.sh"), "replay"] + size + extra, input="\n".join(lines) + "\n", stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    if p.returncode != 0:
        raise SystemExit("fixtures: replay.sh failed: " + p.stderr[-600:])
    now = {}
    for line in p.stdout.splitlines():
        f = line.split("\t")
        if len(f) >= 2 and "/" in f[0]:
            now[tuple(f[0].split("/"))] = f[1]
    by = collections.OrderedDict()
    mended = []
    lost = collections.OrderedDict()
    for r in rows:
        text = now.get((r["thumb"], r["line"]), "")
        target = [w.strip(",.?!").lower() for w in r["sentence"].replace("’", "'").split(" ")]
        pairs, _ = score.align(target, score.out_words(text))
        j = int(r["word"])
        ok = j < len(pairs) and pairs[j] == r["meant"]
        c = by.setdefault(r["category"], [0, 0])
        c[0] += 1
        c[1] += 1 if ok else 0
        if not ok:
            stands = pairs[j] if j < len(pairs) and pairs[j] else "(nothing)"
            lost.setdefault(r["limit"], []).append("%-8s %-14s typed %-16s stands %-16s | %s" % (r["thumb"], r["meant"], r["typed"] or "(nothing)", stands, text))
        if ok:
            mended.append("%s (typed %s, was %s) in: %s" % (r["meant"], r["typed"], r["ghost"], r["sentence"]))
    changed = sum(1 for k, r in sentences.items() if now.get(k, "") != r["ghost_field"])
    print("%d words Ghost Keyboard lost, in %d sentences" % (len(rows), len(sentences)))
    for cat, (n, ok) in by.items():
        print("  %-48s mended now %3d of %3d" % (cat, ok, n))
    print("  %-48s mended now %3d of %3d" % ("all", sum(v[1] for v in by.values()), len(rows)))
    print("sentences whose text is not what the emulator's was: %d of %d" % (changed, len(sentences)))
    if still:
        for limit, words in sorted(lost.items(), key=lambda kv: -len(kv[1])):
            print("%d still lost: %s" % (len(words), limit))
            for w in words:
                print("    " + w)
    if os.environ.get("VERBOSE"):
        for m in mended:
            print("    " + m)


if __name__ == "__main__":
    main()
