# One-time codes from Ghost Messaging

Matt, 2026-11-13: "The messaging app should also work with ghost kbd to proxy verification codes".

Ghost Messaging (ghost-sms.com, `com.infamousvague.ghostmessaging`) is an SMS app of the same hand. Where it is the
phone's default SMS app it has a code's text the moment it arrives, and it hands the keyboard the code. This is the
third way a code reaches the row above the keys, after texts ([sms-codes.md](sms-codes.md)) and email
([mail-codes.md](mail-codes.md)), and the one that Android puts nothing in the way of.

## Why it is needed

The keyboard reading texts itself needs Android's permission to receive them:

- Android 15 and later refuse that permission to an app from outside a store until "Allow restricted settings" is
  chosen on the app's page.
- Android 17 holds a code's text back for three hours from every app but the default SMS app (for apps built for
  Android 17, every code; for the rest, the ones written for one app or one website).

None of that touches the default SMS app, and none of it touches one app answering another on the phone.

## How it works

| Side | Where | What |
| --- | --- | --- |
| Ghost Messaging | its `Codes.kt` | A content provider, `content://com.infamousvague.ghostmessaging.codes/latest`. Asked, it looks through the texts of the last ten minutes for codes and answers with `code` and `at` (when the text arrived, milliseconds since 1970): three at most, newest first, the same code once. It keeps nothing for this. When a text with a code arrives it tells whoever is watching that address. |
| The keyboard | `GhostMessagingCodes.kt` | Watches that address while the switch is on and Ghost Messaging is there, asks when told (and once as it starts), and holds what is new with the codes from texts (`SmsCodes.hold`): a chip at the start of the row for three minutes, in the clipboard's list for ten, gone once typed. |

Who is trusted, both ways:

- Ghost Messaging answers only Ghost Keyboard, known by its package and the SHA-256 of its release certificate
  (`92d8d198…`, as Ghost.md's notes are). Anyone else is answered with nothing.
- The keyboard asks only the app that answers at that address, and only where it is Ghost Messaging by its package
  and the certificate its releases are signed with (`GhostMessagingCodes.CERTIFICATE`, `323e0f73…`). The two apps are
  signed with different keys, so each pins the other's. An app that took the address on a phone without Ghost
  Messaging is never asked: what it answered would be typed as a code.
- A debug build of either takes any build of the other, for trying the two on an emulator.

What is handed over is the code and the time. Never the text's words, never who sent it. On the keyboard's side it is
kept in memory only, as every code is: never on Android's clipboard, in the clipboard history's file, among what
Ghostwriter may read, or in a typing report.

## The switches

- The keyboard: Settings › Row tools › One-time codes from Ghost Messaging (`ghost_messaging_codes`). On to start,
  unlike the other two: it asks Android for nothing, and does nothing on a phone without Ghost Messaging. It is the
  clipboard plugin's, as the others are.
- Ghost Messaging: Settings › Ghost Keyboard › Offer sign-in codes to Ghost Keyboard. On to start.

## Tests

`GhostMessagingCodesTest` (what of an answer is held, once; that only Ghost Messaging is asked and nothing is
written), `SmsCodesSourceTest` (the holders of codes now name this file too),
`test/ghost_messaging_codes_settings_test.dart` (the row). On Ghost Messaging's side, `CodesTest`.
