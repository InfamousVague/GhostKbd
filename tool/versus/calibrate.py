#!/usr/bin/env python3
"""Where a keyboard's keys are on the emulator's screen, measured: tool/versus/calibrate.py <keyboard>.

Nothing here is taken on trust. A first idea of where the keys are comes from one of two places:

  * the accessibility tree, where the keyboard puts its keys in it (Gboard does; `uiautomator dump --windows`), or
  * a hint file (--hint): Ghost Keyboard draws its keys on one canvas and tells the tree nothing, so its hint is
    worked out from its own layout code by tool/versus/replay.sh geometry.

Then every letter key is measured by tapping, in the versus field's "raw" mode (a field that asks for no
suggestions, where a keyboard does no correcting and a key press types its own letter): a tap in the middle of where
each key is thought to be must type that key, and each edge of the area a key answers to is found by halving (tap
between two points that type different things, keep the half where it changes) until it is known to a pixel. What a
key answers to is not always what is drawn: a keyboard may read a touch as higher or further left than it was, to
suit where thumbs land (Ghost Keyboard does, by where Matt's thumb lands). The thumb in tool/versus aims at the
middle of the area a key answers to, on every keyboard alike.

Never tapped: anything that is not a letter, the space bar, the comma or the full stop (the row of tools above the
keys, shift's far side, the ?123 and emoji keys, Android's own buttons under the keyboard). Where an edge borders
one of those it is taken from the tree or the hint, and the result says so ("assumed").

Writes tool/versus/geometry/<keyboard>-<width>x<height>-<dpi>dpi.json.
"""
import argparse
import datetime
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import device as d  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]


def log(*a):
    print(*a, flush=True)


def screen():
    size = re.search(r"(\d+)x(\d+)", d.sh("wm size")).groups()
    dpi = re.search(r"(\d+)", d.sh("wm density")).group(1)
    return int(size[0]), int(size[1]), int(dpi)


def ime_nodes(xml):
    """(content description, resource id, [l, t, r, b]) for every node of the keyboard's window in the tree."""
    import xml.etree.ElementTree as ET
    out = []
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return out
    for window in root.iter("window"):
        if window.get("type") != "TYPE_INPUT_METHOD":
            continue
        for node in window.iter("node"):
            m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
            if m:
                out.append((node.get("content-desc", ""), node.get("resource-id", ""), [int(v) for v in m.groups()]))
    return out


def from_tree(xml):
    """Key rectangles from the accessibility tree: {name: [l, t, r, b]} for the letters, 'space', ',', '.', and
    the keys never to be tapped ('shift', 'delete', 'symbols', 'emoji', 'enter'). Empty where the keyboard's window
    has no keys in it."""
    keys = {}
    for desc, rid, b in ime_nodes(xml):
        name = None
        low = desc.lower()
        if len(desc) == 1 and desc.isalpha():
            name = low
        elif desc in (",", "."):
            name = desc
        elif low == "space" or rid.endswith("key_pos_space"):
            name = "space"
        elif low.startswith("shift") or rid.endswith("key_pos_shift"):
            name = "shift"
        elif low in ("delete", "backspace") or rid.endswith("key_pos_del"):
            name = "delete"
        elif "symbol" in low and "keyboard" in low:
            name = "symbols"
        elif "emoji" in low:
            name = "emoji"
        elif low == "enter" or rid.endswith("key_pos_ime_action"):
            name = "enter"
        if name and name not in keys:
            keys[name] = b
    return keys if all(c in keys for c in "".join(ROWS)) and "space" in keys else {}


