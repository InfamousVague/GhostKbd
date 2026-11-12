#!/usr/bin/env python3
"""Why two words run together were, or were not, parted: tool/versus/probe.py [seen|unseen] [--thumb NAME] [--line N] [--show N]

For every stretch of a typing in which the space landed on a letter or was never typed (so that the keyboard was
handed "hasvtwnspnitid" for "has tendonitis"), prints what the decoder in this tree weighed (tool/versus/probe/Probe.kt):
what it made of the stretch, what the reading that was meant would have cost it word by word and tap by tap, and the
best joins and single words it found itself. The last line says how many such stretches there were and how many were
parted right.

  seen      the 900 typings of the run of 2026-11-11 (the default)
  unseen    tool/versus/scoreboard.py's 900 other typings: for counting, not for designing from
  words     words on the input, one a line, each typed with every tap in the middle of its key: what a name or a
            term no list has could be parted into, and how near the bar that comes
  --thumb   careful, leaning or sloppy alone
  --line N  one sentence (the fixtures' "line")
  --show N  how many of the decoder's own readings to print (5)

The JVM only: no emulator. Compiled into build/versus/probe.jar with the decoder's sources as they stand.
"""
import argparse
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import score  # noqa: E402
import scoreboard  # noqa: E402


def line_of(rec):
    """score.events_line, with what each event was meant as beside it."""
    parts, meant, ms = [], [], 0
    for t in rec["touches"]:
        if t["kind"] == "mark":
            many = sum(1 for u in rec["touches"] if u["kind"] == "mark" and u["word"] == t["word"] and u["meant"] == t["meant"]) > 1
            if many:
                if t.get("types"):
                    parts.append("M:%s:%d" % (t["meant"], ms))
                    meant.append("m")
            else:
                parts.append("T:%.1f:%.1f:%d" % (t["x"], t["y"], ms))
                meant.append("m")
        else:
            parts.append("T:%.1f:%.1f:%d" % (t["x"], t["y"], ms))
            meant.append("k%d:%s" % (t["word"], t["meant"]) if t["kind"] == "key" else "s%d" % t["word"])
        ms += int(round(t["period"] * 1000))
    return "%s/%d\t%s\t%s\t%s" % (rec["thumb"], rec["line"], " ".join(parts), " ".join(meant), " ".join(rec["words"]))


def jar():
    src = "android/app/src/main/kotlin/com/infamousvague/devkeyboard"
    files = [src + "/" + n for n in ("Languages.kt", "Layouts.kt", "Hangul.kt", "Autocorrect.kt", "SwipeDecoder.kt", "TapDecoder.kt", "MistakeMemory.kt", "SampleCleaner.kt", "NextWordModel.kt")]
    files += ["tool/lang_check/stubs/android/graphics/PointF.kt", "tool/versus/replay/Replay.kt", "tool/versus/probe/Probe.kt"]
    out = os.path.join("build", "versus", "probe.jar")
    full = os.path.join(ROOT, out)
    if not os.path.exists(full) or any(os.path.getmtime(os.path.join(ROOT, f)) > os.path.getmtime(full) for f in files):
        os.makedirs(os.path.dirname(full), exist_ok=True)
        p = subprocess.run(["kotlinc"] + files + ["-include-runtime", "-d", out], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
        errors = [ln for ln in p.stdout.splitlines() if "error:" in ln]
        if errors or not os.path.exists(full):
            raise SystemExit("probe: did not compile\n" + "\n".join(errors[:20]))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("which", nargs="?", default="seen", choices=["seen", "unseen", "words"])
    ap.add_argument("--thumb")
    ap.add_argument("--line", type=int)
    ap.add_argument("--show", type=int, default=5)
    args = ap.parse_args()
    board = score.board_file(scoreboard.GEOMETRY)
    if args.which == "words":
        text = sys.stdin.read()
    else:
        records = scoreboard.kept("ghost.jsonl.gz") if args.which == "seen" else scoreboard.unseen(scoreboard.UNSEEN_FIRST, 300)
        text = "\n".join(line_of(r) for r in records if (not args.thumb or r["thumb"] == args.thumb) and (not args.line or r["line"] == args.line)) + "\n"
    p = subprocess.run(["java", "-Xmx2g", "-cp", jar(), "Probe", ".", board, str(args.show)] + (["words"] if args.which == "words" else []), cwd=ROOT, input=text, stdout=subprocess.PIPE, universal_newlines=True)
    sys.stdout.write(p.stdout)
    if p.returncode != 0:
        raise SystemExit(p.returncode)


if __name__ == "__main__":
    main()
