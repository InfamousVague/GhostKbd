#!/usr/bin/env python3
"""Ghost Keyboard's capitals: android/app/src/main/assets/dict/en_capitals.txt, the words English all but always
writes with a capital (Tom, Monday, Algeria, French), so that autocorrect can give one to a name typed in lower case
in the middle of a sentence (Autocorrect.proper reads the file).

    python3 tool/capitals.py                 # counts, and writes the list
    python3 tool/capitals.py --check         # counts again and fails if the bundled file would change
    python3 tool/capitals.py --bars          # what other bars would keep and cost, on sentences they were not set from
    python3 tool/capitals.py --held          # the list as bundled, on the held-out sentences the typing bench reads
    python3 tool/capitals.py --word may      # how a word was counted
    python3 tool/capitals.py --export PATH   # the export, where it is not in build/ngram-src/
    python3 tool/capitals.py --vocab PATH    # a word list to take the vocabulary from; say it again for each list

Standard library only; downloads nothing. The export is the one tool/next_words.py fetches into build/ngram-src/
(eng_sentences_detailed.tsv.bz2: Tatoeba's English sentences, CC BY 2.0 FR), and the vocabulary is English's word
lists, so run it after those.

The vocabulary is every list autocorrect reads as English: assets/dict/en.tsv, and assets/dict/en_more.tsv where the
tree has one (words added to English's list beside wordfreq's, many of them names). `--vocab` names the lists
instead, one a time it is given: `--vocab android/app/src/main/assets/dict/en.tsv --vocab build/more.tsv`. A list
is a word a line, with whatever follows a tab (its frequency) left aside and lines that start with # passed over;
its words are read in lower case. The file's own first lines say which lists it was made over, so `--check` fails
on a tree whose lists have changed since: make it again.

The rule
--------
1. The sentences tool/next_words.py holds out are held out here too: every 20th line of the export is never
   counted, because those are the sentences the typing bench and tool/versus type.
2. A word is counted only where its capital can be nobody's but its own: straight after another word of the same
   sentence, with a space between them, or a comma or semicolon and a space. The first word of a sentence is not
   counted, nor a word after a quotation mark, a bracket, a colon, a dash, a digit or a full stop (Mr. Smith): a
   capital there may be the sentence's, or a quoted sentence's.
3. Each time it is counted a word is written one of three ways: in lower case, with a capital (its first letter a
   capital and no other), or some other way (NASA, iPhone, a sentence shouted in capitals).
4. A word is kept where all of these hold:
   - it is in the word list, of three letters or more, with no apostrophe: autocorrect never mends toward a word
     the list lacks, "I" has a rule of its own (Autocorrect.capitalI), and of two letters typed the keyboard
     cannot tell a title from a slip or a shorthand (mr for me, st for at, fr, io);
   - it was counted MIN_COUNT times or more, so that its share means something;
   - it had its capital SHARE of those times or more.
   That is the whole of it: no list of names, days or countries by hand.

Words that are both a name and a word (will, mark, rose, may, bill, china, turkey) are settled by the same bar,
each by how it was written, and a word that is a name only some of the time is left alone: a capital wrongly given
spoils a right word, and the project counts that four times as bad as a slip left unmended. As counted here:
will 0.0%, rose 3.5%, may 4.0%, bill 14.9%, mark 32.2%, turkey 65.4%, polish 72.9%, jack 74.8%, march 86.8%,
god 89.4%, bob 94.6% are left in lower case; china 95.9%, august 99.0%, french 99.7% and tom 100.0% get their capital.

Why these numbers (`--bars` prints both tables again):
- SHARE, 19 times in 20. By the four-to-one measure a word would pay for its capital from four times in five,
  and on these sentences that measure alone would have the bar at 80% (capitals rightly given less four for each
  given wrongly: 470,647 there, 465,781 at 95%). The bar stands well above it because Tatoeba is not anybody's
  messages: it reads like a phrasebook, written by translators, fuller of names, countries and languages than
  what people type (tool/next_words.py scales its Toms down for the same reason), so a word's share here flatters
  the name. Between 80% and 95% lie god, lord, states, united, western, march, eve, gulf, ford, trump, bob, joe:
  words. Not higher than 95%, because from there up what is written in lower case is mostly a careless writer's
  (russians, thai, hindi, bible, twitter, 47 frenches of 13,904) and one of those among thirty would cost a name
  its place.
- MIN_COUNT, 5 times. The list made from half the counted sentences and held against the other half (both ways):
  words counted 5 to 9 times had their capital 99.2% of the time in the sentences they were not counted from,
  3 or 4 times 98.1%, twice 96.4%, once 90.4%. Under five, what is kept is as often a word out of a title as a
  name (martyrs, crusader, epiphany, aeronautics).
- LETTERS, 3. Mr, Dr, St and Mt pass the other two, and so do Fr, Io, Ko, Wa and Ys.
"""
import bz2
import collections
import hashlib
import os
import re
import sys
from email.utils import parsedate_to_datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
DICT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "dict")
OUT = os.path.join(DICT, "en_capitals.txt")
EXPORT = os.path.join(ROOT, "build", "ngram-src", "eng_sentences_detailed.tsv.bz2")
CREDITS = os.path.join(ROOT, "android", "app", "src", "main", "assets", "ngram", "CREDITS.txt")
#: English's word lists, where the tree has them: wordfreq's, and the words added beside it. `--vocab` names others.
LISTS = [os.path.join(DICT, "en.tsv"), os.path.join(DICT, "en_more.tsv")]

