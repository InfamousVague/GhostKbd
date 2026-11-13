#!/usr/bin/env python3
"""Words changed behind the cursor, once the word after them has ended: tool/versus/behind.py gboard|seen|probe ...

  gboard [SAVED] [--show N]
        Gboard's own: a run's records keep the field's text after every key press ("typed"). For each word this
        takes the first text in which it had a space (or the sentence's end) after it, and the text at the end, and
        counts the words wrong in the first and right in the last (put right behind the cursor) and the other way
        (spoiled). Beside them, what the decoder made of the same words, from a scoreboard saved as SAVED (default
        "base"). Written by the lead on 2026-11-12 as build/after/behind.py; Gboard typed the seen and unseen sets.

  seen [--set NAME=VALUE ...] [--show N]
        Ghost Keyboard's: the seen and the unseen set typed through the decoder on the JVM (replay.sh), and every
        word the second look (Autocorrect.again) changed behind the cursor, counted per 1,000 words typed: a wrong
        word put right, a right word spoiled, one wrong word for another; and how many of the changes put back
        what was typed after the space had changed it (a word a person watched change twice).

  probe [--show N]
        What the word after could win, measured before trusting the mechanism: the decoder typed with the second
        look made to change nothing (Autocorrect.AGAIN_LEAST=99, so the text is the starting tree's), and for every
        word it leaves wrong and every right word it spoils on the seen set (in totals only on the unseen set): was
        it read again at all, was the word meant among the readings held up to the word after, and would it come
        first with the word after weighed in (no margin).

No emulator. The seen set is 900 typings of tool/versus/runs/2026-11-11, the unseen set scoreboard.py's.
"""
import gzip
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import score  # noqa: E402
import scoreboard as sb  # noqa: E402

SIZE = ("1248", "1972", "420")


