#!/usr/bin/env python3
"""Ghost Keyboard's capitals: android/app/src/main/assets/dict/en_capitals.txt, the words English all but always
writes with a capital (Tom, Monday, Algeria, French), so that autocorrect can give one to a name typed in lower case
in the middle of a sentence (Autocorrect.proper reads the file); and dict/en_capitals_beside.tsv, the ones among
them that are no names while another language is loaded with English (Autocorrect.load reads that).

    python3 tool/capitals.py                 # counts, and writes both files
    python3 tool/capitals.py --check         # counts again and fails if a bundled file would change
    python3 tool/capitals.py --bars          # what other bars would keep and cost, on sentences they were not set from
    python3 tool/capitals.py --held          # the list as bundled, on the held-out sentences the typing bench reads
    python3 tool/capitals.py --beside        # the names kept beside each language, on that language's held-out sentences
    python3 tool/capitals.py --beside --bars # what other values of UNSEEN would keep and cost, on languages cut down
    python3 tool/capitals.py --word may      # how a word was counted (with --beside: in every language)
    python3 tool/capitals.py --export PATH   # English's export, where it is not in build/ngram-src/
    python3 tool/capitals.py --vocab PATH    # a word list to take the vocabulary from; say it again for each list

Standard library only; downloads nothing. The exports are the ones tool/next_words.py fetches into build/ngram-src/
(<language>_sentences_detailed.tsv.bz2: Tatoeba's sentences, CC BY 2.0 FR; the other languages' are looked for in
the folder English's is in), and the vocabulary is the word lists, so run it after those. `--bars`, `--held` and a
`--word` without `--beside` read English's export alone (a minute); everything else reads all of them (five).
The contributors whose sentences are counted are those assets/ngram/CREDITS.txt names for the starter tables,
counted from the same exports; a run that writes the files says so if any is not.

The vocabulary is every list autocorrect reads as English: assets/dict/en.tsv, and assets/dict/en_wide.tsv or
en_more.tsv where the tree has one (words added to English's list beside wordfreq's, many of them names). `--vocab`
names the lists instead, one a time it is given: `--vocab android/app/src/main/assets/dict/en.tsv --vocab
build/more.tsv`. A list is a word a line and its Zipf frequency after a tab, with whatever follows another tab (its
marks) left aside and lines that start with # passed over; its words are read in lower case. The file's own first
lines say which lists it was made over, so `--check` fails on a tree whose lists have changed since: make it again.

The rule
--------
1. The sentences tool/next_words.py holds out are held out here too: every 20th line of an export is never
   counted, because English's are the sentences the typing bench and tool/versus type.
2. A word is counted only where its capital can be nobody's but its own: straight after another word of the same
   sentence, with a space between them, or a comma or semicolon and a space. The first word of a sentence is not
   counted, nor a word after a quotation mark, a bracket, a colon, a dash, a digit or a full stop (Mr. Smith): a
   capital there may be the sentence's, or a quoted sentence's.
3. A word is letters of any alphabet with the apostrophes inside it, whole: José is one word and not "Jos" and a
   letter, and the first half of Wal-Mart or Indo-European is no word of its own. (Read as a to z alone until
   2026-11-13, when Bah, Jos, Roc and Fran were on the list by Bahá'í, José, Rocío and François, and Wal, Indo and
   Sino by what follows their hyphen.)
4. Each time it is counted a word is written one of three ways: in lower case, with a capital (its first letter a
   capital and no other), or some other way (NASA, iPhone, a sentence shouted in capitals).
5. A word is kept where all of these hold:
   - it is in the word list, of three letters or more, with no apostrophe: autocorrect never mends toward a word
     the list lacks, "I" has a rule of its own (Autocorrect.capitalI), and of two letters typed the keyboard
     cannot tell a title from a slip or a shorthand (mr for me, st for at, fr, io);
   - it was counted MIN_COUNT times or more, so that its share means something;
   - it had its capital SHARE of those times or more.
   That is the whole of it: no list of names, days or countries by hand.

Words that are both a name and a word (will, mark, rose, may, bill, china, turkey) are settled by the same bar,
each by how it was written, and a word that is a name only some of the time is left alone: a capital wrongly given
spoils a right word, and the project counts that four times as bad as a slip left unmended. `--word` says how any
one was counted.

Why these numbers (`--bars` prints both tables again)
-----------------------------------------------------
- SHARE, 19 times in 20, is a judgment and not a measured best. By the four-to-one measure a word would pay for
  its capital from four times in five, and on these sentences that measure alone would have the bar at 80% or
  under (the list made from one half of the counted sentences and held against the other: from 95% down to 90% it
  gives twelve more capitals rightly for each one wrongly, from 90% down to 80% six for one). It stands above that
  because Tatoeba is not anybody's messages: it reads like a phrasebook, written by translators, fuller of names,
  countries and languages than what people type (tool/next_words.py scales its Toms down for the same reason), so
  a word's share here flatters the name, and nothing here can say by how much. Between 80% and 95% lie god, lord,
  states, united, western, march, eve, gulf, ford, trump, joe: words. What it costs is in plain sight too: under
  twenty counts 95% means never once in lower case, so one careless writer keeps Scott (18 of 19) off the list.
  Not higher than 95%, because from there up what is written in lower case is mostly a careless writer's
  (russians, thai, hindi, bible, twitter) and one of those among thirty would cost a name its place.
- MIN_COUNT, 5 times. The list made from half the counted sentences and held against the other half (both ways):
  words counted 5 to 9 times had their capital 99 times in 100 in the sentences they were not counted from, 3 or 4
  times 98, twice 96, once 90. Under five, what is kept is as often a word out of a title as a name (martyrs,
  crusader, epiphany, aeronautics).
- LETTERS, 3. Mr, Dr, St and Mt pass the other two, and so do Fr, Io, Ko, Wa and Ys.

Beside another language
-----------------------
English's list is read only with English loaded, and with it every other enabled language of the same script is
loaded too (LanguageState.dictionaries). A spelling that is a name in English may be an ordinary word in the other
(French "roman", a novel; German "mir"; Dutch "april", which Dutch writes without a capital), and a person who
writes both gets a capital on it in a sentence of the other. Until 2026-11-13 the decoder took every spelling the
other list has out of the names, and the other lists have most of them: wordfreq counted what each language's
writers wrote, and they write of Tom, Mary, London and Boston as English does. Beside German 26 of the 146 names
the run of 2026-11-11 gives their capital were left.

So each other Latin-script language's own sentences are counted the same way (BESIDE: its Tatoeba exports, every
20th held out, a word under its plain spelling as Script.plain has it, so "catalán" counts for catalan), and a
name is judged beside each language whose list has its spelling (or an accented word that is typed as it):

    of all the times the spelling is written by someone who writes as much of one language as of the other,
    how often has it a capital?   (f_en * s_en + f * s) / (f_en + f)

f_en and f are how common the two lists have it (ten to the power of its Zipf frequency; the other list's accented
words that share the plain spelling added up), s_en is the share of English's counted sentences that write it
with a capital, and s the share of the other language's. The name is taken out beside that language where the
answer is under SHARE, the bar it passed in English alone: Czech writes "o tom" (about that) often enough that Tom
is no name beside it, and Tom is one beside German, Spanish and French, whose sentences never write it otherwise.

Where the other language's sentences write the spelling fewer than MIN_COUNT times there is no share to go by.
Then s is 0 if any of those few is in lower case, and UNSEEN (nine in ten) otherwise. For a name English always
writes with a capital that takes it out where the other list has the spelling commoner than English's (Slovenian
"sami", themselves, and "mali", small, in a language with 3,586 sentences to go by), and leaves it where English's
is the commoner: German's 750,000 sentences write of Günter three times, of Roland three and of Europe, Taylor
and Andrew never, because Tatoeba's names are Tom and Mary in every language; its list has them because its
writers tell of them, as English's do.

UNSEEN was set on ten languages with sentences to spare (de es fr it pt nl tr hu fi pl), each cut down to every
200th, 50th and 10th of its counted sentences, the names beside it worked out from that alone and held to the
rest (`--beside --bars`). Against nothing (every unseen spelling taken as an ordinary word, which keeps 77%, 82%
and 88% of the capitals the names give on English's held-out sentences), nine in ten keeps 96%, 97% and 97%, and
in the language's own sentences gives a capital wrongly 0.08, 0.07 and 0.05 times in a thousand words where
nothing gives 0.03 to 0.04 and the rule before 2026-11-13 gave 0.07 (it took out no accented word's plain
spelling). Each step up to there gives seventeen or more capitals rightly for one wrongly, stock names aside; the
steps past it, in the smallest cut, nine, six and five. With no rule at all (only what the cut-down sentences
show taken out) it is 1.5, 0.6 and 0.2 wrongly in a thousand.

A language loaded with English that the file has no line for (one added to the keyboard since the file was made)
gets the rule there was before: every spelling its list has is taken out, and the plain spelling of every accented
word.

`--beside` holds the result to each language's own held-out sentences, and says what English's keep of their
capitals beside it.
"""
import bz2
import collections
import hashlib
import math
import os
import re
import sys
import unicodedata
from email.utils import parsedate_to_datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
DICT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "dict")
OUT = os.path.join(DICT, "en_capitals.txt")
OUT_BESIDE = os.path.join(DICT, "en_capitals_beside.tsv")
EXPORT = os.path.join(ROOT, "build", "ngram-src", "eng_sentences_detailed.tsv.bz2")
CREDITS = os.path.join(ROOT, "android", "app", "src", "main", "assets", "ngram", "CREDITS.txt")
#: English's word lists, where the tree has them: wordfreq's, and the words added beside it (the wider list, or
#: the one counted from Tatoeba before it). `--vocab` names others.
LISTS = [os.path.join(DICT, "en.tsv"), os.path.join(DICT, "en_wide.tsv"), os.path.join(DICT, "en_more.tsv")]
#: The languages English can be loaded beside: the other Latin-script languages that have a word list
#: (Languages.kt), each with its Tatoeba exports as tool/next_words.py's ISO3 has them. Serbo-Croatian's Cyrillic
#: sentences are read and count for nothing here: no word of them is a spelling of English's.
BESIDE = {
    "es": ["spa"], "fr": ["fra"], "de": ["deu"], "it": ["ita"], "pt": ["por"], "nl": ["nld"], "sv": ["swe"],
    "nb": ["nob"], "da": ["dan"], "fi": ["fin"], "is": ["isl"], "pl": ["pol"], "cs": ["ces"], "sk": ["slk"],
    "sl": ["slv"], "hu": ["hun"], "ro": ["ron"], "lt": ["lit"], "lv": ["lvs"], "tr": ["tur"], "ca": ["cat"],
    "id": ["ind"], "ms": ["zsm", "zlm"], "fil": ["tgl"], "vi": ["vie"], "sh": ["hrv", "bos", "srp"],
}

