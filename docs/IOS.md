# Ghost Keyboard on iOS

The iOS version is a containing app (this repo's Flutter settings app, built for iOS) and a keyboard extension written
in Swift. None of the Android keyboard's Kotlin runs on iOS; it is ported file for file, and the Kotlin stays the
reference: where the two differ and this page does not say why, the Kotlin is right.

## Architecture

```
ios/
  Runner/            the Flutter app: walkthrough, Settings, Market. AppDelegate answers `dev_keyboard/settings`
                     (SettingsChannel.swift), the same channel contract as Android's MainActivity.
  GhostKeyboard/     the keyboard extension (UIInputViewController). UIKit only, no Flutter.
    KeyboardViewController.swift   Android's DevKeyboardService: typing, autocorrect, the row, Ghostwriter.
    KeyboardView.swift             Android's KeyboardView: the keys, drawn; touch; previews; swipe trail.
    PluginRowView.swift            Android's PluginRow: the row above the keys.
    Icons.swift                    RowIcons + an SVG path reader (Phosphor's drawings).
    RowStyle.swift                 Android's RowStyle: the tools' own colours, the AI's working sparkle and line.
  GhostCore/         everything with no UI in it, as a Swift package: Layouts, KeyboardTheme, Backlight,
                     KeyboardPrefs, Plugins, Autocorrect, AdaptiveKeys, Ghostwriter, AiClient, Mockingbird,
                     ClipHistory, Rewrite, GitHubCommits. `swift test` runs its unit tests on a Mac; the same
                     source files are compiled straight into the app and the extension (no framework to embed).
  Shared/            what the app and the extension both need from iOS: the App Group, the shared Keychain.
```

- **Settings** live in `UserDefaults(suiteName: "group.com.infamousvague.ghostkbd")` under the same keys and
  defaults as Android's `KeyboardPrefs`. The app writes them through the channel; the keyboard reads them each time it
  appears and when the app posts a Darwin notification, so a change shows the next time the keyboard draws.
- **Secrets** (each AI provider's key, the GitHub token) are not in UserDefaults: they are in the Keychain, in an
  access group the app and the extension share. The channel routes those keys there, so `lib/settings_store.dart`
  is unchanged.
- **Files** (Mockingbird's samples and style profile, the clipboard history, the adaptive touch model) are in the App
  Group's container.
- **Bundle ids**: `com.infamousvague.ghostkbd` (app), `com.infamousvague.ghostkbd.keyboard` (extension), Team
  `F6ZAL7ANAD`.

## Full Access

iOS gives a keyboard no network, no shared container and no haptics until the person turns on Allow Full Access.

| | Without Full Access | With Full Access |
| --- | --- | --- |
| Typing, pages, long-press, previews, swipe, cursor gestures | yes | yes |
| Themes, backlight, layout settings | the settings as they were when it last had Full Access; if it never had it, the defaults (iOS keeps the shared settings from it) | yes, live |
| Autocorrect, suggestions | yes | yes |
| Learned words, adaptive keys | kept inside the keyboard only | shared with the app |
| Haptics | no (iOS rule) | yes |
| Clipboard history | no; the row says why | yes |
| Ghostwriter, Mockingbird, Rewrite | no; the row says why | yes, once agreed to in the app |

The keyboard never makes a network request of its own accord: only a `<<request>>`, "In my voice", Rewrite, or a
Mockingbird training run does, with the person's own key, straight to the provider they chose, and only after the
consent screen in the app (`ai_consent`).

## What iOS does differently, and what the port does about it

| Android | iOS | The port |
| --- | --- | --- |
| `InputConnection` reads and sets the selection, sends key events | `textDocumentProxy`: text before/after the cursor (a window, not the whole field), the selected text, insert, delete backward, move by characters | Everything is written against those. Cursor up/down counts characters to the same column on the next line (hard line breaks only). |
| Ctrl+Z / Ctrl+Shift+Z for undo and redo | no key events | Undo and redo undo what this keyboard typed: every edit it makes is journalled, and undone only while the text before the cursor still ends with it. |
| Esc, Tab, Ctrl, Alt as key events | no key events | The terminal keys insert the control characters a terminal reads (Esc `0x1B`, Tab, Ctrl+C `0x03`, Alt as an Esc prefix). |
| `EditorInfo.packageName` | the host app is not told to a keyboard | No per-app profiles; markdown and terminal keys show when set to everywhere. |
| A see-through strip above the keyboard for key previews | a keyboard cannot draw outside its view | Previews and long-press panels are drawn over the row above the keys, shortened where the top row leaves less room. |
| Clipboard listener | none; reading the pasteboard shows iOS's paste notice | The clipboard is read only when its chip is tapped. |
| Password fields | iOS shows its own keyboard there | Nothing to do; the backlight and learning rules for private fields still apply to anything marked no-autocorrect. |
| Input method picker (long-press space) | globe key | A globe key where iOS asks for one (`needsInputModeSwitchKey`): tap for the next keyboard, hold for the list. |
| A keyboard draws everything down to the screen's edge | on a phone with no home button iOS draws a strip under every keyboard (its globe and dictation keys), which a keyboard cannot remove or cover | The default light and dark themes draw no background of their own, so iOS's keyboard backdrop shows through and the keys and the strip read as one surface. A chosen theme keeps its colour. Setup says how the person can make the globe and microphone go. |
| Vibrator, sound packs | haptics with Full Access; the system click | Haptics: light/medium/strong impact. Sound: the system's keyboard click. |

## v1

Letters, `?123`, `=\<` and the developer page; shift, caps lock, auto-capitals, double-space full stop; long-press
alternates; key preview; space-bar cursor (characters and lines); word delete by sliding from backspace; themes
(system, light, dark, presets, custom, the Retro keycaps) and the backlight (every effect); key borders, hints, key
height, number row, one-handed, split and docked layouts on iPad; haptics and the click; the row with suggestions,
arrow keys, undo/redo, markdown and terminal keys, incognito; autocorrect in six languages with learned words, slang
and undo-by-backspace; Adaptive keys with its heatmap; swipe typing; Ghostwriter with every provider, web search and
GitHub commit search; Mockingbird (samples, style profile, "In my voice"); Rewrite; clipboard history; the globe key.

## Deferred, and why

- **Dictation.** A keyboard extension cannot record: iOS denies it the microphone whatever its Info.plist says. The
  only legal design is a hand-off: the mic key opens the containing app (`ghostkbd://dictate`), the app records and
  transcribes (whisper.cpp, as on Android), writes the text to the App Group, and the keyboard inserts it when the
  person comes back. iOS gives no way to return to the app they were typing in, so they would have to switch back by
  hand every time, and opening the app from a keyboard is itself something App Review tolerates rather than allows.
  That is not solid enough to ship. On iPhones without a home button iOS shows its own dictation key under every
  keyboard, which works with Ghost Keyboard as it is. Left out of v1.
- **Emoji and GIFs.** The emoji keyboard is one tap of the globe away, and a keyboard cannot attach an image to a
  message on iOS (only copy it to the pasteboard for the person to paste). Later, if asked for.
- **Passwords.** iOS AutoFill puts logins above every keyboard already.
- **Ghost.md notes, per-app profiles, typing stats, typing-profile import/export, sound packs, the updater.** Either
  iOS has no equivalent (per-app, updater) or they are additions that do not block a first release.

## Memory

A keyboard extension is ended by iOS somewhere past 60 MB (the limit is not documented and is lower on older phones).
Measured in the Simulator (iPhone 17, iOS 27, a debug build; `footprint` on the extension's process, and the
`debug_memory` note a debug build writes at each stage of starting):

| | |
| --- | --- |
| The process before any of the keyboard's code has run | 21 MB |
| With the keyboard drawn and English loaded | 31 to 34 MB |
| After typing, corrections, a Ghostwriter fill and a rewrite | 33 MB |

A release build on a phone should be lower (the dictionary alone measures about 3 MB in a release build of GhostCore on
a Mac, against about 13 MB in the debug extension), but that has not been measured on a device. Each further language
costs about what English does. The Simulator reports a peak near 60 MB for every extension as its process starts,
before the keyboard's code runs; that is the Simulator, not the keyboard.

The dictionaries are the `.tsv` files Android ships (`android/app/src/main/assets/dict`), copied into the extension at
build time rather than duplicated in the repo: 2.6 MB for the six languages. Loading is off the main thread; typing
works before it finishes. `didReceiveMemoryWarning` drops every language but the first.

## Tests

- `ios/GhostCore`: 96 unit tests (`swift test`): layouts, themes, backlight, settings and secrets, autocorrect against
  the real word lists, swipe decoding, the touch model, the `<<request>>` parser, every AI provider's request and
  response, Mockingbird, the clipboard history, Rewrite, GitHub search.
- `test/ios_app_test.dart`: the settings app on iOS (walkthrough, consent, the iOS pages) and Android unchanged.
- `ios/RunnerUITests`: the keyboard driven in Safari on the Simulator (`ios/tool/ui_test.sh`, with
  `ios/tool/stub_server.py` as the page typed in and a stand-in AI provider). iOS shows a UI test nothing inside a
  keyboard extension, so the tests tap where `Layouts.swift` puts each key.

## Verified, and how

On the Simulator (iPhone 17, iOS 27), by the UI tests, typing in Safari:

- The keyboard is added in Settings and comes up; auto-capitals; `teh keybkard works` + two spaces becomes
  `The keyboard works. `; `dont` becomes `Don't` and backspace puts `Dont` back and learns it.
- `?123`, `=\<`, the `{ }` page; a pair key leaves the cursor inside; the page's comma returns to the letters; shift
  for one capital; a long press types the first alternate.
- Without Full Access: it types and corrects, a `<<request>>` is not sent (the stand-in provider logs no request) and
  the row says "AI is off: it needs Allow Full Access".
- With Full Access but without the agreement in the app: nothing is sent, and the row says
  "AI is off: agree in the Ghost Keyboard app".
- With both: `<<standup>>` + return is sent to the stand-in provider with the key from the shared Keychain, and the
  answer replaces the tag. Rewrite from the row replaces the field's text. Undo takes back the keyboard's last edit.
- A slide along the space bar moves the cursor; a slide left from delete takes whole words.
- The Retro keycaps with the number row, the light theme with key borders, and the dark default all draw as on
  Android; the backlight lights a pressed key's legend.
- With the typing-out effect on, the answer is typed key by key in place of the tag. (This found a fault, now fixed:
  an app applies a cursor move a moment after it is asked for, so the tag's deletion now waits for it.)
- Settings written by the app reach the keyboard through the App Group; the keyboard's "seen" and Full Access flags
  reach the app.
- The app launches into the iOS walkthrough. A release archive builds unsigned.

- (2026-11-10) Android 0.9.5 brought across: the row's tools as icons in their own colours, seen in the Simulator;
  the default theme on iOS's backdrop, seen; the developer page's long-press panel, a glide's trail and the Retro
  keycaps, seen (the store screenshots). On an iPad Pro 13-inch Simulator the keyboard comes up in its split layout
  and types from the developer page; the app's pages lay out.
- A release archive signed for the App Store builds and exports (`docs/store/ios/SUBMIT.md`); the app and the
  extension carry the App Group and the shared Keychain group.

## Known gaps against Android 0.9.5

Android's undo and redo were rebuilt on a model of the field (`FieldEditor.kt`, `EditHistory.kt`). iOS keeps its
simpler journal (`TextEditor.swift`), which differs where it matters in these ways: undoing a delete is not checked
against the text around it; the journal is not dropped when the field changes while the keyboard stays up; an edit
that moves the cursor (a pair, a markdown wrap) cannot be undone and trying empties the history; every backspace and
every chunk of an AI answer is its own step; the arrows are not dimmed when there is nothing to undo.

## Not verified

Nothing here has run on a real iPhone or iPad. Not exercised at all, beyond compiling and unit tests of their logic:

- Haptics and key sounds (the Simulator has neither).
- The globe key on the keyboard itself (it appears only on phones with a home button; every current Simulator phone
  shows iOS's own globe below the keyboard).
- Swipe typing by a finger (its decoder is unit-tested; a UI test cannot draw a path), moving by lines from the
  space bar, the Adaptive keys heatmap.
- Landscape, iPad, the split and docked layouts, one-handed mode.
- The other preset themes and a custom theme; the backlight effects other than the default.
- Clipboard history and Paste (iOS's paste permission prompt), "In my voice", Mockingbird's sample collection and
  training, web search and GitHub search against real services, every real AI provider (only
  the OpenAI-compatible path ran, against the stand-in).
- The settings cog opening the app from the keyboard.
- The settings app's pages beyond the first walkthrough screen, on a device or the Simulator (its widget tests pass).
- The AI's working sparkle and line, a held space bar starting cursor control, adaptive keys' slow drift and
  Mockingbird's sample cleaning inside the extension (its one-off re-clean of stored samples was not timed there):
  compiled and unit-tested, not watched.

## Changes outside `ios/`

Nothing under `android/`, `site/`, `tool/` or `.github/` was changed. `lib/` gained the iOS walkthrough, the consent
screen and the iOS settings pages behind `isIOS` (`lib/platform.dart`); Android's screens are as they were. After adding
or removing a Swift file, `ios/tool/sync_xcodeproj.rb` brings the Xcode project in line.

## Building

```bash
flutter build ios --simulator --debug        # the app and the extension, for the Simulator
cd ios/GhostCore && swift test                # the shared logic's unit tests
ios/tool/ui_test.sh <simulator udid>          # the keyboard driven in Safari (start ios/tool/stub_server.py first)
```

An App Store archive: `docs/store/ios/SUBMIT.md`.