class Prober:
    """Taps one point at a time in the raw field and says what it typed. The field is emptied and the keyboard
    started over before every tap: Gboard moves the edges of its keys with the letters already typed (the same
    point types w after "t" and a after "th"), so a key's area is only a fixed thing at the start of a sentence.
    A tap waits [settle] seconds after the field is entered: for about 0.3 s a keyboard's keys are not yet where
    they will be (both keyboards; a tap 0.1 s in types a key two rows up), and a tap then measures nothing."""

    def __init__(self, ime, settle=0.6, pause=0.09):
        self.ime = ime
        self.settle = settle
        self.pause = pause
        self.count = 0
        self.n = 0
        self.log = []
        s = d.ready("cal0", "raw", ime=ime)
        self.height = s["height"]

    def tap(self, x, y):
        """What a tap at (x, y) typed into an empty field: one character in lower case, "" for nothing, or "?"
        for anything else."""
        self.count += 1
        got = self._tap(x, y)
        self.log.append([float(x), float(y), got])
        return got

    def sequence(self, points, gap=0.33):
        """The field's text after tapping [points] one after another, from an empty field."""
        self.count += len(points)
        self.n += 1
        tag = "cal%d" % self.n
        taps = "; ".join("input tap %.1f %.1f; sleep %.2f" % (x, y, gap) for x, y in points)
        out = d.sh("am start -n %s --es tag %s --es mode raw >/dev/null; sleep 0.7; %s; sleep 0.25; run-as %s cat files/events.log" % (d.FIELD_ACTIVITY, tag, taps, d.FIELD))
        st = d.state(self._rows(out))
        if st.get("tag") != tag:
            raise SystemExit("calibrate: the versus field's record is not this sequence's own")
        return st["text"]

    def _tap(self, x, y):
        for attempt in range(4):
            self.n += 1
            tag = "cal%d" % self.n
            out = d.sh("am start -n %s --es tag %s --es mode raw >/dev/null; sleep %.2f; input tap %.1f %.1f; sleep %.2f; run-as %s cat files/events.log"
                       % (d.FIELD_ACTIVITY, tag, self.settle + 0.15 * attempt, x, y, self.pause + 0.1 * attempt, d.FIELD))
            rows = self._rows(out)
            st = d.state(rows)
            # The record must be this tap's own, with the keyboard up at its usual height and the field in front.
            if st.get("tag") != tag or not st.get("showing") or st.get("height") != self.height or not st.get("focus"):
                time.sleep(0.4)
                continue
            if st.get("ime") != self.ime:
                raise SystemExit("calibrate: the keyboard in use changed to %s" % st.get("ime"))
            text = st["text"]
            if text == "" and attempt == 0:
                # Nothing typed: once more, slower, before believing it.
                continue
            return text.lower() if len(text) == 1 else ("" if text == "" else "?")
        return ""

    @staticmethod
    def _rows(out):
        rows = []
        for line in out.splitlines():
            f = line.split("\t")
            if len(f) < 2 or not f[1].isdigit():
                continue
            if f[0] == "T" and len(f) >= 3:
                rows.append(("T", int(f[1]), json.loads(f[2])))
            elif f[0] == "R" and len(f) >= 7:
                rows.append(("R", int(f[1]), f[2], f[3], f[4] == "true", f[5], f[6] == "true"))
            elif f[0] == "I" and len(f) >= 5:
                rows.append(("I", int(f[1]), f[2] == "true", int(f[3]), int(f[4])))
            elif f[0] == "F" and len(f) >= 3:
                rows.append(("F", int(f[1]), f[2] == "true"))
        return rows


def edge(p, a, b, want):
    """Between a (which types [want]) and b (which does not), each an (x, y): the last point that types [want], the
    first that does not and what that one types, found by halving. Points differ along one axis only."""
    ax, ay = a
    bx, by = b
    other = None
    while abs(ax - bx) > 1 or abs(ay - by) > 1:
        mx, my = round((ax + bx) / 2), round((ay + by) / 2)
        if (mx, my) == (ax, ay) or (mx, my) == (bx, by):
            break
        got = p.tap(mx, my)
        if got == want:
            ax, ay = mx, my
        else:
            bx, by = mx, my
            other = got
    return (ax, ay), (bx, by), other


