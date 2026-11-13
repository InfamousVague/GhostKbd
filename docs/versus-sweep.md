# Many settings of the decoder, tried fast

Matt, 2026-11-12: "I want you to continue work until we get to the 3.8% of gboard or better". The decoder has
seventy numbers and switches that can be set without touching its code: every `@JvmField var` in the companion
objects of `Autocorrect`, `TapDecoder`, `Scorer` and `SwipeDecoder` (`UNKNOWN`, `MARGIN_COMMON`, `SPATIAL`, `OMIT`,
`WHOLE_CHANGE`, `REJOIN_MARGIN` and the rest). [versus.md](versus.md) tried a handful of them one at a time with
`whatif.py --set` and `scoreboard.py --set`, which start a JVM, read the word lists and type 1,800 sentences for
every setting asked about, and another JVM for the typing bench: 22 to 97 seconds a setting on this Mac as it is
this week. Setting several dials together takes thousands of tries. `tool/versus/sweep.py`
answers for a setting in five to ten seconds on the same Mac, and has a grid and a search built on that.

    tool/versus/sweep.py dials                                    # every dial, what the tree sets it to, which a search takes
    tool/versus/sweep.py line "Autocorrect.UNKNOWN=2.4 TapDecoder.OMIT=1.8"   # a setting as scoreboard.py --bench prints it
    tool/versus/sweep.py grid TapDecoder.SPATIAL=0.9:2.1:0.2      # one dial: a row a value
    tool/versus/sweep.py grid Autocorrect.UNKNOWN_ODD=1.0:1.8:0.2 TapDecoder.SPATIAL=1.3:1.7:0.1   # two: a table
    tool/versus/sweep.py scan                                     # every dial alone, a step down and a step up
    tool/versus/sweep.py search --holdout 1                       # many dials at once, fitted on half the sentences
    tool/versus/sweep.py search                                   # ... and on all of them
    tool/versus/sweep.py front                                    # of all that was tried, the settings no other beats
    tool/versus/sweep.py time --threads 1 "TapDecoder.BUDGET=7"   # what a setting costs a word, beside the tree
    tool/versus/sweep.py check                                    # is it still scoreboard.py's numbers, to the word?
    tool/versus/sweep.py again 40                                 # do 40 kept answers come out the same typed again?

It changes nothing in the app and no dial's value: it says what a value would do. The dials are the tree's
(9468bfd) in everything below, and setting them is left to whoever merges the other changes to the decoder, with
this to do it by.

## What it is

**One JVM that has read everything once** (`tool/versus/replay.sh sweep`, which is `replay/Sweep.kt` compiled
with the replay and the typing bench). It lays out the run's keys, reads the word lists and the starter table, and
then reads settings from standard input, a line each (`Autocorrect.UNKNOWN=2.4 TapDecoder.OMIT=1.8`; an empty line
is the tree as it is). For each it types three things and answers with one line: the seen set and the unseen set
as `scoreboard.py` has them (900 typings each, words wrong by thumb and in all), and the typing bench's own tap
test (12,000 words by its careful, normal and sloppy thumbs: words right, right words spoiled in a thousand, right
words no list has that were spoiled, and its misspellings). After a tab the same line carries every count behind
those figures and a verdict for every word, which is what `sweep.py` reads. It can be started by hand:

    echo "Autocorrect.UNKNOWN=2.4 TapDecoder.OMIT=1.8" | tool/versus/replay.sh sweep --sets "$(tool/versus/sweep.py sets)" | cut -f1

**A dial is found, not listed.** Any field of the keyboard's package that is public, static, not final and a
number or a switch is one, by reflection: a `@JvmField var` added to a companion object, or to a new class, is a
dial here without this tool being touched, and `sweep.py dials` lists it. What each holds as its class loads is
the tree's value, and every one is put back to that before each line, so a line never inherits from the line
before. This tree has 70: 19 of `Autocorrect`, 19 of `TapDecoder`, 6 of `Scorer` and 26 of `SwipeDecoder`. A
search takes 32 of them unless told otherwise, the numbers of `Autocorrect` and `TapDecoder` that a tapped word
can reach; not swipe typing's, not `Scorer`'s five (next-word prediction's: the row of next words feels them, and
nothing here measures that row), not the three that only the row above the keys or a person's remembered
mistakes read, and no switch. Those are named one by one in `sweep.py` (`NOT_SEARCHED`), so a number that a
change adds, to `Scorer` or to a class of its own, is taken without being listed anywhere; a switch or a whole
number is named (`--also TapDecoder.SOME_SWITCH`) and is then tried the other way, or by ones.

