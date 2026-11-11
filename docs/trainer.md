# The typing trainer

Matt, 2026-11-11: "add a 'trainer' where it has a typeracer style thing where we type stories out and it tracks
everything like wpm and missed words etc. I want the keyboard to slowly adapt each run to shift the touch areas and try
different layouts like more condensed or less condensed and after several runs it should build a customized profile
for the user and be able to track common mistakes and help the user correct them, we should only do the condensed vs
expanded views and shifting the UI majorly if the setting is enabled when we start the trainer".

**Settings › Trainer**, on Android. A run shows a part of a story; the person types it with Ghost Keyboard; the run is
timed and scored, the keys' touch areas move a small step toward where the thumbs landed, and what went wrong is
remembered. After five runs there is a profile.

Everything here runs on the phone. Nothing opens a connection, so the Offline switch has nothing of it to stop and
the network ledger has no path for it (`tool/net_check.sh` is unchanged).

The trainer is the one place where the keyboard knows what was meant. Everything
[typing-accuracy.md](typing-accuracy.md) measures was typed by a simulated thumb, which "cannot say how far real
thumbs scatter"; a run can say it for the person holding the phone.

## A run

- The story is shown whole, with a field under it. The run's clock starts at the first key, not when the story
  appears.
- As it is typed the story follows: words typed right fade, words typed wrong turn red, the word in hand is
  underlined with each of its letters marked.
- It ends by itself at the story's last full stop. **Finish**, or the keyboard's Done key, ends it early; the back
  arrow leaves it uncounted.
- A run **counts** once four fifths of the story's words were typed. A run that is left, cut shorter than that, or
  over in under three seconds changes nothing: no history, no step, nothing remembered, and the same part is offered
  again.
- **If Ghost Keyboard is not the keyboard in use** the trainer says so and will not start: a run would measure
  another keyboard. It is asked again as the run starts (`Trainer.start`), so switching away after the page was drawn
  is caught too. The stories are English: a layout with no Latin letters is refused the same way, and with English not
  among the languages the page says autocorrect will not know the words.

What a run comes to (`RunResult`, `TrainerScore`):

