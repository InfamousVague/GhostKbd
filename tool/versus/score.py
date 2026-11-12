#!/usr/bin/env python3
"""Scores a versus run: tool/versus/score.py <run folder> [--fixtures] [--no-replay].

The same score for every keyboard, from what stood in the field once the keyboard had done its correcting:

  words wrong        words of the sentence that are not there as written (capitals and marks aside), found by
                     lining the field's words up against the sentence's (a word run into its neighbour is two wrong)
  ... left wrong     of those, words the thumb slipped on (a tap off its key, a letter left out, doubled or swapped,
                     a space missed or landing on a letter) that the keyboard did not mend
  ... spoiled        of those, words the thumb typed as meant (every tap on its own key, the spaces either side on
                     the bar) that the keyboard changed all the same, or broke with its neighbour

"On its own key" is each keyboard's own keys as they stand at the start of a sentence: Gboard's cells as measured
(calibrate.py); Ghost Keyboard's as its own code reads a touch (replay.sh, held to the emulator's taps).

Each number has a 95% interval from resampling sentences (2,000 times, sentences drawn whole, the same draws for
every keyboard, so the gap between two keyboards has an interval of its own).

For Ghost Keyboard the same taps are also typed through its decoder on the JVM (replay.sh), and the two are held
against each other, sentence by sentence.

Writes into the run folder: results.json, results.md (the tables), agreement.tsv (where the JVM and the emulator
differ), losses.tsv (every word another keyboard got and Ghost Keyboard did not, with what went wrong).
With --fixtures the losses are also written to tool/versus/fixtures/ for tool/versus/fixtures.py to replay.
"""
import argparse
import collections
import json
import os
import random
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
THUMB_ORDER = ["careful", "leaning", "sloppy"]


def out_words(text):
    """The words standing in a field: lower case, the marks about them gone, a curly apostrophe made straight."""
    words = []
    for tok in text.replace("’", "'").lower().split():
        w = re.sub(r"^[^a-z0-9']+|[^a-z0-9']+$", "", tok).strip("'") if not re.search(r"[a-z0-9]", tok) else re.sub(r"^[^a-z0-9]+|[^a-z0-9]+$", "", tok)
        if w:
            words.append(w)
    return words


def align(target, got):
    """For each word of [target] the word of [got] it lines up with (None where it has none), and the words of
    [got] left over: the cheapest way to turn one list into the other, a word swapped, dropped or added each one."""
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
    extra = []
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and cost[i][j] == cost[i - 1][j - 1] + (0 if target[i - 1] == got[j - 1] else 1):
            pairs[i - 1] = got[j - 1]
            i, j = i - 1, j - 1
        elif i > 0 and cost[i][j] == cost[i - 1][j] + 1:
            i -= 1
        else:
            extra.append(got[j - 1])
            j -= 1
    return pairs, extra[::-1]


def events_line(rec):
    """A record's touches as tool/versus/replay.sh reads them, with the time each was made."""
    parts, ms = [], 0
    for t in rec["touches"]:
        if t["kind"] == "mark":
            many = sum(1 for u in rec["touches"] if u["kind"] == "mark" and u["word"] == t["word"] and u["meant"] == t["meant"]) > 1
            if many:
                if t.get("types"):
                    parts.append("M:%s:%d" % (t["meant"], ms))
            else:
                parts.append("T:%.1f:%.1f:%d" % (t["x"], t["y"], ms))
        else:
            parts.append("T:%.1f:%.1f:%d" % (t["x"], t["y"], ms))
        ms += int(round(t["period"] * 1000))
    return " ".join(parts)


