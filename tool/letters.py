#!/usr/bin/env python3
"""Ghost Keyboard's letter table for English: android/app/src/main/assets/dict/en_letters.tsv, how likely a string of
letters is as a word no list has. Autocorrect reads it to say what typed letters that are no listed word are worth
against a reading of their taps (Autocorrect.unlisted): "prrsrnyable" next to nothing, "berber" a good deal more.

    python3 tool/letters.py                  # counts, writes the table, says what it holds
    python3 tool/letters.py --check          # counts again and fails if the bundled file would change
    python3 tool/letters.py --export FILE    # the Tatoeba export, where it is not in build/ngram-src/
    python3 tool/letters.py --list FILE      # one more word list to take as listed (a word a line, or a word, a tab
                                             # and anything); any number of times
    python3 tool/letters.py --say berber prrsrnyable                           # what the table makes of these

    For measuring another table against the bundled one (see "How it was set"):
    python3 tool/letters.py --compare                  # what each way of counting costs on held-out words; writes nothing
    python3 tool/letters.py --order 4 --out build/letters/en_letters-4.tsv     # longer or shorter runs, for the keyboard to try
    python3 tool/letters.py --running --out ...        # each word counted as often as it stood, not once
    python3 tool/letters.py --with-listed 1 --out ...  # the listed words' own spelling counted in, each this many times

Standard library only, and it fetches nothing. Run it again whenever English's word lists change: a word that
joins a list is no longer one of the words no list has, so both the share of such words and what they are spelled
like move with the lists. A list put beside en.tsv as assets/dict/en_<name>.tsv (a word, a tab, its frequency) is
read as listed without being named.

What is counted
---------------
The same export tool/next_words.py counts, build/ngram-src/eng_sentences_detailed.tsv.bz2 (Tatoeba's English
sentences, CC BY 2.0 FR; that tool fetches it, this one only reads it and stops where it is missing), split into
words by the same rule (next_words.walk). Every 20th sentence is held out, by the same count as next_words.py and
tool/typing_bench.sh keep: the bench, tool/versus and tool/next_word_eval.sh type those, and nothing here ever
counts one. A sentence whose writer is not named in assets/ngram/CREDITS.txt is passed over too (4 of 1.9 million),
so that file names everyone whose sentences this table is derived from.

A word is one that no list has when the keyboard, with English alone loaded, would hold it to Autocorrect.unlisted:
it is not in assets/dict/en.tsv nor in any other English list beside it (an en_*.tsv of words and frequencies), is
not typed for a contraction (en_contractions.tsv), is not slang (en_slang.txt) nor one of Autocorrect's DEV_WORDS.
Words in capitals throughout are left out (an acronym is typed in capitals, and autocorrect leaves those alone),
and so is anything code-shaped or longer than a word may be (next_words.learnable). Accents are folded as a hurried
thumb types them (Script.plain: é is e), and a word with any character left that is not a to z or an apostrophe is
left out.

What is written
---------------
Runs of up to ORDER letters with a start mark and an end mark (ORDER 3: "^^b", "^be", "ber", "erb", "rbe", "ber",
"er$" for berber), each word that no list has counted once however often it stood: Tatoeba's sentences are full of
Ziri, Yanni and Skura, and counted by how often they stood a third of the table would be those five names.
Witten-Bell smoothing, each length of run interpolated with the one shorter, down to all 28 marks alike. The file
holds every run seen with what its last letter costs after the ones before (minus 100 times the log to base ten
of its chance), and for every run that others continue, what is added before going by one letter less where the
longer run is not in the file. That is the interpolated model exactly, to the hundredth written.

The first line that is no comment holds the facts the keyboard reads: the order, and the share of running words
that no list has (unlisted words over all words, in the counted sentences). What a string is worth is then
9 + log10(share) + log10(its chance by the table), in the Zipf units every listed word's frequency is in:
the chance that the next word typed is in no list, and is spelled so. Autocorrect.unlisted has two dials on that
(LETTERS_SHIFT, LETTERS_WEIGHT) and never lets it raise a bar; docs/versus.md says how they were set.

A comment line beginning "# check:" holds a few strings with what each costs by the file, in hundredths:
LetterModelTest reads the file as the keyboard does and must find the same, so the two readers cannot part.

How it was set
--------------
By how well a table foretells the words no list has in the held-out lines below 90,000 that the counted sentences
never had (871 kinds on 2026-11-12), as what a letter of them costs (minus log10 of its chance, the end mark counted
as a letter: `--compare` prints these), and by what the keyboard then does with it (docs/versus.md):
- runs of 2, 3, 4 and 5 marks cost 1.137, 1.051, 0.997 and 0.974 a letter, in files of 755, 9,666, 57,777 and
  167,805 rows. Runs of 3 it is: docs/versus.md has what the keyboard does with 2 and with 4;
- each kind of word once costs 1.051 a letter, each as often as it stood 1.147 (--running): Tatoeba's sentences
  are full of Ziri, Yanni and Skura, and a table counted by how often they stood is a table of those names;
- the listed words' own spelling costs 1.092 alone and 1.056 counted in beside the unlisted words (--with-listed 1):
  the words no list has are spelled a little otherwise than the ones the lists have, and say so best themselves;
- Kneser-Ney's counts in place of Witten-Bell's weights cost the same to the third decimal at 2 and 3 marks (tried
  in a scratch script, not kept: there are 28 marks, and nearly every run of two has been seen).
"""
import bz2
import collections
import hashlib
import math
import os
import re
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import next_words as nw  # noqa: E402   (its tokenizer and its hold-out count; nothing of it that fetches is called)

