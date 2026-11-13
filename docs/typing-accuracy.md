# Typing accuracy

What autocorrect, swipe typing and the row go by since 0.11.0 (Matt, 2026-11-10: "improving the reliability of the
swipe and the autocorrect, also make the next word prediction more robust", then "I want the models to be trained
off of previous mistakes and the next word prediction should be influenced by mockingbird"), and how each part was
measured. Next-word prediction has its own page: [next-word.md](next-word.md).

Everything here runs on the phone. Nothing opens a connection, so the Offline switch has nothing of it to stop and
there is no path for it in the network ledger.

## Autocorrect reads where the taps landed

Until 0.11.0 autocorrect knew which keys were pressed and which keys are neighbours. Now it also knows where on
each key the finger came down (`TapDecoder.kt`, `Autocorrect.byTaps`):

- **A tap on the line between two keys is either key.** Every reading of a word is worth its frequency, plus what
  the words before lift it by, less what its slips cost; a letter read as another costs by how much nearer the tap
  was to the key it gave than to the other. "br" with its r tapped on the line beside e is "be"; with the r tapped
  in the middle of its key it stays "br".
- **A word no list has is left alone when it was typed cleanly**, and mended when its taps lean toward a word. A
  common word gives way only to one about forty times as common, and only by slips of the thumb.
- **In English a word is weighed by its place in the sentence** (since 2026-11-12). Where the starter table has a
  row for the one or two words before, a reading is worth how likely its word is after them, and so is the typed
  word: a common word where it all but never stands gives way to a reading that is about 27 times as likely there
  (a worth of 1.0 more, the place counted 0.7 times), and a word the place expects is dearer to change. A listed
  word with every tap well inside its own key is weighed as above all the same, and the place never reads a word
  as its apostrophe spelling ("its" stays "its"). Where no row says, and in every other language, it is as above;
  with English and another language on, the place is read where the other language's table has no row for the
  words before, and counts as far as English's weight goes beside the other's.
  [versus.md](versus.md#a-word-weighed-by-its-place-in-the-sentence-made-2026-11-12) has what that mended, what it
  costs and how it was measured.
- **Two words run together are parted**: "inthe" is "in the", and "thisbis" (the space bar missed for the b above
  it) is "this is". Never a common word, and never where the first word is rare.
- **Misspellings** (definately, tommorow, recieve) are mended as before, and more of them: a long word that is in
  no list and is a letter from a common one is that word misspelled far more often than a name.
- **The row finishes the word the taps are the start of**, a slip or two allowed: "tomir" offers "tomorrow".
- With no taps to go by (text typed by a hardware keyboard, a word the cursor was moved into, a language outside
  the first six), autocorrect is exactly what it was: `tool/lang_check.sh` holds it to its recorded answers.

Around it, in the service:

- **Typed again where a fix was taken out, the word is kept.** A fix noticed a few words later, deleted and typed
  again as it was, is not fixed a second time (backspace straight after a fix always put the word back).
- **Enter finishes the last word** as a space would, where enter sends a message or starts a new line of one.

## Swipe typing

`SwipeDecoder.kt` reads a stroke as a word or several. Since 0.11.0:

- **The first word is read in context**: by next-word prediction's own guess for where the cursor stands (the two
  words before, the person's habits, Mockingbird's samples), where it went by the one word before.
- **Strokes are read in the order they were drawn.** A quick second stroke used to cancel the first, whose word was
  lost; now each waits its turn and is read with the word before it in the text. A reading that comes back after
  the person has moved on is dropped.
- **A stroke is held against a word's lines in their order, and never backwards along one** (`straying`). Before,
  each point of the stroke counted its distance from the nearest line anywhere in the word, so a detour was excused
  by a line the word only reaches later (the d of "adriana" by the way back from i, which made it "ariana"), and a
  there-and-back by the line just drawn (the "re" of "there" runs back along h to e, which made it "the"). Path
  drawn behind where the stroke had got to, past a third of a key, now counts as unexplained.
- **Where the finger doubles back there is a letter.** A turn of 140° or more that none of a reading's letters is
  near counts against it.
- **Long strokes are looked at more closely**: a point every quarter key up to 96 points (it was 48, which left a
  twenty-key stroke a point every half key).
- **Scraps are not words of a phrase**, and a two-letter scrap (wp, pa) does not take the stroke of a word drawn
  over the same line (weep, pisa). A pair of words the starter tables never saw is a less likely phrase.
- **Boards with accented letters as keys** (Turkish, Swedish) read words by the keys they are on.

## Learning from your corrections

Autocorrect and swipe typing are trained by what the person puts right (`MistakeMemory.kt`, `Mistakes.kt`).

What is learned:

| What happened | What it teaches |
| --- | --- |
| A word is typed, then typed again differently in the same place (a slip apart) | what was typed first was a mistake for what stands now: a full lesson |
| A word in the row is tapped that the typed letters are a slip from, not the start of | the same |
| Autocorrect mends a word and the fix is left to stand | half a lesson: it may not have been looked at |
| A fix is undone (backspace straight after it, or the quoted word in the row) | that lesson is forgotten, and the word is kept as a learned word, as before |
| …and then another word is typed over the one put back | the word put back was no word either: it is let go again, and the mistake is learned |
| A swiped word is swapped for another reading in the row | that reading was meant by that stroke: a full lesson |
| A swiped word is deleted and swiped or typed again, as one of the readings it had offered | the same |
| A swiped word is left as it was read | half a count for that reading, where another has been chosen over it before |
| Mockingbird mended a slip in two or more of its samples, always the same way | a lesson, while its plugin is added |

How it is used:

- A typed word that has been put right the same way twice (two full lessons, or four fixes left to stand), and
  more often than any other way, is **mended that way from then on**, whatever its taps say. Its capital is kept
  where the person gave it one in the middle of a sentence and it is no ordinary word ("kria" is "Kira").
- **A common word is never mended by memory alone.** "form" put right to "from" twice makes "from" count for more
  among the readings of the taps the next time "form" is typed, and it still has to win there.
- **A spelling that is also meant is not mended away.** Each time a remembered mistake's spelling is typed and left
  to stand, that counts against mending it; "than" stays "than" for someone who writes it as often as they
  mistype "then".
- A learned word (one the person kept) always wins over a remembered mistake of the same spelling.
- A swipe's readings are put in the order the person keeps choosing: a reading picked over the first one twice,
  and at least once more often than the first was meant, goes first. Only ever one of the readings the stroke gave.

What is never learned from: a password field, anything while incognito (the plugin's chip, an app's profile, a
field that asks keyboards not to learn), an address, a name, a search, this app's own fields, a terminal. The same
rule as next-word learning.

**Settings › Typing › Autocorrect › Your corrections** has the switch ("Learn from my corrections", on by
default), how many words and swipes are remembered, and "Forget my corrections", which deletes every one of them.
With the switch off nothing more is learned and Mockingbird's repairs are not gone by; what was learned is still
used until it is forgotten.

The file is `mistakes.tsv` in the app's no-backup files (not in Android's cloud backup), plain text:

    #ghost-mistakes	1	events=12	faded=0
    t	kria	Kira:2.00
    t	than	then:3.00	than:2.50
    s	form	from:3.00	form:1.00

