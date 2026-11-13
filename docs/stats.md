# The Stats page

Matt, 2026-11-11: "make a stats page that shows the missed keys and key shifting as well as other stats like type
speed most misspelled words and other key information use iconography and colors".

Settings › Stats, in the top card under Setup and the trainer. Android only. One page of pictures from everyday
typing, each part in its own colour with its own icon (`lib/stats/stats_page.dart`):

| Part | Colour | What it shows | Counted by |
| --- | --- | --- | --- |
| Two dials | mint, green | speed (words a minute) and accuracy, today's once today has any, else the last 30 days' | `TypingStats` |
| Six tiles | blue, amber, orange, purple, cyan, rose | words and time today, days in a row, autocorrect's fixes (and how many were kept), words swiped (and how many were right first time), slips taken back with backspace | `TypingStats` |
| Last 7 days | blue bars, amber line | words typed each day, the speed drawn over them, the fastest and the fullest day kept | `TypingStats` |
| Missed keys | red | the keyboard, each key tinted by how often it is missed, an arrow to the key hit in its place, and the commonest slips as chips ("r for e 11×") | `KeyTallies.slip` |
| Key shifting | indigo | the keyboard, a ring where the taps on each key land and an arrow for how far Adaptive keys has that key's touch area off its middle | `KeyTallies.landed`, `AdaptiveKeys.offset` |
| Most misspelled words | magenta | the words put right most often: as typed, as meant, how often | `Mistakes.most` |
| Busiest keys | lime | the eight letters pressed most, each with its share | `TypingStats` |

The two pictures of the keyboard are the trainer's (`lib/trainer/trainer_board.dart`, `TrainerMaps.board`), fed from
everyday typing instead of its runs (`BoardFigure(everyday: true)`; `MainActivity.statsPage`). The trainer's own
runs are not counted here, as they never were in typing stats.

The counting's settings stay where they were, Settings › Typing › Typing stats (the accuracy record, the anonymous
reports), with a row that opens this page. The switch (Count my typing) and Reset are on both.

## What is counted for the pictures

New with this page, for each layout typed on, kept until the stats are reset (`KeyTallies.kt`, in the typing stats'
own file):

- **A slip between two keys**: "pressed>meant" and how often. A slip is a letter taken back with backspace and typed
  again as its neighbour within three seconds, or a letter autocorrect swapped for its neighbour; never a touch in
  the middle of its own key (that is a change of mind). The same rule Adaptive keys learns by, counted whether or
  not that plugin is on. 300 pairs at most for a layout.
- **Where the taps that stood land**: for each letter key, the sum of how far across and down from the key's middle
  its taps landed (in fractions of the key) and how many: the mean of the last 2,000 or so.

Which key for which, and where the thumb falls. **Never a letter in its order, never a word**: the promise typing
stats made is kept (`KeyTalliesTest` holds what is written to it). Counted only where typing stats count: the
switch on, never in a password field, never while incognito.

**The one card with words in it** is Most misspelled words. Those are not counted by typing stats: they are what the
keyboard already remembers from the person's own corrections (Settings › Typing › Autocorrect › Your corrections,
`Mistakes.kt`), read for this page and shown to nobody else. With "Learn from my corrections" off the card says so
and lists nothing. Resetting the stats does not forget them; their own Forget does.

Nothing on the page is sent anywhere (`tool/net_check.sh`).

## What a number means

- **Speed**: characters typed over five, a minute, over the time spent typing (a pause of five seconds or more is
  not typing). Nothing until there are ten seconds to measure.
- **Accuracy**: how much of what was typed stood. A backspace straight after a key, or an autocorrect fix, is a slip.
  A capital autocorrect gives is a fix here (I always was; a name's is since names got theirs, docs/versus.md).
- **In a row**: days with typing up to today, or up to yesterday before today's first word.
- **Missed**: of the times a key was meant (its taps that stood and the slips meant for it), the share that landed
  on a neighbour. A key is tinted once it has ten taps; a slip is drawn as an arrow once it has been seen twice.
- **Shifted**: Adaptive keys' own nudge, at most 30% of a key, shown only where it is large enough to be in use
  (2%). With the plugin off nothing has shifted, and the picture is the rings alone.

## Checks

- `KeyTalliesTest` (the counters, their file, the most-corrected words, the picture's data),
  `test/stats_page_test.dart` (the page with nothing counted and with a full day, at a phone's width; the wording;
  search; the promise at its foot).
- On the emulator, 2026-11-11: the page with the phone's own month of counts, then a paragraph tapped into an
  ordinary field by a scripted thumb that lands low and right: the dials and tiles moved, two keys showed as
  missed with the key hit in their place, the rings filled in, and Adaptive keys' nineteen shifted keys were drawn.
- Not checked: on a phone in a hand; a light theme on a device (the widget test draws dark); a layout other than
  QWERTY; a right-to-left layout.