#: As tool/next_words.py: every 20th sentence is held out and never counted.
HOLD_OUT = 20
#: `--held` reads English's held-out sentences up to this one (a line of build/typing-bench/en-heldout.txt each)
#: and no further: from the next on they are tool/versus's unseen sets (scoreboard.py's UNSEEN_FIRST), which
#: nothing reads but the scoreboard's own totals.
HELD_READ = 100000
#: A word is kept where it was counted this often in the middle of a sentence, and had its capital this share of
#: those times: 19 times in 20. See the docstring for why these two.
MIN_COUNT = 5
SHARE = 0.95
#: And the fewest letters a kept word has.
LETTERS = 3
#: Beside another language: the share of the time a spelling counts as written with a capital there, where that
#: language's sentences write it fewer than MIN_COUNT times and never in lower case. See the docstring for how it
#: was set; `--beside --bars` prints the table again.
UNSEEN = 0.9
#: The languages `--beside --bars` cuts down: the ten with the most sentences.
BARS = ["de", "es", "fr", "it", "pt", "nl", "tr", "hu", "fi", "pl"]
#: Tatoeba's two stock names, which are near half the names its English sentences write: `--held` says the share
#: with them and without.
STOCK = ("tom", "mary")

#: A word: letters of any alphabet, with the apostrophes inside it.
WORD = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)*")
#: What may stand between two words for the second to be counted: a space, or a comma or semicolon and a space.
BETWEEN = re.compile(r"[,;]? ")
LOWER, CAPITAL, OTHER = 0, 1, 2
#: Script.plain's four Latin letters that Unicode will not take apart.
LATIN_MAP = str.maketrans({"ł": "l", "ø": "o", "đ": "d", "ı": "i"})


