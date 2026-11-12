#!/usr/bin/env python3
"""Tries Ghost Keyboard's decoder, as it is in this tree, on the words it lost: tool/versus/fixtures.py [file ...]
[--keep-taps-at-marks] [--set NAME=VALUE ...] (the last two as tool/versus/whatif.py has them).

Each line of tool/versus/fixtures/*.tsv is a word another keyboard got and Ghost Keyboard did not, with the taps of
the whole sentence it was in, exactly as they were injected on the emulator. This types every such sentence through
the decoder on the JVM again (tool/versus/replay.sh; a few seconds for all of them) and says which of the lost words
come out right now, by what went wrong, and which sentences changed at all. Run it before and after a change to
autocorrect: "mended" should go up, and "sentences that changed" says what else moved.

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
    if "--keep-taps-at-marks" in argv:
        argv.remove("--keep-taps-at-marks")
        extra.append("--keep-taps-at-marks")
    while "--set" in argv:
        i = argv.index("--set")
        extra += argv[i:i + 2]
        del argv[i:i + 2]
    files = argv or sorted(glob.glob(os.path.join(HERE, "fixtures", "*.tsv")))
    if not files:
        raise SystemExit("fixtures: no tool/versus/fixtures/*.tsv (tool/versus/score.py <run> --fixtures writes them)")
    rows = []
    for path in files:
        cols = None
        for line in open(path, encoding="utf-8"):
            if line.startswith("#") or not line.strip():
                continue
            f = line.rstrip("\n").split("\t")
            if cols is None:
                cols = f
                continue
            rows.append(dict(zip(cols, f)))
    size = os.environ.get("VERSUS_SCREEN", "1248 1972 420").split()
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
    for r in rows:
        text = now.get((r["thumb"], r["line"]), "")
        target = [w.strip(",.?!").lower() for w in r["sentence"].replace("’", "'").split(" ")]
        pairs, _ = score.align(target, score.out_words(text))
        j = int(r["word"])
        ok = j < len(pairs) and pairs[j] == r["meant"]
        c = by.setdefault(r["category"], [0, 0])
        c[0] += 1
        c[1] += 1 if ok else 0
        if ok:
            mended.append("%s (typed %s, was %s) in: %s" % (r["meant"], r["typed"], r["ghost"], r["sentence"]))
    changed = sum(1 for k, r in sentences.items() if now.get(k, "") != r["ghost_field"])
    print("%d words Ghost Keyboard lost, in %d sentences" % (len(rows), len(sentences)))
    for cat, (n, ok) in by.items():
        print("  %-48s mended now %3d of %3d" % (cat, ok, n))
    print("  %-48s mended now %3d of %3d" % ("all", sum(v[1] for v in by.values()), len(rows)))
    print("sentences whose text is not what the emulator's was: %d of %d" % (changed, len(sentences)))
    if os.environ.get("VERBOSE"):
        for m in mended:
            print("    " + m)


if __name__ == "__main__":
    main()
