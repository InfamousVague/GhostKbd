#!/usr/bin/env python3
"""Ghost Keyboard's next-word starter tables: android/app/src/main/assets/ngram/<language>.tsv, which word tends to
follow which, so the row above the keys has three likely next words on the first day (NextWordModel.kt reads them).

    python3 tool/next_words.py              # every language that has a word list: downloads, counts, gates, writes
    python3 tool/next_words.py en de        # only these (the others' files are left as they are)
    python3 tool/next_words.py --selftest   # the tokenizer against tool/next_words_fixtures.tsv; downloads nothing
    python3 tool/next_words.py --check      # counts again and fails if a bundled file would change

Standard library only. Run it after tool/dictionaries.py: the vocabulary is read from assets/dict/<language>.tsv, so
a table never holds a word autocorrect does not know.

Where the counts come from
--------------------------
Tatoeba (https://tatoeba.org), a collection of sentences and translations written by its contributors, released
under CC BY 2.0 FR (https://creativecommons.org/licenses/by/2.0/fr/). The per-language exports are read:

    https://downloads.tatoeba.org/exports/per_language/<iso3>/<iso3>_sentences_detailed.tsv.bz2

into build/ngram-src/ (ignored by git). Each file's export date, size, SHA-256 and sentence count are written to
tool/next_words_sources.tsv when the tables are made, and the attribution to assets/ngram/NOTICE.txt and
assets/ngram/CREDITS.txt (the contributors whose sentences were counted). The attribution line the app shows:

    Counts derived from Tatoeba (CC BY 2.0 FR); vocabulary limited to the wordfreq lists (CC BY-SA 4.0).

None of Tatoeba's sentences is in the app: only how often one listed word followed another.

What was not used, and why
--------------------------
- Peter Norvig's count_2w.txt (norvig.com/ngrams): the page's MIT licence covers its code. The counts are derived
  from the Google Web Trillion Word Corpus, which the Linguistic Data Consortium distributes under its own
  agreement; no licence is stated for the derived file, so it is not bundled.
- wordfreq has no word-pair data.

Words that are never offered
----------------------------
The "List of Dirty, Naughty, Obscene, and Otherwise Bad Words" (LDNOOBW, CC BY 4.0,
https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words) is used as a filter, for the
languages it has: a word on it is neither a context nor a follower in a starter table. The list is not in the app.

How a table is made (the numbers are constants below)
-----------------------------------------------------
1. Sentences are split into words by the one rule the keyboard uses too (walk(); the fixture file keeps the two the
   same): a word is a run of letters, their marks and the language's joiners; . ! ? and a new line start a sentence;
   commas, quotes and brackets are looked through; a digit, an emoji or a symbol ends the context.
2. Only words in the language's word list count (and elisions such as c'est whose parts are both listed); any other
   word ends the context, so nothing is ever predicted from or to a word the keyboard does not know.
3. Tatoeba reads like a phrasebook and loves a few names (Tom, Mary, Boston). Each word's counts are scaled by
   min(1, 8 x its share in wordfreq / its share in Tatoeba), which removes them without a list per language. A word
   scaled below a quarter is never a context.
4. Each word is shown in the casing it most often has in the middle of a sentence (I, Monday, German nouns).
5. Every 20th sentence is held out. A table is written only if it has at least 5,000 pairs and its top three are
   right at least 8 points more often than the language's three commonest words would be, on the held-out sentences.
   A language that fails ships no table: it predicts from the person's own typing and word frequency only.
"""
import bz2
import collections
import hashlib
import multiprocessing
import os
import re
import sys
import types
import unicodedata
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
DICT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "dict")
OUT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "ngram")
SRC = os.path.join(ROOT, "build", "ngram-src")
FIXTURES = os.path.join(HERE, "next_words_fixtures.tsv")
SOURCES = os.path.join(HERE, "next_words_sources.tsv")

EXPORT = "https://downloads.tatoeba.org/exports/per_language/{0}/{0}_sentences_detailed.tsv.bz2"
BAD_WORDS = "https://raw.githubusercontent.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master/{0}"

