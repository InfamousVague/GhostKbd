#!/usr/bin/env python3
"""Ghost Keyboard's word lists: android/app/src/main/assets/dict/<language>.tsv, one `word<TAB>zipf` a line,
commonest first, made from the `wordfreq` package (Robyn Speer; its data is CC BY-SA 4.0).

    python3 -m venv .venv && .venv/bin/pip install wordfreq==3.1.1
    .venv/bin/python tool/dictionaries.py            # writes every list this script makes, and NOTICE.txt
    .venv/bin/python tool/dictionaries.py pl ru      # only these
    .venv/bin/python tool/dictionaries.py --check    # writes nothing: fails if a file would change

Every list is inside the app (Autocorrect.kt reads them from its assets): nothing is ever downloaded, so they work
with Offline on.

What goes in a list: the language's commonest 30,000 words that its keyboard can type (Languages.kt has the layouts).
- Latin-script languages keep words made of lower-case Latin letters and an apostrophe (Catalan also its middle dot).
- The others keep words spelled in their own alphabet (ALPHABETS), which drops the few per cent of Latin entries
  (brand names, "ok") a Cyrillic or Arabic board cannot type.
- wordfreq's quirks are undone where they would be wrong on a keyboard: Greek loses its final sigma there (it comes
  back), Persian mixes Arabic's ي and ك with its own ی and ک (they are merged, the frequencies added).
- Serbian in Cyrillic (`sr`) is wordfreq's Latin Serbo-Croatian list (`sh`) transliterated, since wordfreq has no
  Cyrillic one. That list holds English words and names too (the, of, michael, google), which letter for letter
  come out as Cyrillic nobody writes (тхе, оф, мицхаел): of its commonest 30,000 words that can be transliterated,
  the ones that are English and not Serbian are left out (`foreign`), so sr.tsv is a few hundred words short.
- Urdu loses the entries that are two words run together (ہےکہ, کےلئے): the ones with ے or ں inside them, letters
  Urdu writes only at a word's end (`fused`).
- Korean is not made: wordfreq's Korean list is word parts, not words. Chinese and Japanese need a conversion engine,
  and Hindi, Bengali and Tamil a layered layout, none of which the keyboard has; their lists are not bundled.

The six lists the keyboard shipped with first (en de es fr it pt) are wordfreq's too, made before this script. They
are never rewritten; `--check` only confirms each of their lines is still what wordfreq says.
"""
import collections
import math
import os
import sys
import unicodedata

from wordfreq import get_frequency_dict, iter_wordlist

HERE = os.path.dirname(os.path.abspath(__file__))
DICT = os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "dict")

#: How many words a list holds at most (a language with fewer that pass is shorter).
N = 30_000

#: The lists that came before this script: checked, never written.
LEGACY = "en de es fr it pt".split()

LATIN = "nl sv nb da fi is pl cs sk sl hu ro lt lv tr ca id ms fil vi sh".split()


def _range(a, b):
    return {chr(c) for c in range(ord(a), ord(b) + 1)}


#: The letters of each language that is not written in Latin script.
ALPHABETS = {
    "ru": _range("а", "я") | {"ё"},
    "uk": (_range("а", "я") - set("ъыэ")) | set("єіїґ"),
    "bg": _range("а", "я") - set("ыэ"),
    "mk": (_range("а", "я") - set("йщъыьэюя")) | set("ѓѕјљњќџ"),
    "el": _range("α", "ω") | set("άέήίόύώϊϋΐΰ"),
    "he": _range("א", "ת"),
    "ar": _range("ء", "غ") | _range("ف", "ي"),
    "fa": set("اآبپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیءأؤئ"),
    "ur": set("اآبپتٹثجچحخدڈذرڑزژسشصضطظعغفقکگلمنںوہھءیےئؤۂۃ"),
}

