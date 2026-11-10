# Anonymous typing reports

Matt, 2026-11-10: "allow anonymous reporting of swipe and text metrics and mistakes so we can improve our knowledge
over time". Everything [typing-accuracy.md](typing-accuracy.md) measures was typed by a simulated thumb; this is how
real typing gets to say what autocorrect and swipe typing get right and wrong.

**It is off unless the person turns it on**: Settings › Typing › Typing stats › Anonymous reports › "Share anonymous
typing reports". With it off nothing is counted for a report at all. Turned off again, what was waiting is deleted.

## What a report holds

Exactly what Settings shows under "See the report": the page prints the report as it would be sent, and that text is
the whole of it. `Reports.kt` builds it; `ReportTally` is what it holds between one sending and the next.

```json
{
  "v": 1, "app": "0.12.0", "from": "2026-11-10", "to": "2026-11-11",
  "board": {"layout": "qwerty", "languages": ["en"], "keyDp": 44, "rowAspect": 0.95, "condensed": true, "numberRow": false},
  "counts": {"words.typed": 412, "fix.made": 31, "fix.undone": 2, "mistakes.retyped": 9, "swipe.kept": 57, "swipe.picked": 4, "keys": 2301},
  "taps": {"bin": 0.25, "span": 1.5, "own": [144 counts], "slip": [144 counts]},
  "mistakes": [["retyped", "teh", "the", 3], ["undone", "form", "from", 1]],
  "strokes": [{"kind": "picked", "read": ["form", "from", "for"], "meant": "from", "path": [3.0, 1.0, 3.5, 0.0, 8.5, 0.0, 6.5, 2.0]}],
  "swipeMs": [0, 3, 9, 1, 0, 0, 0, 0]
}
```

- **`counts`**: how often things happened, by name. Never what was typed.

  | Name | What happened |
  | --- | --- |
  | `words.typed`, `words.swiped` | a word finished by tapping; by a stroke |
  | `words.completed`, `words.predicted` | a word taken from the row while typing it; a next word tapped |
  | `words.noTaps` | a typed word whose taps were not known (a hardware keyboard, the cursor moved into it) |
  | `keys`, `backspaces` | keys pressed, and backspaces among them |
  | `fix.made`, `fix.joined` | autocorrect changed a word; into two words |
  | `fix.undone`, `fix.undoneOwn` | a fix was undone; and the word it had changed was one no list has |
  | `fix.retyped` | a fix was typed over with another word |
  | `words.keptAfterFix` | a word typed again as it was where a fix had been taken out |
  | `mistakes.retyped`, `mistakes.picked` | a word put right by typing over it; by taking another from the row |
  | `mistakes.unlisted`, `mistakes.more` | such a mistake that is not reported as text (see below); one past the sixty a report holds |
  | `swipe.kept`, `swipe.picked` | a swiped word left as read; swapped for another reading |
  | `swipe.deleted`, `swipe.redrawn` | a swiped word deleted whole; and then drawn again as a word it had offered |
  | `swipe.phrases` | a stroke that was several words |
  | `strokes.more`, `taps.far` | a misread stroke past the twelve a report holds; a tap further than a key and a half from its key |

- **`taps`**: where taps land about the key that was meant, as two 12 × 12 grids of counts, a quarter of a key to the
  cell, a key and a half each way (across in key widths, down in rows). `own`: every tap of a word that stood as
  typed, measured from its own key. `slip`: the one tap autocorrect put right in a word it mended by a single letter,
  measured from the key of the letter it became. No letters, no order, no words. This is the thing a simulated thumb
  cannot say: how far real thumbs scatter, and whether by the key's height or by the millimetre.
- **`mistakes`**: up to 60 of `[how, typed, meant, times]`, common words only (below). `retyped`: typed, then typed again differently in the
  same place. `picked`: a word taken from the row that what was typed is a slip from. `undone`: a fix that was
  undone, where it is the word *typed* that was right and the third field is the fix that was not.
- **`strokes`**: up to 12 swipes with the word they were: every misread while there is room (`picked`, `redrawn`),
  and one in ten of those read right (`kept`). `path` is the stroke as x, y, x, y… in key widths across and rows down
  from the middle of the top row's first letter, at most 48 points: which keys the finger ran over, nothing of the
  screen. `read` are the first readings the stroke gave.