| | |
| --- | --- |
| Words a minute | the characters of the words that **came out right**, a word being five characters, over the time from first key to last. A wrong word earns nothing, so leaning on autocorrect that fails does not look fast |
| Accuracy | the share of letter taps that hit the key they were meant for, before autocorrect: taps taken back with backspace count as what they were |
| Words right, right first time | a word is right when it is the story's exactly, capital and full stop included. First time: no backspace in it and no fix |
| Missed words | what was meant and what stood there in the end; words skipped; two words run together (a space missed) |
| Backspaces | every one |
| Autocorrect | **mended** (a wrong word changed to the story's), **spoiled** (a right word changed to another), changed to another wrong word, and typed wrong and let stand |
| Where it went wrong | the keys missed most, with the neighbour hit in their place, and the letter pairs whose second letter went wrong or that were swapped |
| Also | words drawn as a stroke, words taken from the row above the keys (neither says anything of the taps) |

## How the keyboard hears a run

The app and the keyboard are one process, and `Trainer` is where they meet. While a run is on, `TrainerKeys` stands
between the keys and the service that handles them (`KeyboardView`'s listener): every call goes on to the service
exactly as it came, and around each key it reads the field's whole text, before and after. So it never has to follow
what the service does with a key: a fix, a full stop for two spaces, a word put back are seen as they stand in the
text. What changes the text between keys (a word tapped in the row, a stroke read a moment later) is seen as the next
key begins, or when the screen says its field changed. With no run on it does nothing but pass the call on, and it
only ever listens in this app's own field.

For each letter the view gives where the finger landed about the middle of the key it was read as, in fractions of
that key (`KeyboardView.peekTyped`: the same point Adaptive keys goes by, after touch correction).

`TrainerScore` then reads the run back: each word as the taps spelled it is held against the story's word letter by
letter (`TrainerText.letters`: which typed letter stands for which meant one, which is one too many, which two are
swapped), which gives **every tap the key it was meant for**. A tap taken back stood for whatever stands in its place
afterwards. A tap on a neighbour of the key meant, away from the middle of the key it pressed, is a **slip**, and is
measured from the key meant; a wrong key pressed in its middle, or nowhere near, is a wrong letter and says nothing
of the aim. The words themselves are held against the story's (`TrainerText.words`), so a word skipped costs that
word and not every word after it.

## Touch areas: one model, a step a run

There is one model of where a key listens: **Adaptive keys'** (`AdaptiveKeys`), a nudge for each letter key that
`KeyboardView.adapt` reads touches by. The trainer keeps no model of its own; it feeds that one.

Everyday typing can only move a nudge by slips, because it cannot know what a clean tap was meant for. A run knows
what every tap was meant for, so the trainer measures the thing itself: where the taps meant for a key land about its
middle, the mean of all of them (`TrainerAims.Measure`). That mean is the best place for the key to listen (a touch
is then read as the key whose taps it is most like). After each counted run, each key the run touched takes one
**step** toward it (`TrainerAims.step`, written by `AdaptiveKeys.trained`):

- at most **0.04 of a key** a run (under two points of a 44-point key): never a jump;
- shorter while the measure stands on few taps (it is trusted in proportion: halfway at 20);
- none until the key has 15 touches, Adaptive keys' own rule, so a key's first nudge is one step from its middle;
- never more than 0.3 of a key from the middle, Adaptive keys' own bound: eight runs to cross all of it;
- and like every nudge it then drifts back as Adaptive keys has all nudges drift (a little with each clean touch,
  half every two weeks).

Nothing of this can be seen: the keys are drawn where they were.

**Adaptive keys need not be added.** In a trainer run the nudges are in use whatever the plugin says, in the trainer's
own field only. Everyday typing uses them where Adaptive keys is added; applying the profile adds it.

### Does it work

A thumb that always lands off the keys' middles the same way should make fewer mistakes as the runs go by, with the
steps, and not without them. `tool/trainer_sim.sh` has such a thumb type the trainer's stories through the app's own
autocorrect, run scoring and steps (`TrainerSim`, the benchmark's thumb with a lean added: scatter 0.20 of a key
across and 0.15 down, 6% wilder taps), twenty thumbs, twelve runs each, the same touches typed twice:

| Thumb | Wrong keys, as drawn | Wrong keys, with steps | Words wrong, as drawn | Words wrong, with steps |
| --- | ---: | ---: | ---: | ---: |
| lands 0.24 of a key right, 0.18 low | 12.3% → 12.6% | 12.4% → 4.1% | 6.5% → 6.9% | 6.6% → 2.6% |
| lands 0.20 left, 0.15 high | 8.9% → 8.8% | 8.9% → 3.8% | 3.5% → 3.5% | 3.7% → 1.6% |
| a small lean: 0.10 right, 0.06 low | 4.9% → 4.7% | 4.7% → 3.5% | 2.6% → 1.6% | 2.5% → 1.2% |
| no lean | 3.5% → 3.4% | 3.4% → 3.3% | 1.4% → 1.3% | 1.3% → 1.3% |

Each cell is the first two runs → the last four. "Wrong keys" are taps read as another key than the one meant;
"words wrong" are story words that did not come out right, autocorrect and all. Run by run for the first thumb, the
wrong keys go 12.8, 12.1, 9.4, 7.9, 7.4, 6.1, 5.9, 4.0, 4.1, 4.1, 4.0, 4.3 with the steps and 12.1, 12.4, 11.2, 11.1,
12.5, 12.0, 13.2, 11.8, 12.3, 12.6, 12.3, 13.4 without: slowly, and most of it by the eighth run. With no lean there
is nothing to learn, and the steps do no harm: scatter alone moved a nudge an eighth of a key at most, on a key with
few taps yet.

(The two columns differ a little in the first run, before anything has moved. With the key model in use a touch is
read as the key whose middle is nearest, which in a key's corner is the key of the next row, half a key along, and
not the key the touch is over. That is Adaptive keys' own reading, with or without a trainer.)

`TrainerAdaptationTest` holds every build to the direction of it: the wrong keys fall by well over a third with the
steps and stay where they were without; the same for a lean the other way; a thumb with no lean is left as it was; no
key ever moves more than a step in a run, or past its bound.

**A simulated thumb is not a person.** This says the steps do what they are meant to for a steady lean. How real
thumbs lean is what a run measures, and nobody has run this on a phone in a hand yet.

On the emulator (`emulator-5584`, real taps at real key positions on the real keyboard, a scripted thumb landing 0.38
of a key right and 0.10 low of where the keyboard expects, scatter 0.10 and 0.08), after two runs with no lean:

| Run | Thumb | Keys | Taps on their key | Wrong |
| ---: | --- | --- | ---: | ---: |
| 1 | no lean | Condensed, the emulator's own | 177 of 177 | 0% |
| 2 | no lean | Condensed | 170 of 170 | 0% |
| 3 | leaning | Condensed | 162 of 179 | 9.5% |
| 4 | leaning | Condensed | 160 of 172 | 7.0% |
| 5 | leaning | Condensed | 154 of 166 | 7.2% |
| 6 | leaning | Condensed | 171 of 175 | 2.3% |
| 7 | leaning | Condensed | 168 of 171 | 1.8% |
| 8 | leaning | Condensed | 177 of 182 | 2.7% |
| 9 | leaning | Condensed, in a trial | 161 of 161 | 0% |
| 10 | leaning | Short, a trial | 181 of 182 | 0.5% |
| 11 | leaning | Condensed, in a trial | 148 of 148 | 0% |

One thumb, one course of runs: it shows the steps doing on the real keyboard what they do in the simulation, and no
more than that. The trainer's own measure of the thumb after run 5 was 0.23 of a key right and 0.07 low (three
leaning runs of five), each nudge was 0.12 right (three steps of 0.04), and the profile's sentence was "Your taps
land well to the right of a key’s middle and a little below it."

## Trying layouts, only with the switch on

"Try different layouts" is a switch on the trainer's first page. **It is off unless the person turns it on, and it
is read at the moment a run starts** (`Trainer.start`), nowhere else.

- **Off**: the keyboard looks exactly as the person set it, in every run.
- **On**: a run may be typed on keys of another **height** than the person's own: Condensed, Short, Normal or Tall
  (`KeyboardPrefs.KEY_HEIGHT`). The run says so on its page ("Trying Short keys for this run. Your own (Condensed) are
  back when it ends.") and in its results. Practice runs are never trials.

Which height, and what the runs add up to, is `LayoutTrials`. The heights stand in a line, most condensed first. One
is **home**: the best found so far, which starts as the person's own. A trial holds home against one neighbour (a step
more condensed, or a step less), turn about: home, the neighbour, home, the neighbour. A run's score is its words a
minute times its accuracy. The trial ends when:

- the neighbour scored higher in **three of every four pairs** or more and its usual (median) score is **six in a
  hundred** better: it becomes home, one step and no further, and the next step out is tried from there;
- it lost that clearly, or six pairs did not settle it: it is let go and home stays.

Never before **four pairs** (eight runs), so one noisy run decides nothing. When neither neighbour does better the
trials are settled, and every run is on home until "Try the layouts again".

What that rule does was tried on two thousand simulated people, all starting on Normal, whose runs scatter by five
(and by seven) in a hundred of their score (`tool/trainer_sim.sh`):

| Which height is better | End on it, runs scattering 5% | 7% | Runs until settled |
| --- | ---: | ---: | ---: |
| none: all four are worth the same | 83.7% stay on their own | 71.1% | 23 |
| Short, by a tenth | 91.1% | 81.2% | 17 |
| Short, by six in a hundred | 61.7% | 56.6% | 20 |

So a height that is clearly better is found nine times in ten, a small edge is found a little over half the time, and
where nothing is better about one person in six is moved a step by chance (never two: 0.9% ended on Condensed). That
last is why a move is only ever a suggestion on the profile. `TrainerAdaptationTest` holds each build to three in
four staying and four in five finding the better one.

**A trial layout is worn, never set.** The setting is not touched: `Trainer.heightFor` answers for the one run that
asked, in this app's own field, and the service reads it where it reads the key height. So the person's own keys are
back when a run ends, when the trainer is left, when the app goes to the background, and when the app dies mid-run,
because nothing was changed that would need putting back. The setting changes only when the person taps **Use Short
keys** on the profile.

What is tried is key height only. Other layouts a keyboard could try (a narrower one-handed board, a board lifted
off the screen's edge, a number row) are not trialled: see "Not built".

## The profile

After **five stories** (`TrainerBook.PROFILE_RUNS`; practice runs do not count toward it). Five parts are about 215
words and 840 letter taps. By then sixteen letters (a c d e g h i l m n o r s t u w), which take nine taps in ten,
each stand on the fifteen taps Adaptive keys asks before it uses a nudge; five steps of 0.04 have had room to cover a
lean of a fifth of a key; and a trend read off the first three runs and the last three is not one run's doing. b, f,
k, p and y have their fifteen by the tenth run. j, q, v, x and z come up seldom in any English (two or three taps in
five parts here, most of them in "The puzzle box"): their keys are measured slowly, and are in the profile once they
have their taps.

It says:

- **speed and accuracy**, at first (the first three runs) and now (the last three), and the best run;
- **where the taps land**: on the whole, in a sentence ("Your taps land well to the right of a key's middle and a
  little below it"), then key by key, the keys furthest off first, and the slips between keys;
- **key height**: each height's usual score and runs, and the one the trials point to, if the switch was ever on;
- **the mistakes**: words missed more than once, letter pairs that go wrong.

**Apply to everyday typing** adds Adaptive keys where it is not added and blends the measured aims into its model,
through a typing profile's own door (`TypingProfile.import`: each side weighed by its taps). Each run has already
moved the nudges a step; applying moves them to the measure now. **Use Short keys** (where the trials point away from
the person's own) sets the key height. Each is the person's own tap; nothing is applied without it.

The profile **is a Ghost typing profile** ([typing-profile.md](typing-profile.md)), and "See it as a typing profile"
shows the file: `layout`, `keys` (each letter's `dx`, `dy` and `samples`), `slips`, and a `report` of a few sentences.
What a typing profile has no field for rides under `trainer`, which every reader of the format passes over:

```json
{
  "format": "ghost-typing-profile", "version": 1, "created": "2026-11-11T10:00:00Z",
  "source": { "app": "Ghost Keyboard", "tool": "trainer" },
  "report": "Over 7 runs: 43 words a minute (38 at first), 95% of taps on their key. Your taps land a little to the right of a key’s middle and below it. Slips: p for o. Words missed more than once: because.",
  "layout": "qwerty",
  "keys": { "o": { "dx": 0.14, "dy": 0.06, "samples": 84 } },
  "slips": [ { "pressed": "p", "meant": "o", "count": 9 } ],
  "trainer": {
    "runs": 7,
    "wordsAMinute": { "first": 38, "now": 43, "best": 47 },
    "accuracy": { "first": 0.93, "now": 0.95 },
    "keyHeight": { "suggested": "short", "score": 44.1, "runs": 4, "ownScore": 40.6, "ownRuns": 4 },
    "mistakes": [ { "word": "because", "typed": "becuase", "missed": 3, "of": 7 } ],
    "pairs": [ { "pair": "ou", "missed": 9, "of": 40 } ]
  }
}
```

What the trainer applies itself leaves the `slips` out: the aims are already the mean of every tap, slips among them,
and a profile's slips would teach them a second time. Settings' own export (AI › Typing profile) carries the nudges
the trainer gave, since they are Adaptive keys' own.

## Mistakes

`TrainerBook` keeps, across runs:

- **words**: for each story word, how often it was tried and how often its **first try** was not the word (whatever
  autocorrect or a backspace made of it after), what was typed for it, and how its latest eight tries went;
- **letter pairs** and **keys**: how often typed, how often wrong, over every run and lately (each run a thing turns
  up in, what was there before counts six tenths as much), so "getting better" and "getting worse" can be said once
  there is enough to say it.

**Your mistakes** lists them, the words missed more than once first and those still going wrong before those that
have come right, each with its latest tries as marks (✗ ✗ ✓ ✓). **Practise my mistakes** is a run made of them: the
words missed, three times each, and for each letter pair that goes wrong a few of the stories' own words that have
it, twice each (`TrainerBook.practice`). It is typed on the person's own keys, counts toward the mistakes and the
touch areas, and not toward the profile's speed or the layout trials.

**A run's mistakes are also taught to "Your corrections"** (`Mistakes`, [typing-accuracy.md](typing-accuracy.md)),
which is what autocorrect goes by. The trainer knows what was meant, so each is a full lesson, and it stays inside
that memory's own rules:

- only with **Learn from my corrections** on (Settings › Typing › Autocorrect);
- only a spelling a slip or two from the word meant (`MistakeMemory.alike`);
- only where the word meant is one of the word lists', and **what was typed is no word at all**: a real word typed
  for another is as likely the story misread as a habit of the thumb;
- a mistake is still gone by only once it has been made the same way twice, a common word is still never mended by
  memory alone, and a word typed right counts for its spelling as one left to stand does.

## What is never learned

A story is not the person's writing. From a run:

- **no word pair** is counted for next-word prediction, and **nothing goes to Mockingbird**;
- **nothing is counted** for typing stats, the accuracy record, or an anonymous typing report;
- **no word is kept** as a learned word, and the clipboard is not recorded.

Two things hold it. The trainer's field asks keyboards not to learn from it (Android's
`IME_FLAG_NO_PERSONALIZED_LEARNING`, which Ghost Keyboard treats as incognito, and which any other keyboard is asked
too), and it is one of this app's own fields, which none of those ever learn from. And nothing of the trainer's own
code calls any of them (`TrainerKeysTest` reads the sources for it). On the emulator, eleven runs and a practice run
left `next_words.tsv`, `typing_stats.xml`, Mockingbird's samples, the accuracy record (switched on) and every setting
byte for byte as they were.

What a run does hand on, and nothing else: each key's nudge takes its step in Adaptive keys' model, and its
mistakes go to "Your corrections" as above.

What the trainer keeps for itself is `trainer.json` in the app's no-backup files (not in Android's backup): the runs
as a line each (when, which story, the height, the time, the speed, the counts), the measures, the mistakes, the
layout trials, and the switch. It holds a story's words only where they were mistyped. **Forget what the trainer
learned** deletes all of it and takes the steps it gave the keys back out of Adaptive keys' model, as far as they are
still there (`TrainerAims.takeBack`). What it taught "Your corrections" is that memory's, and is forgotten from its
own page; a key height the person took stays theirs.

## The stories

`android/app/src/main/assets/trainer/stories.txt`: nine stories written for the trainer, none anyone else's text,
each told in six parts of 38 to 49 words. A run types one part and the next run goes on with the story, so no part
comes round again until all fifty-four have been typed (`TrainerStories.next`). One of them, "The puzzle box", is
there for the keys plain prose seldom reaches: j, q, x and z.

A part holds only what the letters page types without a detour: letters, spaces, full stops, and at most three
commas (a comma is two taps on this keyboard). No apostrophes (a contraction typed without one is another word as
often as not), no digits, no quotes, no capital but a sentence's first. `TrainerStoriesTest` holds the file to that,
and to every word being one the English list has and autocorrect leaves alone when every tap is in the middle of its
key, so a run never loses a word to the story's own vocabulary.

## Where things live

| File | What |
| --- | --- |
| `TrainerText.kt` | a typed word against the one meant, letter by letter; the words typed against the story's |
| `TrainerRun.kt` | `TrainerRecorder` (a run as it is typed), `TrainerScore` (what it came to), `RunResult` |
| `TrainerAims.kt` | the measure of where a key's taps land, the step, taking the steps back |
| `LayoutTrials.kt` | which height a trial run is typed on, and when a height takes over |
| `TrainerBook.kt` | what is kept between runs: the history, the mistakes, practice runs, the profile |
| `TrainerStories.kt` | the stories file, and which part is next |
| `TrainerCodec.kt` | `trainer.json`, and the profile as a typing profile |
| `TrainerMaps.kt` | what the screen is told, as a channel carries it |
| `Trainer.kt` | the phone's side: a run in hand, the file, the steps into Adaptive keys, the lessons into corrections |
| `TrainerKeys.kt` | the ear on the keyboard's keys |
| `TrainerChannel.kt` | `dev_keyboard/trainer`, the screen's line to all of it |
| `lib/trainer/` | the pages: `trainer_store.dart`, `trainer_screen.dart` |

The first eight have no Android in them (`TrainerCodec` writes JSON with org.json, as `Reports.kt` does). What changed in files the trainer shares: the service
gives the view its listener through `TrainerKeys` and reads `Trainer.heightFor` and `Trainer.adapts` in
`applySettings`; `KeyboardView.peekTyped`; `AdaptiveKeys.trained`; `MainActivity` registers the channel; Settings has
the row (`panes.dart`), and `main.dart` opens a section's own screen when asked for it by name.

## Checks

- `android/app/src/test`: `TrainerTest` (letters and words, a run scored, the step, the trials, the book, the file,
  the profile), `TrainerStoriesTest` (the stories), `TrainerKeysTest` (the ear with a stand-in service and field; a
  trial layout worn only by its run in this app's field; what the screen is told; what a story must never teach),
  `TrainerAdaptationTest` (the leaning thumb; the layout trials). `cd android && ./gradlew testDebugUnitTest`.
- `test/trainer_test.dart`: the first page and its switch, off unless turned on; a run refused where Ghost Keyboard
  is not the keyboard in use; a run to its results; a trial said and never set from the screen; the profile and what
  applying asks for; no Trainer on an iPhone.
- `tool/trainer_sim.sh`: the tables above. `JAVA_OPTS="-Druns=16 -Dseeds=40 -Dlean=0.3,0.1" tool/trainer_sim.sh`.
- On the emulator (2026-11-10, `emulator-5584`, taps at real key positions): twelve runs typed to their results; with
  the switch off two runs' keyboards were the same pixel for pixel but for the last key's backlight; with it on a run
  on Condensed keys was followed by one on Short, said on its page; left part-way, and with the app killed part-way,
  the next field had the Condensed keys again and the key height setting had never changed; the profile appeared after
  the fifth run; a practice run, applying the profile, taking a suggested height, a run refused with another
  keyboard in use, and forgetting were each done once. Nothing here has run on a phone in a hand.

## Not built

- **Other layouts to try.** Only key height is trialled. A narrower board toward one thumb, a board lifted off the
  screen's edge, a number row and a split are all settings the keyboard has; none is tried, because each is a choice
  about how the phone is held more than a thing a score can settle in a few runs.
- **A height of its own.** The trials move between the four heights the keyboard has, not to a height in between.
- **Stories in other languages**, and the trainer on a layout that is not Latin.
- **The trainer on iOS**: the iPhone's keyboard is another port.
- **Anything a real thumb has shown.** Every number here is a simulated thumb's or a scripted one's.