#: A character that may join two halves of a word, once, never first or last: an apostrophe, Catalan's middle dot,
#: Persian and Urdu's half-space (U+200C).
JOINERS = {"uk": "'", "he": "'", "fa": "‌", "ur": "‌", "ca": "'·"}
JOINERS.update({lang: "'" for lang in LATIN + LEGACY if lang not in JOINERS})

#: Letters wordfreq leaves in a language's list in another language's form.
FIX = {"fa": {"ي": "ی", "ك": "ک"}}

#: Serbian Latin to Cyrillic, letter for letter; lj, nj and dž are one letter each.
SR = {"a": "а", "b": "б", "v": "в", "g": "г", "d": "д", "đ": "ђ", "e": "е", "ž": "ж", "z": "з", "i": "и", "j": "ј",
      "k": "к", "l": "л", "m": "м", "n": "н", "o": "о", "p": "п", "r": "р", "s": "с", "t": "т", "ć": "ћ", "u": "у",
      "f": "ф", "h": "х", "c": "ц", "č": "ч", "š": "ш"}

#: Stems where n+j, l+j or d+ž are two letters (a prefix meeting a root, a borrowed word), with a bar where they part.
SR_SPLIT = {"injek": "in|jek", "konjug": "kon|jug", "konjunk": "kon|junk", "nadživ": "nad|živ", "odživ": "od|živ",
            "podžup": "pod|žup", "tanjug": "tan|jug"}

#: Serbian words that are spelled as a commoner English word is (more is the sea, most a bridge, so is salt), and
#: names Serbian writes the same way: kept where `foreign` would take them for English. Read through by hand from the
#: entries its frequency rule would drop; a word missing here is a Serbian word missing from sr.tsv.
SR_OWN = set("""
    more most same love let go top so oh as set open hit at original real fine stop face dog but hard side island
    mine police site table prime profile note ten age bus start title florida ring sale institute civil care color
    alternative human urban gone market name obama moment prince holding sit song trip clinton ran total print tag
    amazon level singer limit distance dragon zoo
""".split())

#: How much commoner, in hundredths of a Zipf point (100 is ten times), English must have a word for it to count as
#: English in the Serbo-Croatian list.
SR_MARGIN = 100

#: The letters Urdu writes only at the end of a word.
UR_FINAL = "ےں"

#: Urdu's prefix "without", which joins the word after it and ends in a final letter all the same (بےشک, بےحد).
UR_PREFIX = "بے"

#: The languages this script writes, in the order they are made.
MADE = LATIN + list(ALPHABETS) + ["sr"]


def serbian_cyrillic(word):
    """[word] in Cyrillic, or None where it has a letter Serbian Cyrillic lacks (q w x y)."""
    for stem, split in SR_SPLIT.items():
        word = word.replace(stem, split)
    out = []
    i = 0
    while i < len(word):
        two = word[i:i + 2]
        if two == "lj":
            out.append("љ"); i += 2
        elif two == "nj":
            out.append("њ"); i += 2
        elif two == "dž":
            out.append("џ"); i += 2
        elif word[i] == "|":
            i += 1
        elif word[i] in SR:
            out.append(SR[word[i]]); i += 1
        else:
            return None
    return "".join(out)


def hundredths(z):
    """A two-decimal Zipf string as a whole number of hundredths, so comparisons are exact."""
    return int(round(float(z) * 100))


def english():
    """en.tsv, the keyboard's own English list (wordfreq's commonest 40,000): word to Zipf in hundredths."""
    words = {}
    with open(os.path.join(DICT, "en.tsv"), encoding="utf-8") as f:
        for line in f:
            word, _, z = line.rstrip("\n").partition("\t")
            words[word] = hundredths(z)
    return words


