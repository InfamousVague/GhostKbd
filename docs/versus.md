# Ghost Keyboard against other keyboards

Matt, 2026-11-11: "how can we measure accuracy against gboard and swiftkey to improve ours". Everything measured
before this was Ghost Keyboard against itself ([typing-accuracy.md](typing-accuracy.md)): a simulated thumb on the
JVM, and replays of recorded typing. Another keyboard's decoder cannot be run that way; it can only be typed on. So
`tool/versus` types on them: the same taps, injected on the Android emulator, into each keyboard in turn, and the
text each leaves in the field is scored the same way.

    tool/versus/versus.sh                 # everything: about four hours for two keyboards
    tool/versus/unpack.sh 2026-11-11      # the kept records of that day's run, put back under build/
    tool/versus/versus.sh --score build/versus/runs/2026-11-11
    tool/versus/whatif.py                 # Ghost Keyboard alone on the JVM, the same sentences, in seconds
    tool/versus/fixtures.py               # the words Ghost Keyboard lost, tried again on the decoder as it is now
    tool/typing_bench.sh versus           # the same, from the typing bench

What the run of 2026-11-11 recorded is kept in `tool/versus/runs/2026-11-11/`, gzipped: every tap as it was placed
on each keyboard, the field after every key press, the typings thrown away, and the tables as `score.py` wrote
them. Every step of `versus.sh` was run that day, most of them many times, but one at a time as they were written
(the snapshot by hand, before `prepare.py` had it): the script has not yet been run as one command from start to
end.

## What it found, 2026-11-11

