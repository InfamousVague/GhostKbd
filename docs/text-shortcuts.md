# Text shortcuts

A plugin (`shortcuts`, `Shortcuts.kt`; Settings › Typing › Text shortcuts, off until switched on; Android only).
Matt, 2026-11-12, choosing from a list of plugins to add: "I like … text shortcuts".

A shortcut is a few letters that stand for a phrase: `omw` for "On my way!", `@@` for an e-mail address, `;sig` for
a sign-off of several lines.

## How one is typed

- Type the shortcut as a word of its own. It is matched whole and by itself: the run of characters between the last
  space (or line break, or the field's start) and the cursor, whatever its capitals. `omw` is found in "omw" and
  "Omw", and not in "omw.", "(omw" or "homw".
- While that run is a shortcut, the row above the keys shows what it stands for as its first chip, and the letters
  themselves in quotes beside it. A tap on the phrase puts it in; a tap on the letters keeps them as typed.
- With **Expand when I press space** on (the default), a space or Enter straight after the shortcut puts the phrase
  in its place, followed by the space. Enter then does what Enter does (a new line, or sending the message).
- Backspace straight after an expansion puts the letters back, and the next space leaves them alone, once: letters
  kept that way (or by their own chip) are kept from autocorrect too. Only the very next key counts: after anything
  else, backspace is backspace. The Undo arrow takes an expansion back as one step, with its space.
- A full stop or comma straight after the letters does not expand them. Only a space or Enter does.

## What a shortcut may be, and what it stands for

- One word of 2 to 24 characters with no space in it; letters, digits and marks all count (`;sig`, `@@`, `addr1`).
- The phrase is up to 2,000 characters and is typed as written, with four things read in it (`Shortcuts.expanded`):
  `\n` is a new line, `{date}` is today's date (2026-11-12), `{time}` the time (09:05), and `{clipboard}` the newest
  clip in the clipboard's history. `{clipboard}` is nothing where there is no clip, while incognito, or with the
  clipboard plugin off. Anything else in braces is left as it is.
- Up to 200 shortcuts. Adding one that is already there changes it.

## Where

Everywhere the keyboard types, terminals too, but never in a password field. Incognito does not stop a shortcut:
expanding one learns nothing and sends nothing.

## What is kept, and where

The shortcuts are kept on the phone only, in the keyboard's own settings file (`text_shortcuts`). No connection is
made for them, and a phrase put in by a shortcut is not counted by next-word prediction.

## Checked

`ShortcutsTest` (10 tests): what counts as a shortcut before the cursor, what may be one and the words for why not,
the list's order and limit, what a phrase types, and that the list is written and read back whole.
`test/text_shortcuts_settings_test.dart`: the switch on Typing, the page, removing, the form and its refusals.

Not checked on a device when this was written: the expansion at a space and at Enter, backspace taking it back, and
the row's two chips (`DevKeyboardService.expandShortcut`, `useShortcut`, `contextual`).
