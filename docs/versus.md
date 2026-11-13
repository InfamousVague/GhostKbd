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
through `Replay.kt`'s model of the service. Still cut in two after this step, and
mended by the next (below): a word whose first letter landed on the bar (" egim" for "begin": the tap comes after
the last word's own space, and makes a full stop of it), and scraps that were mended at the bar's tap into a word
too good to give up ("eome" made "some", for "women").

### The two changes on the emulator (2026-11-12)

A debug build of the tree with both changes (0.16.3, commit 1594724) was put on emulator-5584 and given the taps of
every typing the two changes alter against the tree before them (0668977), by the JVM's own account: 104 of the
900. Forty typings they leave alone were typed with them (`tool/versus/device-check.sh`). The emulator's text was
the JVM's for this tree in 104 of the 104, and the old tree's in none; and in 40 of the 40 others. So what
`KeyboardView` and `DevKeyboardService` do with the bar's tap on a phone is what `Replay.kt`'s model of them does,
on every sentence where it matters. The records are in `tool/versus/runs/2026-11-12-reach-rejoin-check`. (The Mac
was restarted in the middle of the first attempt; the emulator was started again, the check run from its start,
and the emulator put back as it had been found.)

### Words run together, and a word's first or last letter on the bar (made 2026-11-12)

**Run together.** `TapDecoder.joins` found most of the pairs a missed space leaves, and `Autocorrect.byTaps` left
them: two words that are not common together are worth less than `JOIN_BAR`, the bar of a six-letter name, however
plainly the dozen letters they made are no word ("frimsteangerz", "gadvzritchds"). Four things changed.

- **The bar falls with the length of letters that look like no word**: by `JOIN_FALL` (1) for every letter over
  `JOIN_LONG` (6). Not for two words set side by side, which is how a name or a compound no list has is made
  ("worktree", "infamousvague", "windowpane"): where every run of three letters that no common word has lies where
  the join's words meet, and one of the pieces either side of that place is a word as typed, the bar stays where it
  was at any length. The best join speaks for the letters; no other is taken.
- **The search is best-first and stops itself** (`MAX_JOIN_WORK`, 16,000): the likeliest first words first, each
  find raising what the rest must be worth. A first or middle word may have one letter missed, doubled or swapped
  as well as letters on the keys beside their own (`starts`, `JOIN_SLIP`), and three words are looked for
  (`JOIN_THREE`).
- **A first word is read by slips of the thumb only** (`TapDecoder.slip`), and a last word the tables list after
  the first is held to the same bound on changes as any word (`TapDecoder.fits`). Without the first, the name
  "baya" was "buy a" wherever "buy" was likely; without the second, "anneke" was "a week".
- **The words before lift a join's first word by half** of what they lift a single word (`JOIN_LIFT`): the whole
  of it parted names into words that suited their place.

**The first letter on the bar.** The tap comes straight after the space before it and ends no word; the two made a
full stop, and the rest of the word began a sentence ("It did. It rain" for "It did not rain"). The service now
keeps that tap as it keeps the one that ended a word, and when the next word ends `Autocorrect.rejoin` reads the tap
and the word's letters as one word, against the letters as they stand and the best word they make by themselves at
the start of a sentence. The full stop goes, and its capital with it.

**The last letter on the bar.** What stands for the scrap is worth less by what mending it cost ("eome" made
"some", and "women" reads the same taps for less); where the mend supplied the very letter the tap can be ("wome"
made "women") the word comes back as it stands and the second space is its space; and a full stop or a comma
straight after the space is read as a second space is ("tragi ." for "tragic.").

**A double tap lands on one spot.** A full stop by two spaces is two taps in one place; a letter that fell on the
bar and the space beside it land where each was aimed. Where the two landed more than `REJOIN_APART` (half a key)
apart, the one that may be a letter is held as no likelier a space than its place says: `REJOIN_MARGIN` is not
asked. Of the 37 such pairs in the two sets, 3 are less than half a key apart and the middle one is 1.4 keys. No
set here has a double tap that was meant, so the half key is reasoned, not measured.

| | careful | leaning | sloppy | all | Mended | Broken |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| the run's 900 typings, before | 2.5% | 5.4% | 10.9% | 6.3% | | |
| after | 2.5% | 5.0% | 9.3% | 5.6% | 35 | 0 |
| the 900 unseen typings, before | 3.3% | 5.0% | 12.2% | 6.8% | | |
| after | 3.0% | 4.8% | 11.2% | 6.3% | 31 | 0 |

326 words wrong to 291 of 5,205, and 437 to 406 of 6,408. Of the words one of whose letters fell on the bar, 47 of
56 come out right in the run's typings where 39 did, and 57 of 76 in the unseen where 45 did; full stops that nobody
typed, 2 in the run's text where there were 9, and 8 in the unseen where there were 21.

What the two sets cannot show, the bench and two probes do (`scoreboard.py --bench`, `probe.py words`,
`probe.py spoils`):

| | versus before | after |
| --- | ---: | ---: |
| bench, joins fixed (careful, normal, sloppy) | 60.0%, 61.5%, 56.4% | 72.2%, 75.4%, 71.6% |
| bench, words right | 96.41%, 92.34%, 85.65% | 96.59%, 92.81%, 86.74% |
| bench, right words spoiled in a thousand | 1.22, 1.48, 1.65 | 1.22, 1.48, 1.45 |
| bench, unknown words spoiled | 1.5%, 4.3%, 7.1% | 1.5%, 4.3%, 7.1% |
| 166 names and compounds no list has, typed cleanly: changed | 4 | the same 4 |
| 393,000 right words of 30,000 other held-out lines: spoiled (careful, leaning, sloppy) | 430, 247, 179 | 432, 248, 180 |
| bench, slips mended to a wrong word | 3.4%, 4.0%, 5.1% | 3.6%, 4.1%, 5.2% |
| microseconds for `Autocorrect.correct`, a typed word of the run: mean | 194 | 114 |
| the slowest in a hundred, in a thousand | 1,926, 3,039 | 1,226, 2,635 |
| the slowest single word, at its quickest of nine | 2,999 | 3,122 |

The times are the thread's own clock on this Mac's JVM under a load average of 26 to 41, one run straight after the
other (`probe.py --time`); from run to run the slowest word moved between 2.4 and 3.1 thousandths of a second for
either tree. A phone is slower, and by how much was not measured.

A word with slips in it that nothing reads is now sometimes parted where it was left ("in editor" for "invedyor",
which was "investor"): wrong either way, and the wrong word is the harder to see. The bench's share of slips mended
to a wrong word says how often. A join under the bar as it was is therefore taken only where no single word reads
the letters for as much as that bar, which brought that share most of the way back (it was 3.7%, 4.3% and 5.3%
without).

Not typed on a device: the emulator check above was of 1594724, before this work. `Replay.kt`'s `Typist` mirrors what `DevKeyboardService` does with the bar's taps (`Cut`,
`rejoined`, `together`, the mark after the space), and that mirror is all that runs: nothing on the JVM runs the
service itself, backspace after a rejoin among it. Still lost: joins of two exact words one of which is rare ("tom
hushed", which reads as "tomb" and "hushed" set side by side), a second word no list has ("has tendonitis"), a
first word too far off its keys for a join's budget ("kerd" for "need"), a word cut in two and run into the next
("a otherbdrpartmeny"), and a letter that fell in the bar's middle, which is a space as far as a tap can say.

After review (2026-11-13), with nothing in English reading differently (seen 291, unseen 406, the bench the same):

- **English only.** All of the above, not just the fall of the bar, is for English being loaded. Without English the
  joins go by `TapDecoder.pairs`, versus's search kept word for word, and `Autocorrect.rejoin` reads no first scrap,
  no closing mark (its `mark` argument), no mend's cost and no `doubled`. On 15,000 typings in ten other languages
  (two common words run together, or with the space's tap on a letter, and a word cut by the bar) the tree read 6
  of 22,259 lines otherwise than versus before this, and none after. With English and another language loaded
  together the other is typed under English's rules, and nothing measures that.
- **The service compiles.** `DevKeyboardService.rejoined` had two `val after`, which Kotlin refuses: the app did not
  build, and nothing here built the service to say so. `tool/versus/service_check.sh` compiles the app's main
  sources (MainActivity and BuildConfig stubbed) against the SDK's `android.jar` and Gradle's cached jars, without
  Gradle; it is not a build.
- **The mirror is the service's.** The `Typist` now rejoins after a closing mark only for `.,!?;:` and only where
  what stands before the scraps ends in no letter, digit or `` @#/._-\:$`'’ ``, as `rejoined` does; the 900 typings
  read the same either way.
- **Two spaces after a sentence** arm the service's `Cut` with no first scrap, so the next word is offered to
  `rejoin`. A test holds that fifteen words that stand (ice, all, and, one, ever, ...) are left under each of c v b
  n m from a third of the way down the bar, and that on its top edge only two rare ones give way (ow under the n is
  now, ore under the m more).
- **The work cap** bounds the whole search but for one word's letters against the taps: a last word's search walks
  no more nodes than the work left, and the walk for first words has half of it at most (`JOIN_START_SHARE`).
- **Retimed**, versus and this tree one after the other, twice, load 23 to 35, by probe.py's time mode over the
  seen set's 5,172 typed words: mean 186 and 94 microseconds, then 106 and 72; the slowest in a thousand 2,910 and
  2,337, then 1,694 and 1,610; the slowest single word 2,566 and 2,903, then 1,904 and 2,031. The slowest single
  word is a little slower than on versus.
- **`REJOIN_APART` is a guess**: no typing set has a full stop typed with a double tap. `JOIN_FALL` and `JOIN_LONG`
  were set on the 40,000-word list and are to be set again on a longer one.
- **Still to do on the emulator** (`device-check.sh`), none of which any test covers: "It did. ot" with the n on the
  bar; "tragi ." with the c on it; two spaces after a sentence, then a real word; backspace straight after each
  rejoin; a password field; the cursor moved between the two scraps.

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
| after | 96.78 / 93.12 / 87.16% | 1.93 / 1.75 / 2.07 | 323 / 553 / 817 | 62.2 / 63.6 / 59.4% | 2.3 / 3.0 / 6.0% |

**What it costs.** A right word that is rare in its place, and a slip away from a word the place expects, is
changed more often: 42 right words spoiled where there were 31, in the bench's 36,000 typed (nest to best, vase to
base and gadded to added, each by all three thumbs; hunch to bunch; done to some). The bench's own measure, words
right less four for every right word spoiled, goes from 93.76 to 94.52 (94.60 before the code review's changes,
below). With the place counted in full
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
documents, 287 that the thumb can type (2,639 words), plain prose about a keyboard with its own words in it; the
code review added 459 typeable sentences (4,480 words) of another project's documents, typed the same way
(`whatif.py --file ... --first 0 --sentences 3000`). Each word is typed by the three thumbs:

| Not Tatoeba's | careful | leaning | sloppy | Mended | Broken | Right words spoiled |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| this repository's, the place not read (`--set Autocorrect.HERE=false`) | 3.1% | 7.3% | 14.1% | | | 9 |
| as the tree is | 2.8% | 6.1% | 13.6% | 79 | 29 | 9 |
| another project's, the place not read | 4.0% | 6.5% | 14.6% | | | 22 |
| as the tree is | 3.8% | 5.9% | 13.7% | 123 | 50 | 30 |

So away from home the place still mends between two and three times what it breaks, and is worth about two thirds
of what it is on Tatoeba's unseen sentences: 0.63 of a point of words wrong in the first (645 of 7,917 to 595) and
0.54 in the second (1,124 of 13,440 to 1,051), 0.89 on the unseen set. With neither of the code review's two rules for
clean taps and apostrophes (below), the second lost 42 right words where the place not read loses 22; now 30. These are documents, not messages: what they say is
that the gain is not all Tatoeba's, not how large it is in a person's own writing.

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

### The word before, read again (made 2026-11-13)

Gboard does not settle a word once and for all at the space. From its records (`tool/versus/behind.py gboard`,
which takes the field's text after every key press): on the seen set 289 of 5,205 words were wrong when each first
had a space after it (5.55%) and 199 at the end (3.82%), 90 put right behind the cursor as the next word ended and no
right word spoiled that way; on the unseen set 304 (4.74%) and 221 (3.45%), 88 put right, 5 spoiled. "Ut never"
stood until the space after never and became "It never"; "Sre you" became "Are you". Ghost Keyboard settled each
word at its space and never looked at it again.

`Autocorrect.again` reads the word before again once the next word has ended: every reading of its taps (what
stands, what was typed, the decoder's readings), each weighed by what its slips cost, by its place after the words
before it (`atPlace`) and by how likely the word that has now followed is after it (`Scorer.follows`, from the
starter tables' one-word and two-word rows; up to 3 for a reading, and no more than 1 against one: a pair the tables
never saw is often only a pair Tatoeba seldom has). Where another reading comes ahead of what stands by 0.5, and the
word after itself gives it 0.5 of that, it replaces what stands, behind the cursor: a slip left standing is mended
("we ate going" to "we are going", "I'd tell you of I knew" to "if I knew"), and a word the space changed is put
back to what was typed (only 0.5 further ahead still: a word seen changing twice), or to a third reading, where the
word after says so. A typed word no list has is never put back (the first try put back "Ery" for Very and "ftlmt"
for front). `DevKeyboardService`
keeps the word that ended last (`Behind`, beside the bar's `Cut`) and calls it as the next word ends; the replay's
`Typist` and the bench's `typeAll` do the same. Where there is no place (prediction off), where a table does not
count its words (every language's but English's today, and English loaded with another: `Scorer.counted` asks it
of every table) or with `Autocorrect.AGAIN` off, there is no second look, and the scoreboard is then the starting
tree's word for word on both sets with the bench's lines identical.

What there was to win, measured first (`tool/versus/behind.py probe`, the second look made to change nothing): of
the 267 words the starting tree left wrong on the seen set that were typed with a slip, 99 were never read again
(70 a sentence's last word, which has no word after it; 31 in joins), for 74 the word meant was not among the
readings held up to the word after, for 53 it was among them and not first, and for 41 it came first with the word
after weighed in; of its 11 right words spoiled, 5 would come first again, 4 were among the readings, 2 never read
again. On the unseen set: 364 slips left wrong (102 never read again, 105 not among, 80 among, 77 first) and 13
spoiled (6 first, 5 among, 2 never read again).

| | seen | unseen | bench careful | normal | sloppy |
|---|---|---|---|---|---|
| starting tree (9d36d4c) | 5.34% (278) | 5.88% (377) | 96.87%, 1.93 | 93.19%, 1.75 | 87.24%, 2.07 |
| the second look, first made | 4.90% (255) | 5.07% (325) | 97.16%, 1.62 | 93.85%, 1.61 | 88.24%, 1.86 |
| as reviewed (`AGAIN_LOW` 1, `AGAIN_BACK` 0.5) | 4.86% (253) | 5.09% (326) | 97.13%, 1.83 | 93.83%, 1.61 | 88.24%, 1.86 |

(The bench's words right and right words spoiled per 1,000; unknown words spoiled 2.3%, 3.0% and 6.0% all three
ways. Against the starting tree: seen 25 mended and none broken, unseen 54 mended and 3 broken.)

Away from Tatoeba (`tool/versus/elsewhere.py --at 9d36d4c`, 288 sentences of this repository's documents as they
stood at 9d36d4c, typed by `whatif.py --file`; the documents' sentences change with the documents, this section
added two, so the file is named by its commit). As first made the second look mended 36 words there and broke 19
(careful 4 and 1, leaning 13 and 8, sloppy 19 and 10): "A fix is worn" became "A fox is worn", "install id" became
"install is", and "keys", "says" and "launches", put right at the space, became "lets", "days" and "launched". Most
of the 19 were pairs the tables give less room than they deserve ("says only", "keys a", "fix is": a negative
`follows` of 1.3 to 1.6) and rare typed words put back (os, hoe, ein). With a pair the tables lack counting no more
than 1 against a reading, and putting back what was typed 0.5 further ahead: 26 mended and 8 broken (careful 2 and
1, leaning 11 and 4, sloppy 13 and 3); careful 3.55% to 3.51%, leaning 5.89% to 5.63%, sloppy 13.15% to 12.77%.
That is 18 words of 7,941 (0.23%), against 25 of 5,205 seen (0.48%) and 51 of 6,408 unseen (0.80%): measured against
unseen, about seven tenths of the gain is Tatoeba's own, since the tables it reads were counted from Tatoeba. The 8
it still breaks there: launches to launched (twice, before "into"), says to days and to say, coc read as fox not fix,
keys to let's, lasts to pasta, as to a.

What a person sees (`tool/versus/behind.py seen --elsewhere`, and the bench's "changed behind the cursor"): on the
seen set 29 words change behind the cursor (5.6 per 1,000 typed): 25 put a wrong word right, none spoils a right
one, 4 swap one wrong word for another; 1 of the 29 puts back what was typed after the space had changed it.
Gboard on the same taps: 82 changes, 58 put right, 1 spoiled. On the unseen set 64 (10.0 per 1,000): 54, 3, 7, with
4 put back. On the documents' sentences 39 (4.9 per 1,000): 26, 8, 5, none put back. On the bench 3.3, 7.6 and 12.3
per 1,000 (careful 34 right, 3 spoiled, 3 other; normal 80, 4, 7; sloppy 127, 8, 13). Of the 90 words Gboard put
right behind the cursor on the seen set the tree has 79 right (the starting tree 74), of the 88 on unseen 73 (64).
It costs processor time, on the main thread at the space: on the bench, on an idle Mac, 114 to 120 µs a word
careful against 67 to 73 off, 142 to 143 normal against 87 to 90, 185 to 186 sloppy against 121 to 123 (the tap
search is run once more for the word before). Skipping it where the word after already bears out what stands
(`follows` 1 or more) saved 7 µs a word and lost 2 words on the bench: not kept.

On the phone (`DevKeyboardService`): the change and the word after go in as one edit, and backspace straight after
puts back both. The change is written into the words that ended a moment ago (`ends`), so the word before, typed
again as it was over the change, is kept and learned (as a fix taken out is) and the next word does not change it
again. It is read again only in the same field, with nothing selected, the text before the cursor ending in the
two words exactly and the 32 characters before them those that stood there as it ended (a cursor tapped to other
words that end the same way), and is let go where the app reports a cursor the keyboard did not move. Never at
enter: the message goes with it, and nothing could take the change back. Reports count `fix.behind` and
`fix.behindUndone`.

**Tried and not kept**: the first look made more cautious where a second look follows (a bar 0.25, 0.5 or 1.0 higher
at the space for a listed word: seen 257, 264, 278; unseen 327, 327, 338; with the second look's own margins at 0,
258 and 319 against 258 and 316 without it); the word just ended read once more after the word before it changed
(seen 255, unseen 324 against 325); the word after counting less for rare words (in full from a Zipf of 5, and
4.5 and 5.5: seen 261, 259, 264, unseen 336, 328, 339, with 18, 19 and 14 broken on the documents' sentences); a pair the
tables lack counting no more than 0.5 against a reading (documents 21 mended and 6 broken, but unseen 333) or 0
(unseen 342); the word after weighed 1.3 (seen 255, unseen 321, but the bench spoiled 1.83 and
2.07 per 1,000 careful and sloppy) or 0.7 (262, 342); no margins (258, 316, careful spoiled 2.03). The sentence's
last word has no word after it, and the tables have no rows that say how likely a word is to end a sentence (the
rows after "^" are starts): 70 of the seen set's wrong words are last words, and reading them by the mark that ends
them would need such rows counted into the tables first.

### After the code review (2026-11-13)

A code review of the change above (it reproduced the gain with a thumb of its own and found the place off to be the
base word for word) found what the track's own measures cannot see, and this is what was done about each.

- **A right word with every tap in the middle of its key was rewritten where the tables expect a neighbour**: "Its"
  opening a sentence was always "It's", and on prose that is not Tatoeba's the place changed clean words and
  mended none. Now a listed word whose every tap is well inside its own key (`Autocorrect.CLEAN_TAP`: nearer its
  own key's middle than any other's by 0.7 of a key width squared, within about 0.15 of a key of the middle) is
  weighed as though there were no place, and at a place no listed word is read as a word it differs from by
  apostrophes alone (`PLACE_APOSTROPHE` off: an apostrophe has no tap, so only the place would say so). Typed
  with every tap in the middle of its key, held-out lines 30,001 to 34,000 (28,021 words) now have no word the
  place alone changes (11 before), and five fewer than with the place not read. The cost is on Tatoeba's
  sentences: seen 5.38% for 5.34%, unseen 5.93% for 5.88% (ff for off, kn for in, yu for you, thy for they, t for
  to: a letter left out or a wrong key, every tap clean, which the place mended). On the bench, with the join rule
  below, words right are 96.78 / 93.12 / 87.16% for 96.87 / 93.19 / 87.24% and its measure 94.52 for 94.60; right
  words spoiled are 1.93 / 1.75 / 2.07 in 1000 either way. On the prose that is not Tatoeba's (above) the apostrophe
  rule keeps 19 words that allowing those readings breaks, and mends none fewer; the clean-tap rule is near
  nothing either way there with the thumbs' scattered taps (`CLEAN_TAP` has the table).
- **With English and another language on, two words run together were parted less often than before**: where the
  other language's table (which carries no counts) had a row for the first word, the second was read as nothing.
  It is now read by the old row, as before there was a place, and the place is read only where the language being
  written (the table `Scorer.weights` weighs most) has a row: run together and typed on their keys, 6,362 pairs
  of held-out lines 64,001 to 70,000 are parted 4,099 times with English and German on, 4,043 with the place not
  read (English alone: 4,134 and 4,059).
- **The person's own counts could lift a word without a cap**: "Ken" typed cleanly after "hi" became "Jen" once
  "Jen" had been typed there three times. They now lift a word at most 1.0 over what the tables make of it
  (`Scorer.MINE_LIFT`, 0.7 after the weight, under the 1.0 a common word's bar asks): the counts alone never take
  a common word's place, and the review's fifteen cases all stand. Nothing types with a person's counts, so this
  was set by hand, not measured.
- **A table with no row at a place had no say there**, so English's row counted in full in a German sentence. Now
  there is no place where the language being written has no row (German leading, English's row is not gone by).
  Counting such a table in as its words' own frequencies, by its weight, was tried first: it took English's say
  down while English was being written and parted 3,954 of those 6,362 pairs, fewer than the place not read.
  With English and another language on, the place is still read only where the other's table (which has no
  counts) has no row for the words before: 64% of held-out places with German, 94% with English alone.
- **After a possessive the place was the row of the letter s** (the table's maker looks a word up by what follows
  its apostrophe, for French's j'ai): the place now asks for the word whole, and "mary's" has no place.
- **A held-down second word of a join was worth more than a plain one.** It now pays `JOIN_UNSEEN` or what the
  place holds it down by, whichever is more, so it is never worth more than a plain one. Paying both, as the review
  had it, parted fewer of the bench's sloppy joins (58.6% for 59.4%); this way the normal thumb's are 63.6% for
  64.1%, and the seen and unseen sets are the same all three ways.
- Comments and documents that no longer said what the code does are put right; `REST_LEAST` is a dial with its
  comment; a weight of 0 gives no place rather than every worth NaN.

### Where this thumb lands (made 2026-11-12)

The leaning thumb of the run is the careful one with a lean: every tap lands 0.24 of a key to the right and 0.18
of a key's height below where it aims. Ghost Keyboard left 2.5% of the careful thumb's words wrong and 5.4% of the
leaning one's, so half of what the leaning thumb loses is the lean and nothing else. A person's thumb leans too,
by hand, grip and phone, and not the same way from one sitting to the next
(players of a typing game hit below the keys' middles, by 2.24 mm on one phone, and differently on each key: Henze,
Rukzio and Boll, MobileHCI 2012; each of Gboard's study users has a pattern of their own: Sivek and Riley, PACM HCI
2022. The research track's docs/versus-research.md, section 4, has more). The keyboard had two things for that: offsets measured from
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
difference a user could see: Sivek and Riley, "Spatial model personalization in Gboard", PACM HCI 2022). What that leaves out shows on a thumb that aims at the keys as
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

**After the review (2026-11-13).** What the review of this work asked, and what came of it:

- *Every language, and prediction off.* The lean was read on every board and with no scorer, and measured only in
  English. It is now read only on an English board with prediction on (`Autocorrect.aimed` takes it off the taps
  again anywhere else, at all three readings), and `Autocorrect.settled` learns nothing on another board and lets
  go of what it held, so the globe key in mid-field brings nothing over. Every other language, and English with
  prediction off, read each word exactly as before there was a lean (`ThumbLeanTest`). The scoreboard did not move
  (0 mended, 0 broken on both sets against the tree before).
- *When it begins again.* Also when the keys change width under it (the Fold opened or shut, one-handed or split
  mode in the field). The service teaches a fix only once it is in the field (not where `replaceBefore` failed),
  and two scraps rejoined take back the first scrap's lesson; the Typist does the same. No word moved.
- *A rare word typed right and spoiled* ("doodled" read as "doodles" on the seen set, leaning thumb). That is the
  lean itself, not the typed word's cost coming off the rare word's gap: with the gap left as the base has it
  (the typed cost never taken off) "doodled" is still read as "doodles", and the seen set loses two "Tom"s (typed
  "yom") and a "cases" (typed "vases"); capped at half a point or a point it is the same as uncapped. The trade is
  that a letter landed on the side of its key away from the lean is read as aimed at the neighbour there; on the
  bench, with leaning thumbs, right words spoiled still fall with the lean (2.07, 1.59, 2.05 in a thousand to 1.78,
  0.91, 1.71).
- *The clamp, and the bar as the aimed key.* Costs counted from the unclamped distances: for the thumb aiming at
  the keys as drawn 0 mended and 0 broken (careful, leaning), 1 broken (sloppy); on the seen set 1 broken ("tbale"
  left as typed); on the bench with leaning thumbs 93.50%, 88.52%, 80.59% words right where 93.47%, 88.49%,
  80.51%, spoiled the same. The bar allowed to be the aimed key in a letter's tap (the typed word paying what the
  bar is nearer): nothing moved anywhere. "Amenas" is split by neither: it is the one lean for the board. Both
  were measured on the seen set, the drawn aim and the bench, and neither is kept; the clamp is what makes the
  reading move smoothly from no lean, where a key the tap is nearer than the typed one by the keys' own
  geometry (they listen where one thumb was measured) would otherwise become the aimed key at the least lean.
- *Speed.* `tool/typing_bench.sh leantime` calls correct twice for each of the bench's 12,000 words, with and
  without the lean it was read by, timed by processor time around correct alone, on a Mac with a load average of
  4 to 7: fastest of six rounds 122.5 µs a word with the lean and 124.8 without, for a normal thumb leaning 0.24
  and 0.18 (10,566 of 12,187 words read by a lean); 104.8 and 105.6 for one that does not lean (10,248 of 11,864
  read by some lean, mostly a few hundredths). No cost that can be seen.
- *The service never compiled.* Still no Gradle, but kotlinc has now compiled every source of the app's main set
  but the few that import Flutter, against Android 36's android.jar with androidx's annotation and autofill
  classes from the Gradle cache and stand-ins for BuildConfig and MainActivity.EXTRA_OPEN: no errors, and
  DevKeyboardService with its new lines among them. It has still not been built by Gradle or typed on.
- *Unseen as the check only.* The dials' comments give unseen figures from when they were set; nothing chosen
  since has been chosen on them (the experiments above were judged on seen, the drawn aim and the bench).

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

### Names get their capital (made 2026-11-12)

Not in the score, which leaves capitals aside. The thumb types everything in lower case. On 2026-11-11, of the
names the field ended up with the right letters of (Tom, Mary, Vladimir, Algeria, Monday), Gboard gave 134 of 191
their capital (Mary 26 of 26, Tom 34 of 48), and Ghost Keyboard 0 of 188. Both capitalise I and the first word of
a sentence every time.

Now a name typed in lower case comes out with its capital, in the middle of a sentence too: left as typed ("tom"
is Tom), mended ("mondya" is Monday), parted from the word it ran into ("withmary" is "with Mary") or put together
again after a tap on the bar ("lo doj" is London). `Autocorrect.correct` and `suggest` take a `names` argument,
`Autocorrect.named` does the same for a word `rejoin` made, and `Autocorrect.proper` is the rule: a word of
`assets/dict/en_capitals.txt` is written as that file has it, its possessive with it (tom's is Tom's), unless the
person keeps it in lower case. A word that is no name comes out, asked for names, exactly as it does unasked:
`Autocorrect.name` says whether it is one before anything of it is written again (as first made, a word left as
typed was put in lower case and back into its capitals, and İrem, typed from i's long-press beside Turkish, came
back as I and a dot above it, in French alone as much as in English).

**The list** is 1,884 words, 15 KB, made by `tool/capitals.py` from the same Tatoeba export the starter tables
are counted from, the held-out twentieth never counted. A word is counted where its capital can only be its own
(straight after another word of its sentence), whole and in any alphabet (until 2026-11-13 a word was a to z
alone, and Bah, Jos, Roc and Fran were on the list as the first letters of Bahá'í, José, Rocío and François, and
Wal, Indo and Sino as the first halves of hyphenated names), and kept where it is a word of English's list, of three letters or
more, counted five times or more and written with a capital 19 times in 20 or more. There is no list of names by
hand. Words that are a name only some of the time fall to the same bar, each by how it was written: will 0.0%,
rose 3.5%, may 4.0%, bill 14.9%, mark 32.2%, turkey 65.4%, jack 74.8%, march 86.8%, god 89.4%, joe 94.5% and bob
94.6% are left as typed; china 95.9%, august 99.0% and tom 100.0% get their capital. The bar of 95% is a
judgment about Tatoeba and not a measured best. `capitals.py --bars` tries others on sentences they were not set
from (the list made from one half of the counted sentences, held against the other): from 95% down to 90% gives
3,271 more capitals rightly for 272 wrongly, twelve to one, and from 90% to 80% six to one, so by the project's
four-to-one measure alone the bar would be at 80% or under. It is left at 95% because Tatoeba reads like a
phrasebook, fuller of names than what people type, so a word's share there flatters the name by an amount nothing
here can measure; what it costs is in plain sight (under twenty counts it means never once in lower case, so Scott,
18 of 19, is out, and Bob and Joe are near it). A real answer needs sentences that are not Tatoeba's. `--held`
holds the list to the first 100,000 held-out sentences, as the decoder gives capitals (a listed name's possessive
is the name's; I'm, I'll, I'd and I've are left out, having a rule of their own): 25,655 of the 32,662 words
written there with a capital in the middle of a sentence get it (78.5%), and 11 of the 491,103 written in lower
case get one (0.02 in a thousand: french, london, china and eight more, once each, where the sentence's writer left
a capital out). Tom, Mary and their possessives are 14,804 of those 32,662; the other names get theirs 10,851
times of 17,858, which is 60.8%, and that is the figure to carry to other text (the bench's sentences, where Tom
and Mary are few, give 61%). Until 2026-11-13 this paragraph said 72.2%, counting I'm and possessives wrongly.
That the sentences it holds out are the ones the bench and `tool/versus` type was checked line by line: the
export's every 20th sentence, as `capitals.py` numbers them, is the line of `build/typing-bench/en-heldout.txt`
in 100,000 of the first 100,000.

A lower bar was tried on both sets of typings: at 90% the run's typings gain United three times and the unseen
ones nothing, and Federation is given a capital three times where its sentence has none; at 80% nine and none, and
Mandarin with it. That is one sentence each way, typed by three thumbs, and says nothing for or against the bar.

The vocabulary is a list of files (`--vocab`; unsaid, English's lists as the tree has them: `en.tsv`, and
`en_wide.tsv` where there is one), so the list can be made again when English's word list grows. Tried over
`en.tsv` and the wider list as another branch had it on 2026-11-12 (127,488 words between them) the same rule
keeps 2,293 words, 402 more and none fewer (Berber, Kabyle, Esperanto, Tuaregs, Polisario), and gives 25,264 of
the 33,935 held-out capitals, with the same 11 wrongly given. That list marks 15,433 of its own words as names
(`n`: a dictionary writes them with a capital and never without). 631 of them pass the rule; 14,691 were counted
fewer than five times in Tatoeba's sentences; and among the rest are words people write in lower case whatever
the dictionary says (tiktok with a capital 2 times of 82, parmesan 5 of 31, youtuber 0 of 20), so the mark is not
the rule by itself.

**Where.** `DevKeyboardService.namesWanted`: the field asks for its sentences to be capitalised and Settings ›
Typing › Capitals at sentence starts is on, the two things that make the keyboard give a capital of its own at
all; never in a terminal. The row shows what the space will leave: "Tom" as its pick for "tom", then "tom"
itself. Backspace straight after puts the lower case back and keeps it as a learned word, as undoing any fix does,
and a kept word is never given a capital again, nor its plural or possessive. Where nothing may be learned
(incognito, a field that asks keyboards not to) the word typed again in the same place is still left as it was
typed, as a fix of letters taken back always was. One backspace to swap the space for a question mark ("are you
coming tom", backspace, "?") also keeps tom in lower case for good: as the track asked, and far more often than it
happened for I; whether a capital taken back should be learned only from the row's quoted chip is a product
question left open. With no English, asking for names changes nothing (`TypingAccuracyTest` tries every other
language the keyboard has a list for, alone on its own board), and German's nouns are German's own affair. On the
Turkish board, where I is the capital of ı, a name that begins with i is left as typed, as the lone i is there.

**Beside another language.** The list is English's and read only with English loaded, and with it every other
enabled language of the same script (`LanguageState.dictionaries`). A spelling that is a name in English may be
an ordinary word in the other: French "roman", a novel; German "mir"; Spanish "los", and "catalán" typed without
its accent. Until 2026-11-13 every spelling the other list had was taken out, and wordfreq's other lists hold Tom,
Mary, London and Boston, since their writers tell of them as English's do: beside German 26 of the 146 seen names
and 32 of the 98 unseen were left. Now `dict/en_capitals_beside.tsv` says, a line a language, which names are no
names beside it, counted by `capitals.py` from that language's own Tatoeba sentences (every 20th held out, a word
under its plain spelling): a name is out where, of all the times the two languages together write the spelling
(weighted by the two lists' frequencies), it has its capital less than 95% of the time; where the other language
writes it fewer than five times it counts as written with a capital nine times in ten if never seen in lower case
(UNSEEN, set on ten languages cut down to a two-hundredth, a fiftieth and a tenth of their sentences and held to the
rest: 96 to 97% of English's capitals kept, and 0.05 to 0.08 capitals wrongly given in a thousand of the other
language's words, where the old rule gave 0.07). A language with no line keeps the old rule, now with the plain
spelling of each accented word too. Out beside Spanish: catalan, israeli, hindu, danes, los, las; French: roman,
hai, crete, les; Czech: tom (Czech writes "o tom"), mir, mira; Catalan: hague, plato, arabs; Hungarian: indian;
Finnish: jai. Still given, by the same rule, because English writes them far more than the other list does:
saudi beside Spanish, islamic and nordic beside Catalan, german beside Hungarian. Replayed with a second list
loaded on English's board (`scoreboard.py --beside`, the JVM alone, no emulator run was typed that way):

| beside | seen names | Tom and Mary aside | unseen names | Tom and Mary aside |
| --- | ---: | ---: | ---: | ---: |
| English alone | 146 of 191 | 73 of 118 | 98 of 122 | 69 of 93 |
| German | 145 of 188 | 73 of 116 | 98 of 122 | 70 of 94 |
| Spanish | 143 of 190 | 70 of 117 | 89 of 117 | 60 of 88 |
| French | 146 of 189 | 73 of 116 | 98 of 122 | 69 of 93 |
| Italian | 146 of 190 | 73 of 117 | 95 of 122 | 67 of 94 |
| Portuguese | 149 of 193 | 72 of 116 | 100 of 124 | 69 of 93 |
| Dutch | 118 of 159 | 50 of 91 | 77 of 100 | 51 of 74 |
| Polish | 115 of 158 | 48 of 91 | 76 of 101 | 50 of 75 |
| Turkish | 116 of 155 | 48 of 87 | 76 of 99 | 50 of 73 |

Counted among the names whose letters came out right, as everywhere here. Beside Dutch, Polish and Turkish fewer
names have the right letters at all: the words wrong on those replays are 23% (1,196 of 5,205 seen with Dutch),
against 6.6 to 6.8% beside German, Spanish, French, Italian and Portuguese, with names and without alike. That is
the decoder reading English's taps against two lists, not the names, and it is unexplained here (it may be the
replay's: the starter table is English's alone); it wants a look from whoever owns two-language typing.

What a capital counts as. To the Stats page it is a fix like any other ("Fixed by autocorrect", and a slip
against the accuracy dial), as i made I has always been: of the 1,906 fixes made on the run's typings 101 are a
name's capital and nothing else, which is 0.4 of a point of that dial (93.0% for 93.4%), and 73 of 2,361 on the
unseen ones (0.2). To the mistake memory the word stood as typed: a fix that only gave a capital teaches nothing
(until 2026-11-13 a possessive the lists lack, linda's for 1,634 of the names, was learned as a mistake for
Linda's and shown among the words put right most often), and taking the capital back forgets nothing.
To an anonymous report it is no fix made (a report goes by the letters) and, taken back, no fix undone. The
setting that turns it off is still called "Capitals at sentence starts".

| | Names given their capital | A capital given to a word the sentence writes without |
| --- | ---: | ---: |
| the run's 900 typings, Gboard | 134 of 191 (70.2%) | 1 of 3,925 words (the west Coast) |
| the run's 900 typings, before | 0 of 191 | 0 of 3,787 |
| after | 146 of 191 (76.4%) | 0 of 3,787 |
| the 900 unseen typings, Gboard | 103 of 135 (76.3%) | 1 of 5,144 |
| the 900 unseen typings, before | 0 of 122 | 1 of 4,934 |
| after | 98 of 122 (80.3%) | 1 of 4,934 |
| the typing bench's 12,000 words, careful thumb | 115 of 189 (60.8%) | 0 of 9,670 |
| normal | 107 of 177 (60.5%) | 0 of 9,128 |
| sloppy | 93 of 151 (61.6%) | 0 of 8,251 |

Counted among the words that came out with the right letters (`scoreboard.py` prints the line under each set's,
and `tool/typing_bench.sh taps` under each thumb's; `-Dcapitals=true` lists them). **The lead over Gboard is one
name, Tom**: Tom is 57 of the 219 names in the run's sentences and 33 of the 159 in the unseen ones, and Ghost
Keyboard gives him his capital every time where Gboard leaves "tom" in a few sentences for every thumb. With Tom
and Mary aside (the line under the capitals in `scoreboard.py`), seen: 73 of 118 against Gboard's 74 of 117;
unseen: 69 of 93 against 85 of 108; and Gboard gives a capital to more different names on both (23 of 38 against
19 of 37, 31 of 38 against 25 of 34). So: ahead on Tom, level or behind on every other name, on both sets. What
Gboard has and this lacks is names of several words (of 14 two-word names on the unseen typings Gboard gives both
words their capital 14 times, Ghost Keyboard 6, and one word alone 5: "western Sahara", "new York") and names the
list does not hold (Esperanto, Interlingua, Incas are not in en.tsv; Colgate); the wider word list and a list of
pairs are the next gain, not the letters. Not one word of either set is mended or broken by the capitals, and the
bench's own lines are to the digit what they were.

A capital also lands on words that came out wrong, which the two counts above pass over since each goes by right
letters: a slip that spells a listed name, or is mended into one. They were wrong words before names too, but
"It had Ben a long meeting" is what is seen. On the run's typings 4 (Tim for Tom twice, Ana for Anna, Sam for
ask), on the unseen 6 (Tim for Tom twice, Nile for mile, Ben for been, Luke for like, Tim for time), on the
bench 5, 8 and 14 by thumb; Gboard 3 and 1. `--show` lists them; for the ranking track, a slip that spells a
listed name (ben, tim, luke, nile) is being kept as a word. The one capital "amiss" on the unseen
set was there before, and is Gboard's one too: the sentence itself writes "i" for I. `named` takes 77 ns a word,
and `correct` timed with and without names in turn, in one process, shows no difference (22.6 and 20.9 µs a word
at the middle of seven turns that ran from 17 to 30, on a Mac at a load average of 240); the list is 15 KB as a
file (7 KB deflated), is read in 0.7 ms and takes 258 KB on the JVM.

Still without, on the run's typings (45): names of several words whose words are ordinary alone, 12 (United
States, the Polisario Front, Western Sahara: Gboard has United States every time); ordinary words that are a name
where they stand, 17 (the Earth and the Moon, Grasshopper Green, Kiss the band, a D in a test: Gboard has none of
them either); and names no list has, or that the counted sentences never wrote five times, 16 (At Frah, In
Amenas, Adrar, Tuaregs, Polisario; Colgate, which Gboard has). On the bench's sentences the names its careful
thumb left without are led by New (6 of 75), Bill (5), Year (4), Mr and Brown (3 each), then Union, Day, War,
World and Series: words of names of several words again, and words that are a name only some of the time. What
would reach the first kind is a second list, of pairs, made the same way, and a service that may give the word
before its capital when the second one ends; neither is here. Nor is a swiped name given one unless a typed space
or mark ends it. And a name that begins something written with dots (john.smith@..., amazon.com), typed in a
field that capitalises sentences, gets its capital too: the full stop ends the word before anything says what it
was the start of.

On a phone none of this has been typed yet: `NamesSourceTest` holds the service's lines and the replay's to each
other, and the replay is what the figures above are from. The two device checks kept so far were typed on builds
that gave names no capital, and their `meta.json` says so (`--no-names`): typed that way on this tree the JVM's
text is the emulator's in 144 of 144 typings and in 10 of 10, and with names asked for it is the emulator's but
for a capital in all of them (24 and 4 differ: "Did tim survive?" is "Did Tim survive?"). So what is left for a
phone to say is whether the service asks where its lines say it does: `device-check.sh` against a commit from
before names types exactly the typings a name's capital alters.

### Squeezing 0.17.0 (2026-11-13)

Matt, 2026-11-13: "see if there is anywhere we can squeeze out just a few more points". 0.17.0 was ten tracks merged
one at a time and never tuned together; the scatter track's spread had raised the normal and sloppy thumbs' right
words spoiled on the bench from 1.48 and 1.65 a thousand to 2.01 and 2.43 (build/merge-notes.md, m7).

**The joint tuning.** `sweep.py search` over nineteen dials (the spread's `SPATIAL`, `SPREAD_LOW`, `SPREAD`,
`SPREAD_POWER`, `SPREAD_TAPS`; `HERE_WEIGHT`, `HERE_COMMON`, `LETTERS_SHIFT`, `MARGIN_COMMON`, `MARGIN_RARE`,
`UNKNOWN`, `UNKNOWN_ODD`, `UNKNOWN_LONG`, `WHOLE_CHANGE`; the second look's `AGAIN_MARGIN`, `AGAIN_LEAST`, `AGAIN_LOW`,
`AGAIN_BACK`, `AGAIN_UNKNOWN`), each on a fine step of its own, fitted on one half of the sentences and then on the
other (`--holdout 1`, `--other-half`). The objective would not have asked for fewer spoils (it charges only spoils
over the tree's), so `sweep.py` has a `--guard SETTING`: the bench's penalty is counted against that setting's bench.
The guard was `ThumbLean.SPREAD_LOW=0.85`, which alone takes the normal and sloppy thumbs back to 1.47 and 1.62 (seen
+6 words wrong, unseen +1), and each search started there. Half A moved ten dials, half B seven; five moves were made
by both, and only those are kept: `SPREAD_LOW` 0.6 to 0.85, `SPREAD` 0.065 to 0.07, `AGAIN_MARGIN` and `AGAIN_LEAST`
0.5 to 0.4, `UNKNOWN` 3.2 to 3.1, `UNKNOWN_LONG` 1.6 to 1.5 (b937695). B's `LETTERS_SHIFT` 0.8 (four more words) was
left out: on a fresh draw of the bench it spoiled more (below).

| | seen careful | leaning | sloppy | all | unseen careful | leaning | sloppy | all |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0.17.0 | 1.15% | 1.79% | 3.92% | 2.29% (119) | 1.69% | 1.73% | 6.13% | 3.18% (204) |
| tuned | 1.15% | 1.61% | 4.21% | 2.32% (121) | 1.54% | 1.64% | 5.90% | 3.03% (194) |
| Gboard | 1.84% | 3.23% | 6.40% | 3.82% | 1.64% | 2.39% | 6.32% | 3.45% |

Seen 8 mended, 10 broken; unseen 18 mended, 8 broken. The careful thumb on unseen, the one place Gboard was ahead
(1.69% to its 1.64%), is now ahead of it (1.54%). On the bench (words right, right words spoiled a thousand,
unknown words spoiled):

| | careful | normal | sloppy | misspellings |
| --- | --- | --- | --- | ---: |
| 0.16.5 | 96.41%, 1.22, 1.5% | 92.34%, 1.48, 4.3% | 85.65%, 1.65, 7.1% | 232 |
| 0.17.0 | 97.62%, 1.32, 1.6% | 95.14%, 2.01, 4.3% | 90.83%, 2.43, 7.1% | 235 |
| tuned | 97.63%, 1.32, 1.6% | 95.13%, 1.47, 2.5% | 90.43%, 1.62, 3.5% | 235 |

Per thumb, plainly: the careful thumb gains a little in words right and spoils exactly what it did; the normal
thumb spoils 4 fewer right words of some 7,460 (3 fewer of its 162 unknown ones) for 1 fewer word right in all; the sloppy thumb
spoils 4 fewer of some 4,930 (3 fewer of its 85 unknown ones) and has 48 fewer words right of 12,002 (90.83% to 90.43%): that is
the part of the spread's gain the floor gives back. Held out: the setting has fewer words wrong than 0.17.0 on the
half not fitted in two of three random draws of halves (-3, +1, -6 words; every interval holds 0), so the sets'
gain is within noise; what is certain is the spoils.

**A fresh draw of the bench.** The guard was the bench's own 12,000 words with seed 7, and so the search could fit
its few spoils. Typed again with other taps and other unknown words (`-Dwords=24000 -Dseed=11`), right words spoiled
a thousand and unknown words spoiled, careful / normal / sloppy: 0.17.0 0.86, 2.43, 2.81 and 1.1%, 5.9%, 8.4%;
tuned 0.91, 1.89, 2.39 and 1.5%, 4.5%, 6.6%; with `LETTERS_SHIFT` 0.8 as well 0.96, 2.29, 2.39 and 1.7%, 6.2%, 6.6%.
The fall is smaller than on the guard's own draw (normal 2.43 to 1.89 where 2.01 to 1.47), and the careful thumb
spoils one word more of 543 unknown ones and one more right word.

**Tried and left out** (each measured, none in the tree):

- *Sparing a listed word the loosening* (`SPREAD_LISTED`, `SPREAD_COMMON`): the spoils the spread added are not
  listed words read loosely but mostly unknown ones ("zsh" as "as", "devkbd" as "second", "jovana" as "jobs a");
  sparing listed words cost words right on the bench and gained nothing. *Sparing a word typed with every tap well
  inside its key* (`SPREAD_CLEAN`): a loose thumb's right words seldom are, and nothing changed.
- *Holding letters no list has more when the thumb is loose* (`UNKNOWN_LOOSE`): per spoil saved it cost more words
  wrong than the floor does.
- *A lower bar where letters no list has all but spell a word* (`UNKNOWN_NEAR` 0.9 for readings whose slips cost
  0.5 or less: "uury" with its u on the line is "yurt", "redi" is "redo"): seen 121 to 118, unseen 194 to 191, 7
  mended and 1 broken, the bench's lines the same; but on the fresh draw the normal thumb spoiled 3 more unknown
  words of 357 and the careful one 1 more. At a cost of 0.25 it is clean on both draws and gains 2 words. Left out.
- *Less of a common word's guard at the second look* (`AGAIN_GUARD`): 0.6 gives seen 118 and unseen 192, but the
  careful thumb's right words spoiled rise to 1.42 and the normal one's to 1.88.
- *The tap costs* (a second pair of half searches over `INSERT_NEAR`, `NO_SPACE`, `INSERT`, `OMIT`, `INSERT_DOUBLED`,
  `OMIT_DOUBLED`, `FAR_SHORT`, `SWAP`, `LISTED_EDIT`, `LONE_OMIT`, `RARE_GAP`, `RARE_BUDGET`, `REJOIN_MARGIN`,
  `JOIN_UNSEEN`, `SURE_MARGIN`, `LISTED_BUDGET`, `HERE_WEIGHT`, `MARGIN_RARE`, `CLEAN_TAP`): the only move both made
  is `NO_SPACE` 2.8, 3 mended and none broken, the bench's lines the same or better; on the fresh draw the normal
  thumb spoils 3 more unknown words (two words run together read where a name was). Left out.

**What is still lost to Gboard.** On seen, 55 words Gboard got and this does not (`why.py --set seen --lost`), by
what the decoder weighed: the space run together or split, so no word was read (11); letters no list has whose
meant word is near but worth less than the bar for letters no list has (10: yurt, redo, crab, woken);
a common word typed for another, the meant word under the common word's margin (12: "is" for us twice, "top" for
too, "nor" for not, "lime" for line, "be" for he); letters two keys or more of slips from the meant word (7:
"beeaofaz" for breakfast); words never read by the taps because a list keeps them as typed (7: "ur", "eta", "sm");
the meant word read but another or none chosen (4); a rare listed word kept (3: "truk", "dour", "hymie"); one name
no list has. Counted the same way over every word left wrong, the unseen set's 194 (counts only): a common word
typed for another 52, letters two keys or more off 46, near but under the bar 24, the space 22, another reading
chosen 15, kept as typed by a list 14, a rare listed word 11, a name no list has 7, other 3. The careful thumb's 33
on unseen: 12 a common word typed for another, 7 two keys or more off, 6 near but under the bar, 3 a rare listed
word, 2 the space, 3 other. Every class that is left is one where the bench's unknown words pull the other way:
the bar for letters no list has, the common word's margin and the tap costs are each at the bottom of their valley
as the bench's spoils see them.

**Time** (`sweep.py time --threads 1`, the threads' own processor time, least of four): the tree 275 and 290 µs a
word on seen and unseen, 106, 145, 200 on the bench's three thumbs; 0.17.0's dials 280, 294, 106, 155, 218. The
higher floor reads a sloppy thumb less loosely, so the search does less: 7% and 9% faster for the normal and sloppy
thumbs. The second look and the spread together (`AGAIN=false ThumbLean.SPREAD_ON=false` against the tree) cost
about a fifth on the sets (225 and 235 without) and nearly half again on the bench (54, 81, 121 without).

Checks: unit.sh 184, lang_check 17,245, next_word_check 585, swipe-selfcheck 8, 587 Gradle unit tests, 74 Flutter
tests. ThumbLeanTest's wild tap in a careful field's second word now loosens 10 fields of 20 (15 or more at
`SPREAD` 0.065: the prior holds a little more). Not typed on the emulator or a phone.

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
does (`"replay": ["--marks-without-taps", "--no-names"]` for 2026-11-11: that build read a word ended from ?123
without its taps, and gave no name its capital). With both, the count on main at 0.16.2 is still 900 of
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
