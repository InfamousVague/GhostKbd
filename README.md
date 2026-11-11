# Dev Keyboard

A developer-centric keyboard for Android. Stage 1: a faithful copy of Gboard's QWERTY keyboard.

## How it's built

| Part | Where | What |
| --- | --- | --- |
| The keyboard | `android/app/src/main/kotlin/.../DevKeyboardService.kt`, `KeyboardView.kt`, `Layouts.kt`, `KeyboardTheme.kt` | A native `InputMethodService` drawing one custom `View`. Native so it opens instantly and never waits on a Flutter engine. |
| Setup & settings | `lib/` | A Flutter app drawn with **Glacier** (`packages/glacier`, vendored from Ghost.md — see `packages/glacier/VENDORED.md`). |
| The bridge | `MainActivity.kt` ↔ `lib/settings_store.dart` | One `MethodChannel` (`dev_keyboard/settings`). Settings live in a native SharedPreferences file (`KeyboardPrefs.kt`) that the keyboard reads live, so changes show on the keyboard right away. |

## What it does (Gboard parity)

- QWERTY, `?123` and `=\<` pages, with Gboard's layout and light/dark colours
- Key preview bubble, long-press panels (digits on the top row, Gboard's symbol hints on the rest, accents, the period's punctuation panel, `( → < [ {` and friends)
- Shift: tap for one capital, double-tap or long-press for caps lock, auto-caps at sentence starts
- Backspace repeats while held; deletes a selection in one go
- Slide along the space bar to move the cursor, or hold it and then slide; with cursor control off, long-press space opens the keyboard picker
- Double-space period; enter key follows the field (search, go, send, next, done, newline)
- Number/phone fields open on `?123`; toolbar with settings and hide
- Settings: theme, key height, number row, symbol hints, auto-caps, double-space period, space-bar cursor, long-press delay, popup, vibrate, sound

## 0.9.0

- **Rewrite** (a Market plugin, `Rewrite.kt`): a Rewrite button in the row; Fix, Shorter, Formal, Casual, Friendlier or
  Translate, on the selection or the whole field, with the person's own AI key.
- **Typing stats** (`TypingStats.kt`, Settings › Typing › Typing stats): speed, accuracy, autocorrect's fixes, swiped
  words and the busiest keys, a day at a time. Counts only, on the phone, never in password fields or incognito.
- **Live preview** (`KeyboardPreview.kt`, `lib/settings/keyboard_preview.dart`): the Preview button at the foot of every
  settings page draws the keyboard's own view in the app, following each setting as it changes.
- **Cursor control**: slide left from backspace to delete word by word; slide up or down from the space bar to move
  by lines (sideways still moves by characters).
- **Per-app profiles** (`AppProfiles.kt`, Settings › Per-app profiles): the number row, autocorrect's own fixes,
  terminal keys, markdown buttons and incognito, forced on or off for one app.
- **Foldable layouts** (Settings › Look & feel › Layout): on a wide screen, split, full width, or a phone-sized board
  docked left, centre or right; on a phone, one-handed left or right. Tap the arrow on the empty side to move the keys.
