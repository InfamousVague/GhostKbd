#!/usr/bin/env python3
"""Types the sentences into the versus field with one keyboard, a thumb at a time, and records what it left there.

    tool/versus/run.py <keyboard> --out build/versus/runs/<name> [--sentences 300] [--thumbs careful,leaning,sloppy]
                                  [--only <file>]     only the typings listed there, a "<thumb> <line>" a line
                                                      (tool/versus/whatif.py --changed writes such a file)

For every thumb and sentence: the field is emptied (a new field, so the keyboard starts as it does on a new
message), the thumb's taps are injected on the emulator at a person's pace (a touch down, held 55 to 95 ms, lifted;
150 to 250 ms from one touch to the next), and the field's own record of what each key press did is read back. One
line a sentence goes to <out>/<keyboard>.jsonl: the taps as placed on this keyboard, the text after every key
press, and the text that stood. A run can be stopped and started again: sentences already there are not typed twice.

Before each sentence the field's record must say the keyboard under test is the one in use, that the field is in
front with the keyboard up at the height it was measured at; afterwards that nothing took the screen meanwhile.
Anything else and the sentence is typed again; three times and the run stops, saying why. Every so often the
keyboard's installed build is checked against the one the run began with, and the field is read a second way
(uiautomator) to hold the first to.

Every touch is timed on the emulator's own clock (timing.py). A touch that took 250 ms or more from going down to
lifting may have been a long-press (the emulator stops for a moment when this Mac is busy), and its sentence is
typed again, up to six times; what was thrown away goes to <out>/<keyboard>.stalled.jsonl with the reason. A
sentence that never got through is recorded with "stalled", and score.py leaves it out for every keyboard.
"""
import argparse
import datetime
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import device as d  # noqa: E402
import thumb as th  # noqa: E402
import timing  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
# What a touch costs beyond the sleeps asked for, measured on the emulator from the times the field marked each key
# press at (a pilot's taps came 16 ms later each than asked with the first guess, 13 and 8): the two `input`
# commands, and a sleep's overshoot. score.py prints the pace that was really kept.
INPUT_PAIR = 0.021
SLEEP_OVER = 0.012


def log(*a):
    print(datetime.datetime.now().strftime("%H:%M:%S"), *a, flush=True)


def geometry_for(keyboard):
    size = d.sh("wm size").split()[-1]
    dpi = d.sh("wm density").split()[-1]
    path = os.path.join(HERE, "geometry", "%s-%s-%sdpi.json" % (keyboard, size, dpi))
    if not os.path.exists(path):
        raise SystemExit("run: no %s; measure the keyboard first: tool/versus/calibrate.py %s" % (os.path.relpath(path), keyboard))
    geo = json.load(open(path))
    geo["_path"] = path
    return geo


def wait_calm(why, longest=1200):
    """Waits until the emulator keeps time again (timing.calm), up to [longest] seconds: typing into a stall only
    throws sentences away. Says so when it has to wait."""
    began = time.time()
    said = False
    while True:
        m = timing.calm()
        if timing.is_calm(m):
            if said:
                log("  the emulator keeps time again (after %d s)" % (time.time() - began))
            return True
        if not said:
            log("  %s: the emulator is not keeping time (a 70 ms sleep %.0f ms late at the ninth in ten, %.0f at the worst); waiting" % (why, m[1], m[2]))
            said = True
        if time.time() - began > longest:
            log("  still not keeping time after %d s; going on all the same" % longest)
            return False
        time.sleep(10)


