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

  --final          also types a third set, kept for the end: 300 more held-out sentences from line 101,001 on
                   (the file has 102,028 lines; until 2026-11-12 this said line 110,001, where there is nothing).
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
  --no-names       types as a service that does not ask names for their capitals (Replay.kt's own; the bench's
                   -Dnames=false): what every build up to 0.16.3 did, and what a field that capitalises nothing or
                   a person with "Capitals at sentence starts" off still gets (DevKeyboardService.namesWanted)

  --beside CODES   also types both sets as a person with a second language on has them (CODES are its word lists'
                   stems, a comma between: de,es,fr), and prints each set's lines again for each. English's list of
                   names loses some beside another language (Autocorrect.load, dict/en_capitals_beside.tsv), and
                   every word is read against both lists. No emulator run was typed that way: these are the JVM's
                   alone, held against nothing

Under each set's line, its capitals, which the word score leaves aside: of the names the field has the right
letters of (the sentence's words after its first that are written with a capital: Tom, Algeria, Monday; not I),
how many have their capital; and of the words the sentence writes in lower case, how many the field has with a
capital, which is a right word spoiled. The thumb types everything in lower case, so a capital is the keyboard's
doing. --against says which names gained and lost their capital, and which words were given one amiss. Gboard's
capitals are beside the seen set's, and beside the unseen and the final set's where it has been given them.

Under that, what those two counts cannot show. The names again with Tom and Mary left out, and by how many
different names there are: the two are Tatoeba's stock names, a third of the names in these sentences, and a
keyboard that gave a capital to them alone would look well here. The capitals on words that came out wrong (a
slip that spells a listed name, or is mended into one: "It had Ben a long meeting"), which neither count sees
because each goes by words with the right letters; --show lists them. And the names two words long (Western
Sahara, New York), by whether both words have their capital or one alone: a list of single words gives such a
name half ("western Sahara"), and the first count takes the half for a name given.

Ghost Keyboard alone, no emulator. The replay is the emulator's to the character on the seen set (score.py's
count: 900 of 900), which is what lets a JVM stand in for it.
"""
import argparse
import collections
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
FINAL_FIRST = 101000


def kept(name):
    with gzip.open(os.path.join(RUN, name), "rt", encoding="utf-8") as f:
        return [r for r in (json.loads(line) for line in f) if not r.get("stalled")]


def other_records(name, keyboard="gboard"):
    """What another keyboard was given of the unseen or the final set and what it left in the field
    (tool/versus/other-sets.sh): its records, one a typing; None where it was never typed."""
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
                return list(records.values())
    return None


def other(name, keyboard="gboard"):
    """What another keyboard made of the unseen or the final set (tool/versus/other-sets.sh), as verdicts; None where it was never typed."""
    records = other_records(name, keyboard)
    if records is None:
        return None
    return {"%s/%d" % (r["thumb"], r["line"]): [[p == w, True, "", p or "", w] for p, w in zip(score.align(r["words"], score.out_words(r["out"]))[0], r["words"])] for r in records}


def other_capitals(name, keyboard="gboard"):
    """The same keyboard's capitals on that set ([capitals]); None where it was never typed."""
    records = other_records(name, keyboard)
    return None if records is None else capitals(records, lambda r: r["out"])


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


def capitals(records, text_of):
    """{"thumb/line": [the sentence's names as [the sentence writes it, the field has it], the words given a capital
    amiss the same way, how many words the sentence writes in lower case that the field has, the capitals on words
    that came out wrong as [what the sentence has there, the field has it], the names of two words the same way]}
    (score.names, score.capitals_amiss, score.capitals_astray, score.name_pairs). [text_of] gives a record's field."""
    out = {}
    for r in records:
        rec = {"target": r["target"], "out": text_of(r)}
        amiss, plain = score.capitals_amiss(rec)
        out["%s/%d" % (r["thumb"], r["line"])] = [[list(p) for p in score.names(rec)], [list(p) for p in amiss], plain,
                                                   [list(p) for p in score.capitals_astray(rec)], [list(p) for p in score.name_pairs(rec)]]
    return out


#: Tatoeba's two stock names. They are a third of the names in these sentences (Tom alone is 57 of the 219 the seen
#: ones hold) and next to none of anybody's messages, so the capitals are said with them and without: a list that
#: had Tom and nothing else would give "names their capital" two times in five here.
STOCK = ("Tom", "Mary")