- **Market**: two tabs, Installed (what is added, and the built-in plugins' switches) and Discover.

## 0.9.5

- **Undo and redo** (the `undo` plugin; `EditHistory.kt`, `FieldEditor.kt`, `ConnectionPort.kt`): the arrows in the row
  no longer just send Ctrl+Z. Every edit the keyboard makes goes through `FieldEditor`, which keeps a history of them
  for the field in hand (a word of typing, a run of backspaces, an autocorrect fix, a swiped word, a paste, an emoji,
  markdown, a snippet, a Ghostwriter or Rewrite answer, dictation: one step each) and plays it back through the
  InputConnection, the same in every app, with the cursor put back. Before anything is taken out it is read back and
  compared; if the text changed some other way the history is dropped and the app is asked to undo instead (its edit
  menu's Undo, then Ctrl+Z). In memory only, 100 steps and 200,000 characters at most, never in password fields. An
  arrow dims when there is nothing for it to do. A history is one field's: where an app only starts its input again
  (which is also how another input on the same web page or Flutter screen arrives) it is kept only if its newest step
  is found in the text. A new word typed straight after the last step waits on no read of the field, and a field that
  answers reads slowly is not recorded. The history has no Android in it and is checked off the device:
  `tool/undo-selfcheck.sh`, or `cd android && ./gradlew :app:testDebugUnitTest --tests '*FieldEditorTest*'`.

## Releases and auto-update

Installed copies update themselves from **https://ghostkbd.com/** (`apk.json` and `devkeyboard.apk` there, on Ghost.md's
box; copies up to 0.12.0 look under https://attack.fm/devkeyboard/, which the box serves from the same folder). On opening the app,
and from the keyboard twice a day, `Updater.kt` reads `apk.json`, downloads the APK, checks its size, SHA-256,
package, version and signing certificate, and installs it with Android's `PackageInstaller` — silently on Android 12+
once the app installed its own previous version, otherwise with Android's one-tap confirm. Settings › About shows
where it stands and has the **Install updates by itself** switch: off, nothing is installed by itself, but the keyboard
still asks the box twice a day whether there is a new version (for the Update chip above its keys). Only **Offline** stops the
asking. Debug builds never update.

## Languages and layouts

**Settings › Typing › Languages** turns languages on, up to six at once. Each brings its keys and its word list:
37 lists (Latin-script languages from Catalan to Vietnamese, Russian, Ukrainian, Bulgarian, Macedonian and Serbian in
Cyrillic, Greek, Hebrew, Arabic, Persian and Urdu), and Korean with a syllable composer but no word list, so it types
and nothing more. Chinese and Japanese (a conversion engine) and Hindi, Bengali and Tamil (a layered layout) are not
built; Settings says so. Being listed means a language can be typed and has a word list. Nothing has been measured
about how well any of them is corrected.

- `Languages.kt` is the catalogue: each language's script, layouts, word list and own long-press letters, and
  `LanguageState`, the languages that are on and the layout being typed on. The layouts that are on are the stops of
  the switch: the globe key beside space (tap for the next, hold for the list), a long-press on the space bar, and the
  space bar's name for the one in use. Languages on one script share their word lists whichever board they are on,
  so a message can mix English and French.
- `Layouts.kt` has the boards (`LayoutSpec`): QWERTY, QWERTZ, AZERTY, the Nordic, Spanish, Catalan and Turkish ones,
  five Cyrillic, Greek, Hebrew, Arabic, Persian, Urdu and Korean, after AOSP's. A board is as wide as its longest
  row; a script without capitals has no shift. A keyboard that had Spanish, French or German on before layouts keeps
  them on QWERTY (`KeyboardPrefs.pinLegacyLayouts`).
- `Autocorrect.kt` works on whichever board is in use: its neighbours for slips, its alphabet for mending, and the
  script's plain spelling (`Script.plain`: cafe finds café, καλημερα finds καλημέρα) for accents. Adaptive keys and
  the typing profile keep a model per layout. `Hangul.kt` builds Korean's syllable blocks.
- Outside the first six languages autocorrect is cautious, because 30,000 words are only the commonest forms of a
  language with many endings: a word the list lacks is changed on its own only for a near-certain slip (never by
  taking an ending off it, or when it is a listed word with an ending), and the other guesses are offered in the row.
  A listed plain spelling gives way to its accented word only on a board where the accent is a long-press away
  (Swedish "far" stays on the Swedish board). No reader of each language has checked the corrections.
- The word lists are files in the app (`assets/dict/<code>.tsv`, with `NOTICE.txt` for their licence), made from
  wordfreq by `tool/dictionaries.py` (`--check` confirms every file is what the script makes). Nothing is downloaded,
  so every language works with Offline on.
- `tool/lang_check.sh` (kotlinc and java, no emulator) checks every layout, each script's plain spelling, the Korean
  composer, the switch, every word list against its board, and autocorrect on QWERTY against what it said for the
  first six languages before layouts (`tool/lang_check/golden`), listing each difference by its reason.

## Next-word prediction

After a space (and at the start of a field or sentence) the row above the keys offers the three likeliest next
words; a tap types one with a space and offers the next three. The tools scroll in after the words, the first one
peeking in at the row's end: undo and redo, the clipboard and GIFs first, and on a row wide enough for them
and three words (a foldable's inner screen, a tablet) pinned at its start instead. Emoji is a key of its own, grey,
between the { } key and the space bar (it is in the row instead on a board that has both the globe and the mic key,
where the bar has no width left to give). There is no settings cog in the
row: hold the { } key or the comma. While a word is typed, its completions are ranked by
the word before too. **Settings › Typing › Autocorrect › Next word** turns it off, turns its learning off, and
forgets what it learned. On for everyone who updates. [docs/next-word.md](docs/next-word.md) has the model, the
formats, the sources and the numbers.

- `NextWordModel.kt` (no Android in it): the reading of text into words and contexts (`NextWordText`), a starter
  table (`NgramTable`), the person's own counts with their fading and bounds (`PersonalPairs`), and the blend
  (`Scorer`). `NextWords.kt` holds them for the app: tables read from the assets and the learned file read, saved
  and forgotten on one background thread; the typing thread never waits on it.
- Starter tables are files in the app (`assets/ngram/<code>.tsv`), counted from Tatoeba's sentences (CC BY 2.0 FR)
  by `tool/next_words.py`, for 34 of the 37 word lists. Hungarian, Slovenian and Urdu have none and predict from the
  person's own typing only; Korean has no word list and no prediction. Nothing is downloaded by the app.
- What is learned is counted on the phone, never in password fields, while incognito, or in fields that ask for no
  learning, and kept out of Android's backup. A typing profile carries it (`words.next`).
- `tool/next_word_check.sh` (kotlinc and java, no emulator) checks the tokenizer against fixtures the generator
  shares, the blend, the bounds, every table against its word list, and lookup time.

## Typing accuracy (0.11.0)

Autocorrect reads a word by where its taps landed, not only by which keys they hit: a tap on the line between two
keys is either of them, two words run together are parted, and a word no list has is left alone when it was typed
cleanly. Swipe typing reads its first word in context, takes strokes in the order they were drawn, and holds a
stroke against a word's lines in their order. Both learn from what the person puts right (**Settings › Typing ›
Autocorrect › Your corrections**), and with Mockingbird added the next word goes by its samples too.
[docs/typing-accuracy.md](docs/typing-accuracy.md) has what each part goes by, what is learned and what never is,
and the numbers.

- `TapDecoder.kt` (no Android in it): the word list as a trie, read against the taps. `Autocorrect.byTaps` weighs
  its readings; with no taps autocorrect is what it was.
- `MistakeMemory.kt` (no Android in it) and `Mistakes.kt`: what a word was put right to and which swipe reading was
  chosen, kept in `mistakes.tsv` on the phone, out of Android's backup, never from a password field or incognito.
- `tool/typing_bench.sh` types held-out sentences through the keyboard's own code with a simulated thumb, to compare
  one version with another; `tool/accuracy-replay.sh` does the same with a record of real typing.

## Anonymous typing reports (off by default)

Settings › Typing › Typing stats › **Share anonymous typing reports**: off unless the person turns it on. On, the
keyboard sends ghostkbd.com a report at most once a day: counts of what happened, where taps land on their keys as a grid
of counts, and common words that were mistyped or misread with what was meant: only ever the few thousand commonest
words of its own word lists. No account, no id, no time of day, never a word of the person's own or a rarer one,
nothing from where the keyboard does not learn; and Settings shows the report exactly as it would go. [docs/reports.md](docs/reports.md) has every field.

- `Reports.kt`: the tally (`ReportTally`), the rules for what may go in it (`ReportRules`), and the keeping and
  sending (`Reports`, through `Net` like every connection).
- `server/reports/`: the collector that takes them in (standard-library Python, behind the web server) and
  `summarize.py`, which adds them up.

## Offline, and the one door to the network

**Settings › Offline** is one switch for every connection the app can make: with it on, nothing is asked of any
server (AI, GitHub, Ghost.md online, GIPHY, speech-model downloads, the update check and download), and anything
under way is cut. What needs no connection carries on. The row above the keys says "· Offline" on the chips that
would have connected.

- `Net.kt` is the only file that opens a connection. `Net.open` refuses while Offline is on, refuses anything but
  https, and refuses a server its path does not list; `Net.get` follows a download's redirects by hand, each hop
  checked the same way. API calls treat a redirect as an error, so a key is never passed on to another server. The
  connection it hands out asks the switch again each time it would connect, send or read, so a request that was
  being made ready as Offline was switched on never leaves.
- Android's own backup is off for the app (`allowBackup`, and rule files for the Android versions that read those):
  its settings, keys and what it has learned are not copied to a Google account or to a new phone, with Offline on
  or off. A typing profile is how what was learned moves.
- `NetLedger.kt` is the list it checks: every reason to connect (`NetPath`), its servers, what starts it and what is
  sent. Settings › Offline (and its "What leaves your phone") and the Market's "It talks to…" are drawn from the same
  list, through the channel's `netState`.
- A new feature that needs the network adds a `NetPath` and connects through `Net`. `tool/net_check.sh` (kotlinc and
  java, no emulator) tries the ledger's rule on real addresses and fails if any other source opens a connection,
  names a server the ledger does not list, or the settings app gains a network library; `flutter test` covers the
  Settings side.

The app keeps Android's internet permission, and other apps keep their own connections: the switch is about Ghost
Keyboard's own.

To publish a release (from the Mac that has Flutter, the Android SDK and the Ghost.md checkout):

```sh
node tool/release.mjs                 # build, check, publish; version code = live + 1
node tool/release.mjs --version 0.2.0 # with a new version name
node tool/release.mjs --no-deploy     # build and check only, into build/release
```

It uses **Ghost.md's box credentials as they are**: `AFM_DEPLOY_*` from Ghost.md's `tauri/.env`, through Ghost.md's
own `box.mjs` (one multiplexed login). It finds the Ghost.md checkout beside this repo or in the usual places
(`--glyph <path>` or `GLYPH_ROOT` to say). The release key is made on first run in `~/.config/devkeyboard/`
and its certificate pinned there — **back that folder up**: without it no installed copy can take another update.

## Build and run

Needs Flutter 3.41+ (Dart 3.11) and the Android SDK — the same toolchain as Ghost.md.

```sh
flutter pub get
flutter run            # or: flutter build apk
```

Then open **Dev Keyboard**, tap **Turn on**, enable it in the system list, come back and tap **Switch**.