def foreign(word, z, en):
    """Whether [word], an entry of wordfreq's Latin Serbo-Croatian list with Zipf [z] (hundredths), is English or a
    foreign name rather than Serbian, and so must not be transliterated:
    - it has "ch" in it, or starts with "th": no Serbian word does (chicagu, thomasa);
    - or it is in the English list [en] and is spelled as Serbian does not spell: a doubled letter, th, ck, ph, gh
      (google, smith, jack, joseph, night);
    - or it is in the English list at least ten times as common as here (the, of, and, house).
    The last two spare SR_OWN, the Serbian words spelled as an English one is. Names spelled plainly (john, james)
    pass: nothing here tells them from Serbian words."""
    if "ch" in word or word.startswith("th"):
        return True
    if word not in en or word in SR_OWN:
        return False
    if any(pair in word for pair in ("th", "ck", "ph", "gh")) or any(a == b for a, b in zip(word, word[1:])):
        return True
    return en[word] >= z + SR_MARGIN


def fused(word):
    """Whether [word], an Urdu entry, is two words run together: ے or ں stands before its last letter, and Urdu writes
    those only at a word's end. The prefix بے is the one thing that ends in ے and goes on (بےشک)."""
    rest = word[len(UR_PREFIX):] if word.startswith(UR_PREFIX) else word
    return "\u200c" not in word and any(c in UR_FINAL for c in rest[:-1])


def typable(lang, word):
    """Whether [lang]'s keyboard can type [word]: its length, its letters, and at most one joiner inside it."""
    if not 1 <= len(word) <= 24:
        return False
    joiners = JOINERS.get(lang, "")
    if word[0] in joiners or word[-1] in joiners or sum(word.count(c) for c in joiners) > 1:
        return False
    if lang in ALPHABETS:
        alphabet = ALPHABETS[lang]
        return all(c in alphabet or c in joiners for c in word)
    for c in word:
        if c in joiners:
            continue
        if unicodedata.category(c) != "Ll" or not unicodedata.name(c, "").startswith("LATIN"):
            return False
    return True


def zipf(frequency):
    """wordfreq's Zipf scale (log10 of occurrences per billion words), two decimals, as the lists have always had it.
    Not `zipf_frequency`: that tokenises its word again, and needs MeCab for some languages."""
    return "%.2f" % (math.log10(frequency) + 9)


def build(lang):
    """[lang]'s list: (word, zipf) rows, commonest first."""
    source = "sh" if lang == "sr" else lang
    frequencies = get_frequency_dict(source)
    merged = collections.OrderedDict()
    fix = FIX.get(lang, {})
    latin = {}
    for word in iter_wordlist(source):
        f = frequencies[word]
        if fix:
            word = "".join(fix.get(c, c) for c in word)
        if lang == "el" and len(word) > 1 and word.endswith("σ"):
            word = word[:-1] + "ς"
        if lang == "sr":
            source_word = word
            word = serbian_cyrillic(word)
            if word is None or not 1 <= len(word) <= 24:
                continue
            latin.setdefault(word, source_word)
        elif not typable(lang, word):
            continue
        elif lang == "ur" and fused(word):
            continue
        merged[word] = merged.get(word, 0.0) + f
    rows = [(w, zipf(f)) for w, f in sorted(merged.items(), key=lambda kv: -kv[1])[:N]]
    if lang == "sr":
        # After the cut at N, so the list is the same commonest words as before, less the English among them.
        en = english()
        rows = [(w, z) for w, z in rows if not foreign(latin[w], hundredths(z), en)]
    return rows


def encode(rows):
    return "".join(f"{w}\t{z}\n" for w, z in rows).encode("utf-8")