#: As tool/next_words.py: every 20th sentence is held out and never counted.
HOLD_OUT = 20
#: `--held` reads the held-out sentences up to this one (a line of build/typing-bench/en-heldout.txt each) and no
#: further: from the next on they are tool/versus's unseen sets (scoreboard.py's UNSEEN_FIRST), which nothing reads
#: but the scoreboard's own totals.
HELD_READ = 100000
#: A word is kept where it was counted this often in the middle of a sentence, and had its capital this share of
#: those times: 19 times in 20. See the docstring for why these two.
MIN_COUNT = 5
SHARE = 0.95
#: And the fewest letters a kept word has.
LETTERS = 3

WORD = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)*")
#: What may stand between two words for the second to be counted: a space, or a comma or semicolon and a space.
BETWEEN = re.compile(r"[,;]? ")
LOWER, CAPITAL, OTHER = 0, 1, 2


def form(word):
    """How [word] is written: in lower case, with a capital of its own, or another way."""
    if word.islower():
        return LOWER
    if word[0].isupper() and (len(word) == 1 or word[1:].islower()):
        return CAPITAL
    return OTHER


def middles(text):
    """Every word of [text] that stands straight after another word of its sentence: (lower case, form)."""
    text = text.replace("’", "'")
    out = []
    end = -1
    for m in WORD.finditer(text):
        if end >= 0 and BETWEEN.fullmatch(text, end, m.start()):
            raw = m.group()
            out.append((raw.lower(), form(raw)))
        end = m.end()
    return out


def sentences(path):
    """Every sentence of the export as (its number, its text, who wrote it): numbered as tool/next_words.py counts."""
    n = 0
    with bz2.open(path, "rt", encoding="utf-8", errors="replace", newline="\n") as f:
        for line in f:
            cols = line.rstrip("\n").split("\t")
            if len(cols) >= 4:
                n += 1
                yield n, cols[2], cols[3]


def vocabulary(paths):
    """The words of the lists at [paths], in lower case: a line a word, what follows a tab left aside, a line that
    starts with # passed over."""
    words = set()
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                word = line.split("\t", 1)[0].strip()
                if word and not word.startswith("#"):
                    words.add(word.lower())
    return words


def listed(paths):
    """How the file's first lines name the lists at [paths]: assets/dict/en.tsv is wordfreq's, and any other is
    named beside it, one of the app's by its place among the assets and one from elsewhere by its file's name."""
    def name(path):
        full = os.path.abspath(path)
        return "assets/dict/" + os.path.basename(full) if os.path.dirname(full) == os.path.abspath(DICT) else os.path.basename(full)
    names = [name(p) for p in paths]
    first = "assets/dict/en.tsv"
    if first not in names:
        return "Vocabulary limited to %s." % " and ".join(names)
    rest = [n for n in names if n != first]
    return "Vocabulary limited to the wordfreq list (%s, CC BY-SA 4.0)%s." % (first, " and " + " and ".join(rest) if rest else "")


def count(path, part=lambda n: 0):
    """Counts the export: for each part [part] puts a counted sentence in, {word: [lower, capital, other]}; the same
    for the held-out sentences up to the [HELD_READ]th (the rest are passed over unread); who wrote the counted
    ones; and how many sentences there were."""
    parts = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, 0, 0]))
    held = collections.defaultdict(lambda: [0, 0, 0])
    users = set()
    total = 0
    for n, text, user in sentences(path):
        total = n
        if n % HOLD_OUT == 0 and n // HOLD_OUT > HELD_READ:
            continue
        into = held if n % HOLD_OUT == 0 else parts[part(n)]
        found = middles(text)
        for word, how in found:
            into[word][how] += 1
        if found and n % HOLD_OUT != 0:
            users.add(user)
    return parts, held, users, total


