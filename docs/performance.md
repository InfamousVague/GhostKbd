# Performance

How fast the keyboard is, how that is measured, and what the pass of 2026-11-12 found and changed (Matt: "do a
performance pass on the keyboard to make sure everything is as fast as possible"). Everything below was measured on
an emulator with a debug build; none of it has been measured on a phone or with a release build. The emulator and a
debug build are both several times slower than a phone, so the numbers are for holding one build against another,
not for saying how long anything takes in the hand.

## How it is measured

**The keyboard's own timers** (`Perf.kt`). A debug build times the pieces of a key press's work while the file
`files/debug-perf` is in the app's folder, and writes a line for each to the log (tag `GhostPerf`) when the keys are
hidden: how often it ran, the time in all, the middle one, the slow one (19 in 20 were quicker) and the longest.
A release build never times anything.

```sh
adb shell run-as com.infamousvague.devkeyboard touch files/debug-perf
# type something, hide the keyboard
adb logcat -d -s GhostPerf
```

| Name | What |
| --- | --- |
| `touch.down`, `touch.up`, `touch.move` | a finger's touch, start to end: on the way up that is the key typed, autocorrect and all |
| `selection` | the app's report of where the cursor is, with the check for a capital |
| `caps` | that check alone |
| `tick` | the suggestion row's refresh, on the main thread |
| `tick.read` | its reading of the field (off the main thread since 0.16.2) |
| `tick.row`, `row.set` | working out what the row shows, and putting it there |
| `ask.before`, `ask.after`, `ask.selected`, `ask.caps`, `ask.around` | every question that did go to the app on the main thread, by what was asked: each is a wait |
| `draw` | one frame of the keys |
| `show`, `settings` | the keys being shown, and the settings read |

`mirror strikes` on the last line is how often a reading of the field was not what the keyboard had guessed (see
below); it should be 0 in an ordinary text field.

**Around it**: the processor time of the keyboard's process and of each of its threads (`/proc/<pid>/stat`), and
Android's own count of frames and how long they took (`dumpsys gfxinfo com.infamousvague.devkeyboard`). The typing
is done in another app's field (the Settings app's search), so that the process holds only the keyboard and every
question to the field is a real call to another process; in the keyboard's own Try field both are in one process
and a question costs nothing, which hides the very thing that matters.

A profiler (`simpleperf record --app ... -e cpu-clock -g`) says where the processor's time goes, and found the
drawing. It cannot find the waits: a thread waiting for another app's answer uses no processor.

## What was found

A sentence of 75 keys was tapped at about five a second, the same taps each time, two runs of each build.

1. **The keyboard stood still waiting for the app.** Reading the field is a call that stops the keyboard's main
   thread until the app's own main thread answers, and an app is at its busiest just after a letter is typed into
   it. Every letter asked whether a capital was due (about 15 ms on the emulator), then read the words for the row
   (10 ms) and asked two or three more things for the row (17 ms); every space asked four or five times before it
   could correct the word (64 ms). In all, 4.4 of the 17 seconds. A key pressed in that time waits its turn: its
   click, its popup and its letter.
2. **Every frame measured every key.** With the backlight on, as it is to start with, the board is drawn about
   thirty times a second for 1.2 s after each key (495 frames for the 75 keys), and every frame measured thirty
   labels again, made a new colour filter for every lit key, and built each key's name and rectangles afresh.
3. **Small things, many times**: the list of added plugins was split and sorted into a set at every question (dozens
   a key); an icon's lookup made a pair each time.

## What changed

**The keyboard's own copy of the text** (`FieldMirror.kt`, `MirrorConnection.kt`). One reading of the field is kept,
and the keyboard's own typing is done to it: what the keys commit goes on the end of what stands before the cursor,
what they delete comes off. A question the copy can answer is answered from it. It is a guess at what the app holds,
so it is used on strict terms:

- It starts from a real reading that says where the cursor is (Android 12's `getSurroundingText`), which the row's
  refresh makes a moment after every key, and each of those is held against what was guessed. Twice wrong in a
  field, and that field is never guessed at again.
- The app must be seen to report the cursor where the keys' typing put it before any guess is used; any other
  report (a tap in the text, a selection, the app's own edit) drops the copy, and the next question goes to the app.
- Anything the keyboard does to the field besides plain typing and deleting at the cursor drops it.
- It goes stale 2.5 s after the last key, so whatever happened while the keys were at rest is read afresh.
- Whether a capital is due is worked out by the same sum Android's own text field answers with, and the app's own
  answer is taken the first three times it matters in each field; an app that answers differently is always asked.

Before Android 12, and in an app that does not answer that one reading, everything is asked as it always was.

**The row's reading off the main thread** (`DevKeyboardService.tick`). The one reading left for each key is made on
a thread of its own. Its answer is used only if nothing was typed or moved while it was on its way; otherwise the
field is read again. What the row then asks (what follows the cursor, what is selected) is answered from that same
reading.

**Drawing** (`KeyboardView`). Each key's label, the size it fits at and its baseline are kept until the keys change.
The backlight is drawn in 64 steps of strength; a frame is drawn only when some key's step or colour has changed,
and a filter is kept for each. Keys under the listening panel are not drawn. Presses, swipes and the mic's level
ask for a frame without having the labels measured again.

## The numbers

Emulator (Android 16, arm64, a Mac that was also doing other things), debug build, 75 keys, two runs each.

| | Before | After |
| --- | --- | --- |
| Main thread: a key's follow-up work (key up, the cursor report, the row) | 4.39 s, 4.43 s | 0.31 s, 0.28 s |
| the same for one key | 59 ms | 4 ms |
| A key going up, the slow one in twenty | 64 ms, 63 ms | 1.6 ms, 1.9 ms |
| The cursor report with its capital check, the middle one | 14.6 ms, 14.7 ms | 0.13 ms, 0.10 ms |
| The row's refresh on the main thread, the middle one | 27.9 ms, 29.8 ms | 2.0 ms, 1.8 ms |
| One frame of the keys on the main thread, the middle one | 0.62 ms, 0.53 ms | 0.27 ms, 0.25 ms |
| The slowest frame in a hundred (Android's count) | 65 ms, 65 ms | 20 ms, 19 ms |
| Processor time, main thread | 980 ms, 850 ms | 690 ms, 650 ms |
| Processor time, drawing thread | 950 ms, 910 ms | 1020 ms, 980 ms |
| Frames drawn | 496, 497 | 492, 498 |

The same 25 steps (autocorrect at a space, backspace after a correction, the double-space full stop, typing after a
tap moved the cursor into the text, a held backspace, a full stop) left the same text after every step on both
builds, but for the held backspace: in the same 0.9 s it took one or two more characters, each of its steps no
longer waiting on the app.

## What is left, and what was not done

- **The backlight is an animation, and that is most of what the processor still does while someone types.** The
  drawing thread's time did not fall: the same thirty frames a second are drawn after each key, and on the emulator
  each costs about 2 ms there whatever is in it. A frame that only changes a few legends could be made cheaper by
  keeping the keys at rest as one picture and drawing the lit keys over it; that was not built, because what it
  saves cannot be told on an emulator (whose cost for a frame is mostly the emulator's own).
- **The undo history's own check of the field** (`FieldEditor.audit`) is still a question to the app on the main
  thread, once a word, 0.3 s after the word starts: 16 of the 24 waits left in a run of 16 words (`ask.around`).
- **The capital check as a field is entered**, and the first three times it matters in that field: the other 8
  (`ask.caps`).
- Not measured: a phone, a release build, swipe typing, dictation, the emoji and GIF panels, how long the keyboard
  takes to appear from cold.
