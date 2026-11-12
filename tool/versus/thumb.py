"""The sentences tool/versus types, and the thumb that types them.

The sentences are Tatoeba's held-out English ones (build/typing-bench/en-heldout.txt, every 20th of the export,
never counted for the keyboard's starter tables; tool/typing_bench.sh cuts the file). They are taken from line
90,001 on, a stretch none of tool/typing_bench's modes reads, so the costs autocorrect was tuned with never saw them.

The thumb is tool/typing_bench/Bench.kt's (its Noise and typeAll), with the lean tool/trainer_sim gives a thumb,
said in a way that is no keyboard's: for every key meant, how far off the tap lands in key widths and key heights.
A keyboard's own key positions turn that into pixels (place), so each keyboard gets the same clumsy thumb on its own
keys: the same offsets, slips and pauses, from the same seed.

What a person is taken to do, as the bench has it: type in lower case and let the keyboard capitalise; type a
contraction without its apostrophe (dont, im) and let the keyboard put it in; type the comma, and the full stop,
question mark or exclamation mark that ends the sentence, each without a slip.
"""
import math
import os
import random
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
HELD_OUT = os.path.join(ROOT, "build", "typing-bench", "en-heldout.txt")
CONTRACTIONS = os.path.join(ROOT, "android", "app", "src", "main", "assets", "dict", "en_contractions.tsv")
FIRST_LINE = 90000


class Thumb:
    """How a thumb misses, in key sizes: a steady lean (across, down), Gaussian scatter about it, a share of wilder
    taps, and slips of order: a letter left out, doubled, two swapped, a space missed."""

    def __init__(self, name, sx, sy, lean_x=0.0, lean_y=0.0, wild=0.06, omit=0.004, double=0.003, swap=0.004, no_space=0.004):
        self.name, self.sx, self.sy, self.lean_x, self.lean_y = name, sx, sy, lean_x, lean_y
        self.wild, self.omit, self.double, self.swap, self.no_space = wild, omit, double, swap, no_space

    def describe(self):
        lean = "no lean" if not (self.lean_x or self.lean_y) else "leaning %.2f of a key right and %.2f down" % (self.lean_x, self.lean_y)
        return "scatter %.2f of a key across and %.2f down, %s" % (self.sx, self.sy, lean)


# Bench.kt's careful and sloppy; trainer_sim's thumb that lands right and low, with the careful one's scatter.
THUMBS = {
    "careful": Thumb("careful", 0.20, 0.15),
    "leaning": Thumb("leaning", 0.20, 0.15, lean_x=0.24, lean_y=0.18),
    "sloppy": Thumb("sloppy", 0.34, 0.25),
}