def measure(ime, hint, screen_w):
    """The areas the letter keys and the space bar answer to, by tapping. [hint] is {name: [l, t, r, b]}."""
    p = Prober(ime)
    centre = {k: ((v[0] + v[2]) / 2.0, (v[1] + v[3]) / 2.0) for k, v in hint.items()}
    notes = []
    # 1. The middle of every key types that key.
    for row in ROWS:
        for c in row:
            got = p.tap(*centre[c])
            if got != c:
                raise SystemExit("calibrate: a tap in the middle of where %r should be typed %r: the hint is wrong, or the keyboard is not on its letters" % (c, got))
    for name, want in (("space", " "), (",", ","), (".", ".")):
        if name in centre:
            got = p.tap(*centre[name])
            if got != want:
                raise SystemExit("calibrate: a tap in the middle of where %r should be typed %r" % (name, got))
    log("  every key's middle types its key (%d taps)" % p.count)

    left, right, top, bottom = {}, {}, {}, {}
    how = {}
    # 2. Left and right edges, along the line through the middles of each row.
    for r, row in enumerate(ROWS):
        y = round(sum(centre[c][1] for c in row) / len(row))
        for i in range(len(row) - 1):
            a, b = row[i], row[i + 1]
            (ax, _), (bx, _), other = edge(p, (round(centre[a][0]), y), (round(centre[b][0]), y), a)
            right[a] = ax + 0.5
            left[b] = bx - 0.5 if other == b else None
            if other != b:
                # Something between the two keys: find where b itself starts.
                (cx, _), _, _ = edge(p, (round(centre[b][0]), y), (bx, y), b)
                left[b] = cx - 0.5
                notes.append("between %s and %s, %d px answer to neither (%r)" % (a, b, cx - bx, other))
        first, last = row[0], row[-1]
        # The row's two ends: out to the screen's edge where the key answers all the way (Gboard's a and l do),
        # else as far as the key goes. Past a row's end may be shift or backspace, which are not tapped: there
        # the end is taken from the hint.
        for c, side in ((first, "left"), (last, "right")):
            safe = not any(n in hint and hint[n][1] < centre[c][1] < hint[n][3] for n in ("shift", "delete"))
            if not safe:
                (left if side == "left" else right)[c] = float(hint[c][0] if side == "left" else hint[c][2])
                how["%s.%s" % (c, side)] = "assumed"
                continue
            far = 1 if side == "left" else screen_w - 2
            got = p.tap(far, y)
            if got == c:
                (left if side == "left" else right)[c] = 0.0 if side == "left" else float(screen_w)
            else:
                (ax, _), _, _ = edge(p, (round(centre[c][0]), y), (far, y), c)
                (left if side == "left" else right)[c] = ax - 0.5 if side == "left" else ax + 0.5
        log("  row %d: left and right edges found (%d taps so far)" % (r + 1, p.count))

    # 3. Top and bottom edges, down the line through each key's middle. Above the top row are the keyboard's tools
    # and below the bottom row its ?123 and emoji keys: those sides are measured only where what lies beyond is a
    # letter, the space bar, the comma or the full stop.
    def beyond(x, y):
        for name, b in hint.items():
            if b[0] <= x < b[2] and b[1] <= y < b[3]:
                return name
        return None

    pitch = (sum(centre[c][1] for c in ROWS[2]) / len(ROWS[2]) - sum(centre[c][1] for c in ROWS[0]) / len(ROWS[0])) / 2.0
    for r, row in enumerate(ROWS):
        for c in row:
            x, y = round(centre[c][0]), round(centre[c][1])
            for side, dy in (("top", -pitch), ("bottom", pitch)):
                target = beyond(x, y + dy)
                ok = target is not None and (len(target) == 1 or target == "space")
                if not ok:
                    (top if side == "top" else bottom)[c] = None
                    how["%s.%s" % (c, side)] = "assumed"
                    continue
                (_, ay), _, _ = edge(p, (x, y), (x, round(y + dy)), c)
                (top if side == "top" else bottom)[c] = ay - 0.5 if side == "top" else ay + 0.5
        log("  row %d: top and bottom edges found (%d taps so far)" % (r + 1, p.count))

    # Where a side could not be tapped it is the hint's own (the tree's, or the layout code's).
    for row in ROWS:
        for c in row:
            if top[c] is None:
                top[c] = float(hint[c][1])
            if bottom[c] is None:
                bottom[c] = float(hint[c][3])

    # 4. The space bar: its top under the letters above it, its right end against the full stop where there is one.
    sx, sy = centre["space"]
    space = {"left": float(hint["space"][0]), "right": float(hint["space"][2]), "top": None, "bottom": float(hint["space"][3])}
    space_how = {"left": "assumed", "right": "assumed", "bottom": "assumed"}
    tops = []
    for x in (sx - (hint["space"][2] - hint["space"][0]) * 0.3, sx, sx + (hint["space"][2] - hint["space"][0]) * 0.3):
        (_, ay), _, _ = edge(p, (round(x), round(sy)), (round(x), round(sy - pitch)), " ")
        tops.append(ay - 0.5)
    space["top"] = sum(tops) / len(tops)
    if "." in hint and hint["."][0] >= hint["space"][2] - 2:
        (ax, _), _, other = edge(p, (round(sx), round(sy)), (round(centre["."][0]), round(sy)), " ")
        space["right"] = ax + 0.5
        space_how["right"] = "measured"
    # Its left end borders a key that is not tapped: a tap just inside the hinted end must still be the bar.
    inside = space["left"] + 4
    if p.tap(inside, sy) == " ":
        space_how["left"] = "assumed; 4 px inside it types a space"
    else:
        raise SystemExit("calibrate: 4 px inside the space bar's hinted left end does not type a space")
    log("  the space bar (%d taps in all)" % p.count)

    keys = {}
    for row_i, row in enumerate(ROWS):
        for i, c in enumerate(row):
            assumed = sorted(k.split(".")[1] for k in how if k.startswith(c + "."))
            keys[c] = {
                "row": row_i, "end": "first" if i == 0 else "last" if i == len(row) - 1 else "",
                "left": left[c], "right": right[c], "top": top[c], "bottom": bottom[c],
                "assumed": assumed,
            }
    return keys, space, space_how, notes, p