**One setting at a time, on several threads.** The dials are static, so two settings cannot be tried at once in
one JVM without a class loader each, and each class loader would hold its own copy of the word lists and warm up
its own compiled code. But one setting's work divides evenly: a typing does not depend on the typing before it (a
new `Typist` for each, and `Autocorrect.correct` and `rejoin` keep nothing between words: a `TapDecoder` and a
`Scorer` are made for each word, the lexicon, its trie and the starter table are never changed once read, and the
two things that are filled in as they are asked for, a row's followers and the runs of letters a word may have,
are a concurrent map and a value swapped whole). So the worker types one setting's 1,800 typings and the bench's
4,500 sentences on `--threads` threads (4 unless told otherwise) and then takes the next. That is the same use of
the cores as several settings at once would be, with one copy of everything (0.4 to 0.6 GB resident, under
`-Xmx2g`) and no order of finishing that could change a number. Class loaders and several JVMs were not built to
be measured against it: the rule on this Mac while eight tracks shared it was one JVM each.

**The bench's taps are drawn once.** `Bench.kt`'s `typeAll` draws a thumb's taps for all its sentences from one
stream of random numbers, and what the keyboard makes of a word changes none of them. The worker runs it once for
each thumb with a keyboard that changes nothing, keeps every token it typed and every stretch it tallied, and for
each setting reads the tokens again (`byTheTaps`, the bench's own reading of a word) and tallies them with the
bench's own `Tally`, in the bench's order. Three small changes to `Bench.kt` make that possible and leave what it
prints as it was (its whole output for 12,000 words before and after, the times apart: no line differs):
`byTheTaps` is a function of its own, `taps` takes its sentences from `sample` (which already drew the same ones
the same way for `tune`), and the class that was called `Unit` is `Stretch`, because a class of that name takes
`kotlin.Unit`'s place in every file compiled beside it.

**The sets first, the bench only where it can matter.** A search asks for a setting's sets alone (`only=sets` on
the line), which is about half the work. The bench only ever adds to the objective (below), so where the sets alone
leave it too high for a move, the bench is not typed.

**Every answer is kept** under `build/versus/sweep/`, by a digest of the classes the worker was compiled from (not
of its jar, whose bytes change each time it is made: the same sources compiled twice gave the same digest), the
typings, the bench's sentences, English's lists (every file of `assets/dict` and `assets/ngram` whose name begins
`en`) and the options the JVM was given. A grid or a search run again, or run on after being stopped, asks only
for what it has not seen, and a change to the decoder or a list starts a new file. `--no-cache` asks again
regardless; a change that reads a list from somewhere else needs it.

**A setting the decoder throws at** is answered as an error, and the worker goes on with every dial put back; a
search passes such a value over.

**It cannot read the sentences kept for the end.** `scoreboard.py --final` types a third set, from line 110,001 of
the held-out file, whose totals are to be looked at once when the work is done. A search that tries thousands of
settings would wear that out in an afternoon. `sweep.py` makes the unseen set from a copy of the file that stops
at line 110,000 (or at `scoreboard.py`'s `FINAL_FIRST`, should that come to be lower) and refuses a first line
past it; `Sweep.kt` is told the same bound and reads the bench's sentences no further (`Sentences(root, most)`),
and never past line 110,000 whatever it is told. One thing for whoever runs `--final`: the held-out file in this
worktree, and in the one it was copied from, has 102,028 lines, so there is no line 110,001 and that set would
have no sentence in it. It was not run here, by the rule; it follows from `thumb.sentences`, which passes over
every line up to the first it is given.

## It is the scoreboard's numbers, to the word

`sweep.py check` asks the worker for a setting, then runs `scoreboard.py --bench --save` with the same `--set`s
and holds one to the other: the six lines `scoreboard.py` prints (seen, with Gboard beside it; unseen; the bench's
misspellings and its three thumbs) must be this tool's character for character, and every word of the two sets must
have the same verdict in the file `scoreboard.py` saved. On 2026-11-12, the tree (9468bfd) and three other
settings, at a load average of 16 to 19:

| Setting | Lines the same | Words with another verdict | `scoreboard.py` | The sweep |
| --- | ---: | ---: | ---: | ---: |
| as the tree is | 6 of 6 | 0 of 11,613 | 24 s | 6.7 s |
| `Autocorrect.UNKNOWN_ODD=0` | 6 of 6 | 0 of 11,613 | 22 s | 4.9 s |
| `Autocorrect.MARGIN_COMMON=0.6 Autocorrect.UNKNOWN=1.6` | 6 of 6 | 0 of 11,613 | 33 s | 7.1 s |
| `TapDecoder.SPATIAL=1 TapDecoder.OMIT=1.8 TapDecoder.WHOLE_CHANGE=3` | 6 of 6 | 0 of 11,613 | 42 s | 7.7 s |

Two things are read across from Python and are what this check is for: `sweepOutWords` and `sweepAlign` in
`Sweep.kt` are `score.py`'s `out_words` and `align` (which words of a field count as right depends on how the
walk back through `align` breaks its ties, so it is the same walk). The rest is the tools' own code, called:
`Replay.kt`'s `Board`, `Decoder` and `Typist` for the sets, `Bench.kt`'s `sample`, `typeAll`, `byTheTaps`, `Tally`
and `misspellings` for the bench, and `scoreboard.py`'s `kept`, `unseen` and `line` for the sets themselves and
how a line is written. Run `check` again after a merge: it is a few minutes, and nothing else says that the two
tools still type the same thing.