def script(tag, touches, settle=0.7, after=0.45):
    """One shell command: a new field, the touches at their pace, and the field's record. Nothing is touched for
    [settle] seconds after the field is entered: a keyboard's keys take about 0.3 s to be where they will be.
    Around each touch the emulator's clock is read (a shell variable: no command is run for it) and printed as a
    line "P <before it went down> <after it lifted>", for timing.py."""
    parts = ["am start -n %s --es tag %s --es mode plain >/dev/null" % (d.FIELD_ACTIVITY, tag), "sleep %.2f" % settle]
    for t in touches:
        hold = max(0.02, t["hold"] - SLEEP_OVER)
        gap = max(0.02, t["period"] - t["hold"] - INPUT_PAIR - SLEEP_OVER)
        parts.append("a=$EPOCHREALTIME")
        parts.append("input motionevent DOWN %.1f %.1f" % (t["x"], t["y"]))
        parts.append("sleep %.3f" % hold)
        parts.append("input motionevent UP %.1f %.1f" % (t["x"], t["y"]))
        parts.append('echo "P $a $EPOCHREALTIME"')
        parts.append("sleep %.3f" % gap)
    parts.append("sleep %.2f" % after)
    parts.append("run-as %s cat files/events.log" % d.FIELD)
    return "; ".join(parts)


def clock(out):
    """Each touch's two readings of the emulator's clock, in ms from the first: [[before down, after up], ...]."""
    stamps = []
    for line in out.splitlines():
        f = line.split(" ")
        if len(f) == 3 and f[0] == "P":
            try:
                stamps.append((float(f[1]), float(f[2])))
            except ValueError:
                pass
    if not stamps:
        return []
    zero = stamps[0][0]
    return [[round(1000 * (a - zero), 1), round(1000 * (b - zero), 1)] for a, b in stamps]


def parse(out):
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


