# Clipboard pins

Part of the clipboard plugin (`ClipHistory.kt`, `ClipList.kt`; Android only). Matt, 2026-11-12, choosing from a list
of plugins to add: "I like … clipboard pins".

The clipboard's history holds the last 25 copies and forgets the oldest as new ones come. A pinned clip is one that
stays: an address, a link, a reply sent often.

## What a pin is

- It leads the clipboard's list, with a pin on its chip. Two pins stand newest first, then the copies.
- It is not one of the 25. A newer copy never pushes it out.
- Clearing the history (the bin at the end of the list, or Settings › Row tools › Clear clipboard history) takes the
  copies and leaves what is pinned.
- Copied again, it stays pinned.
- At most 25 clips are pinned (`ClipList.MAX_PINS`). A pin past that is refused, and says so.

## How

- In the keyboard: open the clipboard from the row and hold a clip. It is pinned, with a longer tick; hold it again
  and it goes back among the copies, as old as it is (with 25 newer copies there, it is let go). A tap pastes a clip,
  pinned or not. Until the first pin is ever made the list says "Hold a clip to pin it"; never after.
- In Settings › Row tools › Pinned clips: each pin is listed and can be removed, which is for good, and a clip can be
  written by hand and pinned without ever being copied.

## What is kept, and where

The pinned clips are kept where the history is: the app's private storage on the phone (`clip_history`), each a
copy's text, its time and a mark that it is pinned. Nothing is sent anywhere. As before, nothing is recorded that
Android marks sensitive (a password manager's copies), while incognito is on, or with the plugin off; a pin written
by hand in Settings is the one clip that was never on the clipboard.

Ghostwriter's "My recent clipboard" (off unless turned on) reads the newest copies by time, whatever is pinned
(`ClipHistory.recent`): an old pin is not "recent", so pinning something does not put it in front of the AI.

## Checked

`ClipListTest` (12 tests): the order, the count, what a copy, a pin, an unpin, a clear and a removal do, the limit,
and that "recent" goes by time alone. `test/clipboard_pins_settings_test.dart`: the section in Row tools, removing,
clearing, a pin written by hand, a refusal said in words, and the Market's words on Android and on iPhone.

Not checked on a device when this was written: the hold in the row's list (`RowItem.onLong`, `PluginRow.holdable`)
and the pin's drawing.