`sweep.py again` is the other half: it types kept answers again and holds each to what was kept, every word of
both sets and every line of the bench. A setting must come out the same whenever it is typed, after whatever
other setting, and whichever threads type it; a dial not put back, or a thing shared between threads that should
not be, would show here sooner or later. 40 settings drawn at random from the 836 kept: 40 the same. The worker
was changed once after its first day's answers were kept (it clocks its threads' own time, and answers a setting
the decoder throws at where it used to die; no number in either): on the 90 settings both builds had typed, every
word and every line of the bench was the same, and the rest of the first build's answers were then carried over
to the second's file (each such line says so), which is what most of those 40 were.

`scoreboard.py --bench --set` takes numbers of `Autocorrect` and `TapDecoder` only (its bench maps every other
name to `TapDecoder` and sets a float). The sweep sets any dial of any class, switches too, in the sets and the
bench alike; for such a setting `check` can hold only the sets to `scoreboard.py`, and says so.

## How long a setting takes

This Mac had ten cores and a load average between 16 and 560 all the while (eight tracks, and other people's
builds), so every figure here is of a busy machine. One setting, sets and bench, in seconds by the clock:

| Threads | Seconds a setting | Load average as it ran |
| ---: | ---: | ---: |
| 1 | 27.8 to 30.3 for the worker's first three, then 14.2 and 14.4 | 24 |
| 2 | 8.2 to 10.7 | 22 |
| 4 | 4.5 to 6.6 | 23 |
| 8 | 3.1 to 5.5 | 26 |

Four is what the tool takes unless told otherwise: eight bought little on a Mac this full, at the others'
expense. The worker is up in 2 to 5 seconds (and compiled in 11 when a source has changed). With four threads it
took 4.9 to 7.7 s a setting at a load average of 16 to 19, 7.6 s at 30 to 40, and 9.3 s when the load average
stood over 500; `scoreboard.py --bench` took 22 to 42 s at 16 to 19, and 40 to 97 s earlier in the day. A search
asks for most settings' sets alone, about half of that: the 514 settings of a search fitted on half the sentences
took 39 minutes with the Mac as it was.

**What a setting costs the decoder** is another thing, and the worker clocks it apart: each typing's thread asks
the JVM what time it has itself had on a core (`sweepThreadNanos`), which other work on the Mac stretches far
less than the clock on the wall (the typing bench's own "µs a word" goes by the clock: 121, 161 and 223 for its
three thumbs at a load average of 8, and 288, 463 and 600 at 39, with not a line of the decoder changed).
`sweep.py time` types the tree and the settings given turn and turn about, several times each after a first
typing that is thrown away (a JVM compiles what it runs as it runs it), and takes the least of each; a search
ends by clocking what it found against the tree the same way. The sets cost more a word than the bench does,
which is worth knowing apart from this tool: about 590 and 620 microseconds a word on the seen and unseen sets
against 140, 220 and 320 for the bench's three thumbs. The bench types a word at a time; the sets go through
`Typist`, which since 2026-11-12 also asks `Autocorrect.rejoin` at nearly every word's end whether this word and
the last were one, and that is a second search of the trie. The typing bench's "µs a word" does not see it. These
are a JVM on a desk, with four threads sharing its caches: they say how one setting stands to another, not how
long a word takes on a phone.

## The objective

A search needs one number to make small. It is, unless `--weights` says otherwise:

- the words left wrong on the seen set and on the unseen set, together (763 for the tree: 326 and 437);
- and a penalty for every figure of the bench that is worse than the tree's own, for each of its three thumbs:
  **4** for each right word spoiled beyond the tree's count (the bench's own measure, `tune`'s, counts a spoiled
  right word four times as bad as a slip left unmended), **4 more** if no list has that word (the bench swaps 3%
  of its words for such ones, as people type names; a name spoiled is typed again and then taught, and a person
  with many names has more than 3%: the 4 is a judgement, not a measurement), **1** for each word right under the
  tree's count; and **1** for each misspelling mended under the tree's 232.