def form(word):
    """How [word] is written: in lower case, with a capital of its own, or another way."""
    if word.islower():
        return LOWER
    if word[0].isupper() and (len(word) == 1 or word[1:].islower()):
        return CAPITAL
    return OTHER


def lower(word, lang=None):
    """Lower case as the language's own board has it: Turkish I is ı and İ is i (tool/next_words.py's lower)."""
    if lang == "tr":
        word = word.replace("I", "ı").replace("İ", "i")
    return word.lower()


def plain(word):
    """Script.plain for a Latin word in lower case: ß is ss, ł ø đ ı are l o d i, and every accent is gone."""
    if word.isascii():
        return word
    word = word.replace("ß", "ss").translate(LATIN_MAP)
    return "".join(c for c in unicodedata.normalize("NFD", word) if unicodedata.category(c) != "Mn")


def whole(text, at):
    """Whether the word that ends at [at] ends there: no mark rides on its last letter, no digit follows, and no
    hyphen with a letter after it (Wal-Mart, Indo-European: the first half is no word of its own)."""
    after = text[at:at + 1]
    if not after:
        return True
    if after.isdigit() or after == "_" or unicodedata.category(after)[0] == "M":
        return False
    return not (after in "-‐‑" and text[at + 1:at + 2].isalpha())


def middles(text, lang=None):
    """Every word of [text] that stands straight after another word of its sentence: (lower case, form)."""
    text = unicodedata.normalize("NFC", text.replace("’", "'"))
    out = []
    end = -1
    for m in WORD.finditer(text):
        if end >= 0 and BETWEEN.fullmatch(text, end, m.start()) and whole(text, m.end()):
            raw = m.group()
            out.append((lower(raw, lang), form(raw)))
        end = m.end()
    return out