#: Each word list's Tatoeba languages (ISO 639-3). Serbo-Croatian pools three; `sr` is made from `sh`'s table.
ISO3 = {
    "en": ["eng"], "es": ["spa"], "fr": ["fra"], "de": ["deu"], "it": ["ita"], "pt": ["por"], "nl": ["nld"],
    "sv": ["swe"], "nb": ["nob"], "da": ["dan"], "fi": ["fin"], "is": ["isl"], "pl": ["pol"], "cs": ["ces"],
    "sk": ["slk"], "sl": ["slv"], "hu": ["hun"], "ro": ["ron"], "lt": ["lit"], "lv": ["lvs"], "tr": ["tur"],
    "ca": ["cat"], "id": ["ind"], "ms": ["zsm", "zlm"], "fil": ["tgl"], "vi": ["vie"], "ru": ["rus"], "uk": ["ukr"],
    "bg": ["bul"], "mk": ["mkd"], "el": ["ell"], "he": ["heb"], "ar": ["ara"], "fa": ["pes"], "ur": ["urd"],
    "sh": ["hrv", "bos", "srp"],
}

#: The filter list's name for a language, where it has one.
BAD = {"en": "en", "es": "es", "fr": "fr", "de": "de", "it": "it", "pt": "pt", "nl": "nl", "sv": "sv", "nb": "no",
       "da": "da", "fi": "fi", "pl": "pl", "cs": "cs", "hu": "hu", "tr": "tr", "ru": "ru", "ar": "ar", "fa": "fa",
       "fil": "fil"}

#: The characters besides letters that may sit inside a word (Languages.kt's joiners; an apostrophe on Latin boards).
JOINERS = {"uk": "'", "he": "'", "fa": "‌", "ur": "‌", "ca": "'·",
           "ru": "", "bg": "", "mk": "", "sr": "", "el": "", "ar": ""}

#: Punctuation that starts a new sentence besides the shared set: Greek's question mark.
ENDS = {"el": ";;"}

START = "^"
#: What starts a sentence everywhere (NextWordText.SENTENCE_ENDS): . ! ? … Urdu's full stop, Arabic's question mark.
SENTENCE_ENDS = ".!?…۔؟\n"
#: Looked through, as if it were a space (NextWordText.THROUGH): commas, semicolons, colons, quotes, brackets,
#: Spanish's opening ¿ and ¡, and dashes.
THROUGH = ",;:\u060c\u061b\"'\u201c\u201d\u201e\u2018\u2019\u201a\u00ab\u00bb\u2039\u203a()[]{}\u00bf\u00a1\u2014\u2013"
#: A word straight after one of these (or a digit) is part of something code-shaped (NextWordText.CODE).
CODE = "@#/._-\\:$`"
MAX_WORD = 24

#: Tuning, as the docstring says.
HOLD_OUT = 20
MIN_PAIRS = 5_000
MIN_GAIN = 8.0
SCALE = 8.0
DROP_CONTEXT = 0.25
FOLLOWERS = 48
MAX_BYTES = 12_000_000


def joiners_of(lang):
    return JOINERS.get(lang, "'")


def lower(word, lang):
    """Lower case as the layout's locale has it: Turkish I is ı and İ is i."""
    if lang == "tr":
        word = word.replace("I", "ı").replace("İ", "i")
    return word.lower()


class Classes(dict):
    """Each character's class for walk(): w a word character, e a sentence end, space looked through, x anything else."""

    def __init__(self, joiners, ends):
        super().__init__()
        self.joiners = joiners
        self.ends = SENTENCE_ENDS + ends

    def __missing__(self, code):
        c = chr(code)
        cat = unicodedata.category(c)
        if cat[0] in "LM" or c in self.joiners:
            cls = "w"
        elif c in self.ends:
            cls = "e"
        elif c in THROUGH or cat in ("Zs", "Zl", "Zp") or c in "\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f":
            cls = " "
        else:
            cls = "x"
        self[code] = cls
        return cls


TOKEN = re.compile("w+|e|x")


def trim(run):
    """A run of word characters without the joiners and marks at its edges: 'hello' is hello."""
    a, b = 0, len(run)
    while a < b and unicodedata.category(run[a])[0] != "L":
        a += 1
    while b > a and unicodedata.category(run[b - 1])[0] not in "LM":
        b -= 1
    return run[a:b]


def learnable(raw):
    """A word that may be learned or predicted from: not too long, and no capital after the first letter unless it
    is all capitals (camelCase and iOS are names for things, not words)."""
    if not 1 <= len(raw) <= MAX_WORD:
        return False
    if any(c.isupper() for c in raw[1:]):
        return all(c.isupper() for c in raw if unicodedata.category(c)[0] == "L")
    return True