The bench is a guard and not a goal: a setting gets nothing for doing better there than the tree does. These
sentences have few right words that no list has, and a setting can mend their slips by trusting the typed letters
less; the bench is where that shows. `Autocorrect.UNKNOWN_ODD=0`, which [versus.md](versus.md) found would mend
the most, comes out at 1,151 against the tree's 763: 74 fewer words wrong on the sets, and a penalty of 462.
`--weights bench=1,spoiled=0,right=0` makes the bench a goal instead (its words wrong, each spoiled one four times
over, added in whether or not they are worse than the tree's).

With `--holdout SEED` the first part is counted on a random half of each set's sentences (a sentence goes whole,
with all three of its typings), the search fits on that, and the other half is reported beside it, with the
middle 95% of the difference over 2,000 resamplings of its sentences. A setting that was found gains on both
halves; one that was fitted gains on the half it saw. The bench is not halved: every search sees all of it, as a
guard.

`sweep.py front` sets the weighing aside: of every setting answered so far it lists those that no other beats on
all three of words wrong on the two sets, listed words spoiled on the bench and unknown words spoiled there. That
is the choice itself, as far as a search's path has shown it; the objective is one way through it.

## One dial at a time

Everything from here on is the decoder of 9468bfd on 2026-11-12, by the tables `sweep.py` printed. In each: the
words wrong on the seen set (of 5,205) and the unseen set (of 6,408); the bench's careful, normal and sloppy
thumbs, three figures to a column; its misspellings mended (of 264); the words of the two sets mended and broken
against the tree; and the objective, which is 763 for the tree.

`sweep.py scan` moved each of the 32 dials a search takes a step of 0.4 down and up, alone (63 settings). Four
change no word and no figure either way: `Autocorrect.MARGIN_RARE`, `Autocorrect.JOIN_FIRST`,
`TapDecoder.NEIGHBOUR` (it prices a letter with no tap of its own, and here every letter has one) and
`TapDecoder.KEEP`. Five single steps lower
the objective, none by much: `TapDecoder.NO_SPACE` 2.8 (755), `TapDecoder.INSERT_NEAR` 2.4 (756),
`Autocorrect.RARE_GAP` 1.6 (757), `Autocorrect.JOIN_UNSEEN` 0.1 and `Autocorrect.REJOIN_MARGIN` 0.6 (762 each).
Every other step raises it or leaves it. That is what dials set one at a time on the typing bench should look
like: each is at the bottom of its own valley, as the bench's guard sees it. What the sets would gain from a dial
is, nearly everywhere, what the bench says it costs in right words spoiled.

Ten of them over a wider range (`sweep.py grid`): four of those the tests feel most, the three whose single
step lowers the objective by more than a point, `OMIT`, and the two the searches below move.

**`TapDecoder.SPATIAL`.** What a key width of extra distance from a tap costs: how tight a thumb is taken to be. The dial the two sets feel most, and the one where they and the bench pull hardest against each other: at 0.9 the sets have 80 fewer words wrong and the sloppy thumb's words right on the bench go from 85.65% to 87.99%, while right words spoiled go from 1.2 to 3.3 in a thousand for the careful thumb and the unknown ones from 1.5% to 8.7%. Higher than the tree's spoils fewer right words and loses far more than that: 31 and 40 more words wrong on the two sets at 1.7.

| `SPATIAL` | seen | unseen | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings | mended | broken | objective |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| 0.9 | 290 | 393 | 96.35 93.11 87.99 | 3.25 3.36 3.31 | 8.7 12.2 13.1 | 232 | 138 | 58 | 1006 |
| 1.1 | 300 | 413 | 96.43 92.99 87.34 | 2.13 2.15 2.48 | 4.9 7.3 9.5 | 231 | 90 | 40 | 850 |
| 1.3 | 312 | 415 | 96.43 92.74 86.53 | 1.62 1.61 2.07 | 3.0 4.9 8.3 | 230 | 47 | 11 | 781 |
| **1.5**, the tree's | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 1.7 | 357 | 477 | 96.39 92.03 84.59 | 1.01 0.94 1.45 | 0.8 1.8 6.0 | 232 | 12 | 83 | 1002 |
| 1.9 | 377 | 517 | 96.37 91.74 83.54 | 0.81 0.81 1.03 | 0.8 1.2 3.6 | 232 | 18 | 149 | 1225 |
| 2.1 | 403 | 551 | 96.28 91.44 82.84 | 0.81 0.67 1.03 | 0.8 0.6 3.6 | 232 | 17 | 208 | 1415 |

