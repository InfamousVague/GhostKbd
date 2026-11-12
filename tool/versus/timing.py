#!/usr/bin/env python3
"""Was a sentence typed as it was meant to be: every touch lifted when asked?

A touch held 300 ms is a long-press on both keyboards, and types the key's other character ("t" becomes "5"). The
player asks for 55 to 95 ms, but the emulator is a guest of this Mac: when the Mac is short of disk or busy
building something else, the emulator stops for a moment in the middle of a touch and the hold is as long as the
stop. On 2026-11-11 that happened in two spells and put digits in 32 sentences. Such a sentence says nothing of
either keyboard, so it is typed again.

Two ways of knowing, the first exact and the second for records made before the first existed:

  held(record)   run.py's script reads the emulator's own clock ($EPOCHREALTIME, a shell variable, so reading it
                 costs nothing) before each touch goes down and after it has lifted. A touch whose two readings are
                 less than HELD ms apart cannot have been a long-press. Also says when the emulator stood still for
                 a second or more between touches.
  late(record)   from the times the field marked each key press at: a key press that came LATE ms or more after
                 its time and was never caught up is a stop that long, in a hold or between touches (which of the
                 two it cannot say, nor anything of a sentence's first touch).

    tool/versus/timing.py <run folder>               says which sentences are doubted, a keyboard at a time
    tool/versus/timing.py <run folder> --set-aside   and moves them out of <keyboard>.jsonl into
                                                     <keyboard>.stalled.jsonl, for run.py to type again
"""
import json
import os
import re
import sys

HELD = 250     # ms from before a touch goes down to after it lifts: under this it was no long-press (300 ms)
FROZEN = 1000  # ms more than asked from one touch to the next: the emulator stood still
LATE = 150     # ms a key press may come late for good, by the field's times, before the sentence is doubted
OTHER = re.compile(r"[^A-Za-z ,.?!'’\n-]")


def held(record):
    """Why the sentence is to be typed again, by the emulator's clock, or None. None too where there are no readings."""
    stamps = record.get("clock")
    if not stamps:
        return None
    worst = max(b - a for a, b in stamps)
    if worst >= HELD:
        return "a touch took %d ms from going down to lifting" % worst
    touches = record["touches"]
    if len(stamps) == len(touches):
        for (a0, _), (a1, _), t in zip(stamps, stamps[1:], touches):
            if (a1 - a0) - 1000 * t["period"] >= FROZEN:
                return "the emulator stood still for %d ms between two touches" % ((a1 - a0) - 1000 * t["period"])
    return None


def presses(record, merge=40):
    """When each key press changed the field: changes within [merge] ms of one another are one press's (a
    correction and the space that set it off)."""
    out = []
    last = None
    for ms, _ in record["typed"]:
        if last is None or ms - last >= merge:
            out.append(ms)
        last = ms
    return out


def lifts(record):
    """When each touch that types was to lift, in ms from the first touch going down."""
    out, t = [], 0.0
    for u in record["touches"]:
        if u["kind"] != "mark" or u.get("types", True):
            out.append(1000.0 * (t + u["hold"]))
        t += u["period"]
    return out


def late(record):
    """Why the sentence is doubted, by the field's times, or None."""
    c, u = presses(record), lifts(record)
    # A keyboard may change the field twice for one key press, a little apart: the nearer of two is not a press.
    while len(c) > len(u) and len(c) > 1:
        k = min(range(1, len(c)), key=lambda i: c[i] - c[i - 1])
        del c[k]
    if len(c) < len(u):
        return "%d of its touches changed nothing in the field" % (len(u) - len(c))
    if len(c) < 3:
        return None
    over = [(c[k + 1] - c[k]) - (u[k + 1] - u[k]) for k in range(len(c) - 1)]
    # The pace kept is a few ms off the pace asked, the same every touch: taken from the quicker touches, so that a
    # sentence slow throughout does not hide a stop in it.
    base = sorted(over)[len(over) // 4]
    behind = [0.0]
    for v in over:
        behind.append(behind[-1] + v - base)
    # What is never caught up: a keyboard slow over one word is behind for a key press and then on time again.
    for k in range(len(behind) - 2, -1, -1):
        behind[k] = min(behind[k], behind[k + 1])
    worst = max(b - a for a, b in zip(behind, behind[1:]))
    if worst >= LATE:
        return "a key press came %d ms late" % worst
    return None


def doubt(record):
    """Why a record is to be typed again, or None. A record with the emulator's own clock in it is judged by that
    alone; an older one by the field's times, and by a character no tap of the thumb can type (a long-press of a
    sentence's first touch shows no other way)."""
    if record.get("clock"):
        return held(record)
    why = late(record)
    if why is None and OTHER.search(record["out"]):
        why = "a character no tap types (%s)" % "".join(sorted(set(OTHER.findall(record["out"]))))
    return why


def main():
    args = [a for a in sys.argv[1:] if a != "--set-aside"]
    if len(args) != 1:
        raise SystemExit(__doc__)
    aside = "--set-aside" in sys.argv[1:]
    for name in sorted(os.listdir(args[0])):
        if not name.endswith(".jsonl") or name.count(".") != 1:
            continue
        path = os.path.join(args[0], name)
        records = [json.loads(line) for line in open(path)]
        timed = [r for r in records if r.get("clock")]
        doubted = [(r, doubt(r)) for r in records]
        print("%s: %d sentences, %d with the emulator's clock in them; to type again: %d" % (name[:-6], len(records), len(timed), sum(1 for _, w in doubted if w)))
        for r, w in doubted:
            if w:
                print("    %s %d: %s | %s" % (r["thumb"], r["line"], w, r["out"]))
        if aside and any(w for _, w in doubted):
            with open(path[:-6] + ".stalled.jsonl", "a") as f:
                for r, w in doubted:
                    if w:
                        f.write(json.dumps(dict(r, doubt=w), ensure_ascii=False) + "\n")
            with open(path + ".tmp", "w") as f:
                for r, w in doubted:
                    if not w:
                        f.write(json.dumps(r, ensure_ascii=False) + "\n")
            os.replace(path + ".tmp", path)


if __name__ == "__main__":
    main()