def shown(raw, at_start):
    """How a typed word is kept for showing, or "" where its casing says nothing: all capitals only for words of up
    to five characters, and a capital first letter at a sentence start is the sentence's, not the word's."""
    letters = [c for c in raw if unicodedata.category(c)[0] == "L"]
    if len(letters) >= 2 and all(c.isupper() for c in letters):
        return raw if len(raw) <= 5 else ""
    if at_start and raw[:1].isupper():
        return ""
    return raw


def walk(text, classes, lang, full=False):
    """Every word of [text] with the context it was typed in: (prev2, prev, raw, start, end, ok), and the context
    at the text's end. prev is the word before in lower case, "^" at a sentence start, or None where there is no
    context (after a digit, a symbol, a word that is not learnable). [full] says the text is a window that was full,
    so its start is no sentence start and its first word may be cut."""
    cls = text.translate(classes)
    prev2, prev = None, (None if full else START)
    words = []
    for m in TOKEN.finditer(cls):
        kind = cls[m.start()]
        if kind == "e":
            prev2, prev = None, START
        elif kind == "x":
            prev2, prev = None, None
        else:
            start, end = m.span()
            raw = trim(text[start:end])
            if not raw:
                continue
            before = text[start - 1] if start else ""
            # Code-shaped: straight after a digit or one of CODE's characters, or with a digit straight after it (mp3).
            code = (bool(before) and (unicodedata.category(before) == "Nd" or before in CODE)) or \
                (end < len(text) and unicodedata.category(text[end]) == "Nd")
            ok = not code and not (full and start == 0) and learnable(raw)
            words.append((prev2, prev, raw, start, end, ok))
            if ok:
                prev2, prev = prev, lower(raw, lang)
            else:
                prev2, prev = None, None
    return words, (prev2, prev)


def place(text, classes, lang, full):
    """Where the cursor stands after [text]: (prev2, prev, typing), or None where nothing may be predicted."""
    words, state = walk(text, classes, lang, full)
    if words and words[-1][4] == len(text):
        prev2, prev, raw, _, _, ok = words[-1]
        return (prev2, prev, raw) if ok and prev is not None else None
    prev2, prev = state
    return None if prev is None else (prev2, prev, "")


def last_finished(text, classes, lang, full):
    """The last word of [text] that something follows, as it would be learned: (prev2, prev, word, shown), or None."""
    words, _ = walk(text, classes, lang, full)
    for prev2, prev, raw, _, end, ok in reversed(words):
        if end == len(text):
            continue
        if not ok or prev is None:
            return None
        return (prev2, prev, lower(raw, lang), shown(raw, prev == START))
    return None


def unescape(text):
    """A fixture's \\n and \\u200c as the characters they stand for."""
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), text).replace("\\n", "\n")


