# Many settings of the decoder, tried fast

Matt, 2026-11-12: "I want you to continue work until we get to the 3.8% of gboard or better". The decoder has
seventy numbers and switches that can be set without touching its code: every `@JvmField var` in the companion
objects of `Autocorrect`, `TapDecoder`, `Scorer` and `SwipeDecoder` (`UNKNOWN`, `MARGIN_COMMON`, `SPATIAL`, `OMIT`,
`WHOLE_CHANGE`, `REJOIN_MARGIN` and the rest). [versus.md](versus.md) tried a handful of them one at a time with
`whatif.py --set` and `scoreboard.py --set`, which start a JVM, read the word lists and type 1,800 sentences for
every setting asked about, and another JVM for the typing bench: a minute or two a setting on this Mac as it is
this week. Setting several dials together takes thousands of tries. `tool/versus/sweep.py` answers for a setting in
a few seconds, and has a grid and a search built on that.

    tool/versus/sweep.py dials                                    # every dial, what the tree sets it to, which a search takes
    tool/versus/sweep.py line "Autocorrect.UNKNOWN=2.4 TapDecoder.OMIT=1.8"   # a setting as scoreboard.py --bench prints it
    tool/versus/sweep.py grid TapDecoder.SPATIAL=0.9:2.1:0.2      # one dial: a row a value
    tool/versus/sweep.py grid Autocorrect.UNKNOWN_ODD=1.0,1.4,1.8 TapDecoder.SPATIAL=1.1,1.3,1.5   # two: a table
    tool/versus/sweep.py scan                                     # every dial alone, a step down and a step up
    tool/versus/sweep.py search --holdout 1                       # many dials at once, fitted on half the sentences
    tool/versus/sweep.py check                                    # is it still scoreboard.py's numbers, to the word?

It changes nothing in the app and no dial's value: it says what a value would do.

## What it is

**One JVM that has read everything once** (`tool/versus/replay.sh sweep`, which is `replay/Sweep.kt` compiled
with the replay and the typing bench). It lays out the run's keys, reads the word lists and the starter table, and
then reads settings from standard input, a line each (`Autocorrect.UNKNOWN=2.4 TapDecoder.OMIT=1.8`; an empty line
is the tree as it is). For each it types three things and answers with one line: the seen set and the unseen set
as `scoreboard.py` has them (900 typings each, words wrong by thumb and in all), and the typing bench's own tap
test (12,000 words by its careful, normal and sloppy thumbs: words right, right words spoiled in a thousand, right
words no list has that were spoiled, and its misspellings). After a tab the same line carries every count behind
those figures and a verdict for every word, which is what `sweep.py` reads.

**A dial is found, not listed.** Any field of the keyboard's package that is public, static, not final and a
number or a switch is one, by reflection: a `@JvmField var` added to a companion object, or to a new class, is a
dial here without this tool being touched, and `sweep.py dials` lists it. What each holds as its class loads is
the tree's value, and every one is put back to that before each line, so a line never inherits from the line
before. This tree has 70: 19 of `Autocorrect`, 19 of `TapDecoder`, 6 of `Scorer` and 26 of `SwipeDecoder`.

**One setting at a time, on several threads.** The dials are static, so two settings cannot be tried at once in
one JVM without a class loader each, and each class loader would hold its own copy of the word lists and warm up
its own compiled code. But one setting's work divides evenly: a typing does not depend on the typing before it (a
new `Typist` for each, and `Autocorrect.correct` keeps nothing between words but caches that any thread may fill).
So the worker types one setting's 1,800 typings and the bench's 4,500 on `--threads` threads (4 unless told
otherwise) and then takes the next. That is the same use of the cores as several settings at once would be, with
one copy of everything (half a gigabyte resident, under `-Xmx2g`) and no order of finishing that could change a
number. Class loaders were not built to be measured against it: the rule on this Mac while seven tracks shared it
was one JVM each, and nothing they would have bought was missing.

**The bench's taps are drawn once.** `Bench.kt`'s `typeAll` draws a thumb's taps for all its sentences from one
stream of random numbers, and what the keyboard makes of a word changes none of them. The worker runs it once for
each thumb with a keyboard that changes nothing, keeps every token it typed and every stretch it tallied, and for
each setting reads the tokens again (`byTheTaps`, the bench's own reading of a word) and tallies them with the
bench's own `Tally`, in the bench's order. Three small changes to `Bench.kt` make that possible and leave what it
prints as it was: `byTheTaps` is a function of its own, `taps` takes its sentences from `sample` (which already
drew the same ones the same way for `tune`), and the class that was called `Unit` is `Stretch`, because a class of
that name takes `kotlin.Unit`'s place in every file compiled beside it.

**The sets first, the bench only where it can matter.** A search asks for a setting's sets alone (`only=sets` on
the line), which is about half the work. The bench only ever adds to the objective (below), so where the sets alone
leave it too high for a move, the bench is not typed.

