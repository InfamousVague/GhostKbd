# English's wider word list

Matt, 2026-11-12: "We should have a robust word list either way covering at least all the words in the english
language", and then, told what the sources hold: "We don't need to track obscure archeic words just modern used words
if the extra words will not help our accuracy we can leave them out".

English's first list, `assets/dict/en.tsv`, is wordfreq's commonest 40,000 words. 1.3% of the running words of the
sentences Ghost Keyboard was measured on against Gboard ([versus.md](versus.md)) are not in it, and two thirds of
those came out wrong: typed as meant and changed (yurt to hurt, berber to better, dialled to dialed), or typed with a
slip that could not be mended because the word was no candidate (stammer, cupola, tendonitis).

`assets/dict/en_wide.tsv` is the rest of English as it is written now: **87,488 words** that en.tsv lacks, each with
its Zipf frequency and what vouches for it. `Autocorrect.widen` reads it wherever English is loaded. en.tsv is never
rewritten and its frequencies win.

    python3 tool/wide_words.py            # makes the list again, says what is in it, writes its notice
    python3 tool/wide_words.py --check    # writes nothing: fails if the bundled list or the notice would change
    python3 tool/wide_words.py --word yurt
    sh tool/versus/unit.sh WideWordsTest  # the list as the keyboard reads it

Everything below was measured on the JVM with the app's own decoder (`tool/versus/scoreboard.py`,
`tool/typing_bench.sh`); nothing was typed on a phone or an emulator.

## What it does

Words left wrong, of the two sets `tool/versus/scoreboard.py` types (the 900 typings of the run against Gboard, and
900 typings of 300 other held-out sentences), by thumb:

| | careful | leaning | sloppy | all | Right words changed |
| --- | ---: | ---: | ---: | --- | ---: |
| seen, en.tsv alone | 2.48% | 5.42% | 10.89% | 6.26% (326 of 5,205) | 10 |
| seen, with the wider list | 2.07% | 4.84% | 10.66% | 5.86% (305): 23 mended, 2 broken | 2 |
| Gboard, the same taps | 1.84% | 3.23% | 6.40% | 3.82% (199) | |
| unseen, en.tsv alone | 3.28% | 5.01% | 12.17% | 6.82% (437 of 6,408) | 7 |
| unseen, with the wider list | 2.81% | 4.63% | 11.89% | 6.45% (413): 25 mended, 1 broken | 1 |