**`Autocorrect.MARGIN_COMMON`.** How much more than a common word a reading must be worth to replace it. Lower mends the slips that spell a common word (52 mended and 11 broken at 0.8) and spoils right common words in step: 2.1 to 3.1 in a thousand at 0.8. It leaves the unknown words alone.

| `MARGIN_COMMON` | seen | unseen | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings | mended | broken | objective |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| 0.4 | 309 | 408 | 96.41 92.60 86.34 | 2.94 4.04 4.55 | 1.5 4.3 7.1 | 232 | 70 | 24 | 917 |
| 0.8 | 306 | 416 | 96.43 92.55 86.20 | 2.13 2.83 3.10 | 1.5 4.3 7.1 | 232 | 52 | 11 | 826 |
| 1.2 | 317 | 420 | 96.42 92.51 85.93 | 1.52 1.75 2.90 | 1.5 4.3 7.1 | 232 | 32 | 6 | 781 |
| **1.6**, the tree's | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 2 | 336 | 451 | 96.37 92.13 85.22 | 1.01 1.48 1.65 | 1.5 4.3 7.1 | 232 | 3 | 27 | 869 |
| 2.4 | 358 | 477 | 96.25 91.83 84.64 | 1.01 1.48 1.45 | 1.5 4.3 7.1 | 232 | 4 | 76 | 1036 |

**`Autocorrect.UNKNOWN_ODD`.** What letters that look like no word are worth. Lower breaks nothing on the sets down to 1.0 (45 mended, none broken), and all of what it costs is on the bench: the unknown words spoiled double.

| `UNKNOWN_ODD` | seen | unseen | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings | mended | broken | objective |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| 0.2 | 293 | 398 | 96.38 92.62 86.46 | 2.64 3.50 3.31 | 6.8 13.4 15.5 | 232 | 76 | 4 | 987 |
| 0.6 | 297 | 406 | 96.42 92.58 86.34 | 2.13 2.96 3.10 | 4.9 11.0 15.5 | 232 | 64 | 4 | 919 |
| 1 | 305 | 413 | 96.42 92.55 86.24 | 1.62 2.29 2.48 | 3.0 7.9 11.9 | 232 | 45 | 0 | 830 |
| 1.4 | 314 | 422 | 96.39 92.46 85.97 | 1.62 2.15 2.28 | 3.0 7.3 10.7 | 232 | 27 | 0 | 834 |
| **1.8**, the tree's | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 2.2 | 346 | 463 | 96.35 92.18 85.09 | 1.01 1.21 1.24 | 0.8 3.0 4.8 | 229 | 0 | 46 | 906 |
| 2.6 | 379 | 479 | 96.23 91.91 84.49 | 0.91 0.94 1.24 | 0.4 1.8 4.8 | 229 | 1 | 96 | 1074 |

**`Autocorrect.UNKNOWN`.** What a typed word no list has is worth. Lower gains a little everywhere but in right words spoiled; higher loses words and misspellings and spoils no fewer.

| `UNKNOWN` | seen | unseen | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings | mended | broken | objective |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| 2 | 320 | 425 | 96.47 92.54 86.04 | 2.13 2.56 2.28 | 4.6 8.5 11.9 | 244 | 29 | 11 | 901 |
| 2.4 | 321 | 425 | 96.49 92.55 85.94 | 1.62 2.02 2.07 | 3.0 6.1 9.5 | 238 | 23 | 6 | 822 |
| 2.8 | 322 | 428 | 96.49 92.51 85.81 | 1.32 1.88 1.86 | 1.9 5.5 8.3 | 235 | 15 | 2 | 786 |
| **3.2**, the tree's | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 3.6 | 333 | 448 | 96.31 92.13 85.32 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 229 | 3 | 21 | 862 |
| 4 | 343 | 466 | 96.09 91.80 84.94 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 223 | 4 | 50 | 1007 |
| 4.4 | 359 | 489 | 95.92 91.42 84.25 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 214 | 3 | 88 | 1204 |

**`Autocorrect.RARE_GAP`.** A rare listed word gives way to a word this much commoner. At 1.6: 18 words mended and none broken, three more misspellings mended, and one more right word spoiled for each thumb (the penalty of 12): one of the three dials that alone lower the objective by more than a point (757).

| `RARE_GAP` | seen | unseen | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings | mended | broken | objective |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| 0.8 | 319 | 437 | 96.40 92.34 85.68 | 1.83 2.15 2.07 | 1.5 4.3 7.1 | 235 | 20 | 13 | 809 |
| 1.2 | 319 | 435 | 96.40 92.35 85.68 | 1.73 2.02 1.86 | 1.5 4.3 7.1 | 235 | 20 | 11 | 795 |
| 1.6 | 315 | 430 | 96.42 92.38 85.68 | 1.32 1.61 1.86 | 1.5 4.3 7.1 | 235 | 18 | 0 | 757 |
| **2**, the tree's | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 2.4 | 328 | 438 | 96.40 92.29 85.58 | 1.12 1.35 1.65 | 1.5 4.3 7.1 | 225 | 2 | 5 | 789 |
| 2.8 | 331 | 450 | 96.37 92.20 85.46 | 1.12 1.35 1.65 | 1.5 4.3 7.1 | 213 | 2 | 20 | 845 |