def capitals_line(name, c, beside=None):
    def said(c):
        found = [p for v in c.values() for p in v[0]]
        with_capital = sum(1 for _, got in found if got[:1].isupper())
        return "names given their capital %d of %d (%.1f%%), a capital given amiss %d of %d words" % (
            with_capital, len(found), 100.0 * with_capital / max(1, len(found)), sum(len(v[1]) for v in c.values()), sum(v[2] for v in c.values()))
    text = "%-8s %s%s" % (name, said(c), "   | Gboard " + said(beside) if beside is not None else "")
    if beside is not None and len(beside) < len(c):
        # Gboard has not been given every typing of this set yet: the same typings, side by side (as [line] has the words).
        text += "\n         over the %d typings Gboard has had: this %s" % (len(beside), said({k: c[k] for k in beside if k in c}))
    return text


def capitals_more(c, beside=None):
    """A second line under [capitals_line]'s, for what its two counts cannot show: the names with Tatoeba's stock
    names left out ([STOCK]) and by how many different names there are; the capitals on words that came out wrong,
    which neither count sees; and the names of two words, by how many of their words have a capital. None where
    the capitals were saved before these were kept."""
    def said(c):
        if any(len(v) < 5 for v in c.values()):
            return None
        found = [p for v in c.values() for p in v[0]]
        rest = [got for w, got in found if w not in STOCK]
        given = sum(1 for got in rest if got[:1].isupper())
        kinds = {w for w, _ in found}
        kinds_given = {w for w, got in found if got[:1].isupper()}
        astray = [p for v in c.values() for p in v[3]]
        pairs = [got.split(" ") for v in c.values() for _, got in v[4]]
        both = sum(1 for a, b in pairs if a[:1].isupper() and b[:1].isupper())
        one = sum(1 for a, b in pairs if a[:1].isupper() != b[:1].isupper())
        return "%s aside %d of %d (%.1f%%), %d of %d different names; a capital on a word that came out wrong %d (%d where the sentence has a name); of %d names two words long, both words have theirs in %d and one alone in %d" % (
            " and ".join(STOCK), given, len(rest), 100.0 * given / max(1, len(rest)), len(kinds_given), len(kinds),
            len(astray), sum(1 for there, _ in astray if there[:1].isupper()), len(pairs), both, one)
    mine = said(c)
    if mine is None:
        return None
    theirs = said(beside) if beside is not None else None
    return "%-8s %s%s" % ("", mine, "   | Gboard " + theirs if theirs else "")


def capital_changes(now, then, show):
    """Names that gained or lost their capital since [then], and words given one amiss that were not: a name counts
    where both typings have its letters, so that a word mended or broken is not also a capital won or lost. With
    [show], the capitals now on words that came out wrong are listed too."""
    gained, lost, amiss, righted, astray = [], [], [], [], []
    for key in now:
        if key not in then:
            continue
        has, had = collections.Counter(n for n, _ in now[key][0]), collections.Counter(n for n, _ in then[key][0])
        with_now = collections.Counter(n for n, got in now[key][0] if got[:1].isupper())
        with_then = collections.Counter(n for n, got in then[key][0] if got[:1].isupper())
        for name in has:
            both = min(has[name], had[name])
            more = min(with_now[name], both) - min(with_then[name], both)
            gained += [name] * max(0, more)
            lost += [name] * max(0, -more)
        before = collections.Counter(got for _, got in then[key][1])
        after = collections.Counter(got for _, got in now[key][1])
        amiss += ["%s (the sentence has %s)" % (got, word) for word, got in now[key][1] if before[got] == 0]
        righted += [got for _, got in then[key][1] if after[got] == 0]
        if len(now[key]) > 3:
            astray += ["%s (the sentence has %s)" % (got, there or "another word") for there, got in now[key][3]]
    text = "%d names gained their capital, %d lost it; %d words given a capital amiss that were not, %d no longer" % (len(gained), len(lost), len(amiss), len(righted))
    if show:
        def few(items):
            return ", ".join("%s%s" % (w, " x%d" % n if n > 1 else "") for w, n in collections.Counter(items).most_common(show))
        text += ("\n        gained  " + few(gained) if gained else "") + ("\n        LOST    " + few(lost) if lost else "") + ("\n        AMISS   " + few(amiss) if amiss else "") + ("\n        on a wrong word  " + few(astray) if astray else "")
    return text


