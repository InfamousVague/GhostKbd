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
  arrow is in the row only while it has something to do (since 0.16.0; before, it dimmed). A history is one field's: where an app only starts its input again
  (which is also how another input on the same web page or Flutter screen arrives) it is kept only if its newest step
  is found in the text. A new word typed straight after the last step waits on no read of the field, and a field that
  answers reads slowly is not recorded. The history has no Android in it and is checked off the device:
  `tool/undo-selfcheck.sh`, or `cd android && ./gradlew :app:testDebugUnitTest --tests '*FieldEditorTest*'`.

## 0.15.0

- **One-time codes from texts** (`OtpCodes.kt`, `SmsCodes.kt`; Settings › Row tools, off unless turned on;
  [docs/sms-codes.md](docs/sms-codes.md)): a sign-in code that arrives by text message is a chip at the start of the
  row above the keys and the first thing in the clipboard's list, a tap to type it. The chip is a code's own
  (`CodeChip.kt`), not the clipboard's: blue, a text message on it, the digits in their groups, and an outline that
  runs down with the time it has left. The text is read once, on the
  phone, and let go; only the code is held, in memory, for ten minutes or until it is used. The app asks Android for
  `RECEIVE_SMS` only, when the switch is turned on, and its receiver is disabled in Android itself while the switch
  is off. `tool/otp_check.sh` runs the finder against its table of texts.
- **Stats** (`lib/stats/stats_page.dart`, `KeyTallies.kt`; Settings › Stats; [docs/stats.md](docs/stats.md)): everyday
  typing in pictures. Speed and accuracy as dials, six tiles, the week as bars with the speed over them, the keyboard
  twice (the keys missed with the key hit in their place; where the taps land and how far Adaptive keys has shifted
  a touch area), the words put right most often, and the busiest keys. Typing stats now also count which key was
  hit for which and where taps land: counts only, never a word.
- **The website's look & feel key** (`site/js/chunks/look.js`, `site/js/kbd.js`): a whole keyboard on a small screen,
  stepping through the key heights and the foldable and one-handed layouts with the themes.
- A chip tapped away in the row as Undo lit up could stay drawn (`PluginRow.setItems`).

## 0.16.0

- **Agent keys** (the `agent` plugin, `AgentKeys.kt`; Settings › Row tools, off until switched on;
  [docs/agent-keys.md](docs/agent-keys.md)): the keys a coding agent in a terminal is driven with, as chips in the
  row: Shift+Tab, Esc twice, Ctrl+C, a new line that does not send the prompt (Ctrl+J), `/`, `@`, `!` and Ctrl+R, in
  terminal apps; a new line, a code block, `/` and `@` in AI chat apps (a new kind of app, `AppKind.ASSISTANT`). A
  per-app profile can turn them on or off for one app.
- **Connectors** (the `connectors` plugin, `Connectors.kt`, `McpClient.kt`, `AiTools.kt`; a Market add-on;
  [docs/connectors.md](docs/connectors.md)): add a Model Context Protocol server by its https address, with a token
  where it wants one, and switch on the tools Ghostwriter may call while it writes (the ones a server marks read-only
  start on, every other off). The phone asks the connector itself; a token goes only to its own server and the AI's
  key never to a connector (`NetPath.CONNECTOR_TOOLS`, `CONNECTOR_CALL`: the servers the person added, and no other).
  Signing in through a browser is not built yet.
- **Dictation without a time limit** (`Dictation.kt`, `SpeechCuts.kt`, `DictationWords.kt`;
  [docs/dictation.md](docs/dictation.md)): a take is cut into pieces at its pauses and written down while it goes on,
  so the words arrive as you talk (it was 60 seconds, and words only after). Your own words for the speech model
  (Settings › Dictation › Your words), and after a take a Tidy chip that has your AI take out the ums, with As a
  prompt beside it where the Prompt enhancer is added. Speech never leaves the phone; Tidy sends the words, only when
  tapped (`NetPath.TIDY`).