Words Gboard got and Ghost Keyboard did not: 184, where there were 201 (the other way, 78 for 74). The list could
never have been worth much more than this there: 66 of the seen set's running words are not in en.tsv and 42 of
them were wrong. The list has 54 of the 66, and 24 are still wrong (of the unseen set's 87, 60 were wrong and 39
are; the list has 72). What is left is a word of the list typed with a slip that its frequency cannot carry past
what letters no list has are worth ("cupila" stays, for cupola at Zipf 2.68; see [below](#what-is-still-in-no-list)),
and the names no source has.

The typing bench (`tool/typing_bench.sh taps`, 12,000 words), which has what those sentences lack: right words a
thumb might spoil, and words no list has typed on purpose. It swaps 3% of its words for such words and leaves out
the ones the lists know, so a longer list changes which sentences it types; both rows below were typed with the 44
of its 83 words that neither list knows (`-Dunknown`), on the same sentences and the same taps:

| | Words right: careful, normal, sloppy | Right words spoiled, in a thousand | as a count | Unknown words spoiled | Misspellings mended |
| --- | --- | --- | --- | --- | ---: |
| en.tsv alone | 96.45%, 92.57%, 85.65% | 1.42, 1.61, 1.62 | 14, 12, 8 | 2.3%, 3.1%, 3.5% | 232 of 264 |
| with the wider list | 96.56%, 92.73%, 85.74% | 1.11, 1.34, 1.42 | 11, 10, 7 | 2.3%, 3.1%, 3.5% | 232 of 264 |

(As the bench prints it for en.tsv alone, with all 61 words that list does not know: 96.41%, 92.34% and 85.65%
right; 1.22, 1.48 and 1.65 spoiled in a thousand; 1.5%, 4.3% and 7.1% of unknown words spoiled. Those are other
sentences, and not to be read against the row above.)

Swipe typing (`tool/typing_bench.sh swipe`, 6,005 strokes): the first reading is right 96.13%, 94.88% and 87.75% of
the time for the bench's neat, normal and sloppy hands, where it was 94.58%, 93.45% and 86.68%. Of 400 phrases
drawn in one stroke, 80.0%, 76.5% and 62.5% are read right at first, where 81.0%, 76.8% and 62.0% were: four and
one fewer, two more.

The same rules hold wherever else English is loaded; no other language's list or answers change
(`tool/lang_check.sh`: every other language's rows are what they were).

## Which words

A word belongs if it is in use now and is a word. Two sources say those two things, and neither says both:

- **wordfreq 3.1.1's whole English list** (321,180 entries; 307,583 of plain letters) says what is written, and how
  often. It is not evidence that a string is right: definately (Zipf 2.87), occured, recieve and teh are in it beside
  names, places and newer words.
- **The English Speller Database** (ESDB, formerly SCOWL; release 2026.02.25) says which strings are words: the
  vetted lists spelling checkers are made from. Every entry has a size (the smaller the commoner: 60 is the default
  spelling dictionary, and the largest its maker is fairly sure holds no misspellings; 70 the large one, with a few
  errors allowed for; 80, in its README's words, "a valid word in current usage"), its inflected forms, its
  spellings by country, and marks for variants and for uncommon or archaic forms. It says nothing of how much a word
  is used: detruncated, lipomata and feretory are in it.

The list starts where the two meet, and every edge was settled by putting the words in and taking them out
([the table](#each-kind-in-and-out)).

| Mark | Kind | Words | Share of held-out running words | For example |
| --- | --- | ---: | ---: | --- |
| `d` | In the database at size 60 or under, in a standard spelling of American, British (-ise and -ize), Canadian or Australian English or a common variant of one, and written | 41,213 | 0.419% | barnacle, callused, cleat, percolation, smallholding, suburbanite, underhand |
| `dn` | The same, written there with a capital and never without: a name | 4,333 | 0.088% | berber, betelgeuse, capulet, kaaba, scarlatti |
| `l` | Only in its larger sizes (65 to 80), or a less common variant, and written | 24,886 | 0.042% | corvid, hagfish, mixups, quetzal, recharger, rosehips, stepmum |
| `ln` | The same, a name | 8,814 | 0.033% | boynton, hanukah, snowdonia, summerville, tuaregs |
| `w` | Only wordfreq has it, at Zipf 2.0 or more, and it passes the tests below: a name, a place, a newer word | 8,242 | 0.193% | chikungunya, cyberbullying, kitesurfing, ludovic, okafor, passcode, polisario, quadcopter |
| | all | 87,488 | 0.774% | |

The share is of the 688,431 running words of the first 90,000 held-out Tatoeba sentences
(`build/typing-bench/en-heldout.txt`; `tool/versus` types from line 90,001 on, and no list was shaped by any of them).
en.tsv has 98.257% of them. With the wider list 99.031% are words of the lists, and 0.137% more are the possessive
of one (see [below](#a-possessive-is-known-by-its-word)).

The list also says, of en.tsv's own 17,653 words under Zipf 3.2, which 13,624 the database vouches for at `d`
(devise, fable, dismisses); the other 4,029 are as doubtful as they were (definately, occured, kira).

What was left out, and why:

- **Words only the database has** (109,000 to size 80). No writing that wordfreq counted shows them once in a hundred
  million words. Put in to size 60, 70 or 80 with an estimated frequency (`--unwritten`), 13,000, 50,000 and 109,000
  words mended nothing on either set and nothing on the bench. By Matt's word they are out.
- **Inflected forms only the database has** (`--forms`: 28,025 plurals and verb forms of listed words that wordfreq
  never saw). They cover 0.003% of running words and changed nothing. Out. The forms people do write are in: a
  listed word's plural, its verb forms and both of its spellings are words wherever wordfreq shows them at all
  (dialled and dialed, favour and favor, sceptical and skeptical).
- **Size 85 and over** (6,093 written words). Its words come from a list whose notice must be shown prominently
  wherever it is used; the list never takes a word on that evidence. One that is written often enough comes in as
  `w` on wordfreq's evidence alone.
- **Archaic forms and spellings** (the database's `@`: canst, didst, spake), abbreviations, prefixes, suffixes,
  Roman numerals and acronyms written in capitals (610), words it marks offensive or as the coarsest vulgarities
  (19), and words on the LDNOOBW list (54), which the starter tables leave out too. Such a word is what it was
  before: letters no list has.
- **Possessives** (8,730 that are written). The keyboard knows one by its word.
- **Written-only words that may be a misspelling.** wordfreq's tail is names mixed with misspellings and scraps. A
  word only it has is kept where it is four letters or more with a vowel, is not stretched (sooooo) or a Roman
  numeral, is not two common words run together (atleast, ofhis: 210), and no commoner word of the lists is a slip
  away: one slip to a word ten times as common, or two slips to one a hundred times as common from six letters
  (8,735: awsome, tommorow, wierd, and names that sit beside a common word, such as eamon and vasil). Of the 41
  misspellings in the bench's list that wordfreq has at Zipf 2.0 or more, none gets through. Without the test the
  bench mends 220 of its 264 misspelled words where it mends 232 with it: the misspellings had become words.
- **Words Tatoeba's sentences use** (`--more`, the closed `versus-words` track's en_more.tsv). It adds 422 words to
  this list and 0.615% of the held-out running words, nearly all of that the names Tatoeba's own stories are told
  about (Ziri, Yanni, Rima, Skura, Mennad). On the two sets and the bench it changes nothing. It is a list of the
  measure's own kind of text, and is left out; the option is there.

A word's frequency is wordfreq's, never more than 2.71 (en.tsv's least is 2.72: wordfreq left the word out of its
commonest 40,000).

### Reading the database

The tool reads `data/scowl-pre.txt` and the files its own build merges into it or adjusts it by, as text, by the
grammar of its README. Nothing in that folder is run. What it follows of the build (`combine.py`): the merged files
(basic, extra, signature, coca, coca_llm) as more lines of the same grammar; `exclude`'s raised sizes (calender and
irregardless are at 70, not 60); and from `variants`, `fixes`, `compounds` and `compounds-auto`, the variant levels
they give a lemma, the lemmas they remove and the overrides they add. Not followed: their changes to derived forms,
to parts of speech and to which group a word belongs, which the list does not go by. The database's own lists could
not be made to compare with. 275,707 lines in 211,786 groups were read and none was refused.

## What a word of it is for

| | Which words | Why |
| --- | --- | --- |
| Known when typed | all 87,488 | A word is a word. |
| A completion in the row | all | They rank under every word of en.tsv, so they show where few commoner words begin that way ("yur" offers yurt), and are looked through only where the first lists have fewer than eight to offer. |
| A reading of a slip (in the trie) | the 79,246 a dictionary vouches for | Left out of the trie they would still be known. Read, they are what mends 10 more of the seen set's words and 17 more of the unseen set's (305 and 413 wrong, where 315 and 430 are with none read). |
| A word a stroke may be | the 29,621 of those at Zipf 2.0 or more | A word's shape is 144 bytes of heap. These lift the first reading of the bench's strokes by 1.1 to 1.6 points; the other 49,625 would lift it 0.2 to 0.4 more for 7 MB ([the budget](#the-budget)). |
| A reading where the taps are not known | none | With no taps a slip is read as a word of the first lists only, as it always was: with the wider list's words read there too, more right words are changed than slips are mended ([below](#where-the-taps-are-not-known)). |

### A word a dictionary vouches for stands

Before, a listed word under Zipf 3.2 gave way to any word a hundred times as common that was a cheap slip away,
whatever that left it worth. That rule was made for a list in which a rare word is as often a misspelling as a word,
and it stays for the words no dictionary vouches for (`l`, `w`, and en.tsv's own 4,029). An `l` or `w` word that
only the wider list has is also held at the bar of letters no list has (`WIDE_HOLD`): it was such letters before,
and knowing it never makes it weaker than it was then ([Answering the review](#answering-the-review-2026-11-13)).

A `d` word is a word. Typed by its taps, it is one of the readings, worth its own frequency, and gives way only to a
reading worth `SURE_MARGIN` more: "yurt" on its own keys is "yurt", "dialled" is not "dialed", and "vases" with its v
on the line to c is "cases".

`SURE_MARGIN` is 1.3. It was set on 60,000 of the typing bench's words, typed on the same sentences each time, by
the bench's own measure (words right, less four for every right word spoiled, the mean of its three thumbs, and
three points for every misspelling mended):

| | Measure | Right words spoiled, of 60,000 words: careful, normal, sloppy |
| --- | ---: | --- |
| en.tsv alone | 93.537 | 90, 87, 58 |
| the list, a vetted word as doubtful as any (`SURE_STANDS` 0) | 93.645 | 80, 82, 56 |
| margin 0.6 | 93.679 | 81, 81, 62 |
| 1.0 | 93.700 | 75, 76, 59 |
| 1.15 | 93.707 | 73, 74, 58 |
| **1.3** | **93.726** | 70, 72, 53 |
| 1.45 | 93.720 | 70, 72, 53 |
| 1.6 | 93.715 | 70, 72, 52 |
| 2.0 | 93.690 | 69, 71, 52 |

Read only as slips of the thumb, as a common word is, such a word did no better at any margin tried, and the most
its slips may cost changes nothing between 2.2 and 4.4, so it has the budget every listed word has.

On the two sets the margin trades a right word that stands for a slip that spells one. Against 0.6, at 1.3 "yurt",
"trill" and "dismisses" typed as meant are no longer changed (to hurt, troll, dismissed), and "tet" for yet, "dour"
for sour, "maris" for maria and "vane" for came are left as typed: three gained and four lost over the two sets, and
29 fewer right words spoiled by the bench's three thumbs (224 to 195).

Size 60 is where the line belongs. With the mark given to size 70 as well, or to everything, "calender" becomes a
word that stands, the bench mends 229 of its 264 misspelled words where it mends 232, and both sets get worse (306
and 413 of the seen and unseen sets' words wrong; 307 and 416). Names held as doubtful instead: worse too (307 seen).

### Where the taps are not known

A word the cursor was moved into, text from a hardware keyboard, and everything typed while a list beyond the first
six is loaded are corrected without taps, by which keys are neighbours. Where a list beyond the first six is loaded
(a cautious lexicon) the wider list is never read in, so English there is exactly as it was with en.tsv alone
(`WideWordsTest.aCautiousListLeavesEnglishAsItWas`). Where English is loaded alone, or with the first six, the path
without taps is nearly, not exactly, as it was: a word of the wider list is a known rare word there rather than
letters no list has, and possessives are known by their word. Measured on the bench's 12,000 words typed on the
same sentences (with context): words right 93.61%, 85.19%, 71.65% with en.tsv alone and 93.63%, 85.15%, 71.54%
with the list, right words spoiled 5.47, 5.09, 4.67 in a thousand and 4.66, 4.42, 3.85. And
`tool/lang_check.sh` still holds it to its recorded answers: of English's 2,073 golden rows 1,649 are the same, and
every other but one that differed already is a word of the wider list joining the row as a completion (246), being
typed (24), or read from a stroke (153); with the file moved away 2,072 are the same, as before there was one. On
60,000 of the bench's words typed without taps, its three thumbs leave 93.41%, 84.96% and 71.37% of words right
where en.tsv alone leaves 93.37%, 84.99% and 71.46%, and spoil 5.30, 5.04 and 4.61 right words in a thousand for
6.17, 5.82 and 5.23. Two things were tried there and left off, each a dial:

- **The wider list's words as readings** (`WIDE_UNTAPPED`). On the bench's lines typed without taps (24,000 words,
  its normal thumb) they mend 66.8% of slips for 66.6%, and for that change 20.8% of the right words no list has
  where 18.7% are changed without them, and spoil 5.05 right words in a thousand for 4.58. A rare word a neighbouring
  key away from letters no list has is seldom what was meant, and nothing but the taps says when it was.
- **A vetted word never changed** (`SURE_UNTAPPED`). On the same lines that leaves 84.70% of words right and spoils
  4.58 right words in a thousand, where holding it as every rare word always was (it gives way to a word a hundred
  times as common one cheap slip away) leaves 85.18% and spoils 4.78, and en.tsv alone 85.21% and 5.39. With no
  taps, letters that spell a rare word are a slip of the commoner word a key away far more often than they are the
  rare word. So "yurt" with no taps to go by is still "hurt", as it was. Where the cursor was moved into a word the
  person may well have meant its letters, and there this is the wrong way round; nothing measures that, and it is
  no worse than it was.

### A possessive is known by its word

0.20% of running words are possessives no list has (Sami's, Algeria's, the plan's), and for two thirds of them the
lists have the word. Typed apostrophe and all, such a word was letters no list has, and its apostrophe was read as a
tap too many: "plan's" came out "plans". Now a possessive no list has is its word with 's after it: the word is
judged as it would be alone, by its own taps, and what it is left as or mended to keeps the 's. Where the word is no
word either ("plsn's") the letters are read whole first, as they always were, and that reading is taken where it has
an apostrophe of its own (the lists have possessives too: "tem's" is "tom's"); where it has none, the typed
apostrophe is kept if the word can be mended ("plan's", not "plans"). `isWord` says a possessive of a listed word
is a word.

On the same 60,000 words of the bench, without the rule its three thumbs leave 96.47%, 92.44% and 85.61% of words
right and spoil 72, 73 and 53 right words (plan's, champion's and hour's among them); with it 96.47%, 92.46% and
85.64%, and 70, 72 and 53. The two sets have no possessives (their thumb types none), so only the bench sees this.

## The budget

For a mid-range phone, the wider list may cost no more than as much again as English costs today, and nothing the
first words typed can feel:

| | Budget | Measured |
| --- | --- | --- |
| Heap, English's lists with swipe's shapes | no more than twice today's | 14.2 MB today (9.0 without swipe's shapes); 28.0 MB with the list (18.8) |
| Heap while the list is read | not budgeted before the review; stated here | the first lists stay in use while the wider one is read, so the old trie, the file's words, the merge's arrays and the new trie are alive together: English's lists load in a 9 MB heap with en.tsv alone and need 25 MB with the list (the smallest `-Xmx` that loads them, serial collector, without swipe's shapes; `build/review4/Peak.kt`). Each English lexicon kept for the globe key is widened on its own, once it is switched to, and shares nothing with another: about 18.8 MB each with the list where it was 9.0 |
| Reading it | on the loader's thread, after en.tsv is in use; no longer than en.tsv takes twice over | en.tsv 33 ms, both 61 ms, on this Mac's JVM, warmed |
| In the APK | under 0.7 MB | 533,388 bytes deflated (1,681,713 as a file; en.tsv is 185,629 and 525,088) |
| A word's end on the main thread | no more than a fifth longer | 61, 96 and 139 µs a word for the bench's three thumbs with en.tsv alone; 70, 110 and 160 with the list: 15% (the least of three rounds of 24,000 words each, on a quiet Mac, before possessives). On the finished tree, by processor time, three interleaved rounds of 12,000 words at load 8 to 10: medians 117, 154, 232 µs with `WIDE=0` and 139, 171, 272 with the list (+19%, +11%, +17%), but the rounds swung by as much (109 to 160 and 139 to 158 careful), so this is in line with the budget, not proof of it |
| A key press's row on the main thread | no longer than it was | the wider words are looked through for completions only where the first lists have fewer than eight; looked through at every key press, a word's first letter took 167 µs for 107 (`build/wide/heap/RowTime.kt`, 39,305 key presses), and now the two cannot be told apart on a busy Mac (63 to 100 µs each) |

The heap is the JVM's on a Mac (`build/wide/heap/Heap2.kt`, a scratch probe: used heap after a collection, before
and after `loadNow`), not a phone's. Android keeps a string of plain letters at a byte a letter with a smaller
header, so the strings, which are a third of the cost here, are about half of that there.

What a word costs, as the keyboard already holds its words:

- **Known** (a `String`, its place in the sorted array, its frequency, its mark): about 62 bytes here. The first
  list's words are also entries of a hash map (about 48 bytes more); the wider list's are not: `Listed` finds them by
  halving the sorted array, which holds both. 5.4 MB for all of them.
- **A reading of a slip**: 2.5 trie nodes of 22 bytes. 4.3 MB for the 79,246.
- **A word a stroke may be**: its shape, about 144 bytes. 4.1 MB for the 29,621; all 79,246 would be 11.3 MB, and
  that is where the budget bites.

What each choice was measured to cost and to buy (heap as above, with swipe's shapes; words wrong of the seen set's
5,205 and the unseen set's 6,408; the first reading of the bench's 6,005 strokes right, for its neat, normal and
sloppy hands):

| | Heap | Seen, unseen | Strokes read right at first |
| --- | ---: | --- | --- |
| en.tsv alone | 14.2 MB | 326, 437 | 94.58%, 93.45%, 86.68% |
| the list known only: none read, none swiped | 19.6 MB | 315, 430 | the same |
| read from Zipf 2.0, swiped from 2.0 | 25.3 MB | 307, 414 | 96.13%, 94.88%, 87.75% |
| read from 1.5, swiped from 2.0 | 26.6 MB | 305, 414 | the same |
| **all read, swiped from 2.0** | **28.0 MB** | **305, 413** | **96.13%, 94.88%, 87.75%** |
| all read, swiped from 2.3 | 26.1 MB | 305, 413 | 95.88%, 94.65%, 87.59% |
| all read, swiped from 1.5 | 31.5 MB | 305, 413 | 96.18%, 94.91%, 87.68% |
| all read, all swiped | 35.2 MB | 305, 413 | 96.52%, 95.25%, 87.91% |

Every dictionary word is read: the rarest 25,000 cost 1.4 MB of trie and one word of the unseen set, which is no
evidence either way, and a word that is known and cannot be what a slip was meant to be is a strange thing to
explain. Swiping stops at Zipf 2.0 because that is where the budget is spent. The strokes are Tatoeba's sentences
too, and part of what the rarer words add there is its own names. A stroke takes 0.6, 1.3 and 2.7 ms to read with
the list where it took 0.7, 1.1 and 2.2 (all swiped: 0.6, 1.7 and 3.7).

The form was kept: a sorted array of strings, a trie over it, and swipe's index. A sorted pool of characters in
place of the strings would save about 3.8 MB of the 5.4 here and about half that on a phone, and every reader of
`Lexicon.sorted` (the tap decoder, the joins, the row's completions) would have to change with it. The numbers do
not ask for it.

The list is read second. `loadWanted` puts en.tsv into use as it always did, tells `onLoaded`, then reads the wider
list into a new lexicon and tells `onLoaded` again: the first words typed wait no longer for their corrections than
they did. A file that is missing or cannot be read leaves English exactly as it was: with `Autocorrect.WIDE` at 0
the scoreboard's every word, on both sets, is the base's, and so are the bench's lines.

## Each kind, in and out

One thing changed at a time, each measured as the list as it is was (before `WIDE_HOLD`, which puts one more word of the seen set wrong: 306). Words wrong on the seen set (5,205 words; 326
with en.tsv alone) and the unseen set (6,408; 437), with words mended and broken against en.tsv alone; the bench's
misspelled words mended, of 264 (232 with en.tsv alone); and the bench's 12,000 words typed on the same sentences as
en.tsv alone was, for its careful, normal and sloppy thumbs: words right, in points, and right words spoiled, as a
count, each against en.tsv alone. The bench swaps 3% of its words for words no list has, and a longer list knows
some of those (61 of its 83 are unknown to en.tsv), so both were typed with the words the list in question does not
know: the last column says how many.

| | Words added | Seen | Unseen | Misspellings | Words right | Right words spoiled | Unknown words |
| --- | ---: | --- | --- | ---: | --- | --- | ---: |
| **the list as it is** | 87,488 | 305 (+23, -2) | 413 (+25, -1) | 232 | +0.11 +0.16 +0.09 | -3 -2 -1 | 44 |
| the database to size 60 only | 58,227 | 309 (+19, -2) | 417 (+21, -1) | 232 | +0.11 +0.16 +0.09 | -3 -2 -1 | 44 |
| to size 70 | 72,718 | 307 (+21, -2) | 414 (+24, -1) | 232 | +0.10 +0.15 +0.09 | -3 -2 -1 | 44 |
| its words from Zipf 1.5 only | 62,031 | 305 (+23, -2) | 414 (+24, -1) | 232 | +0.11 +0.16 +0.09 | -3 -2 -1 | 44 |
| from Zipf 2.0 only | 37,876 | 309 (+19, -2) | 416 (+22, -1) | 232 | +0.11 +0.10 +0.14 | -7 -5 -1 | 45 |
| no names (`dn`, `ln`) | 78,053 | 312 (+16, -2) | 418 (+20, -1) | 232 | +0.12 +0.16 +0.09 | -3 -2 -1 | 44 |
| no written-only words | 79,246 | 305 (+23, -2) | 413 (+25, -1) | 232 | +0.10 +0.14 +0.07 | -6 -4 -2 | 58 |
| written-only from Zipf 1.5 | 105,103 | 305 (+23, -2) | 413 (+25, -1) | 232 | +0.08 +0.19 +0.09 | -5 -2 -2 | 42 |
| written-only, none tested for a commoner word beside it | 96,433 | 305 (+23, -2) | 415 (+25, -3) | 220 | +0.09 +0.05 +0.10 | -4 -1 +1 | 42 |
| with the forms only the database has | 115,513 | 305 (+23, -2) | 413 (+25, -1) | 232 | +0.11 +0.16 +0.09 | -3 -2 -1 | 44 |
| with every database word to size 70 | 137,015 | 305 (+23, -2) | 413 (+25, -1) | 232 | +0.07 +0.13 +0.08 | -7 -2 -2 | 43 |
| with Tatoeba's words | 87,910 | 305 (+23, -2) | 413 (+25, -1) | 232 | +0.11 +0.16 +0.09 | -3 -2 -1 | 44 |
| none of its words read as a slip | 87,488 | 315 (+13, -2) | 430 (+8, -1) | 232 | +0.03 +0.07 +0.04 | -3 -2 -1 | 44 |
| read from Zipf 2.0 only | 87,488 | 307 (+21, -2) | 414 (+24, -1) | 232 | +0.10 +0.16 +0.09 | -3 -2 -1 | 44 |
| read from Zipf 1.5 only | 87,488 | 305 (+23, -2) | 414 (+24, -1) | 232 | +0.11 +0.16 +0.09 | -3 -2 -1 | 44 |
| written-only words read too | 87,488 | 304 (+24, -2) | 413 (+25, -1) | 232 | +0.11 +0.17 +0.11 | -3 -2 -1 | 44 |
| a vetted word as doubtful as any | 87,488 | 307 (+20, -1) | 416 (+21, -0) | 232 | +0.10 +0.08 +0.07 | -2 -1 -1 | 44 |
| possessives not known by their word | 87,488 | 305 (+23, -2) | 413 (+25, -1) | 232 | +0.08 +0.12 +0.07 | -1 -1 +0 | 44 |

- **The database's sizes.** 60 to 70 is two words of the seen set, three of the unseen and 0.047% of running words
  for 14,491 words; 70 to 80 is two of the seen set's, one of the unseen set's and 0.013% for 14,770. The seen
  set's two are coffered and Tuaregs, which that set has because Gboard got them. Size 80 is kept on its maker's
  word that these are words in use and wordfreq's that they are written (corvid, hagfish, mixups, recharger,
  rosehips), not on what three words measure.
- **Names** are the kind most worth having: 7 words of the seen set and 5 of the unseen. (The row takes the 13,147
  out as names; 3,712 of them come back as written-only words, known and not read, so it understates them.)
- **Written-only words** change neither set, since no slip is read as one: what they buy is that 14 of the bench's 58
  remaining unknown words (okafor, zoltan, kwame, grubhub) are known and left alone, as 0.19% of running words are.
  From Zipf 1.5 there would be 17,615 more for 0.016% of running words.
- **Rarer database words** (Zipf 1.0 to 1.5: 25,457 words for 0.039% of running words, one word of the unseen set)
  are the list's thinnest part by measurement. They are kept because a dictionary and writing both vouch for them,
  and `WIDE_LEAST` 1.5 takes them out again.
- The two rows that spoil fewest right words on the bench (Zipf 2.0 only, no written-only words) were typed on other
  sentences than the rest, and are not to be read against them.

## What is still in no list

0.832% of the held-out running words (5,727 of 688,431; 905 different words), where it was 1.743%. Ten words are
0.609 points of that: the names Tatoeba's stories are told about, and their possessives (ziri ×1964, rima ×902,
skura ×380, mennad ×331, rima's, fadil, ziri's, kabylie, baya, damiano). The other 895 are 0.223%, and 793 of them
occur once (a sample of the 895: aisen, callsign, deindustrialized, freegans, hokkien, nocebo, semiprime, soonish,
there've). On the
two sets the keyboard is scored on, 12 of the seen set's 5,205 running words are in no list (adrar, amenas, frah,
yennayer) and 15 of the unseen set's 6,408: 0.23% each, where 1.27% and 1.36% were not in en.tsv.

So letters no list has are right words about a fifth as often as they were on those two sets, and half as often on
Tatoeba's sentences at large, with their names. What they are worth against a reading
(`Autocorrect.unlisted`: UNKNOWN, UNKNOWN_ODD, UNKNOWN_LONG) was set when one word in sixty was unlisted, and was not
changed here: that is the letter model's to say.

What lowering them would do was measured for whoever sets them next (`scoreboard.py --set`; nothing was changed):

| `UNKNOWN`, `UNKNOWN_ODD`, `UNKNOWN_LONG` | Seen | Unseen | Misspellings mended | Bench: words right | Right words spoiled | Unknown words spoiled |
| --- | --- | --- | ---: | --- | --- | --- |
| 3.2, 1.8, 1.6, as they are | 305 | 413 | 232 | 96.56%, 92.73%, 85.74% | 11, 10, 7 | 2.3%, 3.1%, 3.5% |
| 2.9, 1.5, 1.3 | 288 | 388 | 241 | 96.57%, 92.92%, 86.20% | 21, 16, 11 | 6.3%, 6.8%, 8.2% |
| 2.6, 1.2, 1.0 | 273 | 370 | 247 | 96.61%, 93.11%, 86.49% | 26, 18, 14 | 7.8%, 8.0%, 11.8% |

17 and 25 more words right on the two sets for three tenths, paid for in the bench's unknown words: nearly all of the
extra spoiling is three of its 44 ("mattv" to matt 18 times, "gyatt" to hyatt, "zsh" to ssh). The bench makes one
word in 33 a word no list has; with this list, text has one in 120 to one in 430.

## Answering the review (2026-11-13)

A review of the finished track reproduced its numbers and raised nine points. What was changed, and measured:

- **The bench, like for like.** The bench filters its unknown words by `isWord`, so the list's run and en.tsv's
  typed different sentences. Both typed with the 51 unknown words neither list has (`-Dunknown`), en.tsv alone
  (`WIDE=0`) leaves 96.45%, 92.57%, 85.65% right and spoils 1.42, 1.61, 1.62 right words in a thousand; the tree
  96.55%, 92.72%, 85.74% and 1.11, 1.34, 1.42. Unknown words spoiled are 2.3%, 3.1%, 3.5% both ways. That is the
  bench's before and after.
- **A cautious lexicon is never widened.** The path without taps was said to be "the same"; it was not. Now a
  lexicon with a list beyond the first six never reads the wider list, and is exactly as before (a test holds it).
  English alone without taps is nearly the same, measured in [Where the taps are not known](#where-the-taps-are-not-known).
- **A name only writing vouches for is held no less than it was unlisted** (`WIDE_HOLD`). As a rare listed word
  that is not `SURE`, "piotr" gave way to "poor" (any word a hundred times commoner a cheap slip away, whatever
  that left it worth), where unlisted it had stood. Now an `l` or `w` word only the wider list has gives way only
  to a reading that passes both that rule and the bar of letters no list has. Seen: 306 where 305 (hymie, which the
  rare-word rule made "humor", stands, as it did unlisted); unseen 413 either way; the bench like for like 96.55%,
  92.72%, 85.74% where 96.56%, 92.73%, 85.74%, spoils unchanged. The bench's 32 unknown words that the lists know,
  typed with its filter taken off (a copy of Bench.kt, `-Dnofilter`): careful unknown words spoiled 5.0% → 4.6%
  of 262, right words spoiled 1.82 → 1.72 in a thousand, piotr → poor gone; normal and sloppy -0.02 and -0.01
  right, unknown words spoiled unchanged. Held exactly as unlisted letters instead (no rare-word rule), the unseen
  set had two words more wrong (incas → a, as), so the stricter of the two bars is the one kept.
- **The loader**: it gives up every 4,096 lines when another lexicon is wanted, not only after the whole file;
  `loadNow` forgets only its own key's place among those to be widened; a line with two apostrophes is not taken;
  the bit set's size is the dial `WIDE_BITS`; `theFirstListIsInUseBeforeTheWiderOneIsRead` waits for its second
  `onLoaded` with a deadline instead of sleeping 50 ms.
- **The heap while the list is read** and the cost of several English lexicons are now in [the budget](#the-budget).
  The narrow lexicon's trie cannot be let go before the wide one is made: it is in use while the list is read.
- **Outside the tap path.** `Reports.known` is `isWord`, so a typed word of the wider list that the person put
  right by hand is counted as `mistakes.unlisted` and never reported as a pair: fewer pairs leave the phone, none
  more. Mockingbird's sample cleaner reads the wider list through `loadNow`, so "senor" is no longer cleaned to
  "senior" (senor is a `d` word). The person's own words are those typed three times or more (`OWN_WORD_COUNT`
  2.5 in NextWords), as `isListed`'s comment says; the 2 in `personalZipf` is next-word prediction's weighting of a
  known word, another thing.
- **Parts of other tracks' code.** The diff touches the row's completions (`suggest`), `pick`, `runs()` and swipe's
  `ends()`/`zipf()` and `WordTrie`. Each reads the first lists alone or behaves as before with `WIDE=0` or without
  the file; they are where a merge with the row, letters and swipe tracks needs care (`runs()` and `unlisted()`
  above all, after letters).

## Not done, and not sure of

- **Nothing was typed on a phone.** Every number is the JVM's, and the heap and the times are a Mac's. The list is
  read by the same code on a phone, but how long 1.7 MB of text takes to read there, and what 14 MB more heap does to
  a mid-range phone's keyboard, were not measured.
- **The sentences are Tatoeba's**, all three measures. Its names are in the lists or not for reasons of their own,
  and the share of words in no list is a different number on other text.
- **The written-only words were not read by a person.** 8,242 of wordfreq's words passed tests made to keep
  misspellings out; a sample of 480 was looked over (names, places, brands, newer words, a few acronyms in lower
  case and foreign words: nunca, cinq, wissenschaft; one misspelling, nickleback). The two thresholds of the test
  for a commoner word beside it were taken as found and held against the bench's 41 misspellings that wordfreq
  has, which is one list of misspellings.
- **The margin of 1.3 is the bench's**, tuned on its thumb. The two sets are indifferent to it.
- **The time a word's end takes** was last taken on a quiet Mac before possessives were known by their word and
  before the row's completions were changed; after that the Mac's load never fell under 35, and four more rounds
  said nothing either way. A bit set of the wider words' hashes (128 KB of heap) was added last, so that letters
  which are no word of it skip the binary search; its readings are the same to the word (seen, unseen and 24,000
  bench words), but four interleaved rounds of the bench timed by processor time (Mac load 15 to 34) could not tell
  it from the tree without it, or either from en.tsv alone: 133 to 187, 160 to 173 and 79 to 160 µs a word for the
  careful thumb. It is kept because it can only take work away; that it does is not measured.
- **A person's own words.** `isListed` does not count the wider list, so a word of it that the person types three
  times is still theirs: never changed, what a slip of it is mended to, swiped. Until then it is the rare word the
  list says it is. That is `WideWordsTest`'s to hold; no measure here has a person in it.
- **The names track.** The marks say which 13,147 words are names (`dn`, `ln`), and the file is where a capital for
  them could come from. Nothing here uses that.
- **Size.** A tighter form for the words that are only known was not built, because the budget did not need it.
