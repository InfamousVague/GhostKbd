#!/usr/bin/env python3
"""English's wider word list: android/app/src/main/assets/dict/en_wide.tsv, the words of English as it is written
now that en.tsv's 40,000 do not reach (Matt, 2026-11-12: "We should have a robust word list either way covering at
least all the words in the english language", and then: "We don't need to track obscure archeic words just modern
used words if the extra words will not help our accuracy we can leave them out").

    python3 tool/wide_words.py                  # reads the sources, says what it found, writes the list and the notice
    python3 tool/wide_words.py --check          # writes nothing: fails if the bundled list or the notice would change
    python3 tool/wide_words.py --dry            # says what it would write, and writes nothing
    python3 tool/wide_words.py --word yurt      # what each source says of a word, and what the list makes of it
    python3 tool/wide_words.py --samples 40     # more of each kind shown
    python3 tool/wide_words.py --size 70 --written 2.4 --out build/wide/try.tsv     # another cut, written elsewhere

Standard library only; downloads nothing and runs nothing it reads. Autocorrect.widen reads the list wherever English
is loaded; en.tsv is never rewritten, and its frequencies win. docs/wide-words.md says what the list does for the
keyboard, what each kind of word was measured to be worth, and what it costs.

The sources
-----------
Each is found by its path (a default under build/word-src, and an option to say where) and each is optional: one that
is not there is said so and left out, and the list is what the others give. The bundled list is made from all of
them, so the tool will not write that one with a source missing unless --without names it.

- en.tsv, the first list: wordfreq's commonest 40,000. Its words are not repeated here; the list only says which of
  its rarer words a dictionary vouches for.
- wordfreq 3.1.1's whole English list as plain text (--wordfreq, build/word-src/wordfreq-en-large.tsv: a word, a tab,
  its Zipf frequency; CC BY-SA 4.0). What is written, and how often: the evidence that a word is in use now. It is
  not evidence that a string is a word: misspellings (definately, wierd), scraps and other languages' words are in it.
- The English Speller Database, formerly SCOWL (--database, build/word-src/esdb-src/wordlist-rel-2026.02.25; Kevin
  Atkinson and others, MIT-like, see its Copyright file). The lists spelling checkers are made from: the evidence that
  a string is a word. Read as text by this tool (data/scowl-pre.txt and the files its own build merges into it or
  adjusts it by); nothing in that folder is run. What this reader does and does not do of its build is under
  "Reading the database" below.
- en_more.tsv, the words Tatoeba's sentences use (--more PATH: the list the closed versus-words track made with its
  tool/more_words.py, counted from sentences, held-out ones never among them). Not among the defaults: it adds 422
  words, nearly all the names Tatoeba's own stories are told about, and by measurement nothing on the sets the
  keyboard is scored on.
- The LDNOOBW list (--bad, build/ngram-src/ldnoobw/en): words on it are left out, as tool/next_words.py and
  tool/more_words.py leave them out.

Which words
-----------
A word belongs where it is in use now and is a word. So the list starts where the two kinds of evidence meet, and
each edge was settled by measurement (docs/wide-words.md has the numbers; the defaults below are what they chose).
The keyboard goes by the marks: a `d` word typed as meant stands, an `l` or a `w` word is as doubtful as any rare
word was, and a `w` word is known and never offered as what a slip was meant to be.

1. A word the database vouches for and wordfreq shows written (any frequency it lists, from Zipf 1.01 up):
   - `d` where the database has it at its default spelling-dictionary size or under (--sure, 60), in a standard
     spelling of American, British (-ise and -ize), Canadian or Australian English or a common variant of one
     (--sure-level): the size its maker has checked for strings that are likelier a commoner word misspelled;
   - `l` where only its larger sizes have it (to --size, 80: "a valid word in current usage"), or as a less common
     variant (to --level). Never size 85, whose words come from a source with a notice of its own.
   An `n` beside either says the database writes it with a capital and never without: a name (Berber, Vladimir).
   Left out: abbreviations, prefixes and suffixes, Roman numerals, words written all in capitals (acronyms), forms
   it marks archaic, words it marks offensive or as the coarsest vulgarities, and anything that is not 3 to 24 plain
   letters with at most one apostrophe inside. A possessive (yurt's) is not listed either: the keyboard knows one by
   its word (Autocorrect.POSSESSIVE).
2. `w`: a word only writing vouches for (wordfreq from --written up, or en_more.tsv): names, places and newer words
   (ludovic, fenway, chromecast), which come mixed with misspellings and scraps. Kept where it is four letters or
   more with a vowel among them, and no commoner word of the lists is one slip away from it (a letter changed, left
   out, added or two swapped, to a word --near commoner; or two slips to a word --far commoner, from six letters):
   awsome, stuf and tommorow go out by that, and so does a name that sits beside a common word, which is then where
   it was before, a word no list has.
3. Words only the database has (--unwritten, off): no writing wordfreq counted shows them once in a hundred
   million words, and by measurement they mend nothing. With --forms the inflected forms of a listed word are taken
   all the same (its plural, its verb forms), and with --unwritten N every word to size N.

Every word has a Zipf frequency: wordfreq's where it has one; en_more.tsv's where only that has the word; and for a
word only the database has, an estimate from the words that have both (the least tenth of them at its size, and never
more than the least wordfreq lists).

The file: a word, a tab, its Zipf to two decimals, a tab, its marks; in the order of the words, so the keyboard
merges it with its first list in one pass. A line for a word en.tsv has (those under Zipf --common, 3.2, the keyboard's
own line between a common word and a rare one) carries en.tsv's frequency and says only what vouches for the word.

Reading the database
--------------------
data/scowl-pre.txt is read by the grammar of its README ("File Format"): each line's sizes with their regions,
categories and tags; its spelling codes and variant levels; its lemma with its rank marks, part of speech, class and
usage note; and its derived forms, each with its own codes where it has them. A form stands at the least size any
line gives it, and at the best variant level any spelling code gives it (a derived form's level is the worse of its
own and its lemma's, as the database's own view has it).
Of what its build (combine.py) then does:
- data/basic, extra, signature, coca and coca_llm are merged in as more lines of the same grammar;
- data/exclude's entries are raised to the sizes it gives (calender, irregardless);
- data/variants, fixes, compounds and compounds-auto are read for the variant levels they give a lemma, the lemmas
  they remove and the overrides they add ("+ 70: +: thru"). Their changes to derived forms, to parts of speech and
  to which group a word belongs are not followed: the list does not go by those.
- its "simplify" and "combine POS" passes change tags and groups, not which words there are at which size.
The database's own lists could not be made here to compare (nothing of it is run), so this is as near as reading
gets: --word shows every line a word was found on.
"""
import argparse
import collections
import itertools
import math
import os
import pickle
import random
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
DICT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "dict")
OUT = os.path.join(DICT, "en_wide.tsv")
NOTICE = os.path.join(DICT, "NOTICE.txt")
DICTIONARIES = os.path.join(HERE, "dictionaries.py")
SRC = os.path.join(ROOT, "build", "word-src")
WORDFREQ = os.path.join(SRC, "wordfreq-en-large.tsv")
DATABASE = os.path.join(SRC, "esdb-src", "wordlist-rel-2026.02.25")
BAD = os.path.join(ROOT, "build", "ngram-src", "ldnoobw", "en")
PAIRS = os.path.join(ROOT, "android", "app", "src", "main", "assets", "ngram", "en.tsv")
HELDOUT = os.path.join(ROOT, "build", "typing-bench", "en-heldout.txt")
CACHE = os.path.join(ROOT, "build", "wide")