**Every answer is kept** under `build/versus/sweep/`, by a hash of the worker's jar, the sets, the word lists and
the starter table: a grid or a search run again, or run on after being stopped, asks only for what it has not
seen, and a change to the decoder or its lists starts a new file.

**It cannot read the sentences kept for the end.** `scoreboard.py --final` types a third set, from line 110,001 of
the held-out file, whose totals are to be looked at once when the work is done. A search that tries thousands of
settings would wear that out in an afternoon. `sweep.py` makes the unseen set from a copy of the file that stops
at line 110,000 and refuses a first line past it; `Sweep.kt` reads the bench's sentences through
`Sentences(root, 110_000)`. (The file in this worktree has 102,028 lines, so `--final` has nothing to type here
whatever is asked of it; that is the scoreboard's to settle, and was not run.)

## It is the scoreboard's numbers, to the word

`sweep.py check` asks the worker for a setting, then runs `scoreboard.py --bench --save` with the same `--set`s
and holds one to the other: the six lines `scoreboard.py` prints (seen, with Gboard beside it; unseen; the bench's
misspellings and its three thumbs) must be this tool's character for character, and every word of the two sets must
have the same verdict in the file `scoreboard.py` saved. On 2026-11-12, the tree (9468bfd) and three other
settings:

| Setting | Lines the same | Words with another verdict | `scoreboard.py` | The sweep |
| --- | ---: | ---: | ---: | ---: |
| as the tree is | 6 of 6 | 0 of 11,613 | 97 s | 21.6 s |
| `Autocorrect.UNKNOWN_ODD=0` | 6 of 6 | 0 of 11,613 | 59 s | 12.1 s |
| `Autocorrect.MARGIN_COMMON=0.6 Autocorrect.UNKNOWN=1.6` | 6 of 6 | 0 of 11,613 | 40 s | 8.7 s |
| `TapDecoder.SPATIAL=1 TapDecoder.OMIT=1.8 TapDecoder.WHOLE_CHANGE=3` | 6 of 6 | 0 of 11,613 | 50 s | 13.2 s |

Two things are read across from Python and are what this check is for: `sweepOutWords` and `sweepAlign` in
`Sweep.kt` are `score.py`'s `out_words` and `align` (which words of a field count as right depends on how the
walk back through `align` breaks its ties, so it is the same walk). The rest is the tools' own code, called:
`Replay.kt`'s `Board`, `Decoder` and `Typist` for the sets, `Bench.kt`'s `sample`, `typeAll`, `byTheTaps`, `Tally`
and `misspellings` for the bench, and `scoreboard.py`'s `kept`, `unseen` and `line` for the sets themselves and
how a line is written. Run `check` again after a merge: it is a few minutes, and nothing else says that the two
tools still type the same thing.

`scoreboard.py --bench --set` takes numbers of `Autocorrect` and `TapDecoder` only (its bench maps every other
name to `TapDecoder` and sets a float). The sweep sets any dial of any class, switches too, in the sets and the
bench alike; for such a setting `check` can hold only the sets to `scoreboard.py`, and says so.

## How long a setting takes

This Mac had ten cores and a load average between 22 and 88 all the while (seven tracks, and other people's
builds), so every figure here is of a busy machine. One setting, sets and bench, once the JVM is warm:

| Threads | Seconds a setting | Load average as it ran |
| ---: | ---: | ---: |
| 1 | 14.2 to 14.4 | 24 |
| 2 | 8.2 to 9.9 | 22 |
| 4 | 4.5 to 5.5 | 23 |
| 8 | 3.1 to 5.5 | 26 |

Four is what the tool takes unless told otherwise: eight bought little on a Mac this full, at the others'
expense. The worker is up in 2 to 5 seconds. Under the heavier load later in the day the same four threads took
11.2 s a setting over the 63 settings of a scan, and `scoreboard.py --bench` 40 to 97 s for one. A search asks for
most settings' sets alone, about half of that.

The sets cost more a word than the bench does, which is worth knowing apart from this tool. With one thread, at a
load average of 24, the worker spent 451 and 438 microseconds a word on the seen and unseen sets and 129, 189 and
425 on the bench's three thumbs. The bench types a word at a time; the sets go through `Typist`, which since
2026-11-12 also asks `Autocorrect.rejoin` at nearly every word's end whether this word and the last were one, and
that is a second search of the trie. The typing bench's "µs a word" does not see it.

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
halves; one that was fitted gains on the half it saw.
