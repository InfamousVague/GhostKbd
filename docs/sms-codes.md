# One-time codes from text messages

Matt, 2026-11-11: "add a feature that can read one time passcodes from text messages and show them in the clipboard".
And the same day, of how it looked: "make the code show up as a special chip not just the normal clipboard".

A sign-in sends a text: "Your verification code is 482913". With this on, the code is a chip at the start of the row
above the keys the moment the text arrives, and a tap types it. It is also the first thing in the clipboard's list
(the clipboard button in the row) for as long as it is held.

**It is off unless the person turns it on**: Settings › Row tools › One-time codes from texts, under Clipboard
history, whose plugin it belongs to. Turning it on is when Android is asked for the permission, and not before.
Android only.

## What happens to a text

- Android hands every text that arrives to the app (`SmsCodeReceiver`). It cannot hand over only some: the
  permission is all texts or none.
- The text is read once for a code (`OtpCodes.find`) and let go. **The text, who sent it and when are kept
  nowhere**: not in a file, not in a log, not in memory after that one look.
- A code that is found is held by `SmsCodes`, **in memory only**, for ten minutes (`KEEP_MS`) or until it is typed,
  three at most. For the first three minutes (`FRESH_MS`) it leads the row as a chip of its own
  ([The chip](#the-chip)); for all ten it is in the clipboard's list, drawn the same way. Typed, it is let go at
  once: a one-time code is for one use.
- A text with no code in it changes nothing and leaves nothing.

## The chip

A code is not something copied, and its chip does not look like one (`CodeChip.kt`). The clipboard's chips are amber
with a clipboard on them; a code's is:

- **its own blue** (`RowTint.CODE`), a colour no other chip in the row has;
- **a text message** on it, a speech bubble with three dots, where the others have a tool's icon;
- **the code in the groups it is read in**: six digits as two threes (`482 913`), eight as two fours, nine as three
  threes, every digit the same width. Any other length, and any code with a letter in it, is left whole. This is
  only how it is drawn (`CodeLook.groups`): what a tap types is the code as it came, with no space in it;
- **an outline that runs down** as the time it is offered for passes, starting whole and opening from the top the
  way a clock's hand goes round: three minutes at the row's start, ten in the clipboard's list. When the outline is
  gone, so is the chip.

As it arrives it settles into place and a light passes over the digits twice (`CodeLook.ARRIVAL_MS`). With Android's
animations off it does neither; the outline still moves, once a second, as a clock does. A screen reader says "One-time
code 4 8 2 9 1 3, from a text message": a digit at a time, not a number in the hundreds of thousands.

## What it never does

- **Never reads the texts the phone has stored.** The app asks for `RECEIVE_SMS` only. There is no `READ_SMS`, so it
  cannot look at the messages app's history, and no `SEND_SMS`. Android's own question says "send and view SMS
  messages" all the same: that sentence is Android's for the whole group of text permissions, whichever one an app
  asks for.
- **Never writes a code to storage.** Not the clipboard history's file, not the settings, not a log. If Android
  stops the keyboard's process, a held code is gone, which is the price of that.
- **Never puts a code on Android's clipboard**, where other apps could read it. "The clipboard" here is the
  keyboard's own list.
- **Never sends anything anywhere.** Nothing in `SmsCodes.kt` or `OtpCodes.kt` opens a connection, and
  `tool/net_check.sh` would fail if it did. A code is not among what Ghostwriter may read, not in a typing report
  (a report never holds text with a figure in it anyway), not in Mockingbird's samples by this road, and typing it
  is a paste: nothing learns from it and typing stats do not count it.
- **Nothing at all with the switch off.** The receiver is disabled in Android itself (`SmsCodes.apply` sets the
  component's state; the manifest ships it `enabled="false"`), so no text reaches the app whatever permission it
  still holds. Only Android can send to the receiver: it is guarded by `BROADCAST_SMS`, which no app can hold.
  To take the permission back as well, use Android's page for the app (Permissions › SMS).

`SmsCodesSourceTest` holds the sources to each of these.

Incognito does not hide a code: nothing of it is kept either way, and a sign-in is where incognito is most often on.
The clipboard button itself is hidden while incognito, so there the code is only the chip in the row.

## What Android itself decides

Checked in November 2026 against Android's own pages (links at the foot).

- **Android 15 and later, an app from outside a store.** Text permissions are "restricted settings" for a
  sideloaded app: Android refuses the request until **Allow restricted settings** is chosen on the app's page in
  Android's settings (⋮ at the top right). The switch's row says so and opens that page once Android has refused.
  An app installed with `adb` is not restricted, so an emulator does not show this; it has not been seen on a phone
  by us yet.
- **Android 17.** To stop apps stealing codes, Android holds some texts back from apps for three hours:
  - a text written for one website to read (WebOTP: its last line is `@site.example #123456`) or for one app to
    read (SMS Retriever: it ends in an eleven-character jumble) is held back from **every** other app. Those codes
    do not show here on Android 17. The app or site they were written for fills them in by itself;
  - an ordinary text ("Your code is 123456") is held back the same way from apps **built for Android 17**
    (`targetSdk` 37 or higher). This app is built for Android 16 (`targetSdk` 36, Flutter's default today), so it
    still receives them.
- **Before raising `targetSdk` to 37**: on Android 17 this feature would then receive nothing in time. The row
  already says so if it happens (`_smsCodesRows` reads the phone's version and the build's target). The way forward
  then is Google's SMS User Consent API (Android asks about each text, and it needs a Play services library), or to
  keep the target at 36 for as long as sideloading allows.

## Finding the code

`OtpCodes.kt` is text in and a code or nothing out, with no Android in it.

1. A text longer than 1,000 characters is not a code's.
2. A last line of `@site.example #123456` gives its code outright.
3. Otherwise the text must have a word that names a code: *code*, *OTP*, *PIN*, *verification* and their like in
   the keyboard's languages and the commonest others (Chinese, Japanese, Korean, Russian, Arabic, Hindi, Thai,
   Turkish, Polish, Dutch, Swedish, Indonesian, Vietnamese). Words many other texts have too (*password*, *log
   in*, *security*) count only right beside the number.
4. An advertisement's words (*20% off*, *promo*, *coupon*) rule a text out unless it also has a word only a code's
   text has (*OTP*, *verification*, *one-time*).
5. The candidates are runs of four to eight figures, six figures in two halves ("482 913", "482-913": typed as
   482913), and letters with figures where the text says outright "code is K7M2PQ".
6. A number is not a candidate when it is part of something else: an amount, a date, a time span, a phone number,
   an address on the web, the end of a card or account, an order or reference number, a postcode, a count of
   something, a longer name ("BA2490").
7. The code is a candidate in the same sentence as the word that names it: the nearest, six figures before other
   lengths. A year (1900 to 2099) only where it stands beside that word.

`OtpCodesTable.kt` is the table it is held to: 101 made-up texts in the shapes real services send, 65 with a code
and 36 without. `tool/otp_check.sh` runs it in a second, and `tool/otp_check.sh "Your code is 482913"` says what the
finder makes of any text.

It will get some wrong. A wrong chip is a nuisance and goes by itself in three minutes; a missed code is typed by
hand as before. Known misses: a code in three groups ("123 456 789"), a code written in letters only, a text with
no word for what the number is.

## Limits

- The row above the keys must be showing (Settings › Look & feel › Keys › Hide the row is off).
- A code is typed whole into the field in hand. A sign-in page with six separate boxes takes it or not as that page
  is written, as it does a paste.
- A code that arrived while the keyboard's process was not running is held only if Android keeps that process
  until the keyboard is opened. It usually does for the keyboard in use.
- No iOS: a keyboard extension is given no texts. iOS fills codes into its own keyboard's row by itself.

## Where things live

| | |
| --- | --- |
| `OtpCodes.kt` | the finder |
| `SmsCodes.kt` | the holder (`SmsCodes`), the switch's effect on Android (`apply`), the receiver (`SmsCodeReceiver`) |
| `CodeChip.kt` | how a code is drawn: `CodeChip` (the view), `CodeLook` (its groups, how much of its edge is left), `RowCode` |
| `DevKeyboardService.kt` | `codeItems` (the chips), `pasteCode`, the listener that redraws the row |
| `MainActivity.kt` | `smsCodesState`, `setSmsCodes`, `askForTexts`, `openAppInfo` for the settings app |
| `lib/settings/panes.dart` | `_smsCodesRows`: the switch and what it says of Android |
| `AndroidManifest.xml` | `RECEIVE_SMS`, the receiver (off, and Android's alone to call) |

## Checks

- `OtpCodesTest` (the table; how long a code is held; that using it lets it go), `SmsCodesSourceTest` (the promises
  above against the sources, and that the chip is a code's own and not the clipboard's), `CodeLookTest` (the groups,
  the outline's share), `test/sms_codes_settings_test.dart` (the row in each state).
- On the emulator (Android 16), 2026-11-11: the switch, Android's question refused and then allowed, a text sent
  with `adb emu sms send` showing as a chip with the keyboard open and after it was opened, the chip typing its
  code and going, a second code in the clipboard's list, an ordinary text showing nothing, and the switch off
  taking the receiver out of Android's list (`cmd package query-receivers`).
- The chip, on the emulator, 2026-11-11, on a dark and a light keyboard, condensed rows and normal ones: a text
  arriving with the keyboard open (the chip at the row's start, the light over its digits), the outline opening from
  the top a few seconds on, six digits as two threes, eight as two fours, a four-digit PIN whole, the same chips in
  the clipboard's list, and a tap typing `482913` with no space in it and the chip going.
- Not checked: a phone with a SIM and a real service's text; Android 15's restricted-settings refusal on a
  sideloaded install; Android 17; the chip where the screen reads right to left, or with a screen reader on.

## Sources

- Android 17 behaviour changes, [all apps](https://developer.android.com/about/versions/17/behavior-changes-all)
  and [apps targeting Android 17](https://developer.android.com/about/versions/17/behavior-changes-17): the
  three-hour hold.
- [Android 15's restricted settings for sideloaded apps](https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/)
  (9to5Google, from the compatibility definition), and
  [what it looks like on Android 16](https://community.appinventor.mit.edu/t/sendmessagedirect-messagereceived-setting-sms-permissions-on-android-16/173745)
  (a developer forum).