#: Held-out lines the share covered is counted on: the first 90,000 of build/typing-bench/en-heldout.txt, which
#: tool/versus never types (its sentences are from line 90,001 on) and no list was shaped by.
COVERAGE_LINES = 90_000

#: A word of the list: 3 to 24 plain letters, with at most one apostrophe inside.
TYPABLE = re.compile(r"[a-z]+(?:'[a-z]+)?\Z")
LEAST_LETTERS = 3
MOST_LETTERS = 24
#: The most a word of this list counts for: a hundredth under the least en.tsv has (2.72). wordfreq left it out of
#: its commonest 40,000, so it is rarer than any of those.
CAP = 2.71

# --- the English Speller Database, read as text -----------------------------------------------------------------------

#: A variant level's mark, and its number (README, "VARIANT-LEVEL"): 0 no variant, 1 include, 2 equal, 3 disagreement,
#: 4 common, 5 variant, 6 acceptable, 7 uncommon, 8 archaic, 9 invalid.
LEVELS = {"": 0, ".": 1, "=": 2, "?": 3, "v": 4, "~": 5, "V": 6, "-": 7, "@": 8, "x": 9}
SPELLING = re.compile(r"([_ABZCD]?)([.=?v~V@x-]?)\Z")
REGIONS = ("US", "GB", "CA", "AU")
#: The marks after a word (README, "ANNOTATION"): _ none, * usage dependent, - uncommon, @ archaic, ~ inapplicable,
#: ! infrequent. Before a lemma, the group's.
RANKS = "_*@~!-"
SIZES = re.compile(r"(\d+[^:#]*):\s*(.*)\Z")
LEMMA = re.compile(
    r"(?P<lemma>[^:<>{}#()]+?)\s*"
    r"(?:<(?P<pos>[^/>]*)(?:/(?P<cls>[^>]*))?>\s*)?"
    r"(?:\{(?P<defn>[^}]+)\}\s*)?"
    r"(?:\((?P<usage>[^:#|)]+)\)\s*)?"
    r"(?::\s*(?P<words>.*))?\Z")

#: One place a spelling stands in the database: the size the line gives it, the best variant level any spelling
#: code gives it there, its rank marks (its group's and its own), the group's part of speech, class and usage note,
#: the line's category, region and tags, how it is written, its group (a number) and whether it is the group's lemma.
Place = collections.namedtuple("Place", "size level rank pos cls usage category region tags written group lemma")


def read_sizes(text):
    """A line's sizes, each with what follows it: [[size, category, region, [tags]]]."""
    out = []
    for tok in text.split():
        if tok[0].isdigit():
            out.append([int(tok), "", "", []])
        elif not out:
            raise ValueError("no size: " + text)
        elif tok in REGIONS:
            out[-1][2] = tok
        elif tok[0] == "[":
            out[-1][3].append(tok)
        else:
            out[-1][1] = tok
    return out


def read_spellings(text, default=None):
    """Spelling codes with their variant levels ("A Cv DV", "_V", "V") as {code: level}; None where [text] is not
    that. A level with no code goes to each of [default] (a derived form's, under its lemma's codes), or to "_"."""
    out = {}
    for tok in text.split():
        m = SPELLING.match(tok)
        if not m:
            return None
        level = LEVELS[m.group(2)]
        if m.group(1):
            out[m.group(1)] = level
        else:
            for code in (default or "_"):
                out[code] = level
    return out


def read_word(text):
    """A word and its rank mark ("aahed-", "cutleries~"); (None, "") for the "-" that stands for a form not there."""
    text = text.strip().rstrip("†")
    if text == "-" or not text:
        return None, ""
    rank = ""
    if len(text) > 1 and text[-1] in RANKS:
        rank = text[-1]
        text = text[:-1]
    return text, ("" if rank == "_" else rank)


def split_forms(text):
    """A line's derived forms, parted at the commas that are not inside brackets."""
    out, depth, start = [], 0, 0
    for i, c in enumerate(text):
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "," and depth == 0:
            out.append(text[start:i])
            start = i + 1
    out.append(text[start:])
    return out


class Line(object):
    """One line of the database's grammar, parsed."""
    __slots__ = ("prefix", "sizes", "override", "codes", "rank", "lemma", "lemma_rank", "pos", "cls", "usage", "forms", "dots")

    @staticmethod
    def parse(line, adjusting=False):
        """[line] parsed, or None where it is a comment or blank. With [adjusting], a line of an adjust file: it may
        open with one of ? + - = ~ and need have no sizes."""
        hash_at = line.find("#")
        if hash_at >= 0:
            line = line[:hash_at]
        line = line.strip()
        if not line:
            return None
        self = Line()
        self.prefix = ""
        if adjusting and len(line) > 1 and line[0] in "?+-=~" and line[1] == " ":
            self.prefix = line[0]
            line = line[2:].strip()
        m = SIZES.match(line)
        self.sizes = []
        if m and (not adjusting or re.match(r"\d+(\s|:|$)", line)):
            self.sizes = read_sizes(m.group(1))
            line = m.group(2)
        elif not adjusting:
            raise ValueError("no size")
        self.dots = line.strip() == "..."
        self.override = False
        self.codes = {}
        head, colon, tail = line.partition(":")
        if colon and head.strip() == "+":
            self.override = True
            line = tail.strip()
        elif colon:
            h = re.sub(r"\{\s*\d+\s*\}", "", head).strip()
            if h and all(SPELLING.match(t) and t[0] in "_ABZCD" for t in h.split()):
                self.codes = read_spellings(h)
                line = tail.strip()
        m = LEMMA.match(line)
        if not m:
            raise ValueError("no lemma")
        text = m.group("lemma").strip()
        self.rank = ""
        if text == "-" or self.dots:
            self.lemma, self.lemma_rank = None, ""
        else:
            if len(text) > 1 and text[0] in "_@!-":
                self.rank = "" if text[0] == "_" else text[0]
                text = text[1:]
            self.lemma, self.lemma_rank = read_word(text)
        self.pos = (m.group("pos") or "").split("→")[-1]
        self.cls = m.group("cls") or ""
        self.usage = m.group("usage") or ""
        self.forms = []
        if m.group("words") and not self.dots:
            keys = list(self.codes) or None
            for part in split_forms(m.group("words")):
                part = part.strip()
                inside = re.match(r"\((.*)\)\Z", part)
                for alt in (inside.group(1).split("|") if inside else [part]):
                    alt = alt.strip()
                    codes = None
                    coded = re.match(r"([^:]*):\s*(.+)\Z", alt)
                    if coded and read_spellings(coded.group(1), keys) is not None:
                        codes = read_spellings(coded.group(1), keys)
                        alt = coded.group(2)
                    word, rank = read_word(alt)
                    if word is not None:
                        self.forms.append((word, rank, codes))
        return self


