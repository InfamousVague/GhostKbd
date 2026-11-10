#!/usr/bin/env python3
"""What the anonymous typing reports say, added up: the numbers the typing benchmark could only simulate.

    python3 summarize.py /var/lib/ghostkbd-reports            # every day there
    python3 summarize.py /var/lib/ghostkbd-reports 2026-11    # the days that start so

Prints how often autocorrect's fixes and swipe's first readings stood, how far taps land from the middle of the key
that was meant (by how tall the keys are, which is what tool/typing_bench.sh -Dkeyh could not tell), and the
commonest mistakes; and writes mistakes.tsv beside the reports: typed, meant, how it is known, the reports it was in
and how often. A pair is only worth acting on once several reports have it.
"""
import collections
import glob
import json
import math
import os
import sys

GRID, BIN, SPAN = 12, 0.25, 1.5


def spread(grid):
    """Mean and standard deviation of a grid of tap counts, across and down, in keys; and how many taps."""
    n = sum(grid)
    if n == 0:
        return 0, (0.0, 0.0), (0.0, 0.0)
    sx = sy = sxx = syy = 0.0
    for i, c in enumerate(grid):
        x = -SPAN + (i % GRID + 0.5) * BIN
        y = -SPAN + (i // GRID + 0.5) * BIN
        sx += c * x; sy += c * y; sxx += c * x * x; syy += c * y * y
    mx, my = sx / n, sy / n
    return n, (mx, math.sqrt(max(sxx / n - mx * mx, 0.0))), (my, math.sqrt(max(syy / n - my * my, 0.0)))


def share(a, b):
    return "—" if b == 0 else f"{100.0 * a / b:.1f}%"


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else "./reports"
    prefix = sys.argv[2] if len(sys.argv) > 2 else ""
    reports = []
    for path in sorted(glob.glob(os.path.join(directory, prefix + "*.jsonl"))):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    reports.append(json.loads(line))
    print(f"{len(reports)} reports")
    if not reports:
        return
    counts = collections.Counter()
    for r in reports:
        counts.update(r.get("counts", {}))
    typed, swiped = counts["words.typed"], counts["words.swiped"]
    print(f"words: {typed} typed, {swiped} swiped, {counts['words.completed']} finished from the row, {counts['words.predicted']} predicted")
    print(f"autocorrect: {counts['fix.made']} fixes ({share(counts['fix.made'], typed)} of typed words), undone {share(counts['fix.undone'], counts['fix.made'])}, "
          f"typed over {share(counts['fix.retyped'], counts['fix.made'])}; two words parted {counts['fix.joined']}")
    print(f"words put right by hand: {counts['mistakes.retyped']} ({share(counts['mistakes.retyped'], typed)} of typed words), from the row {counts['mistakes.picked']}")
    settled = counts["swipe.kept"] + counts["swipe.picked"]
    print(f"swipe: first reading stood {share(counts['swipe.kept'], settled + counts['swipe.deleted'])}, another chosen {share(counts['swipe.picked'], settled + counts['swipe.deleted'])}, "
          f"deleted {share(counts['swipe.deleted'], settled + counts['swipe.deleted'])}, of them drawn again as an offered word {counts['swipe.redrawn']}")
    ms = [sum(r.get("swipeMs", [0] * 8)[i] for r in reports) for i in range(8)]
    print("swipe reading time (ms) <5 <10 <20 <40 <80 <160 <320 more:", " ".join(str(n) for n in ms))

    # Where taps land, by how tall the keys are for their width: the question a simulated thumb cannot answer.
    by_aspect = collections.defaultdict(lambda: {"own": [0] * GRID * GRID, "slip": [0] * GRID * GRID, "reports": 0})
    for r in reports:
        aspect = (r.get("board") or {}).get("rowAspect")
        key = "unknown" if aspect is None else ("squat (<1.15)" if aspect < 1.15 else "tall (≥1.15)")
        b = by_aspect[key]
        b["reports"] += 1
        for name in ("own", "slip"):
            for i, c in enumerate(r["taps"][name]):
                b[name][i] += c
    for key, b in sorted(by_aspect.items()):
        n, (mx, sx), (my, sy) = spread(b["own"])
        m, (ux, tx), (uy, ty) = spread(b["slip"])
        print(f"taps, {key} keys, {b['reports']} reports: {n} on words that stood: across {mx:+.2f} ± {sx:.2f} key widths, down {my:+.2f} ± {sy:.2f} rows; "
              f"{m} that were slips, from the key meant: across {ux:+.2f} ± {tx:.2f}, down {uy:+.2f} ± {ty:.2f}")

    pairs = collections.defaultdict(lambda: [0, 0])
    for r in reports:
        for kind, typed_word, meant, n in r.get("mistakes", []):
            p = pairs[(typed_word, meant, kind)]
            p[0] += 1
            p[1] += n
    ranked = sorted(pairs.items(), key=lambda kv: (-kv[1][0], -kv[1][1], kv[0]))
    out = os.path.join(directory, "mistakes.tsv")
    with open(out, "w", encoding="utf-8") as f:
        f.write("typed\tmeant\thow\treports\ttimes\n")
        for (typed_word, meant, kind), (in_reports, times) in ranked:
            f.write(f"{typed_word}\t{meant}\t{kind}\t{in_reports}\t{times}\n")
    print(f"{len(ranked)} different mistakes, written to {out}; the commonest:")
    for (typed_word, meant, kind), (in_reports, times) in ranked[:20]:
        print(f"  {typed_word} → {meant}  ({kind}; {in_reports} reports, {times} times)")
    strokes = sum(len(r.get("strokes", [])) for r in reports)
    misread = sum(1 for r in reports for s in r.get("strokes", []) if s["kind"] != "kept")
    print(f"{strokes} swipe strokes kept, {misread} of them misread: replay them with the decoder before changing it")


if __name__ == "__main__":
    main()
