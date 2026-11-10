# Next-word prediction

After a space, at the start of a field and at the start of a sentence, the row above the keys offers the three
likeliest next words, the likeliest in the middle. A tap types the word with a space and the row offers the next
three. While a word is being typed its completions are ranked by the word before as well as by how common they are,
and where autocorrect could mend a slip two ways it picks the likelier one after that word.

It is part of Autocorrect (which owns the word lists and the row's suggestions): **Settings › Typing › Autocorrect ›
Next word** has the switch, the learning switch, how many word pairs have been learned, and "Forget what it learned".
With the switch off, the row, completions and corrections are exactly as they were before there was prediction.

Nothing here opens a connection. The starter tables are files inside the app and what is learned is a file in the
app's own storage, so there is no path for it in the network ledger (`NetLedger.kt`) and the Offline switch has
nothing of it to stop.

## What it goes by

Two things, blended in `Scorer` (`NextWordModel.kt`):

- **Starter tables**, one a language, inside the app: for a word, for two words in a row, or for the start of a
  sentence, the words that most often came next in Tatoeba's sentences and each one's share. A two-word row ("how
  are", "^ thank" for thank opening a sentence) carries how often its two words were seen together.
- **What the person types**, counted on the phone (`PersonalPairs`): how often each word followed the word before
  it, and the two words before it.

With `u` a personal count, `U` everything the person has typed after that context, and `B` the starter share:

    the starter share: B  = (n·B₂ + 15·B₁) / (n + 15)  where the table has a row for the two words before, seen n times
                                                       (else B = B₁, the one-word row's share)
    after one word:    S2 = (u + 8·B) / (U + 8)
    after two words:   S  = (u + 3·S2) / (U + 3)      where the person has typed that pair of words before

So a new keyboard goes by the starter table alone, a pair the person types leads in its own context after a few uses,
and a habit after two particular words leads at once. A pair typed once is remembered and not offered; it is offered
from its second use, or its third if the word is in no word list.

Where nothing is known of a context, the row shows its tools as it always did. Where one or two words are known, the
row is filled up with the person's most-typed words, then the language's commonest.

Several languages can be on at once. Each table is weighed by how many of the last four words it knows, so the
language being written leads without being chosen.

For completions, a word's rank is its Zipf frequency (or what the person's own use of it comes to, if more) plus
`log10(1 + 40 · S / the word's own share of all words)`, at most 6. That lift is exactly 0 where the context says
nothing, so context never ranks a word down. For corrections the same lift counts for at most 1.5, and only among
candidates that were good enough corrections without it: context never causes a correction.

## What is learned, and what never is

A finished word is held until the next word ends, and counted then only if it still stands as it was typed, so a
typo fixed by hand and an autocorrection that was undone are never learned. (Backspace elsewhere does not lose it:
fixing a slip in the first word of a sentence keeps the last word of the sentence before.) Counting happens a moment
after the key press, never on it. The rules are one plain class, `NextWordLearner` in `NextWordModel.kt`, which
`tool/next_word_check.sh` types at.

A word counts once, however it was entered by hand: typed, swiped, or tapped in the row. A swiped word (which has no
space after it yet) is counted when something ends it: a space, a full stop, the next swipe, or sending the message.

Never learned:

- anything in a password field;
- anything while incognito (the Incognito plugin's chip, an app's profile, or a field that asks keyboards for no
  personalised learning);
- addresses, names, searches, fields that ask for no suggestions, and this app's own fields;
- anything in a terminal app (and nothing is predicted there);
- text that was pasted, dictated or written by the AI, neither as a word nor as the word before one;
- what was typed while learning was off, once it is on again in the same field: with the Incognito chip switched
  off in the middle of a message, the words typed before that are not learned and are not the context of the words
  typed after. The text behind the cursor at that moment is fenced off (as pasted text is) until the next sentence
  starts; a word begun before the fence is not learned either. If that text is edited, nothing is learned until the
  next sentence;
- code-shaped words (after `@ # / . _ - \ : $` or a digit, or with a digit straight after: the `mp` of `mp3`),
  camelCase, and words over 24 characters;
- a half-typed word when the keyboard is hidden. The last word of a message counts when enter is pressed or the
  app's own button sends it.

With "Learn from what I type" off nothing more is counted; what was learned is still used until it is forgotten.

Counts fade: every 2,000 learned words every count is multiplied by 0.84 (half forgotten in about 8,000 words) and
a count under 0.4 is dropped. At most 30,000 pairs are kept and 32 followers a context; past that the least typed go.
Fading and pruning run when the keyboard hides.

## Files

**Starter tables**: `android/app/src/main/assets/ngram/<language>.tsv`, with the same codes as `assets/dict`.

    #ghost-ngram	1	en	Tatoeba export 2026-11-07, CC BY 2.0 FR
    ^	I:1429 the:486 he:416
    thank	you:7779 God:397 me:274
    ^ thank	=1352 you:9194 God:459 goodness:141

A line is a context in lower case (`^` is a sentence's start), a tab, then `word:share` entries, likeliest first. The
share is in ten-thousandths of everything that followed the context. A context of two words opens with `=n`, how
often they were seen together; a keyboard from before two-word rows skips that entry and never looks the row up.
In memory a context is its words' numbers, so the two-word rows cost no strings (English: about 10 MB). A word is written as it is usually cased in the
middle of a sentence. `NOTICE.txt` and `CREDITS.txt` beside them carry the attribution.

**What is learned**: `next_words.tsv` in the app's no-backup files, so it is not in Android's cloud backup and is
not copied to a new phone by it. A typing profile carries it instead (`words.next`, [typing-profile.md](typing-profile.md)).

    #ghost-next-words	1	events=5321	decayed=4000
    ^	hey:4.00 ok:3.00
    on	my:6.00 the:2.00
    on my	way:5.00
    =	monday=Monday i=I

A context with a space is the two words before. The last line lists the words shown with capitals. It is plain text
in the app's private storage; a file this keyboard cannot read is set aside beside it as `next_words.bad` (or
`.newer`). "Forget what it learned" deletes the learned file, a set-aside one and a half-written save
(`next_words.tsv.tmp`): every copy there is.

It is written when the keyboard hides, and after 200 newly learned words once typing has paused for three seconds,
always on a background thread and by way of a temporary file. Killing the app loses at most those words.

## Where the starter tables come from

`tool/next_words.py` (standard library only) makes them, and its header has the detail.

- **Counts**: [Tatoeba](https://tatoeba.org)'s per-language sentence exports, CC BY 2.0 FR. None of the sentences
  is in the app, only how often one listed word followed another. Each export's date, size and SHA-256 are in
  `tool/next_words_sources.tsv`; the contributors are credited in `assets/ngram/CREDITS.txt` and in Settings › About.
- **Vocabulary**: only words in the keyboard's word lists (wordfreq, CC BY-SA 4.0).
- **Filter**: words on the LDNOOBW list (CC BY 4.0) are left out. The list is not in the app.
- **Not used**: Peter Norvig's `count_2w.txt`. Its page gives a licence for the code; the counts derive from the
  Google Web Trillion Word Corpus, distributed by the LDC under its own agreement, and no licence is stated for them.

Tatoeba reads like a phrasebook and loves a few names, so each word's counts are scaled by how much more often
Tatoeba uses it than wordfreq says people do; "Tom", "Mary" and "Boston" go without a list of names per language.

### How it is measured

`tool/next_word_eval.sh` types the held-out sentences (written to `build/ngram-eval/` by the generator) through the
keyboard's own `Scorer` and `Autocorrect.suggest`: how often the next word is in the row before a letter is typed,
and the share of keystrokes saved by tapping a word in the row as soon as it is one of the first three. English,
20,000 held-out words, on 2026-11-10:

| | Top one | Top three | Keystrokes saved |
| --- | ---: | ---: | ---: |
| One-word tables (100,000 pairs, 48 followers) | 14.0% | 25.5% | 45.3% |
| Two-word rows, larger tables, context weighed 40 | 20.0% | 33.7% | 50.2% |

Learning on these sentences as if one person typed them all lowers the numbers a little (they are many people's,
which the starter table already holds), so the personal blend was left as it was; it is for one person's habits.

### Which languages have one

A table ships only if, on every 20th sentence held out from the counting, its top three were right at least 8
points more often than always offering the language's three commonest words, and it has at least 5,000 pairs.
**These numbers are measured on Tatoeba's own sentences and flatter real messages**; nothing has been measured on
anyone's real typing. Made from the exports of 2026-11-07, with the first (one-word) tables; the pairs each table
keeps now are in `assets/ngram/NOTICE.txt`:

| Language | Sentences | Pairs kept | Top one | Top three | Three commonest words | Starter table |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| English (`en`) | 2,040,567 | 100,000 | 15.9% | 26.9% | 10.6% | yes |
| Russian (`ru`) | 1,235,743 | 50,000 | 14.6% | 24.2% | 10.8% | yes |
| Italian (`it`) | 991,553 | 50,000 | 14.5% | 24.7% | 8.9% | yes |
| German (`de`) | 786,418 | 50,000 | 11.9% | 21.3% | 7.9% | yes |
| Turkish (`tr`) | 750,219 | 50,000 | 12.3% | 20.9% | 9.6% | yes |
| French (`fr`) | 730,079 | 50,000 | 14.4% | 26.1% | 8.4% | yes |
| Portuguese (`pt`) | 445,685 | 50,000 | 13.3% | 24.4% | 9.8% | yes |
| Hungarian (`hu`) | 443,774 | 46,696 | 13.1% | 23.1% | 15.7% | no: 7.4 points over the commonest words, under 8 |
| Spanish (`es`) | 443,166 | 50,000 | 13.5% | 23.5% | 9.8% | yes |
| Hebrew (`he`) | 212,926 | 35,000 | 13.9% | 22.4% | 9.2% | yes |
| Dutch (`nl`) | 201,505 | 35,000 | 13.7% | 24.4% | 10.4% | yes |
| Ukrainian (`uk`) | 188,941 | 33,618 | 16.0% | 25.6% | 13.7% | yes |
| Lithuanian (`lt`) | 159,332 | 32,282 | 9.9% | 21.3% | 9.5% | yes |
| Finnish (`fi`) | 155,018 | 35,000 | 12.5% | 21.7% | 7.9% | yes |
| Polish (`pl`) | 137,212 | 33,879 | 12.5% | 21.0% | 8.6% | yes |
| Czech (`cs`) | 90,156 | 23,764 | 12.5% | 20.7% | 9.6% | yes |
| Filipino (Tagalog) (`fil`) | 79,138 | 26,509 | 17.6% | 31.7% | 15.7% | yes |
| Macedonian (`mk`) | 78,260 | 15,011 | 14.7% | 24.4% | 14.0% | yes |
| Arabic (`ar`) | 68,750 | 12,302 | 9.7% | 16.6% | 6.9% | yes |
| Danish (`da`) | 66,554 | 21,252 | 16.0% | 27.4% | 11.2% | yes |
| Croatian, Bosnian, Serbian (Latin) (`sh`) | 60,446 | 18,529 | 14.6% | 24.5% | 13.6% | yes |
| Swedish (`sv`) | 56,869 | 16,302 | 14.9% | 26.6% | 11.6% | yes |
| Greek (`el`) | 43,058 | 13,628 | 13.8% | 24.4% | 9.8% | yes |
| Romanian (`ro`) | 41,914 | 14,895 | 14.4% | 23.2% | 9.3% | yes |
| Slovak (`sk`) | 33,329 | 9,430 | 13.0% | 22.1% | 9.7% | yes |
| Vietnamese (`vi`) | 33,274 | 18,217 | 19.1% | 33.7% | 9.2% | yes |
| Persian (`fa`) | 31,856 | 14,736 | 10.8% | 19.1% | 9.0% | yes |
| Indonesian (`id`) | 28,418 | 9,909 | 12.6% | 22.2% | 7.5% | yes |
| Bulgarian (`bg`) | 25,721 | 8,638 | 14.8% | 23.2% | 11.9% | yes |
| Latvian (`lv`) | 20,234 | 6,169 | 12.9% | 21.7% | 10.9% | yes |
| Norwegian (`nb`) | 18,898 | 7,847 | 14.8% | 24.6% | 10.6% | yes |
| Icelandic (`is`) | 13,394 | 5,770 | 17.1% | 26.1% | 12.9% | yes |
| Catalan (`ca`) | 10,965 | 5,642 | 10.8% | 18.8% | 8.4% | yes |
| Malay (`ms`) | 10,062 | 5,245 | 11.8% | 21.0% | 9.4% | yes |
| Slovenian (`sl`) | 3,586 | 1,111 | 11.0% | 16.5% | 10.9% | no: under 5,000 pairs |
| Urdu (`ur`) | 2,851 | 2,215 | 14.8% | 25.8% | 11.6% | no: under 5,000 pairs |

Serbian in Cyrillic (`sr`) is the Serbo-Croatian table transliterated (18,500 pairs after dropping words its list
lacks); it was not measured separately.

**Hungarian, Slovenian and Urdu have no starter table.** They predict from the person's own typing and word
frequency only, which means nothing is offered after a space until pairs have been typed twice. **Korean** has no
word list, so it has no prediction at all, like the plain ABC layout.

Together the tables are 11.3 MB as files and about 5.1 MB inside the APK.

## How fast, how big

- A lookup (reading the place in the text, blending, taking the top three) took a median of about 2.4 µs on a desk
  JVM with the real English table (`tool/next_word_check.sh` prints it), and a median of 90 to 140 µs on the
  arm64 emulator, the slowest about 0.46 ms, over a few hundred lookups. A debug build logs "next-word lookup took"
  for any over a millisecond. Not measured on a phone.
- The English table (100,000 pairs) added about 1.9 MB to the keyboard's heap on the emulator; six tables at once
  (the most that can be on) about 7.7 MB.
- A full learned file (27,000 pairs after pruning) was about 240 KB with the check's short made-up words; real
  words run longer, and the check holds a full file under 700 KB. The files seen on the emulator were under 1 KB.

## Checks

- `tool/next_word_check.sh`: the tokenizer on `tool/next_words_fixtures.tsv`, the blend, fading and every bound,
  the learned file's round trip, what is learned from typed, swiped and tapped words (each once), from words
  around a backspace, and across the fence that incognito and pasted text leave, every bundled table against its
  word list, context in suggestions and corrections, and lookup time. kotlinc and java only.
- `python3 tool/next_words.py --selftest`: the same fixtures through the generator's tokenizer, so the two cannot
  drift apart.
- `tool/lang_check.sh` still holds autocorrect to its golden with no context given.
- `flutter test test/next_word_settings_test.dart`: the settings card.

## Not built

Context in swipe typing's ranking; a per-app switch (an app's incognito profile stops learning there); forgetting
one pair by long-press; starter tables with two words of context; iOS.
