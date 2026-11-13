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
through `Replay.kt`'s model of the service. Not yet typed on a device. Still cut in two: a word whose first letter
landed on the bar (" egim" for "begin": the tap comes after the last word's own space, and makes a full stop of
it), and scraps that were mended at the bar's tap into a word too good to give up ("eome" made "some", for
"women").

### A word weighed by its place in the sentence (made 2026-11-12)

A reading of a word's taps was worth its word's frequency plus a lift from the words before, capped at 1.5, and a
common typed word gave way only to a reading worth 1.6 more. So the words before could choose between two good
readings and could never say that a word does not come here: "forgot about is", "need to rat more" and "I'd tell
you of I knew" stood, is, rat and of being common words. 23 of the 199 words still lost to Gboard were slips that
spell a common word, 9 spelled a rare one ("yom" for Tom, five times), and in others a wrong reading was put first.

`Scorer.Here` (NextWordModel.kt) now says how likely a word is after the one or two words before it, by the starter
tables. A follower a row lists has its share of what followed. A word the row does not list has its part of what
the row leaves over, by its own frequency, and is held to a fifth of the most it could have followed and stayed
unlisted (`UNLISTED`). A row is believed as far as it was seen, and the word's own frequency for the rest. The
person's own counts lift a word as they do in the row above the keys, which is itself unchanged.
`Autocorrect.byTaps`, `rejoin` and the second of two words run together weigh every reading by it, and the typed
word too (`atPlace`): a word no row bears on is worth its frequency exactly, a listed one more ("your" after "them
with" 6.94 for 6.53), and a common word in a place it all but never stands less ("of" after "tell you" 6.09 for
7.4), so that it is cheap to replace. The words a row lists are each held up against the taps, since the search goes by
frequency and would pass over a word that is rare and likely here. The place's say counts 0.7 times
(`HERE_WEIGHT`), and at a place a common typed word gives way to a reading worth 1.0 more (`HERE_COMMON`). Where no
table has a row for the words before, where next-word prediction is off, and in every language but English, a word
is weighed exactly as it was. Since the code review (below) a listed word with every tap well inside its own key is
weighed as it was too, and the place never reads a word as its apostrophe spelling.

| | careful | leaning | sloppy | all | Mended | Broken |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| the run's 900 typings, before | 2.5% | 5.4% | 10.9% | 6.3% | | |
| after | 2.0% | 4.6% | 9.6% | 5.4% | 58 | 12 |
| the 900 unseen typings, before | 3.3% | 5.0% | 12.2% | 6.8% | | |
| after | 2.7% | 4.2% | 10.9% | 5.9% | 77 | 20 |

That is 326 words wrong of 5,205 to 280, and 437 of 6,408 to 380 (278 and 377 before the code review's changes).
Of the words Gboard got and this did not, 168 are left where there were 201, and 87 go the other way where 74 did.
By what stood in the decoder's way (`fixtures.py --still`, on the tree before the code review), 158 of the list's 326 are still lost where 199 were: a letter left out, doubled or swapped
46 to 32, more letters off their keys than the search will change 18 to 9, letters that spell a rare word 9 to 1,
letters that spell a common word 23 to 18, no word typed and the word meant within reach 55 to 51, and the space's
42 to 41. On 12,000 of the typing bench's own words, by its three thumbs (careful, normal, sloppy):

| | Words right | Right words spoiled in 1000 | Slips that spell a word, mended | Joins fixed | Right words no list has, spoiled |
| --- | ---: | ---: | ---: | ---: | ---: |
| before | 96.41 / 92.34 / 85.65% | 1.22 / 1.48 / 1.65 | 290 of 426 / 507 of 715 / 761 of 1,082 | 60.0 / 61.5 / 56.4% | 1.5 / 4.3 / 7.1% |
| after | 96.87 / 93.19 / 87.24% | 1.93 / 1.75 / 2.07 | 333 / 560 / 825 | 62.2 / 64.1 / 59.4% | 2.3 / 3.0 / 6.0% |

**What it costs.** A right word that is rare in its place, and a slip away from a word the place expects, is
changed more often: 42 right words spoiled where there were 31, in the bench's 36,000 typed (nest to best, vase to
base and gadded to added, each by all three thumbs; hunch to bunch; done to some). The bench's own measure, words
right less four for every right word spoiled, goes from 93.76 to 94.60. With the place counted in full
(`HERE_WEIGHT` 1) it spoils 3.04, 2.83 and 4.14 in a thousand and that measure is 94.30: the tables are surer of
themselves than they have reason to be. The weight was set where the measure peaked, with the two sets of
sentences beside it (94.53 at 0.8, 94.59 at 0.7, 94.50 at 0.6, before a join's second word was weighed as it now
is; `Autocorrect.HERE_WEIGHT` has the table).

**English's one-word rows now say how often their word was seen** (`=n`, as two-word rows did), because the shares
cannot. Three sightings get a follower listed, so three over a row's least share should be the count; but the last
followers of most rows are names the table's maker scaled down ("taught ... Tom:8 Layla:1"), and a row of one
follower says nothing. That guess is within a quarter of the count for 72 in 100 of the 18,171 rows, more than
twice it for 6 (7.8% of all places) and under half it for 9. By that guess "it" after "taught" was held to half of
one in ten thousand. `tool/next_words.py` writes the counts for English alone (`SEEN_ONE`), the table is 78,969 bytes
longer (30,507 compressed), and a keyboard from before skips the entry. Every other language's file is as it was,
and its words are weighed as before until its table is made again: `Scorer.Here` goes by no table whose one-word
rows do not say how often they were seen (`NgramTable.counted`), and where such a table has a row for the words
before, no other table is gone by either. Made again, English's file also took in ten two-word rows after a
sentence's start that the tokenizer has counted otherwise since a picture character began a sentence
(`next_words.py --check en` said DIFFERENT before this, and says same now). The row above the keys is as it was:
`tool/next_word_eval.sh` gives the same 20.0% for its first word, 33.8% for its three and 50.2% of key presses
saved.