**`TapDecoder.OMIT`.** A letter of the word with no tap. Cheaper mends 21 and breaks 9 at 1.6, and spoils a few more right words; dearer loses misspellings (216 of 264 at 2.4).

| `OMIT` | seen | unseen | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings | mended | broken | objective |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| 1.2 | 326 | 424 | 96.51 92.42 85.94 | 1.73 2.29 1.86 | 2.3 6.1 7.1 | 237 | 32 | 19 | 818 |
| 1.6 | 323 | 428 | 96.44 92.42 85.77 | 1.52 1.75 1.86 | 1.9 4.3 7.1 | 235 | 21 | 9 | 779 |
| **2**, the tree's | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 2.4 | 331 | 446 | 96.23 92.16 85.44 | 1.22 1.35 1.65 | 1.5 4.3 7.1 | 216 | 0 | 14 | 863 |
| 2.8 | 339 | 456 | 96.17 92.08 85.39 | 1.22 1.35 1.45 | 1.5 4.3 6.0 | 213 | 1 | 33 | 905 |

**`TapDecoder.NO_SPACE`.** The space missed between two words run together. At 2.8 eight words are mended, none broken, and no figure of the bench is worse: the lowest objective one step of 0.4 reaches from the tree (755). From 3.6 up it is past `JOIN_BUDGET` (3.4), and no join without a tap for its space is read at all: the two rows are the same.

| `NO_SPACE` | seen | unseen | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings | mended | broken | objective |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| 2 | 323 | 431 | 96.53 92.48 85.92 | 1.62 1.88 1.45 | 2.3 5.5 7.1 | 232 | 13 | 4 | 798 |
| 2.4 | 323 | 431 | 96.54 92.48 85.84 | 1.42 1.75 1.45 | 2.3 5.5 7.1 | 232 | 9 | 0 | 786 |
| 2.8 | 324 | 431 | 96.46 92.40 85.72 | 1.22 1.48 1.45 | 1.5 4.3 7.1 | 232 | 8 | 0 | 755 |
| **3.2**, the tree's | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 3.6 | 344 | 451 | 96.14 92.12 85.51 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 32 | 871 |
| 4 | 344 | 451 | 96.14 92.12 85.51 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 32 | 871 |

**`TapDecoder.INSERT_NEAR`.** A tap too many, beside the key of the letter next to it. Dearer is a little better as far as 2.8 (753, the lowest in these ten tables); cheaper is much worse.

| `INSERT_NEAR` | seen | unseen | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings | mended | broken | objective |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| 1.2 | 336 | 461 | 96.21 92.04 85.37 | 2.64 3.09 2.48 | 5.3 10.4 10.7 | 232 | 3 | 37 | 1103 |
| 1.6 | 332 | 448 | 96.34 92.26 85.54 | 1.42 1.88 1.86 | 1.5 6.1 7.1 | 232 | 2 | 19 | 848 |
| **2**, the tree's | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 2.4 | 325 | 431 | 96.44 92.41 85.70 | 1.22 1.48 1.45 | 1.5 4.3 6.0 | 232 | 9 | 2 | 756 |
| 2.8 | 324 | 429 | 96.43 92.42 85.74 | 1.22 1.48 1.45 | 1.5 4.3 6.0 | 232 | 14 | 4 | 753 |
| 3.2 | 325 | 430 | 96.44 92.44 85.76 | 1.12 1.35 1.45 | 1.5 4.3 6.0 | 232 | 15 | 7 | 755 |

**`TapDecoder.INSERT`.** A tap too many, anywhere else. Dearer mends a handful and gives up three to six misspellings; the objective does not move. It is here because the searches below move it once other dials have moved.

