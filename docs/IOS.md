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
| Themes, backlight, layout settings | yes, as last synced: read-only copies of the settings are readable; where iOS refuses even that, the defaults | yes, live |
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

A keyboard extension is killed somewhere past 60 MB (the limit is not documented and is lower on older phones). The
budget, measured on the Simulator (see the test `AutocorrectMemoryTests`):

| | |
| --- | --- |
| UIKit, the view, the drawn icons | about 12 MB |
| One language's dictionary (40,000 words: a `[String: Float]`, a sorted array, the swipe index) | see GhostCore's report below |
| Each further language | about the same again |
| Mockingbird, Ghostwriter, clipboard | text only, under 1 MB |

The dictionaries are the `.tsv` files Android ships (`android/app/src/main/assets/dict`), copied into the extension at
build time rather than duplicated in the repo. Loading is off the main thread; typing works before it finishes.
`didReceiveMemoryWarning` drops every language but the first.

## Building

```bash
flutter build ios --simulator --debug        # the app and the extension, for the Simulator
cd ios/GhostCore && swift test                # the shared logic's unit tests
```

An App Store archive: `docs/store/ios/SUBMIT.md`.
