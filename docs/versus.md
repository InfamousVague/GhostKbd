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

### Where this thumb lands (made 2026-11-12)

The leaning thumb of the run is the careful one with a lean: every tap lands 0.24 of a key to the right and 0.18
of a key's height below where it aims. Ghost Keyboard left 2.5% of the careful thumb's words wrong and 5.4% of the
leaning one's, so half of what the leaning thumb loses is the lean and nothing else. A person's thumb leans too,
by hand, grip and phone, and not the same way from one sitting to the next
([versus-research.md](versus-research.md), section 4). The keyboard had two things for that: offsets measured from
one person's taps (`KeyboardView.LETTER_OFFSETS_DP`) and the adaptive keys plugin, which moves single keys over
weeks. Neither knows where the thumb is landing now, and neither is changed by this.

**The estimate** (`ThumbLean.kt`, no Android in it). For the field being typed in: how far from the keys' middles
this thumb's taps fall, across and down, in key widths. It begins at nothing as a field is entered. Every word
autocorrect has settled teaches it, the letters that stand each against its own tap, where the word can be trusted
(`Autocorrect.settled`: a word of the lists or one of the person's own, with a tap for every letter; a tap further
than a key and a quarter from the key it is now read for says nothing). It holds forty taps, so that what is older
fades. It is in memory only and gone when the field is left; nothing in it can write it down, and a password field
never teaches it, since no word is corrected there. The estimate is not the taps' mean miss but the lean to expect
given that mean: one field in five is taken to be typed by a thumb that leans (`ODDS` 4), by about 0.3 of a key
each way, and the mean counts for as much of itself as the taps make that likely. Three taps say nothing; a dozen
of a careful thumb say most of it; a sloppy thumb's mean seldom stands out of its own scatter.

**The reading** (`TapDecoder.load`). Each tap of the word being settled is taken to have landed that much nearer
where it was aimed. A key's cost for a tap is what it was and twice the lean along the line from the typed key to
it; the cheapest key is the one the tap was aimed at, every cost counts from that key, and the typed letter pays
what it is further off (`TapDecoder.own`). `Autocorrect.byTaps` and `rejoin` take that off what the typed word is
worth: off its bar, off the frequency a rare word gives way at, off the bar two joined words must clear. So "yom"
with its first tap just over the line from t, by a thumb known to land to the right, is "tom" typed, and gives way
to it. The space bar's tap is read the same way, as a letter and as a space. With no lean estimated nothing is
added and every number is as it was: with `ThumbLean.ON=false` the scoreboard is the tree's before this, word for
word (0 mended, 0 broken on both sets).

**The service** tells it of fields and settled words (`DevKeyboardService`: a new field and a field left begin it
again, `tapsFor` and `tapsAcross` read by it, `correctLastWord` teaches it, a fix taken back with backspace is
unlearned). `Replay.kt`'s Typist does the same for a line of taps, which is a field, and `Bench.kt` for a sentence.

Words wrong, by `tool/versus/scoreboard.py` (the three thumbs, then all three):

| | careful | leaning | sloppy | all | Mended | Broken |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| the run's 900 typings, before | 2.48% | 5.42% | 10.89% | 6.26% (326 words) | | |
| after | 2.48% | 4.38% | 11.01% | 5.96% (310) | 22 | 6 |
| the 900 unseen typings, before | 3.28% | 5.01% | 12.17% | 6.82% (437) | | |
| after | 3.32% | 4.03% | 12.13% | 6.49% (416) | 26 | 5 |

By thumb (`tool/versus/saved.py base done`): the leaning thumb has 76 words wrong where it had 94 and 86 where it
had 107 (19 mended and 1 broken, 22 and 1); the careful thumb 43 as before and 71 where 70; the sloppy thumb 191
where 189 and 259 where 260 (3 mended and 5 broken, 4 and 3).