ROOT = os.path.join(HERE, "..")
DICT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "dict")
OUT = os.path.join(DICT, "en_letters.tsv")
EXPORT = os.path.join(ROOT, "build", "ngram-src", "eng_sentences_detailed.tsv.bz2")
CREDITS = os.path.join(ROOT, "android", "app", "src", "main", "assets", "ngram", "CREDITS.txt")
SOURCES = os.path.join(HERE, "next_words_sources.tsv")
AUTOCORRECT = os.path.join(ROOT, "android", "app", "src", "main", "kotlin", "com", "infamousvague", "devkeyboard", "Autocorrect.kt")
HELD_OUT = os.path.join(ROOT, "build", "typing-bench", "en-heldout.txt")

#: The letters of the table (Autocorrect.LetterModel's): a to z and the apostrophe. "^" starts a word and "$" ends it.
LETTERS = "abcdefghijklmnopqrstuvwxyz'"
START, END = "^", "$"
OUTCOMES = LETTERS + END
#: Runs of this many marks. Set by measurement (docs/versus.md, "What letters no list has are worth").
ORDER = 3
#: The held-out lines the bench types from the top of its file and that tool/versus never reads: the only ones
#: this tool measures itself on (--measure), and it counts none of them.
MEASURE_LINES = 90_000
WORD = re.compile("^[a-z']+$")


def plain(word):
    """Script.plain for the Latin script: accents and marks gone, ß as ss, and the four letters Unicode keeps whole."""
    if all(ord(c) < 0x80 for c in word):
        return word
    nfd = unicodedata.normalize("NFD", word.replace("ß", "ss").translate(str.maketrans("łøđı", "lodi")))
    return "".join(c for c in nfd if unicodedata.category(c) != "Mn")