def best_level(lemma_codes, form_codes):
    """The best variant level any spelling code gives a form. A derived form's code is held against each of its
    lemma's, kept where they are the same code or either is "_", at the worse of the two levels (the database's own
    view, words_w_variant_info)."""
    lemma_codes = lemma_codes or {"_": 0}
    if form_codes is None:
        return min(lemma_codes.values())
    best = None
    for k1, l1 in lemma_codes.items():
        for k2, l2 in form_codes.items():
            if k1 == k2 or k1 == "_" or k2 == "_":
                level = max(l1, l2)
                if best is None or level < best:
                    best = level
    return best


class Database(object):
    """The database's text files read: every spelling as written, with each place it stands ([places])."""

    VERSION = 4

    def __init__(self):
        self.places = collections.defaultdict(set)
        self.lemmas = collections.defaultdict(set)  # a group's lemmas, as written
        self.by_lemma = collections.defaultdict(set)  # a lemma as written: the groups it heads
        self.members = collections.defaultdict(set)  # a group's spellings, lemmas and forms
        self.groups = 0
        self.lines = 0
        self.unread = []
        self.adjusted = collections.Counter()

    def read(self, path, tag=None):
        """A file in the database's own grammar: scowl-pre.txt, or one of the files merged into it."""
        group = []
        with open(path, encoding="utf-8") as f:
            for raw in itertools.chain(f, [""]):
                if not raw.strip():
                    if group:
                        self._add(group, tag)
                        group = []
                    continue
                if raw.startswith("#"):
                    continue
                try:
                    line = Line.parse(raw)
                except ValueError as e:
                    self.unread.append("%s: %s (%s)" % (os.path.basename(path), raw.strip(), e))
                    continue
                if line is not None:
                    self.lines += 1
                    group.append(line)

    def _add(self, group, tag):
        self.groups += 1
        gid = self.groups
        for line in group:
            if line.lemma is not None:
                self.lemmas[gid].add(line.lemma)
                self.by_lemma[line.lemma].add(gid)
        for line in group:
            # An override ("60: +: ya <pn>") puts its words at that size whatever their variant level.
            codes = {"_": 0} if line.override else line.codes
            words = []
            if line.lemma is not None:
                words.append((line.lemma, line.lemma_rank, None, True))
            words += [(w, r, c, False) for w, r, c in line.forms]
            for word, rank, form_codes, is_lemma in words:
                level = best_level(codes, None if line.override else form_codes)
                if level is None:
                    continue
                marks = "".join(sorted(set(line.rank + rank)))
                self.members[gid].add(word)
                for size, category, region, tags in line.sizes:
                    self.places[word].add(Place(size, level, marks, line.pos, line.cls, line.usage, category, region,
                                                " ".join(sorted(tags + ([tag] if tag else []))), word, gid, is_lemma))

    def adjust(self, path):
        """One of the build's adjust files, as far as this reader follows them (see the docstring): a lemma's new
        size (data/exclude), its new variant level, a lemma taken away, an override added."""
        group = []
        with open(path, encoding="utf-8") as f:
            for raw in itertools.chain(f, [""]):
                if not raw.strip():
                    if group:
                        self._adjust(group)
                        group = []
                    continue
                if raw.startswith("#"):
                    continue
                try:
                    line = Line.parse(raw, adjusting=True)
                except ValueError as e:
                    self.unread.append("%s: %s (%s)" % (os.path.basename(path), raw.strip(), e))
                    continue
                if line is not None:
                    group.append(line)

    def _same_pos(self, place_pos, pos):
        """Whether an entry's part of speech is the one an adjust line names (n_v is both n and v; none is any)."""
        if not pos or not place_pos:
            return True
        return bool(set(place_pos.split("_")) & set(pos.split("_"))) or place_pos in ("m", "a")

    def _adjust(self, group):
        named = [line for line in group if line.lemma is not None]
        # "= 70 [-]: ..." after its entries: none of them stands at a smaller size than that.
        for line in group:
            if line.dots and line.sizes and line.prefix in ("=", "+"):
                least = min(s[0] for s in line.sizes)
                for entry in named:
                    for gid in self.by_lemma.get(entry.lemma, ()):
                        self._raise(gid, entry.pos, least)
        for line in named:
            if line.prefix == "-" and not line.sizes:
                # A lemma taken out of its group.
                kept = set(p for p in self.places.get(line.lemma, ()) if not (p.lemma and self._same_pos(p.pos, line.pos)))
                if len(kept) != len(self.places.get(line.lemma, ())):
                    self.adjusted["lemmas removed"] += 1
                    self.places[line.lemma] = kept
            elif line.override and line.sizes and line.prefix == "+":
                gid = next(iter(self.by_lemma.get(line.lemma, ())), 0)
                for size, category, region, tags in line.sizes:
                    self.places[line.lemma].add(Place(size, 0, "", line.pos, line.cls, line.usage, category, region, " ".join(sorted(tags)), line.lemma, gid, True))
                self.adjusted["overrides added"] += 1
            elif line.codes and line.prefix in ("", "=", "?"):
                # A lemma's variant level said anew.
                level = min(line.codes.values())
                had = self.places.get(line.lemma)
                if not had:
                    continue
                changed = set()
                for p in had:
                    changed.add(p._replace(level=level) if p.lemma and self._same_pos(p.pos, line.pos) else p)
                if changed != had:
                    self.adjusted["variant levels changed"] += 1
                    self.places[line.lemma] = changed

    def _raise(self, gid, pos, least):
        for word in self.members.get(gid, ()):
            had = self.places[word]
            changed = set()
            for p in had:
                changed.add(p._replace(size=max(p.size, least), tags=(p.tags + " [-]").strip()) if p.group == gid and p.size < least and self._same_pos(p.pos, pos) else p)
            if changed != had:
                self.adjusted["sizes raised"] += 1
                self.places[word] = changed

    @staticmethod
    def load(root, cache=True):
        """The database under [root] read, or None where it is not there. Reading takes half a minute, so what was
        read is kept under build/wide for the next run, for as long as the files are the same."""
        pre = os.path.join(root, "data", "scowl-pre.txt")
        if not os.path.exists(pre):
            return None
        merged = [("basic", "[basic]"), ("extra", "[extra]"), ("signature", "[+]"), ("coca", "[coca]"), ("coca_llm", "[coca-llm]")]
        adjusts = ["compounds-auto", "compounds", "variants", "fixes", "exclude"]
        files = [pre] + [os.path.join(root, "data", n) for n, _ in merged] + [os.path.join(root, "data", n) for n in adjusts]
        stamp = (Database.VERSION, os.path.abspath(root), tuple((os.path.getsize(f), int(os.path.getmtime(f))) for f in files if os.path.exists(f)))
        kept = os.path.join(CACHE, "database.pickle")
        if cache and os.path.exists(kept):
            try:
                with open(kept, "rb") as f:
                    was, db = pickle.load(f)
                if was == stamp:
                    return db
            except Exception:
                pass
        db = Database()
        db.read(pre)
        for name, tag in merged:
            path = os.path.join(root, "data", name)
            if os.path.exists(path):
                db.read(path, tag)
        for name in adjusts:
            path = os.path.join(root, "data", name)
            if os.path.exists(path):
                db.adjust(path)
        db.places = dict(db.places)
        db.lemmas = dict(db.lemmas)
        db.by_lemma = dict(db.by_lemma)
        db.members = {}
        if cache:
            try:
                os.makedirs(CACHE, exist_ok=True)
                with open(kept, "wb") as f:
                    pickle.dump((stamp, db), f, pickle.HIGHEST_PROTOCOL)
            except Exception:
                pass
        return db