def align_index(target, got):
    """For each word of [target] the index of the word of [got] it lines up with, or None (score.align's way)."""
    n, m = len(target), len(got)
    cost = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        cost[i][0] = i
    for j in range(1, m + 1):
        cost[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            cost[i][j] = min(cost[i - 1][j - 1] + (0 if target[i - 1] == got[j - 1] else 1), cost[i - 1][j] + 1, cost[i][j - 1] + 1)
    pairs = [None] * n
    i, j = n, m
    while i > 0 and j > 0:
        if cost[i][j] == cost[i - 1][j - 1] + (0 if target[i - 1] == got[j - 1] else 1):
            pairs[i - 1] = j - 1
            i, j = i - 1, j - 1
        elif cost[i][j] == cost[i - 1][j] + 1:
            i -= 1
        else:
            j -= 1
    return pairs


def norm(word):
    w = score.out_words(word)
    return w[0] if w else ""


def sets():
    return (("seen", sb.kept("ghost.jsonl.gz")), ("unseen", sb.unseen(sb.UNSEEN_FIRST, 300)))


def run(command, records, dials=()):
    lines = ["%s/%d\t%s" % (r["thumb"], r["line"], score.events_line(r)) for r in records]
    extra = ["--board", score.board_file(sb.GEOMETRY)] + [x for d in dials for x in ("--set", d)]
    env = dict(os.environ)
    env.pop("VERSUS_OVERLAY", None)
    p = subprocess.run(["sh", os.path.join(HERE, "replay.sh"), command] + list(SIZE if command == "replay" else ()) + extra,
                       input="\n".join(lines) + "\n", stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, env=env, cwd=ROOT)
    if p.returncode != 0:
        raise SystemExit("behind: replay.sh failed: " + p.stderr[-600:])
    return p.stdout


def target_of(r, text, k):
    """The word of [r]'s sentence that the [k]th word of [text] lines up with, as (index, word), or (None, None)."""
    got = score.out_words(text)
    for j, g in enumerate(align_index(r["words"], got)):
        if g == k:
            return j, r["words"][j]
    return None, None


def seen(dials, show):
    for name, records in sets():
        by = {"%s/%d" % (r["thumb"], r["line"]): r for r in records}
        out = run("replay", records, dials)
        words = right = spoiled = swapped = back = 0
        ex = []
        for line in out.splitlines():
            f = line.split("\t")
            if len(f) < 3 or f[0] not in by:
                continue
            r = by[f[0]]
            words += len(r["words"])
            for ch in (f[5].split(";") if len(f) > 5 and f[5] else []):
                k, stood, now, typed = ch.split(":", 3)
                j, meant = target_of(r, f[1], int(k))
                a, b = norm(stood) == meant, norm(now) == meant
                if not a and b:
                    right += 1
                    kind = "put right"
                elif a and not b:
                    spoiled += 1
                    kind = "SPOILED"
                else:
                    swapped += 1
                    kind = "wrong for wrong" if not a else "right for right"
                if now == typed and stood != typed:
                    back += 1
                ex.append("%-8s %-15s meant %-10s typed %-10s stood %-10s now %s" % (name, kind, meant, typed, stood, now))
        n = right + spoiled + swapped
        print("%-7s %d changes behind the cursor in %d words (%.1f per 1,000): %d put a wrong word right, %d spoiled a right one, %d one word for another; %d put back what was typed" % (
            name, n, words, 1000.0 * n / max(1, words), right, spoiled, swapped, back))
        for e in sorted(ex, key=lambda s: ("put right" in s, s))[:show]:
            print("    " + e)


def readings(trace):
    """A looked word's trace lines as [(word, worth, slips, after, all, stands)]."""
    out = []
    for t in trace:
        m = re.match(r"\s+(\S+): worth (\S+), slips (\S+), after (\S+), in all (\S+)( \(stands\))?$", t)
        if m:
            out.append((m.group(1), float(m.group(2)), float(m.group(3)), float(m.group(4)), float(m.group(5)), bool(m.group(6))))
    return out


def probe(show):
    saved = json.load(open(os.path.join(ROOT, "build", "versus", "scoreboard", "base.json")))
    for name, records in sets():
        by = {"%s/%d" % (r["thumb"], r["line"]): r for r in records}
        out = run("looks", records, ["Autocorrect.AGAIN_LEAST=99"])
        looked = {}   # (id, k) -> (typed, stood, next, trace)
        finals = {}
        cur = None
        for line in out.splitlines():
            if line.startswith("\t"):
                if cur is not None:
                    cur[3].append(line[1:])
                continue
            f = line.split("\t")
            if len(f) >= 3 and f[1] == "=":
                finals[f[0]] = f[2]
                cur = None
            elif len(f) >= 6:
                cur = (f[2], f[3], f[4], [])
                looked[(f[0], int(f[1]))] = cur
        # Each looked word, lined up with the sentence's word it stands for.
        at = {}
        for (key, k), v in looked.items():
            if key in finals:
                j, _ = target_of(by[key], finals[key], k)
                if j is not None:
                    at[(key, j)] = v
        tally = {}
        ex = []
        for key, verdicts in saved[name].items():
            for j, (ok, clean, typed, stands, meant) in enumerate(verdicts):
                if ok:
                    continue
                kind = "spoiled" if clean else "wrong"
                v = at.get((key, j))
                if v is None:
                    why = "not read again"
                else:
                    rs = readings(v[3])
                    words = [x[0] for x in rs]
                    if meant not in words:
                        why = "meant not among the readings"
                    else:
                        best = max(rs, key=lambda x: x[4])
                        why = "first with the word after" if best[0] == meant else "among them, not first"
                    if len(ex) < 4000:
                        ex.append("%-8s %-30s meant %-10s typed %-10s stood %-10s then %-10s | %s" % (name, why, meant, v[0], v[1], v[2], "  ".join("%s %.2f(%+.2f)" % (x[0], x[4], x[3]) for x in rs)))
                tally.setdefault(kind, {}).setdefault(why, 0)
                tally[kind][why] += 1
        for kind in ("wrong", "spoiled"):
            t = tally.get(kind, {})
            print("%-7s %s: %d words (%s)" % (name, "left wrong, typed with a slip" if kind == "wrong" else "right words spoiled", sum(t.values()), ", ".join("%s %d" % (w, c) for w, c in sorted(t.items(), key=lambda x: -x[1]))))
        if name == "seen":
            for e in sorted(ex)[:show]:
                print("    " + e)


def gboard(saved, show):
    ours = json.load(open(os.path.join(ROOT, "build", "versus", "scoreboard", saved + ".json")))
    for name, path in (("seen", "tool/versus/runs/2026-11-11/gboard.jsonl.gz"), ("unseen", "tool/versus/runs/other-sets/unseen/gboard.jsonl.gz")):
        recs = {}
        with gzip.open(os.path.join(ROOT, path), "rt", encoding="utf-8") as f:
            for line in f:
                r = json.loads(line)
                if not r.get("stalled"):
                    recs[(r["thumb"], r["line"])] = r      # a typing that stalled was typed again: the later record counts
        mended = spoiled = wrong_first = wrong_last = words = ours_wrong = ours_right = 0
        ex = []
        for key, r in sorted(recs.items()):
            # The field's texts that stood for 25 ms or more, or were the last (an edit passes through others).
            texts = [text for i, (t, text) in enumerate(r["typed"]) if not (i + 1 < len(r["typed"]) and r["typed"][i + 1][0] - t < 25)]
            first = [None] * len(r["words"])
            for text in texts:
                if not (text.endswith(" ") or text is texts[-1]):
                    continue
                got = score.out_words(text)
                if not got:
                    continue
                pairs = score.align(r["words"][:min(len(r["words"]), len(got) + 1)], got)[0]
                for j in range(min(len(pairs), len(got), len(r["words"]))):
                    if first[j] is None:
                        first[j] = pairs[j] == r["words"][j]
            last = [p == w for p, w in zip(score.align(r["words"], score.out_words(r["out"]))[0], r["words"])]
            v = ours[name].get("%s/%d" % key)
            for j, (a, b) in enumerate(zip(first, last)):
                words += 1
                wrong_first += a is False
                wrong_last += not b
                spoiled += 1 if a and not b else 0
                if a is False and b:
                    mended += 1
                    ok = v[j][0] if v and j < len(v) else None
                    ours_right += 1 if ok else 0
                    ours_wrong += 0 if ok else 1
                    if not ok:
                        ex.append("%-8s %d  meant %-10s stands %-12s | %s" % (key[0], key[1], r["words"][j], v[j][3] if v and j < len(v) else "?", " ".join(r["words"][max(0, j - 2):j + 3])))
        print("%s: Gboard had %d of %d words wrong when each first had a space or the end after it (%.2f%%), %d at the end (%.2f%%): %d put right behind the cursor, %d right words spoiled that way" % (
            name, wrong_first, words, 100.0 * wrong_first / words, wrong_last, 100.0 * wrong_last / words, mended, spoiled))
        print("   of the %d: the decoder saved as %r has %d wrong and %d right" % (mended, saved, ours_wrong, ours_right))
        if name == "seen":
            for e in ex[:show]:
                print("     " + e)


def main():
    argv = sys.argv[1:]
    show = 0
    if "--show" in argv:
        i = argv.index("--show")
        show = int(argv[i + 1])
        del argv[i:i + 2]
    dials = []
    while "--set" in argv:
        i = argv.index("--set")
        dials.append(argv[i + 1])
        del argv[i:i + 2]
    if not argv or argv[0] not in ("gboard", "seen", "probe"):
        raise SystemExit(__doc__)
    if argv[0] == "gboard":
        gboard(argv[1] if len(argv) > 1 else "base", show)
    elif argv[0] == "seen":
        seen(dials, show)
    else:
        probe(show)


if __name__ == "__main__":
    main()