**The first words of a field have no evidence.** The leaning thumb's words wrong by the word's place in its
sentence (`saved.py --places`), beside what a lean known from the first tap would leave (`ThumbLean.FIXED_X=0.24`
and `FIXED_Y=0.2286`: the run's keys are 1.27 times as tall as wide):

| Word of its sentence | 1st | 2nd | 3rd | 4th | 5th, 6th | 7th to 9th | 10th on | all |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| seen: words there | 300 | 300 | 293 | 231 | 327 | 198 | 86 | 1,735 |
| wrong before | 9 | 17 | 20 | 16 | 12 | 14 | 6 | 94 |
| wrong after | 9 | 17 | 17 | 12 | 8 | 8 | 5 | 76 |
| with the lean known | 3 | 11 | 12 | 11 | 7 | 8 | 5 | 57 |
| unseen: words there | 300 | 299 | 296 | 280 | 443 | 364 | 154 | 2,136 |
| wrong before | 12 | 12 | 14 | 16 | 26 | 20 | 7 | 107 |
| wrong after | 12 | 10 | 12 | 16 | 19 | 13 | 4 | 86 |
| with the lean known | 9 | 11 | 10 | 15 | 18 | 14 | 4 | 81 |

From the fifth word on the estimate does what a known lean does. The first three words are where it falls short:
17 of the 19 words between the two on the seen set. Those sentences are short (5.8 words; the unseen ones 7.1),
and they begin with short words (Tom, I, the), so two words in there are often five taps to go by. Believing the
taps sooner (`ODDS` 1) reads the leaning thumb's 69 and 86 words wrong and the sloppy thumb's 193 and 258.

**Kept from one field to the next** (`ThumbLean.KEEP`, which is off and is there to be measured) the leaning
thumb's words wrong are 58 and 80, and the other two thumbs' 43, 189 and 69, 259. So an estimate that begins again
at every field gets 18 of the 36 words a kept one would mend on the seen set and 21 of the 27 on the unseen one.
It is not kept. The field the run types in asks keyboards to learn nothing, so that one sentence teaches nothing
for the next; an estimate that lives and dies with a field keeps to that, and each sentence of the run is a new
field (so the replay and the bench begin it again at every one, as the phone does at every field entered). What a
thumb is doing in this field is also the only thing it is an estimate of.

**Where the estimate stands** (`tool/versus/leanlog.py`, from the replay's own log of every word). The first word
of every sentence is read by no lean: 0 of 300 for each thumb on each set (and 299 of 300 with `KEEP`, so the
count can fail). At a sentence's end the leaning thumb, whose lean is 0.24 of a key across and 0.229 down, is
taken to lean 0.205 across and 0.199 down on the seen set (spread 0.066 and 0.062) and 0.222 and 0.207 on the
unseen one (0.054 both ways): over a tenth of a key in 287 and 294 of its 300 sentences. The careful thumb is
given over a tenth of a key in 1 sentence of 300 and 2 of 300 (0.15 and 0.25 at the most), the sloppy thumb in 9
and 6 (0.27 and 0.38 at the most; in nine sentences of ten under 0.05 and 0.02). That is the estimate wandering,
and what the sloppy thumb's broken words below come of.

**The typing bench** (12,000 words; its three thumbs as they are, and all three given a lean with `-Dlean=`).
Words right:

| The bench's thumbs | | careful | normal | sloppy |
| --- | --- | ---: | ---: | ---: |
| with no lean | before | 96.41% | 92.34% | 85.65% |
| | after | 96.41% | 92.35% | 85.58% |
| leaning 0.24 right, 0.18 down (the run's thumb) | no estimate | 92.53% | 85.95% | 76.54% |
| | after | 93.47% | 88.49% | 80.51% |
| | kept, one long field | 93.88% | 89.49% | 83.55% |
| leaning 0.2 left, 0.1 down | no estimate | 94.28% | 89.32% | 81.19% |
| | after | 94.85% | 90.13% | 82.82% |
| leaning 0.12 right, 0.09 down | no estimate | 95.56% | 90.97% | 83.55% |
| | after | 95.74% | 91.23% | 83.97% |
| leaning 0.2 up | no estimate | 94.78% | 88.59% | 79.10% |
| | after | 95.10% | 89.37% | 80.27% |
| | kept, one long field | 95.23% | 89.92% | 81.92% |

- **A thumb with no lean pays next to nothing.** Right words spoiled (1.22, 1.48 and 1.65 in a thousand) and the
  right words no list has that are spoiled (1.5%, 4.3%, 7.1%) do not move at all; the sloppy thumb loses 0.07 of a
  point of words right. On the scoreboard the careful thumb is one word worse on one set and the sloppy thumb two
  worse on one and one better on the other.
- **A thumb that leans as the run's does gains a point to four points**, more the sloppier it is, and its right
  words are spoiled less than before (1.78, 0.91 and 1.71 in a thousand where 2.07, 1.59 and 2.05). A lean of
  half the size gains a fifth to two fifths of a point, one to the left or straight up between the two.
- **Other leans on the run's own sentences** (`whatif.py --thumbs leaning --lean`, 1,735 words, the careful
  thumb's scatter): 0.2 left and 0.1 down, 75 words wrong before and 60 after; 0.12 right and 0.09 down, 63 and
  59; 0.15 left and 0.15 up, 68 and 65; 0.2 straight up, 72 and 72 (4 mended, 4 broken: on these 300 sentences
  nothing, where the bench's 12,000 words say a third of a point).

**What a wrong estimate costs.**

- The run's lean read into thumbs that do not have it (`FIXED_X`, `FIXED_Y`): the careful thumb's words wrong go
  from 43 to 46 and from 70 to 80, the sloppy thumb's from 189 to 261 and from 260 to 353. A careful thumb's taps
  are mostly still nearest their own keys; a sloppy thumb's are not.
- A lean that turns right round every five sentences, typed as one long field (`-Dlean.turn=5` with `KEEP`):
  words right 92.81%, 86.24% and 76.58% with no estimate, and 93.03%, 86.47%, 77.73% with it. Holding 100 taps
  it does no better than no estimate (92.73%, 85.90%, 76.75%), holding 25 it follows sooner (93.22%, 87.24%,
  78.52%) and then costs a steady lean in a long field a third of a point for the sloppy thumb (83.24% where
  83.55%). Forty is the least that costs a steady lean nothing. For a dozen words after such a turn the taps are
  read by the old lean: only a new field ends that at once.
- On the scoreboard an estimate begun too soon is the sloppy thumb's 5 and 3 broken words.

**One lean for the whole board.** A field's taps are a few dozen: enough for one pair of numbers, not for a pair
a row or a side (Gboard keeps 800 taps for seven groups of keys, and found that the number of groups made no
difference a user could see; versus-research.md). What that leaves out shows on a thumb that aims at the keys as
they are drawn (`whatif.py --aim drawn`): the keyboard's own correction lifts its taps more the higher the row, so
its misses differ by row, and one lean fits the top rows and is wrong for the bottom one. Words wrong on the run's
sentences with no estimate and with it: careful 47 and 51, leaning 136 and 94, sloppy 217 and 215. The leaning
thumb gains what the run's does; the careful thumb loses four words, one of them a name typed right and split in
two ("Amenas" read as "one as": its bottom row's taps were read as nearer the bar than they were).

**Tried, and not kept.**

- A key's cost as the lesser of with the lean and without it (the first cut): it mended the sloppy thumb's words
  as well, and the bench showed why, right words spoiled 2.07 and 2.69 in a thousand for the sloppy thumb where
  1.65: it loosens every tap and is not the lean.
- The estimate as the mean with a threshold on how far it stands out of the scatter, in place of the odds:
  leaning 82 and 93 words wrong at the threshold that left the sloppy thumb alone.
- A lean under 0.03 or 0.06 of a key read as none: the sloppy thumb's unseen words wrong 261 where 259.
- The word in hand counted toward the estimate before it is read (its taps against the keys nearest them): 74 and
  85 for the leaning thumb at `ODDS` 16, where plain `ODDS` 4 gives 76 and 86; on the bench no better for leaning
  thumbs (93.37%, 88.14%, 79.42%), and with `ODDS` 4 a sloppy thumb with no lean lost 0.26 of a point.
- Letters autocorrect changed left out of the lesson: leaning 91 unseen where 88 (at `ODDS` 16). Words no list has
  let in, taught as typed: leaning 76 and 85, sloppy 192 and 261.
- A lean across and a lean down each believed on its own evidence: the leaning thumb's 76 and 86 again.
- Two joined words held to their own bar whatever the typed letters cost: nothing on the scoreboard, and three
  more words wrong (97 where 94) for the leaning thumb that aims at the keys as drawn.

**Not measured.** People: these are simulated thumbs whose lean is the same on every key, and `ODDS` is a belief
about how many people lean that only recorded typing can settle. A phone: the service's part is held to its
source by `ThumbLeanTest` and modelled by the Typist, but no build of it has been typed on. Speed, properly: the
bench's time a word was read on a Mac with a load average of 50 to 200, where it swings by half from one pass to
the next. The fastest of five passes of the tree before this was 196, 303 and 495 µs a word for the bench's three
thumbs, and the fastest of six of this tree 197, 299 and 412: no difference that could be measured there. The work
added is one more pass over the keys for each tap of a word read by a lean, and a word's taps summed once.

### How tightly this thumb types (made 2026-11-13)

One dial says how tight a thumb is taken to be: `TapDecoder.SPATIAL` (1.5), what a key width squared of extra
distance from a tap costs a reading. The measure's careful and leaning thumbs scatter 0.20 of a key across and 0.15
down, the sloppy one 0.34 and 0.25, and they want different values of it. With `SPATIAL` fixed for every thumb
(`ThumbLean.SPREAD_FIXED`, joins read as below), words wrong:

| SPATIAL | seen careful | leaning | sloppy | unseen careful | leaning | sloppy |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0.6 | 3.23% | 5.24% | 8.70% | 3.79% | 5.10% | 10.72% |
| 0.9 | 2.59% | 4.38% | 9.22% | 3.46% | 4.17% | 10.30% |
| 1.5 (base) | 2.48% | 4.38% | 11.01% | 3.32% | 4.03% | 12.13% |
| 1.7 | 2.42% | 4.27% | 12.22% | 3.37% | 4.07% | 13.86% |
| 2.0 | 2.36% | 4.27% | 13.66% | 3.46% | 4.49% | 15.73% |

With `TapDecoder.SPATIAL` itself fixed (joins read as loosely as words; build/scatter/ceiling-V.txt), words wrong
and the bench (words right, right words spoiled a thousand, unknown words spoiled of 263, 164 and 84):

| SPATIAL | seen | unseen | bench careful | normal | sloppy |
| ---: | ---: | ---: | --- | --- | --- |
| 0.9 | 278 | 385 | 96.34%, 3.25, 8.7% | 93.09%, 3.50, 12.8% | 87.96%, 3.31, 13.1% |
| 1.0 | 283 | 394 | 96.43%, 2.23, 5.3% | 93.13%, 2.56, 8.5% | 87.65%, 2.69, 10.7% |
| 1.1 | 292 | 403 | 96.42%, 2.13, 4.9% | 93.02%, 2.15, 7.3% | 87.37%, 2.69, 10.7% |
| 1.2 | 291 | 397 | 96.47%, 1.73, 3.4% | 92.88%, 2.02, 6.7% | 86.91%, 2.48, 9.5% |
| 1.3 | 296 | 404 | 96.43%, 1.62, 3.0% | 92.70%, 1.61, 4.9% | 86.46%, 2.28, 9.5% |
| 1.4 | 299 | 409 | 96.39%, 1.52, 2.7% | 92.55%, 1.61, 4.9% | 86.06%, 1.65, 7.1% |
| 1.5 (base) | 310 | 416 | 96.41%, 1.22, 1.5% | 92.35%, 1.48, 4.3% | 85.58%, 1.65, 7.1% |

**What the spread buys, said plainly** (the review of 2026-11-13). Moving that one dial to 1.2, with no new code,
gives words wrong of 291 and 397, within noise of the spread's 288 and 393, and the normal and sloppy thumbs then
spoil exactly what they spoil with the spread (2.02 and 2.48 a thousand). 1.0 gives 283 and 394. Over the best
fixed `SPATIAL` at the same bench cost, then, the spread gains nothing measurable in words wrong; what it buys is
the careful thumb's spoils, which a lower fixed `SPATIAL` raises and the spread lowers: 1.01 right words spoiled a
thousand against 1.73 at 1.2 (1.22 in base), and 3 unknown words of 263 against 9 (4 in base). Its words right go
the other way by a little: 96.33% against 96.47%, 92.75 against 92.88, 87.04 against 86.91. The two together, the
spread with `SPATIAL` at 1.2: seen 270, unseen 386, but the careful thumb spoils 1.62 a thousand and 7 unknown
words, the normal 2.69 and 14, the sloppy 3.10 and 10, which breaks the rule that the careful thumb's spoils must
not rise.

**The ceiling.** Each thumb at its own value with the spread known from the first tap (careful and leaning 1.7,
sloppy 0.9, the floor below): seen 5.30% (276 of 5205), unseen 5.91% (379 of 6408), from 5.96% (310) and 6.49%
(416). With nothing in the way (sloppy 0.6 and joins as loose as words, `TapDecoder.SPATIAL` fixed): seen 258,
unseen 381.

**The estimate** (`ThumbLean.tight`). The lean already sums each trusted tap's miss and its square for the field
(`ThumbLean.learn`: the same taps, the same forty-tap memory, the same lesson taken back with a fix); their spread
about their own mean is the scatter of this thumb about where it lands, each way. It begins at `SPREAD` (0.065 key
widths squared), worth `SPREAD_TAPS` (4) of the field's own taps. Nothing new is kept and nothing is written down;
a password field never teaches it, as it never teaches the lean.

**The reading** (`TapDecoder.load`). The word being settled is read with `SPATIAL` times `Taps.tight`, which is
`SPREAD` over the estimate (one over the spread squared) where the taps fall closer than `SPREAD`, and that ratio
cubed (`SPREAD_POWER`) where they fall wider, between 0.6 and 2 times `SPATIAL` (`SPREAD_LOW`, `SPREAD_HIGH`;
0.9 and 3 at 1.5). The bounds were values of `SPATIAL` itself until the review; with `SPATIAL` at 0.9 that floor
left the spread nothing to do (seen 277 on, 278 off). As factors they move with it, but the dials do not carry
over: at `SPATIAL` 0.9 the spread now gives seen 263, unseen 389 against 278 and 385 without it, and the bench's
normal thumb spoils 5.79 right words a thousand and 31 unknown words of 164. A track that moves `SPATIAL` must
re-set these (build/scatter/laws*.py forecast them from the sp-V grid). A field's first word has nothing estimated
and reads exactly as before. Only with English's list alone and the next-word model on (`Autocorrect.spread`): with
English and another language, with prediction off, in other languages, and with `ThumbLean.SPREAD_ON=false`, every
word is read as before, and the scoreboard is then the base's word for word on both sets (0 mended, 0 broken) with
the bench's lines identical.

**Which dials saw the unseen set.** Not only the floor and the joins: the forecasts that chose the knee (`SPREAD`),
its weight and the power printed unseen totals beside seen for each of some thirty variants, and one of them
counted the careful thumb's words read below 1.45 on the unseen fields' own taps. The margins between those variants
were within noise, but the unseen set is no clean check of this work.

**What follows the spread.** A word's own reading and the suggestion strip's finish (`Autocorrect.suggest`) do;
nothing measures the strip. The bar's tap read as a letter between two words (`Autocorrect.rejoin`) does not
(`SPREAD_REJOIN` false): following the spread it gave 287 and 394, spared 288 and 393, the bench identical, so it
is spared and reads as before. Two words run together are read as tightly as `SPATIAL` as it stands
(`SPREAD_JOINS` 1): read loosely, letters no list has were split into two words ("mapbox" as "map of", "unalive"
as "I alive"), 28 of the 83 words the bench's three thumbs spoiled. With and without:
unseen 397 and 395 at a floor of 0.5, bench right words spoiled 3.09 and 5.11 a thousand for the normal thumb,
4.96 and 7.03 for the sloppy one. `TapDecoder.WHOLE_CHANGE` times the spread was tried and left out: unseen 388
where 395, but the careful thumb's right words spoiled rose to 1.22 and its unknown words spoiled to 1.9%. The
floor was settled on unseen and the bench together: 0.5, 0.7, 0.9, 1.0, 1.1 gave unseen 397, 395, 394, 400, 407
and the sloppy thumb's right words spoiled 4.96, 3.10, 2.48, 2.28, 2.28.

| | seen careful | leaning | sloppy | all | unseen careful | leaning | sloppy | all |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| base | 2.48% | 4.38% | 11.01% | 5.96% (310) | 3.32% | 4.03% | 12.13% | 6.49% (416) |
| the spread | 2.59% | 4.09% | 9.91% | 5.53% (288) | 3.32% | 4.12% | 10.96% | 6.13% (393) |

On the bench (words right, right words spoiled a thousand, unknown words spoiled with their count):

| | careful | normal | sloppy |
| --- | --- | --- | --- |
| base | 96.41%, 1.22, 1.5% (4 of 263) | 92.35%, 1.48, 4.3% (7 of 164) | 85.58%, 1.65, 7.1% (6 of 84) |
| the spread | 96.33%, 1.01, 1.1% (3 of 263) | 92.75%, 2.02, 6.1% (10 of 164) | 87.04%, 2.48, 8.3% (7 of 84) |

The unknown words spoiled are a handful each, and move by one or three: noise either way.

The careful thumb's spoils fall (it is read tighter) and it loses 0.08 of a point of words right; the normal thumb
spoils 0.54 more right words a thousand and 1.8 points more unknown words, the sloppy one 0.83 and 1.2 points
more, for 0.40 and 1.46 points of words right.

**The law on other thumbs** (`Bench.kt -Dthumbs=0.15:0.11,0.24:0.18,0.30:0.22,0.40:0.29,0.46:0.34`, none of them
the measure's). Words right before and with the spread: 97.63% and 97.68%, 94.53 and 94.63, 89.89 and 90.76,
76.95 and 80.68, 64.99 and 71.13; right words spoiled 1.20 and 0.83, 1.29 and 1.40, 1.28 and 1.91, 0.61 and 1.52,
1.88 and 1.88. The best fixed `SPATIAL` for words right on those thumbs is 2.0 or more, 1.0, 0.8, 0.6 or less,
0.6 or less: about 0.05 to 0.07 over the scatter across squared. That supports the theory, one over the spread
squared, not the dials that ship: the shipped law follows it only on the tight side; on the loose side it is the
ratio cubed and floored at 0.6 of `SPATIAL`, chosen for the bench's spoils, and so cannot reach the 0.8 and 0.6
those wide thumbs want. Their spoils rise too (1.28 to 1.91 a thousand at 0.30 across, 0.61 to 1.52 at 0.40).

**Why 0.08 over the spread squared and not 0.217.** The careful thumb's part is its wild taps: one in sixteen
scatters 2.2 times as widely, and a tap half a key to a key from a key is a hundred times likelier to be one of
those than a tail of the ordinary scatter, so the quadratic that fits the mixture there is 0.06 to 0.11 over the
spread squared, not 0.2 (the fit, by the mixture's own density). That does not explain the sloppy thumb, whose
mixture is close to 0.2 over a key's distance and which still wants 0.6. The search's budgets do not either:
`BUDGET`, `JOIN_BUDGET`, `FINISH_BUDGET` doubled and `WHOLE_CHANGE` 4 at `SPATIAL` 1.5 left the sloppy thumb's
words wrong at 186 and 255 where 191 and 259. What is left is the weighing: frequencies, the next-word lift and the
bars, set with `SPATIAL` at 1.5, count for more against a tap's evidence than a probability would. Not proved.

**A wrong estimate.** A careful thumb read as loose all the way (`SPREAD_FIXED` 0.9): words wrong 2.59% and 3.46%
where 2.48% and 3.32%, bench right words spoiled 2.54 a thousand and unknown words 6.1% where 1.22 and 1.5%. A
loose thumb read as tight (1.7): the sloppy thumb's 12.22% and 13.86% where 11.01% and 12.13%, bench 84.49% right
where 85.58%. A thumb that changes in mid-field (the formula on the measure's own taps, three sentences of one
thumb and three of the other as one field): careful to sloppy, `SPATIAL` is under 1.2 five words after the change
(the median; ten at the ninetieth of 200); sloppy to careful it is slow, over 1.2 after a median of 14 words and
not within three sentences in 68 of 200, since forty taps fade slowly and the loose side is read steeply. A new
field ends it at once.

**A wild tap early in a field.** The spread is a plain variance of the taps within `REACH`; one wild tap in a
field's second word has only the first word's taps and the prior against it. ThumbLeanTest holds what follows: a
careful thumb (0.16 of a key) with one tap 0.9 of a key off in its second word is read looser than `SPATIAL` in
at least 15 of 20 fields, never below the floor, and tight again by the sentence's end. The review's simulation of the
formula (build/review/sim.py; Gaussian thumbs with one tap in 16 wild) has the careful thumb at the floor for 3.0%
and 3.1% of its second and third words, below 1.2 for 5.7%. Holding each tap's square (`SPREAD_CAP`) was measured
and left off: at 0.5, 0.36, 0.25 words wrong 290, 291, 298 and 396, 395, 417; the careful thumb's spoils did not
fall (1.01, 1.01, 0.91 a thousand), the sloppy thumb's words right did (86.93%, 86.75%, 86.26% against 87.04%),
since a held square also holds a sloppy thumb's honest taps.

**By place in the sentence** (`tool/versus/saved.py base done ceil --places`, ceil the ceiling above). The
estimate begins at nothing, so the first words are read as before: the sloppy thumb's unseen words wrong at
places 1 to 4 are 141 in base, 141 with the spread and 127 at the ceiling; from the fifth word on 118, 94 and 93.
The estimate has nearly all of the ceiling from a field's fifth word and none of it before. Early on it can even
work the wrong way: in the review's simulation a sloppy thumb is read tighter than base at 25% of its second words
and 22% of its third (a few taps that happen to fall close).

**Kept across fields** (`ThumbLean.KEEP`, off and to stay off: the field tool/versus types in asks keyboards to
learn nothing). Kept, the spread gives seen 4.96% (258), unseen 5.85% (375), where the kept lean alone gives 290
and 408; bench 96.34%, 93.03%, 87.71% right, spoiled 1.01, 2.15, 2.69.

**Time.** The bench's time a word, two passes each on a Mac less loaded than before: off 114, 132, 144 and 106,
103, 142 µs for the careful, normal and sloppy thumbs; on 81, 117, 178 and 96, 126, 185. The sloppy thumb's words
cost about a quarter more, read loosely (a looser reading lets more of the search through); the estimate itself is
a division and a power once a settled word.

**Not measured.** People, whose scatter is not one Gaussian with one wild share; a phone. The spread is one number
for the board; a thumb that is tight across and loose down is read by their mean.

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