| `INSERT` | seen | unseen | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings | mended | broken | objective |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| 2 | 338 | 459 | 96.23 92.11 85.43 | 2.33 2.69 2.69 | 4.2 7.9 10.7 | 232 | 1 | 35 | 1037 |
| 2.4 | 330 | 448 | 96.35 92.28 85.64 | 1.73 2.15 2.28 | 3.0 6.7 9.5 | 232 | 0 | 15 | 887 |
| **2.8**, the tree's | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 3.2 | 325 | 435 | 96.41 92.36 85.68 | 1.22 1.35 1.65 | 1.5 3.7 7.1 | 229 | 4 | 1 | 763 |
| 3.6 | 324 | 435 | 96.46 92.40 85.72 | 1.12 1.21 1.65 | 1.5 3.7 7.1 | 229 | 5 | 1 | 762 |
| 4 | 322 | 435 | 96.45 92.37 85.69 | 1.12 1.21 1.65 | 1.5 3.7 7.1 | 226 | 7 | 1 | 763 |
| 4.4 | 322 | 435 | 96.46 92.36 85.66 | 1.12 1.21 1.65 | 1.5 3.7 7.1 | 226 | 7 | 1 | 763 |

**`TapDecoder.INSERT_FIRST`.** A word's first tap as one too many. Dearer changes not one word from the tree; cheaper spoils. Here for the same reason: with `UNKNOWN_ODD` lower, the searches raise it and the bench's penalty goes.

| `INSERT_FIRST` | seen | unseen | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings | mended | broken | objective |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| 3.2 | 327 | 436 | 96.39 92.33 85.61 | 1.32 1.61 2.07 | 1.5 4.3 8.3 | 232 | 1 | 1 | 791 |
| 3.6 | 326 | 437 | 96.39 92.33 85.64 | 1.32 1.61 2.07 | 1.5 4.3 8.3 | 232 | 0 | 0 | 788 |
| **4**, the tree's | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 4.4 | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 4.8 | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 5.2 | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |
| 5.6 | 326 | 437 | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 | 0 | 0 | 763 |

A grid of two dials prints a table for each of the words wrong, the bench's penalty and the objective, one dial
down and the other across. <!-- TWO-DIALS -->(One of `UNKNOWN_ODD` by `SPATIAL` was not through when this was written.)

## Many at once

`sweep.py search` is coordinate descent with a shrinking step. Each dial in turn is tried a step down and a step
up with the others as they stand (0.4 for a cost, one for a whole number, the other position for a switch), and
moved where that lowers the objective by at least one (`--least`), then on the same way while it still does, three
more steps at most; of two moves as good, the one nearer the tree's value. A dial that changes no word either way
is passed over for the rest of that size of step. When a whole round moves nothing the steps are halved, twice
(0.2, then 0.1), and the search ends when a round at the smallest moves nothing. Nothing cleverer was needed to
see what there is: one answer is a few seconds, the objective is a count of words, flat between its steps, and
the settings that matter turn out to be few. What it cannot do is leave a valley for a deeper one a long way
off; `--from SETTING` starts it somewhere else.

**Fitted on half the sentences, told by the other half.** Two searches over all 32 dials, each fitted on a
random half of each set's sentences (`--holdout 1`, and `--other-half`: 150 sentences of each set, 450 typings),
with the whole bench as the guard:

| Fitted on | Settings tried | The half it was fitted on | The half held out | over 2,000 resamplings | seen, all | unseen, all | mended | broken |
| --- | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| the tree | | | | | 326 | 437 | | |
| half A | 514 | 382 wrong to 329 (6.45% to 5.55%) | 381 to 336 (6.70% to 5.91%), 45 fewer | 63 to 28 fewer | 283 | 382 | 110 | 12 |
| half B | 437 | 381 wrong to 337 (6.70% to 5.92%) | 382 to 336 (6.45% to 5.67%), 46 fewer | 63 to 31 fewer | 286 | 387 | 91 | 1 |

Each gained on the half it never saw nearly what it gained on its own (45 against 53, 46 against 44): the
search is finding something, not fitting. And the bench, which neither was asked to better, is better for both:

| | bench: words right % | right words spoiled in 1,000 | unknown words spoiled % | misspellings |
| --- | --- | --- | --- | ---: |
| the tree | 96.41 92.34 85.65 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 |
| fitted on half A | 96.64 92.84 86.38 | 1.12 1.48 1.65 | 1.5 4.9 7.1 | 235 |
| fitted on half B | 96.55 92.75 86.25 | 1.22 1.48 1.65 | 1.5 4.3 7.1 | 232 |

(Half A's setting spoils one right word fewer for the careful thumb. For the normal thumb it spoils as many as
the tree, of which one more is a word no list has: 8 of 164 where the tree spoils 7.)

What the two moved, and where to:

| Dial | The tree | Fitted on half A | Fitted on half B |
| --- | ---: | ---: | ---: |
| `Autocorrect.UNKNOWN_ODD` | 1.8 | 1 | 1.3 |
| `Autocorrect.RARE_GAP` | 2 | 1.6 | 1.6 |
| `TapDecoder.INSERT` | 2.8 | 3.6 | 3.6 |
| `TapDecoder.INSERT_NEAR` | 2 | 3.2 | 2.6 |
| `TapDecoder.NO_SPACE` | 3.2 | 2.8 | 2.8 |
| `TapDecoder.INSERT_FIRST` | 4 | 4.8 | |
| `Autocorrect.REJOIN_MARGIN` | 1 | 0.2 | |
| `Autocorrect.UNKNOWN_LONG` | 1.6 | 1.7 | |
| `TapDecoder.OMIT_DOUBLED` | 1.2 | 0.8 | |
| `TapDecoder.SWAP` | 1.1 | 0.7 | |
| `Autocorrect.RARE_BUDGET` | 2.2 | | 2.4 |
| `TapDecoder.FAR_SHORT` | 4.4 | | 4.8 |
| `TapDecoder.INSERT_DOUBLED` | 1.4 | | 1.9 |
| `TapDecoder.VOWEL` | 2.2 | | 2.3 |

Five dials go the same way in both halves, to much the same place: letters that look like no word held less
dear, a rare word giving way sooner, a tap too many dearer (both kinds), and a missed space cheaper. Those are
the finding. The other nine moved in one half only, each for one to three words of that half (`INSERT_FIRST` for
eleven, every one of them penalty taken off the bench after `UNKNOWN_ODD` had put it there): the other half's
sentences did not ask for them, and they are what fitting looks like.

The largest single move is the one [versus.md](versus.md) could not take alone. From the tree, `UNKNOWN_ODD` at
1.4 leaves 27 fewer words wrong on the two sets and costs 98 on the bench (the table above). In both searches it
came after a tap too many had been made dearer (`INSERT` 3.6, `INSERT_NEAR` 2.8), and the same step then bought
15 and 18 words of half the sentences for a penalty of 8 and of nothing.<!-- UNKNOWN-ODD-WITH -->

<!-- FULL-SEARCH -->**Fitted on everything.** Not through when this was written: `build/versus/logs/search-all.out` has it.

## After a merge

The other changes to the decoder add dials and move the ground under these. In order:

    tool/versus/sweep.py check                    # the two tools still type the same thing (a few minutes)
    tool/versus/sweep.py dials                    # the new dials are there; "searched" marks those a search takes
    tool/versus/sweep.py scan --also Class.SWITCH # which of them these tests can feel
    tool/versus/sweep.py search --holdout 1       # then --holdout 1 --other-half: what moves in both halves is found
    tool/versus/sweep.py search                   # fitted on everything: the values to take for those dials
    tool/versus/sweep.py check "SETTING"          # scoreboard.py agrees about the setting chosen
    tool/versus/sweep.py time --threads 1 "SETTING"   # and what it costs a word beside the tree

- The worker's jar is `Replay.kt`, `Bench.kt` and `Sweep.kt` compiled together with the decoder, all in one
  package: a function or class of the same name added to both `Replay.kt` and `Bench.kt` stops it compiling, and
  the compiler names it. `Sweep.kt` calls `Board.recorded`, `Decoder(root)`, `Typist(board, decoder)` with its
  `tap`, `mark` and `text`, and `Bench.kt`'s `Keyboard`, `Sentences`, `sample`, `typeAll`, `byTheTaps`, `Tally`,
  `misspellings` and `NOISES`: a change to one of those is a change here, and `check` says whether it was made
  right.
- A new source file of the decoder goes into `replay.sh`'s list as into the other tools'; the worker is compiled
  from that list.
- The tree's own answer is the base of every penalty, so the objective of a merged tree starts from the merged
  tree's bench, not this one's.

## What these numbers are not

- **One thumb model.** The sets and the bench are both a simulated thumb's: the sets' three are the bench's
  careful and sloppy ones and a careful one that leans. A setting fitted to them is fitted to that thumb
  ([versus.md](versus.md), "What these numbers are not"). The held-out half says the search is not fitting
  sentences; nothing here says it is not fitting the thumb.
- **The unseen set is no longer unseen** to anything a search fitted on everything has found: it is half the
  objective. That is what the halves are for, and why the search fitted on everything is reported beside them
  and not instead of them. The set kept for the end was not read, and this tool cannot read it.
- **The bench is a guard with a judgement in it.** Four words for a right word spoiled is the bench's own measure;
  four more for one no list has is this tool's. `--weights` and `front` are there for whoever weighs it
  otherwise.
- **Not on a device.** Every figure is the decoder on the JVM, which gave the emulator's text in 900 of 900
  typings of the run that was measured. A setting changes no code, only numbers the same code reads; it was
  still not typed on the emulator.
- **Coordinate descent finds a nearby valley.** Dials that only pay when moved together by more than a step each
  would be missed. The two halves landing in much the same place from the same start says little about that.