def listed_words(extra):
    """Every word the keyboard knows with English alone loaded, and what each file gave: ({word}, [(name, count)])."""
    words, said = set(), []

    def take(name, found):
        said.append((name, len(found)))
        words.update(found)

    for name in sorted(os.listdir(DICT)):
        path = os.path.join(DICT, name)
        if name == "en_contractions.tsv":
            # "dont<TAB>don't": the first is what is typed, and it is never held as an unlisted word.
            take(name, {line.split("\t")[0] for line in open(path, encoding="utf-8") if "\t" in line})
        elif name == "en_slang.txt":
            take(name, {line.strip().lower() for line in open(path, encoding="utf-8") if line.strip() and not line.startswith("#")})
        elif (name == "en.tsv" or name.startswith("en_")) and name.endswith(".tsv") and name != os.path.basename(OUT):
            found = set()
            for line in open(path, encoding="utf-8"):
                word, tab, rest = line.rstrip("\n").partition("\t")
                try:
                    float(rest.split("\t")[0])
                except ValueError:
                    continue
                if word and not word.startswith("#"):
                    found.add(word)
            if found:
                take(name, found)
    source = open(AUTOCORRECT, encoding="utf-8").read()
    take("Autocorrect.DEV_WORDS", set(source.split('private val DEV_WORDS = """')[1].split('"""')[0].split()))
    for path in extra:
        take(path, {line.split("\t")[0].strip().lower() for line in open(path, encoding="utf-8") if line.strip() and not line.startswith("#")})
    return words, said


def typed_words(text, classes):
    """The words of a sentence as autocorrect would be handed them, lower case: none in capitals throughout, none
    that next-word prediction would not learn (code-shaped, too long, a capital inside)."""
    words, _ = nw.walk(nw.normalise(text, "en"), classes, "en")
    for _, _, raw, _, _, ok in words:
        if not ok:
            continue
        letters = [c for c in raw if unicodedata.category(c)[0] == "L"]
        if len(letters) >= 2 and all(c.isupper() for c in letters):
            continue
        yield nw.lower(raw, "en")


def count(export, listed):
    """The counted sentences' words that no list has, each with how often it stood; and what was read."""
    classes = nw.Classes(nw.joiners_of("en"), nw.ENDS.get("en", ""))
    credited = set(n.strip() for n in open(CREDITS, encoding="utf-8").read().split("\n\n", 1)[1].replace("\n", " ").split(", "))
    unlisted = collections.Counter()
    facts = collections.Counter()
    n = 0
    with bz2.open(export, "rt", encoding="utf-8", errors="replace", newline="\n") as f:
        for line in f:
            cols = line.rstrip("\n").split("\t")
            if len(cols) < 4:
                continue
            n += 1
            # next_words.count's own test, and tool/typing_bench.sh's awk: these sentences are never counted.
            if n % nw.HOLD_OUT == 0:
                facts["held out"] += 1
                continue
            if cols[3] not in credited:
                facts["writer not credited"] += 1
                continue
            facts["counted"] += 1
            for word in typed_words(cols[2], classes):
                facts["words"] += 1
                if word in listed:
                    continue
                word = plain(word)
                if word in listed:
                    continue
                if not WORD.match(word):
                    facts["other letters"] += 1
                    continue
                unlisted[word] += 1
    facts["sentences"] = n
    return unlisted, facts


class Table:
    """Letter runs of [order] marks over [words] ({word: weight}), Witten-Bell smoothed and interpolated."""

    def __init__(self, order, words):
        self.order = order
        # counts[k][run of k letters before][the letter after]
        self.counts = [collections.defaultdict(lambda: collections.defaultdict(float)) for _ in range(order)]
        for word in sorted(words):
            weight = words[word]
            marks = START * (order - 1) + word + END
            for i in range(order - 1, len(marks)):
                for k in range(order):
                    self.counts[k][marks[i - k:i]][marks[i]] += weight
        self.totals = [{run: sum(row.values()) for run, row in level.items()} for level in self.counts]
        self.memo = {}

    def chance(self, k, before, letter):
        """The chance of [letter] after the [k] letters [before], interpolated with every shorter run."""
        key = (before, letter)
        if key in self.memo:
            return self.memo[key]
        p = 1.0 / len(OUTCOMES)
        for j in range(k + 1):
            run = before[len(before) - j:] if j else ""
            row = self.counts[j].get(run)
            if row:
                kinds = len(row)
                p = (row.get(letter, 0.0) + kinds * p) / (self.totals[j][run] + kinds)
        self.memo[key] = p
        return p

    def log10(self, word):
        marks = START * (self.order - 1) + word + END
        return sum(math.log10(self.chance(self.order - 1, marks[i - self.order + 1:i], marks[i])) for i in range(self.order - 1, len(marks)))

    def rows(self):
        """{run: [cost of its last letter in hundredths or None, what is added before going one letter shorter or None]}."""
        rows = {}
        for k in range(self.order):
            for before, row in self.counts[k].items():
                if before:
                    kinds = len(row)
                    rows.setdefault(before, [None, None])[1] = round(-100 * math.log10(kinds / (self.totals[k][before] + kinds)))
                for letter in (OUTCOMES if k == 0 else sorted(row)):
                    rows.setdefault(before + letter, [None, None])[0] = round(-100 * math.log10(self.chance(k, before, letter)))
        return rows