def selftest():
    """The fixture file's cases, which NextWordCheck.kt runs through the keyboard's own tokenizer too."""
    failures = 0
    n = 0
    with open(FIXTURES, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            lang, full, text, want_place, want_finished = line.split("\t")
            text, want_place, want_finished = (unescape(v) for v in (text, want_place, want_finished))
            classes = Classes(joiners_of(lang), ENDS.get(lang, ""))
            got = place(text, classes, lang, full == "1")
            got_place = "-" if got is None else "|".join("~" if p is None else p for p in got)
            got = last_finished(text, classes, lang, full == "1")
            got_finished = "-" if got is None else "|".join("~" if p is None else p for p in got)
            n += 2
            for what, g, w in (("place", got_place, want_place), ("finished", got_finished, want_finished)):
                if g != w:
                    failures += 1
                    print(f"FAIL  {what} of {text!r} ({lang}, full {full}): got {g}, wanted {w}")
    print(f"next_words selftest: {n - failures} of {n} passed")
    sys.exit(1 if failures else 0)


# --- the corpus --------------------------------------------------------------------------------------------------

#: Serbian Cyrillic to Latin, for pooling Serbian's sentences with Croatian's and Bosnian's.
SR_LATIN = {"а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "ђ": "đ", "е": "e", "ж": "ž", "з": "z", "и": "i",
            "ј": "j", "к": "k", "л": "l", "љ": "lj", "м": "m", "н": "n", "њ": "nj", "о": "o", "п": "p", "р": "r",
            "с": "s", "т": "t", "ћ": "ć", "у": "u", "ф": "f", "х": "h", "ц": "c", "ч": "č", "џ": "dž", "ш": "š"}
SR_LATIN.update({k.upper(): v.capitalize() for k, v in list(SR_LATIN.items())})
SR_TABLE = str.maketrans(SR_LATIN)
APOSTROPHES = str.maketrans({"’": "'", "ʼ": "'", "‘": "'", "`": "'"})
PERSIAN = str.maketrans({"ي": "ی", "ك": "ک"})


def normalise(text, lang):
    """A sentence as the word list spells things: straight apostrophes, Persian's own ی and ک, Serbian in Latin."""
    text = text.translate(APOSTROPHES)
    if lang == "fa":
        text = text.translate(PERSIAN)
    if lang == "sh":
        text = text.translate(SR_TABLE)
    return text


def fetch(url, path):
    """[url] into [path] unless it is there, with its Last-Modified date beside it. True when the file is there."""
    if os.path.exists(path) and os.path.exists(path + ".date"):
        return True
    os.makedirs(os.path.dirname(path), exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "next_words.py"}, method="HEAD" if os.path.exists(path) else "GET")
    try:
        with urllib.request.urlopen(request, timeout=600) as r:
            date = r.headers.get("Last-Modified", "")
            if request.get_method() == "GET":
                with open(path + ".part", "wb") as out:
                    while True:
                        block = r.read(1 << 20)
                        if not block:
                            break
                        out.write(block)
                os.replace(path + ".part", path)
    except Exception as e:  # a missing export is said, never passed over quietly
        print(f"  could not fetch {url}: {e}")
        return os.path.exists(path)
    with open(path + ".date", "w") as f:
        f.write(date)
    return True


def export_date(path):
    """An export's date as 2026-11-07, from the Last-Modified header kept beside it."""
    try:
        from email.utils import parsedate_to_datetime
        return parsedate_to_datetime(open(path + ".date").read().strip()).strftime("%Y-%m-%d")
    except Exception:
        return "unknown"


def sentences(lang):
    """Every sentence of [lang]'s exports as (text, username), and the exports read as (iso3, path, count)."""
    for iso in ISO3[lang]:
        path = os.path.join(SRC, f"{iso}_sentences_detailed.tsv.bz2")
        if not fetch(EXPORT.format(iso), path):
            print(f"  {lang}: no export for {iso}")
            continue
        with bz2.open(path, "rt", encoding="utf-8", errors="replace", newline="\n") as f:
            for line in f:
                cols = line.rstrip("\n").split("\t")
                if len(cols) >= 4:
                    yield cols[2], cols[3], iso


def vocabulary(lang):
    """[lang]'s word list: word to its share of all words (ten to the power of its Zipf frequency less nine)."""
    words = {}
    with open(os.path.join(DICT, f"{lang}.tsv"), encoding="utf-8") as f:
        for line in f:
            word, _, zipf = line.rstrip("\n").partition("\t")
            try:
                words[word] = max(words.get(word, 0.0), 10 ** (float(zipf) - 9))
            except ValueError:
                pass
    return words


def bad_words(lang):
    """The words never offered in [lang]: LDNOOBW's single words, where it has the language."""
    name = BAD.get(lang)
    if name is None:
        return set()
    path = os.path.join(SRC, "ldnoobw", name)
    if not fetch(BAD_WORDS.format(name), path):
        return set()
    with open(path, encoding="utf-8", errors="replace") as f:
        return {lower(w.strip(), lang) for w in f if w.strip() and " " not in w.strip()}