Ghost Keyboard 0.15.0 (80) against Gboard 15.1.08.726012951 (the emulator image's own), tap typing only, English
(US), default settings, nothing learned, on an Android 16 emulator of 1248 by 1972 px at 420 dpi. SwiftKey was not
on the emulator and is not here ([how it would be](#not-measured-and-how-it-would-be)). 300 held-out sentences
(1,735 words) typed by three thumbs, the same taps into both.

| Thumb | Keyboard | Words wrong | of them left wrong | of them spoiled | Slips mended | Sentences all right |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| careful | Ghost Keyboard | **2.7%** (2.0 to 3.5) | 43 | 4 | 85% of 281 | 86% |
| careful | Gboard | **1.8%** (1.2 to 2.5) | 27 | 5 | 90% of 282 | 90% |
| leaning | Ghost Keyboard | **7.7%** (6.4 to 9.1) | 129 | 5 | 81% of 684 | 66% |
| leaning | Gboard | **3.2%** (2.3 to 4.1) | 50 | 6 | 93% of 690 | 85% |
| sloppy | Ghost Keyboard | **16.1%** (14.3 to 18.0) | 279 | 1 | 72% of 1,000 | 41% |
| sloppy | Gboard | **6.4%** (5.1 to 7.7) | 108 | 3 | 89% of 1,003 | 71% |

In brackets, the 95% interval. Ghost Keyboard's words wrong less Gboard's: careful **+0.9** points (+0.1 to +1.6),
leaning **+4.5** (+3.1 to +5.8), sloppy **+9.7** (+8.0 to +11.5). Over the three thumbs Ghost Keyboard leaves 461
of 5,205 words wrong (8.9%) and Gboard 199 (3.8%).

- **The gap is all in slips not mended.** Neither keyboard spoils words the thumb got right: 10 for Ghost Keyboard,
  14 for Gboard, of some 3,240 words typed as meant.
- **It grows with the thumb.** A careful thumb is a point apart; a thumb that leans or scatters loses two and a half
  times as many words on Ghost Keyboard as on Gboard.
- **Ghost Keyboard's own numbers hold.** The typing bench gives 0.11.0 words wrong of 3.6% and 16.8% for its
  careful and sloppy thumbs, on other sentences with unknown words mixed in; the emulator's 2.7% and 16.1% for the
  same two scatters are of a piece with them. What is new is the other keyboard beside them.

How far to trust the numbers:

- **The decoder on the JVM is the decoder on the emulator.** Fed the same taps, `tool/versus/replay.sh` leaves the
  very text the emulator did in 900 of 900 sentences, character for character. So a change to autocorrect can be
  tried against this run in seconds, without the emulator (`whatif.py`, `fixtures.py`).
- **Gboard types the same thing twice.** 60 of its sentences typed again, hours later, by the same taps: the same
  text 60 times (`twice.py`).
- **The field was read two ways** (its own record, and `uiautomator dump`) 33 times, and they agreed 33 times.
- **The thumb never presses a key that is no letter**; a tap that would is pulled back (0.4% to 4.1% of taps, more
  on Gboard). It decides nothing: over the words no tap of which was moved, the figures are 2.7%, 8.0% and 15.6%
  against 1.9%, 3.3% and 6.2%.
- **88 of the 1,800 sentence-typings were thrown away and typed again** (30 of Ghost Keyboard's, 58 of Gboard's),
  because the emulator had stalled in the middle of a tap; see [the run](#how-it-was-measured). Before that the
  tables read 3.5%, 7.7% and 16.1% against 1.8%, 3.6% and 7.8%.

## Where Ghost Keyboard loses

326 words Gboard got and Ghost Keyboard did not (and 64 the other way). Every one is in
`tool/versus/fixtures/ghost-lost-to-gboard.tsv` with the taps of its whole sentence, and
`build/versus/runs/2026-11-11/losses.tsv` after a run.

### Gboard reads most near misses right at the key press

What each tap typed there and then, before any word was corrected:

| Thumb | Keyboard | Taps that landed on another letter's key | of them typed as the letter meant | Taps on their own key | of them typed as something else |
| --- | --- | ---: | ---: | ---: | ---: |
| careful | Ghost Keyboard | 224 | 0 | 6,969 | 0 |
| careful | Gboard | 217 | 130 (60%) | 6,976 | 8 (0.1%) |
| leaning | Ghost Keyboard | 807 | 0 | 6,357 | 0 |
| leaning | Gboard | 820 | 545 (66%) | 6,349 | 18 (0.3%) |
| sloppy | Ghost Keyboard | 1,371 | 2 | 5,784 | 1 |
| sloppy | Gboard | 1,346 | 736 (55%) | 5,814 | 47 (0.8%) |

A key on Gboard is not a fixed patch of glass. Where a tap lands just over the line, it types the letter the word
so far makes likely, more often than not the one meant, and it is wrong about a tap that was on its own key less
than once in a hundred. More than half the slips a thumb makes never reach Gboard's autocorrect at all. Ghost
Keyboard types the key under the touch, always, and leaves every slip to the end of the word. That is a choice,
not a fault: the end of the word is where it knows most. But what it does there has to make up the whole
difference, and the tables below say where it does not.

### What stood in the decoder's way

The same 326 words, by the first thing in `Autocorrect.byTaps` and `TapDecoder` that kept the word from being
mended (`score.py`'s `limit`, worked out from the taps and the word list):

| What stood in the way | Words | careful | leaning | sloppy | For example (typed, left, meant) |
| --- | ---: | ---: | ---: | ---: | --- |
| More letters off their keys than the search will change in a word this long | 89 | 1 | 25 | 63 | ur, ur, it; jr, jr, he; niy, niy, buy; jluze, jluze, house; kpiaebwd, kpiaebwd, loosened |
| No word typed, the word meant within the search's reach, and not mended | 65 | 4 | 19 | 42 | hkpelwss, hkpelwss, hopeless; slaughtwres, slaughtwres, slaughtered; atsmmdr, atsmmdr, stammer |
| A letter left out, doubled or swapped | 51 | 11 | 20 | 20 | wer, we, wear; cortnuately, cortnuately, fortunately; immediatdelh, immediatdelh, immediately |
| Cut in two: one of its letters landed on the space bar | 37 | 2 | 13 | 22 | e er, er, ever; nun er, er, number; lea e, e, leave; ac ident, don't, accident |
| The letters typed spell a common word, which is kept | 31 | 4 | 8 | 19 | is, is, us; mr, mr, me; if, if, of; lime, lime, like; pleases, pleases, pleased |
| Run together: the space landed on a letter | 26 | 0 | 2 | 24 | hasvtwnspnitid for "has tendonitis"; gadvzritchds for "had stitches" |
| Run together: no space was typed | 12 | 0 | 6 | 6 | eatjow for "eat now"; conmectedyrt for "connected yet" |
| The letters typed spell a rare word | 9 | 1 | 6 | 2 | yom, yom, Tom; dior, dior, door; kn, kn, in |
| Typed as meant, and changed | 6 | 4 | 2 | 0 | yurt, hurt; berber, better; dialled, dialed; envies, envied; knifed, knife |
| all | 326 | 27 | 101 | 198 | |

- **The search's reach (89 words, more than a quarter).** `TapDecoder.search` reads at most one letter as another
  key in a word of three letters or fewer, two in a word up to five, three up to eight and four beyond (`changes`);
  where the letters typed spell a common word, one up to four letters and two beyond. It counts letters, not how
  far off the taps were: a tap a hair over the line counts as much as one two keys away. A sloppy thumb puts one
  tap in five on a neighbouring key, so "it" comes out "ur" and "buy" comes out "niy" often, and no reading of
  those is ever looked at, whatever the costs are set to. Gboard reads them.
- **A word cut in two is never put together again (37).** A letter's tap that lands on the top of the space bar
  ends the word. Each half is then corrected as a word of its own ("ac ident" became "ac I don't"). Gboard read 25
  of 46 such taps as the letter in the first place, and put six of the rest right afterwards. The decoder already
  reads a low tap on the bottom row as a space that was meant (`spaceCost`); it has nothing for the other way
  round.
- **Two words run together (38)** are parted only where each half is within `JOIN_BUDGET` of a word and the first
  is common. With a slip in either half as well, which is how a sloppy thumb leaves them, most stay one string
  ("eatjow", "hasvtwnspnitid").
- **Common "words" that nobody types (31).** The list counts mr, hr, ur, di, ke and sis as common words (Zipf 3.5
  to 5.7), so a slip that spells one is kept unless another reading is forty times as likely (`MARGIN_COMMON`) and
  one change away. "Sort it out" stayed "sort ur out".
- **A word in no list (24 of the 326)**, such as rednecks, cupola, coffered and Tuaregs, cannot be mended toward,
  and five of the six right words Ghost Keyboard changed were words its list lacks (yurt, Berber, dialled, envies,
  knifed).

### The word before a question mark was read without its taps (mended 2026-11-12)

`DevKeyboardService.tapsFor` asks `KeyboardView.tapKeys` where the keys are, and in 0.15.0 that was null on any
page but the letters. A question mark or an exclamation mark typed from the ?123 page, and every comma (0.15.0 had
no comma on its letters page: the { } key was where Gboard's is), therefore ended its word with the keyboard on
another page, and the word was corrected by which keys are neighbours alone, as before 0.11.0. 42 of the 326 lost
words ended that way. A person who held the full stop for its question mark kept the taps; one who went to ?123
did not. The first cut of the JVM replay read those words by their taps, and the emulator disagreed in 24
sentences of 900: that is how it was found.

**Mended** (Matt, 2026-11-12: "Yes, fix it"), on top of 0.16.2. `KeyboardView` keeps where the letters' keys
listen as it leaves the letters for another page (`lettersKeys`, taken in `showPage` before the page changes), and
`tapKeys` answers with that on any other page; a new field forgets it. Nothing else changes: the taps of a word
were always kept by the service, and only the keys to read them against were let go. `TapKeysSourceTest` holds the
lines to the source, and that the page is changed in no third place. Through the decoder on the JVM, the 900
sentence-typings of 2026-11-11 now leave 2.6%, 7.4% and 15.2% of words wrong where 0.15.0 left 2.7%, 7.7% and
16.1%: 25 words mended and 2 broken, 23 of them among the 326 (`whatif.py --marks-without-taps` types as before).
**On a device**, as far as the Mac allowed (2026-11-12, a debug build of 0.16.2 with the fix on the emulator;
records in `tool/versus/runs/2026-11-12-fix-check/`). `whatif.py --marks-without-taps --changed` listed the 26
typings of 900 the fix alters on that build's keys, and `run.py --only` typed from that list and from 40 others.
The Mac's load average stood between 200 and 1,000 all the while: single taps were held for up to nine seconds,
21 typings were thrown away for it, and nine got through before the emulator was given back. In all nine the
emulator's text is the tree's, character for character. Two of the nine are among the 26: "Are you committed?",
which 0.15.0 left as "Are you committee?", and "Am I welcome?", which it left as "Am unwelcome?". The other 24 are
not typed yet (`run.py ghost --only build/versus/only-altered.txt`, on a quiet Mac). That build's keys were not
measured either, the emulator being too slow for it: the letters were taken to answer where 0.15.0's were
measured, the space bar where the layout code puts it, and the nine sentences are what bears that out.

### What the decoder's own dials would buy

The same 900 sentence-typings through the decoder on the JVM, with its numbers set differently
(`whatif.py --set`; the `@JvmField` dials `tool/typing_bench.sh` tunes). Words wrong, and words mended and broken
against the build that was measured:

| Decoder | careful | leaning | sloppy | all | Mended | Broken |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| as measured, 0.15.0 (`--marks-without-taps`) | 2.7% | 7.7% | 16.1% | 8.9% | | |
| taps kept at marks: the tree since 2026-11-12 | 2.6% | 7.4% | 15.2% | 8.4% | 25 | 2 |
| and `Autocorrect.UNKNOWN_ODD` 0 (it is 1.8) | 2.5% | 6.8% | 14.0% | 7.8% | 56 | 0 |
| and `MARGIN_COMMON` 0.6 (1.6), `UNKNOWN` 1.6 (3.2) | 2.1% | 6.4% | 13.0% | 7.2% | 96 | 7 |
| taps kept, every bar and margin off | 3.8% | 6.5% | 11.6% | 7.3% | 151 | 70 |
| Gboard, on the emulator | 1.8% | 3.2% | 6.4% | 3.8% | | |

- **Letters that look like no word are held dear for a reason.** With `UNKNOWN_ODD` at 0, so that any reading
  within reach beats a string like "hkpelwss", 31 more words are mended and not one broken on these sentences. But
  these sentences hold few right words that no list has (names, codes), which is where it costs, and the typing
  bench, which mixes them in, says how much: `-Dset=A.UNKNOWN_ODD=0` spoils 10.6%, 15.2% and 17.9% of them for its
  three thumbs where the decoder as it is spoils 1.5%, 2.4% and 6.0%, and right words spoiled go from about 1.2 in
  a thousand to 3.8. All three numbers of the table's fourth row together: 16.7%, 20.1% and 23.8% of unknown words
  spoiled, 6.4 to 7.0 right words in a thousand. Not as they stand.
- **The cautious numbers are worth about 1.7 of the 5.0 points between the keyboards** (8.9% to 7.2%, against
  Gboard's 3.8%), and no more: with every bar off, the decoder mends 151 and breaks 70. The rest is not caution.
  It is the search's reach, the space, and how the readings are ranked.
- **`TapDecoder.BUDGET` binds nothing**: at 7 or 9 not one word changes. **`SPATIAL`** lower (a scattier thumb
  assumed) mends 36 to 53 and breaks 18 to 29.
- These are four hours of one thumb model on one set of sentences, and the dials were tuned on a bench with the
  same thumb. They say where to look, not what to ship.

### The search's reach, by how near the line a tap was (made 2026-11-12)

The bound on how many letters of a word the search will read as another key counted letters, whatever their taps
said. It now counts each by its cost (`TapDecoder.counts`, in hundredths): a letter read as another key for
`WHOLE_CHANGE` or more is a whole change, and for less its share of one, so two taps each just over the line to the
next key are a fraction of a change between them, and two in the middles of far keys are two. A letter missed,
added or swapped is a whole one as before. "jr" is "he" again, "tbr" and "yhd" are "the", "jluze" is "house".

`WHOLE_CHANGE` is 2, set on the typing bench's own tuner (`tool/typing_bench.sh tune`, whose measure is words
right less four for every right word spoiled: 93.02 at 0.5, 93.45 at 1, 93.76 at 2, 93.84 at 4; taken where the
curve flattens). It was first tried as source that the app did not have (`whatif.py --overlay`): one more letter
of reach for every word mended 88 words of the run and broke 3, but spoiled more of the bench's right words for
the same gain (for its careful thumb 1.6 in a thousand, and 3.0% of the right words no list has, where counting by
cost leaves 1.2 and 1.5%).

| | careful | leaning | sloppy | all | Mended | Broken |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| the run's 900 typings, before | 2.6% | 7.4% | 15.2% | 8.4% | | |
| after | 2.5% | 6.0% | 12.1% | 6.9% | 80 | 0 |
| 900 typings of 300 other sentences, before | 3.9% | 6.7% | 15.5% | 8.7% | | |
| after | 3.8% | 5.6% | 13.0% | 7.4% | 89 | 6 |

The second pair of rows is what to believe: 300 held-out sentences from line 100,001 on, which nothing had read,
typed by the same three thumbs on the same keys (`scoreboard.py`'s "unseen"). On 12,000 of the bench's own words
the sloppy thumb's words right go from 82.7% to 85.7% and the normal one's from 91.6% to 92.3% (careful 96.4%
either way); right words spoiled go from 1.2, 1.1 and 1.5 in a thousand to 1.2, 1.5 and 1.7, and the right words
no list has that are spoiled from 1.5%, 2.4% and 6.0% to 1.5%, 4.3% and 7.1%.

### A word cut in two is put together again (made 2026-11-12)

A thumb reaching for c, v, b, n or m lands on the top of the space bar as often as on the key above it. The word
was then two scraps with a space between, each corrected as a word of its own ("bei g", "ha e", "would t"); and
where the letter was the word's last, the space that followed made a full stop of the two: "keep in touch" came
out "keep I. Touch". Nothing could put them back.

`KeyboardView` now keeps the bar's own tap with where it aimed, and `DevKeyboardService` the word that tap ended,
as typed and as it was left standing, with its taps (`Cut`), until the next word ends or a second space comes.
Then `Autocorrect.rejoin` reads the letters and the bar's tap as one word: `TapDecoder` takes that tap as a letter
for how much nearer the bar's middle line it landed than that letter's key (next to nothing just under the bar's
top edge, 2.4 in its middle), and never as nothing. The one word is held against what stands. Two scraps are worth
the first as it stands and the second after it, less nine for being two words, as a join is counted, and the one
word must be worth `REJOIN_MARGIN` (1) more: "is a" and "to go" stand however high the space was tapped, and
"bei g" gives way wherever it was. One scrap with nothing after it is held as `byTaps` holds any typed word: "the"
and two spaces is still a full stop, not "then"; "o" and two spaces is "on". Backspace straight after puts the
scraps back and learns nothing from them.

| | careful | leaning | sloppy | all | Mended | Broken |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| the run's 900 typings, before | 2.5% | 6.0% | 12.1% | 6.9% | | |
| after | 2.5% | 5.4% | 10.9% | 6.3% | 32 | 0 |
| the 900 unseen typings, before | 3.8% | 5.6% | 13.0% | 7.4% | | |
| after | 3.3% | 5.0% | 12.2% | 6.8% | 41 | 1 |

The typing bench cannot see this (it types a word at a time), so the two sets of sentences are its only measure,
through `Replay.kt`'s model of the service. Still cut in two: a word whose first letter
landed on the bar (" egim" for "begin": the tap comes after the last word's own space, and makes a full stop of
it), and scraps that were mended at the bar's tap into a word too good to give up ("eome" made "some", for
"women").

### The two changes on the emulator (2026-11-12)

A debug build of the tree with both changes (0.16.3, commit 1594724) was put on emulator-5584 and given the taps of
every typing the two changes alter against the tree before them (0668977), by the JVM's own account: 104 of the
900. Forty typings they leave alone were typed with them (`tool/versus/device-check.sh`). The emulator's text was
the JVM's for this tree in 104 of the 104, and the old tree's in none; and in 40 of the 40 others. So what
`KeyboardView` and `DevKeyboardService` do with the bar's tap on a phone is what `Replay.kt`'s model of them does,
on every sentence where it matters. The records are in `tool/versus/runs/2026-11-12-reach-rejoin-check`. (The Mac
was restarted in the middle of the first attempt; the emulator was started again, the check run from its start,
and the emulator put back as it had been found.)

### Gboard on sentences nothing was made from (2026-11-12)

Until now only the run's own 900 typings had been given to Gboard, and every change above was made from the words
lost there. On 2026-11-12 Gboard was given the 900 typings of `scoreboard.py`'s "unseen" set too: 300 held-out
sentences from line 100,001 on, the same three thumbs and the same seed, its keys as they were measured on
2026-11-11, its learned words deleted first (`tool/versus/other-sets.sh`; 14 typings were thrown away for a stall
and typed again).

| | careful | leaning | sloppy | all |
| --- | ---: | ---: | ---: | ---: |
| Gboard, the run's 900 typings (5,205 words) | 1.84% | 3.23% | 6.40% | 3.82% (199) |
| Gboard, the 900 unseen typings (6,408 words) | 1.64% | 2.39% | 6.32% | 3.45% (221) |
| Gboard, the 900 final typings (6,609 words) | 1.45% | 3.09% | 4.90% | 3.15% (208) |
| Ghost Keyboard's decoder at bbd0877, the run's | 2.48% | 5.42% | 10.89% | 6.26% (326) |
| Ghost Keyboard's decoder at bbd0877, unseen | 3.28% | 5.01% | 12.17% | 6.82% (437) |

Gboard is no worse on fresh sentences than on the run's, and the gap is wider there, not narrower: 282 words Gboard
got and Ghost Keyboard did not, 66 the other way. "As good as Gboard" therefore means 3.45% on these sentences as
well as 3.82% on the run's, and a change that closes the first gap and not the second was fitted to the run. The
records are in `tool/versus/runs/other-sets/unseen`, and `scoreboard.py` prints Gboard's line beside the unseen
set's from them.

A third set was to be kept for the end ("final"). Its place had been given as line 110,001 of the held-out file,
which has 102,028 lines: the set was empty, and nothing was ever typed from it. It is now the 300 sentences from
line 101,001 on, and a set with fewer sentences than asked for is an error in `run.py` and in
`scoreboard.py --final`. Gboard was given it that evening as the unseen set was (20 typings thrown away for a stall
and typed again; records in `tool/versus/runs/other-sets/final`): 3.15% of words wrong, 208 of 6,609. Ghost
Keyboard's decoder has not been run on it and will not be until the work is done: no change is to be chosen by
what it does there, and only its totals are read. Gboard's three figures (3.82%, 3.45%, 3.15%) are also a measure
of how much a set of 300 sentences moves a keyboard's score by itself: two thirds of a point between the best and
the worst of three.

### What letters no list has are worth (made 2026-11-12)

Where the letters typed are no listed word, every reading of their taps is held against what the letters themselves
are worth (`Autocorrect.unlisted`), and that was one of three fixed numbers: `UNKNOWN` 3.2, `UNKNOWN_ODD` 1.8 for
four letters or more with a run of three in them that no common word has (`odd`), `UNKNOWN_LONG` 1.6 from six
letters. 55 of the 199 words still lost to Gboard stood behind those bars with the word meant in the search's reach:
"prrsrnyable" for presentable, "sgsrtet" for starter, "courteoya" for courteous. A fixed bar cannot tell
"prrsrnyable" from a name, and lowered for every string it changes the names
([above](#what-the-decoders-own-dials-would-buy)).

**A table of letters.** `tool/letters.py` takes the words of Tatoeba's counted sentences that no English list has
(every 20th sentence held out, as everywhere: 43,272 kinds of word, 263,981 of 14,912,141 running words, 1.77%) and
writes `assets/dict/en_letters.tsv`: what each letter costs after the two before it, a word's start and its end
counted as marks, as minus the log to base ten of its chance. 9,666 runs, 87 KB (39 KB zipped), 77 KB in memory.
`LetterModel` reads it where English is the only list loaded; any other set of lists has the fixed bars as before
(`tool/lang_check.sh` passes, and with `Autocorrect.LETTERS` at 0 the scoreboard and the bench give the tree
before to the last word). What letters are worth by the table is the chance that a word no list has was meant here
and is spelled so, in the units a listed word's frequency is in: 9 + log10 of 0.0177, less what the letters cost.
"kira" 1.5, "berber" 1.3, "whiby" 0.1, "nguyen" -3.8, "hkpelwss" -11.0, "prrsrnyable" -11.6. It falls with every
letter: a long string a slip from a word is that word.

**Taken at its word, the table changes the names.** With the table in place of the fixed bars and counted in full
(`UNKNOWN` kept as a ceiling; `LETTERS_ODD` 0, `UNKNOWN_LONG` 3.2, `LETTERS_WEIGHT` 1, `LETTERS_SHIFT` 0) the run's
900 typings leave 5.05% of words wrong for 6.26% (85 mended, 22 broken) and the unseen ones 5.71% for 6.82%; and
the typing bench's thumbs have 63.5%, 64.6% and 65.5% of the names and terms they type on purpose changed, where
the fixed bars change 1.5%, 4.3% and 7.1%. Shifted up by 5 units, until those are spoiled about as seldom as now
(2.3%, 3.7%, 4.8%), it leaves 5.59% and 6.51% wrong and mends 175 of the bench's 264 misspellings for 232:
"definately" looks like a word, and only the bar for long words says it is more likely one misspelled. The table's
chance is spread over every string that could be a word. The words people do type that no list has are few of
those, so a real name is worth far more than its letters say ("thandiwe" -6.3, "ghostkbd" -9.5); and what its
letters say cannot tell a misspelling from a name at all.

**So the table only ever lowers a bar, and counts for part of what it says.** `unlisted` is the lesser of the fixed
bar and 9 + log10 of the share, plus `LETTERS_SHIFT` (1; 0.75 until the review of 2026-11-13, below), less `LETTERS_WEIGHT` (0.6) times what the letters
cost. Where the table has its say `odd` has none (`LETTERS_ODD` 0): `UNKNOWN_LONG` from six letters and `UNKNOWN`
under six are the bars it lowers.

| | careful | leaning | sloppy | all | Mended | Broken |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| the run's 900 typings, before | 2.5% | 5.4% | 10.9% | 6.3% (326) | | |
| after | 2.4% | 4.7% | 9.4% | 5.5% (287) | 45 | 6 |
| the 900 unseen typings, before | 3.3% | 5.0% | 12.2% | 6.8% (437) | | |
| after | 3.3% | 4.5% | 10.4% | 6.1% (391) | 50 | 4 |

On 12,000 of the bench's own words, careful, normal and sloppy: words right 96.41%, 92.34% and 85.65% to 96.51%,
92.98% and 87.20%; right words spoiled 1.22, 1.48 and 1.65 in a thousand to 1.01, 1.21 and 1.03; the right words
no list has that are spoiled 1.5%, 4.3% and 7.1% to 0.8%, 3.0% and 3.6%; two words run together and parted 60.0%,
61.5% and 56.4% to 63.3%, 71.8% and 66.8% (two words must be worth the lesser of the bar and `JOIN_BAR`, 0, and
two words run together with a slip in them are letters worth less than nothing by the table); misspellings mended
232 of 264, as before. Of the slips the bench mends, more go to the wrong word: 3.4%, 4.0% and 5.1% of them to
3.5%, 4.3% and 5.4% (a string that stood as typed now gives way to a reading, and the reading is sometimes another
word), while 83.1%, 83.2% and 80.4% of slips are mended for 83.3%, 84.0% and 82.0%. The figures in the rest of
this paragraph are for the shift of 0.75 it first stood at. Of the 199 words still lost to Gboard 155 are left (`fixtures.py --still`), and 40 of the 55
that stood behind the bars: 18 of those meant a word no list has (stammer, crawlspace, rednecks, coffered), 15 are
strings of five letters or fewer, which the table leaves at `UNKNOWN` or near it ("crah", "redi", "whiby"), and 7
are longer strings it still makes worth more than the reading ("archerh", "donsted", "slaughtwres").

**The curve.** `LETTERS_SHIFT` moves what the table says up and down. Words wrong on the two sets with what was
mended and broken against the tree before; then the bench's three thumbs. Its measure is the tuner's own
(`tool/typing_bench.sh tune`): words right less four for every right word spoiled, a mean of the three thumbs, and
three points for all the misspellings.

| Shift | Seen | Mended, broken | Unseen | Mended, broken | Bench: words right | Right words spoiled per 1000 | Unknown words spoiled | Misspellings of 264 | Measure |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| the tree before | 6.26% (326) | | 6.82% (437) | | 96.41, 92.34, 85.65 | 1.22, 1.48, 1.65 | 1.5%, 4.3%, 7.1% | 232 | 93.76 |
| 3.25 | 6.03% (314) | 21, 9 | 6.68% (428) | 24, 15 | 96.43, 92.48, 86.07 | 0.81, 0.94, 1.03 | 0.0%, 1.8%, 3.6% | 232 | 94.07 |
| 2.25 | 5.80% (302) | 32, 8 | 6.43% (412) | 36, 11 | 96.46, 92.71, 86.58 | 0.81, 0.94, 1.03 | 0.0%, 1.8%, 3.6% | 232 | 94.33 |
| 1.75 | 5.71% (297) | 36, 7 | 6.30% (404) | 41, 8 | 96.48, 92.86, 86.81 | 0.81, 0.94, 1.03 | 0.0%, 1.8%, 3.6% | 232 | 94.46 |
| 1.25 | 5.55% (289) | 43, 6 | 6.16% (395) | 47, 5 | 96.50, 92.95, 87.05 | 0.91, 0.94, 1.03 | 0.4%, 1.8%, 3.6% | 232 | 94.57 |
| **1** | 5.51% (287) | 45, 6 | 6.10% (391) | 50, 4 | 96.51, 92.98, 87.20 | 1.01, 1.21, 1.03 | 0.8%, 3.0%, 3.6% | 232 | 94.60 |
| 0.75 | 5.40% (281) | 50, 5 | 5.99% (384) | 56, 3 | 96.53, 93.02, 87.37 | 1.32, 1.48, 1.24 | 1.5%, 4.3%, 4.8% | 235 | 94.64 |
| 0.5 | 5.28% (275) | 55, 4 | 5.93% (380) | 61, 4 | 96.52, 93.09, 87.42 | 1.52, 1.61, 1.45 | 2.3%, 4.9%, 6.0% | 235 | 94.64 |
| 0.25 | 5.19% (270) | 58, 2 | 5.87% (376) | 63, 2 | 96.53, 93.18, 87.55 | 1.93, 2.02, 1.65 | 3.8%, 6.7%, 7.1% | 235 | 94.62 |
| -0.25 | 5.11% (266) | 62, 2 | 5.74% (368) | 71, 2 | 96.50, 93.32, 87.79 | 3.04, 2.96, 3.31 | 8.0%, 11.0%, 16.7% | 241 | 94.52 |
| -0.75 | 4.88% (254) | 76, 4 | 5.63% (361) | 79, 3 | 96.47, 93.21, 87.92 | 4.26, 5.65, 4.96 | 11.8%, 21.3%, 25.0% | 241 | 94.07 |
| -1.25 | 4.86% (253) | 78, 5 | 5.63% (361) | 85, 9 | 96.37, 93.18, 87.99 | 6.29, 7.67, 6.41 | 18.3%, 29.9%, 32.1% | 241 | 93.59 |

- **Where it stands (2026-11-13).** 1: the least shift tried at which no thumb spoils more right words, nor more
  of the right words no list has, than the fixed bars do, on the bench's own words and on the tuner's own stretch
  that the shift was not set on (next but one). On that stretch: words right 96.63%, 93.04% and 87.09% for 96.49%,
  92.72% and 86.21% without the table; right words spoiled 0.99, 1.14 and 1.36 in a thousand for 1.18, 1.30 and
  1.49; unknown words spoiled 2.5%, 4.0% and 5.5% for 3.1%, 4.7% and 6.2%; measure 94.62 for 94.13. It leaves six
  more of the seen set's words wrong than 0.75 and seven more of the unseen set's.
- **Where it stood first.** 0.75 is where the bench's measure is highest (flat from 1 to 0.25), and the least shift at
  which the right words no list has are spoiled no more often than the fixed bars spoil them. From 1 up no line of
  the bench is worse than the tree before, with 39 fewer words wrong on the seen set and 46 fewer on the unseen
  one. Below 0.75 a quarter of a unit buys some six words of the seen set's 5,205 and costs four, then eight, of
  the bench's 511 unknown words (15 spoiled at 0.75, 19 at 0.5, 27 at 0.25; the fixed bars spoil 17). Under -0.75
  nothing more is mended: what this bar holds back is spent at 253 words, 4.86%.
- **On bench sentences the shift was not set on.** The tuner keeps a stretch of its own, 20,000 words from line
  40,000 of the held-out file with another seed (`tool/typing_bench.sh tune` with `-Donly=none -Drounds=0` types
  it and tunes nothing). There the measure is 94.13 without the table and 94.64 with it, and 0.75 is the best of
  the shifts there too (94.59 at 1.25, 94.54 at 0.25, 94.41 at -0.25). Words right 96.49%, 92.72% and 86.21% to
  96.63%, 93.08% and 87.17%; right words spoiled 1.18, 1.30 and 1.49 in a thousand to 1.18, 1.22 and 1.86; unknown
  words spoiled 3.1%, 4.7% and 6.2% (of 479, 297 and 145) to 3.1%, 4.4% and 8.3%. So "no more often" does not
  hold there for the sloppy thumb: 12 of its 145 for 9. At 1.25 every line there is better than without the table
  (0.87, 1.06 and 1.36 in a thousand; 2.1%, 3.7% and 5.5%).
- **`odd` gives way to the table** (`LETTERS_ODD`). `odd` knows "has a run no common word has" or "has none", and
  holds "gyatt" and "kekw" as cheap as "hkpelwss". Kept beside the table (the table then only lowers bars, and
  nothing the tree mended is lost: no word broken on either set down to a shift of 0.75) it leaves 5.61% and 6.23%
  wrong at 1.75, the least shift at which the bench's lines are as before; 5.32% and 5.98% at 0.75, with 2.7%,
  6.7% and 8.3% of unknown words spoiled; 5.17% and 5.87% at 0.25 with 4.6%, 7.9% and 9.5% (measure 94.47, 94.59,
  94.59; on the tuner's own stretch 94.51 at 1.75 and 94.62 at 0.75). Without it the same unknown words are
  spoiled a whole unit of shift lower, where 11 and 15 fewer words are wrong. What that costs is the short slips
  `odd` caught and the table does not think odd enough: "truk", "opej", "havun" and "cyss" stand on the seen set,
  "musz" and "agajn" on the unseen, and "tcis" there is parted in two (two words are held to `JOIN_BAR` where
  "this" is now held to `UNKNOWN`). The table cannot do better by them: it makes "opej" worth -0.6 and "piotr"
  -2.6, "agajn" -1.9 and "gyatt" -1.6.
- **The weight.** 0.5, 0.6, 0.8 and 1 trace the same curve once the shift suits each. With the right words no
  list has spoiled as at 0.6 and 0.75, a weight of 0.8 (shift 3.05) leaves 278 and 386 words wrong and a weight
  of 1 (shift 5.3) 274 and 386, for 281 and 384: a little better by the seen set, no better by the unseen one.
  A weight of 1 at 5.3 is as good a place to stand as 0.6 at 0.75, and by the bench a shade better: measure 94.70
  for 94.64, and on the tuner's own stretch 94.69 for 94.64 with the sloppy thumb's unknown words spoiled as
  without the table (1.18, 1.38 and 1.49 in a thousand; 3.1%, 5.1% and 6.2%). It was measured at five shifts, 0.6
  at twelve.
- **The bar for long words stays.** With the table and no `UNKNOWN_LONG` (0.6, 0.75) 190 of the 264 misspellings
  are mended for 235, and 22 and 23 words are broken on the two sets. At 2.0 for 1.6, 6 right words are changed
  on the seen set for 11 and 5 on the unseen for 7, and 220 misspellings are mended (measure 94.43).
- **No floor** (`LETTERS_FLOOR`). With the table never saying less than 0, -1, -2, -3 and -4 the measure is 94.23,
  94.43, 94.55, 94.60 and 94.64 (5.73% and 6.23% of words wrong at 0, 5.46% and 6.09% at -2, 5.40% and 6.02% at
  -4): most of what the table mends is worth less than nothing.
- **The rule for a word with an ending of its own stays** (`inflected`, `LETTERS_INFLECTED`). Without it 12 more
  misspellings are mended and 3 more words of the seen set (measure 94.74), and 3 and 2 more of the careful and
  the normal thumb's unknown words are spoiled, which is what the rule is for.
- **The table asked only of long strings** (`LETTERS_FROM` 6): with `odd` out of the way it only costs (5.61% and
  6.24% at 0.75). With `odd` kept it is the gentlest way in, the fixed bars as they were under six letters: 5.49%
  and 6.18% at 1.25 with the bench's right words and unknown words spoiled exactly as before and nothing broken on
  either set, 5.17% and 5.91% at -0.25 with 3.4%, 6.1% and 7.1% of unknown words spoiled and the bench's highest
  measure of all, 94.77 (94.72 on the tuner's own stretch, with 4.4%, 6.7% and 8.3% of unknown words spoiled
  there for 3.1%, 4.7% and 6.2%).
- **Runs of three letters, each kind of word once.** On the 871 kinds of unlisted word in held-out lines that the
  counted sentences never had, a letter costs 1.137, 1.051, 0.997 and 0.974 with runs of 2, 3, 4 and 5
  (`tool/letters.py --compare`), in 755, 9,666, 57,777 and 167,805 rows (7 KB, 87 KB and 565 KB for the first
  three). In the keyboard, with the other dials as they stand, runs of 2 do about as well as 3 while the shift is
  high and fall away sooner (5.49% and 6.04% at 0.25 with 0.4%, 3.0% and 4.8% of unknown words spoiled, then 5.15%
  and 5.82% with 5.3%, 10.4% and 9.5% at -0.25). Runs of 4 do a little better than 3: 5.36% and 5.99% at a shift
  of 1.25 with the bench's unknown words exactly as before, 5.49% and 6.09% at 1.75 with 0.0%, 1.8% and 3.6%
  (measure 94.65 and 94.68), in a file six times the size. Each word counted as often as it stood costs 1.147 a
  letter and spoils more than three times the unknown words at the same shift (Tatoeba's sentences are full of
  Ziri, Yanni and Skura). The listed words' own spelling counted in costs 1.056 and changes little (5.42% and
  6.05% at 0.75).

**Reviewed 2026-11-13.**

- *With prediction off.* There is no Scorer then (nor in a terminal, nor where the words before give no place to
  read from), and that path is to behave as it did. The table has no say there (`LETTERS_UNSCORED` 0, and
  `LetterModelTest.withPredictionOffTheFixedBarsAloneSay` holds it): the bench typed with no Scorer at all
  (`-Dscorer=none`, `scoreboard.py --java=-Dscorer=none --bench`) gives the tree before to the last figure, 95.90%,
  90.88% and 82.78% of words right. With the table let in there (`LETTERS_UNSCORED` 1) it would be 96.04%, 91.66%
  and 84.73%; right words spoiled 1.01, 1.48 and 1.45 in a thousand to 1.12, 1.35 and 1.03; unknown words spoiled
  1.5%, 4.3% and 7.1% to 1.5%, 3.7% and 4.8%; slips to the wrong word 4.1%, 4.6% and 6.0% to 4.2%, 4.9% and 6.5%;
  misspellings 232 to 235 (measured at the shift of 0.75). Better by most lines, worse for the careful thumb's right
  words: whoever owns that rule can choose.
- *Joins.* The bench's joins (two words run together, parted) rise with the table, 60.0%, 61.5% and 56.4% to 63.3%,
  71.8% and 66.8%, and the bench never calls `rejoin`: a run-together string is letters no list has, held by
  `byTaps` to what the table says, so the reading of two words wins more often. That is this track's bar, not the
  joins' code. `rejoin`'s own scraps are held to the table too, and `LETTERS_JOINS` 0 puts them back on the fixed
  bars: the scoreboard's two sets give the same words to the last one either way (0 mended, 0 broken).
- *Slips to the wrong word* rise a little on every thumb (above): a cost beside the slips mended.

**The six right words** the tree changed are changed still: berber (to never, better and barber), yurt, dialled,
knifed, envies and nan, and on the unseen set gabble, trill and incas. The table never raises a bar, so nothing
it says can keep them. They want a list that has them.

**What it costs.** The table is asked once for a word no list has and answers in a few look-ups; the time goes to
the search, which looks further where the bar is lower. By the processor time of the bench's typing on this Mac
(a scratch harness under `build/`, the table off and on by turns in one process, the middle of five rounds): 159,
267 and 385 microseconds a word for the careful, normal and sloppy thumb before, 165, 292 and 446 after, which is
4%, 9% and 16% more. Two more runs said 3%, 9% and 16%, and 6%, 10% and 18%. The Mac was busy and the clock's own
figure (the bench's "µs a word") moved by half from run to run, so it is not given. A floor gives little of the
time back: in one process, 161, 280 and 430 with none, 161, 272 and 421 with -4, 162, 275 and 417 with -2. At the
shift of 1 (2026-11-13, the same harness, the least of five rounds, the table off and on by turns in one process):
69, 113 and 162 microseconds a word without the table, 72, 127 and 188 with it, 4%, 12% and 16% more. The Mac was
busier than before and the rounds moved by a third; separate processes, one tree each, moved by half or more.

The bench's unknown words are spoiled as often as before, and they are other words: "gyatt" to hyatt went from 8
times to 1 and "kekw" to like from 3 to none, which `odd` had held cheap; "ghostmd" to ghosts (4 times), "piotr" to
poor (3) and "attackfm" to attacked are new, which the table holds cheap. Of the right words in no list that are
not names, "typewriters" to typewriter is new. The misspelling it mends that the fixed bars did not is
"dissapoint".

Not typed on a device and not built with Gradle: the change is in `Autocorrect.kt` alone, which the JVM runs as the
emulator does, and the table is read through the same door as `en.tsv`.

**With a wider list.** The table is counted against whatever English lists are in `assets/dict` (`en.tsv` and any
`en_<name>.tsv` of words and frequencies), so one run of `tool/letters.py` makes it again when a list is added.
Against the wider list as it stood on 2026-11-12 (101,112 more lines; `--list`) 0.94% of running words are in no
list for 1.77%, 17,200 kinds for 43,272, and the table has 8,813 runs. What is left is names and possessives, and
letters that are no word's stand out more: "prrsrnyable" -13.0 for -11.6, "hkpelwss" -11.7 for -11.0, "kira" 1.8
for 1.5, "berber" 1.1 for 1.3. The share is 0.28 units less, so the same shift is a lower bar: the curve has to
be read again on that list before the shift is kept.

### Slips the search did not reach (made 2026-11-12)

`TapDecoder.search` gave up a reading of a short word past one change, read letters that spell a common word by
slips of the thumb alone (no letter left out, added or swapped), and cost a letter left out, a tap too many and two
taps swapped the same wherever they were. Where `Autocorrect.byTaps` asks for the word as typed and English is
among the lists, the search now reaches further (`search`'s `reach`; every other search, and the other languages
that read by taps, are as they were):

- a word of up to three letters may be changed by half a change more than its one (`SHORT_MORE`), for half a point
  more (`SHORT_PAST`): "tbr" with its two slips near their lines is "the", typed squarely it stays;
- letters that spell a common word may have lost a letter, had one tapped twice or two swapped, each for at least
  `LISTED_EDIT` (2): th, ot, su, tae, ff and soo were kept as the common words the lists make them. A tap that
  doubles nothing is never taken out of one ("ink" is not "in");
- a word typed as one letter may have lost its other for `LONE_OMIT` (1.8): "t" where "to" is likely is "to";
- one of two swapped taps may have landed on a neighbour's key as well (`SWAP_SLIP`): "iculs" is "could", and the
  swap steps over an apostrophe ("dotn" is "don't", not "dont");
- a tap too many costs by where it landed, from `INSERT_DOUBLED` on the key beside it up to `REACH_INSERT` (3.4) in
  the middle of a key of its own, and a letter left out within the word costs `REACH_OMIT` (1.8; 2 elsewhere).
  A letter left out after the last tap still costs 2 (`REACH_OMIT_END`), so a word typed whole is lengthened
  ("civilize" made "civilized") no more readily than before.

"English among the lists" means the board's lists, all of one script: with English and French both on, the French
words typed on that board are read with the reach too. Each language alone is as it was (`tool/lang_check.sh`,
line for line).

| | careful | leaning | sloppy | all | Mended | Broken |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| the run's 900 typings, before | 2.48% | 5.42% | 10.89% | 6.26% | | |
| after | 2.02% | 4.84% | 9.91% | 5.59% | 38 | 3 |
| the 900 unseen typings, before | 3.28% | 5.01% | 12.17% | 6.82% | | |
| after | 2.72% | 4.49% | 11.42% | 6.21% | 42 | 3 |

On the bench's 12,000 words, words right go from 96.41%, 92.34% and 85.65% to 96.75%, 92.91% and 86.62% for the
careful, normal and sloppy thumb; right words spoiled from 1.22, 1.48 and 1.65 in a thousand to 1.32, 1.48 and
1.45; the right words no list has that are spoiled from 1.5%, 4.3% and 7.1% to 1.9%, 4.3% and 6.0%. What it spoils
that was not spoiled: "kbd" made "kind" and "nd" made "and" (the bench's "2nd" with its digit trimmed) by the
careful thumb, "if" made "of" by the normal one; no longer spoiled: "confidences" (twice) and "ratioed". Each dial's
comment in `TapDecoder` has its neighbours on the seen set and on the bench tuner's measure. Broken on the run: "o"
for "on", now "of" before the next space's `rejoin` sees it, which then holds the scrap at that word's whole
frequency (rejoin's to mend); "im" read "on" for "in" because the word before it was mended; "colf" now "could"
for "cold". The search's own visits in `byTaps` grow by about a fifth (1,124,411 to 1,359,884 on the run, the most
in one search 2,966 to 3,810; on the bench 8,079,522 to 9,746,102); none of 5,016 such searches on the run, 6,177
on the unseen set or 35,148 on the bench reaches `MAX_VISITS` (40,000), before or after. Processor time a word
could not be told apart from the Mac's load (the least of nine rounds: careful 84 against 62 µs, normal 138
against 101, sloppy 135 against 145, the tree second). Not yet typed on a device.

`LONE_OMIT` was first set at 1.4 from a count of lone letters made over the seen and the unseen set together; the
unseen set is for totals only, so it was recounted on the seen set alone (1.8). The other dials' grids had the
unseen set's totals in them too; read on the seen set and the bench alone, each choice stands (`REACH_INSERT` 3.4
and 3.8 leave 293 words wrong on the seen set, 3.1 leaves 294; `REACH_OMIT` 1.6 and 1.8 leave 293, 2.0 leaves 295,
and 1.6 spoils more of the sloppy thumb's words).

### What the thumb had done

The 326 again, by the slip (`score.py`'s `category`):

| What went wrong | Words | careful | leaning | sloppy | Left as typed | The slip spelled another word | The word meant is in no list of ours |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| a short word (three letters or fewer) | 85 | 1 | 29 | 55 | 60 | 39 | 0 |
| the space: run into its neighbour, or split | 75 | 2 | 21 | 52 | 5 | 7 | 1 |
| three or more keys off | 39 | 1 | 7 | 31 | 28 | 0 | 3 |
| one key off | 32 | 7 | 9 | 16 | 24 | 9 | 8 |
| a letter left out | 28 | 8 | 10 | 10 | 21 | 12 | 0 |
| two keys off | 24 | 1 | 6 | 17 | 23 | 0 | 5 |
| a letter swapped | 15 | 1 | 9 | 5 | 14 | 4 | 0 |
| a letter doubled | 8 | 2 | 1 | 5 | 7 | 0 | 0 |
| a name or other capital | 8 | 0 | 4 | 4 | 7 | 4 | 2 |
| a right word changed | 6 | 4 | 2 | 0 | 0 | 0 | 5 |
| a contraction | 6 | 0 | 3 | 3 | 3 | 1 | 0 |
| all | 326 | 27 | 101 | 198 | 192 | 76 | 24 |

192 of the 326 were left exactly as typed: more often than it picks the wrong word, Ghost Keyboard does nothing.

### Names get no capital

Not in the score, which leaves capitals aside. The thumb types everything in lower case. Of the names the field
ended up with the right letters of (Tom, Mary, Vladimir, Algeria, Monday), Gboard gave 134 of 191 their capital
(Mary 26 of 26, Tom 34 of 48), and Ghost Keyboard 0 of 188. Both capitalise I and the first word of a sentence
every time.

### Where Ghost Keyboard wins

64 words it got and Gboard did not: short words (19), one key off (12), a right word changed (11), the space (7).
Gboard set to English (US) turned six right words into their American spellings (favour, humour, moustache), which
count against it here since the sentence says favour; Ghost Keyboard did the same to dialled, twice. Gboard left
some slips in names as they were (Tmo, Rrom and Fom for Tom, Slgeria for Algeria) that Ghost Keyboard mended.

### Using the list

    tool/versus/fixtures.py                          # 326 lost words: how many does the decoder in this tree mend?
    tool/versus/fixtures.py --marks-without-taps     # ... typing as 0.15.0 did: none, by construction
    VERBOSE=1 tool/versus/fixtures.py                # and which
    tool/versus/whatif.py --set TapDecoder.SPATIAL=1.0   # all 5,205 words: what a change mends and what it breaks
    tool/versus/whatif.py --set ... --changed build/versus/changed.txt   # the typings a change alters, and then
    tool/versus/run.py ghost --out build/versus/runs/try --only build/versus/changed.txt   # just those, on the emulator
    tool/versus/experiments/reach.sh && tool/versus/whatif.py --overlay build/versus/experiments/reach --java=-Dversus.reach=1
                                                     # a change to the decoder's source, tried without being made
    tool/versus/timing.py --calm                     # is the emulator keeping time just now? (three seconds, no taps)

`fixtures.py` types each lost word's whole sentence through `Autocorrect` as it stands in the tree, by the taps
the emulator was given, on the keys as they were that day (the list carries its keyboard with it,
`ghost-lost-to-gboard.geometry.json`: the layout in the tree moves on). Typing as 0.15.0 did, it mends 0 of 326
and leaves all 241 sentences as the emulator had them, which is the point: it starts from the emulator's own
answer. With the taps kept at marks, as the tree now types, it mends 23, and 25 of the 241 sentences read
otherwise. Mended should go up with a change to autocorrect; `whatif.py` says what else moved.

## How it was measured

**The field.** `tool/versus/field` is an app of one text field, built by the Android SDK's own tools (no Gradle,
13 KB), that belongs to no keyboard. It is a message field: text, sentences capitalised, autocorrect asked for,
several lines. It asks keyboards not to learn from it (`IME_FLAG_NO_PERSONALIZED_LEARNING`: Gboard shows its
incognito hat, Ghost Keyboard treats it as incognito and wrote nothing to its own files all run), because the same
sentences are typed three times over and a keyboard that learned them from the first thumb would be marking its
own homework by the third. That is not a guess: with learning allowed, Gboard typed 5 of 32 trial sentences
differently on identical taps, every one a word it had been taught minutes before in the same run ("Tom", twice;
three words of sentences the careful thumb had just typed). Each sentence gets a new field, so the keyboard is
told a field was entered, as when the next message is begun. The app keeps a record of the field's text after
every key press, which is what is read back; every 50th sentence is also read by `uiautomator dump`, and the two
must agree.

**Where the keys are** (`calibrate.py`, saved in `tool/versus/geometry/` for each keyboard and screen). Nothing is
taken on trust. A first idea comes from the accessibility tree where a keyboard puts its keys in it (Gboard does),
or from the keyboard's own layout code (Ghost Keyboard draws its keys on one canvas and tells the tree nothing:
`replay.sh geometry` is `KeyboardView.layoutKeys` read across). Then every letter key is measured by tapping: a tap
in its middle must type it, and each edge of the area it answers to is found by halving until it is known to a
pixel, about 500 taps a keyboard. Each of those taps is made into an empty field, for a reason found on the way:
**Gboard moves the edges of its keys with the letters already typed** (the same pixel types w after "t" and a after
"th"; low on the b key types b at the start of a word and a space where a word could end). At the start of a
sentence its keys answer where the tree says they are, to a pixel. Ghost Keyboard's do not answer where they are
drawn, and are not meant to: it moves every touch before reading it, by where Matt's own thumb was measured to
land on each key (`KeyboardView.LETTER_OFFSETS_DP`), so each key answers lower and mostly further left than it is
drawn (p by 0.29 of a key down, a, s and d by 0.17 of a key to the left). Its areas never move.

**The thumb** (`thumb.py`) is the typing bench's, said in key sizes so that it is no keyboard's: for each key meant,
how far off the tap lands in key widths and heights (Gaussian, one tap in 16 wilder by 2.2), a letter in 250 left
out, in 330 doubled, a pair in 250 swapped, a space in 250 missed, all from one seed. Three thumbs: careful
(scatter 0.20 of a key across, 0.15 down), leaning (the same, landing 0.24 of a key right and 0.18 down every time,
`tool/trainer_sim`'s thumb), sloppy (0.34 and 0.25). On each keyboard the offsets are laid from the middle of the
area each key answers to, in that keyboard's own key sizes; the space bar is aimed at anywhere along its middle
half. So each keyboard gets an equally clumsy thumb on its own keys, and each keyboard's idea of where thumbs land
is taken to be right for its user. A person is taken to type as the bench has them: lower case throughout, a
contraction without its apostrophe (dont, im), the comma and the closing mark without a slip (the full stop from
its key, a question mark from the ?123 page and back).

The thumb never presses a key that is not a letter or the space bar: a tap that would (shift, backspace, ?123, the
emoji key, the row above the keys) is pulled back along its line toward where it was aimed until it is on a letter
or the bar. The bench's thumb cannot leave the letters either; here the reason is plainer. A robot that hit ?123 by
accident would type digits for the rest of the sentence, where a person would see it.

**The sentences** are Tatoeba's held-out English ones (every 20th of the export, never counted for Ghost
Keyboard's starter tables), the first 300 the thumb can type from line 90,001 on, a stretch no mode of the typing
bench reads, so the costs autocorrect was tuned with never saw them. Passed over: lines with digits or other marks,
two sentences on a line, an acronym in capitals, and any apostrophe that is not one of the bench's contractions
(it's, Tom's: no sequence of keys says which was meant).

**The run** (`run.py`). Each keyboard in turn is cleared of what it has learned (`prepare.py`: Ghost Keyboard by
`pm clear`, so it is a new install at its defaults; Gboard by its own "Delete learned words and data", its settings
left as found, which were its defaults), made the keyboard in use, measured, and typed on: a touch down, held 55 to
95 ms, lifted, 150 to 250 ms from one touch to the next (kept: 197 ms at the middle on Ghost Keyboard, 205 on
Gboard). Before each sentence the field's record must say the keyboard under test is the one in use, the field is
in front and the keyboard is up at the height it was measured at; nothing is touched for 0.7 s after a field is
entered, because for about 0.3 s **neither keyboard's keys are yet where they will be** (a tap 0.1 s in types a
key two rows up, on both). `prepare.py` saves Ghost Keyboard's data and the keyboard in use first, and puts both
back afterwards.

**A stalled emulator types digits** (`timing.py`). A touch held 300 ms is a long-press on both keyboards and types
the key's other character. The emulator is a guest of this Mac, and twice during the run the Mac was starved (down
to 300 MB of disk; a load average of 80 from other builds): the emulator stopped for a moment in the middle of
touches, and "yet" came out "ye5", a full stop "?", and one whole sentence "2e!e3d m9435ruc(34#.". Both keyboards
were hit, in different sentences. Such a sentence says nothing of a keyboard. `run.py` now reads the emulator's own
clock before each touch goes down and after it lifts (`$EPOCHREALTIME`, a shell variable, so it costs nothing), and
a sentence in which any touch took 250 ms or more is typed again, up to six times; what was thrown away is kept in
`<keyboard>.stalled.jsonl`. The 2026-11-11 run was made before that existed, so its sentences were held to the
times the field marked each key press at: 88 of 1,800 had a key press 150 ms or more late and never caught up, or a
touch that changed nothing, and were typed again with the clock on. All 32 sentences with a digit or an accent in
them were among the 88; none is left.

**The score** (`score.py`), the same for every keyboard, from the text standing in the field. The field's words
are lined up against the sentence's (capitals and marks aside; a curly apostrophe is a straight one). A word of the
sentence that is not there as written is a **word wrong**. Each is one of two kinds:

- **left wrong**: the thumb slipped on it (a tap off its key, a letter left out, doubled or swapped, a space missed
  or landing on a letter) and the keyboard did not mend it;
- **spoiled**: the thumb typed it as meant, every tap on its own key and the spaces either side on the bar, and the
  keyboard changed it all the same.

"On its own key" is by each keyboard's keys as they stand at the start of a sentence (Gboard's measured cells; Ghost
Keyboard's own reading of a touch). Every figure has a 95% interval from resampling whole sentences 2,000 times, the
same draws for every keyboard, so the gap between two keyboards has an interval of its own.

**The same taps on the JVM** (`replay.sh`, `replay/Replay.kt`). Ghost Keyboard's decoder is its own source,
compiled as it stands. What stands between a touch and the decoder lives in an Android view and a service and
cannot run on the JVM, so that part is read across: `Board` is `KeyboardView`'s layout, touch correction and
`keyAt`; `Typist` is what `DevKeyboardService` does with a tapped letter, a space and a mark. Both are held to the
emulator: the 507 calibration taps must each mean the key the emulator typed (`replay.sh probes`; they do, edge
pixels and all), and `score.py` counts the sentences whose text on the JVM is the emulator's, character for
character: 900 of 900.

A run is replayed on the keyboard it was typed on, as the build of that day typed. Each run keeps its own copy of
the measured keys (`<run>/ghost.geometry.json`), and `Board.recorded` lays them out from that, not from the layout
code in the tree: 0.16.2 gave the space bar the { } key's place, and on the tree's keys 2 of the 507 calibration
taps of 0.15.0 already mean another key. A run's `meta.json` says where the build typed otherwise than the tree
does (`"replay": ["--marks-without-taps"]` for 2026-11-11). With both, the count on main at 0.16.2 is still 900 of
900: the decoder has not changed since 0.15.0, and its 421 unit tests pass with the fix in.

## What these numbers are not

- **A robot's taps are not a person's.** Every touch is one point, held a moment and lifted: no roll of the thumb,
  no slide, no two thumbs down at once, always the same size and pressure, at a pace with no rhythm in it. A
  keyboard that reads any of those gets nothing from them here.
- **The thumb is ours.** Its misses are the same bell curve on every key, each tap independent of the last; a real
  thumb misses differently by key, by hand and by what came before. It is centred on where each keyboard's keys
  answer, which takes each keyboard's idea of where thumbs land to be right for whoever is typing. Ghost Keyboard's
  idea is one person's thumb (Matt's). For a thumb that lands where the keys are drawn (`whatif.py --aim drawn`)
  the JVM gives it 3.4%, 14.1% and 17.2% in place of 2.7%, 7.7% and 16.1%: the measured offsets are a gift to the
  thumb they were measured from and a tax on any other.
- **It never presses a key that is not a letter or the space bar**, never takes a word from the row above the
  keys, never goes back to put a word right, and never swipes. What is measured is what autocorrect leaves when
  nobody is looking: a part of how good a keyboard is, not all of it.
- **Question marks come from the ?123 page** on both keyboards, and commas too on Ghost Keyboard, which costs it
  the taps of the word before ([above](#the-word-before-a-question-mark-is-read-without-its-taps)): about a
  point of its sloppy figure. 69 of the 300 sentences are questions, 5 end in an exclamation mark and 16 have a
  comma.
- **Nothing is learned.** Both keyboards were asked not to learn, so each is the keyboard a new install is. A
  keyboard a person has typed on for a month knows their words; which of the two gains more from that is not here.
- **The sentences are Tatoeba's**: short, plain, written to be translated, with a great many Toms and Marys, and
  some British spellings a keyboard set to English (US) may "mend". Ghost Keyboard's starter tables were counted
  from the other nineteen in twenty of the same collection, so these sentences are nearer what it knows than a
  stranger's messages would be, and that leans the comparison its way. Sentences from somewhere else
  (`run.py --file`) would say by how much.
- **One emulator, one screen**: 1248 by 1972 at 420 dpi, 475 dp across, wider than most phones, so keys are bigger
  in the hand than a phone's though the thumb's misses are counted in key sizes. Gboard there is the image's own
  (a preload, signed in to nothing), not this month's from Google Play. Ghost Keyboard there was a debug build
  another session had installed (its Kotlin and word lists the same as `sherlock`'s, which the JVM replay bears
  out).
- **The emulator can stall**, and a stall in the middle of a touch changes what is typed. Every sentence was
  checked for it and 88 were typed again, but for the 1,712 typed before the emulator's clock was read the check
  is the field's key-press times, which cannot see a stall in a sentence's very first touch.
- **English (US), default settings, one seed.** A gap smaller than its interval is not a gap; the careful thumb's
  (+0.1 to +1.6) only just is one.

## Not measured, and how it would be

- **SwiftKey.** It is not on the emulator, and was not fetched from anywhere but Google Play. Installed from Play
  on an emulator signed in for the purpose, `tool/versus/versus.sh --keyboards swiftkey --name 2026-11-11` adds it
  to the same run: it is in `device.py`'s list, and `calibrate.py` reads its keys from the accessibility tree if
  it puts them there, else takes `--hint` (a file of roughly where the keys are, from a screenshot) and measures
  from that. What it has learned has to be deleted in its own settings by hand. About two hours.
- **Swipe typing.** The same field, keys and sentences; the stroke is the bench's (`Bench.kt`'s `stroke`, a path in
  key sizes with its three glides) laid on each keyboard's keys and injected as one touch down, a move every 15 ms
  and a lift (`input motionevent DOWN/MOVE/UP`; an `input` call takes 7 ms here). Score: a word a stroke, right as
  first read. The JVM side is `SwipeDecoder` on the same points. A stall matters more here than for taps, so the
  clock is read around every stroke.
- **Next-word suggestions.** Gboard puts the three words of its strip in the accessibility tree, so
  `uiautomator dump --windows` after each word reads them (a second or two a word, so over an hour a keyboard).
  Ghost Keyboard draws its row and tells the tree nothing: either the row is given content descriptions (a small
  change to `PluginRow`, good for screen readers too), or its side is read from the JVM, which types as the
  emulator does. Score: the next word among the three, and the key presses saved by taking it.
- **A second kind of sentence.** `run.py --file` types any file of sentences. MacKenzie and Soukoreff's 500
  phrases are what text-entry studies use, and are no keyboard's home ground.
- **The question mark by holding the full stop**, which keeps Ghost Keyboard's taps: a touch held 350 ms and
  lifted, as a mark's recipe in `calibrate.py`. Only the JVM's estimate of it is above.