**The smoothing was set on held-out likelihood, not on these sentences** (`tool/versus/likelihood.sh`: Tatoeba's
held-out lines 1 to 60,000, then lines 60,001 to 90,000 once, which chose nothing). What is measured is what a
written word was worth among all the listed words at its place, their shares there made to sum to one, in the
frequency's own units: 5.979 by frequency alone and 7.006 by `Scorer.Here` on the first lines, 5.921 and 6.735 on
the later ones. `UNLISTED` 0.2 is where the 60 commonest words are expected about as often as they were written in
places no row lists them (5,082 times against 5,391; 3,716 at 0.1, 7,734 at 0.5; on the later lines 3,978 against
3,990). How many sightings a row's belief is weighed against (`PRIOR_HERE_ONE`, `PRIOR_HERE_TWO`, 15 each) hardly
matters now that every row says how often it was seen: 4 and 60 give 7.006 and 7.005 for the first, 7.008 and
6.998 for the second.

**How much of this is Tatoeba's.** The tables were counted from Tatoeba and every sentence above is Tatoeba's, so
the place looks better here than it will in a person's own writing. Two measures of that. 14 of the 60 words mended
on the run's sentences are "Tom" (7 of the 80 on the unseen ones), which the tables expect at the start of a
sentence and few people's messages do. And `tool/versus/elsewhere.py` cuts sentences from this repository's own
documents, 287 that the thumb can type (2,639 words), plain prose about a keyboard with its own words in it:

| 287 sentences that are not Tatoeba's | careful | leaning | sloppy | Mended | Broken |
| --- | ---: | ---: | ---: | ---: | ---: |
| the place not read (`--set Autocorrect.HERE=false`) | 3.1% | 7.3% | 14.1% | | |
| as the tree is | 2.8% | 6.3% | 13.6% | 83 | 38 |

So away from home the place still mends about twice what it breaks, and is worth about three fifths of what it is
on Tatoeba's unseen sentences: 0.57 of a point of words wrong there (645 of 7,917 to 600), 0.94 here. It spoils 17
right words there where 9 were. These are documents in one voice, not messages: what they say is that the gain is
not all Tatoeba's, not how large it is in a person's own writing.

**Why a word was left or changed.** `tool/versus/why.py` types the run again and prints what `byTaps` weighed for
every word left wrong (`--lost`: those Gboard got; `--typed`, `--meant`: a pattern; `--set unseen`): the typed word
and what a reading needs, then each reading with its frequency, what it is worth at its place, what its slips cost
and what that leaves, and the word meant whether or not it was among them. `Autocorrect.TRACE` is what tells it,
and on a phone nobody listens.

    sloppy/90152  meant me, typed mr, stands mr  WRONG   | May I ask you to do me a favour?
          mr after "May I ask you to do": typed mr: listed at 5.69 (common), worth 5.2430506 as it stands here; a reading needs 6.2430506
          meant me: frequency 6.48, worth 6.514906 (a row lists it), slips 0.27622107   (not among the readings)
          chosen: none, it stands

**What the words before cannot say.** 18 of the 23 slips that spell a common word still stand, and `why.py` shows
that for most of them the words before do not decide: "is" and "us" are worth the same after "forgot about" (6.85
and 6.82), "ate" is listed after "we" and reading its t as an r costs 2.61, "if" after "tell you" is worth 6.53
where "of" is worth 6.09. What tells them apart is the word after ("of I knew", "ate going to", "top expensive"), which
the decoder never looks at; `DevKeyboardService` already keeps the last word with its taps until the next one ends,
for `rejoin`, and that is where a second look would be taken. Also not done: a name the tables do not list is
worth only its frequency beside a word the place expects; a slip in a British spelling is mended to the American
one where only that follows in the table ("neighbiur" to neighbor); and a word mended wrongly at a space misleads
what comes after it ("ea t" for "want": "ea" was read "was" at the bar's tap, and `rejoin` holds what stands at its
full worth, with nothing off for what that reading's slips cost).

**Tried and not kept**, each on the bench and both sets of sentences: a bound on how unlike its typed letters a
word the rows list may be read, counted in letters (the bench's measure 94.27 for 94.53 at weight 0.8, seen 5.84%
for 5.42%) and by what the slips cost (94.19, 5.73%), which spare a few right words no list has and lose the sloppy
thumb's words; the place counted in full up to a lift of 1.5 or 1.0 and by half beyond (94.46 and 94.44 for
94.59); a common typed word never read with a letter missed or added (`LISTED_EDITS` off: 94.34, seen 5.53%,
unseen 6.09%); the bar for a common word at 1.3 (94.44, 5.51%, 5.99%, with 1.83 / 1.48 / 1.86 spoiled) and at 0.8
(94.58, 5.34%, 5.88%, with 2.13 / 2.02 / 2.69).

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