def wrong(rows, tag, ime, height):
    """Why this record cannot be trusted, or None."""
    resets = [r for r in rows if r[0] == "R"]
    if len(resets) != 1 or resets[0][2] != tag:
        return "the record is not this sentence's"
    if resets[0][5] != ime:
        return "the keyboard in use is %s" % resets[0][5]
    if not resets[0][6] and not any(r[0] == "F" and r[2] for r in rows):
        return "the field was not in front"
    if any(r[0] == "F" and not r[2] for r in rows):
        return "something took the screen from the field"
    seen = [r for r in rows if r[0] == "I"]
    if not seen or not seen[-1][2]:
        return "the keyboard was not showing"
    first_key = min([r[1] for r in rows if r[0] == "T"] or [0])
    for r in seen:
        if r[1] > first_key > 0 and (not r[2] or r[3] != height):
            return "the keyboard's height changed while typing (%d px)" % r[3]
    if seen[-1][3] != height:
        return "the keyboard is %d px tall, not the %d it was measured at" % (seen[-1][3], height)
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("keyboard", choices=sorted(d.KEYBOARDS))
    ap.add_argument("--out", required=True)
    ap.add_argument("--sentences", type=int, default=300)
    ap.add_argument("--thumbs", default="careful,leaning,sloppy")
    ap.add_argument("--seed", default="7")
    ap.add_argument("--first", type=int, default=th.FIRST_LINE, help="the line of the sentence file after which sentences are taken")
    ap.add_argument("--file", default=th.HELD_OUT, help="the sentences, one a line (default build/typing-bench/en-heldout.txt)")
    ap.add_argument("--aim", default="answers", choices=["answers", "drawn"])
    ap.add_argument("--learn", action="store_true", help="let the keyboard learn from the field (it is asked not to by default)")
    ap.add_argument("--geometry", help="the keyboard's keys, where they are not the file in tool/versus/geometry (calibrate.py --out)")
    ap.add_argument("--only", help="a file of \"<thumb> <line>\" lines: of the sentences and thumbs asked for, only these are typed")
    args = ap.parse_args()
    kb = d.KEYBOARDS[args.keyboard]
    if not d.installed(kb["package"]):
        raise SystemExit("run: %s is not installed on %s" % (kb["name"], d.SERIAL))
    where = d.front()
    if not (where.startswith(d.FIELD) or "launcher" in where.lower()):
        raise SystemExit("run: %s is in front on %s; not touching it" % (where, d.SERIAL))
    if args.geometry:
        geo = json.load(open(args.geometry))
        geo["_path"] = args.geometry
    else:
        geo = geometry_for(args.keyboard)
    board = th.Board(geo)
    sents, skipped = th.sentences(args.sentences, path=args.file, first=args.first)
    if len(sents) < args.sentences:
        # A set that is not there is no set: the "final" one was once asked for past the end of the file, and typed nothing.
        raise SystemExit("run: %s has only %d sentences the thumb can type after line %d, not the %d asked for" % (args.file, len(sents), args.first, args.sentences))
    thumbs = [th.THUMBS[n] for n in args.thumbs.split(",")]
    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, args.keyboard + ".jsonl")
    # The keyboard as it was measured goes with the run: the file in tool/versus/geometry is the next build's soon.
    kept = os.path.join(args.out, args.keyboard + ".geometry.json")
    if not os.path.exists(kept):
        import shutil
        shutil.copyfile(geo["_path"], kept)
    done = set()
    if os.path.exists(path):
        for line in open(path):
            try:
                r = json.loads(line)
                # One that never got through is tried again when the run is taken up (score.py takes the later).
                if not r.get("stalled"):
                    done.add((r["thumb"], r["line"]))
            except ValueError:
                pass
    if d.press_wait():
        log("an \"isn't responding\" dialog was up (the emulator has been starved); Wait pressed")
    d.set_ime(kb["ime"])
    version = d.version(kb["package"])
    stamp = d.install_stamp(kb["package"])
    if version["sha256"] != geo["version"]["sha256"]:
        raise SystemExit("run: %s was measured as build %s and is now %s: measure it again" % (kb["name"], geo["version"]["sha256"][:12], version["sha256"][:12]))
    state = d.ready("warm", "plain", ime=kb["ime"])
    # A keyboard just switched to slides into place: nothing is typed until it has.
    time.sleep(2.0)
    state = d.ready("warm2", "plain", ime=kb["ime"])
    time.sleep(1.0)
    if state["height"] != geo["keyboard_height"]:
        raise SystemExit("run: the keyboard is %d px tall, and was measured at %d" % (state["height"], geo["keyboard_height"]))
    meta = {
        "keyboard": args.keyboard, "name": kb["name"], "version": version, "geometry": os.path.basename(geo.get("_path", "")),
        "geometry_measured": geo.get("measured", ""),
        "device": geo["device"], "started": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "sentences": len(sents), "file": os.path.relpath(args.file), "first_line": args.first, "skipped": skipped, "seed": args.seed, "aim": args.aim, "learn": args.learn,
        "thumbs": {t.name: t.describe() for t in thumbs},
    }
    # A run taken up again keeps when it first began, and says when it was taken up.
    before = {}
    if done and os.path.exists(os.path.join(args.out, args.keyboard + ".meta.json")):
        before = json.load(open(os.path.join(args.out, args.keyboard + ".meta.json")))
        if before.get("version", {}).get("sha256") != version["sha256"]:
            raise SystemExit("run: %s holds sentences typed on another build of %s (%s); start a new folder" % (args.out, kb["name"], before.get("version", {}).get("sha256", "?")[:12]))
        meta["taken_up_again"] = before.get("taken_up_again", []) + [meta["started"]]
        meta["started"] = before.get("started", meta["started"])
    with open(os.path.join(args.out, args.keyboard + ".meta.json"), "w") as f:
        json.dump(meta, f, indent=1)
        f.write("\n")
    todo = [(t, s) for t in thumbs for s in sents if (t.name, s.line_no) not in done]
    if args.only:
        # In the file's own order: the ones that matter most can be put first.
        wanted = [(f[0], int(f[1])) for f in (line.split() for line in open(args.only)) if len(f) >= 2 and f[1].isdigit()]
        place = {k: i for i, k in reversed(list(enumerate(wanted)))}
        todo = sorted([(t, s) for t, s in todo if (t.name, s.line_no) in place], key=lambda ts: place[(ts[0].name, ts[1].line_no)])
    log("%s %s on %s: %d sentences x %d thumbs, %d to type (%d already there)" % (kb["name"], version["versionName"], d.SERIAL, len(sents), len(thumbs), len(todo), len(done)))
    wait_calm("before the first sentence")
    started = time.time()
    with open(path, "a") as out, open(os.path.join(args.out, args.keyboard + ".stalled.jsonl"), "a") as aside:
        for n, (t, s) in enumerate(todo):
            taps, slips = th.plan(s, t, args.seed)
            touches = board.place(taps, args.aim)
            tag = "%s-%d" % (t.name, s.line_no)
            command = script(tag, touches).replace("--es mode plain", "--es mode plain --ez learn true" if args.learn else "--es mode plain")
            why = stalled = record = None
            attempt = 0
            while True:
                attempt += 1
                text = d.sh(command, timeout=300)
                rows = parse(text)
                why = wrong(rows, tag, kb["ime"], geo["keyboard_height"])
                stalled = None
                if why is None:
                    keys = [(r[1], r[2]) for r in rows if r[0] == "T"]
                    record = {
                        "keyboard": args.keyboard, "thumb": t.name, "line": s.line_no, "target": s.text, "words": s.words,
                        "touches": touches, "slips": slips, "typed": keys, "out": keys[-1][1] if keys else "", "clock": clock(text),
                    }
                    stalled = timing.held(record) if len(record["clock"]) == len(touches) else "the emulator's clock was read for %d of %d touches" % (len(record["clock"]), len(touches))
                    if stalled is None:
                        break
                    aside.write(json.dumps(dict(record, doubt=stalled, attempt=attempt), ensure_ascii=False) + "\n")
                    aside.flush()
                if attempt >= (3 if why else 6):
                    break
                log("  %s: %s; again" % (tag, why or stalled))
                if why and d.press_wait():
                    log("  an \"isn't responding\" dialog had taken the screen; Wait pressed")
                time.sleep(2)
                if stalled:
                    wait_calm(tag)
                else:
                    time.sleep(3 * min(attempt, 4))
                if d.current_ime() != kb["ime"]:
                    raise SystemExit("run: stopped at %s: the keyboard in use is no longer %s" % (tag, kb["name"]))
                d.ready("again", "plain", ime=kb["ime"])
            if why is not None:
                raise SystemExit("run: stopped at %s: %s" % (tag, why))
            if stalled is not None:
                log("  %s: %s, six times over; recorded as stalled, and left out of the score" % (tag, stalled))
                record["stalled"] = stalled
            if attempt > 1:
                record["attempts"] = attempt
            if n % 50 == 49:
                # The second way of reading the field, and the build still the one the run began with.
                record["uiautomator"] = d.field_text_by_uiautomator()
                if d.install_stamp(kb["package"]) != stamp:
                    raise SystemExit("run: stopped at %s: %s was installed over while the run was going" % (tag, kb["name"]))
            out.write(json.dumps(record, ensure_ascii=False) + "\n")
            out.flush()
            if n % 25 == 24 or n == len(todo) - 1:
                rate = (time.time() - started) / (n + 1)
                log("  %d of %d (%s); %.1f s a sentence, about %d min to go" % (n + 1, len(todo), tag, rate, rate * (len(todo) - n - 1) / 60))
    meta["finished"] = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
    # Everything thrown away for a stall, this time and before (timing.py --set-aside's too).
    meta["typings_thrown_away_for_a_stall"] = sum(1 for _ in open(os.path.join(args.out, args.keyboard + ".stalled.jsonl")))
    meta["version_at_end"] = d.version(kb["package"])
    with open(os.path.join(args.out, args.keyboard + ".meta.json"), "w") as f:
        json.dump(meta, f, indent=1)
        f.write("\n")
    d.ready("done", "plain", ime=kb["ime"])
    log("%s: done, %s" % (kb["name"], os.path.relpath(path)))


if __name__ == "__main__":
    main()