`t` is a typed word and what it was put right to; `s` a stroke's first reading and what was chosen over it. A
word's own spelling in its row is how often it stood. Counts fade (every 3,000 lessons each is multiplied by 0.8,
and one under 0.3 is dropped), at most 3,000 of each kind are kept, and it is written when the keyboard hides, on a
background thread, by way of a temporary file. A key press never waits on any of it.

## How it was measured

`tool/typing_bench.sh` types Tatoeba's held-out English sentences (every 20th of the export, never counted for the
starter tables) through the keyboard's own code with a simulated thumb: taps scattered around each key, a share of
wild ones, letters dropped, doubled and swapped, spaces missed or landing on the letters above the bar, 3% of words
swapped for names and terms no list has, and a list of common misspellings typed on purpose. **A simulated thumb is
not a person.** The numbers compare one version of the keyboard with another on the same taps; they say nothing of
how good either is on real typing, which is what the accuracy record is for (Settings › Typing stats,
`tool/accuracy-replay.sh`) and, from anyone who turns them on, the anonymous typing reports ([reports.md](reports.md)).
The typing trainer ([trainer.md](trainer.md)) is the one place where the keyboard knows what was meant: a run
measures where that person's taps land about each key, and each run after the first tests a fix made from it, read
both ways on the same taps, which reaches everyday typing only once it is proven better and taken. Against another
keyboard, the same taps are typed into both on the emulator: [versus.md](versus.md).

Tap typing, 30,000 words (`taps`), 0.10.4's autocorrect beside 0.11.0's on the same taps:

| Thumb | Words right, 0.10.4 | 0.11.0 | Right words spoiled per 1,000 | Unknown words spoiled | Run-together words parted |
| --- | ---: | ---: | ---: | ---: | ---: |
| careful | 93.6% | 96.4% | 4.6 → 1.5 | 12.0% → 1.4% | 0% → 58% |
| normal | 85.3% | 91.8% | 4.4 → 1.7 | 13.7% → 2.8% | 0% → 59% |
| sloppy | 72.3% | 83.2% | 3.3 → 1.6 | 12.9% → 3.8% | 0% → 55% |

Misspellings mended: 192 of 264 before, 232 now. Reading a word takes about 0.1 ms on a desk JVM.

Those keys are one and a half times as tall as they are wide, as a phone's are. Where the keys are as tall as wide
(a foldable's inner screen, a tablet, the condensed keyboard; `-Dkeyh=100`) the rows sit closer, a tap is nearer the
keys above and below it, and the same costs make more wrong changes: words right 96.4%, 92.2% and 84.4% (0.10.4:
93.6%, 85.3%, 72.3%), right words spoiled 3.4, 3.4 and 2.4 per 1,000 (0.10.4: 4.6, 4.4, 3.3), unknown words spoiled
7% to 9% (0.10.4: 12% to 14%). Ahead of 0.10.4 there too, by less. Whether a real thumb scatters by the key's
height or by the millimetre is not something a simulated one can say; the accuracy record can.

The row while typing with slips (`row`): the share of key presses saved by tapping a word as soon as the row has
it went from 34.5% to 37.3% for the normal thumb (28.5% to 33.8% for the sloppy one), and words left wrong after
the space from 5.6% to 2.2% (13.5% to 6.2%).

Swipe typing, 6,000 words each drawn as one stroke, and 400 phrases of two and three words drawn in one (`swipe`),
the decoder under 0.10.4's rules beside 0.11.0's on the same strokes:

| Glide | First reading right, 0.10.4 | 0.11.0 | Phrases right, 0.10.4 | 0.11.0 |
| --- | ---: | ---: | ---: | ---: |
| neat | 93.9% | 94.6% | 72.8% | 81.0% |
| normal | 92.5% | 93.5% | 70.3% | 76.8% |
| sloppy | 85.2% | 86.7% | 56.3% | 62.0% |

Words that end in a there-and-back (there, here, were, even, data and the like), forty strokes each (`turns`):
83%, 83% and 68% read right first before, 90%, 88% and 77% now, with the short words their strokes pass for (the,
he, we) as they were. A stroke takes one to two milliseconds on a desk JVM, about a third more than it did.

Learning from corrections (`mistakes`): a person with sixty habits (a word they get wrong the same way six times
in ten) types 30,000 words, putting right seven in ten of the words left wrong; then 30,000 more are scored with
what that taught and without. Habits mended went from 74% to 82% (the habits that spell another real word, form
for from: 32% to 60%), words right from 89.9% to 90.3%, with right words spoiled level (1.9 per 1,000).

Prediction in a writer's own voice (`voice`) is in [next-word.md](next-word.md#mockingbirds-samples).

## Checks

- `android/app/src/test`: `TypingAccuracyTest` (taps, parted words, the row, remembered mistakes, swipe choices,
  Mockingbird's pairs), `MistakeMemoryTest` (what settles, what is forgotten, the file, fading, Mockingbird's
  repairs), `SwipeDecoderTest` (phrases, long words, there-and-backs, detours, scraps), `FieldEditorTest`.
  `cd android && ./gradlew testDebugUnitTest`; `tool/swipe-selfcheck.sh` runs the swipe ones with kotlinc alone.
- `tool/lang_check.sh`: autocorrect with no taps against its recorded answers in seven sets of languages, and a
  floor for swipe typing: of the words drawn straight through their keys, at least 93 in 100 read as themselves
  first. `JAVA_OPTS=-Drecord=swipes tool/lang_check.sh` records the swipe readings afresh after a change to the
  decoder; it prints how many read right before and after, which is the thing to look at first.
- `tool/next_word_check.sh`, `python3 tool/next_words.py --selftest`: next-word prediction.
- `tool/typing_bench.sh [taps|row|swipe|turns|next|mistakes|voice]`: the numbers above. `tune` and `tuneswipe` try
  other costs; the keyboard never changes them.

## Not built

A model of each person's own scatter (it was tried in the benchmark: one setting of the costs did as well for a
careful thumb as for a sloppy one); learning which key a person tends to hit for which (the Adaptive keys plugin
moves the keys' targets instead); correcting the word before the last; carrying what was learned from corrections
in a typing profile; anything on iOS.