- **Prompt enhancer** (the `enhance` plugin, `Enhance.kt`; a Market add-on; [docs/prompt-enhancer.md](docs/prompt-enhancer.md)):
  an Enhance chip in the row. What was typed for an AI is rewritten as a clearer prompt, in place: what is wanted
  first, every particular kept, nothing added. Tight or thorough, with a few words about you and your work if you
  give them. Its own ledger path (`NetPath.ENHANCE`): the person's own AI provider and nowhere else.
- **Text shortcuts** (the `shortcuts` plugin, `Shortcuts.kt`; Settings › Typing, off until switched on;
  [docs/text-shortcuts.md](docs/text-shortcuts.md)): a few letters that stand for a phrase. `omw` and a space types
  "On my way!"; the row offers the phrase first; backspace straight after puts the letters back. A phrase can hold
  `{date}`, `{time}`, `{clipboard}` and `\n`. Kept on the phone, never used in a password field.
- **Clipboard pins** (`ClipList.kt`, `ClipHistory.kt`; [docs/clipboard-pins.md](docs/clipboard-pins.md)): hold a clip
  in the clipboard's list to pin it. A pinned clip leads the list, is not one of the 25 copies, and stays when the
  history is cleared; Settings › Row tools lists the pins, removes them and takes one written by hand.
- **The row** (`PluginRow.WordRoom`, `TapGuard`, `Quill`): about three suggested words, in the row's middle, with
  room kept at its end for the first of the tools; Undo and Redo are there only while each has something to do, and
  the row keeps its shape as they come and go (a tap on an arrow leaves the row as it was, and what takes the place
  of an arrow that its own tap took away takes no tap for 0.6 seconds); and while the AI writes or rewrites, a
  feather quill scribbles in the row's busy chip.
- **The emoji button and the mic key** moved: emoji is in the row beside the clipboard again, and with dictation on
  the mic key stands where the full stop was (`Layouts.withMic`).

## 0.16.1

- **The row**: the clipboard, emoji, and undo and redo are at its start, before the suggested words, on a phone too
  (`PluginRow.pins`, `DevKeyboardService.everyday`): in 0.16.0 they followed the words there, with one of them in
  view at the row's end. The clipboard and emoji are first and never move; the arrows come and go after them. The words share what is left, about three of them, two where three would be too narrow to
  read (`WordRoom.shown`); GIFs lead the tools that follow the words, so on a phone the GIF button is the one in
  view at the row's end.

## 0.16.2

- **The emoji key and the { } button changed places** (`Layouts.withEmoji`, `KeyType.EMOJI`,
  `DevKeyboardService.everyday`): emoji is a key between ?123 and the space bar, where the { } key was, with no
  pill behind its icon; the { } key is a button in the row above the keys, second after the clipboard, lit while
  the code keys are showing. A hold on either opens Settings, as a hold on the { } key did.
- **Dictation shows its words as you speak** (`LiveWords.kt`, `Dictation.kt`, `whisper_jni.cpp`'s `nativeDraft`;
  Settings › Dictation › Show words as I speak, on unless switched off; [docs/dictation.md](docs/dictation.md)): the
  phrase being spoken is written down again about once a second and shown in the field as a draft, a second or so
  behind the voice, and the phrase as written down at its pause takes the draft's place. Until now the words came
  only at a pause of 0.6 seconds, which for a message spoken straight through meant all at the end. A phrase is one
  undo step however often its draft was revised (`FieldEditor.replaceBefore`). Debug builds compile whisper for
  speed too, and can be given a recording in the microphone's place (`DebugSpeech.kt`).
- **A key press no longer waits on the app** (`FieldMirror.kt`, `MirrorConnection.kt`, `DevKeyboardService.tick`;
  [docs/performance.md](docs/performance.md)): asking the app for its text is the one slow thing a keyboard does,
  and every letter asked two or three times and every space four or more, the keyboard standing still each time
  until the app's own main thread answered. Now the keyboard keeps its own copy of the text around the cursor, does
  its own typing to it, and answers from it; the copy is checked against the app after every key, is dropped at any
  cursor move or edit that was not the keys' own, and a field whose app rewrites what is typed is asked as before.
  The suggestion row's one reading is made off the main thread. On the emulator, typing 75 keys in another app's
  field, the keyboard's main thread spent 4.4 s of 17 on a key's follow-up work before and 0.3 s after. A held
  backspace keeps nearer its 50 ms step for the same reason.
