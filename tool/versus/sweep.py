#!/usr/bin/env python3
"""Many settings of the decoder's dials, a few seconds each: tool/versus/sweep.py dials | line | grid | scan | search | check

scoreboard.py --set starts a JVM, reads the word lists and types everything, for each setting it is asked about.
This keeps one JVM (tool/versus/replay.sh sweep: replay/Sweep.kt) that has read everything once and types it all
again for every setting it is sent, on several threads: the seen and unseen sets as scoreboard.py has them, and the
typing bench's tap test (12,000 words by three thumbs). Its numbers are scoreboard.py's, to the word.

  sweep.py dials                      every dial there is (a @JvmField var of the decoder's companion objects, found
                                      by reflection), what the tree sets it to, and which a search takes unasked
  sweep.py line [SETTING ...]         each setting as scoreboard.py --bench prints it, with what the objective makes
                                      of it. A SETTING is one argument: "Autocorrect.UNKNOWN=2.4 TapDecoder.OMIT=1.8";
                                      "-" is the tree as it is
  sweep.py grid DIAL[=VALUES] [DIAL[=VALUES]] [--with SETTING]
                                      one dial: a row a value. Two: a table. VALUES are 1.6,2.0,2.4 or 1.6:3.2:0.4
                                      (from, to, step); left out, seven values about the tree's
  sweep.py scan [DIAL ...] [--with SETTING] [--step 0.4]
                                      each dial alone, a step down and a step up: which of them these tests can
                                      feel, and which way. With no DIAL, the dials a search takes
  sweep.py search [DIAL[=FROM:TO[:STEP]] ...] [--from SETTING] [--step 0.4] [--halvings 2] [--least 1]
                                      many dials at once, by coordinate descent with a shrinking step: each dial in
                                      turn is tried a step down and a step up, and moved where that lowers the
                                      objective by --least or more (and on the same way while it still does); when
                                      a whole round moves nothing the steps are halved, --halvings times. With no
                                      DIAL, every number of Autocorrect and TapDecoder that these tests can see
  sweep.py check [SETTING ...]        holds this tool to scoreboard.py --bench for the tree and three other settings
                                      (or those given): the same lines, and the same verdict on every word

The objective, which a search makes as small as it can: the words left wrong on seen and unseen together, and a
penalty for each figure of the bench that is worse than the tree's own, so that words cannot be bought on these
sentences by spoiling right ones. For each of the bench's three thumbs: 4 for every right word spoiled over the
tree's count (the bench's own measure counts one four times as bad as a slip left unmended), 4 more for every
spoiled one that no list has (the bench has 3% of such words, and a person's names are theirs to type again), 1 for
every word right under the tree's; and 1 for every misspelling mended under the tree's. --weights changes them:
"spoiled=4,unknown=4,right=1,misspellings=1,seen=1,unseen=1,bench=0" (bench=W adds W times the bench's words wrong,
each spoiled one four times over, to the objective whether or not they are worse than the tree's).

  --holdout [SEED]   fit on a random half of each set's sentences (a sentence goes whole, all three thumbs) and
                     report the other half beside it: a setting that was found gains on both, one that was fitted
                     gains on the half it saw. --other-half fits on the half this leaves out
  --threads N        how many threads type a setting (4). Settings are tried one at a time: the dials are static,
                     and a setting's typings are independent of each other, so the threads share one setting
  --bench-words N    the bench's words (12000, as scoreboard.py --bench has it); 0 leaves the bench out
  --overlay DIR, --java OPTS     as tool/versus/whatif.py has them

Every answer is kept under build/versus/sweep/, by what was compiled and what was typed, so a grid or a search run
again, or run on after being stopped, asks only for what it has not seen. Nothing here can read the held-out
sentences from line 110,001 on, which are kept for the end (scoreboard.py --final): the unseen set is made from a
copy of the file that stops at line 110,000, and Sweep.kt reads no further.
"""
import argparse
import hashlib
import itertools
import json
import os
import random
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import score  # noqa: E402
import scoreboard  # noqa: E402
import thumb as th  # noqa: E402

WORK = os.path.join(ROOT, "build", "versus", "sweep")
# The last line of the held-out sentences this may read: those after it are scoreboard.py --final's.
LAST_LINE = min(110000, scoreboard.FINAL_FIRST)
SETS = ("seen", "unseen")
# What a search takes when it is given no dial: every number but these. Swipe typing's own are not asked for by a
# tap; the starter table's priors are next-word prediction's, which nothing here measures; and three that only the
# row above the keys or a person's remembered mistakes read, which no tap test here reaches.
NOT_SEARCHED = ("SwipeDecoder.", "Scorer.", "Autocorrect.FINISH_LEAST", "TapDecoder.FINISH_BUDGET", "Autocorrect.REMEMBERED")
# Dials that are read as a whole number (Autocorrect.LONG_WORD is a count of letters): stepped by ones.
WHOLE = ("Autocorrect.LONG_WORD",)
WEIGHTS = {"seen": 1.0, "unseen": 1.0, "spoiled": 4.0, "unknown": 4.0, "right": 1.0, "misspellings": 1.0, "bench": 0.0}
CHECKS = ["-", "Autocorrect.UNKNOWN_ODD=0", "Autocorrect.MARGIN_COMMON=0.6 Autocorrect.UNKNOWN=1.6", "TapDecoder.SPATIAL=1.0 TapDecoder.OMIT=1.8 TapDecoder.WHOLE_CHANGE=3"]


