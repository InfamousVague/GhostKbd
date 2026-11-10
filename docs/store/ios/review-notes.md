# Notes for App Review

Paste the block below into App Store Connect › App Review Information › Notes. Fill in the two placeholders. Keep it
free of third-party names (use "the AI provider" throughout).

---

Ghost Keyboard is a custom keyboard (a keyboard extension) with a companion app for its settings.

**Setting it up**
1. Open the app and follow the walkthrough, or: Settings › General › Keyboard › Keyboards › Add New Keyboard › Ghost Keyboard.
2. In any text field, touch and hold the globe key and choose Ghost Keyboard.

**It works without Full Access**
Without Allow Full Access the keyboard types, autocorrects, offers suggestions, swipe-types, switches pages
(letters, ?123, =\<, and the { } code page), shows long-press alternates, and uses its default theme. It makes no
network requests and cannot: it has no network access in that state. The next-keyboard (globe) key is provided
wherever iOS asks for one.

**Why Full Access is requested (RequestsOpenAccess)**
Full Access is optional and used only for:
- the optional AI assistant, which sends a request the user writes to the AI provider the user has chosen, using the
  user's own API key (network access);
- sharing settings, learned words and themes between the app and the keyboard (the shared container);
- clipboard history, read only when the user taps Clipboard or Paste on the keyboard;
- haptic feedback, which iOS allows keyboards only with Full Access.
The keyboard does not log or transmit keystrokes. There is no analytics and no server operated by us.

**Consent**
Before any text can be sent to an AI provider, the app shows a screen stating what is sent, to whom and when, and the
user must tap "I agree" (Settings › AI › Your AI shows it again and lets the user withdraw). Without that agreement, or
without Full Access, the keyboard's row says plainly that the AI is off and why, and sends nothing.

**Testing the AI assistant**
The assistant uses the user's own account with an AI provider; there is no account with us and nothing to sign in to.
For review:
1. In the app: Market › add Ghostwriter (read and accept the consent screen).
2. Settings › AI › Your AI: choose the provider `<provider name>` and paste this key: `<a key Matt creates for review, with a small spending limit>`.
3. Turn on Allow Full Access: Settings › General › Keyboard › Keyboards › Ghost Keyboard.
4. In Notes, with Ghost Keyboard selected, type `<<a short thank-you note>>`. When the closing `>>` is typed the row
   shows "✦ Writing…" and the answer replaces the request.

**Other notes**
- The keyboard does not offer dictation. iOS's own dictation key below the keyboard is unaffected.
- iOS shows its own keyboard in password fields; that is expected.
- The settings cog on the keyboard opens the companion app's settings.
- No sign-in, no purchases, no ads.

---

## Before sending

- Create the review key at the provider with a low limit, and revoke it after approval.
- The note names a provider in step 2 because the reviewer has to pick it in the app. That is in the review notes
  only, which are not public metadata.
- If App Review objects to the keyboard opening its companion app from the settings cog (it asks the host app to open
  a `ghostkbd://` link), set `canOpenApp` to `false` in `ios/GhostKeyboard/KeyboardViewController.swift`: the cog then
  shows "Open the Ghost Keyboard app to change settings" instead. Nothing else depends on it.