# --- the other sources ------------------------------------------------------------------------------------------------

def read_tsv(path):
    """A `word<TAB>zipf` file as {word: zipf}; None where it is not there."""
    if not path or not os.path.exists(path):
        return None
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            word, _, z = line.rstrip("\n").partition("\t")
            try:
                out[word] = float(z.split("\t")[0])
            except ValueError:
                pass
    return out


def read_set(path, first_column=False):
    if not path or not os.path.exists(path):
        return None
    out = set()
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            out.add((line.split("\t", 1)[0] if first_column else line).lower())
    return out


def read_pairs(path):
    """The starter table's one-word rows (assets/ngram/en.tsv): {word: {the word after it: its share of what
    followed}}, in lower case; None where the file is not there."""
    if not path or not os.path.exists(path):
        return None
    out = {}
    with open(path, encoding="utf-8") as f:
        next(f, None)
        for line in f:
            key, tab, row = line.rstrip("\n").partition("\t")
            if not tab or " " in key or key == "^":
                continue
            after = out.setdefault(key.lower(), {})
            for entry in row.split(" "):
                word, colon, share = entry.rpartition(":")
                if colon and share.isdigit():
                    after[word.lower()] = max(after.get(word.lower(), 0.0), int(share) / 10000.0)
    return out


def heldout(path, lines=COVERAGE_LINES):
    """The running words of the first [lines] held-out lines, as the typing bench reads a sentence's words
    (tool/typing_bench/Bench.kt, Sentences.words): {word: times}; None where the file is not there."""
    if not path or not os.path.exists(path):
        return None
    held = collections.Counter()
    with open(path, encoding="utf-8") as f:
        for line in itertools.islice(f, lines):
            for tok in line.split():
                a, b = 0, len(tok)
                while a < b and not tok[a].isalpha():
                    a += 1
                while b > a and not tok[b - 1].isalpha():
                    b -= 1
                tok = tok[a:b].replace("’", "'")
                if not tok or (len(tok) >= 2 and all(c.isupper() for c in tok)):
                    continue
                word = tok.lower()
                if all(c in "abcdefghijklmnopqrstuvwxyz'" for c in word):
                    held[word] += 1
    return held


# --- which words ------------------------------------------------------------------------------------------------------

LOWER = re.compile(r"[a-z]+(?:'[a-z]+)?\Z")
CAPITAL = re.compile(r"[A-Z][a-z]*(?:'[A-Za-z][a-z]*)?\Z")
ACRONYM = re.compile(r"[A-Z]{2,}(?:'s)?\Z")
MIXED = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?\Z")
LETTERS = "abcdefghijklmnopqrstuvwxyz"


def typable(word):
    return TYPABLE.match(word) is not None and LEAST_LETTERS <= len(word.replace("'", "")) and len(word) <= MOST_LETTERS


def slips(word):
    """Every string one slip from [word]: a letter left out, changed or added, or two next to each other swapped."""
    out = set()
    for i in range(len(word)):
        out.add(word[:i] + word[i + 1:])
        for c in LETTERS:
            out.add(word[:i] + c + word[i + 1:])
        if i + 1 < len(word):
            out.add(word[:i] + word[i + 1] + word[i] + word[i + 2:])
    for i in range(len(word) + 1):
        for c in LETTERS:
            out.add(word[:i] + c + word[i:])
    out.discard(word)
    return out


def cuts(word, most=2):
    """[word] with up to [most] letters left out: two words within [most] slips of each other share one of these."""
    out = {word}
    edge = {word}
    for _ in range(most):
        edge = {w[:i] + w[i + 1:] for w in edge for i in range(len(w))}
        out |= edge
    return out


def distance(a, b, most=2):
    """How many slips [a] is from [b] (a letter left out, changed, added, or two neighbours swapped), up to [most]."""
    if abs(len(a) - len(b)) > most:
        return most + 1
    before2 = None
    before = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        row = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            v = min(before[j] + 1, row[j - 1] + 1, before[j - 1] + cost)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                v = min(v, before2[j - 2] + 1)
            row[j] = v
        if min(row) > most:
            return most + 1
        before2, before = before, row
    return before[len(b)]


class Chosen(object):
    """A word of the list: its Zipf, its marks, the kind it came in by (for the counts), and what was read of it."""
    __slots__ = ("zipf", "marks", "kind")

    def __init__(self, zipf, marks, kind):
        self.zipf, self.marks, self.kind = zipf, marks, kind


KINDS = [
    ("dictionary", "a word the database has at its spelling-dictionary size, that wordfreq shows written"),
    ("dictionary name", "the same, written with a capital there: a name"),
    ("larger dictionary", "a word only the database's larger sizes have, or a less common variant, that wordfreq shows written"),
    ("larger dictionary name", "the same, written with a capital there"),
    ("written", "a word only wordfreq has: a name, a place, a newer word"),
    ("tatoeba", "a word en_more.tsv has and nothing above took"),
    ("form", "an inflected form of a listed word that only the database has"),
    ("unwritten", "a word only the database has"),
]