- **`swipeMs`**: how long strokes took to read, counted in eight bands (under 5, 10, 20, 40, 80, 160, 320 ms, more).
- **`board`**: the layout, its languages, a key's width in dp, a row's height as a share of that, and whether the
  keys are condensed and the number row is on.
- **`app`**, **`from`**, **`to`**: the app's version and the two days (UTC) the report covers.

## What it never holds

- **No account, no device or install id, no time of day.** Nothing in a report says whose it is, and nothing links
  one report to the next. The request itself says only that it is Ghost Keyboard, with no version.
- **No text but the commonest words of the app's own word lists** (`ReportRules`, held to it by `ReportsTest`):
  Zipf 4 and up (`Autocorrect.REPORTABLE`), about seven thousand words of English. The lists themselves go further
  (forty thousand words, with rarer names, places and the names of illnesses among them: kira, nguyen, herpes), and
  none of that is ever text in a report. A mistake is reported only where what was meant is a common word and what
  was typed is a common word or no word at all; an undone fix only where both words are common; a stroke only where
  every word of it is. A nickname, a rarer name, a term of someone's work, a word the keyboard was taught: counted
  as a number (`mistakes.unlisted`, `fix.undoneOwn`) and no more. Nothing with a digit or a symbol in it is ever
  text in a report.
- **The very commonest first names are common words** (john, sarah, mary are among the seven thousand, as they are
  in any text), so "jonh" put right to "john" can be in a report. It says that an unnamed someone typed a common
  name, and nothing else.
- **Nothing from where the keyboard does not learn**: a password field, anything while incognito (the plugin's chip,
  an app's profile, a field that asks keyboards not to learn), an address, a name, a search, a terminal, this app's
  own fields. The rule next-word learning has (`DevKeyboardService.reporting`).
- **No sentences.** Words are never in order: a report cannot be read back into what was written.

A common word can still be a private one ("cancer" and "divorce" are among the seven thousand). A report says an
unnamed someone mistyped it; it does not say who, when, or beside what. That is the whole of the risk, and it is why
the switch is off unless turned on and the report is shown before it goes.

## When it is sent, and where

- At most once a day, as the keyboard hides, once the report has seen 100 words; or when "Send it now" is tapped.
  A report the server does not take is kept and tried again no sooner than six hours later. One that has waited a
  fortnight unsent is let go.
- To `https://attack.fm/devkeyboard/report`, through `Net.open` like every connection the keyboard makes: the
  **Offline** switch stops it, Settings › Offline lists it under "What leaves your phone" with these same words
  (`NetPath.REPORT`), and `tool/net_check.sh` fails the day anything else opens a connection.
- It is kept in `typing_report.json` in the app's no-backup files until it goes: not in Android's backup.

## The server

`server/reports/collector.py`, with its own README. It reads each report against the same rules the keyboard wrote
it by, drops whatever it does not know, refuses whatever is malformed, and appends what passes as one line to a file
a day. It keeps no address, no header and no time of day, and the web server is told to log nothing for that path.
`server/reports/summarize.py` adds the lines up: how often fixes and first readings stood, how far taps land (by how
tall the keys are), and the commonest mistakes.

## Using what comes back

- **Tap scatter** sets `TapDecoder.SPATIAL` and answers whether square keys need their own costs (the open question
  in typing-accuracy.md).
- **`undone` pairs** are right words autocorrect spoiled: each one seen in several reports is a case for
  `tool/typing_bench.sh` to hold, or a word to leave alone.
- **`retyped` and `picked` pairs** seen in several reports are slips autocorrect missed or mended wrongly: the
  benchmark's misspelling list grows by them.
- **Strokes** are replayed through `SwipeDecoder` as the accuracy record's are, misreads first.

Never act on a pair one report has: one person's habit is theirs, and the keyboard already learns it on their phone
([typing-accuracy.md](typing-accuracy.md), "Learning from your corrections").

## Checks

- `android/app/src/test/.../ReportsTest.kt`: what the rules let in and keep out, the grids, the bounds, the file's
  round trip, and that the collector takes a report exactly as the keyboard writes it.
- `server/reports/test_collector.py`: the collector's rules, that a line holds nothing of who sent it, and its limits.
- `test/reports_settings_test.dart`: off by default, what the page promises, and that it shows the report itself.
- `tool/net_check.sh`: the report's path is in the ledger and nothing else connects.