NOTICE = """Ghost Keyboard's word lists
===========================

The .tsv files in this folder are derived from wordfreq 3.1.1 by Robyn Speer
(https://github.com/rspeer/wordfreq), whose data is distributed under the Creative Commons
Attribution-ShareAlike 4.0 licence (https://creativecommons.org/licenses/by-sa/4.0/). These files are distributed
under the same licence.

Citation: Robyn Speer. (2022). rspeer/wordfreq: v3.0 (v3.0.2). Zenodo. https://doi.org/10.5281/zenodo.7199437

What was changed
----------------
Each file is one language's commonest words (40,000 for English, up to 30,000 for the others) with wordfreq's Zipf
frequency to two decimals, made by tool/dictionaries.py in Ghost Keyboard's repository:
- only words the language's keyboard can type are kept: lower-case words in the language's own script, of 1 to 24
  characters, with at most one apostrophe (or Catalan middle dot, or Persian and Urdu half-space) inside;
- Greek words that end in sigma are spelled with the final form (ς);
- Persian words spelled with the Arabic letters ي and ك are respelled with ی and ک, and merged with the same word
  where it was there in both spellings (their frequencies added);
- sr.tsv (Serbian in Cyrillic) is wordfreq's Latin Serbo-Croatian list ("sh") transliterated letter for letter,
  less the entries among its commonest 30,000 that are English words or foreign names rather than Serbian (they have
  no Serbian Cyrillic spelling that the letters would give);
- Urdu entries that are two words written without the space between them (a letter that Urdu writes only at a
  word's end stands inside them) are left out;
- en.tsv, de.tsv, es.tsv, fr.tsv, it.tsv and pt.tsv are wordfreq's lists as they are.
en_contractions.tsv and en_slang.txt are Ghost Keyboard's own and are not from wordfreq.

en_wide.tsv
-----------
English's wider list: the words of English as it is written now that en.tsv does not reach, each with a Zipf
frequency and a mark for what vouches for it (d a dictionary, l a larger dictionary only, w writing only, n a
name). Made by tool/wide_words.py in Ghost Keyboard's repository, which says how each word was chosen. It is a
derived work of the sources below and, as it takes its frequencies from wordfreq, is distributed under the
Creative Commons Attribution-ShareAlike 4.0 licence with the notices below kept beside it.

Frequencies, and which words are in use: wordfreq 3.1.1 (the citation and sources are those given for the
other lists in this notice). What was changed: of its English list, only words of 3 to 24 plain letters
with at most one apostrophe inside that en.tsv does not already have, and of those only the ones the
dictionary below also has, or that pass the tool's tests for a word only writing vouches for.

Which strings are words: the English Speller Database (ESDB, formerly SCOWL), release 2026.02.25,
https://github.com/en-wl/wordlist, read at sizes 80 and under. No word that only its size 85 has is here.
Its notice:

  Copyright 2000-2026 by Kevin Atkinson

  Permission to use, copy, modify, distribute, and sell any part of SCOWLv2, or
  word lists created from it, is hereby granted without fee, provided that the
  above copyright notice appears in all copies and that both the above
  copyright notice and this notice appear in supporting documentation.  Kevin
  Atkinson makes no representations about the suitability of this database for
  any purpose.  It is provided "as is" without express or implied warranty.

  SCOWL is derived from many sources, most of which are in the Public Domain.
  Data from the Corpus of Contemporary American English (COCA) was also used.

  All data from COCA comes from 3-gram data that is not freely available;
  however, the usage is within the rights given by the NDA that was signed when
  purchasing the data.  More information on COCA is available at
  https://www.english-corpora.org/coca/.

  The primary source of words for SCOWL comes from 12dicts and ENABLE2K.  Both
  are in the Public Domain, but Alan Beale <biljir@pobox.com> deserves special
  credit as he is the author of 12dicts and a major contributor to ENABLE2K.  In
  addition, he gave me an incredible amount of feedback and created a number of
  special lists in order to help improve the overall quality of SCOWL.

The list takes Australian spellings with the others, and for those the database's notice goes on:

  The initial information for Australian English comes from Benjamin Titze:

    Copyright 2016 by Benjamin Titze

    Permission to use, copy, modify, distribute and sell this array, the
    associated software, and its documentation for any purpose is hereby
    granted without fee, provided that the above copyright notice appears
    in all copies and that both that copyright notice and this permission
    notice appear in supporting documentation. Benjamin Titze makes no
    representations about the suitability of this array for any
    purpose. It is provided "as is" without express or implied warranty.

The database's parts of speech were read to tell an abbreviation, a prefix or a name from a word; they were
first assigned with the help of WordNet (WordNet 1.6 Copyright 1997 by Princeton University. All rights
reserved.), which is provided "as is" with no representations or warranties. None of them is in the file.

Words on the "List of Dirty, Naughty, Obscene, and Otherwise Bad Words"
(https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words, CC BY 4.0) were left out.
None of that list is in the file.

wordfreq's sources
------------------
wordfreq contains data extracted from Google Books Ngrams (http://books.google.com/ngrams) and Google Books Syntactic
Ngrams, and data derived from these Creative Commons-licensed sources: the Leeds Internet Corpus, from the University
of Leeds Centre for Translation Studies (http://corpus.leeds.ac.uk/list.html); Wikipedia, the free encyclopedia
(http://www.wikipedia.org); ParaCrawl, a multilingual Web crawl (https://paracrawl.eu).

It contains data from OPUS OpenSubtitles 2018 (http://opus.nlpl.eu/OpenSubtitles.php), whose data originates from the
OpenSubtitles project (http://www.opensubtitles.org/) and may be used with attribution to OpenSubtitles.

It contains data from the SUBTLEX word lists SUBTLEX-US, SUBTLEX-UK, SUBTLEX-CH, SUBTLEX-DE and SUBTLEX-NL, created
by Marc Brysbaert et al. and freely available at http://crr.ugent.be/programs-data/subtitle-frequencies.

It contains data from the OSCAR corpus (https://oscar-corpus.com), NewsCrawl and GlobalVoices, and statistics about
words commonly used on Twitter and Reddit; none of their text is reproduced.

The full list of citations is in wordfreq's README.
"""