def vouched(db, word, size, level, sure, sure_level, spared=()):
    """What the database says of [word] in lower case, from every way it writes it: (mark, least size, places) where
    it vouches for it at [size] or under, else None. The mark is d or l, with n where it is always a name there."""
    if db is None:
        return None
    took = []
    for written in db.lower.get(word, ()):
        for p in db.places[written]:
            if p.size > size or p.level > level or "@" in p.rank:
                continue
            if p.pos in ("abbr", "pre", "suf", "x") or p.cls.startswith("abbr") or p.category == "roman-numerals":
                continue
            # A sense the database marks offensive, or as the coarsest kind of vulgar, vouches for nothing here: a
            # word with no other sense is left out, and is then what it was before, a word no list has.
            if p.usage in ("offensive-1", "offensive-2", "vulgar-1") and word not in spared:
                continue
            if ACRONYM.match(written) or not (LOWER.match(written) or CAPITAL.match(written) or MIXED.match(written)):
                continue
            took.append(p)
    if not took:
        return None
    is_sure = any(p.size <= sure and p.level <= sure_level for p in took)
    named = all(not LOWER.match(p.written) for p in (took if not is_sure else [p for p in took if p.size <= sure and p.level <= sure_level]))
    return ("d" if is_sure else "l") + ("n" if named else ""), min(p.size for p in took), took


def why_not(db, word, args, bad, never):
    """Why [word], which the database has and wordfreq shows written, is not taken: for the report's counts."""
    if word.endswith("'s"):
        return "a possessive (known by its word)"
    if not typable(word):
        return "not 3 to 24 plain letters"
    if word in never:
        return "a contraction or slang the keyboard has lists of its own for"
    if bad is not None and word in bad:
        return "on the LDNOOBW list"
    places = [p for written in db.lower.get(word, ()) for p in db.places[written]]
    inside = [p for p in places if p.size <= args.size]
    if not inside:
        return "only at a size over %d" % args.size
    live = [p for p in inside if p.level <= args.level and "@" not in p.rank]
    if not live:
        return "only as an archaic form or spelling"
    words = [p for p in live if not (p.pos in ("abbr", "pre", "suf", "x") or p.cls.startswith("abbr") or p.category == "roman-numerals")]
    if not words:
        return "an abbreviation, a prefix, a suffix or a Roman numeral"
    clean = [p for p in words if p.usage not in ("offensive-1", "offensive-2", "vulgar-1")]
    if not clean:
        return "marked offensive or vulgar by the database"
    return "written all in capitals, or with a mark"


#: A Roman numeral, which wordfreq lists among its words (xxvii).
ROMAN = re.compile(r"m{0,3}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})\Z")
#: A word stretched for effect, or laughed (sooooo, aaand, hahahaha): a letter three times running, or a pair.
STRETCHED = re.compile(r"(.)\1\1|(..)\2\2")


def run_together(word, z, first, pairs, margin):
    """The two words [word] is, where it is two words written without their space (goodmorning, atleast, fromthe):
    the starter table has the second following the first, and the two of them one after the other are [margin]
    commoner than [word] itself (the first word's frequency, less what share of its followers the second is). A
    name made of two words (westworld, moneyball) is not that: nothing has "world" following "west" so often."""
    if pairs is None or margin is None:
        return None
    for i in range(1, len(word) - 1):
        a, b = word[:i], word[i:]
        if a not in first or b not in first:
            continue
        share = pairs.get(a, {}).get(b, 0.0)
        if share > 0.0 and first[a] + math.log10(share) >= z + margin:
            return a + " " + b
    return None


def choose(args, first, written, db, more, bad, never, pairs=None):
    """The list: {word: Chosen}, and the words of en.tsv a dictionary vouches for {word: marks}."""
    out = {}
    said = {}
    notes = collections.Counter()
    if db is not None and not hasattr(db, "lower"):
        lower = collections.defaultdict(list)
        for w in db.places:
            lower[w.lower()].append(w)
        db.lower = dict(lower)

    def allowed(word):
        if not typable(word) or word.endswith("'s"):
            return False
        return word not in never and (bad is None or word not in bad)

    # 1. Where the two kinds of evidence meet.
    if db is not None:
        for word in db.lower:
            if word in first:
                if first[word] < args.common:
                    v = vouched(db, word, args.size, args.level, args.sure, args.sure_level, spared=first)
                    if v and v[0].startswith("d"):
                        said[word] = v[0]
                continue
            z = written.get(word) if written is not None else None
            v = vouched(db, word, args.size, args.level, args.sure, args.sure_level) if allowed(word) else None
            if v is None:
                if z is not None:
                    notes["in the database and written: " + why_not(db, word, args, bad, never)] += 1
                continue
            mark = v[0]
            if "n" in mark and not args.names:
                continue
            if z is None or z < args.floor:
                continue
            kind = ("dictionary" if mark[0] == "d" else "larger dictionary") + (" name" if "n" in mark else "")
            out[word] = Chosen(min(z, CAP), mark, kind)

    # 2. Words only writing vouches for.
    def near_a_commoner(word, z, reference, far):
        if args.near is not None:
            for s in slips(word):
                zs = reference.get(s)
                if zs is not None and zs >= z + args.near and word not in (s + "s", s + "es"):
                    return s
        if args.far is not None and len(word) >= 6:
            for c in cuts(word):
                for s in far.get(c, ()):
                    if s[0] == word[0] and reference[s] >= z + args.far and distance(word, s) <= 2 and word not in (s + "s", s + "es"):
                        return s
        return None

    if (written is not None and args.written is not None) or more is not None:
        reference = dict(first)
        for w, c in out.items():
            if c.marks[0] in "dl":
                reference[w] = c.zipf
        far = collections.defaultdict(list)
        if args.far is not None:
            for s, zs in reference.items():
                if zs >= (args.written if args.written is not None else 2.0) + args.far and len(s) >= 4:
                    for c in cuts(s):
                        far[c].append(s)

        def a_word(word):
            return len(word) >= 4 and "'" not in word and any(c in "aeiouy" for c in word) and not STRETCHED.search(word) and not ROMAN.match(word)

        if written is not None and args.written is not None:
            for word, z in written.items():
                if z < args.written or word in first or word in out or not allowed(word):
                    continue
                if not a_word(word):
                    notes["written: under four letters, no vowel, stretched, or a Roman numeral"] += 1
                    continue
                two = run_together(word, z, first, pairs, args.together)
                if two is not None:
                    notes["written: two words run together"] += 1
                    if len(args.shown_together) < 400:
                        args.shown_together.append("%s (%s)" % (word, two))
                    continue
                beside = near_a_commoner(word, z, reference, far)
                if beside is not None:
                    notes["written: a commoner word is a slip away"] += 1
                    if len(args.shown_dropped) < 400:
                        args.shown_dropped.append("%s (%s)" % (word, beside))
                    continue
                out[word] = Chosen(min(z, CAP), "w", "written")
        # en_more.tsv: the words Tatoeba's sentences use. A word the database has is a dictionary's word with the
        # frequency the sentences give it; any other is one only writing vouches for.
        if more is not None:
            for word, z in more.items():
                if word in first or word in out or not allowed(word):
                    continue
                v = vouched(db, word, args.size, args.level, args.sure, args.sure_level)
                if v is not None and ("n" not in v[0] or args.names):
                    out[word] = Chosen(min(z, CAP), v[0], "tatoeba")
                elif a_word(word) and run_together(word, z, first, pairs, args.together) is None:
                    # Written by three contributors or more (tool/more_words.py's rule): a name far more often than
                    # a misspelling, so it is not held to the test of a commoner word beside it (Rima, Baya, Yanni).
                    out[word] = Chosen(min(z, CAP), "w", "tatoeba")

    # 3. Words only the database has.
    if db is not None and (args.forms or args.unwritten):
        estimate = calibrate(db, written, args)
        listed = set(first) | set(out)
        if args.forms:
            heads = collections.defaultdict(set)
            for word in listed:
                for w in db.lower.get(word, ()):
                    for p in db.places[w]:
                        if p.lemma:
                            heads[p.group].add(word)
            for word in db.lower:
                if word in listed or not allowed(word):
                    continue
                v = vouched(db, word, args.size, args.level, args.sure, args.sure_level)
                if v is None or ("n" in v[0] and not args.names):
                    continue
                lemmas = [h for p in v[2] if not p.lemma for h in heads.get(p.group, ())]
                if not lemmas:
                    continue
                z = max((first.get(h) or out[h].zipf) for h in lemmas) - args.form_gap
                out[word] = Chosen(max(0.5, min(estimate["least"], z)), v[0], "form")
        if args.unwritten:
            for word in db.lower:
                if word in first or word in out or not allowed(word):
                    continue
                v = vouched(db, word, args.unwritten, args.level, args.sure, args.sure_level)
                if v is None or ("n" in v[0] and not args.names):
                    continue
                out[word] = Chosen(min(estimate["least"], estimate["by size"].get(v[1], estimate["least"])), v[0], "unwritten")
    return out, said, notes