class Reader:
    """The table as Autocorrect.LetterModel reads it back from the file: for holding this tool to the keyboard."""

    def __init__(self, text):
        self.rows = {}
        self.order, self.share = 0, 0.0
        for line in text.split("\n"):
            if not line or line.startswith("#"):
                continue
            f = line.split("\t")
            if f[0] == "=":
                facts = dict(zip(f[1::2], f[2::2]))
                self.order, self.share = int(facts["order"]), float(facts["share"])
                continue
            self.rows[f[0]] = (int(f[1]) if len(f) > 1 and f[1] else None, int(f[2]) if len(f) > 2 and f[2] else 0)

    def cost(self, word):
        """Minus log10 of the word's chance, in hundredths: the sum Autocorrect.LetterModel.cost makes."""
        marks = START * (self.order - 1) + word + END
        total = 0
        for i in range(self.order - 1, len(marks)):
            k = self.order - 1
            while True:
                before = marks[i - k:i]
                row = self.rows.get(before + marks[i])
                if row is not None and row[0] is not None:
                    total += row[0]
                    break
                if k == 0:
                    raise ValueError("no row for " + marks[i])
                back = self.rows.get(before)
                if back is not None:
                    total += back[1]
                k -= 1
        return total

    def worth(self, word):
        return 9 + math.log10(self.share) - self.cost(word) / 100.0


