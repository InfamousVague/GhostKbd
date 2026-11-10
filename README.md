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

Installed copies update themselves from **https://attack.fm/devkeyboard/** (Ghost.md's box). On opening the app,
and from the keyboard twice a day, `Updater.kt` reads `apk.json`, downloads the APK, checks its size, SHA-256,
package, version and signing certificate, and installs it with Android's `PackageInstaller` — silently on Android 12+
once the app installed its own previous version, otherwise with Android's one-tap confirm. Settings › Updates shows
where it stands and has the **Automatic updates** switch. Debug builds never update.

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