def replay(records, size):
    """Ghost Keyboard's decoder on the JVM over [records]' touches: {(thumb, line): (text, keys' own text, fixes)}."""
    lines = ["%s/%d\t%s" % (r["thumb"], r["line"], events_line(r)) for r in records]
    p = subprocess.run(["sh", os.path.join(HERE, "replay.sh"), "replay"] + [str(v) for v in size], input="\n".join(lines) + "\n", stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    if p.returncode != 0:
        raise SystemExit("score: replay.sh failed: " + p.stderr[-600:])
    out = {}
    for line in p.stdout.splitlines():
        f = line.split("\t")
        if len(f) >= 3 and "/" in f[0]:
            thumb, n = f[0].split("/")
            out[(thumb, int(n))] = (f[1], f[2], f[3] if len(f) > 3 else "", int(f[4]) if len(f) > 4 and f[4].isdigit() else 0)
    return out


def landed(rec):
    """For each touch that is a letter or a space: the key it landed on, as run.py worked it out when it placed
    the touch (Gboard's measured cells; Ghost Keyboard's own reading of a touch)."""
    return [t.get("on") for t in rec["touches"] if t["kind"] != "mark"]


def keys_text(rec):
    """What the keys themselves gave for a record's touches, a character a touch: for holding the JVM's reading of
    each touch against the one run.py placed it by."""
    out = []
    for t in rec["touches"]:
        if t["kind"] == "mark":
            many = sum(1 for u in rec["touches"] if u["kind"] == "mark" and u["word"] == t["word"] and u["meant"] == t["meant"]) > 1
            if not many or t.get("types"):
                out.append(t["meant"])
        else:
            out.append(t.get("on") or "?")
    return "".join(out)


def classes(rec, on):
    """For each word of the sentence: did the thumb type it as meant? And what its touches landed on."""
    n = len(rec["words"])
    clean = [True] * n
    space_trouble = [False] * n
    order = [None] * n
    for w, kind in rec["slips"]:
        if kind == "space missed":
            for k in (w, w + 1):
                if k < n:
                    clean[k] = False
                    space_trouble[k] = True
        else:
            clean[w] = False
            order[w] = kind
    typed = [""] * n
    off = [0] * n
    touches = [t for t in rec["touches"] if t["kind"] != "mark"]
    for t, c in zip(touches, on):
        w = t["word"]
        if t["kind"] == "key":
            typed[w] += c if c else "?"
            if c != t["meant"]:
                clean[w] = False
                if c == " ":
                    space_trouble[w] = True
                else:
                    off[w] += 1
        else:
            if c != " ":
                for k in (w, w + 1):
                    if k < n:
                        clean[k] = False
                        space_trouble[k] = True
                typed[w] += (c if c else "?")
    return clean, typed, off, space_trouble, order


def gaps(rec):
    """Milliseconds from one key press's mark on the field to the next, for the pace that was really kept."""
    times = [t for t, _ in rec["typed"]]
    return [b - a for a, b in zip(times, times[1:]) if 0 < b - a < 2000]


def interval(values):
    v = sorted(values)
    return v[int(0.025 * (len(v) - 1))], v[int(0.975 * (len(v) - 1))]


def category(word, original, clean, off, space_trouble, order, first=False):
    if clean:
        return "a right word changed"
    if space_trouble:
        return "the space: run into its neighbour, or split"
    if order:
        return "a letter %s" % order
    if "'" in word:
        return "a contraction"
    if original[:1].isupper() and word != "i" and not first:
        return "a name or other capital"
    if len(word) <= 3:
        return "a short word (three letters or fewer)"
    if re.search(r"(.)\1", word):
        return "a word with a doubled letter"
    if off >= 2:
        return "two or more keys off"
    return "one key off"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run")
    ap.add_argument("--no-replay", action="store_true")
    ap.add_argument("--fixtures", action="store_true")
    ap.add_argument("--resamples", type=int, default=2000)
    args = ap.parse_args()

    runs, metas = {}, {}
    for name in sorted(os.listdir(args.run)):
        if name.endswith(".jsonl"):
            kb = name[:-6]
            runs[kb] = {}
            for line in open(os.path.join(args.run, name)):
                r = json.loads(line)
                runs[kb][(r["thumb"], r["line"])] = r
            meta = os.path.join(args.run, kb + ".meta.json")
            metas[kb] = json.load(open(meta)) if os.path.exists(meta) else {}
    if not runs:
        raise SystemExit("score: no <keyboard>.jsonl in " + args.run)
    keyboards = [k for k in ("ghost", "gboard", "swiftkey") if k in runs] + [k for k in runs if k not in ("ghost", "gboard", "swiftkey")]

    jvm = {}
    if "ghost" in runs and not args.no_replay:
        dev = metas["ghost"].get("device", {})
        jvm = replay(list(runs["ghost"].values()), (dev.get("width", 1248), dev.get("height", 1972), dev.get("dpi", 420)))

    # Every word of every sentence, for every keyboard.
    table = {}   # (kb, thumb, line) -> dict
    for kb in keyboards:
        for key, rec in runs[kb].items():
            on = landed(rec)
            clean, typed, off, space_trouble, order = classes(rec, on)
            got = out_words(rec["out"])
            pairs, extra = align(rec["words"], got)
            right = [p == w for p, w in zip(pairs, rec["words"])]
            table[(kb,) + key] = {"rec": rec, "clean": clean, "typed": typed, "off": off, "space": space_trouble, "order": order, "pairs": pairs, "extra": extra, "right": right}

    thumbs = [t for t in THUMB_ORDER if any(k[1] == t for k in table)] + sorted({k[1] for k in table} - set(THUMB_ORDER))
    results = {"keyboards": {kb: metas.get(kb, {}) for kb in keyboards}, "thumbs": {}, "resamples": args.resamples}
    rnd = random.Random(20261111)
    md = []
    for thumb in thumbs:
        # Only sentences every keyboard typed with this thumb are compared.
        lines = sorted(set.intersection(*[{k[2] for k in table if k[0] == kb and k[1] == thumb} for kb in keyboards]))
        if not lines:
            continue
        per = {}
        for kb in keyboards:
            rows = []
            for line in lines:
                e = table[(kb, thumb, line)]
                n = len(e["right"])
                wrong = sum(1 for r in e["right"] if not r)
                slipped = sum(1 for c in e["clean"] if not c)
                left = sum(1 for r, c in zip(e["right"], e["clean"]) if not r and not c)
                spoiled = sum(1 for r, c in zip(e["right"], e["clean"]) if not r and c)
                rows.append((n, wrong, left, spoiled, slipped, n - slipped, 1 if wrong == 0 else 0, len(e["extra"])))
            per[kb] = rows

        def rates(rows):
            n = sum(r[0] for r in rows)
            slipped = sum(r[4] for r in rows)
            cleanw = sum(r[5] for r in rows)
            return {
                "wrong": 100.0 * sum(r[1] for r in rows) / n,
                "left": 100.0 * sum(r[2] for r in rows) / n,
                "spoiled": 100.0 * sum(r[3] for r in rows) / n,
                "mended": 100.0 * (slipped - sum(r[2] for r in rows)) / slipped if slipped else 0.0,
                "spoiled_per_1000": 1000.0 * sum(r[3] for r in rows) / cleanw if cleanw else 0.0,
                "sentences_right": 100.0 * sum(r[6] for r in rows) / len(rows),
            }

        point = {kb: rates(per[kb]) for kb in keyboards}
        boot = {kb: collections.defaultdict(list) for kb in keyboards}
        diff = collections.defaultdict(list)
        for _ in range(args.resamples):
            pick = [rnd.randrange(len(lines)) for _ in lines]
            drawn = {kb: rates([per[kb][i] for i in pick]) for kb in keyboards}
            for kb in keyboards:
                for k, v in drawn[kb].items():
                    boot[kb][k].append(v)
            for kb in keyboards[1:]:
                for k in ("wrong", "left", "spoiled"):
                    diff[(kb, k)].append(drawn[keyboards[0]][k] - drawn[kb][k])
        n_words = sum(r[0] for r in per[keyboards[0]])
        res = {"sentences": len(lines), "words": n_words, "keyboards": {}}
        for kb in keyboards:
            rows = per[kb]
            touches = [t for line in lines for t in table[(kb, thumb, line)]["rec"]["touches"] if t["kind"] != "mark"]
            pace = sorted(g for line in lines for g in gaps(table[(kb, thumb, line)]["rec"]))
            res["keyboards"][kb] = {
                "words_wrong": sum(r[1] for r in rows), "left_wrong": sum(r[2] for r in rows), "spoiled": sum(r[3] for r in rows),
                "slipped": sum(r[4] for r in rows), "typed_as_meant": sum(r[5] for r in rows), "words_added": sum(r[7] for r in rows),
                "rates": point[kb], "intervals": {k: interval(v) for k, v in boot[kb].items()},
                "taps": len(touches), "taps_moved_by_guard": sum(1 for t in touches if t.get("moved")),
                "ms_between_key_presses": {"median": pace[len(pace) // 2] if pace else 0, "5%": pace[int(0.05 * len(pace))] if pace else 0, "95%": pace[int(0.95 * len(pace))] if pace else 0},
            }
        res["gaps"] = {kb: {k: {"gap": point[keyboards[0]][k] - point[kb][k], "interval": interval(diff[(kb, k)])} for k in ("wrong", "left", "spoiled")} for kb in keyboards[1:]}
        results["thumbs"][thumb] = res

    # The tables.
    names = {kb: metas.get(kb, {}).get("name", kb) for kb in keyboards}
    md.append("| Thumb | Keyboard | Words wrong | of them left wrong | of them spoiled | Slips mended | Right words spoiled per 1,000 | Sentences all right |")
    md.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for thumb, res in results["thumbs"].items():
        for kb in keyboards:
            k = res["keyboards"][kb]
            r, ci = k["rates"], k["intervals"]
            md.append("| %s | %s | **%.1f%%** (%.1f to %.1f) | %.1f%% (%d) | %.1f%% (%d) | %.0f%% of %d | %.1f (%.1f to %.1f) | %.0f%% |" % (
                thumb, names[kb], r["wrong"], ci["wrong"][0], ci["wrong"][1], r["left"], k["left_wrong"], r["spoiled"], k["spoiled"],
                r["mended"], k["slipped"], r["spoiled_per_1000"], ci["spoiled_per_1000"][0], ci["spoiled_per_1000"][1], r["sentences_right"]))
    md.append("")
    if len(keyboards) > 1:
        md.append("| Thumb | %s wrong less the other's (points) | 95%% interval | left wrong | spoiled |" % names[keyboards[0]])
        md.append("| --- | ---: | ---: | ---: | ---: |")
        for thumb, res in results["thumbs"].items():
            for kb in keyboards[1:]:
                g = res["gaps"][kb]
                md.append("| %s, against %s | %+.1f | %+.1f to %+.1f | %+.1f (%+.1f to %+.1f) | %+.1f (%+.1f to %+.1f) |" % (
                    thumb, names[kb], g["wrong"]["gap"], g["wrong"]["interval"][0], g["wrong"]["interval"][1],
                    g["left"]["gap"], g["left"]["interval"][0], g["left"]["interval"][1], g["spoiled"]["gap"], g["spoiled"]["interval"][0], g["spoiled"]["interval"][1]))
        md.append("")

    # The JVM against the emulator.
    agreement = None
    if jvm:
        same = same_keys = 0
        rows = []
        for key, rec in sorted(runs["ghost"].items()):
            if key not in jvm:
                continue
            text, plain, fixes, unhandled = jvm[key]
            if text == rec["out"]:
                same += 1
            else:
                rows.append("%s\t%d\t%s\t%s\t%s\t%s" % (key[0], key[1], rec["target"], rec["out"], text, fixes))
        words_same = words_all = 0
        for key, rec in runs["ghost"].items():
            if key in jvm:
                a, _ = align(rec["words"], out_words(rec["out"]))
                b, _ = align(rec["words"], out_words(jvm[key][0]))
                words_all += len(rec["words"])
                words_same += sum(1 for x, y in zip(a, b) if x == y)
        # The keys' own text on the JVM (no autocorrect), a character a touch, against the key each touch was placed on.
        taps_all = taps_same = 0
        for key, rec in runs["ghost"].items():
            if key in jvm:
                mine, theirs = keys_text(rec), jvm[key][1].lower()
                if len(mine) == len(theirs):
                    taps_all += len(mine)
                    # A second space within a second is a full stop and a space on the keyboard: still the bar.
                    taps_same += sum(1 for a, b in zip(mine, theirs) if a == b or (a == " " and b == "."))
        agreement = {"sentences": len(jvm), "same_text": same, "words": words_all, "same_words": words_same, "touches": taps_all, "touches_read_the_same": taps_same}
        results["jvm_agreement"] = agreement
        with open(os.path.join(args.run, "agreement.tsv"), "w") as f:
            f.write("thumb\tline\tsentence\ton the emulator\ton the JVM\tthe JVM's fixes\n" + "\n".join(rows) + ("\n" if rows else ""))
        md.append("Ghost Keyboard's decoder on the JVM, fed the same taps: the field's text is the same, character for character, in %d of %d sentences (%.1f%%); %d of %d words come out the same (%.2f%%)." % (
            same, len(jvm), 100.0 * same / max(1, len(jvm)), words_same, words_all, 100.0 * words_same / max(1, words_all)))
        md.append("")

    # Checks on the run itself.
    checks = {}
    for kb in keyboards:
        recs = list(runs[kb].values())
        second = [r for r in recs if "uiautomator" in r]
        odd = [r for r in recs if re.search(r"[^A-Za-z ,.?!'’\n-]", r["out"])]
        checks[kb] = {"sentences": len(recs), "read_twice": len(second), "read_twice_same": sum(1 for r in second if r["uiautomator"] == r["out"]), "with_other_characters": len(odd)}
    results["checks"] = checks

    # Where another keyboard got the word and Ghost Keyboard did not.
    losses = []
    counts = collections.Counter()
    other_way = collections.Counter()
    if "ghost" in keyboards and len(keyboards) > 1:
        for thumb in results["thumbs"]:
            for (kb, t, line), e in sorted(table.items()):
                if kb != "ghost" or t != thumb:
                    continue
                rec = e["rec"]
                originals = [re.sub(r"[,.?!]", "", w) for w in rec["target"].replace("’", "'").split(" ")]
                for other in keyboards[1:]:
                    o = table.get((other, thumb, line))
                    if not o:
                        continue
                    for j, word in enumerate(rec["words"]):
                        if o["right"][j] and e["right"][j]:
                            continue
                        if e["right"][j] and not o["right"][j]:
                            other_way[(thumb, other)] += 1
                            continue
                        if not o["right"][j]:
                            continue
                        cat = category(word, originals[j] if j < len(originals) else word, e["clean"][j], e["off"][j], e["space"][j], e["order"][j], first=j == 0)
                        left = e["pairs"][j] or "(nothing)"
                        how = "left as typed" if left == e["typed"][j].strip() else "changed to another word"
                        counts[(cat, thumb)] += 1
                        losses.append({"category": cat, "outcome": how, "thumb": thumb, "line": line, "word": j, "meant": word, "typed": e["typed"][j], "ghost": left, "other": other, "others": o["pairs"][j], "sentence": rec["target"], "ghost_field": rec["out"], "other_field": o["rec"]["out"], "events": events_line(rec)})
    results["losses"] = {"count": len(losses), "by_category": {}, "other_way": {"%s/%s" % k: v for k, v in other_way.items()}}
    by_cat = collections.Counter(l["category"] for l in losses)
    for cat, n in by_cat.most_common():
        results["losses"]["by_category"][cat] = {"count": n, "by_thumb": {t: counts[(cat, t)] for t in results["thumbs"]}, "left_as_typed": sum(1 for l in losses if l["category"] == cat and l["outcome"] == "left as typed")}
    cols = ["category", "outcome", "thumb", "line", "word", "meant", "typed", "ghost", "other", "others", "sentence", "ghost_field", "other_field", "events"]
    with open(os.path.join(args.run, "losses.tsv"), "w") as f:
        f.write("\t".join(cols) + "\n")
        for l in sorted(losses, key=lambda l: (-by_cat[l["category"]], l["category"], THUMB_ORDER.index(l["thumb"]) if l["thumb"] in THUMB_ORDER else 9, l["line"], l["word"])):
            f.write("\t".join(str(l[c]) for c in cols) + "\n")
    if args.fixtures and losses:
        os.makedirs(os.path.join(HERE, "fixtures"), exist_ok=True)
        with open(os.path.join(HERE, "fixtures", "ghost-lost-to-%s.tsv" % "-".join(keyboards[1:])), "w") as f:
            f.write("# Words another keyboard got and Ghost Keyboard did not (tool/versus/score.py --fixtures). tool/versus/fixtures.py types\n")
            f.write("# each sentence's taps through the decoder on the JVM again and says which words are right now.\n")
            f.write("\t".join(cols) + "\n")
            for l in sorted(losses, key=lambda l: (-by_cat[l["category"]], l["category"], l["thumb"], l["line"], l["word"])):
                f.write("\t".join(str(l[c]) for c in cols) + "\n")

    if losses:
        md.append("| What went wrong (Ghost Keyboard wrong, %s right) | Words | careful | leaning | sloppy | Left as typed |" % " or ".join(names[k] for k in keyboards[1:]))
        md.append("| --- | ---: | ---: | ---: | ---: | ---: |")
        for cat, v in results["losses"]["by_category"].items():
            md.append("| %s | %d | %d | %d | %d | %d |" % (cat, v["count"], v["by_thumb"].get("careful", 0), v["by_thumb"].get("leaning", 0), v["by_thumb"].get("sloppy", 0), v["left_as_typed"]))
        md.append("")

    with open(os.path.join(args.run, "results.json"), "w") as f:
        json.dump(results, f, indent=1)
        f.write("\n")
    with open(os.path.join(args.run, "results.md"), "w") as f:
        f.write("\n".join(md) + "\n")
    print("\n".join(md))
    for kb in keyboards:
        c = checks[kb]
        print("%s: %d sentences; read a second way %d times, the same %d; with a character that should not be there: %d" % (names[kb], c["sentences"], c["read_twice"], c["read_twice_same"], c["with_other_characters"]))
    for thumb, res in results["thumbs"].items():
        for kb in keyboards:
            k = res["keyboards"][kb]
            print("%s, %s: %d taps, %d moved off a key that is not a letter (%.1f%%); %d ms between key presses (5%% to 95%%: %d to %d)" % (
                thumb, names[kb], k["taps"], k["taps_moved_by_guard"], 100.0 * k["taps_moved_by_guard"] / max(1, k["taps"]),
                k["ms_between_key_presses"]["median"], k["ms_between_key_presses"]["5%"], k["ms_between_key_presses"]["95%"]))
    if results["losses"]["other_way"]:
        print("the other way (Ghost Keyboard right, the other wrong):", results["losses"]["other_way"])


if __name__ == "__main__":
    main()
