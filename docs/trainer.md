# The typing trainer

Matt, 2026-11-11: "add a 'trainer' where it has a typeracer style thing where we type stories out and it tracks
everything like wpm and missed words etc. I want the keyboard to slowly adapt each run to shift the touch areas and try
different layouts like more condensed or less condensed and after several runs it should build a customized profile
for the user and be able to track common mistakes and help the user correct them, we should only do the condensed vs
expanded views and shifting the UI majorly if the setting is enabled when we start the trainer".

Then, the same day: "In the trainer make each run after the first test a new fixes and a b test the accuracy and don't
suggest switching until its proven we got a better result".

**Settings › Trainer**, on Android. A run shows a part of a story; the person types it with Ghost Keyboard; the run is
timed and scored, where its taps landed is measured, and what went wrong is remembered. The first run is typed on the
keyboard as it is. Each run after it is typed on a new **fix** (the keys' touch areas a small step nearer to where the
taps land) and is a test of that fix: every tap is read both ways, by the fix and by the keyboard as it is. Everyday
typing changes only when a fix has been proven better and the person switches to it. After five runs there is a
profile.

Its row is at the top of Settings, in the first card with Setup and straight under it (Matt, 2026-11-11: "Place the
training section at the top next to the setup section"); Offline's card comes after. An iPhone has Setup alone there.

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
| Words right, right first time | a word is right when it is the story's exactly, capital and full stop included. First time: no backspace in it and no fix. A word drawn as a stroke or taken whole from the row is right first time where it arrived as the story's word, and not where it had to be taken away or swapped for its other reading |
| Missed words | what was meant and what stood there in the end; words skipped; two words run together (a space missed) |
| Backspaces | every one |
| Autocorrect | **mended** (a wrong word changed to the story's), **spoiled** (a right word changed to another), changed to another wrong word, and typed wrong and let stand. Only words the taps spelled: a stroke read as the wrong word is a missed word, not autocorrect's doing |
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

## Touch areas: a fix a run, read both ways

Where a key listens is a nudge: how far from its middle, in fractions of the key. Everyday typing has one model of
them, **Adaptive keys'** (`AdaptiveKeys`), which `KeyboardView.adapt` reads touches by where that plugin is added.
Everyday typing can only move a nudge by slips, because it cannot know what a clean tap was meant for. A run knows
what every tap was meant for, so the trainer measures the thing itself: where the taps meant for a key land about its
middle, the mean of all of them (`TrainerAims.Measure`). That mean is the best place for the key to listen (a touch
is then read as the key whose taps it is most like).

A **fix** is where the trainer would have every letter key listen (`FixTrial`): the keyboard as it is, with each key
that has enough taps one **step** nearer to that mean than the fix before had it (`TrainerAims.next`):

- at most **0.04 of a key** a fix (under two points of a 44-point key): never a jump;
- shorter while the measure stands on few taps (it is trusted in proportion: halfway at 20);
- none until the key has 15 taps, Adaptive keys' own rule;
- never more than 0.3 of a key from the middle, Adaptive keys' own bound: eight fixes to cross all of it;
- and worn by Adaptive keys' own rules of use (`TrainerAims.inUse`): a key under a fiftieth of a key from its middle
  listens at its middle.

**The first run is typed on the keyboard as it is**, and only measures. **Each story run after it is typed on a new
fix**, a step on from the last, and is an A/B test of it. The keyboard's view reads every letter's touch twice as it
is typed: as the fix reads it, which is what is typed, and as the keyboard as it is would have read the same touch
(`KeyboardView.fix`, `Typed.asIs`; with Adaptive keys added that is its nudges, without it the key under the touch).
The run's result counts both readings over the same taps (`RunResult.hits` and `asIs`), and the taps only one of them
read right (`won`, `lost`). Nothing is set against another run: a story, a tired thumb or a good day is the same for
both readings.

- A fix left untested (its run was left, or not counted) is tested by the next run. A step is only ever taken on a
  run's evidence.
- A fix whose every key is still too near where the keyboard has it to be in use is no fix yet: there is nothing to
  test, the run is typed on the keyboard as it is, and it goes a step on at the next run all the same.
- A practice run of one's own mistakes is typed on the keyboard as it is and tests nothing. Its taps are measured.
- Where Adaptive keys is added or taken away between runs, the fixes begin again from the keyboard as it now is.

**A fix is worn, never set**, like a trial layout. It is kept in the trainer's own file and worn only by the run that
tests it, in the trainer's own field (`Trainer.fixFor`). Adaptive keys' model is not written, whether or not that
plugin is added, so nothing a run finds changes everyday typing, and nothing of it can be seen: the keys are drawn
where they were.

### Proven, and only then offered

The trainer suggests switching only once a fix is **proven** (`FixTrial.verdict`). Over the runs that tested a fix
since the keyboard last changed (the latest eight):

- at least **three** of them: one good run decides nothing, and neither do two;
- of the taps the two read differently, so many more went the fix's way that chance alone would split them that far
  less than once in a hundred tries were the fix no better (a sign test, `Proof.chance`);
- the fix **led steadily from run to run**. A thumb lands a little differently every time the phone is picked up, so
  a run's taps lean together, and a fix can win one run by luck as surely as a coin can come up heads. Each run's
  lead is weighed as a run (`FixRun.lead`: the taps won less the taps lost, over the square root of their number),
  and the leads have to be steadier than chance gives once in a hundred tries (Student's t, `Proof.steady`);
- and the run just typed did not go against it.

Until then the pages say what the two readings came to and what is still missing ("A fix is proven on 3 runs or more:
1 more to go", "It is ahead on the count, but not steadily from run to run, and one good run is not proof"), and
there is no switch to tap. The same held the other way round, on less (once in twenty), is **worse**: the fix is
dropped, the page says so, and the next starts again from the keyboard as it is.

**Switch everyday typing to fix N** is the person's own tap, on the trainer's first page, a run's results or the
profile (`Trainer.apply`, which does nothing unless the fix is proven). What it takes is the fix the runs tested
(`FixTrial.testedAt`), never one made since: a run that is started and left has already made the next fix, which no
run has typed on, and that one stays the trainer's. Adaptive keys is added where it is not, and
each key the fixes have moved listens in its model where the fix has it. Where Adaptive keys was not added the runs
read every other key at its middle, so a nudge left in the model from before is cleared: everyday typing then reads a
touch exactly as the runs that proved the fix did. The fix is now the keyboard as it is; later fixes are held against
it, and what the model was given is remembered so that Forget can take it back.

Like any nudge there, a fix that was taken drifts back as Adaptive keys has all nudges drift (a little with each
clean touch, half every two weeks). The trainer's own copy does not, so a later run tests whether the drift cost
anything.

### Does it work

A thumb that always lands off the keys' middles the same way should make fewer mistakes as the runs go by, typed on
the fixes, and not without them. `tool/trainer_sim.sh` has such a thumb type the trainer's stories through the app's
own autocorrect, run scoring and fixes (`TrainerSim`, the benchmark's thumb with a lean added: scatter 0.20 of a key
across and 0.15 down, 6% wilder taps), twenty thumbs, twelve runs each, the same touches typed twice:

| Thumb | Wrong keys, as it is | Wrong keys, on the fixes | Words wrong, as it is | Words wrong, on the fixes |
| --- | ---: | ---: | ---: | ---: |
| lands 0.24 of a key right, 0.18 low | 12.3% → 12.6% | 12.1% → 4.1% | 6.5% → 6.9% | 6.5% → 2.6% |
| lands 0.20 left, 0.15 high | 8.9% → 8.8% | 8.8% → 3.8% | 3.5% → 3.5% | 3.6% → 1.6% |
| a small lean: 0.10 right, 0.06 low | 4.9% → 4.7% | 4.8% → 3.5% | 2.6% → 1.6% | 2.5% → 1.2% |
| no lean | 3.5% → 3.4% | 3.5% → 3.3% | 1.4% → 1.3% | 1.3% → 1.3% |

Each cell is the first two runs → the last four. "Wrong keys" are taps read as another key than the one meant;
"words wrong" are story words that did not come out right, autocorrect and all. Run by run for the first thumb, the
wrong keys go 12.1, 12.1, 9.4, 7.9, 7.4, 6.1, 5.9, 4.0, 4.1, 4.1, 4.0, 4.3 on the fixes and 12.1, 12.4, 11.2, 11.1,
12.5, 12.0, 13.2, 11.8, 12.3, 12.6, 12.3, 13.4 on the keyboard as it is: slowly, and most of it by the eighth run.
The first run is the very same either way (it is typed on the keyboard as it is). With no lean there is nothing to
learn, and the fixes do no harm: scatter alone moved a key an eighth of a key at most, on a key with few taps yet.

**The second reading is the keyboard as it is.** The runs typed on the fixes, read back as the keyboard as it is
would have read them, came to 12.5, 11.2, 11.1, 12.5, 12.0, 13.2, 11.8, 12.3, 12.6, 12.3, 13.4 wrong keys in a
hundred from the second run on: the very numbers the same touches gave when they were typed on it. That is what lets
one run be both sides of the test.

### How often "proven" is right

The same simulation, a thousand thumbs of each kind, twelve runs each, every run after the first testing a fix
(`tool/trainer_sim.sh`, "Proof for…"):

| Thumb | Told a fix was proven | By run 5 | By run 6 | By run 8 | Had a fix dropped |
| --- | ---: | ---: | ---: | ---: | ---: |
| lands 0.24 of a key right, 0.18 low | 100% | 27.4% | 51.6% | 97.1% | 0% |
| lands 0.20 left, 0.15 high | 100% | 24.4% | 43.4% | 90.2% | 0% |
| the first, and its lean strays 0.08 of a key from run to run | 99.8% | 22.6% | 43.3% | 89.5% | 0% |
| a small lean: 0.10 right, 0.06 low | 63.3% | 4.3% | 7.9% | 29.9% | 0% |
| a slight lean: 0.05 right, 0.03 low | 9.9% | 0.3% | 0.6% | 3.2% | 0.2% |
| **no lean** | **0.2%** | 0% | 0% | 0% | 1.6% |
| **no lean of its own; a new one every run, 0.08 of a key** | **1.0%** | 0% | 0% | 0.5% | 8.7% |
| **no lean of its own; a new one every run, 0.15 of a key** | **1.7%** | 0.5% | 0.9% | 1.2% | 20.6% |

The last three are thumbs the fixes do nothing for, and a fix called proven for one of them is a mistake: two in a
thousand, and under two in a hundred for a thumb whose lean is new every time. A steady lean of a fifth of a key is
proven for every thumb, half of them by the sixth run. A small lean takes longer, and a slight one is mostly never
proven in twelve runs: the fix reads a few more taps right for it (96.8% against 96.5%), which is too little to tell
from chance, and the trainer says "not proven yet" rather than guess.

The steadiness rule is what the third row from the bottom is for. Without it (a count of taps alone, ahead in two
runs of three) the thumb with a new lean every run was told a fix was proven 12.3 times in a hundred, because each
run's taps lean together and two runs that happen to lean the fix's way look like a great many taps agreeing. With
it, 1.7. The cost is a run or two more before a real lean is proven.

`TrainerFixTest` holds the rules (a new fix a run, none on an untested one, what is and is not proof), and
`TrainerAdaptationTest` holds every build to these courses: the wrong keys fall by well over a third on the fixes and
stay where they were without; the same for a lean the other way; a thumb with no lean is left as it was; no key ever
moves more than a step in a fix, or past its bound; a steady lean is proven for nine thumbs in ten by the eighth run
and never before the fourth; and of 150 thumbs with no lean at most two, and of 150 with a new lean every run at
most seven, are told a fix was proven.

**A simulated thumb is not a person.** This says the fixes and their test do what they are meant to for a steady
lean. How real thumbs lean is what a run measures, and nobody has run this on a phone in a hand yet.

On the emulator (`emulator-5584`, 2026-11-11, real taps at real key positions on the real keyboard, a scripted thumb
landing 0.38 of a key right and 0.10 low of where the keyboard expects, scatter 0.10 and 0.08), from a trainer with
nothing kept and Adaptive keys not added:

| Run | Typed on | Keys the fix moved | Taps on their key | The same taps, as the keyboard is | Verdict |
| ---: | --- | ---: | ---: | ---: | --- |
| 1 | the keyboard as it is | | 152 of 180 | | |
| 2 | fix 1 | 3 | 154 of 175 | 150 | testing |
| 3 | fix 2 | 11 | 168 of 178 | 160 | testing |
| 4 | fix 3 | 12 | 164 of 172 | 154 | **proven** |
| | *switched to fix 3* | | | | |
| 5 | fix 4 | 14 | 168 of 171 | 163 | testing |
| 6 | fix 5 | 16 | 176 of 177 | 171 | testing |
| 7 | fix 6 | 18 | 168 of 168 | 165 | **proven**, against fix 3 |
| | *a run started on fix 7 and left; switched to fix 6* | | | | |

One thumb, one course of runs: it shows the test doing on the real keyboard what it does in the simulation, and no
more than that. Over runs 2 to 4 the fix read 22 taps right that the keyboard as it is read wrong and none the other
way. The switch added Adaptive keys, put the twelve keys of fix 3 in its model where the fix had them, and cleared
seven nudges an earlier use of the plugin had left on keys the fix had not moved. After it the keyboard as it is read
the fifth run's taps 163 of 171 right, where it had read 154 of 172 before, and the fixes after it were held against
it: thirteen taps to none over runs 5 to 7. Then a run was started, which made fix 7, and left after twenty taps. The
page offered fix 6 and named fix 7 nowhere, and the switch put fix 6 in the model, all eighteen keys where the runs
had tested them, with fix 7 still in hand for the next run. A second course, with a thumb leaning
0.23 and 0.13 at a scatter of 0.08, proved nothing: the keyboard as it is read every one of its taps right, so no fix
had anything to add, and none was offered.

An earlier course on the same emulator (2026-11-10, when each run still moved Adaptive keys' own model a step and
nothing was tested) is what the step rule was first tried on: the same thumb's taps on their key went from 162 of 179
to 177 of 182 over six runs.

## Trying layouts, only with the switch on

"Try different layouts" is a switch on the trainer's first page. **It is off unless the person turns it on, and it
is read at the moment a run starts** (`Trainer.start`), nowhere else.

- **Off**: the keyboard looks exactly as the person set it, in every run.
- **On**: a run may be typed on keys of another **height** than the person's own: Condensed, Short, Normal or Tall
  (`KeyboardPrefs.KEY_HEIGHT`). The run says so on its page ("Trying Short keys for this run. Your own (Condensed) are
  back when it ends.") and in its results. Practice runs are never trials.

Which height, and what the runs add up to, is `LayoutTrials`. The heights stand in a line, most condensed first. One
is **home**: the best found so far, which starts as the person's own. A trial is an A/B test of home against one
neighbour (a step more condensed, or a step less), turn about: home, the neighbour, home, the neighbour, each pair of
runs typed one after the other so that a good day counts for both. A run's score is its words a minute times its
accuracy. The trial ends when:

- the neighbour is **proven** better: over four pairs or more its lead, pair by pair, is so steady that chance would
  give one like it less than once in two hundred tries were it no better (a paired t test on the scores' ratios). It
  becomes home, one step and no further, and the next step out is tried from there;
- its scores are not ahead at all once four pairs are in, or ten pairs have not proven it: it is let go and home
  stays.

Never before **four pairs** (eight runs), so one noisy run decides nothing. When neither neighbour is proven better
the trials are settled, and every run is on home until "Try the layouts again". The profile says "No height was
proven better than your own", which is all the runs can say: not that none is.

What that rule does was tried on two thousand simulated people, all starting on Normal, whose runs scatter by five
(and by seven) in a hundred of their score (`tool/trainer_sim.sh`):

| Which height is better | End on it, runs scattering 5% | 7% | Runs until settled |
| --- | ---: | ---: | ---: |
| none: all four are worth the same | 96.2% stay on their own | 96.3% | 25 |
| Short, by a tenth | 85.2% | 55.8% | 22 |
| Short, by six in a hundred | 41.7% | 22.3% | 26 |

So where nothing is better about one person in twenty-six is moved a step by chance (the rule before this one, three
pairs of four and six in a hundred, moved one in six). A height that is a tenth better is found for five people in
six where runs are steady and a little over half where they are not, and a small edge mostly is not proven: the
trials would rather say nothing than suggest a change on less. That is the price of proof, and it is why a trial is
looked at after every pair at one in two hundred and not at one in twenty: looking more often finds more by chance.
`TrainerAdaptationTest` holds each build to nineteen in twenty staying and three in four finding the better one.

A height's lead is its **score**, not its taps on their key alone: taller keys are easier to hit and cost screen, so
on accuracy alone the tallest would nearly always win. The profile says each height's taps on their key beside its
score, for the person to weigh.

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
each stand on the fifteen taps Adaptive keys asks before it uses a nudge; four runs have each tested a fix, which is
enough for a steady lean to have been proven or not; and a trend read off the first three runs and the last three is
not one run's doing. b, f,
k, p and y have their fifteen by the tenth run. j, q, v, x and z come up seldom in any English (two or three taps in
five parts here, most of them in "The puzzle box"): their keys are measured slowly, and are in the profile once they
have their taps.

It says:

- **speed and accuracy**, at first (the first three runs) and now (the last three), and the best run;
- **where the taps land**: on the whole, in a sentence ("Your taps land well to the right of a key's middle and a
  little below it"), then key by key, the keys furthest off first, and the slips between keys;
- **key height**: each height's usual score, its taps on their key and its runs, how the height being tried stands,
  and the one the trials proved better, if the switch was ever on;
- **everyday typing**: the test of the fixes as it stands, with both readings of the runs that tested one;
- **the mistakes**: words missed more than once, letter pairs that go wrong.

**Switch everyday typing to fix N** is there only once a fix is proven (above), and **Use Short keys** only once the
trials have proven that height better. Each is the person's own tap; nothing is switched without it, and the keyboard
does neither unless it is proven, whatever the screen asks (`Trainer.apply`).

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

The file is where the taps were measured to land, for the person to read or carry to another keyboard. The trainer
itself imports none of it: what reaches everyday typing is a proven fix, a step at a time. Settings' own export (AI ›
Typing profile) carries the nudges of a fix that was taken, since they are Adaptive keys' own.

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

## The pictures

Matt, 2026-11-11: "Show inforgraphics on the trainer with charts and stuff to show where the mistakes happen and what
has shifted where using a simplified keyboard display". The keyboard is drawn simply (`lib/trainer/trainer_board.dart`:
its letter keys where the layout in use has them, and the space bar's row as shapes with nothing on them), in two ways.

**Where the mistakes happen.** Each key is tinted by the share of the taps meant for it that missed, with that share
under its letter: a little shows early (the tint goes by the share to the power 0.6) and one tap in five or more is
the whole of it. A key is tinted once it has ten taps, since three taps say nothing; in a single run's picture, from
its second. An arrow runs from a key to the one hit in its place, for the six commonest slips seen twice or more
(once, in a run's picture).

**What has shifted.** For each key a ring where the taps meant for it land about its middle (`TrainerAims.Measure`,
once it stands on fifteen taps), and an arrow from the middle to where the fix has the key listening. An arrow in the
trainer's colour is a key the fix has somewhere else than the keyboard as it is; a grey one is where the keyboard has
it already (a nudge of Adaptive keys' own, or a fix that was taken). Fix by fix the arrow grows toward the ring. Both
are drawn one and a half times their size (`boardOffsetScale`) and kept inside their key, and the page says so: a
step is a twenty-fifth of a key, which at a key's own scale is a pixel. An arrow too short for a shaft is all head,
and the head is narrower than long so that its way still reads. The line under the picture says how many keys the fix
moves and which is furthest; the footer says which way the taps lean on the whole (`TrainerBook.lean`).

**The test.** The runs that tested a fix as two lines, oldest first (`FixFigure`): the share of each run's taps on
their key as the fix read them, and as the keyboard as it is would have read the very same taps, with the fix each
run tested named under it. The gap between the lines is the fix's lead.

Where they are:

| Page | What is drawn |
| --- | --- |
| The trainer's first page | **The test**: the fix in hand, where it stands, the two lines, and the switch once it is proven. Once a run has been counted, both pictures of the keyboard over every run (the arrows are the fix in hand); and under the speed bars of **Your runs** two lines, the share of taps on their key and of words right first time, run by run |
| A run, as it is typed | which fix it is typed on and tests ("Testing fix 3: 12 keys’ touch areas moved. Every tap is read both ways."), or that it is the first |
| A run's results | **The test** first: its taps as the fix read them and as the keyboard as it is would have, the taps only one read right, where that leaves the fix, and the switch once it is proven. Then that run's own two pictures: where it went wrong (**Where it went wrong**), and the touch areas of the fix it was typed on |
| History | each run's fix, and what the keyboard as it is would have made of its taps ("fix 3, 90% as it is") |
| Your mistakes | where the mistakes happen, over the lists |
| Your profile | where the taps land and what has shifted, over **Where your taps land**; the test, under **Everyday typing** |

The keyboard works all of it out and the screen only draws: `TrainerMaps.board` gives the keys row by row with each
one's taps, misses, landing place and where the fix has it, and the slips; `Trainer.state` sends it as `board` (with
`lean`), and `Trainer.finish` sends the run's own. `TrainerMaps.fix` gives the test: the fix in hand, the runs that
tested one, the verdict, and what keeps it from proven. The screen judges nothing: the switch is drawn where the
keyboard said `proven` and nowhere else (`lib/trainer/trainer_fix.dart`). Every picture says what it shows in a line
under it, which is also what a screen reader is told of it.

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
byte for byte as they were; so did two more runs with words drawn as strokes, a word taken from the row, a stroke
taken back with backspace and drawn again, and a stroke swapped for its other reading and back.

What a run does hand on, and nothing else: its mistakes go to "Your corrections" as above. A run does not touch
Adaptive keys' model (`TrainerKeysTest` reads the source for it: the model is written in two places only, taking a
proven fix and taking it back).

What the trainer keeps for itself is `trainer.json` in the app's no-backup files (not in Android's backup): the runs
as a line each (when, which story, the height, the time, the speed, the counts, the fix it tested and its second
reading), the measures, the mistakes, the fix in hand with the runs that tested one, the layout trials, and the
switch. It holds a story's words only where they were mistyped. **Forget what the trainer learned** deletes all of
it and takes what a fix the person switched to gave the keys back out of Adaptive keys' model, as far as it is still
there (`TrainerAims.takeBack`). What it taught "Your corrections" is that memory's, and is forgotten from its own
page; a key height the person took stays theirs, and so does Adaptive keys where a switch added it.

**A trainer kept by an earlier version** (0.13.0 and 0.13.1 moved Adaptive keys' model a step after every run,
proven or not) comes across as it is. Its runs and measures are kept. The steps it gave the keys stay in the model,
where Forget still takes them back: with Adaptive keys added they are the keyboard as it is, and the first fix is a
step on from them; without it they were never in everyday use, and the first fix starts from them, untested, so the
next run tests what those runs had built. A key height that version had moved the trials to on its looser rule is
not proven, and the trials begin again from the person's own keys with every score kept.

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
| `TrainerAims.kt` | the measure of where a key's taps land, the step from one fix to the next, the rules of use, taking back what was given |
| `TrainerFix.kt` | `FixTrial`: the fix in hand, the runs that tested one, and the verdict; `Proof`: the sign test and Student's t the verdicts stand on |
| `LayoutTrials.kt` | which height a trial run is typed on, and when a height is proven |
| `TrainerBook.kt` | what is kept between runs: the history, the mistakes, practice runs, the profile |
| `TrainerStories.kt` | the stories file, and which part is next |
| `TrainerCodec.kt` | `trainer.json`, and the profile as a typing profile |
| `TrainerMaps.kt` | what the screen is told, as a channel carries it; `board`, the keyboard as the pictures draw it; `fix`, the test |
| `Trainer.kt` | the phone's side: a run in hand and the fix it wears, the file, a proven fix into Adaptive keys, the lessons into corrections |
| `TrainerKeys.kt` | the ear on the keyboard's keys |
| `TrainerChannel.kt` | `dev_keyboard/trainer`, the screen's line to all of it |
| `lib/trainer/` | the pages: `trainer_store.dart`, `trainer_screen.dart`; the test as the pages read it: `trainer_fix.dart`; the pictures: `trainer_board.dart` |

The first nine have no Android in them (`TrainerCodec` writes JSON with org.json, as `Reports.kt` does). What changed in files the trainer shares: the service
gives the view its listener through `TrainerKeys` and reads `Trainer.heightFor` and `Trainer.fixFor` in
`applySettings`; `KeyboardView.fix`, the second reading it makes of a touch while one is worn (`Typed.asIs`), and
`peekTyped`; `AdaptiveKeys.trained`; `MainActivity` registers the channel; Settings has the row (`panes.dart`, in
Setup's group), and `main.dart` opens a section's own screen when asked for it by name.

## Checks

- `android/app/src/test`: `TrainerTest` (letters and words, a run scored, the step, the trials and what is proof for
  one, the book, the file with the fix trial in it, the profile), `TrainerFixTest` (a run read both ways; the first
  run only measures and each after it tests a new fix; none on an untested one; the rules of use; what is and is not
  proof; a fix dropped; a fix taken; the two tests themselves), `TrainerStoriesTest` (the stories), `TrainerKeysTest`
  (the ear with a stand-in service and field; a trial layout and a fix worn only by their run in this app's field;
  what the screen is told; what a story must never teach, and that the key model is written only where a proven fix
  is taken or taken back), `TrainerAdaptationTest` (the leaning thumb; how often the test says proven, to which
  thumbs; the layout trials). `cd android && ./gradlew testDebugUnitTest`.
- `test/trainer_test.dart`: the first page and its switch, off unless turned on; a run refused where Ghost Keyboard
  is not the keyboard in use; a run to its results; a trial said and never set from the screen; a run typed on a fix
  saying so, and its results setting the two readings side by side; **the switch offered in no state but proven**
  (seven that are not, each with what it says), and switching the person's own tap; the profile; no Trainer on an
  iPhone; its row in Settings' top card, under Setup, opening from there; and which page draws which picture, from
  what the keyboard sent.
- `test/trainer_fix_test.dart`: what the keyboard sent read into a test (and anything else read as none), that only
  the keyboard's own verdict is ever proof, each sentence, and the two lines drawn at three widths in both themes.
- The pictures: `TrainerBoardTest` (the keys of every layout row by row, a key's numbers, which slips are drawn, and
  that a channel carries all of it) and `test/trainer_board_test.dart` (what was sent read into a board, each
  picture's line, and both pictures and the lines drawn at three widths in both themes, measured and not).
- The pictures on the emulator (2026-11-11, `emulator-5584`, taps at real key positions): after two clean runs,
  three typed by a thumb leaning about a quarter of a key right and an eighth low. Taps on their key went 167 of 178,
  166 of 172, 170 of 171 as the touch areas stepped toward the lean. A run's results drew the keys it missed in red
  with their shares, arrows to the keys hit in their place, and the touch areas it moved (twelve, fourteen, sixteen)
  in the trainer's colour beside those moved before it in grey; the first page, Your mistakes and Your profile drew
  theirs, and the runs' two lines stood under the speed bars. Dark theme only; not on a phone.
- The test on the emulator (2026-11-11, `emulator-5584`): the course in "Does it work" above, from the first run to a
  proven fix, the switch, and a run after it; before it, the same build installed over a trainer the released
  version had kept (five runs, sixteen keys given steps, Adaptive keys added), whose first page said "The first fix:
  Next run tests one" and whose next run was typed on fix 1 and read both ways (177 of 177 either way: that
  keyboard already listened where the taps land). Each page was looked at in the dark theme and the first page in
  the light one. Scripted taps only; not on a phone.
- `tool/trainer_sim.sh`: the tables above. `JAVA_OPTS="-Druns=16 -Dseeds=40 -Dthumbs=1000 -Dlean=0.3,0.1"
  tool/trainer_sim.sh`; `-Donly=tables`, `proof` or `layouts` prints one part.
- On the emulator (2026-11-10, `emulator-5584`, taps at real key positions): fifteen runs typed to their results (eleven stories, a practice run, one more story on the last build but one, and the two with strokes below); with
  the switch off two runs' keyboards were the same pixel for pixel but for the last key's backlight; with it on a run
  on Condensed keys was followed by one on Short, said on its page; left part-way, and with the app killed part-way,
  the next field had the Condensed keys again and the key height setting had never changed; the profile appeared after
  the fifth run; a practice run, applying the profile, taking a suggested height, a run refused with another
  keyboard in use, and forgetting were each done once. Two more runs had words that no tap spelled: "in" drawn as a
  stroke and "squeezed" taken from the row after six letters (49 of 49 words right, 172 of 172 taps on their key,
  "Words swiped 1", "Words from the row 1"); then "on" drawn and read right, "to" drawn as "it", taken back with
  backspace and drawn again, and "if" swapped in the row for "of" and back (46 of 46 words right, right first time
  44 of 46, one backspace, "Words swiped 3", autocorrect charged with nothing). Nothing here has run on a phone in a
  hand.

## Not built

- **A fix that is anything but touch areas.** A fix moves where the keys listen and nothing else. Autocorrect's
  costs, the touch correction every keyboard starts with, and key size are not fixes the trainer makes or tests.
- **Two halves of one run typed two ways.** A run is typed on the fix, and the keyboard as it is reads the same
  touches at the same moment. Typing half a story on each would put different words on each side, and the second
  reading already is the keyboard as it is (the numbers above).
- **A key height judged on accuracy alone.** The trials go by speed times accuracy, and say each height's accuracy
  beside it.
- **Other layouts to try.** Only key height is trialled. A narrower board toward one thumb, a board lifted off the
  screen's edge, a number row and a split are all settings the keyboard has; none is tried, because each is a choice
  about how the phone is held more than a thing a score can settle in a few runs.
- **A height of its own.** The trials move between the four heights the keyboard has, not to a height in between.
- **Stories in other languages**, and the trainer on a layout that is not Latin.
- **The trainer on iOS**: the iPhone's keyboard is another port.
- **Anything a real thumb has shown.** Every number here is a simulated thumb's or a scripted one's.