def note(text):
    sys.stderr.write(text + "\n")
    sys.stderr.flush()


def fmt(v):
    """A dial's value as it is written in a setting: 2.4, 3, true."""
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    text = ("%.4f" % v).rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def bounded_heldout():
    """The held-out sentences up to line LAST_LINE and not one further, as a file of their own: whatever reads
    sentences for this tool reads them from this."""
    dst = os.path.join(WORK, "en-heldout-to-%d.txt" % LAST_LINE)
    if not os.path.exists(dst) or os.path.getmtime(dst) < os.path.getmtime(th.HELD_OUT):
        os.makedirs(WORK, exist_ok=True)
        with open(th.HELD_OUT, encoding="utf-8") as f, open(dst + ".tmp", "w", encoding="utf-8") as g:
            g.writelines(itertools.islice(f, LAST_LINE))
        os.replace(dst + ".tmp", dst)
    return dst


_sentences = th.sentences


def _bounded_sentences(count, path=None, first=th.FIRST_LINE, most_words=20):
    if first >= LAST_LINE:
        raise SystemExit("sweep: the sentences from line %d on are kept for the end, and are not read here" % (LAST_LINE + 1))
    return _sentences(count, path=bounded_heldout(), first=first, most_words=most_words)


class Sets:
    """The seen and unseen sets exactly as scoreboard.py types them (its own kept() and unseen()), written for
    Sweep.kt: a line a typing, with the words that should stand and which of them the thumb typed as meant."""

    def __init__(self):
        # scoreboard.unseen asks thumb.sentences for its sentences: here it gets them from the bounded copy.
        th.sentences = _bounded_sentences
        try:
            self.records = {"seen": scoreboard.kept("ghost.jsonl.gz"), "unseen": scoreboard.unseen(scoreboard.UNSEEN_FIRST, 300)}
        finally:
            th.sentences = _sentences
        self.clean = {name: [score.classes(r, score.landed(r))[0] for r in self.records[name]] for name in SETS}
        self.gboard = {"%s/%d" % (r["thumb"], r["line"]): [[p == w, True, "", p or "", w] for p, w in zip(score.align(r["words"], score.out_words(r["out"]))[0], r["words"])]
                       for r in scoreboard.kept("gboard.jsonl.gz")}
        board = score.board_file(scoreboard.GEOMETRY)
        lines = ["board\t" + board]
        for name in SETS:
            for r, clean in zip(self.records[name], self.clean[name]):
                lines.append("typing\t%s\t%s\t%s\t%s\t%s" % (name, r["thumb"], " ".join(r["words"]), "".join("1" if c else "0" for c in clean), score.events_line(r)))
        text = "\n".join(lines) + "\n"
        self.digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
        self.path = os.path.join(WORK, "sets-%s.tsv" % self.digest[:12])
        if not os.path.exists(self.path):
            os.makedirs(WORK, exist_ok=True)
            with open(self.path + ".tmp", "w", encoding="utf-8") as f:
                f.write(text)
            os.replace(self.path + ".tmp", self.path)
        self.words = {name: sum(len(r["words"]) for r in self.records[name]) for name in SETS}

    def verdicts(self, name, bits):
        """A set's words as scoreboard.py keeps them ({"thumb/line": [[right, typed as meant, "", "", word], ...]})
        from the worker's answer, a 1 for each word left wrong: scoreboard.line and tally read these."""
        out, i = {}, 0
        for r, clean in zip(self.records[name], self.clean[name]):
            out["%s/%d" % (r["thumb"], r["line"])] = [[bits[i + j] == "0", clean[j], "", "", w] for j, w in enumerate(r["words"])]
            i += len(r["words"])
        if i != len(bits):
            raise SystemExit("sweep: the worker answered for %d words of %s, and the set has %d" % (len(bits), name, i))
        return out

    def halves(self, seed, other):
        """For each set, a True for every word of a sentence in the half that is fitted on: a random half of the
        set's sentences, each going whole with all its typings. [other] takes the other half."""
        out = {}
        for name in SETS:
            lines = sorted({r["line"] for r in self.records[name]})
            random.Random("%s|%s" % (seed, name)).shuffle(lines)
            fit = set(lines[:len(lines) // 2])
            out[name] = [(r["line"] in fit) != other for r in self.records[name] for _ in r["words"]]
        return out

    def by_sentence(self, name, bits, mask, keep):
        """{line: words wrong} over the words whose [mask] is [keep]."""
        out, i = {}, 0
        for r in self.records[name]:
            n = len(r["words"])
            if mask[i] == keep:
                out[r["line"]] = out.get(r["line"], 0) + bits.count("1", i, i + n)
            i += n
        return out


class Worker:
    """tool/versus/replay.sh sweep, kept running: a setting in, a line out."""

    def __init__(self, sets_path, bench_words, threads, env):
        os.makedirs(WORK, exist_ok=True)
        self.log = open(os.path.join(WORK, "worker.log"), "a")
        self.p = subprocess.Popen(["sh", os.path.join(HERE, "replay.sh"), "sweep", "--sets", sets_path, "--bench-words", str(bench_words), "--threads", str(threads)],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log, universal_newlines=True, bufsize=1, env=env)
        head = self.p.stdout.readline()
        if not head.startswith("ready\t"):
            raise SystemExit("sweep: the worker did not start; see %s" % self.log.name)
        self.ready = json.loads(head.split("\t", 1)[1])

    def ask(self, setting):
        self.p.stdin.write(setting + "\n")
        self.p.stdin.flush()
        line = self.p.stdout.readline()
        if "\t" not in line:
            raise SystemExit("sweep: the worker stopped; see %s" % self.log.name)
        return json.loads(line.split("\t", 1)[1])

    def close(self):
        try:
            self.p.stdin.close()
            self.p.wait(timeout=30)
        except Exception:
            self.p.kill()
        self.log.close()


class Sweep:
    """Answers for settings: from what is kept under build/versus/sweep/, else from the worker (started when first needed)."""

    def __init__(self, args):
        self.args = args
        self.env = dict(os.environ)
        self.env.pop("VERSUS_OVERLAY", None)
        if args.overlay:
            self.env["VERSUS_OVERLAY"] = args.overlay
        self.env["JAVA_OPTS"] = ((args.java + " ") if args.java else "") + self.env.get("JAVA_OPTS", "")
        # The dials, which also compiles the worker if a source is newer than its jar: once, before anything waits on it.
        p = subprocess.run(["sh", os.path.join(HERE, "replay.sh"), "sweep", "--dials"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, env=self.env)
        if p.returncode != 0 or not p.stdout.startswith("ready\t"):
            raise SystemExit("sweep: replay.sh sweep --dials failed: " + p.stderr[-1500:])
        self.dials = json.loads(p.stdout.split("\t", 1)[1])["dials"]
        self.defaults = {name: self.value(name, d["default"]) for name, d in self.dials.items()}
        self.worker = None
        self.asked = 0
        self.seconds = 0.0
        self.sets = None
        self.cache = {}
        self.cache_file = None

    def value(self, name, text):
        kind = self.dials[name]["type"]
        if kind == "switch":
            if text not in ("true", "false"):
                raise SystemExit("sweep: %s is a switch: true or false, not %s" % (name, text))
            return text == "true"
        try:
            return int(text) if kind == "int" else float(text)
        except ValueError:
            raise SystemExit("sweep: %s=%s is no number" % (name, text))

    def parse(self, text):
        """A setting as written ("Autocorrect.UNKNOWN=2.4 TapDecoder.OMIT=1.8", or "-") as {dial: value}."""
        out = {}
        for part in text.replace(",", " ").split():
            if part == "-":
                continue
            name, eq, v = part.partition("=")
            if not eq or name not in self.dials:
                raise SystemExit("sweep: %s: %s" % (part, "a setting is Name=value" if not eq else "no such dial (sweep.py dials lists them)"))
            out[name] = self.value(name, v)
        return out

    def canon(self, setting):
        """A setting as it is sent and kept: the dials that are not as the tree has them, by name."""
        return " ".join("%s=%s" % (n, fmt(v)) for n, v in sorted(setting.items()) if fmt(v) != fmt(self.defaults[n]))

    def load(self):
        """The sets, and what has been answered before for this build, these lists and these sentences."""
        if self.sets is not None:
            return
        self.sets = Sets()
        h = hashlib.sha256()
        jar = os.path.join(ROOT, "build", "versus", "replay%s-sweep.jar" % ("-" + os.path.basename(self.args.overlay.rstrip("/")) if self.args.overlay else ""))
        assets = os.path.join(ROOT, "android", "app", "src", "main", "assets")
        for path in [jar, self.sets.path, bounded_heldout()] + [os.path.join(assets, p) for p in ("dict/en.tsv", "dict/en_contractions.tsv", "dict/en_slang.txt", "ngram/en.tsv")]:
            with open(path, "rb") as f:
                h.update(hashlib.sha256(f.read()).digest())
        h.update(("%d|%s" % (self.args.bench_words, self.args.java or "")).encode("utf-8"))
        self.cache_file = os.path.join(WORK, "answers-%s.jsonl" % h.hexdigest()[:12])
        if os.path.exists(self.cache_file) and not self.args.no_cache:
            for line in open(self.cache_file, encoding="utf-8"):
                try:
                    kept = json.loads(line)
                except ValueError:
                    continue  # a line cut short when a run was stopped
                self.merge(kept["setting"], kept["answer"])

    def merge(self, key, answer):
        """An answer, or the part of one that was asked for, put with what is known of its setting."""
        have = self.cache.setdefault(key, {"seconds": 0.0})
        for part in ("setting", "sets", "bench", "misspellings"):
            if part in answer:
                have[part] = answer[part]
        have["seconds"] += answer["seconds"]
        return have

    def ask(self, setting, parts=("sets", "bench")):
        """What the worker makes of a setting ({dial: value}): its sets, its bench, or both, as [parts] names. A
        search asks for the sets alone first: they are half the work, and most settings fall at them."""
        self.load()
        key = self.canon(setting)
        have = self.cache.get(key, {})
        want = [p for p in parts if p not in have and (p == "sets" or self.args.bench_words > 0)]
        if not want:
            return have
        if self.worker is None:
            t0 = time.time()
            self.worker = Worker(self.sets.path, self.args.bench_words, self.args.threads, self.env)
            note("sweep: the worker is up in %.0f s: %d threads, %s" % (time.time() - t0, self.args.threads, ", ".join(
                "%s %d words" % (k, v["words"]) for k, v in self.worker.ready["sets"].items()) + (", the bench's %d words a thumb" % self.worker.ready["bench"]["words"] if self.worker.ready["bench"] else ", no bench")))
        answer = self.worker.ask(("only=%s " % want[0] if len(want) == 1 and self.args.bench_words > 0 else "") + key)
        if "error" in answer:
            raise SystemExit("sweep: %s" % answer["error"])
        self.asked += 1
        self.seconds += answer["seconds"]
        with open(self.cache_file, "a", encoding="utf-8") as f:
            f.write(json.dumps({"setting": key, "answer": answer}) + "\n")
        return self.merge(key, answer)

    def close(self):
        if self.worker is not None:
            self.worker.close()
            self.worker = None
        if self.asked:
            note("sweep: the worker was asked %d times, %.1f s each" % (self.asked, self.seconds / self.asked))
            self.asked, self.seconds = 0, 0.0


class Objective:
    """What a search makes small: see this file's opening. [base] is the tree's own answer; [fit], where a half is
    held out, says for each set which words are fitted on."""

    def __init__(self, weights, base, fit=None):
        self.w, self.base, self.fit = weights, base, fit

    def wrong(self, answer, name, keep=True):
        bits = answer["sets"][name]["wrong"]
        if self.fit is None:
            return bits.count("1") if keep else 0
        return sum(1 for b, m in zip(bits, self.fit[name]) if b == "1" and m == keep)

    def penalty(self, answer):
        if "bench" not in answer:
            return 0.0
        w, total = self.w, 0.0
        for thumb, t in answer["bench"].items():
            was = self.base["bench"][thumb]
            total += w["spoiled"] * max(0, t["spoiled"] - was["spoiled"]) + w["unknown"] * max(0, t["oovSpoiled"] - was["oovSpoiled"]) + w["right"] * max(0, was["right"] - t["right"])
            total += w["bench"] * (t["words"] - t["right"] + 4 * t["spoiled"])
        return total + w["misspellings"] * max(0, self.base["misspellings"][0] - answer["misspellings"][0])

    def of(self, answer):
        return sum(self.w[name] * self.wrong(answer, name) for name in SETS) + self.penalty(answer)

    def said(self):
        w = self.w
        text = "words wrong on %s" % " and ".join(("%s x %s" % (fmt(w[n]), n) if w[n] != 1 else n) for n in SETS if w[n])
        if self.fit is not None:
            text += " (the fitted half of each)"
        text += "; and for each thumb of the bench %s for a right word spoiled over the tree's, %s more where no list has it, %s for a word right under the tree's; %s for a misspelling mended under the tree's" % (
            fmt(w["spoiled"]), fmt(w["unknown"]), fmt(w["right"]), fmt(w["misspellings"]))
        if w["bench"]:
            text += "; and %s times the bench's words wrong, a spoiled one four times over" % fmt(w["bench"])
        return text


def bench_text(answer):
    """The bench's three figures for its three thumbs, as a table's columns."""
    if "bench" not in answer:
        return "%-17s  %-14s  %-14s  %5s" % ("-", "-", "-", "-")
    ts = list(answer["bench"].values())
    return "%s  %s  %s  %5d" % (" ".join("%5.2f" % (100.0 * t["right"] / max(1, t["words"])) for t in ts), " ".join("%4.2f" % (1000.0 * t["spoiled"] / max(1, t["plainTypedRight"])) for t in ts),
                               " ".join("%4.1f" % (100.0 * t["oovSpoiled"] / max(1, t["oovTypedRight"])) for t in ts), answer["misspellings"][0])


BENCH_HEAD = "%-17s  %-14s  %-14s  %5s" % ("bench: right %", "spoiled /1000", "unknown sp. %", "missp")


def sets_text(answer):
    return "  ".join("%s %4d" % (" ".join("%4d" % answer["sets"][name]["thumbs"][t][1] for t in scoreboard.THUMBS), answer["sets"][name]["wrong"].count("1")) for name in SETS)


SETS_HEAD = "  ".join("%-19s" % (name + ": c    l    s  all") for name in SETS)


def outcome(answer):
    """Everything an answer says but how long it took: two settings with the same outcome read every word alike."""
    return ([answer["sets"][n]["wrong"] for n in SETS], [t["line"] for t in answer.get("bench", {}).values()], answer.get("misspellings"))


def churn(answer, base):
    """Words mended and broken against [base], over both sets."""
    mended = broken = 0
    for name in SETS:
        for a, b in zip(answer["sets"][name]["wrong"], base["sets"][name]["wrong"]):
            mended += a == "0" and b == "1"
            broken += a == "1" and b == "0"
    return mended, broken


def board_lines(sw, answer, label=""):
    """A setting as scoreboard.py --bench prints it."""
    out = [scoreboard.line("seen", sw.sets.verdicts("seen", answer["sets"]["seen"]["wrong"]), sw.sets.gboard), scoreboard.line("unseen", sw.sets.verdicts("unseen", answer["sets"]["unseen"]["wrong"]))]
    if "bench" in answer:
        out.append("bench    misspellings mended, by the taps: %d of %d" % tuple(answer["misspellings"]))
        out += ["bench    " + " ".join(t["line"].split("  [")[0].split()) for t in answer["bench"].values()]
    return [label + ln for ln in out]


def values_of(sw, spec):
    """A grid's dial and its values: "Name=1.6,2.0", "Name=1.6:3.2:0.4", or "Name" for seven about the tree's."""
    name, eq, text = spec.partition("=")
    if name not in sw.dials:
        raise SystemExit("sweep: %s: no such dial (sweep.py dials lists them)" % name)
    kind = sw.dials[name]["type"]
    if kind == "switch":
        return name, [False, True]
    if not eq:
        d = sw.defaults[name]
        step = first_step(sw, name, 0.4)
        vals = [d + k * step for k in range(-3, 4)]
        vals = [v for v in vals if v >= 0 or d < 0]
    elif ":" in text:
        f = [float(x) for x in text.split(":")]
        if len(f) != 3 or f[2] <= 0:
            raise SystemExit("sweep: %s: a range is from:to:step" % spec)
        vals, v = [], f[0]
        while v <= f[1] + 1e-9:
            vals.append(v)
            v += f[2]
    else:
        vals = [float(x) for x in text.split(",")]
    return name, [int(round(v)) if kind == "int" else round(v, 4) for v in vals]


def first_step(sw, name, scale):
    """The step a dial starts a search with: [scale] for a cost (they are in Zipf units, and the tree's run from 0
    to 5.5), a quarter of the tree's value for one far larger than that, 1 for a whole number."""
    d = abs(sw.defaults[name])
    if sw.dials[name]["type"] == "int" or name in WHOLE:
        return 1
    return scale if d <= 8 else round(d * scale / 1.6, 4)


def cmd_dials(sw, args):
    for name, d in sw.dials.items():
        taken = d["type"] == "float" and not name.startswith(NOT_SEARCHED) and name not in NOT_SEARCHED
        print("%-28s %-6s %-8s %s" % (name, d["type"], d["default"], "searched" if taken else ""))


def cmd_line(sw, args):
    base = sw.ask({})
    fit = sw.sets.halves(args.holdout, args.other_half) if args.holdout is not None else None
    obj = Objective(args.weights, base, fit)
    for text in args.settings or ["-"]:
        setting = sw.parse(text)
        answer = sw.ask(setting)
        print(sw.canon(setting) or "as the tree is")
        for ln in board_lines(sw, answer, "  "):
            print(ln)
        mended, broken = churn(answer, base)
        print("  objective %s (seen %d + unseen %d + bench %s); against the tree, over both sets: %d mended, %d broken; %.1f s" % (
            fmt(obj.of(answer)), obj.wrong(answer, "seen"), obj.wrong(answer, "unseen"), fmt(obj.penalty(answer)), mended, broken, answer["seconds"]))
        if fit is not None:
            for ln in halves_lines(sw, obj, base, answer):
                print("  " + ln)
        # What the typing itself took, as its threads clocked it: a dial that widens a search shows here.
        print("  microseconds a word: %s%s" % (", ".join("%s %.0f" % (n, answer["sets"][n]["micros"]) for n in SETS),
                                               "; the bench's thumbs %s" % ", ".join("%.0f" % t["micros"] for t in answer["bench"].values()) if "bench" in answer else ""))


def table_head(first, width, obj):
    return "%-*s  %s  %s  %s%s" % (width, first, SETS_HEAD, BENCH_HEAD, "mended broken  objective", "   fitted  held out" if obj.fit else "")


def table_row(label, width, a, base, obj):
    mended, broken = churn(a, base)
    held = "   %6d  %8d" % (sum(obj.wrong(a, n) for n in SETS), sum(obj.wrong(a, n, False) for n in SETS)) if obj.fit else ""
    return "%-*s  %s  %s  %6d %6d  %9s%s" % (width, label, sets_text(a), bench_text(a), mended, broken, fmt(obj.of(a)), held)


def table_foot(sw):
    return "Sets: words wrong for the careful, leaning and sloppy thumbs and in all (of %d and %d). Bench: its careful, normal and sloppy thumbs. Mended and broken: words of the two sets, against the tree." % (
        sw.sets.words["seen"], sw.sets.words["unseen"])


def cmd_grid(sw, args):
    if not 1 <= len(args.dials) <= 2:
        raise SystemExit("sweep: a grid is over one dial or two")
    fixed = sw.parse(args.with_setting or "-")
    base = sw.ask({})
    fit = sw.sets.halves(args.holdout, args.other_half) if args.holdout is not None else None
    obj = Objective(args.weights, base, fit)
    axes = [values_of(sw, spec) for spec in args.dials]
    print("objective: " + obj.said())
    if fixed:
        print("with " + sw.canon(fixed))
    if len(axes) == 1:
        name, vals = axes[0]
        print(table_head(name.split(".")[1], 14, obj))
        for v in vals:
            setting = dict(fixed)
            setting[name] = v
            print(table_row(fmt(v) + (" *" if fmt(v) == fmt(sw.defaults[name]) else ""), 14, sw.ask(setting), base, obj))
            sys.stdout.flush()
        print("* the tree's value. " + table_foot(sw))
        return
    (a_name, a_vals), (b_name, b_vals) = axes
    cells = {}
    for av in a_vals:
        for bv in b_vals:
            setting = dict(fixed)
            setting[a_name] = av
            setting[b_name] = bv
            cells[(av, bv)] = sw.ask(setting)
    tables = [("words wrong, seen and unseen%s" % (" (the fitted half)" if fit else ""), lambda a: "%d" % sum(obj.wrong(a, n) for n in SETS)),
              ("the bench's penalty", lambda a: fmt(obj.penalty(a))), ("objective", lambda a: fmt(obj.of(a)))]
    if fit:
        tables.insert(1, ("words wrong, seen and unseen (the half held out)", lambda a: "%d" % sum(obj.wrong(a, n, False) for n in SETS)))
    for title, cell in tables:
        print("%s: %s down, %s across" % (title, a_name, b_name))
        print("%10s  %s" % ("", " ".join("%7s" % (fmt(bv) + ("*" if fmt(bv) == fmt(sw.defaults[b_name]) else "")) for bv in b_vals)))
        for av in a_vals:
            print("%10s  %s" % (fmt(av) + ("*" if fmt(av) == fmt(sw.defaults[a_name]) else ""), " ".join("%7s" % cell(cells[(av, bv)]) for bv in b_vals)))
    print("* the tree's value.")


def cmd_scan(sw, args):
    """Each dial alone, a step down and a step up: which of them these tests can feel, and which way."""
    dials = search_dials(sw, args.dials, args.step)
    fixed = sw.parse(args.with_setting or "-")
    base = sw.ask({})
    fit = sw.sets.halves(args.holdout, args.other_half) if args.holdout is not None else None
    obj = Objective(args.weights, base, fit)
    start = sw.ask(fixed)
    print("objective: " + obj.said())
    print(table_head("a step of %s either way" % fmt(args.step), 34, obj))
    print(table_row(sw.canon(fixed)[:34] or "as the tree is", 34, start, base, obj))
    rows, still = [], []
    for name, lo, hi, step in dials:
        now = fixed.get(name, sw.defaults[name])
        felt = False
        for way in (-1, 1):
            v = round(min(hi, max(lo, now + way * step)), 4)
            if fmt(v) == fmt(now):
                continue
            setting = dict(fixed)
            setting[name] = v
            a = sw.ask(setting)
            if outcome(a) == outcome(start):
                continue
            felt = True
            print(table_row("%s=%s" % (name, fmt(v)), 34, a, base, obj))
            sys.stdout.flush()
            rows.append((obj.of(a), "%s=%s" % (name, fmt(v))))
        if not felt:
            still.append(name)
    print(table_foot(sw))
    if still:
        print("a step either way changes no word and no figure: " + " ".join(still))
    print("the lowest objectives a step away: " + ", ".join("%s %s" % (label, fmt(j)) for j, label in sorted(rows)[:8]))


def search_dials(sw, specs, scale):
    """[(dial, least, most, first step)] for a search: those asked for, or every number of the decoder's that is not
    in NOT_SEARCHED."""
    out = []
    for spec in specs or [n for n, d in sw.dials.items() if d["type"] == "float" and not n.startswith(NOT_SEARCHED) and n not in NOT_SEARCHED]:
        name, eq, text = spec.partition("=")
        if name not in sw.dials:
            raise SystemExit("sweep: %s: no such dial (sweep.py dials lists them)" % name)
        lo, hi, step = (0.0 if sw.defaults[name] >= 0 else float("-inf")), float("inf"), first_step(sw, name, scale)
        if eq:
            f = [float(x) for x in text.split(":")]
            if len(f) not in (2, 3):
                raise SystemExit("sweep: %s: a search's dial is Name or Name=from:to or Name=from:to:step" % spec)
            lo, hi = f[0], f[1]
            step = f[2] if len(f) == 3 else step
        out.append((name, lo, hi, step))
    return out


def held_out(sw, obj, base, found, resamples=2000):
    """What the half that was not fitted on says of [found] against the tree, with the middle 95% of the difference
    over [resamples] resamplings of that half's sentences (drawn whole, as score.py resamples)."""
    rows = {}
    for name in SETS:
        was = sw.sets.by_sentence(name, base["sets"][name]["wrong"], obj.fit[name], False)
        now = sw.sets.by_sentence(name, found["sets"][name]["wrong"], obj.fit[name], False)
        for line in was:
            rows[(name, line)] = (was[line], now[line])
    pairs = [rows[k] for k in sorted(rows)]
    rnd = random.Random(20261112)
    diffs = sorted(sum(b - a for a, b in (pairs[rnd.randrange(len(pairs))] for _ in pairs)) for _ in range(resamples))
    return sum(a for a, _ in pairs), sum(b for _, b in pairs), diffs[int(0.025 * (len(diffs) - 1))], diffs[int(0.975 * (len(diffs) - 1))]


def halves_lines(sw, obj, base, answer):
    """The tree against [answer] on the half of the sentences that is fitted on and on the half held out."""
    fitted = [sum(obj.wrong(a, n) for n in SETS) for a in (base, answer)]
    was, now, low, high = held_out(sw, obj, base, answer)
    n_fit = sum(sum(obj.fit[n]) for n in SETS)
    n_held = sum(len(obj.fit[n]) - sum(obj.fit[n]) for n in SETS)
    return ["the half fitted on (%d words):  %d wrong to %d, %+d (%.2f%% to %.2f%%)" % (n_fit, fitted[0], fitted[1], fitted[1] - fitted[0], 100.0 * fitted[0] / n_fit, 100.0 * fitted[1] / n_fit),
            "the half held out (%d words):   %d wrong to %d, %+d (%.2f%% to %.2f%%); over 2,000 resamplings of its sentences the difference is %+d to %+d" % (
                n_held, was, now, now - was, 100.0 * was / n_held, 100.0 * now / n_held, low, high)]


def cmd_search(sw, args):
    dials = search_dials(sw, args.dials, args.step)
    base = sw.ask({})
    fit = sw.sets.halves(args.holdout, args.other_half) if args.holdout is not None else None
    obj = Objective(args.weights, base, fit)
    x = sw.parse(args.from_setting or "-")
    here = sw.ask(x)
    best = obj.of(here)
    print("search over %d dials: %s" % (len(dials), " ".join(d[0] for d in dials)))
    print("objective: " + obj.said())
    print("the tree: %s (seen %d + unseen %d)%s" % (fmt(obj.of(base)), obj.wrong(base, "seen"), obj.wrong(base, "unseen"), "; starts from %s at %s" % (sw.canon(x), fmt(best)) if x else ""))
    sys.stdout.flush()
    tried = [1]
    began = time.time()

    def judge(name, v):
        """The objective with [name] at [v] and the rest as they stand, the answer, and whether that is the whole
        objective. The sets are typed first: no penalty is less than nothing, so where they alone leave the
        objective too high for a move the bench is not typed, and what comes back is only a floor."""
        setting = dict(x)
        setting[name] = v
        tried[0] += 1
        a = sw.ask(setting, ("sets",))
        if "bench" not in a and args.bench_words > 0 and obj.of(a) <= best - args.least:
            a = sw.ask(setting)
        return obj.of(a), a, "bench" in a or args.bench_words <= 0

    for level in range(args.halvings + 1):
        shrink = 2 ** level
        print("steps %s" % ("of 1/%d the first size" % shrink if level else "of the first size (%s for a cost)" % fmt(args.step)))
        sys.stdout.flush()
        # A dial that changes no word of either set a step either way is passed over for the rest of this size of step.
        inert = set()
        for rnd in range(args.rounds):
            moved = 0
            for name, lo, hi, step0 in dials:
                if name in inert or tried[0] >= args.budget:
                    continue
                whole = sw.dials[name]["type"] == "int" or name in WHOLE
                step = max(1, int(round(step0 / shrink))) if whole else step0 / shrink
                if whole and level and step0 / shrink < 1:
                    continue  # a whole number has no smaller step than the one already tried
                now = x.get(name, sw.defaults[name])
                tries = []
                for way in (-1, 1):
                    v = round(min(hi, max(lo, now + way * step)), 4)
                    if fmt(v) != fmt(now):
                        tries.append(judge(name, v) + (way, v))
                if not tries:
                    continue
                if all(t[1]["sets"] and all(t[1]["sets"][n]["wrong"] == here["sets"][n]["wrong"] for n in SETS) and ("bench" not in t[1] or outcome(t[1]) == outcome(here)) for t in tries):
                    inert.add(name)
                    continue
                j, a, whole_objective, way, v = min(tries, key=lambda t: (t[0], abs(t[4] - sw.defaults[name])))
                if j > best - args.least or not whole_objective:
                    continue
                was_j, was_v = best, now
                x[name], here, best = v, a, j
                moved += 1
                # On the same way while it still pays.
                for _ in range(args.run):
                    v2 = round(min(hi, max(lo, x[name] + way * step)), 4)
                    if fmt(v2) == fmt(x[name]) or tried[0] >= args.budget:
                        break
                    j2, a2, whole_objective = judge(name, v2)
                    if j2 > best - args.least or not whole_objective:
                        break
                    x[name], here, best = v2, a2, j2
                print("  %-26s %5s to %-5s  objective %s to %s  (seen %d, unseen %d, bench %s)" % (
                    name, fmt(was_v), fmt(x[name]), fmt(was_j), fmt(best), obj.wrong(here, "seen"), obj.wrong(here, "unseen"), fmt(obj.penalty(here))))
                sys.stdout.flush()
            print("    round %d: %d dials moved, %d passed over as changing nothing; objective %s; %d settings tried so far, %.0f minutes" % (
                rnd + 1, moved, len(inert), fmt(best), tried[0], (time.time() - began) / 60.0))
            sys.stdout.flush()
            if not moved:
                break
        if tried[0] >= args.budget:
            print("stopped at %d settings tried (--budget)" % tried[0])
            break
    found = sw.canon(x)
    print("found, with %d settings tried: %s" % (tried[0], found or "nothing better than the tree"))
    if found:
        print("    as scoreboard.py takes it: " + " ".join("--set " + p for p in found.split()))
        for part in found.split():
            name = part.split("=")[0]
            print("    %-26s %5s, the tree's %s" % (name, fmt(x[name]), fmt(sw.defaults[name])))
    for label, a in (("tree   ", base), ("found  ", here)):
        for ln in board_lines(sw, a, label):
            print(ln)
    mended, broken = churn(here, base)
    print("objective %s to %s; against the tree, over both sets: %d mended, %d broken" % (fmt(obj.of(base)), fmt(best), mended, broken))
    if fit is not None:
        for ln in halves_lines(sw, obj, base, here):
            print(ln)


def cmd_check(sw, args):
    """This tool against scoreboard.py --bench, a setting at a time: the lines it prints, and every word's verdict."""
    bad = 0
    settings = [sw.parse(text) for text in args.settings or CHECKS]
    answers = [sw.ask(setting) for setting in settings]
    # The worker is let go before scoreboard.py starts JVMs of its own: one at a time on a busy Mac.
    sw.close()
    for k, (setting, answer) in enumerate(zip(settings, answers)):
        # scoreboard.py's bench is 12,000 words: with another count here only the sets are held to it.
        whole = args.bench_words == 12000
        mine = board_lines(sw, answer)[:None if whole else 2]
        name = "sweep-check-%d" % k
        cmd = [sys.executable, os.path.join(HERE, "scoreboard.py"), "--save", name] + (["--bench"] if whole else []) + [x for part in sw.canon(setting).split() for x in ("--set", part)]
        if args.overlay:
            cmd += ["--overlay", args.overlay]
        if args.java:
            cmd += ["--java", args.java]
        t0 = time.time()
        theirs = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True).stdout.splitlines()
        took = time.time() - t0
        said = ""
        if whole and len(theirs) == 2:
            # Its bench takes numbers of Autocorrect and TapDecoder only (Bench.kt's -Dset): for a switch, or a dial
            # of another class, it prints nothing, and only the sets can be held to it.
            mine, said = mine[:2], "; scoreboard.py's bench has no answer for this setting, so only the sets were held to it"
        kept = json.load(open(os.path.join(ROOT, "build", "versus", "scoreboard", name + ".json")))
        words = sum(1 for n in SETS for key, v in kept[n].items() for _ in v)
        differ = sum(1 for n in SETS for key, v in sw.sets.verdicts(n, answer["sets"][n]["wrong"]).items() for a, b in zip(v, kept[n][key]) if a[0] != b[0])
        same = sum(1 for ln in mine if ln in theirs)
        ok = same == len(mine) and len(theirs) == len(mine) and differ == 0 and words == sum(sw.sets.words.values())
        bad += 0 if ok else 1
        print("%s: %s" % (sw.canon(setting) or "as the tree is", "the same" if ok else "NOT THE SAME"))
        print("    %d of scoreboard.py's %d lines are this tool's %d; %d of %d words have another verdict; scoreboard.py took %.0f s, the sweep %.1f s%s" % (
            same, len(theirs), len(mine), differ, words, took, answer["seconds"], said))
        for ln in mine:
            print("    %s %s" % ("=" if ln in theirs else "sweep     ", ln))
        for ln in theirs:
            if ln not in mine:
                print("    scoreboard %s" % ln)
    if bad:
        raise SystemExit("sweep: %d settings do not come out as scoreboard.py has them" % bad)


def weights(text):
    out = dict(WEIGHTS)
    for part in text.split(","):
        name, eq, v = part.partition("=")
        if name not in out or not eq:
            raise argparse.ArgumentTypeError("weights are %s" % ",".join("%s=%s" % (k, fmt(v)) for k, v in WEIGHTS.items()))
        out[name] = float(v)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["dials", "line", "grid", "scan", "search", "check"])
    ap.add_argument("items", nargs="*", help="settings (line, check) or dials (grid, search)")
    ap.add_argument("--with", dest="with_setting", metavar="SETTING", help="grid, scan: a setting every other is tried with")
    ap.add_argument("--from", dest="from_setting", metavar="SETTING", help="search: where it starts")
    ap.add_argument("--step", type=float, default=0.4, help="search, scan: the first step of a cost")
    ap.add_argument("--halvings", type=int, default=2, help="search: how many times the steps are halved")
    ap.add_argument("--least", type=float, default=1.0, help="search: the least a move must lower the objective by")
    ap.add_argument("--rounds", type=int, default=6, help="search: the most rounds over the dials at one size of step")
    ap.add_argument("--run", type=int, default=3, help="search: how many more steps the same way a dial that moved is tried")
    ap.add_argument("--budget", type=int, default=100000, help="search: the most settings to try")
    ap.add_argument("--holdout", nargs="?", const="1", default=None, metavar="SEED")
    ap.add_argument("--other-half", action="store_true")
    ap.add_argument("--weights", type=weights, default=dict(WEIGHTS))
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--bench-words", type=int, default=12000)
    ap.add_argument("--overlay")
    ap.add_argument("--java")
    ap.add_argument("--no-cache", action="store_true", help="ask the worker again for what was answered before")
    args = ap.parse_args()
    args.settings = args.dials = args.items
    sw = Sweep(args)
    try:
        {"dials": cmd_dials, "line": cmd_line, "grid": cmd_grid, "scan": cmd_scan, "search": cmd_search, "check": cmd_check}[args.command](sw, args)
    finally:
        sw.close()


if __name__ == "__main__":
    main()
