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
sys.path.insert(0, HERE)
import timing  # noqa: E402
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


NAMED_KEYS = {"space": ("SPACE", " ", ""), "shift": ("SHIFT", "", ""), "delete": ("DELETE", "", ""), "enter": ("ENTER", "", ""), "emoji": ("EMOJI", "", ""),
              "mic": ("MIC", "", ""), "globe": ("GLOBE", "", ""), "symbols": ("MODE", "", "?123"), "dev": ("MODE", "", "{ }"), "letters": ("MODE", "", "ABC")}


def board_file(geometry):
    """The letters page a run was typed on, as tool/versus/replay/Replay.kt reads it (Board.recorded): written to
    build/versus/boards/ from the run's geometry file [geometry] (a path), whose touch model lists every key's cell
    in the order the keyboard laid them out, and the offsets it read touches by. A keyboard's layout changes from
    one version to the next (0.16.2 gave the space bar the { } key's place), and taps aimed at one are other keys
    on another: a run is replayed on the keyboard it was typed on. None where the file has no such model."""
    geo = json.load(open(geometry))
    model = geo.get("touch_model") or {}
    if model.get("kind") != "ghost-keyboard-view":
        return None
    cells = model["cells"]
    unit = sorted(r[2] - r[0] for name, r in cells if len(name) == 1 and name.isalpha())[len([1 for name, _ in cells if len(name) == 1 and name.isalpha()]) // 2]
    rows = []
    for name, r in cells:
        if not rows or abs(rows[-1][0][1][1] - r[1]) > 1.0:
            rows.append([])
        rows[-1].append((name, r))

    def points(text):
        return "+".join(str(ord(c)) for c in text)
    most = 0.0
    made = []
    for row in rows:
        keys = []
        for name, r in row:
            kind, text, label = NAMED_KEYS.get(name, ("CHAR", name, "") if len(name) == 1 else ("MODE", "", name))
            keys.append("%s|%s|%s|%s" % (kind, points(text), points(label), round((r[2] - r[0]) / unit * 2) / 2.0))
        across = sum(float(k.rsplit("|", 1)[1]) for k in keys)
        most = max(most, across)
        made.append((across, keys))
    dev = geo["device"]
    out = ["# the letters page a versus run was typed on, from %s (tool/versus/score.py board_file)" % os.path.basename(geometry),
           "screen\t%d\t%d\t%d\t%d" % (dev["width"], dev["height"], dev["dpi"], int(-(-48 * dev["dpi"] // 160)))]
    out += ["offset\t%s\t%r\t%r" % (c, v[0], v[1]) for c, v in sorted(model["offsets_dp"].items())]
    out += ["row\t%s\t%s" % ((most - across) / 2.0, "\t".join(keys)) for across, keys in made]
    out += ["cell\t%s\t%s" % (name, "\t".join(repr(float(v)) for v in r)) for name, r in cells]
    folder = os.path.join(ROOT, "build", "versus", "boards")
    os.makedirs(folder, exist_ok=True)
    text = "\n".join(out) + "\n"
    import hashlib
    path = os.path.join(folder, "%s-%s.board" % (os.path.basename(geometry)[:-5] if geometry.endswith(".json") else os.path.basename(geometry), hashlib.sha256(text.encode("utf-8")).hexdigest()[:10]))
    if not os.path.exists(path):
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
    return path


def replay(records, size, board=None, flags=(), dials=(), overlay=None, java=None):
    """Ghost Keyboard's decoder on the JVM over [records]' touches: {(thumb, line): (text, keys' own text, fixes)}.
    [board]: the keys as they were when the touches were made ([board_file]); without it, as this tree lays them out.
    [flags]: Replay.kt's own, "--marks-without-taps" for a run typed on a build from before 2026-11-12 (a word
    ended from the ?123 page was read without its taps then). [dials]: the decoder's own numbers for this run, as
    "TapDecoder.BUDGET=8" (Replay.kt's --set). [overlay]: a folder of source files to compile in place of the tree's
    of the same name (replay.sh's VERSUS_OVERLAY), and [java] options for the JVM that runs them: a change tried
    without being made."""
    lines = ["%s/%d\t%s" % (r["thumb"], r["line"], events_line(r)) for r in records]
    extra = (["--board", board] if board else []) + list(flags) + [x for dial in dials for x in ("--set", dial)]
    env = dict(os.environ)
    env.pop("VERSUS_OVERLAY", None)
    if overlay:
        env["VERSUS_OVERLAY"] = overlay
    if java:
        env["JAVA_OPTS"] = java
    p = subprocess.run(["sh", os.path.join(HERE, "replay.sh"), "replay"] + [str(v) for v in size] + extra, input="\n".join(lines) + "\n", stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, env=env)
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
    """Milliseconds from one touch to the next, for the pace that was really kept: by the emulator's clock where
    the record has it (a touch going down to the next going down, marks from the ?123 page aside), else by the
    times the field marked each key press at."""
    if rec.get("clock") and len(rec["clock"]) == len(rec["touches"]):
        return [b[0] - a[0] for a, b, t, u in zip(rec["clock"], rec["clock"][1:], rec["touches"], rec["touches"][1:]) if t["kind"] != "mark" and u["kind"] != "mark"]
    times = timing.presses(rec)
    return [b - a for a, b in zip(times, times[1:]) if 0 < b - a < 2000]


def interval(values):
    v = sorted(values)
    return v[int(0.025 * (len(v) - 1))], v[int(0.975 * (len(v) - 1))]


def word_list():
    """Ghost Keyboard's English word list as its files have it (assets/dict/en.tsv, the contractions and the slang
    list): {word: how common, on the list's own scale}. For saying of a lost word whether the keyboard could have
    known it at all; the keyboard's own test (Autocorrect.isWord) knows a little more than the files say."""
    words = {}
    base = os.path.join(ROOT, "android", "app", "src", "main", "assets", "dict")
    try:
        for line in open(os.path.join(base, "en.tsv"), encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            if len(f) >= 2:
                try:
                    words[f[0].lower()] = float(f[1])
                except ValueError:
                    pass
        for line in open(os.path.join(base, "en_contractions.tsv"), encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            if len(f) == 2:
                words.setdefault(f[1].lower(), 0.0)
        for line in open(os.path.join(base, "en_slang.txt"), encoding="utf-8"):
            for w in line.split():
                if not w.startswith("#"):
                    words.setdefault(w.lower(), 0.0)
    except OSError:
        pass
    return words


def category(word, original, clean, off, space_trouble, order, first=False):
    """What the thumb did to a word the keyboard then got wrong: the first of these that is true of it."""
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
    if off >= 3:
        return "three or more keys off"
    if off == 2:
        return "two keys off"
    return "one key off"


def tokens(text):
    """A text's words as they are written, the marks about them gone."""
    return [w for w in (re.sub(r"^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "", t) for t in text.replace("’", "'").split()) if w]


def names_at(rec):
    """The sentence's names the field has the right letters of, each as (its place in the sentence, its place in
    the field, the sentence writes it, the field has it): the sentence's words after its first that are written
    with a capital (Tom, Algeria, Monday; not I). The thumb types everything in lower case, so a capital is the
    keyboard's doing."""
    want, got = tokens(rec["target"]), tokens(rec["out"])
    lower = [w.lower() for w in got]
    out = []
    used = 0
    for j, w in enumerate(want):
        if j == 0 or not w[:1].isupper() or w == "I" or w.startswith("I'"):
            continue
        # The first place from where the last name was found: names are few, and in order.
        try:
            k = lower.index(w.lower(), used)
        except ValueError:
            continue
        used = k + 1
        out.append((j, k, w, got[k]))
    return out


def names(rec):
    """[names_at] without the places: each name as (the sentence writes it, the field has it)."""
    return [(w, got) for _, _, w, got in names_at(rec)]


def name_pairs(rec):
    """The names of several words among [names]: two of the sentence's names that stand next to each other and
    that the field has next to each other (Western Sahara, New York), each pair as (the sentence writes it, the
    field has it). A list of single words gives such a name half its capitals or none ("western Sahara"), and
    [names] counts the half as a name given."""
    found = {j: (k, w, got) for j, k, w, got in names_at(rec)}
    return [(found[j][1] + " " + found[j + 1][1], found[j][2] + " " + found[j + 1][2]) for j in sorted(found) if j + 1 in found and found[j + 1][0] == found[j][0] + 1]


def capitals(rec):
    """(names the field has with their capital, names it has at all): see [names]. The word score leaves capitals aside."""
    found = names(rec)
    return sum(1 for _, got in found if got[:1].isupper()), len(found)


def field_words(text):
    """A field's words as they stand, the marks about them gone, and for each whether it opens a sentence there:
    the first word, or one after a full stop, a question or an exclamation mark. Such a word's capital is the shift
    key's doing (a full stop may be the keyboard's own, from a second space typed within a second)."""
    got, opens = [], []
    start = True
    for t in text.replace("’", "'").split():
        w = re.sub(r"^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "", t)
        if w:
            got.append(w)
            opens.append(start)
        start = t[-1:] in ".?!…"
    return got, opens


def capitals_amiss(rec):
    """The other side of [names]: (words the field has with a capital where the sentence writes none, each as
    (the sentence writes it, the field has it); how many words the sentence writes in lower case that the field has
    the right letters of). The two are lined up word for word (the longest runs they share). A word that opens a
    sentence in the field ([field_words]) has the sentence's capital, which is not counted."""
    import difflib
    want = tokens(rec["target"])
    got, opens = field_words(rec["out"])
    amiss, plain = [], 0
    blocks = difflib.SequenceMatcher(None, [w.lower() for w in want], [w.lower() for w in got], autojunk=False).get_matching_blocks()
    for a, b, size in blocks:
        for j, k in zip(range(a, a + size), range(b, b + size)):
            if j == 0 or opens[k] or not want[j][:1].islower():
                continue
            plain += 1
            if got[k][:1].isupper():
                amiss.append((want[j], got[k]))
    return amiss, plain


def capitals_astray(rec):
    """What [names] and [capitals_amiss] both pass over, since each counts a word only where the field has the
    sentence's letters: the capitals on words that came out wrong, each as (what the sentence has there, the field
    has it). A slip that spells a listed name, or is mended into one, stands with a capital ("It had Ben a long
    meeting" for been, "Tim" for Tom). The word was wrong before names too, so no count of words moves by it, but
    it is what the person sees. What the sentence has there is "" where the two do not line up one word for one.
    Never I or I'm (which had their capital before names), nor a word that opens a sentence in the field."""
    import difflib
    want = tokens(rec["target"])
    got, opens = field_words(rec["out"])
    out = []
    for tag, a1, a2, b1, b2 in difflib.SequenceMatcher(None, [w.lower() for w in want], [w.lower() for w in got], autojunk=False).get_opcodes():
        if tag == "equal":
            continue
        for i, k in enumerate(range(b1, b2)):
            w = got[k]
            if opens[k] or not w[:1].isupper() or w == "I" or w.startswith("I'"):
                continue
            out.append((want[a1 + i] if a2 - a1 == b2 - b1 else "", w))
    return out


def at_the_press(rec):
    """What each letter's tap typed at the moment it was made, before any word was corrected: for every tap meant
    for a letter, (where it landed: "own", "other" letter's key, or the "bar"; what was typed: "meant", the key
    "under" it, "third", or "more" than a letter changed). None where the field's changes do not line up with the
    touches, one for one."""
    touches = [t for t in rec["touches"] if t["kind"] != "mark" or t.get("types", True)]
    presses, last = [], None
    for ms, text in rec["typed"]:
        if last is None or ms - last >= 40:
            presses.append([text, text])
        else:
            presses[-1][1] = text
        last = ms
    if len(presses) != len(touches):
        return None
    out, before = [], ""
    for t, (first, after) in zip(touches, presses):
        if t["kind"] == "key":
            on, meant = t.get("on"), t["meant"]
            got = first[-1].lower() if len(first) == len(before) + 1 and first.startswith(before) else None
            where = "own" if on == meant else "bar" if on == " " else "other"
            out.append((where, "more" if got is None else "meant" if got == meant else "under" if got == on else "third"))
        before = after
    return out


def reach(letters, common):
    """How many letters of a word TapDecoder.search will read as another key (its `changes`, as of 0.15.0): one in
    a word of three letters or fewer, two up to five, three up to eight, four beyond; and where the letters typed
    spell a common word, one up to four letters and two beyond. Read across from the decoder: keep it in step."""
    if common:
        return 1 if letters <= 4 else 2
    return 1 if letters <= 3 else 2 if letters <= 5 else 3 if letters <= 8 else 4


COMMON = 3.2  # Autocorrect.COMMON: from this Zipf frequency a listed word is a common one


def limit(rec, j, clean, typed, off, order, listed):
    """What in Ghost Keyboard's decoder stood between the thumb's slip and the word meant: the first of these that
    is true of word [j]. Worked out from the taps and the word list alone, so it holds for another keyboard's
    losses too only as a description of the slip."""
    if clean:
        return "typed as meant, and changed"
    mine = [t for t in rec["touches"] if t["word"] == j]
    before = [t for t in rec["touches"] if t["word"] == j - 1]
    if any(t["kind"] == "key" and t.get("on") == " " for t in mine):
        return "cut in two: one of its letters landed on the space bar"
    if any(t["kind"] == "space" and t.get("on") not in (" ", None) for t in mine + before):
        return "run together: the space landed on a letter"
    if any(kind == "space missed" and w in (j, j - 1) for w, kind in rec["slips"]):
        return "run together: no space was typed"
    if order:
        return "a letter left out, doubled or swapped"
    letters = typed.strip()
    common = listed.get(letters, 0.0) >= COMMON
    if off > reach(len(letters), common):
        return "more letters off their keys than the search will change in a word this long"
    if common:
        return "the letters typed spell a common word, which is kept"
    if letters in listed:
        return "the letters typed spell a rare word"
    return "no word typed, the word meant within the search's reach, and not mended"


def ends_off_letters(rec, j):
    """Word [j] is ended by a mark typed from the ?123 page: Ghost Keyboard then reads it without its taps."""
    marks = [t for t in rec["touches"] if t["kind"] == "mark" and t["word"] == j]
    return any(sum(1 for u in marks if u["meant"] == t["meant"]) > 1 for t in marks)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run")
    ap.add_argument("--no-replay", action="store_true")
    ap.add_argument("--fixtures", action="store_true")
    ap.add_argument("--resamples", type=int, default=2000)
    args = ap.parse_args()

    runs, metas, left_out = {}, {}, {}
    for name in sorted(os.listdir(args.run)):
        # <keyboard>.jsonl alone: <keyboard>.stalled.jsonl is what was thrown away.
        if name.endswith(".jsonl") and name.count(".") == 1:
            kb = name[:-6]
            runs[kb] = {}
            left_out[kb] = 0
            for line in open(os.path.join(args.run, name)):
                r = json.loads(line)
                # A sentence the emulator never typed without a stall says nothing of the keyboard (run.py, timing.py).
                if r.get("stalled"):
                    left_out[kb] += 1
                    continue
                runs[kb][(r["thumb"], r["line"])] = r
            meta = os.path.join(args.run, kb + ".meta.json")
            metas[kb] = json.load(open(meta)) if os.path.exists(meta) else {}
    if not runs:
        raise SystemExit("score: no <keyboard>.jsonl in " + args.run)
    keyboards = [k for k in ("ghost", "gboard", "swiftkey") if k in runs] + [k for k in runs if k not in ("ghost", "gboard", "swiftkey")]

    jvm = {}
    ghost_geometry = None
    if "ghost" in runs:
        # The keyboard as it was measured for this run: the run's own copy of it, else the one in tool/versus/geometry.
        for where in (os.path.join(args.run, "ghost.geometry.json"), os.path.join(HERE, "geometry", metas["ghost"].get("geometry") or "-")):
            if os.path.exists(where):
                ghost_geometry = where
                break
    if "ghost" in runs and not args.no_replay:
        dev = metas["ghost"].get("device", {})
        # As the build that was measured typed: the run's meta says where that is not as this tree types.
        jvm = replay(list(runs["ghost"].values()), (dev.get("width", 1248), dev.get("height", 1972), dev.get("dpi", 420)),
                     board=board_file(ghost_geometry) if ghost_geometry else None, flags=metas["ghost"].get("replay", []))

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
        # The same count over the words none of whose taps (their space's too) had to be moved off a key that is
        # no letter, on any keyboard: does the moving decide anything?
        def moved(kb, line, j):
            return any(t.get("moved") for t in table[(kb, thumb, line)]["rec"]["touches"] if t["word"] == j and t["kind"] != "mark")
        steady = [(line, j) for line in lines for j in range(len(table[(keyboards[0], thumb, line)]["right"])) if not any(moved(kb, line, j) for kb in keyboards)]
        res["no_tap_moved"] = {"words": len(steady), "wrong": {kb: 100.0 * sum(1 for line, j in steady if not table[(kb, thumb, line)]["right"][j]) / max(1, len(steady)) for kb in keyboards}}
        # What each tap typed as it was made.
        res["at_the_press"] = {}
        for kb in keyboards:
            tally = collections.Counter()
            for line in lines:
                for pair in at_the_press(table[(kb, thumb, line)]["rec"]) or [("unread", "unread")]:
                    tally[pair] += 1
            res["at_the_press"][kb] = {"%s/%s" % k: v for k, v in sorted(tally.items())}
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

    if len(keyboards) > 0:
        md.append("| Thumb | Keyboard | Taps that landed on another letter's key | of them typed as the letter meant, there and then | Taps on their own key | of them typed as something else | Words wrong where no tap was moved off a key that is no letter |")
        md.append("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
        for thumb, res in results["thumbs"].items():
            for kb in keyboards:
                a = res["at_the_press"][kb]
                other = sum(v for k, v in a.items() if k.startswith("other/"))
                own = sum(v for k, v in a.items() if k.startswith("own/"))
                md.append("| %s | %s | %d | %d (%.0f%%) | %d | %d (%.1f%%) | %.1f%% of %d |" % (
                    thumb, names[kb], other, a.get("other/meant", 0), 100.0 * a.get("other/meant", 0) / max(1, other), own, own - a.get("own/meant", 0), 100.0 * (own - a.get("own/meant", 0)) / max(1, own),
                    res["no_tap_moved"]["wrong"][kb], res["no_tap_moved"]["words"]))
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
        aside = os.path.join(args.run, kb + ".stalled.jsonl")
        caps = [capitals(r) for r in recs]
        checks[kb] = {"sentences": len(recs), "read_twice": len(second), "read_twice_same": sum(1 for r in second if r["uiautomator"] == r["out"]), "with_other_characters": len(odd),
                      "names_given_their_capital": sum(c[0] for c in caps), "names_with_the_right_letters": sum(c[1] for c in caps),
                      "typings_thrown_away_for_a_stall": sum(1 for _ in open(aside)) if os.path.exists(aside) else 0,
                      "left_out_as_stalled": left_out.get(kb, 0), "timed_by_the_emulators_clock": sum(1 for r in recs if r.get("clock")),
                      "doubted_still": sum(1 for r in recs if timing.doubt(r))}
    results["checks"] = checks

    # Where another keyboard got the word and Ghost Keyboard did not.
    listed = word_list()
    losses = []
    wins = []
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
                            wins.append({"category": category(word, originals[j] if j < len(originals) else word, o["clean"][j], o["off"][j], o["space"][j], o["order"][j], first=j == 0),
                                         "thumb": thumb, "line": line, "word": j, "meant": word, "typed": o["typed"][j], "ghost": e["pairs"][j], "other": other, "others": o["pairs"][j] or "(nothing)",
                                         "sentence": rec["target"], "ghost_field": rec["out"], "other_field": o["rec"]["out"]})
                            continue
                        if not o["right"][j]:
                            continue
                        cat = category(word, originals[j] if j < len(originals) else word, e["clean"][j], e["off"][j], e["space"][j], e["order"][j], first=j == 0)
                        lim = limit(rec, j, e["clean"][j], e["typed"][j], e["off"][j], e["order"][j], listed)
                        left = e["pairs"][j] or "(nothing)"
                        how = "left as typed" if left == e["typed"][j].strip() else "changed to another word"
                        counts[(cat, thumb)] += 1
                        typed_word = e["typed"][j].strip()
                        known = "yes" if word in listed else "no"
                        slip_is_word = "yes" if (typed_word != word and typed_word in listed) else "no"
                        losses.append({"category": cat, "limit": lim, "no_taps": "yes" if ends_off_letters(rec, j) else "no",
                                       "outcome": how, "thumb": thumb, "line": line, "word": j, "meant": word, "typed": e["typed"][j], "ghost": left, "other": other, "others": o["pairs"][j],
                                       "meant_in_list": known, "typed_is_a_word": slip_is_word,
                                       "sentence": rec["target"], "ghost_field": rec["out"], "other_field": o["rec"]["out"], "events": events_line(rec)})
    results["losses"] = {"count": len(losses), "by_category": {}, "other_way": {"%s/%s" % k: v for k, v in other_way.items()}}
    by_cat = collections.Counter(l["category"] for l in losses)
    for cat, n in by_cat.most_common():
        results["losses"]["by_category"][cat] = {"count": n, "by_thumb": {t: counts[(cat, t)] for t in results["thumbs"]}, "left_as_typed": sum(1 for l in losses if l["category"] == cat and l["outcome"] == "left as typed")}
    results["losses"]["meant_not_in_ghosts_list"] = sum(1 for l in losses if l["meant_in_list"] == "no")
    results["losses"]["slip_spelled_another_word"] = sum(1 for l in losses if l["typed_is_a_word"] == "yes")
    results["losses"]["left_as_typed"] = sum(1 for l in losses if l["outcome"] == "left as typed")
    by_limit = collections.Counter(l["limit"] for l in losses)
    results["losses"]["by_limit"] = {lim: {"count": n, "by_thumb": {t: sum(1 for l in losses if l["limit"] == lim and l["thumb"] == t) for t in results["thumbs"]},
                                           "no_taps": sum(1 for l in losses if l["limit"] == lim and l["no_taps"] == "yes")} for lim, n in by_limit.most_common()}
    results["losses"]["no_taps"] = sum(1 for l in losses if l["no_taps"] == "yes")
    cols = ["category", "limit", "no_taps", "outcome", "thumb", "line", "word", "meant", "typed", "ghost", "other", "others", "meant_in_list", "typed_is_a_word", "sentence", "ghost_field", "other_field", "events"]
    with open(os.path.join(args.run, "losses.tsv"), "w") as f:
        f.write("\t".join(cols) + "\n")
        for l in sorted(losses, key=lambda l: (-by_cat[l["category"]], l["category"], THUMB_ORDER.index(l["thumb"]) if l["thumb"] in THUMB_ORDER else 9, l["line"], l["word"])):
            f.write("\t".join(str(l[c]) for c in cols) + "\n")
    # The other way: what another keyboard got wrong and Ghost Keyboard did not (the slip as the other's keys had it).
    win_cols = ["category", "thumb", "line", "word", "meant", "typed", "ghost", "other", "others", "sentence", "ghost_field", "other_field"]
    with open(os.path.join(args.run, "wins.tsv"), "w") as f:
        f.write("\t".join(win_cols) + "\n")
        for w in sorted(wins, key=lambda w: (w["category"], THUMB_ORDER.index(w["thumb"]) if w["thumb"] in THUMB_ORDER else 9, w["line"], w["word"])):
            f.write("\t".join(str(w[c]) for c in win_cols) + "\n")
    results["wins"] = {"count": len(wins), "by_category": dict(collections.Counter(w["category"] for w in wins).most_common())}
    wins_note = "The other way, %d words Ghost Keyboard got and %s did not: %s." % (len(wins), " or ".join(names[k] for k in keyboards[1:]), "; ".join("%s %d" % kv for kv in collections.Counter(w["category"] for w in wins).most_common())) if wins else ""
    if args.fixtures and losses:
        os.makedirs(os.path.join(HERE, "fixtures"), exist_ok=True)
        stem = os.path.join(HERE, "fixtures", "ghost-lost-to-%s" % "-".join(keyboards[1:]))
        with open(stem + ".tsv", "w") as f:
            f.write("# Words another keyboard got and Ghost Keyboard did not (tool/versus/score.py --fixtures). tool/versus/fixtures.py types\n")
            f.write("# each sentence's taps through the decoder on the JVM again and says which words are right now.\n")
            if ghost_geometry:
                # The keyboard the taps were made on goes with them: the layout in the tree moves on.
                import shutil
                if os.path.abspath(ghost_geometry) != os.path.abspath(stem + ".geometry.json"):
                    shutil.copyfile(ghost_geometry, stem + ".geometry.json")
                f.write("# board: %s\n" % os.path.relpath(stem + ".geometry.json", ROOT))
            f.write("\t".join(cols) + "\n")
            for l in sorted(losses, key=lambda l: (-by_cat[l["category"]], l["category"], l["thumb"], l["line"], l["word"])):
                f.write("\t".join(str(l[c]) for c in cols) + "\n")

    if losses:
        md.append("| What went wrong (Ghost Keyboard wrong, %s right) | Words | careful | leaning | sloppy | Left as typed | The slip spelled another word | The word meant is in no list of ours |" % " or ".join(names[k] for k in keyboards[1:]))
        md.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        for cat, v in results["losses"]["by_category"].items():
            mine = [l for l in losses if l["category"] == cat]
            md.append("| %s | %d | %d | %d | %d | %d | %d | %d |" % (cat, v["count"], v["by_thumb"].get("careful", 0), v["by_thumb"].get("leaning", 0), v["by_thumb"].get("sloppy", 0), v["left_as_typed"],
                                                           sum(1 for l in mine if l["typed_is_a_word"] == "yes"), sum(1 for l in mine if l["meant_in_list"] == "no")))
        md.append("| all | %d | %d | %d | %d | %d | %d | %d |" % (len(losses), sum(1 for l in losses if l["thumb"] == "careful"), sum(1 for l in losses if l["thumb"] == "leaning"), sum(1 for l in losses if l["thumb"] == "sloppy"),
                                                              results["losses"]["left_as_typed"], results["losses"]["slip_spelled_another_word"], results["losses"]["meant_not_in_ghosts_list"]))
        md.append("")
        md.append("| What stood in the decoder's way (the same %d words) | Words | careful | leaning | sloppy | Ended by a mark from ?123, so read without taps | For example (typed, left, meant) |" % len(losses))
        md.append("| --- | ---: | ---: | ---: | ---: | ---: | --- |")
        for lim, v in results["losses"]["by_limit"].items():
            mine = [l for l in losses if l["limit"] == lim]
            shown = []
            for l in sorted(mine, key=lambda l: (l["line"], l["word"])):
                e = "%s, %s, %s" % (l["typed"].strip() or "(nothing)", l["ghost"], l["meant"])
                if e not in shown:
                    shown.append(e)
            md.append("| %s | %d | %d | %d | %d | %d | %s |" % (lim, v["count"], v["by_thumb"].get("careful", 0), v["by_thumb"].get("leaning", 0), v["by_thumb"].get("sloppy", 0), v["no_taps"], "; ".join(shown[:4])))
        md.append("| all | %d | %d | %d | %d | %d | |" % (len(losses), sum(1 for l in losses if l["thumb"] == "careful"), sum(1 for l in losses if l["thumb"] == "leaning"), sum(1 for l in losses if l["thumb"] == "sloppy"), results["losses"]["no_taps"]))
        md.append("")

    if wins_note:
        md.append(wins_note)
        md.append("")
    md.append("Not in the score, which leaves capitals aside: of the names the thumb typed in lower case and the field has the right letters of (Tom, Mary, Algeria, Monday), given their capital: %s." % "; ".join(
        "%s %d of %d" % (names[kb], checks[kb]["names_given_their_capital"], checks[kb]["names_with_the_right_letters"]) for kb in keyboards))
    md.append("")

    with open(os.path.join(args.run, "results.json"), "w") as f:
        json.dump(results, f, indent=1)
        f.write("\n")
    with open(os.path.join(args.run, "results.md"), "w") as f:
        f.write("\n".join(md) + "\n")
    print("\n".join(md))
    for kb in keyboards:
        c = checks[kb]
        print("%s: %d sentences; read a second way %d times, the same %d; with a character no tap types: %d" % (names[kb], c["sentences"], c["read_twice"], c["read_twice_same"], c["with_other_characters"]))
        print("    typings thrown away for a stall and done again: %d; never got through, and left out: %d; every touch timed on the emulator: %d; still doubted (timing.py): %d" % (
            c["typings_thrown_away_for_a_stall"], c["left_out_as_stalled"], c["timed_by_the_emulators_clock"], c["doubted_still"]))
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