def sentences(paths, read=None):
    """Every sentence of the exports at [paths] as (its number, its text, who wrote it): numbered as
    tool/next_words.py counts, on through a language's exports where it has several. [read], where it is given,
    is told how many sentences each export had, once it has been read to its end."""
    n = 0
    for path in paths:
        first = n
        with bz2.open(path, "rt", encoding="utf-8", errors="replace", newline="\n") as f:
            for line in f:
                cols = line.rstrip("\n").split("\t")
                if len(cols) >= 4:
                    n += 1
                    yield n, cols[2], cols[3]
        if read is not None:
            read[path] = n - first


def frequencies(paths):
    """The words of the lists at [paths], in lower case, each with its Zipf frequency (the commonest a list gives
    it; None where no list gives a number): a line a word, a tab, its frequency; what follows another tab is left
    aside, and a line that starts with # passed over."""
    words = {}
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                cols = line.rstrip("\n").split("\t")
                word = cols[0].strip().lower()
                if not word or word.startswith("#"):
                    continue
                try:
                    zipf = float(cols[1])
                except (IndexError, ValueError):
                    zipf = None
                if word not in words or (zipf is not None and (words[word] is None or zipf > words[word])):
                    words[word] = zipf
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
    """Counts English's export: for each part [part] puts a counted sentence in, {word: [lower, capital, other]};
    the same for the held-out sentences up to the [HELD_READ]th (the rest are passed over unread); who wrote the
    counted ones; and how many sentences there were."""
    parts = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, 0, 0]))
    held = collections.defaultdict(lambda: [0, 0, 0])
    users = set()
    total = 0
    for n, text, user in sentences([path]):
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


def count_beside(lang, paths, spellings):
    """How [lang]'s own sentences (the exports at [paths]) write each of [spellings], a word of theirs counted
    under its plain spelling: {spelling: [lower, capital, other]} for the counted sentences and for the held-out
    ones (every [HOLD_OUT]th, all of them: no other language's are typed by anything), how many words were counted
    in all, how many held-out ones are written in lower case, who wrote the counted sentences, and how many
    sentences each export had."""
    counted = collections.defaultdict(lambda: [0, 0, 0])
    held = collections.defaultdict(lambda: [0, 0, 0])
    users = set()
    words = held_lower = 0
    read = {}
    for n, text, user in sentences(paths, read):
        found = middles(text, lang)
        if n % HOLD_OUT == 0:
            for word, how in found:
                held_lower += how == LOWER
                word = plain(word)
                if word in spellings:
                    held[word][how] += 1
            continue
        words += len(found)
        for word, how in found:
            word = plain(word)
            if word in spellings:
                counted[word][how] += 1
        if found:
            users.add(user)
    return counted, held, words, held_lower, users, read


def kept(counts, vocab, least=MIN_COUNT, share=SHARE):
    """The words of [counts] that pass the rule, in lower case."""
    out = set()
    for word, (low, capital, other) in counts.items():
        n = low + capital + other
        if len(word) >= LETTERS and "'" not in word and word in vocab and n >= least and capital >= share * n:
            out.add(word)
    return out


def is_name(word, words):
    """Whether the decoder gives [word] a capital from the list [words]: a word of it, or one's possessive
    (Autocorrect.name: tom's is Tom's)."""
    return word in words or (word.endswith("'s") and word[:-2] in words)


def judge(words, counts):
    """The list [words] held against sentences counted as [counts], as the decoder would give them their capitals:
    {word: [written with a capital and given it, written with one and left without, written in lower case and
    given one, written in lower case and left]} for every word of three letters or more. Not I'm, I'll, I'd and
    I've: they are no names, and have their capital by another rule (Autocorrect.capitalI)."""
    out = {}
    for word, (low, capital, _) in counts.items():
        if len(word) < LETTERS or word.startswith("i'"):
            continue
        out[word] = [capital, 0, low, 0] if is_name(word, words) else [0, capital, 0, low]
    return out


def totals(judged, leave=()):
    """[judge]'s four counts added up, and the words given a capital wrongly with how often; [leave] are words to
    pass over, with their possessives."""
    sums = [0, 0, 0, 0]
    worst = collections.Counter()
    for word, row in judged.items():
        if word in leave or (word.endswith("'s") and word[:-2] in leave):
            continue
        for i in range(4):
            sums[i] += row[i]
        if row[2]:
            worst[word] = row[2]
    return sums, worst