def bench(overlay, java, dials):
    """The typing bench's tap test on its own sentences: the "by the taps" lines."""
    src = "android/app/src/main/kotlin/com/infamousvague/devkeyboard"
    files = [src + "/" + n for n in ("Languages.kt", "Layouts.kt", "Hangul.kt", "Autocorrect.kt", "SwipeDecoder.kt", "TapDecoder.kt", "ThumbLean.kt", "MistakeMemory.kt", "SampleCleaner.kt", "NextWordModel.kt")]
    if overlay:
        files = [os.path.join(overlay, os.path.basename(f)) if os.path.exists(os.path.join(overlay, os.path.basename(f))) else f for f in files]
    jar = os.path.join("build", "versus", "bench-%s.jar" % (os.path.basename(overlay.rstrip("/")) if overlay else "tree"))
    stale = not os.path.exists(os.path.join(ROOT, jar)) or any(os.path.getmtime(os.path.join(ROOT, f)) > os.path.getmtime(os.path.join(ROOT, jar)) for f in files + ["tool/typing_bench/Bench.kt"])
    if stale:
        subprocess.run(["kotlinc"] + files + ["tool/lang_check/stubs/android/graphics/PointF.kt", "tool/typing_bench/Bench.kt", "-include-runtime", "-d", jar], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # The bench's own short names for the two classes it has always set; any other class's dial by its full name.
    sets = ",".join(("A." + d.split(".", 1)[1]) if d.startswith("Autocorrect.") else ("T." + d.split(".", 1)[1]) if d.startswith("TapDecoder.") else d for d in dials)
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
    ap.add_argument("--no-names", action="store_true")
    ap.add_argument("--beside", default="")
    ap.add_argument("--unseen-first", type=int, default=UNSEEN_FIRST)
    ap.add_argument("--sentences", type=int, default=300)
    args = ap.parse_args()
    board = score.board_file(GEOMETRY)
    flags = (["--marks-without-taps"] if args.marks_without_taps else []) + (["--no-names"] if args.no_names else [])
    size = (1248, 1972, 420)

    def typed(records, more=()):
        """(every word's verdict, the capitals) of [records] typed through this tree's decoder."""
        texts = score.replay(records, size, board=board, flags=flags + list(more), dials=args.set, overlay=args.overlay, java=args.java)
        return verdicts(records, texts), capitals(records, lambda r: texts[(r["thumb"], r["line"])][0])

    def said(name, words, caps, words_beside=None, caps_beside=None):
        """A set's lines: its words, its capitals, and what the capitals' two counts leave out."""
        print(line(name, words, words_beside))
        print(capitals_line("", caps, caps_beside))
        more = capitals_more(caps, caps_beside)
        if more:
            print(more)
    now = {}
    seen_records, unseen_records = kept("ghost.jsonl.gz"), unseen(args.unseen_first, args.sentences)
    now["seen"], now["seen capitals"] = typed(seen_records)
    now["unseen"], now["unseen capitals"] = typed(unseen_records)
    theirs = kept("gboard.jsonl.gz")
    gboard = {"%s/%d" % (r["thumb"], r["line"]): [[p == w, True, "", p or "", w] for p, w in zip(score.align(r["words"], score.out_words(r["out"]))[0], r["words"])] for r in theirs}
    said("seen", now["seen"], now["seen capitals"], gboard, capitals(theirs, lambda r: r["out"]))
    said("unseen", now["unseen"], now["unseen capitals"], other("unseen") if args.unseen_first == UNSEEN_FIRST else None, other_capitals("unseen") if args.unseen_first == UNSEEN_FIRST else None)
    if args.final:
        last = unseen(FINAL_FIRST, args.sentences)
        if len(last) != 3 * args.sentences:
            raise SystemExit("scoreboard: the final set has %d typings, not %d: the held-out file has no %d sentences after line %d" % (len(last), 3 * args.sentences, args.sentences, FINAL_FIRST))
        # Totals only, of the words and of the capitals: neither is kept in `now`, so --save never writes its words.
        words, caps = typed(last)
        said("final", words, caps, other("final"), other_capitals("final"))
    # A second language on: the same taps, read against both word lists, with the names English keeps beside it.
    for code in [c for c in args.beside.split(",") if c]:
        for name, records in (("seen", seen_records), ("unseen", unseen_records)):
            words, caps = typed(records, ("--beside", code))
            said("%s +%s" % (name, code), words, caps)
            now["%s beside %s" % (name, code)], now["%s capitals beside %s" % (name, code)] = words, caps
    folder = os.path.join(ROOT, "build", "versus", "scoreboard")
    if args.against:
        then = json.load(open(os.path.join(folder, args.against + ".json")))
        for name in ("seen", "unseen"):
            print("    %s, against %s: %s" % (name, args.against, changes(now[name], then[name], args.show)))
            # A save from before the capitals were counted has none to hold these against.
            if name + " capitals" in then:
                print("    %s, against %s: %s" % (name, args.against, capital_changes(now[name + " capitals"], then[name + " capitals"], args.show)))
    if args.save:
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, args.save + ".json"), "w") as f:
            json.dump(now, f)
    if args.bench:
        for ln in bench(args.overlay, ((args.java or "") + " -Dnames=false").strip() if args.no_names else args.java, args.set):
            print("bench    " + " ".join(ln.split()))


if __name__ == "__main__":
    main()