def calibrate(db, written, args):
    """What a word only the database has is taken to be worth: at each size, the least tenth of the words of that
    size that wordfreq has too, and never more than a hundredth under the least wordfreq lists (it would have listed
    the word otherwise)."""
    least = (min(written.values()) if written else 1.01) - 0.01
    by_size = collections.defaultdict(list)
    if written:
        for word in db.lower:
            z = written.get(word)
            if z is None or not typable(word):
                continue
            v = vouched(db, word, 85, 9, args.sure, args.sure_level)
            if v is not None:
                by_size[v[1]].append(z)
    table = {}
    for size, zs in by_size.items():
        zs.sort()
        table[size] = round(zs[len(zs) // 10], 2)
    return {"least": round(least, 2), "by size": table, "median": {s: round(sorted(z)[len(z) // 2], 2) for s, z in by_size.items()}, "count": {s: len(z) for s, z in by_size.items()}}


# --- the file, the notice, the counts ---------------------------------------------------------------------------------

def encode(out, said, first):
    rows = [(w, "%.2f" % c.zipf, c.marks) for w, c in out.items()]
    rows += [(w, "%.2f" % first[w], m) for w, m in said.items()]
    rows.sort()
    return "".join("%s\t%s\t%s\n" % r for r in rows).encode("utf-8")


SECTION_START = "en_wide.tsv\n-----------\n"
SECTION_END = "wordfreq's sources\n------------------\n"


def notice_section(args, counts, sources):
    """The part of assets/dict/NOTICE.txt that is this list's: what it is made from, and each source's own notice."""
    lines = [SECTION_START.rstrip("\n")]
    lines.append(
        "English's wider list: the words of English as it is written now that en.tsv does not reach, each with a Zipf\n"
        "frequency and a mark for what vouches for it (d a dictionary, l a larger dictionary only, w writing only, n a\n"
        "name). Made by tool/wide_words.py in Ghost Keyboard's repository, which says how each word was chosen. It is a\n"
        "derived work of the sources below and, as it takes its frequencies from wordfreq, is distributed under the\n"
        "Creative Commons Attribution-ShareAlike 4.0 licence with the notices below kept beside it.")
    lines.append("")
    if "wordfreq" in sources:
        lines.append(
            "Frequencies, and which words are in use: wordfreq 3.1.1 (the citation and sources are those given for the\n"
            "other lists in this notice). What was changed: of its English list, only words of 3 to 24 plain letters\n"
            "with at most one apostrophe inside that en.tsv does not already have, and of those only the ones the\n"
            "dictionary below also has, or that pass the tool's tests for a word only writing vouches for.")
        lines.append("")
    if "database" in sources:
        lines.append(
            "Which strings are words: the English Speller Database (ESDB, formerly SCOWL), release 2026.02.25,\n"
            "https://github.com/en-wl/wordlist, read at sizes %d and under. No word that only its size 85 has is here.\n"
            "Its notice:" % args.size)
        lines.append("")
        lines.append(
            "  Copyright 2000-2026 by Kevin Atkinson\n"
            "\n"
            "  Permission to use, copy, modify, distribute, and sell any part of SCOWLv2, or\n"
            "  word lists created from it, is hereby granted without fee, provided that the\n"
            "  above copyright notice appears in all copies and that both the above\n"
            "  copyright notice and this notice appear in supporting documentation.  Kevin\n"
            "  Atkinson makes no representations about the suitability of this database for\n"
            "  any purpose.  It is provided \"as is\" without express or implied warranty.\n"
            "\n"
            "  SCOWL is derived from many sources, most of which are in the Public Domain.\n"
            "  Data from the Corpus of Contemporary American English (COCA) was also used.\n"
            "\n"
            "  All data from COCA comes from 3-gram data that is not freely available;\n"
            "  however, the usage is within the rights given by the NDA that was signed when\n"
            "  purchasing the data.  More information on COCA is available at\n"
            "  https://www.english-corpora.org/coca/.\n"
            "\n"
            "  The primary source of words for SCOWL comes from 12dicts and ENABLE2K.  Both\n"
            "  are in the Public Domain, but Alan Beale <biljir@pobox.com> deserves special\n"
            "  credit as he is the author of 12dicts and a major contributor to ENABLE2K.  In\n"
            "  addition, he gave me an incredible amount of feedback and created a number of\n"
            "  special lists in order to help improve the overall quality of SCOWL.")
        lines.append("")
        lines.append(
            "The list takes Australian spellings with the others, and for those the database's notice goes on:")
        lines.append("")
        lines.append(
            "  The initial information for Australian English comes from Benjamin Titze:\n"
            "\n"
            "    Copyright 2016 by Benjamin Titze\n"
            "\n"
            "    Permission to use, copy, modify, distribute and sell this array, the\n"
            "    associated software, and its documentation for any purpose is hereby\n"
            "    granted without fee, provided that the above copyright notice appears\n"
            "    in all copies and that both that copyright notice and this permission\n"
            "    notice appear in supporting documentation. Benjamin Titze makes no\n"
            "    representations about the suitability of this array for any\n"
            "    purpose. It is provided \"as is\" without express or implied warranty.")
        lines.append("")
        lines.append(
            "The database's parts of speech were read to tell an abbreviation, a prefix or a name from a word; they were\n"
            "first assigned with the help of WordNet (WordNet 1.6 Copyright 1997 by Princeton University. All rights\n"
            "reserved.), which is provided \"as is\" with no representations or warranties. None of them is in the file.")
        lines.append("")
    if "more" in sources:
        lines.append(
            "Names and newer words that only sentences show: en_more.tsv in this folder, counted from the English\n"
            "sentences of Tatoeba (https://tatoeba.org, CC BY 2.0 FR), whose contributors are named in\n"
            "assets/ngram/CREDITS.txt. None of the sentences is here.")
        lines.append("")
    if "bad" in sources:
        lines.append(
            "Words on the \"List of Dirty, Naughty, Obscene, and Otherwise Bad Words\"\n"
            "(https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words, CC BY 4.0) were left out.\n"
            "None of that list is in the file.")
        lines.append("")
    return "\n".join(lines) + "\n"


def with_section(text, section):
    """[text] (the notice) with this list's section put in, or put in anew, before wordfreq's sources."""
    start = text.find(SECTION_START)
    end = text.find(SECTION_END)
    if end < 0:
        return None
    if start >= 0:
        return text[:start] + section + text[end:]
    return text[:end] + section + text[end:]


def dictionaries_block(source):
    """tool/dictionaries.py's NOTICE string in its source: (where it starts, where it ends, its text)."""
    m = re.search(r'^NOTICE = """(.*?)"""$', source, re.S | re.M)
    if not m:
        return None
    return m.start(1), m.end(1), m.group(1)


def covered(held, words):
    return sum(held.get(w, 0) for w in words)


def possessive_of(word, known):
    return word.endswith("'s") and word[:-2] in known


def report(args, first, written, db, more, held, out, said, notes):
    rnd = random.Random(7)
    print("the list: %d words en.tsv does not have; and of en.tsv's %d words under Zipf %.2f, %d that a dictionary vouches for" % (
        len(out), sum(1 for z in first.values() if z < args.common), args.common, len(said)))
    total = sum(held.values()) if held else 0
    if held:
        base = covered(held, first)
        print("held-out lines 1 to %d: %d running words; en.tsv has %.3f%% of them (%d are words it lacks)" % (COVERAGE_LINES, total, 100.0 * base / total, total - base))
    print("%-24s %8s  %9s  %s" % ("kind", "words", "covers", "for example"))
    by_kind = collections.defaultdict(list)
    for w, c in out.items():
        by_kind[c.kind].append(w)
    for kind, _ in KINDS:
        words = by_kind.get(kind, [])
        if not words:
            continue
        share = ("%8.3f%%" % (100.0 * covered(held, words) / total)) if held else "        -"
        print("%-24s %8d  %s  %s" % (kind, len(words), share, " ".join(sorted(rnd.sample(words, min(args.samples, len(words)))))))
    if held:
        mine = covered(held, out)
        print("%-24s %8d  %8.3f%%" % ("all of them", len(out), 100.0 * mine / total))
        known = set(first) | set(out)
        poss = sum(n for w, n in held.items() if w not in known and possessive_of(w, known))
        left = [(w, n) for w, n in held.items() if w not in known and not possessive_of(w, known)]
        print("with en.tsv: %.3f%% of the held-out running words are words of the lists; %.3f%% more are the possessive of one (yurt's), which the keyboard knows by its word; %.3f%% (%d running words, %d different) are in no list" % (
            100.0 * (base + mine) / total, 100.0 * poss / total, 100.0 * sum(n for _, n in left) / total, sum(n for _, n in left), len(left)))
        left.sort(key=lambda r: (-r[1], r[0]))
        print("    the commonest still in no list: %s" % " ".join("%s×%d" % r for r in left[:40]))
        print("    and a sample of the rest: %s" % " ".join(sorted(rnd.sample([w for w, _ in left[40:]], min(60, max(0, len(left) - 40))))))
    marks = collections.Counter(c.marks for c in out.values())
    print("marks: %s" % "  ".join("%s ×%d" % (m, n) for m, n in sorted(marks.items())))
    bands = collections.Counter(int(c.zipf * 5) / 5.0 for c in out.values())
    print("Zipf: %s" % "  ".join("%.1f+ ×%d" % (z, n) for z, n in sorted(bands.items(), reverse=True)))
    lengths = collections.Counter(len(w) for w in out)
    print("letters: %s; %d characters in all" % ("  ".join("%d ×%d" % (n, c) for n, c in sorted(lengths.items())), sum(len(w) for w in out)))
    for note, n in sorted(notes.items()):
        print("left out, %s: %d" % (note, n))
    if args.shown_dropped:
        print("    a commoner word beside it, for example: %s" % "  ".join(rnd.sample(args.shown_dropped, min(args.samples, len(args.shown_dropped)))))
    if args.shown_together:
        print("    two words run together, for example: %s" % "  ".join(rnd.sample(args.shown_together, min(args.samples, len(args.shown_together)))))
    if db is not None:
        print("the database: %d lines in %d groups read, %d spellings; %s; %d lines not read%s" % (
            db.lines, db.groups, len(db.places), ", ".join("%d %s" % (n, k) for k, n in sorted(db.adjusted.items())) or "nothing adjusted",
            len(db.unread), (": " + "; ".join(db.unread[:5])) if db.unread else ""))


def word_report(args, word, first, written, db, more, bad, out, said):
    print("%s:" % word)
    print("  en.tsv: %s" % ("%.2f" % first[word] if word in first else "not there"))
    print("  wordfreq: %s" % ("no file" if written is None else "%.2f" % written[word] if word in written else "not there"))
    print("  en_more.tsv: %s" % ("no file" if more is None else "%.2f" % more[word] if word in more else "not there"))
    print("  LDNOOBW: %s" % ("no file" if bad is None else "on it" if word in bad else "not on it"))
    if db is None:
        print("  the database: not there")
    else:
        places = sorted(p for w in db.lower.get(word, ()) for p in db.places[w])
        print("  the database: %s" % ("not there" if not places else "%d places" % len(places)))
        for p in places:
            print("    %-18s size %d, variant level %d%s%s%s%s%s  %s" % (
                p.written, p.size, p.level, ", rank " + p.rank if p.rank else "", ", <%s%s>" % (p.pos, "/" + p.cls if p.cls else "") if p.pos or p.cls else "",
                ", (%s)" % p.usage if p.usage else "", ", " + p.category if p.category else "", ", " + p.region if p.region else "", p.tags))
        v = vouched(db, word, args.size, args.level, args.sure, args.sure_level)
        print("  vouched for: %s" % ("no" if v is None else "%s, from size %d" % (v[0], v[1])))
    if word in out:
        print("  in the list: %.2f %s (%s)" % (out[word].zipf, out[word].marks, out[word].kind))
    elif word in said:
        print("  in the list as a word of en.tsv a dictionary vouches for: %s" % said[word])
    else:
        print("  not in the list")


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--wordfreq", default=WORDFREQ, help="wordfreq's whole English list as word<TAB>zipf")
    ap.add_argument("--database", default=DATABASE, help="the English Speller Database's source folder")
    ap.add_argument("--more", default="", help="en_more.tsv, the words Tatoeba's sentences use (not among the defaults)")
    ap.add_argument("--bad", default=BAD, help="the LDNOOBW list")
    ap.add_argument("--heldout", default=HELDOUT, help="held-out sentences, for the share of running words covered")
    ap.add_argument("--without", action="append", default=[], choices=["wordfreq", "database", "more", "bad"], help="leave a source out though it is there")
    ap.add_argument("--size", type=int, default=80, help="the largest database size taken (never over 80)")
    ap.add_argument("--sure", type=int, default=60, help="the largest size whose words are marked d")
    ap.add_argument("--level", type=int, default=7, help="the worst variant level taken (8 is archaic, 9 invalid)")
    ap.add_argument("--sure-level", type=int, default=4, help="the worst variant level marked d")
    ap.add_argument("--floor", type=float, default=1.0, help="the least wordfreq Zipf a database word must have")
    ap.add_argument("--written", type=float, default=2.0, help="the least Zipf of a word only wordfreq has; -1 leaves them out")
    ap.add_argument("--near", type=float, default=1.0, help="a written word is left out where a word this much commoner is one slip away; -1 never")
    ap.add_argument("--far", type=float, default=2.0, help="... or two slips away, from six letters, a word this much commoner; -1 never")
    ap.add_argument("--together", type=float, default=1.0, help="a written word is left out where it is two words run together that are this much commoner apart; -1 never")
    ap.add_argument("--pairs", default=PAIRS, help="the starter table, for telling two words run together")
    ap.add_argument("--no-names", dest="names", action="store_false", help="leave out what the database writes only with a capital")
    ap.add_argument("--forms", action="store_true", help="take the inflected forms of listed words that only the database has")
    ap.add_argument("--form-gap", type=float, default=0.6, help="how much rarer than its word such a form is taken to be")
    ap.add_argument("--unwritten", type=int, default=0, help="take every word the database has to this size, written or not")
    ap.add_argument("--common", type=float, default=3.2, help="en.tsv's words under this Zipf are said to be vouched for or not")
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--dry", action="store_true")
    ap.add_argument("--no-cache", dest="cache", action="store_false")
    ap.add_argument("--word", action="append", default=[])
    ap.add_argument("--samples", type=int, default=14)
    args = ap.parse_args(argv)
    if args.size > 80:
        sys.exit("wide_words: sizes over 80 are not taken (the database's size 85 has a notice of its own)")
    args.written = None if args.written < 0 else args.written
    args.near = None if args.near < 0 else args.near
    args.far = None if args.far < 0 else args.far
    args.together = None if args.together < 0 else args.together
    args.shown_dropped = []
    args.shown_together = []

    first = read_tsv(os.path.join(DICT, "en.tsv"))
    if first is None:
        sys.exit("wide_words: no en.tsv in %s" % DICT)
    never = (read_set(os.path.join(DICT, "en_contractions.tsv"), first_column=True) or set()) | (read_set(os.path.join(DICT, "en_slang.txt")) or set())
    written = None if "wordfreq" in args.without else read_tsv(args.wordfreq)
    if written is not None:
        written = {w: z for w, z in written.items() if TYPABLE.match(w)}
    db = None if "database" in args.without else Database.load(args.database, args.cache)
    more = None if "more" in args.without else read_tsv(args.more)
    bad = None if "bad" in args.without else read_set(args.bad)
    if bad is not None:
        bad = set(w for w in bad if " " not in w)
    held = heldout(args.heldout)
    pairs = read_pairs(args.pairs)
    sources = []
    for name, got, path in (("wordfreq", written, args.wordfreq), ("database", db, args.database), ("more", more, args.more), ("bad", bad, args.bad)):
        if got is None:
            if name != "more" or path:
                print("%s: %s, so the list is made without it" % (name, "left out" if name in args.without else "no " + path))
        else:
            sources.append(name)
            print("%s: %s (%d %s)" % (name, path, len(got.places) if name == "database" else len(got), "spellings" if name == "database" else "words"))
    if held is None:
        print("no %s: the share of running words covered is not counted" % args.heldout)
    # The bundled list is made from all three: one that is not there is left out only where --without says so,
    # so that a source gone missing never quietly leaves a shorter list in the app.
    missing = [name for name, got in (("wordfreq", written), ("database", db), ("bad", bad)) if got is None and name not in args.without]
    if missing and os.path.abspath(args.out) == os.path.abspath(OUT) and not args.dry and not args.word:
        sys.exit("wide_words: %s not found, and the bundled list is made with %s: give its path, or --without %s to make the list without" % (
            " and ".join(missing), "it" if len(missing) == 1 else "them", " --without ".join(missing)))

    out, said, notes = choose(args, first, written, db, more, bad, never, pairs)
    if args.word:
        for word in args.word:
            word_report(args, word.lower(), first, written, db, more, bad, out, said)
        return
    report(args, first, written, db, more, held, out, said, notes)
    data = encode(out, said, first)
    section = notice_section(args, None, sources)
    default = os.path.abspath(args.out) == os.path.abspath(OUT)
    print("%s: %d lines, %d bytes" % (os.path.relpath(args.out, ROOT), data.count(b"\n"), len(data)))

    notice_now = open(NOTICE, encoding="utf-8").read() if os.path.exists(NOTICE) else None
    notice_new = with_section(notice_now, section) if notice_now is not None else None
    source_now = open(DICTIONARIES, encoding="utf-8").read() if os.path.exists(DICTIONARIES) else None
    block = dictionaries_block(source_now) if source_now is not None else None
    source_new = None
    if block is not None:
        made = with_section(block[2], section)
        if made is not None:
            source_new = source_now[:block[0]] + made + source_now[block[1]:]

    if args.check:
        same = os.path.exists(args.out) and open(args.out, "rb").read() == data
        print("%s %s" % (os.path.basename(args.out), "same" if same else "DIFFERENT"))
        ok = same
        if default:
            for name, now, new in (("NOTICE.txt", notice_now, notice_new), ("tool/dictionaries.py's notice", source_now, source_new)):
                good = now is not None and new is not None and now == new
                print("%s %s" % (name, "same" if good else "DIFFERENT"))
                ok = ok and good
        sys.exit(0 if ok else 1)
    if args.dry:
        return
    with open(args.out, "wb") as f:
        f.write(data)
    if default:
        # The notice, and the block tool/dictionaries.py writes it from, so that its --check still passes.
        if notice_new is None or source_new is None:
            print("NOTICE.txt or tool/dictionaries.py's notice could not be found to write this list's part into")
        else:
            with open(NOTICE, "w", encoding="utf-8") as f:
                f.write(notice_new)
            with open(DICTIONARIES, "w", encoding="utf-8") as f:
                f.write(source_new)
            print("NOTICE.txt and tool/dictionaries.py's notice: this list's part written")


if __name__ == "__main__":
    main(sys.argv[1:])