def compact(out):
    """JSON a person can read and a diff can show: a line for each key, mark and tap."""
    lines = ["{"]
    items = list(out.items())
    for i, (k, v) in enumerate(items):
        comma = "," if i < len(items) - 1 else ""
        if k == "touch_model":
            lines.append(" \"touch_model\": {")
            inner = list(v.items())
            for j, (kk, vv) in enumerate(inner):
                lines.append("  %s: %s%s" % (json.dumps(kk), json.dumps(vv), "," if j < len(inner) - 1 else ""))
            lines.append(" }" + comma)
        elif k in ("keys", "hint", "marks", "never_tapped", "aim_off_cell_middle") and isinstance(v, dict):
            lines.append(" %s: {" % json.dumps(k))
            inner = list(v.items())
            for j, (kk, vv) in enumerate(inner):
                lines.append("  %s: %s%s" % (json.dumps(kk), json.dumps(vv), "," if j < len(inner) - 1 else ""))
            lines.append(" }" + comma)
        elif k == "probes":
            lines.append(" \"probes\": [")
            for j, row in enumerate(v):
                lines.append("  %s%s" % (json.dumps(row), "," if j < len(v) - 1 else ""))
            lines.append(" ]" + comma)
        else:
            lines.append(" %s: %s%s" % (json.dumps(k), json.dumps(v), comma))
    lines.append("}")
    return "\n".join(lines) + "\n"