- **Less drawing** (`KeyboardView`): a key's label is measured once, not for every frame of the backlight's fade
  (sixty a second for 1.2 s after each key); a frame that would look the same as the last is not drawn; a fading
  key shares its colour filters where it made a new one every frame; and the keys under the listening panel are
  not drawn at all. A frame takes the main thread under half the time it did.
- **The keyboard times itself** (`Perf.kt`, debug builds only, while `files/debug-perf` is there): each piece of a
  key press's work, how often and how long, written to the log as the keys hide.

## 0.16.3

- **A Comma key setting** (Settings › Look & feel › Keys › Comma key, off unless turned on; `Layouts.withComma`,
  `KeyboardPrefs.commaKey`): with it on, the letters end with ?123, the comma, the emoji key, the space bar, the full
  stop (or the mic) and enter, as Gboard's do. The comma stands where the { } key was until 0.16.2 moved that key to
  a button in the row above the keys, and the emoji key moves over beside it, taking a key's width from the space bar
  (five keys to four). With it off the letters are as 0.16.2 has them: the emoji key next to ?123, the bar at its
  whole width, and a comma a hold on the full stop.
- **The emoji key on the other pages** (`Layouts.withEmoji`): ?123, =\< and the code keys have it next to their
  comma, a key's width off their space bar. In 0.16.2 those pages had no way to emoji but back through the letters.
- With no row to hold the { } button (the row hidden, or nothing in it) the { } key keeps its place and the emoji key
  stands beside it, as from 0.12.1 to 0.15.0.

## 0.16.4

- **Rewrite on this phone** (`LocalAi.kt`, `LocalModels.kt`, `android/llm`; Settings › AI › Rewrite › On this phone;
  [docs/local-ai.md](docs/local-ai.md)): Rewrite and its Fix can run on a language model on the phone itself, with
  no key, no connection and nothing of what is written leaving the phone. Two models are offered, Qwen3.5 2B (1.3 GB)
  and 4B (2.7 GB), fetched only when tapped and held to their size and SHA-256; any other GGUF model on Hugging Face
  can be added by its link (`ModelLinks.kt`). A download that breaks off takes itself up again from where it
  stopped. Run on the emulator with a small model added by its link; not yet on a phone, where neither the time a
  rewrite takes nor the two offered models have been tried.
- **The app is 1.35 MB larger for it, not 4.3**: the model's library is 4.3 MB, and 3.0 MB of speech library for
  32-bit ARM and x86_64, which the app cannot run on and every APK up to 0.16.3 carried, is gone.
- **One-time codes from email** (`MailCodes.kt`; Settings › Row tools, off unless turned on;
  [docs/mail-codes.md](docs/mail-codes.md)): a sign-in code that arrives in Gmail, or another mail app, is a chip at
  the start of the row as a code from a text is, with an envelope on it. It is read from the mail app's own
  notification, on the phone, once Android's notification access has been given: there is no account to connect and
  nothing leaves the phone. Not by signing in to Gmail, which Google's rules for its Gmail access do not allow a
  mobile keyboard. From Android 15, Android hides most code-bearing notifications from an app like this one until the
  phone's owner lets them through (a command from a computer, or Android's Enhanced notifications turned off); Settings
  says so, and says when Android has hidden one. Seen on the emulator; not with a real mail in a real Gmail.
- **The keys sit lower where the phone's own buttons are off** (`SystemStrip.kt`;
  [docs/system-strip.md](docs/system-strip.md)): Space under the keys › Auto still keeps 48dp for Android's hide and
  switch-keyboard buttons, but only the gesture bar's room where a Galaxy's own settings say both are switched off.
  The keyboard also counts the room Android 14 and later give those buttons (a caption bar inset it used not to
  read), and Settings says what it found under the keys. Not seen on a Galaxy, and one of the settings' names is a
  guess; where anything cannot be read the keys stay where they were.

