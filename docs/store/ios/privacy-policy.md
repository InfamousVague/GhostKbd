# Ghost Keyboard: Privacy Policy

Last updated: <date of publication>

Ghost Keyboard is a keyboard for iPhone and iPad, with a companion app for its settings. This policy says what the
keyboard and the app store, what leaves your device, and when. The short version: we (the maker of Ghost Keyboard)
receive nothing. There is no account, no analytics, no advertising and no server of ours.

## What ordinary typing does

Nothing you type is sent anywhere by typing. Autocorrect, suggestions, swipe typing and Adaptive keys run on your
device with dictionaries that ship inside the app.

## What is stored on your device

- **Your settings**, including words you taught autocorrect.
- **Adaptive keys' touch model**: for each letter, where your touches land relative to the key, and up to 400 points
  where a touch missed (the two letters involved and the position, never the text).
- **Clipboard history**, if you use it: up to 25 pieces of text you chose to bring in by tapping Clipboard or Paste
  on the keyboard. The keyboard does not read the clipboard on its own.
- **Mockingbird's samples and style profile**, if you turn Mockingbird on: up to 1,500 samples of text you typed with
  this keyboard (each up to 1,500 characters) and the written description of your style made from them. Never from
  secure fields, addresses, numbers or searches, and never while Incognito is on.
- **Your API key** for the AI provider you choose, and a GitHub token if you add one, in the iOS Keychain.

All of it stays in storage shared only by the Ghost Keyboard app and its keyboard. You can delete the clipboard
history, Mockingbird's samples and profile, and the touch model in Settings; deleting the app deletes everything.

## Allow Full Access

iOS asks every keyboard whether it may have "Full Access". Without it, Ghost Keyboard types, corrects and themes, and
cannot reach the network at all. With it, the keyboard can use the features below, share settings with its app, read
the clipboard when you ask it to, and give haptic feedback. Full Access does not change what ordinary typing does:
nothing is sent.

## The AI features (optional, off until you agree)

Ghostwriter, Mockingbird and Rewrite work by sending text to an AI provider **you** choose, with **your own** API
key. They are off until you add them, enter a key, and agree to a screen in the app that says what follows. You can
withdraw that agreement in Settings at any time; the keyboard then refuses every AI feature.

When you use one of these features, your device sends, directly to the provider you chose:

- **Ghostwriter** (a request typed between `<<` and `>>`): the request; up to 1,500 characters before and 400 after
  it in the field you are typing in; the date; a label for the kind of field; your style profile if you have one;
  with Memory on, past samples of your own typing that relate to the request; with their switches on, up to six
  recent clipboard items from the keyboard's history and the messages of your own GitHub commits that match the
  request.
- **"In my voice" and Rewrite**: the text you selected (or, with nothing selected, the text around the cursor), your
  style profile, and for "In my voice" related samples of your typing.
- **Training a style profile**: recent samples of your typing (up to about 24,000 characters).
- **Test it / Load models** in Settings: a fixed test message, or a request for the provider's list of models.

Every request carries your API key, as the provider requires. If you turn on web search, the provider performs the
search on its side; your device contacts no other service.

The providers you can choose are listed in the app. What a provider does with what it receives is governed by its own
terms and privacy policy, and by your account with it. We are not a party to that exchange and cannot see it.

**GitHub** (optional): if you add a GitHub token and turn the switch on, your device asks GitHub's API for your account
name, your organisations, and commits matching words from your request. The token goes only to GitHub.

## What we collect

Nothing. The app contains no analytics or advertising code, we operate no server that it talks to, and we have no way
to see your typing, your settings, your keys or your requests.

## Children

Ghost Keyboard is not directed at children and collects no information from anyone.

## Changes

If this policy changes, the new version will be posted at this address with a new date.

## Contact

<contact email or support page>
