#!/usr/bin/env python3
"""The second look end to end, through the replay's Typist: tool/versus/again_check.py

AgainTest holds Autocorrect.again alone and AgainSourceTest the service's lines; this holds what the three share,
typed: "we ate going " tapped on the keys of tool/versus/geometry/ghost-1248x1972-420dpi.json, the r of "are"
tapped a fifth of a key left of the t's middle, through tool/versus/replay.sh (the decoder as it is in this tree).
(It was a tenth until the place's review was merged: a tap that near the middle is "well inside its key"
(Autocorrect.CLEAN_TAP), so "ate" is weighed with no place, and the capped lift alone then makes it "are" at its own
space, second look or not.)
With the second look on the field must read "We are going ", with Autocorrect.AGAIN=false "We ate going ". And a
word the word after bears out is never changed: "we are going ", "we ate dinner ". Exit status 0 when all hold.
Run by tool/versus/unit.sh.
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import score  # noqa: E402

GEOMETRY = os.path.join(HERE, "geometry", "ghost-1248x1972-420dpi.json")


def line(text, off):
    """[text] tapped at the middle of each key ("_" a tap on the t moved [off] key widths left), as replay.sh reads it."""
    geo = json.load(open(GEOMETRY))
    keys, space, w = geo["keys"], geo["space"], geo["key_w"]
    parts, ms = [], 0
    for c in text:
        if c == " ":
            x, y = (space["left"] + space["right"]) / 2, (space["top"] + space["bottom"]) / 2
        elif c == "_":
            x, y = keys["t"]["x"] - off * w, keys["t"]["y"]
        else:
            x, y = keys[c]["x"], keys[c]["y"]
        parts.append("T:%.1f:%.1f:%d" % (x, y, ms))
        ms += 180
    return " ".join(parts)


def typed(text, off, dials=()):
    extra = ["--board", score.board_file(GEOMETRY)] + [x for d in dials for x in ("--set", d)]
    env = dict(os.environ)
    env.pop("VERSUS_OVERLAY", None)
    p = subprocess.run(["sh", os.path.join(HERE, "replay.sh"), "replay", "1248", "1972", "420"] + extra, input="t/1\t%s\n" % line(text, off),
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, env=env, cwd=ROOT)
    if p.returncode != 0:
        raise SystemExit("again_check: replay.sh failed: " + p.stderr[-600:])
    return p.stdout.split("\t")[1]


def main():
    checks = [
        ("we a_e going ", 0.2, (), "We are going "),
        ("we a_e going ", 0.2, ("Autocorrect.AGAIN=false",), "We ate going "),
        ("we are going ", 0.0, (), "We are going "),
        ("we a_e dinner ", 0.2, (), "We ate dinner "),
    ]
    bad = 0
    for text, off, dials, want in checks:
        got = typed(text, off, dials)
        ok = got == want
        bad += not ok
        print("again_check: %-4s %r %s -> %r (want %r)" % ("ok" if ok else "FAIL", text.replace("_", "t"), " ".join(dials), got, want))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
