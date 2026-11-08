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
- Slide along the space bar to move the cursor; long-press space for the keyboard picker
- Double-space period; enter key follows the field (search, go, send, next, done, newline)
- Number/phone fields open on `?123`; toolbar with settings and hide
- Settings: theme, key height, number row, symbol hints, auto-caps, double-space period, space-bar cursor, long-press delay, popup, vibrate, sound

## Build and run

Needs Flutter 3.41+ (Dart 3.11) and the Android SDK — the same toolchain as Ghost.md.

```sh
flutter pub get
flutter run            # or: flutter build apk
```

Then open **Dev Keyboard**, tap **Turn on**, enable it in the system list, come back and tap **Switch**.