def count(lang):
    """Counts [lang]'s sentences. Returns everything build() needs, or None where there are no sentences."""
    vocab = vocabulary(lang)
    classes = Classes(joiners_of(lang), ENDS.get(lang, ""))
    pairs = collections.Counter()      # (context, word) in the counted sentences
    starts = collections.Counter()     # how many sentences started with a word, listed or not
    unigrams = collections.Counter()
    casing = collections.defaultdict(collections.Counter)
    held = []                          # (context, word) events of the held-out sentences
    users = set()
    read = collections.Counter()
    known = {}

    def listed(word):
        """In the list, or an elision whose parts are (c'est, l'ho, tom's): the parts, for the scale."""
        hit = known.get(word)
        if hit is None:
            if word in vocab:
                hit = (word,)
            elif "'" in word and all(p in vocab for p in word.split("'")):
                hit = tuple(word.split("'"))
            else:
                hit = ()
            known[word] = hit
        return hit

    n = 0
    for text, user, iso in sentences(lang):
        n += 1
        read[iso] += 1
        out = n % HOLD_OUT == 0
        words, _ = walk(normalise(text, lang), classes, lang)
        before_listed = False   # the word before this one is in the list
        counted = False
        for _, prev, raw, _, _, ok in words:
            if prev == START and not out:
                starts[START] += 1
            # walk()'s own context, except that a word the list lacks ends it as a symbol would.
            context = prev if prev == START or (prev is not None and before_listed) else None
            word = lower(raw, lang)
            before_listed = ok and bool(listed(word))
            if not before_listed:
                continue
            if out:
                if context is not None:
                    held.append((context, word))
                continue
            unigrams[word] += 1
            if context is None:
                continue
            counted = True
            pairs[(context, word)] += 1
            form = shown(raw, context == START)
            if form:
                casing[word][form] += 1
        if counted:
            users.add(user)
    if n == 0:
        return None
    return {"lang": lang, "vocab": vocab, "pairs": pairs, "starts": starts[START], "unigrams": unigrams, "casing": casing,
            "held": held, "users": users, "read": dict(read), "sentences": n, "listed": {w: p for w, p in known.items() if p}}


def build(c):
    """A counted language's table: (rows, report). rows are (context, [(shown, share)...]) with "^" first."""
    lang, vocab, unigrams, listed = c["lang"], c["vocab"], c["unigrams"], c["listed"]
    total = sum(unigrams.values()) or 1
    bad = bad_words(lang)

    def scale_one(word):
        mine = unigrams.get(word, 0) / total
        return 1.0 if mine <= 0 else min(1.0, SCALE * vocab.get(word, 0.0) / mine)

    scales = {}

    def scale(word):
        s = scales.get(word)
        if s is None:
            parts = listed.get(word, (word,))
            s = scale_one(word) if parts == (word,) else min(scale_one(p) for p in parts)
            scales[word] = s
        return s

    def seen(context):
        """How often [context] stood before anything at all, a sentence's end and unlisted words included."""
        return (c["starts"] if context == START else unigrams[context]) or 1

    minimum = 3 if c["sentences"] >= 300_000 else 2
    # Fewer than first planned (60,000 and 40,000), to keep every table together under MAX_BYTES.
    cap = 100_000 if lang == "en" else 50_000 if c["sentences"] >= 400_000 else 35_000
    kept = []
    for (context, word), n in c["pairs"].items():
        if n < minimum or word in bad or context in bad or word == context:
            continue
        if context != START and scale(context) < DROP_CONTEXT:
            continue
        weight = n * scale(word)
        if weight * 10_000 / seen(context) < 0.5:
            continue
        kept.append((weight, context, word))
    kept.sort(key=lambda k: (-k[0], k[1], k[2]))
    by_context = collections.defaultdict(list)
    pairs = 0
    for weight, context, word in kept:
        if pairs >= cap:
            break
        row = by_context[context]
        if len(row) >= FOLLOWERS:
            continue
        row.append((word, weight))
        pairs += 1

    def display(word):
        forms = c["casing"].get(word)
        return max(forms.items(), key=lambda kv: (kv[1], kv[0] == word))[0] if forms else word

    rows = []
    for context in sorted(by_context, key=lambda k: (k != START, k)):
        rows.append((context, [(display(w), max(1, min(9999, round(weight * 10_000 / seen(context))))) for w, weight in by_context[context]]))

    # The gate: on the held-out sentences, how often the word typed was among the table's first three for its context,
    # against always offering the language's three commonest words.
    top = {context: [lower(s, lang) for s, _ in followers[:3]] for context, followers in rows}
    common = [w for w, _ in unigrams.most_common(3)]
    hit1 = hit3 = base = 0
    for context, word in c["held"]:
        guess = top.get(context, ())
        hit1 += guess[:1] == [word]
        hit3 += word in guess
        base += word in common
    events = len(c["held"]) or 1
    most_scaled = sorted((w for w in unigrams if unigrams[w] >= 20), key=scale)[:30]
    report = {"lang": lang, "sentences": c["sentences"], "pairs": pairs, "contexts": len(rows), "events": len(c["held"]),
              "top1": 100.0 * hit1 / events, "top3": 100.0 * hit3 / events, "baseline": 100.0 * base / events,
              "scaled": most_scaled, "read": c["read"]}
    report["passed"] = pairs >= MIN_PAIRS and report["top3"] - report["baseline"] >= MIN_GAIN
    return rows, report