def check_legacy(lang):
    """How many of a first-six list's lines differ from wordfreq (0 is right)."""
    frequencies = get_frequency_dict(lang)
    wrong = 0
    with open(os.path.join(DICT, f"{lang}.tsv"), encoding="utf-8") as f:
        for line in f:
            word, _, z = line.rstrip("\n").partition("\t")
            if word not in frequencies or zipf(frequencies[word]) != z:
                wrong += 1
    return wrong


def main(argv):
    check = "--check" in argv
    langs = [a for a in argv if not a.startswith("--")] or MADE
    unknown = [lang for lang in langs if lang not in MADE]
    if unknown:
        sys.exit(f"dictionaries: no recipe for {' '.join(unknown)} (it makes: {' '.join(MADE)})")
    failed = False
    total = 0
    for lang in langs:
        rows = build(lang)
        data = encode(rows)
        path = os.path.join(DICT, f"{lang}.tsv")
        total += len(data)
        if check:
            same = os.path.exists(path) and open(path, "rb").read() == data
            failed |= not same
            print(f"{lang:4} {'same' if same else 'DIFFERENT'}")
        else:
            with open(path, "wb") as f:
                f.write(data)
            print(f"{lang:4} {len(rows):6} words {len(data):8} bytes  last {rows[-1][0]} {rows[-1][1]}  first {' '.join(w for w, _ in rows[:6])}")
    notice = os.path.join(DICT, "NOTICE.txt")
    if check:
        if langs == MADE:
            for lang in LEGACY:
                wrong = check_legacy(lang)
                failed |= wrong > 0
                print(f"{lang:4} {'as wordfreq has it' if wrong == 0 else f'{wrong} LINES DIFFER from wordfreq'}")
            same = os.path.exists(notice) and open(notice, encoding="utf-8").read() == NOTICE
            failed |= not same
            print(f"NOTICE.txt {'same' if same else 'DIFFERENT'}")
        sys.exit(1 if failed else 0)
    with open(notice, "w", encoding="utf-8") as f:
        f.write(NOTICE)
    print(f"{len(langs)} lists, {total} bytes")


if __name__ == "__main__":
    main(sys.argv[1:])