def beside(names, zipf, other, counted, least=MIN_COUNT, share=SHARE, unseen=UNSEEN):
    """The names that are no names beside a language: see the docstring. [names] are English's, each with the
    share of English's counted sentences that write it with a capital; [zipf] how common English's lists have
    each; [other] the other language's word list ([frequencies]); [counted] how its sentences write each spelling.
    Returns {name: (the share with a capital over both languages, how often the other's sentences wrote it, of
    them with a capital)} for every name the other list has, and the ones among them that are taken out."""
    common = collections.defaultdict(float)
    for word, z in other.items():
        spelling = plain(word)
        if spelling in names and z is not None:
            common[spelling] += 10.0 ** z
    judged, gone = {}, set()
    for word, f in common.items():
        low, capital, other_way = counted.get(word, (0, 0, 0))
        n = low + capital + other_way
        s = capital / n if n >= least else (0.0 if low else unseen)
        f_en = 10.0 ** zipf[word]
        pooled = (f_en * names[word] + f * s) / (f_en + f)
        judged[word] = (pooled, n, capital)
        if pooled < share:
            gone.add(word)
    return judged, gone


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


def encode_beside(gone, sources, lists):
    """The second file: for each language of [gone] its line, the language's list's stem, a tab, and the names
    taken out beside it, a space between. [sources] are the exports read, each as (language, Tatoeba's code, date,
    sentences, sha256)."""
    head = [
        "# English's names (en_capitals.txt) that are no names beside another language: a line a language, its word",
        "# list's stem, a tab, and the names Autocorrect.load takes out while that language is loaded with English,",
        "# in lower case with a space between. A language with no line here is one this file was not made for: every",
        "# spelling its list has is taken out. Made by tool/capitals.py in Ghost Keyboard's repository; see NOTICE.txt",
        "# beside this file.",
        "#",
        "# Taken out: a name whose spelling the other language's list has (or an accented word that is typed as it), and",
        "# that would be written with a capital fewer than %d times in 100 by someone who writes as much of that language" % round(SHARE * 100),
        "# as of English: each list's frequency for the spelling, times the share of that language's sentences that write",
        "# it with a capital where they stand as the names were counted. Where the other language's sentences write it",
        "# fewer than %d times, that share is 0 if any of those is in lower case and %d in 100 otherwise." % (MIN_COUNT, round(UNSEEN * 100)),
        "#",
        "# Counts derived from Tatoeba (https://tatoeba.org, CC BY 2.0 FR), every %dth sentence of each language held" % HOLD_OUT,
        "# out and not counted. %s None of Tatoeba's sentences is here." % ("Vocabulary limited to the wordfreq lists (assets/dict/<language>.tsv, CC BY-SA 4.0)%s." % (
            "" if [os.path.basename(p) for p in lists] == ["en.tsv"] else "; English's are " + " and ".join(os.path.basename(p) for p in lists))),
        "# The exports read (language, Tatoeba's code, export date, sentences, sha256):",
    ]
    head += ["#   %-3s %s  %s %10s  %s" % (lang, iso, date, format(n, ","), digest) for lang, iso, date, n, digest in sources]
    return ("\n".join(head + ["%s\t%s" % (lang, " ".join(sorted(gone[lang]))) for lang in sorted(gone)]) + "\n").encode("utf-8")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def credited():
    """Tatoeba's contributors as assets/ngram/CREDITS.txt names them for the starter tables, counted from the same
    sentences. Its licence asks that they be named: anyone counted here that it lacks is said."""
    if not os.path.exists(CREDITS):
        return set()
    return set(open(CREDITS, encoding="utf-8").read().split("\n\n", 1)[-1].strip().split(", "))