def export_facts(export):
    """The export's date and SHA-256 as tool/next_words_sources.tsv keeps them, where it is the file recorded there."""
    digest = hashlib.sha256()
    with open(export, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    for line in open(SOURCES, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if f[0] == "eng" and f[-1] == digest.hexdigest():
            return f[2], digest.hexdigest()
    return None, digest.hexdigest()


#: Strings whose cost by the file is written into it ("# check:"), for LetterModelTest to hold the keyboard's reader to.
PROBES = ("berber", "kira", "yurt", "nguyen", "don't", "whiby", "dlam", "hkpelwss", "prrsrnyable", "zxqj", "a")


def encode(order, share, rows, said):
    lines = [
        "# Ghost Keyboard: what letters no English word list has are worth (Autocorrect.unlisted). Made by tool/letters.py: do not edit.",
        "# " + said,
        "# A line: a run of letters (^ a word's start, $ its end); what its last letter costs after the ones before it, as minus",
        "# 100 times the log to base ten of its chance; and what is added before going by one letter less where a run is not here.",
        "# Derived from Tatoeba's sentences (CC BY 2.0 FR) and the wordfreq lists (CC BY-SA 4.0): see NOTICE.txt.",
        "=\torder\t%d\tshare\t%.5f" % (order, share),
    ]
    for run in sorted(rows):
        cost, back = rows[run]
        lines.append("%s\t%s\t%s" % (run, "" if cost is None else cost, "" if back is None else back))
    # What the file itself makes of a few strings, read back as the keyboard reads it.
    reader = Reader("\n".join(lines))
    lines.insert(5, "# check: " + ", ".join("%s %d" % (w, reader.cost(w)) for w in PROBES))
    return ("\n".join(lines) + "\n").encode("utf-8")


def held_out(counted, listed):
    """The words no list has in the held-out lines the bench types (never those tool/versus types), none of which
    is ever counted: (how often each kind stood, the kinds the counted sentences never had, all the words read).
    None where the bench's file is not there."""
    if not os.path.exists(HELD_OUT):
        return None
    classes = nw.Classes(nw.joiners_of("en"), nw.ENDS.get("en", ""))
    held = collections.Counter()
    words = 0
    with open(HELD_OUT, encoding="utf-8", errors="replace", newline="\n") as f:
        for i, line in enumerate(f):
            if i >= MEASURE_LINES:
                break
            for word in typed_words(line.rstrip("\n"), classes):
                words += 1
                if word in listed or plain(word) in listed:
                    continue
                if WORD.match(plain(word)):
                    held[plain(word)] += 1
    return held, sorted(w for w in held if w not in counted), words


def a_letter(table, words):
    """What a letter of [words] costs by [table] (minus log10 of its chance), a word's end counted as a letter."""
    return -sum(table.log10(w) for w in words) / max(1, sum(len(w) + 1 for w in words))


def measure(table, held):
    """How well the table foretells the held-out lines' words that no list has: a line of text."""
    if held is None:
        return "no %s to measure on (tool/typing_bench.sh cuts it)" % os.path.relpath(HELD_OUT, ROOT)
    stood, new, words = held
    return "held-out lines below %s (never counted): %.2f%% of %s words are in no list; the %s of their %s kinds that the counted sentences never had cost %.3f a letter (log10)" % (
        format(MEASURE_LINES, ","), 100.0 * sum(stood.values()) / max(1, words), format(words, ","), format(len(new), ","), format(len(stood), ","), a_letter(table, new))


def compare(unlisted, listed, held):
    """What other ways of counting would cost on the held-out words: the lines "How it was set" quotes."""
    if held is None:
        sys.exit("letters: no %s to compare on (tool/typing_bench.sh cuts it)" % os.path.relpath(HELD_OUT, ROOT))
    new = held[1]
    once = {w: 1.0 for w in unlisted}
    spelled = {w: 1.0 for w in listed if WORD.match(w)}
    both = dict(once)
    for w in spelled:
        both[w] = both.get(w, 0.0) + 1.0
    print("what a letter costs (minus log10 of its chance, the end mark counted) over the %s kinds of unlisted word in the held-out lines below %s that the counted sentences never had:" % (format(len(new), ","), format(MEASURE_LINES, ",")))
    for order in (2, 3, 4, 5):
        table = Table(order, once)
        print("  runs of %d, each kind once                              %.3f   (%s rows)" % (order, a_letter(table, new), format(len(table.rows()), ",")))
    print("  runs of %d, each word as often as it stood (--running)  %.3f" % (ORDER, a_letter(Table(ORDER, {w: float(c) for w, c in unlisted.items()}), new)))
    print("  runs of %d, the listed words alone                      %.3f" % (ORDER, a_letter(Table(ORDER, spelled), new)))
    print("  runs of %d, the listed words counted in (--with-listed 1) %.3f" % (ORDER, a_letter(Table(ORDER, both), new)))


def main(argv):
    args = {"--export": EXPORT, "--out": OUT, "--order": str(ORDER), "--with-listed": "0"}
    extra, say = [], []
    check = running = comparing = False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--check":
            check = True
        elif a == "--running":
            running = True
        elif a == "--compare":
            comparing = True
        elif a == "--list":
            extra.append(argv[i + 1])
            i += 1
        elif a == "--say":
            say = argv[i + 1:]
            break
        elif a in args:
            args[a] = argv[i + 1]
            i += 1
        else:
            sys.exit("letters: no such option %s (see the top of tool/letters.py)" % a)
        i += 1
    if say:
        reader = Reader(open(args["--out"], encoding="utf-8").read())
        for word in say:
            print("%-20s %6.2f" % (word, reader.worth(plain(word.lower()))))
        return
    export, order = args["--export"], int(args["--order"])
    if not 1 <= order <= 6:
        sys.exit("letters: an order of 1 to 6 (Autocorrect.LetterModel packs a run into 30 bits)")
    if not os.path.exists(export):
        sys.exit("letters: no %s. This tool fetches nothing: python3 tool/next_words.py en fetches the export there, or name a copy with --export" % export)
    listed, said = listed_words(extra)
    print("listed: " + ", ".join("%s %s" % (name, format(n, ",")) for name, n in said) + "; %s words in all" % format(len(listed), ","))
    unlisted, facts = count(export, listed)
    date, digest = export_facts(export)
    tokens = sum(unlisted.values())
    share = tokens / facts["words"]
    print("%s sentences: %s held out (every %dth), %s by a writer assets/ngram/CREDITS.txt does not name, %s counted" % (
        format(facts["sentences"], ","), format(facts["held out"], ","), nw.HOLD_OUT, format(facts["writer not credited"], ","), format(facts["counted"], ",")))
    print("%s words, %s of them in no list (%.2f%%; %s more in other letters, left out): %s kinds" % (
        format(facts["words"], ","), format(tokens, ","), 100.0 * share, format(facts["other letters"], ","), format(len(unlisted), ",")))
    print("stood most often: " + " ".join("%s %s" % (w, format(c, ",")) for w, c in unlisted.most_common(12)))
    held = held_out(unlisted, listed)
    if comparing:
        compare(unlisted, listed, held)
        return
    weights = {w: (float(c) if running else 1.0) for w, c in unlisted.items()}
    with_listed = float(args["--with-listed"])
    if with_listed > 0:
        for w in listed:
            if WORD.match(w):
                weights[w] = weights.get(w, 0.0) + with_listed
    table = Table(order, weights)
    rows = table.rows()
    described = "Runs of up to %d letters over the %s kinds of word in no list (%s of %s running words, %.2f%%) in the counted sentences of Tatoeba's English export of %s, every %dth held out." % (
        order, format(len(unlisted), ","), format(tokens, ","), format(facts["words"], ","), 100.0 * share, date or "an unrecorded date (sha256 %s)" % digest[:16], nw.HOLD_OUT)
    if running or with_listed > 0:
        described += " NOT the bundled table's counting:%s%s." % (" each word as often as it stood" if running else "", " the listed words counted in, %g times each" % with_listed if with_listed > 0 else "")
    data = encode(order, share, rows, described)
    # The file must say what the table says, read back as the keyboard reads it.
    reader = Reader(data.decode("utf-8"))
    worst = max(abs(-reader.cost(w) / 100.0 - table.log10(w)) / (len(w) + 1) for w in list(unlisted)[:3000] + ["prrsrnyable", "zxqj", "a"])
    if worst > 0.006:
        sys.exit("letters: the file read back differs from the table by %.4f a letter" % worst)
    print("%s rows, %s bytes; read back, it is the table to %.4f a letter" % (format(len(rows), ","), format(len(data), ","), worst))
    print(measure(table, held))
    print("worth (9 + log10 of the share + log10 of the chance): " + "  ".join("%s %.1f" % (w, reader.worth(w)) for w in ("berber", "yurt", "kira", "nguyen", "whiby", "dlam", "prrsrnyable", "hkpelwss")))
    out = args["--out"]
    if check:
        same = os.path.exists(out) and open(out, "rb").read() == data
        print("%s: %s" % (os.path.relpath(out, ROOT), "same" if same else "DIFFERENT"))
        sys.exit(0 if same else 1)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out + ".part", "wb") as f:
        f.write(data)
    os.replace(out + ".part", out)
    print("wrote " + os.path.relpath(out, ROOT))


if __name__ == "__main__":
    main(sys.argv[1:])