## Rewrite on this phone (since 0.16.4)

Rewrite and its Fix on a language model that runs on the phone: Settings › AI › Rewrite › On this phone, Ghost.md's
Qwen3.5 2B and 4B files, a process of its own for the model, worked examples in what the model is shown, and a check
on what it writes before that replaces anything. `LocalAi.kt`, `LocalModels.kt`, `LocalRewrite.kt`, `LlmService.kt`,
`android/llm` (llama.cpp v0.9.0) and `tool/local_ai_try`. Any other GGUF model on Hugging Face can be added by its
link (Settings › AI › Rewrite › Another model; `ModelLinks.kt`): Hugging Face is asked for the file's size and SHA-256
and the download is held to them. The library is part of the Android build since 2026-11-12 (Matt: "build in the llm
as long as it's not too large"): 4.3 MB in the APK, against 3.0 MB of speech library for other processors that came
out of it, so the app grows from 39.9 MB to about 41.3. [docs/local-ai.md](docs/local-ai.md) has the whole account,
its size included.

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
words; a tap types one with a space and offers the next three. The clipboard, emoji, and undo and redo (while each has
something to do) stand at the row's start, before the words, on every row but one made very narrow for one
hand (since 0.16.1; in 0.16.0 only on a wide row). Whenever the row shows words (next words, a word's completions, a
swipe's other readings) it shows about three, sharing what those tools leave (in the row's middle where the row is
wide enough), two where three would be too narrow to read; the other tools follow the words, GIFs first, with the
first of them whole in view at the row's end (`PluginRow.WordRoom`). Whether the tools are pinned is asked as if
both arrows were there, so the row keeps its shape as they come and go. A tap on an arrow keeps the row as it was,
words and all, so the arrow stays under the thumb; and whatever takes the place of an arrow that its own tap took away (Redo, after the last Undo)
takes no tap for the next 0.6 seconds (`TapGuard`). A chip before the words (a clip copied a moment ago, "Incognito
on") takes its room out of theirs. Emoji is a key on the board, next to ?123 where the { } key was, drawn as
the mic key is, its icon alone (`Layouts.withEmoji`); with Settings' Comma key on, the comma has that place and the
emoji key stands beside it, a key's width off the space bar (`Layouts.withComma`); the ?123 pages have the emoji key
next to their comma; and the { } key is a button in the row, where the emoji button was, lit while the code keys are
showing (`DevKeyboardService.everyday`). With no row above the keys (it is hidden, or
nothing is in it) the { } key keeps its place next to ?123, and the emoji key stands beside it. With dictation on,
the mic key stands where the full stop was, next to enter, on the letters page: hold it for the full stop and its
punctuation, or type two spaces (`Layouts.withMic`); in a password, an e-mail address or a web address the full stop
keeps its key. There is no settings cog in the
row: hold the emoji key, the { } button or the comma. While a word is typed, its completions are ranked by
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

## The typing trainer

**Settings › Trainer** (Android; the row under Setup, at the top of Settings): a story is shown, a part at a time, and
typed with the keyboard. A run is timed and scored against what was meant: words a minute, the share of taps that hit
their key, the words missed, backspaces, what autocorrect mended and what it spoiled, the keys and letter pairs that
go wrong. The first run is typed on the keyboard as it is. Each run after it is typed on a new **fix**, every key's
touch area a small step nearer to where the taps meant for it land, and is an A/B test of that fix: the keyboard
reads every tap twice, as the fix reads it and as the keyboard as it is would have, so the two are compared on the
same taps. A fix is worn only by the run that tests it; switching everyday typing to one is offered only once it is
**proven** better (three runs or more, a lead chance would give less than once in a hundred tries, and one that
holds from run to run), and is the person's own tap. With **Try different layouts** switched on (it is off unless
turned on, and read as a run starts) some runs are typed on shorter or taller keys, in pairs with the person's own,
and a height is suggested only once it too is proven; the setting itself is never touched unless the person takes
the suggestion. After five runs there is a profile (where the taps land, speed, mistakes, the test as it stands, the
height the runs proved), and a practice run made of the person's own mistakes. Its pages draw the keyboard simply,
twice: tinted by where the mistakes happen, and with a ring where each key's taps land and an arrow for where the fix
has its touch area; and the runs that tested a fix as two lines, with it and without. A story is not the person's
writing: a run teaches nothing to next-word prediction or Mockingbird and counts nothing for stats or reports.
[docs/trainer.md](docs/trainer.md) has all of it.

- `TrainerText.kt`, `TrainerRun.kt`, `TrainerAims.kt`, `TrainerFix.kt`, `LayoutTrials.kt`, `TrainerBook.kt`,
  `TrainerStories.kt` (no Android in them): what was typed against what was meant, a run scored and read both ways,
  the step from one fix to the next, the test of the fixes and what is proof, the layout trials, what is kept.
- `Trainer.kt`, `TrainerKeys.kt`, `TrainerChannel.kt`: a run in hand and the fix it wears, the ear on the keyboard's
  keys, and the screen's channel. `lib/trainer/` is the pages, `trainer_fix.dart` the test as they read it, and
  `trainer_board.dart` the pictures on them; `assets/trainer/stories.txt` the fifty-four story parts, written for it.
- `tool/trainer_sim.sh` (kotlinc and java, no emulator): a simulated thumb with a steady lean types the stories on
  the fixes and without, to show they do what they are meant to; and a thousand thumbs of each kind, with a lean
  and with none, to show how often the test says "proven", and to whom.

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

Needs Flutter 3.50 (3.47 still builds it) and the Android SDK.

```sh
flutter pub get
flutter run            # or: flutter build apk
```

Then open **Dev Keyboard**, tap **Turn on**, enable it in the system list, come back and tap **Switch**.

The build service names its Flutter: `flutter-version` in `.github/workflows/release.yml` and `check.yml`, 3.50.0
since 2026-11-11. A release is built by the Flutter the app was tried on, not by whichever came out that morning.
Android's Gradle plugin (9.3.1), Kotlin (2.4.20) and Gradle (9.5.0) are the versions that Flutter makes a new
project with (`android/settings.gradle.kts`, `android/gradle/wrapper/gradle-wrapper.properties`).

### Moving to a new Flutter

A new Flutter refuses Android build versions it has stopped supporting, from the day it comes out: 3.50.0 refused
the Gradle plugin 9.1.0 and Kotlin 2.4.0 that 3.47 had made this project with, without a warning from 3.47 first.

1. Read what the new one asks for, at its tag in `github.com/flutter/flutter`. In
   `packages/flutter_tools/gradle/src/main/kotlin/DependencyVersionChecker.kt`, below an `error…Version` it refuses
   to build and below a `warn…Version` it says it soon will. In
   `packages/flutter_tools/lib/src/android/gradle_utils.dart`, the `template…Version`s are what it makes a new
   project with, and `compileSdkVersion`, `targetSdkVersion` and `ndkVersion` are its Android defaults. Then the
   release's list at `docs.flutter.dev/release/breaking-changes`.
2. Raise the three versions to the template's, together. `targetSdk` stays written out in
   `android/app/build.gradle.kts`: read [docs/sms-codes.md](docs/sms-codes.md) before raising it.
3. Push that to a branch of its own, with the new `flutter-version` in both workflows, and run Check on it:
   `gh workflow run check.yml --ref <branch>`. Check makes a debug build only. A release-mode build
   (`flutter build apk --release --target-platform android-arm64`) is what tries R8 and the release's packaging.
4. Run the app that build made on a device before the branch goes to `main`. A machine whose own Flutter is older
   can still do that: have the branch's Check keep its APK (`actions/upload-artifact`), fetch it with
   `gh run download`, and sign it again with the machine's debug key
   (`apksigner sign --ks ~/.android/debug.keystore --ks-key-alias androiddebugkey`), so that it installs over a
   local debug build and keeps its data.