def kept(counts, vocab, least=MIN_COUNT, share=SHARE):
    """The words of [counts] that pass the rule, in lower case."""
    out = set()
    for word, (lower, capital, other) in counts.items():
        n = lower + capital + other
        if len(word) >= LETTERS and "'" not in word and word in vocab and n >= least and capital >= share * n:
            out.add(word)
    return out


def judge(words, counts):
    """The list [words] held against sentences counted as [counts]: (names given their capital, names left without,
    capitals given to a word written in lower case, words rightly left in lower case), and the wrong ones by word."""
    given = missed = wrong = left = 0
    worst = collections.Counter()
    for word, (lower, capital, _) in counts.items():
        if len(word) < LETTERS:
            continue
        if word in words:
            given += capital
            wrong += lower
            if lower:
                worst[word] = lower
        else:
            missed += capital
            left += lower
    return given, missed, wrong, left, worst


def merged(parts):
    out = collections.defaultdict(lambda: [0, 0, 0])
    for counts in parts:
        for word, c in counts.items():
            for i in range(3):
                out[word][i] += c[i]
    return out


def export_date(path):
    try:
        return parsedate_to_datetime(open(path + ".date").read().strip()).strftime("%Y-%m-%d")
    except Exception:
        return "unknown"


def encode(words, counts, date, digest, total, lists):
    """The file: what it is and where it is from ([lists] are the word lists its vocabulary is), then each word as
    it is written, in the word list's order of letters."""
    head = [
        "# Words English all but always writes with a capital, each as it is written. Autocorrect gives a name typed",
        "# in lower case its capital from this (Autocorrect.proper). Made by tool/capitals.py in Ghost Keyboard's",
        "# repository; see NOTICE.txt beside this file.",
        "#",
        "# Counts derived from Tatoeba (https://tatoeba.org, CC BY 2.0 FR): its English export of %s, %s sentences," % (date, format(total, ",")),
        "# every %dth held out and not counted (sha256 %s)." % (HOLD_OUT, digest),
        "# %s None of Tatoeba's sentences is here." % listed(lists),
        "#",
        "# Kept: a word of the list, of %d letters or more, counted %d times or more straight after another word of its" % (LETTERS, MIN_COUNT),
        "# sentence, and written with a capital %d times in 100 or more. %s words." % (round(SHARE * 100), format(len(words), ",")),
    ]
    return ("\n".join(head + [w[0].upper() + w[1:] for w in sorted(words)]) + "\n").encode("utf-8")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main(argv):
    args = list(argv)
    path = EXPORT
    if "--export" in args:
        i = args.index("--export")
        path = args[i + 1]
        del args[i:i + 2]
    word = None
    if "--word" in args:
        i = args.index("--word")
        word = args[i + 1].lower()
        del args[i:i + 2]
    lists = []
    while "--vocab" in args:
        i = args.index("--vocab")
        lists.append(args[i + 1])
        del args[i:i + 2]
    # Not told which: English's lists as the tree has them.
    lists = lists or [p for p in LISTS if os.path.exists(p)]
    if not os.path.exists(path):
        sys.exit("capitals: no %s (run: python3 tool/next_words.py en, or give --export PATH)" % path)
    missing = [p for p in lists if not os.path.exists(p)]
    if missing or not lists:
        sys.exit("capitals: no word list %s" % (" ".join(missing) or LISTS[0]))
    vocab = vocabulary(lists)

    if "--bars" in args:
        # Half the counted sentences against the other half: neither is the held-out twentieth, so a bar is never
        # chosen by the sentences the keyboard is tested on.
        parts, _, _, _ = count(path, part=lambda n: n % 2)
        halves = ((parts[1], parts[0]), (parts[0], parts[1]))
        print("the list made from half the counted sentences, held against the other half (both ways, added up)")
        print("%5s %6s  %9s %8s %7s  %8s  %s" % ("share", "words", "capitals", "missed", "wrong", "net", "most often wrong"))
        for share in (0.5, 0.8, 0.9, 0.95, 0.98, 0.99, 1.0):
            n = given = missed = wrong = 0
            worst = collections.Counter()
            for made, tried in halves:
                words = kept(made, vocab, MIN_COUNT, share)
                g, m, w, _, most = judge(words, tried)
                n += len(words); given += g; missed += m; wrong += w
                worst.update(most)
            # The project's measure: a right word spoiled is four times as bad as a slip left unmended.
            print("%5.2f %6d  %9d %8d %7d  %8d  %s" % (share, n // 2, given, missed, wrong, given - 4 * wrong, " ".join("%s:%d" % kv for kv in worst.most_common(10))))
        print("by how often a word was counted, at a share of %.2f:" % SHARE)
        edges = [1, 2, 3, 5, 10, 20, 50, 200, None]
        for lo, hi in zip(edges, edges[1:]):
            n = capital = lower = 0
            worst = collections.Counter()
            for made, tried in halves:
                words = kept(made, vocab, lo, SHARE) - (kept(made, vocab, hi, SHARE) if hi else set())
                n += len(words)
                for w in words:
                    c = tried.get(w)
                    if c:
                        capital += c[1]; lower += c[0]
                        if c[0]:
                            worst[w] += c[0]
            print("  counted %-9s %5d words; in the other half with a capital %6d, in lower case %4d (%.1f%% with)  %s" % (
                "%d to %d" % (lo, hi - 1) if hi else "%d or more" % lo, n // 2, capital, lower, 100.0 * capital / max(1, capital + lower), " ".join("%s:%d" % kv for kv in worst.most_common(8))))
        return

    parts, held, users, total = count(path)
    counts = parts[0]
    if word is not None:
        for name, c in (("counted", counts), ("held out", held)):
            lower, capital, other = c.get(word, (0, 0, 0))
            n = lower + capital + other
            print("%-9s %s: %d times in the middle of a sentence; lower case %d, with a capital %d (%.1f%%), another way %d" % (
                name, word, n, lower, capital, 100.0 * capital / max(1, n), other))
        print("in the word lists (%s): %s; kept: %s" % (" ".join(os.path.basename(p) for p in lists), "yes" if word in vocab else "no", "yes" if word in kept(counts, vocab) else "no"))
        return
    words = kept(counts, vocab)
    data = encode(words, counts, export_date(path), sha256(path), total, lists)

    if "--held" in args:
        bundled = {line.strip().lower() for line in open(OUT, encoding="utf-8") if line.strip() and not line.startswith("#")}
        given, missed, wrong, left, worst = judge(bundled, held)
        print("the bundled list (%d words) on the first %s held-out sentences, words in the middle of a sentence:" % (len(bundled), format(HELD_READ, ",")))
        print("  written with a capital: %d; given it: %d (%.1f%%); left without: %d" % (given + missed, given, 100.0 * given / max(1, given + missed), missed))
        print("  written in lower case: %d; given a capital: %d (%.2f in 1000)" % (wrong + left, wrong, 1000.0 * wrong / max(1, wrong + left)))
        print("  most often wrong: %s" % " ".join("%s:%d" % kv for kv in worst.most_common(20)))
        lost = collections.Counter({w: c[1] for w, c in held.items() if w not in bundled and len(w) >= LETTERS and c[1]})
        print("  most often left without: %s" % " ".join("%s:%d" % kv for kv in lost.most_common(20)))
        return

    if "--check" in args:
        same = os.path.exists(OUT) and open(OUT, "rb").read() == data
        print("en_capitals.txt %s" % ("same" if same else "DIFFERENT"))
        sys.exit(0 if same else 1)

    with open(OUT, "wb") as f:
        f.write(data)
    # Tatoeba's licence asks that its contributors be named: assets/ngram/CREDITS.txt names them for the starter
    # tables, counted from the same sentences. Anyone counted here that it lacks is said, so it can be made again.
    credited = set()
    if os.path.exists(CREDITS):
        credited = set(open(CREDITS, encoding="utf-8").read().split("\n\n", 1)[-1].strip().split(", "))
    lacking = sorted(users - credited)
    print("en_capitals.txt: %d words, %d bytes, from %d sentences (%d counted); vocabulary: %s (%d words)" % (
        len(words), len(data), total, total - total // HOLD_OUT, " ".join(os.path.basename(p) for p in lists), len(vocab)))
    print("contributors counted: %d; not named in assets/ngram/CREDITS.txt: %d %s" % (len(users), len(lacking), " ".join(lacking[:20])))


if __name__ == "__main__":
    main(sys.argv[1:])