def median(values):
    v = sorted(values)
    return (v[len(v) // 2] + v[(len(v) - 1) // 2]) / 2.0


def middle_x(c, hint, key_w):
    """The middle of a key's cell across, a row's end key taken to be as wide as the others (Gboard's a and l
    answer all the way to the screen's edge, and nobody aims at the middle of that)."""
    for row in ROWS:
        if c == row[0] and c != ROWS[0][0]:
            return hint[c][2] - key_w / 2.0
        if c == row[-1] and c != ROWS[0][-1]:
            return hint[c][0] + key_w / 2.0
    return (hint[c][0] + hint[c][2]) / 2.0


def aim(keys, hint):
    """Where a thumb aims for each key (x, y): the middle of the area the key answers to. A key at a row's end
    answers out to the screen's edge on some keyboards, so its middle is half a key in from its inner edge; a
    side that could not be tapped (the top row's top, a key over ?123) is half a key from the opposite side. A
    key's size is its cell's (the tree's, or the layout code's): the rows of the areas keys answer to are not all
    as tall as one another where a keyboard reads touches as higher in one row than the next."""
    key_w = median([hint[c][2] - hint[c][0] for c, k in keys.items() if not k["end"]])
    key_h = median([hint[c][3] - hint[c][1] for c in keys])
    for c, k in keys.items():
        if k["end"] == "first":
            k["x"] = k["right"] - key_w / 2.0
        elif k["end"] == "last":
            k["x"] = k["left"] + key_w / 2.0
        else:
            k["x"] = (k["left"] + k["right"]) / 2.0
        top_known, bottom_known = "top" not in k["assumed"], "bottom" not in k["assumed"]
        if top_known and bottom_known:
            k["y"] = (k["top"] + k["bottom"]) / 2.0
        elif bottom_known:
            k["y"] = k["bottom"] - key_h / 2.0
        elif top_known:
            k["y"] = k["top"] + key_h / 2.0
        else:
            k["y"] = (hint[c][1] + hint[c][3]) / 2.0
    keys["_w"], keys["_h"] = key_w, key_h


NAMED = {"question mark": "?", "exclamation": "!", "exclamation mark": "!", "exclamation point": "!", "apostrophe": "'", "comma": ",", "period": ".", "full stop": "."}


def tree_keys(xml):
    """Every key of the keyboard's window that the accessibility tree names with one character, or by a mark's
    name (Gboard's ? is "Question mark"): {character: [l, t, r, b]}."""
    keys = {}
    for desc, _, b in ime_nodes(xml):
        c = desc if len(desc) == 1 else NAMED.get(desc.lower())
        if c and c not in keys:
            keys[c] = b
    return keys


def marks(p, ime, hint, symbols_hint):
    """How each of . , ? ! is typed on this keyboard, found by typing it: the taps to make, in order. A mark with a
    key of its own on the letters is one tap. Any other is on the ?123 page: the ?123 key, the mark, and, where the
    keyboard does not go back to its letters by itself (asked by tapping q after it), the key that does."""
    def mid(r):
        return [round((r[0] + r[2]) / 2.0, 1), round((r[1] + r[3]) / 2.0, 1)]
    q = mid(hint["q"])
    out = {}
    for mark in (".", ",", "?", "!"):
        if mark in hint:
            got = p.sequence([mid(hint[mark]), q])
            if got.lower() != mark + "q":
                raise SystemExit("calibrate: the %r key and then q typed %r" % (mark, got))
            out[mark] = {"taps": [mid(hint[mark])], "on": "letters"}
            continue
        mode = mid(hint["symbols"])
        place = back = None
        if symbols_hint and mark in symbols_hint:
            place, back = mid(symbols_hint[mark]), mid(symbols_hint.get("letters", hint["symbols"]))
        else:
            # Ask the tree, with the ?123 page showing.
            p.sequence([mode])
            time.sleep(0.5)
            page = tree_keys(d.windows_dump())
            if mark not in page:
                raise SystemExit("calibrate: no %r on the ?123 page that the accessibility tree names" % mark)
            place, back = mid(page[mark]), mode
        got = p.sequence([mode, place, q])
        if got.lower() == mark + "q":
            out[mark] = {"taps": [mode, place], "on": "?123", "back": "by itself"}
            continue
        got = p.sequence([mode, place, back, q])
        if got.lower() != mark + "q":
            raise SystemExit("calibrate: ?123, %r, back and q typed %r" % (mark, got))
        out[mark] = {"taps": [mode, place, back], "on": "?123", "back": "by its key"}
    # A field started afresh is on the letters, whatever page the last one was left on.
    p.sequence([mid(hint["symbols"])])
    if p.sequence([q]).lower() != "q":
        raise SystemExit("calibrate: a field started after the ?123 page was showing is not on the letters")
    return out


def ghost_touch_model(hint_file, dpi, probes):
    """Ghost Keyboard's own reading of a touch, for thumb.GhostTouch: its cells (the hint, in the order its layout
    makes them) and KeyboardView.LETTER_OFFSETS_DP, read from the source. Refused unless it reads every calibration
    tap as the emulator did."""
    import thumb
    src = open(os.path.join(os.path.dirname(os.path.dirname(HERE)), "android", "app", "src", "main", "kotlin", "com", "infamousvague", "devkeyboard", "KeyboardView.kt"), encoding="utf-8").read()
    block = src.split("LETTER_OFFSETS_DP: Map<String, Pair<Float, Float>> = mapOf(", 1)[1].split("\n        )", 1)[0]
    offsets = {m.group(1): [float(m.group(2)), float(m.group(3))] for m in re.finditer(r'"(\w)" to \((-?[\d.]+)f to (-?[\d.]+)f\)', block)}
    if len(offsets) != 26:
        raise SystemExit("calibrate: read %d of 26 letters from KeyboardView.LETTER_OFFSETS_DP" % len(offsets))
    pairs = json.load(open(hint_file), object_pairs_hook=lambda p: p)
    cells = [[name, rect] for name, rect in dict(pairs)["keys"]]
    model = {"kind": "ghost-keyboard-view", "from": "KeyboardView.letterCorrection and keyAt, with LETTER_OFFSETS_DP read from the source", "density": dpi / 160.0, "offsets_dp": offsets, "cells": cells}
    reader = thumb.GhostTouch(model)
    wrong = []
    for x, y, typed in probes:
        name = reader.key(x, y)
        here = " " if name == "space" else name if name is not None and len(name) == 1 else ""
        if here != typed:
            wrong.append((x, y, typed, here))
    if wrong:
        raise SystemExit("calibrate: Ghost Keyboard's touch reading here disagrees with the emulator on %d of %d taps, the first %r" % (len(wrong), len(probes), wrong[0]))
    return model, len(probes)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("keyboard", choices=sorted(d.KEYBOARDS))
    ap.add_argument("--hint", help="a JSON file of key rectangles {name: [l, t, r, b]} to start from, where the accessibility tree has none")
    ap.add_argument("--out", help="where to write (default tool/versus/geometry/<keyboard>-<w>x<h>-<dpi>dpi.json)")
    args = ap.parse_args()
    kb = d.KEYBOARDS[args.keyboard]
    if not d.installed(kb["package"]):
        raise SystemExit("calibrate: %s is not installed on %s" % (kb["name"], d.SERIAL))
    where = d.front()
    if not (where.startswith(d.FIELD) or "launcher" in where.lower()):
        raise SystemExit("calibrate: %s is in front on %s; not touching it" % (where, d.SERIAL))
    w, h, dpi = screen()
    d.set_ime(kb["ime"])
    s = d.ready("cal0", "raw", ime=kb["ime"])
    # A keyboard just switched to slides into place: nothing is read or tapped until it has.
    time.sleep(1.5)
    tree = from_tree(d.windows_dump())
    if tree:
        hint, source = tree, "the accessibility tree"
    elif args.hint:
        hint, source = json.load(open(args.hint)), "a hint file (%s)" % os.path.basename(args.hint)
        hint = hint.get("keys", hint)
    else:
        raise SystemExit("calibrate: %s puts no keys in the accessibility tree; give --hint" % kb["name"])
    log("%s on %s (%dx%d, %d dpi): first idea of the keys from %s; keyboard %d px tall" % (kb["name"], d.SERIAL, w, h, dpi, source, s["height"]))
    keys, space, space_how, notes, prober = measure(kb["ime"], hint, w)
    taps = prober.count
    d.ready("cal-done", "raw", ime=kb["ime"])

    aim(keys, hint)
    key_w, key_h = keys.pop("_w"), keys.pop("_h")
    hint_all = json.load(open(args.hint)) if (args.hint and not tree) else {}
    how_marks = marks(prober, kb["ime"], hint, hint_all.get("symbols_page"))
    taps = prober.count
    # How far the point a thumb aims at is from the middle of the hint's rectangle for the key, in key sizes: the
    # keyboard's own idea of where thumbs land. The hint's rectangle is the key's cell (Gboard) or the key as laid
    # out before any touch correction (Ghost Keyboard).
    shift = {c: [round((keys[c]["x"] - middle_x(c, hint, key_w)) / key_w, 3), round((keys[c]["y"] - (hint[c][1] + hint[c][3]) / 2.0) / key_h, 3)] for c in keys}
    out = {
        "keyboard": args.keyboard,
        "name": kb["name"],
        "version": d.version(kb["package"]),
        "device": {"serial": d.SERIAL, "width": w, "height": h, "dpi": dpi, "model": d.sh("getprop ro.product.model").strip(), "android": d.sh("getprop ro.build.version.release").strip()},
        "measured": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "first_idea_from": source,
        "taps": taps,
        "keyboard_height": s["height"],
        "key_w": round(key_w, 2),
        "key_h": round(key_h, 2),
        "keys": {c: {k: (round(v, 1) if isinstance(v, float) else v) for k, v in keys[c].items()} for c in sorted(keys)},
        "space": dict({k: round(v, 1) for k, v in space.items()}, how=space_how),
        "marks": how_marks,
        "never_tapped": {n: hint[n] for n in ("shift", "delete", "symbols", "emoji", "enter", "dev") if n in hint},
        "hint": {c: hint[c] for c in sorted(hint)},
        "aim_off_cell_middle": shift,
        "notes": notes,
    }
    if args.keyboard == "ghost" and args.hint and not tree:
        out["touch_model"], agreed = ghost_touch_model(args.hint, dpi, prober.log)
        log("  Ghost Keyboard's own reading of a touch agrees with the emulator on all %d calibration taps" % agreed)
    out["probes"] = prober.log
    path = args.out or os.path.join(HERE, "geometry", "%s-%dx%d-%ddpi.json" % (args.keyboard, w, h, dpi))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(compact(out))
    worst = max(shift.items(), key=lambda kv: abs(kv[1][0]) + abs(kv[1][1]))
    log("%s: key %.1f px wide, %.1f px tall; %d taps; the aim furthest from the middle of its key's cell: %s by %+.2f, %+.2f of a key" % (kb["name"], key_w, key_h, taps, worst[0], worst[1][0], worst[1][1]))
    for n in notes:
        log("  note: " + n)
    log("wrote " + os.path.relpath(path))


if __name__ == "__main__":
    main()