def encode(lang, rows, dates):
    head = f"#ghost-ngram\t1\t{lang}\tTatoeba export {dates}, CC BY 2.0 FR\n"
    return (head + "".join(context + "\t" + " ".join(f"{s}:{n}" for s, n in followers) + "\n" for context, followers in rows)).encode("utf-8")


def serbian(rows):
    """`sh`'s table in Cyrillic, for `sr`: every word through tool/dictionaries.py's letters, kept where sr.tsv has it."""
    stub = types.ModuleType("wordfreq")
    stub.get_frequency_dict = stub.iter_wordlist = None
    sys.modules.setdefault("wordfreq", stub)
    sys.path.insert(0, HERE)
    from dictionaries import serbian_cyrillic
    vocab = vocabulary("sr")

    def turn(word):
        cyr = serbian_cyrillic(word.lower())
        if cyr is None or cyr not in vocab:
            return None
        return cyr.capitalize() if word[:1].isupper() and not word.isupper() else cyr.upper() if word.isupper() and len(word) > 1 else cyr

    out = []
    for context, followers in rows:
        key = context if context == START else turn(context)
        if key is None:
            continue
        mine = [(t, n) for t, n in ((turn(s), n) for s, n in followers) if t is not None]
        if mine:
            out.append((key, mine))
    out.sort(key=lambda r: (r[0] != START, r[0]))
    return out


def make(lang):
    """One language, counted and built in a worker: (lang, rows or None, report or None, users, source rows)."""
    c = count(lang)
    if c is None:
        return lang, None, None, set(), []
    rows, report = build(c)
    sources = []
    for iso in ISO3[lang]:
        path = os.path.join(SRC, f"{iso}_sentences_detailed.tsv.bz2")
        if os.path.exists(path):
            digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
            sources.append((iso, lang, export_date(path), os.path.getsize(path), c["read"].get(iso, 0), digest))
    return lang, rows, report, c["users"], sources


NOTICE = """Ghost Keyboard's next-word starter tables
=========================================

The .tsv files in this folder say which word tends to follow which, for the three words the keyboard offers after a
space. They were made by tool/next_words.py in Ghost Keyboard's repository.

Counts derived from Tatoeba (CC BY 2.0 FR); vocabulary limited to the wordfreq lists (CC BY-SA 4.0).

Tatoeba
-------
The counts are derived from the sentences of Tatoeba (https://tatoeba.org), written by its contributors and released
under the Creative Commons Attribution 2.0 France licence (https://creativecommons.org/licenses/by/2.0/fr/). The
contributors whose sentences were counted are named in CREDITS.txt, beside this file.

What was changed: none of the sentences is here. Each file holds, for a word (or "^", the start of a sentence), the
words that most often came next and each one's share of what followed, in ten-thousandths. Words that are not in the
language's word list were left out, the counts of words Tatoeba uses far more than people usually do (its stock
names) were scaled down, and words on the LDNOOBW list were removed.

The exports read (language, export date, sentences, pairs kept):
{exports}

Languages with a word list and no file here have no starter table: {missing}. There the keyboard
suggests from what the person types and from how common words are.

wordfreq
--------
A word is in a table only if it is in the keyboard's word list for the language (assets/dict, derived from wordfreq
3.1.1 by Robyn Speer, CC BY-SA 4.0; see assets/dict/NOTICE.txt), and wordfreq's frequencies were used to scale the
counts. sr.tsv is sh.tsv transliterated to Cyrillic.

LDNOOBW
-------
The "List of Dirty, Naughty, Obscene, and Otherwise Bad Words"
(https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words, CC BY 4.0) was used as a filter.
None of it is in these files.
"""