def contractions():
    """{"don't": "dont"}: a contraction, and the letters people type for it."""
    out = {}
    with open(CONTRACTIONS, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2:
                out[parts[1]] = parts[0]
    return out


class Sentence:
    def __init__(self, line_no, text, words, keys):
        self.line_no = line_no      # its line in en-heldout.txt, from 1
        self.text = text            # as written
        self.words = words          # what should stand: lower case, apostrophes kept, no marks
        self.keys = keys            # what is typed: [("word", letters), ("mark", ","), ("space", " "), ...]


def sentences(count, path=HELD_OUT, first=FIRST_LINE, most_words=20):
    """The first [count] sentences from line [first] on that the thumb can type: letters, spaces, commas and one
    closing . ? or !, any apostrophe a contraction's (the bench's own list). Skipped: digits and other marks, a
    second sentence on the line, an acronym in capitals, other apostrophes (it's, Tom's: no key sequence says which
    was meant), a line of more than [most_words] words. Returns the sentences and how many lines were passed over."""
    table = contractions()
    out, skipped = [], {}
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            if n <= first:
                continue
            if len(out) >= count:
                break
            text = line.rstrip("\n").replace("’", "'").strip()
            why = None
            tokens = text.split(" ")
            if not text or re.search(r"[^A-Za-z ,.?!']", text):
                why = "another character"
            elif not re.search(r"[.?!]$", text) or re.search(r"[.?!]", text[:-1]):
                why = "not one sentence with one closing mark"
            elif any(t == "" for t in tokens) or len(tokens) > most_words:
                why = "spacing or length"
            words, keys = [], []
            if why is None:
                for i, tok in enumerate(tokens):
                    m = re.match(r"^([A-Za-z']+)([,.?!]?)$", tok)
                    if not m or (m.group(2) in ".?!" and m.group(2) and i != len(tokens) - 1):
                        why = "a mark inside a word"
                        break
                    word, mark = m.group(1), m.group(2)
                    if len(word) >= 2 and word.isupper():
                        why = "an acronym"
                        break
                    lower = word.lower()
                    if "'" in lower and lower not in table:
                        why = "an apostrophe that is no listed contraction"
                        break
                    words.append(lower)
                    keys.append(("word", table.get(lower, lower)))
                    if mark:
                        keys.append(("mark", mark))
                    if i != len(tokens) - 1:
                        keys.append(("space", " "))
            if why is None and keys[-1][0] != "mark":
                why = "not one sentence with one closing mark"
            if why:
                skipped[why] = skipped.get(why, 0) + 1
                continue
            out.append(Sentence(n, text, words, keys))
    return out, skipped


def plan(sentence, thumb, seed):
    """The taps [thumb] makes for [sentence], each a dict, no keyboard in mind:
      kind "key":   meant (the letter aimed at), dx, dy (how far off, in key widths and heights), word (which word)
      kind "space": u (where along the bar it aims, -0.25 to 0.25 of the bar's width from its middle), dx, dy, word
      kind "mark":  meant (the mark), word
    and for all: hold (seconds the finger is down) and period (seconds from this touch to the next).
    The same seed, thumb and sentence always give the same taps."""
    rnd = random.Random("%s|%s|%d" % (seed, thumb.name, sentence.line_no))

    def gauss(s):
        g = rnd.gauss(0.0, 1.0) * s
        return g * (2.2 if rnd.random() < thumb.wild else 1.0)

    def timing():
        return {"hold": round(rnd.uniform(0.055, 0.095), 3), "period": round(rnd.uniform(0.150, 0.250), 3)}

    taps = []
    word_index = -1

    def key(c, slip=None):
        t = {"kind": "key", "meant": c, "dx": round(thumb.lean_x + gauss(thumb.sx), 4), "dy": round(thumb.lean_y + gauss(thumb.sy), 4), "word": word_index}
        if slip:
            t["slip"] = slip
        t.update(timing())
        taps.append(t)

    slips = []
    for kind, value in sentence.keys:
        if kind == "word":
            word_index += 1
            i = 0
            while i < len(value):
                r = rnd.random()
                if r < thumb.omit and len(value) > 1:
                    slips.append((word_index, "left out"))
                elif r < thumb.omit + thumb.swap and i + 1 < len(value):
                    key(value[i + 1], "swapped")
                    key(value[i], "swapped")
                    slips.append((word_index, "swapped"))
                    i += 1
                else:
                    key(value[i])
                    if rnd.random() < thumb.double:
                        key(value[i], "doubled")
                        slips.append((word_index, "doubled"))
                i += 1
        elif kind == "space":
            if rnd.random() < thumb.no_space:
                slips.append((word_index, "space missed"))
                continue
            t = {"kind": "space", "meant": " ", "u": round(rnd.uniform(-0.25, 0.25), 4), "dx": round(thumb.lean_x + gauss(thumb.sx), 4), "dy": round(thumb.lean_y + gauss(thumb.sy), 4), "word": word_index}
            t.update(timing())
            taps.append(t)
        else:
            t = {"kind": "mark", "meant": value, "word": word_index}
            t.update(timing())
            taps.append(t)
    return taps, slips


class GhostTouch:
    """Which key Ghost Keyboard reads a touch as, on its letters: KeyboardView.letterCorrection and keyAt, in
    screen pixels. The cells are the keyboard's own layout (tool/versus/replay.sh geometry) and the offsets its
    LETTER_OFFSETS_DP; calibrate.py puts both in the geometry file only once this reads every one of the emulator's
    calibration taps as the emulator did. The same thing is in Kotlin in replay/Replay.kt (Board.keyAt), for the
    decoder; this one is here so that placing a thumb's taps needs no JVM."""

    def __init__(self, model):
        self.density = model["density"]
        self.cells = [(name, r[0], r[1], r[2], r[3], model["offsets_dp"].get(name)) for name, r in model["cells"]]
        self.keys_top = min(c[2] for c in self.cells)
        self.bottom_top = max(c[2] for c in self.cells)
        letters = [c for c in self.cells if len(c[0]) == 1 and c[0].isalpha()]
        unit_w = letters[0][3] - letters[0][1]
        self.sigma2 = 2.0 * (unit_w * 0.6) * (unit_w * 0.6)

    def correction(self, x, y):
        sw = sx = sy = 0.0
        for name, l, t, r, b, off in self.cells:
            if off is None and t < self.bottom_top:
                continue
            dx, dy = x - (l + r) / 2.0, y - (t + b) / 2.0
            w = math.exp(-(dx * dx + dy * dy) / self.sigma2)
            sw += w
            if off is not None:
                sx += w * off[0] * self.density
                sy += w * off[1] * self.density
        return (0.0, 0.0) if sw < 1e-6 else (sx / sw, sy / sw)

    def key(self, raw_x, raw_y):
        """The name of the key a touch at a screen pixel means ("q", "space", "shift", ...), None above the keys."""
        if raw_y < self.keys_top:
            return None
        ox, oy = self.correction(raw_x, raw_y)
        x, y = raw_x - ox, max(raw_y - oy, self.keys_top + 1.0)
        for name, l, t, r, b, _ in self.cells:
            if l <= x < r and t <= y < b:
                return name
        best = None
        for name, l, t, r, b, _ in self.cells:
            dx = l - x if x < l else x - r if x > r else 0.0
            dy = t - y if y < t else y - b if y > b else 0.0
            dist = dx * dx + dy * dy
            if best is None or dist < best[0]:
                best = (dist, name)
        return best[1]


class Board:
    """A keyboard's keys on a screen, as tool/versus/calibrate.py measured them."""

    def __init__(self, geometry):
        g = geometry
        self.g = g
        self.name = g["keyboard"]
        self.key_w, self.key_h = g["key_w"], g["key_h"]
        self.keys = g["keys"]
        self.space = g["space"]
        self.marks = g["marks"]
        self.width = g["device"]["width"]
        m = 0.06 * self.key_w
        self.margin = m
        # What the thumb never presses: every key that is not a letter or the bar, a little more than its cell.
        cells = dict(g["never_tapped"])
        for name in (",", "."):
            if name in g["hint"]:
                cells[name] = g["hint"][name]
        self.forbidden = {n: [r[0] - m, r[1] - m, r[2] + m, r[3] + m] for n, r in cells.items()}
        self.top = min(g["hint"][c][1] for c in "qwertyuiop") + m
        self.bottom = self.space["bottom"] - m
        self.reader = GhostTouch(g["touch_model"]) if g.get("touch_model", {}).get("kind") == "ghost-keyboard-view" else None

    def at(self, x, y):
        """The letter or " " a touch at (x, y) lands on at the start of a sentence, None for anything else (a key
        that is not a letter, the tools above the keys, Android's strip below). Ghost Keyboard's own reading of the
        touch where the geometry has it; else the measured areas (Gboard's are its cells, to a pixel)."""
        if not (0 <= x < self.width and self.top <= y <= self.bottom):
            return None
        if self.reader is not None:
            name = self.reader.key(x, y)
            return " " if name == "space" else name if name is not None and len(name) == 1 and name.isalpha() else None
        if any(r[0] <= x <= r[2] and r[1] <= y <= r[3] for r in self.forbidden.values()):
            return None
        for c, k in self.keys.items():
            if k["left"] <= x < k["right"] and k["top"] <= y < k["bottom"]:
                return c
        s = self.space
        if s["left"] <= x < s["right"] and s["top"] <= y < s["bottom"]:
            return " "
        return None

    def guard(self, x, y, home):
        """(x, y) if a tap there presses a letter or the bar. Else the tap is pulled back along the line toward
        where it was aimed ([home]) until it does, and a little further (0.03 of a key), so it is not on the very
        edge. Returns x, y and whether it was moved."""
        if self.at(x, y) is not None:
            return x, y, False
        dist = math.hypot(home[0] - x, home[1] - y)
        if dist < 1e-6:
            return home[0], home[1], True
        ux, uy = (home[0] - x) / dist, (home[1] - y) / dist
        step = 1.0
        d = step
        while d < dist:
            px, py = x + ux * d, y + uy * d
            if self.at(px, py) is not None:
                d = min(dist, d + 0.03 * self.key_w)
                px, py = x + ux * d, y + uy * d
                if self.at(px, py) is not None:
                    return px, py, True
            d += step
        return home[0], home[1], True

    def place(self, taps, aim="answers"):
        """[taps] (plan's) as touches on this keyboard: a list of dicts with x, y in screen pixels, hold and gap in
        seconds, what was meant, the key the touch lands on by the measured areas ("on"), and "moved" where the
        guard moved it. A mark is as many touches as this keyboard needs for it, none of them scattered.
        [aim] "answers": the middle of the area each key answers to; "drawn": the middle of its cell."""
        out = []
        for t in taps:
            if t["kind"] == "mark":
                steps = self.marks[t["meant"]]["taps"]
                for j, (x, y) in enumerate(steps):
                    last = j == len(steps) - 1
                    out.append({"kind": "mark", "meant": t["meant"], "types": last and len(steps) == 1 or (len(steps) > 1 and j == 1), "x": x, "y": y, "hold": t["hold"],
                                # Another page takes a moment to find a key on: a third of a second a step.
                                "period": t["period"] if last and len(steps) == 1 else 0.33, "word": t["word"]})
                continue
            if t["kind"] == "space":
                s = self.space
                hx, hy = (s["left"] + s["right"]) / 2.0 + t["u"] * (s["right"] - s["left"]), (s["top"] + s["bottom"]) / 2.0
            else:
                k = self.keys[t["meant"]]
                if aim == "drawn":
                    cell = self.g["hint"][t["meant"]]
                    hx = k["x"] if k.get("end") else (cell[0] + cell[2]) / 2.0
                    if k.get("end") == "first":
                        hx = cell[2] - self.key_w / 2.0
                    elif k.get("end") == "last":
                        hx = cell[0] + self.key_w / 2.0
                    hy = (cell[1] + cell[3]) / 2.0
                else:
                    hx, hy = k["x"], k["y"]
            x, y, moved = self.guard(hx + t["dx"] * self.key_w, hy + t["dy"] * self.key_h, (hx, hy))
            touch = {"kind": t["kind"], "meant": t["meant"], "x": round(x, 1), "y": round(y, 1), "hold": t["hold"], "period": t["period"], "word": t["word"], "on": self.at(x, y)}
            if moved:
                touch["moved"] = True
            if "slip" in t:
                touch["slip"] = t["slip"]
            out.append(touch)
        return out