def bundled():
    """The names as bundled, in lower case, and what is taken out beside each language as bundled."""
    names = {line.split("\t")[0].strip().lower() for line in open(OUT, encoding="utf-8") if line.strip() and not line.startswith("#")}
    gone = {}
    if os.path.exists(OUT_BESIDE):
        for line in open(OUT_BESIDE, encoding="utf-8"):
            if line.strip() and not line.startswith("#") and "\t" in line:
                lang, words = line.rstrip("\n").split("\t", 1)
                gone[lang] = set(words.split())
    return names, gone


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
    zipf = frequencies(lists)
    vocab = set(zipf)

    def exports(lang):
        """[lang]'s exports, in the folder English's is in: never fetched from here."""
        found = [os.path.join(os.path.dirname(path), "%s_sentences_detailed.tsv.bz2" % iso) for iso in BESIDE[lang]]
        lacking = [p for p in found if not os.path.exists(p)]
        if lacking:
            sys.exit("capitals: no %s (run: python3 tool/next_words.py %s, which fetches it)" % (" ".join(lacking), lang))
        return found

    if "--bars" in args and "--beside" in args:
        # UNSEEN, on languages with sentences to spare: each cut down to every kth of its counted sentences, the
        # names beside it worked out from that cut alone, and held to the rest of its counted sentences, which say
        # how the language really writes each spelling. No held-out sentence of any language is read.
        parts, held, _, _ = count(path)
        counts = parts[0]
        words = kept(counts, vocab)
        shares = {w: counts[w][1] / sum(counts[w]) for w in words}
        english = {w: row[0] for w, row in judge(words, held).items() if row[0]}
        every = sum(english.values())
        cuts = (200, 50, 10)
        dials = ("before", 0.0, 0.5, 0.7, 0.8, 0.85, 0.88, 0.9, 0.91, 0.92, 0.93, 0.94, 1.0)
        # For each cut and dial: capitals rightly given in the rest, stock names aside, wrongly given, words in the
        # rest, the share of English's held-out capitals still given (added up over the languages), names out.
        table = {(k, u): [0, 0, 0, 0, 0.0, 0] for k in cuts for u in dials}
        for lang in BARS:
            other = frequencies([os.path.join(DICT, "%s.tsv" % lang)])
            full = collections.defaultdict(lambda: [0, 0, 0])
            small = {k: collections.defaultdict(lambda: [0, 0, 0]) for k in cuts}
            tokens = 0
            cut_tokens = dict.fromkeys(cuts, 0)
            m = 0
            for n, text, _ in sentences(exports(lang)):
                if n % HOLD_OUT == 0:
                    continue
                m += 1
                found = [(plain(w), how) for w, how in middles(text, lang)]
                tokens += len(found)
                mine = [(w, how) for w, how in found if w in words]
                for w, how in mine:
                    full[w][how] += 1
                for k in cuts:
                    if m % k == 1:
                        cut_tokens[k] += len(found)
                        for w, how in mine:
                            small[k][w][how] += 1
            for k in cuts:
                rest = {w: [c[i] - small[k].get(w, (0, 0, 0))[i] for i in range(3)] for w, c in full.items()}
                for u in dials:
                    gone = {w for w in words if w in other} if u == "before" else beside(shares, zipf, other, small[k], unseen=u)[1]
                    row = table[(k, u)]
                    row[0] += sum(c[1] for w, c in rest.items() if w not in gone)
                    row[1] += sum(c[1] for w, c in rest.items() if w not in gone and w not in STOCK)
                    row[2] += sum(c[0] for w, c in rest.items() if w not in gone)
                    row[3] += tokens - cut_tokens[k]
                    row[4] += 100.0 * sum(n for w, n in english.items() if w not in gone and not (w.endswith("'s") and w[:-2] in gone)) / max(1, every)
                    row[5] += len(gone)
            print("%s: %d sentences counted, %d words in the middle of them" % (lang, m, tokens), flush=True)
        print("%d names; the capitals they give on English's held-out sentences: %d" % (len(words), every))
        for k in cuts:
            print("each of %s cut to every %dth of its counted sentences, and held to the rest (%s words):" % (" ".join(BARS), k, format(table[(k, 0.0)][3], ",")))
            print("  %-8s %9s %24s | %s" % ("unseen", "names out", "English's capitals kept", "in its own sentences, to 1000 words: capitals rightly (%s aside), wrongly; and what the step from the line above buys" % " and ".join(w.capitalize() for w in STOCK)))
            last = None
            for u in dials:
                right, right_rest, wrong, rest_words, share_kept, out = table[(k, u)]
                step = ""
                if last is not None and u != 0.0:
                    more, bad = right - last[0], wrong - last[1]
                    step = "   %d more rightly, %d more wrongly%s" % (more, bad, " (%.0f to 1)" % (more / bad) if bad > 0 else "")
                last = (right, wrong)
                print("  %-8s %9d %23.1f%% | %6.2f (%5.2f) %7.3f%s" % (
                    "the rule before" if u == "before" else "%.2f" % u, out, share_kept / len(BARS), 1000.0 * right / rest_words, 1000.0 * right_rest / rest_words, 1000.0 * wrong / rest_words, step))
        return

    if "--bars" in args:
        # Half the counted sentences against the other half: neither is the held-out twentieth, so a bar is never
        # chosen by the sentences the keyboard is tested on.
        parts, _, _, _ = count(path, part=lambda n: n % 2)
        halves = ((parts[1], parts[0]), (parts[0], parts[1]))
        print("the list made from half the counted sentences, held against the other half (both ways, added up)")
        print("%5s %6s  %9s %8s %7s  %8s  %s" % ("share", "words", "capitals", "missed", "wrong", "net", "most often wrong"))
        rows = []
        for share in (0.5, 0.8, 0.9, 0.95, 0.98, 0.99, 1.0):
            n = given = missed = wrong = 0
            worst = collections.Counter()
            for made, tried in halves:
                words = kept(made, vocab, MIN_COUNT, share)
                (g, m, w, _), most = totals(judge(words, tried))
                n += len(words); given += g; missed += m; wrong += w
                worst.update(most)
            rows.append((share, given, wrong))
            # The project's measure: a right word spoiled is four times as bad as a slip left unmended.
            print("%5.2f %6d  %9d %8d %7d  %8d  %s" % (share, n // 2, given, missed, wrong, given - 4 * wrong, " ".join("%s:%d" % kv for kv in worst.most_common(10))))
        # What each step down buys, which is what the four-to-one measure asks of it.
        by = {share: (given, wrong) for share, given, wrong in rows}
        for high, low in ((0.95, 0.9), (0.9, 0.8)):
            more, bad = by[low][0] - by[high][0], by[low][1] - by[high][1]
            print("from %.2f down to %.2f: %d more capitals rightly, %d more wrongly (%.1f to 1)" % (high, low, more, bad, more / max(1, bad)))
        print("by how often a word was counted, at a share of %.2f:" % SHARE)
        edges = [1, 2, 3, 5, 10, 20, 50, 200, None]
        for lo, hi in zip(edges, edges[1:]):
            n = capital = low = 0
            worst = collections.Counter()
            for made, tried in halves:
                words = kept(made, vocab, lo, SHARE) - (kept(made, vocab, hi, SHARE) if hi else set())
                n += len(words)
                # In the list's own order, so that words wrong equally often are named the same way every run.
                for w in sorted(words):
                    c = tried.get(w)
                    if c:
                        capital += c[1]; low += c[0]
                        if c[0]:
                            worst[w] += c[0]
            print("  counted %-9s %5d words; in the other half with a capital %6d, in lower case %4d (%.1f%% with)  %s" % (
                "%d to %d" % (lo, hi - 1) if hi else "%d or more" % lo, n // 2, capital, low, 100.0 * capital / max(1, capital + low), " ".join("%s:%d" % kv for kv in worst.most_common(8))))
        return

    parts, held, users, total = count(path)
    counts = parts[0]
    words = kept(counts, vocab)

    if word is not None and "--beside" not in args:
        for name, c in (("counted", counts), ("held out", held)):
            low, capital, other = c.get(word, (0, 0, 0))
            n = low + capital + other
            print("%-9s %s: %d times in the middle of a sentence; lower case %d, with a capital %d (%.1f%%), another way %d" % (
                name, word, n, low, capital, 100.0 * capital / max(1, n), other))
        print("in the word lists (%s): %s; kept: %s" % (" ".join(os.path.basename(p) for p in lists), "yes" if word in vocab else "no", "yes" if word in words else "no"))
        return

    if "--held" in args:
        names, _ = bundled()
        judged = judge(names, held)
        print("the bundled list (%d words) on the first %s held-out sentences, words of %d letters or more in the middle of a sentence," % (len(names), format(HELD_READ, ","), LETTERS))
        print("as the decoder gives them (a name's possessive is the name's; I'm, I'll, I'd and I've are left out):")
        for label, leave in (("", ()), ("  %s and their possessives aside:\n" % " and ".join(w.capitalize() for w in STOCK), STOCK)):
            (given, missed, wrong, left), worst = totals(judged, leave)
            print("%s  written with a capital: %d; given it: %d (%.1f%%); left without: %d" % (label, given + missed, given, 100.0 * given / max(1, given + missed), missed))
            print("  written in lower case: %d; given a capital: %d (%.2f in 1000)" % (wrong + left, wrong, 1000.0 * wrong / max(1, wrong + left)))
        print("  most often wrong: %s" % " ".join("%s:%d" % kv for kv in worst.most_common(20)))
        lost = collections.Counter({w: row[1] for w, row in judged.items() if row[1]})
        print("  most often left without: %s" % " ".join("%s:%d" % kv for kv in lost.most_common(20)))
        return

    # Beside every other language: its own sentences, counted the same way.
    shares = {w: counts[w][1] / sum(counts[w]) for w in words}
    lacking = sorted(w for w in words if zipf[w] is None)
    if lacking:
        sys.exit("capitals: no frequency in the word lists for %s" % " ".join(lacking[:20]))
    gone, judged, theirs, sources = {}, {}, {}, []
    counted_by = set(users)
    for lang in sorted(BESIDE):
        found = exports(lang)
        other = frequencies([os.path.join(DICT, "%s.tsv" % lang)])
        theirs[lang] = count_beside(lang, found, words)
        counted, _, _, _, who, read = theirs[lang]
        judged[lang], gone[lang] = beside(shares, zipf, other, counted)
        counted_by |= who
        sources += [(lang, iso, export_date(p), read[p], sha256(p)) for iso, p in zip(BESIDE[lang], found)]
    data = encode(words, counts, export_date(path), sha256(path), total, lists)
    data_beside = encode_beside(gone, sources, lists)

    if word is not None:
        low, capital, other = counts.get(word, (0, 0, 0))
        print("%s in English: %d times in the middle of a sentence, with a capital %d (%.1f%%); Zipf %s; kept: %s" % (
            word, low + capital + other, capital, 100.0 * capital / max(1, low + capital + other), zipf.get(word), "yes" if word in words else "no"))
        for lang in sorted(BESIDE):
            if word in judged[lang]:
                pooled, n, cap = judged[lang][word]
                print("  beside %-3s its sentences write it %d times, with a capital %d; over both languages %.1f%% with a capital: %s" % (
                    lang, n, cap, 100.0 * pooled, "no name there" if word in gone[lang] else "a name there too"))
        return

    if "--beside" in args:
        names, taken = bundled()
        english = {w: row[0] for w, row in judge(names, held).items() if row[0]}
        every = sum(english.values())
        print("the bundled list (%d names) beside each language. On the language's own held-out sentences (every %dth):" % (len(names), HOLD_OUT))
        print("the capitals the names left would give rightly, and wrongly (to a spelling written there in lower case), by")
        print("the bundled file, by the rule before it (every spelling the other list has taken out) and with nothing")
        print("taken out. And of the %d capitals the names give on English's held-out sentences, the share still given" % every)
        print("beside the language, by the bundled file and by the rule before it.")
        print("%-4s %9s | %5s %5s | %-13s %-13s %-13s | %s" % ("", "sentences", "has", "out", "bundled", "before", "nothing out", "English's capitals kept"))
        sums = [0, 0, 0, 0, 0, 0, 0, 0.0, 0.0]
        for lang in sorted(BESIDE):
            _, theirs_held, _, held_lower, _, read = theirs[lang]
            other = frequencies([os.path.join(DICT, "%s.tsv" % lang)])
            before = {w for w in names if w in other}
            # A language the bundled file has no line for is left to the rule before, as the decoder leaves it.
            out = taken.get(lang, before)
            cells = []
            for i, dropped in enumerate((out, before, set())):
                right = sum(c[1] for w, c in theirs_held.items() if w in names and w not in dropped)
                wrong = sum(c[0] for w, c in theirs_held.items() if w in names and w not in dropped)
                sums[2 * i] += right; sums[2 * i + 1] += wrong
                cells.append("%d / %d" % (right, wrong))
            kept_here = [100.0 * sum(n for w, n in english.items() if w not in dropped and not (w.endswith("'s") and w[:-2] in dropped)) / max(1, every) for dropped in (out, before)]
            sums[6] += held_lower; sums[7] += kept_here[0]; sums[8] += kept_here[1]
            print("%-4s %9d | %5d %5s | %-13s %-13s %-13s | %5.1f%% %5.1f%%" % (
                lang, sum(read.values()), len(judged[lang]), len(taken[lang]) if lang in taken else "none", cells[0], cells[1], cells[2], kept_here[0], kept_here[1]))
        print("%-4s %9s | %5s %5s | %-13s %-13s %-13s | %5.1f%% %5.1f%%" % (
            "all", "", "", sum(len(v) for v in taken.values()), "%d / %d" % (sums[0], sums[1]), "%d / %d" % (sums[2], sums[3]), "%d / %d" % (sums[4], sums[5]), sums[7] / len(BESIDE), sums[8] / len(BESIDE)))
        print("words written in lower case in the middle of those held-out sentences: %s; a capital wrongly given to %.3f in 1000 of them (before: %.3f, nothing out: %.3f)" % (
            format(sums[6], ","), 1000.0 * sums[1] / max(1, sums[6]), 1000.0 * sums[3] / max(1, sums[6]), 1000.0 * sums[5] / max(1, sums[6])))
        return

    if "--check" in args:
        same = os.path.exists(OUT) and open(OUT, "rb").read() == data
        same_beside = os.path.exists(OUT_BESIDE) and open(OUT_BESIDE, "rb").read() == data_beside
        print("en_capitals.txt %s" % ("same" if same else "DIFFERENT"))
        print("en_capitals_beside.tsv %s" % ("same" if same_beside else "DIFFERENT"))
        sys.exit(0 if same and same_beside else 1)

    with open(OUT, "wb") as f:
        f.write(data)
    with open(OUT_BESIDE, "wb") as f:
        f.write(data_beside)
    named = credited()
    unnamed = sorted(counted_by - named)
    print("en_capitals.txt: %d words, %d bytes, from %d sentences (%d counted); vocabulary: %s (%d words)" % (
        len(words), len(data), total, total - total // HOLD_OUT, " ".join(os.path.basename(p) for p in lists), len(vocab)))
    print("en_capitals_beside.tsv: %d languages, %d bytes; names taken out beside each: %s" % (
        len(gone), len(data_beside), " ".join("%s:%d" % (lang, len(gone[lang])) for lang in sorted(gone))))
    print("contributors counted: %d; not named in assets/ngram/CREDITS.txt: %d %s" % (len(counted_by), len(unnamed), " ".join(unnamed[:20])))


if __name__ == "__main__":
    main(sys.argv[1:])