def main(argv):
    if "--selftest" in argv:
        selftest()
    check = "--check" in argv
    asked = [a for a in argv if not a.startswith("--")]
    unknown = [a for a in asked if a not in ISO3 and a != "sr"]
    if unknown:
        sys.exit(f"next_words: no recipe for {' '.join(unknown)}")
    langs = [a for a in (asked or list(ISO3)) if a != "sr"]
    if "sr" in asked and "sh" not in langs:
        langs.append("sh")
    # The downloads first, one at a time, so the workers only read.
    for lang in langs:
        for iso in ISO3[lang]:
            fetch(EXPORT.format(iso), os.path.join(SRC, f"{iso}_sentences_detailed.tsv.bz2"))
        if lang in BAD:
            fetch(BAD_WORDS.format(BAD[lang]), os.path.join(SRC, "ldnoobw", BAD[lang]))
    with multiprocessing.Pool(min(8, os.cpu_count() or 2)) as pool:
        made = pool.map(make, sorted(langs, key=lambda l: l != "en"), chunksize=1)
    os.makedirs(OUT, exist_ok=True)
    failed = False
    users = set()
    sources = []
    lines = []
    print(f"{'':4} {'sentences':>9} {'pairs':>7} {'top 1':>6} {'top 3':>6} {'common 3':>8}  table")
    written = {}
    for lang, rows, report, who, src in sorted(made):
        if report is None:
            print(f"{lang:4} no sentences: no table")
            continue
        verdict = "written" if report["passed"] else "NOT WRITTEN (gate)"
        print(f"{lang:4} {report['sentences']:9} {report['pairs']:7} {report['top1']:6.1f} {report['top3']:6.1f} {report['baseline']:8.1f}  {verdict}")
        print(f"     scaled down most: {' '.join(report['scaled'])}")
        if not report["passed"]:
            continue
        dates = ", ".join(sorted({d for _, _, d, _, _, _ in src}))
        written[lang] = (encode(lang, rows, dates), report, dates)
        users |= who
        sources += src
        if lang == "sh":
            cyr = serbian(rows)
            pairs = sum(len(f) for _, f in cyr)
            if pairs >= MIN_PAIRS:
                written["sr"] = (encode("sr", cyr, dates), dict(report, pairs=pairs, lang="sr"), dates)
                print(f"sr   {'':9} {pairs:7} {'':6} {'':6} {'':8}  written (sh's table in Cyrillic)")
            else:
                print(f"sr   {pairs} pairs after transliteration: NOT WRITTEN")
    for lang, (data, report, dates) in sorted(written.items()):
        path = os.path.join(OUT, f"{lang}.tsv")
        if check:
            same = os.path.exists(path) and open(path, "rb").read() == data
            failed |= not same
            print(f"{lang:4} {'same' if same else 'DIFFERENT'}")
        else:
            with open(path, "wb") as f:
                f.write(data)
        lines.append(f"  {lang:4} {dates}  {report['sentences']:>9,} sentences  {report['pairs']:>7,} pairs")
    if check:
        sys.exit(1 if failed else 0)
    if not asked:
        # A whole run owns the folder: a table that no longer passes is removed, and the notices are rewritten.
        for name in os.listdir(OUT):
            if name.endswith(".tsv") and name[:-4] not in written:
                os.remove(os.path.join(OUT, name))
        every = sorted(n[:-4] for n in os.listdir(DICT) if n.endswith(".tsv") and "_" not in n)
        missing = ", ".join(l for l in every if l not in written) or "none"
        with open(os.path.join(OUT, "NOTICE.txt"), "w", encoding="utf-8") as f:
            f.write(NOTICE.format(exports="\n".join(lines), missing=missing))
        with open(os.path.join(OUT, "CREDITS.txt"), "w", encoding="utf-8") as f:
            f.write("Tatoeba contributors whose sentences were counted for Ghost Keyboard's next-word starter tables\n"
                    "(https://tatoeba.org, CC BY 2.0 FR). A name is a Tatoeba username; \\N is a sentence with no owner.\n\n")
            f.write(", ".join(sorted(users, key=str.lower)) + "\n")
        with open(SOURCES, "w", encoding="utf-8") as f:
            f.write("# iso3\tlist\texport date\tbytes\tsentences\tsha256   (written by tool/next_words.py)\n")
            for row in sorted(sources):
                f.write("\t".join(str(v) for v in row) + "\n")
    size = sum(os.path.getsize(os.path.join(OUT, n)) for n in os.listdir(OUT))
    print(f"{len(written)} tables, assets/ngram is {size:,} bytes")
    if size > MAX_BYTES:
        sys.exit(f"next_words: assets/ngram is over {MAX_BYTES:,} bytes")


if __name__ == "__main__":
    main(sys.argv[1:])
