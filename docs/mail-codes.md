# One-time codes from email

Matt, 2026-11-12: "for the OTP parsing id like to also be able to hook up gmail so new authentication codes can be
parsed and sent to the keyboard automatically."

Settings › Row tools › One-time codes from email. Off unless switched on. A code that arrives in Gmail, or another mail
app, shows above the keys as the codes from texts do ([sms-codes.md](sms-codes.md)): a chip with an envelope on it at
the start of the row for three minutes, the first thing in the clipboard's list for ten, gone once it is typed.

## How, and why not by signing in to Gmail

The keyboard does not sign in to Gmail. It reads the mail app's own notification, on the phone.

Three ways were looked at (2026-11-12).

- **Gmail's API, with Google sign-in.** Google's rules for it rule a keyboard out by name. Its Workspace API user data
  and developer policy (last updated 2026-09-03, read 2026-11-12) says: "The Gmail scopes are not allowed for certain
  use cases. These include, but are not limited to: Mobile keyboards." Every scope that can read a mail's body is
  "restricted", which for an app anyone can install means Google's verification and a yearly security assessment; an
  app that has not passed is capped at 100 people, behind a warning screen. Not built.
- **IMAP with an app password.** Google still gives app passwords to accounts with 2-Step Verification, and Gmail's
  IMAP is on for everyone. It would work and needs no review by Google. But an app password is a password to the
  account's whole mail, to send as well as to read, and it would be kept in a keyboard, with a connection held open
  to Google. Not built; it is Matt's to choose, and would be a mail client's worth of new code.
- **The mail app's notification.** Gmail tells Android a mail has come; Android shows that to an app the person has
  given notification access, as it shows it on the lock screen. Nothing leaves the phone, there is no account and no
  password, it works with Offline on, and it is not Gmail's alone: Outlook, Proton Mail and the rest say the same
  things the same way. Built. Its limit is Android's own, below.

## What happens to a notification (`MailCodes.kt`)

- Android hands every app's notifications to `MailCodeListener`: it has no way to hand over only some. (The person
  can narrow it on Android's own page for the access, by app.) Notifications that stay up, a call or music or a
  download, are never asked for.
- One that is not a mail app's is not read at all: the app's id is checked before a word of it is touched. A mail
  app is one of `MailCodes.APPS` (Gmail, Gmail Go, Outlook, Samsung Email, Yahoo Mail, Proton Mail, K-9 Mail,
  Thunderbird, FairEmail) or any app whose notification says it is email.
- Of a mail app's notification two things are read: its line of text and its longer text. For Gmail those are
  reported to be the subject, and the subject with the start of the mail; that is from another app's device-tested
  notes (OTP Helper, October 2026), not seen here. Not the title (the sender), not the account's address, not the
  picture, not the people. The first 600 characters, cut between words.
- Those words are read once for a code by the same finder the texts go through (`OtpCodes.find`) and let go of. A
  code that is found is held with the codes from texts, in memory only (`SmsCodes.hold`). The subject, the sender and
  the mail are kept nowhere, written nowhere and sent nowhere; nothing in the file opens a connection, logs, or writes
  a file (`MailCodesTest` holds the source to that).
- The line that only sums up others ("3 new messages") is not read. A notification Android posted more than ten
  minutes ago, or a mail more than half an hour old posted again, is not news. The same mail posted twice, which
  Gmail has been reported to do, is one code: a code that was typed does not come back.
- With the switch off the listener is disabled in Android itself, so no notification reaches the app at all, whatever
  access it still holds.

The keyboard never cancels, snoozes, opens or answers a notification.

## What Android keeps back

**Notification access.** Android's own switch, on its own page, which only the person can turn. For an app from
outside a store Android calls it a restricted setting first: on Android's page for the app, ⋮, Allow restricted
settings, as for the texts.

**Android 15 and later hide codes from apps like this one.** A notification that Android judges to hold a one-time
code reaches a notification listener with its words replaced: the title becomes the app's name and the text
"Sensitive notification content hidden" (in the phone's language). That is Android guarding codes against an app that
reads notifications and sends them away, which this one could be for all Android knows. Read in Android 16's source
(`NotificationManagerService.redactStatusBarNotification`, `isAppTrustedNotificationListenerService`), a listener is
shown such a notification whole only if one of these holds:

1. It is part of the system, or holds a permission only the system's own apps and a few roles are given.
2. The app-op `RECEIVE_SENSITIVE_NOTIFICATIONS` is allowed for it. Android has no switch for that; the phone's owner
   sets it from a computer, once:
   `adb shell cmd appops set --user 0 com.infamousvague.devkeyboard RECEIVE_SENSITIVE_NOTIFICATIONS allow`
   Android looks at it only when a listener connects, not when it is set, so the listener connects again where it
   has changed since it last did (`MailCodeListener.reconnect`: when Settings is opened, and when a notification
   arrives hidden), and then goes over what is on the screen, so the code Android had just hidden is read after all.
3. It has a companion device paired through Android (a watch's app, say). Not used: the keyboard has no device, and
   inventing one to get round a protection is not something it should do.

And where nothing is judged to hold a code, nothing is hidden. The judging is done by Android's notification
assistant: "Enhanced notifications" in Android's notification settings (on a Galaxy, reported as "Suggest actions and
replies for notifications"). Turning it off also ends Android's suggested replies.

So on Android 15 and later the feature needs one of those two things done by the phone's owner. Settings says so
where it applies (`MailCodes.trusted` reads the app-op), offers the command to copy, and says when Android has in
fact hidden a mail's notification from the keyboard (`MailCodes.Hidden`: the listener knows the replacement text by
asking Android for its own string). It cannot see whether Enhanced notifications is on.

**Gmail's own settings.** Gmail notifies for the Primary inbox by default. A code filed under another label, or with
notifications set to "High priority only" or "None", posts nothing for the keyboard to read.

## Seen, and not seen

Seen on the emulator (Pixel image, Android 16, a debug build, which also reads the shell's own notifications so that
`adb shell cmd notification post` can stand for a mail app):

- The switch enables the listener and its page in Settings says where Android stands.
- With access given, a posted "Your verification code is 482913. It expires in 10 minutes." puts 482 913 at the start
  of the row with an envelope on its chip. Android did not judge the shell's notification to hold a code.

And with a throwaway build whose listener also read the emulator's text-message app, since that is what Android's
assistant there does judge (2026-11-12; that build was never committed):

- As things come, with nothing allowed: of three texts with a code, each the first from its sender, one reached the
  listener whole and was then handed over a second time hidden (the code was on the row by then); the other two were
  hidden from the start. A second text in a conversation Android had already judged was hidden every time. So
  without one of the two ways above, most codes do not show.
- The app-op allowed, and the listener not yet connected again: still hidden. Connected again: the next code showed,
  in a conversation where it had been hidden before.
- Android's notification assistant switched off (`adb shell cmd notification disallow_assistant`, which is what
  turning Enhanced notifications off does), the app-op back as it came: nothing was judged, and both codes of a new
  conversation showed.

Not seen: a real mail in a real Gmail, on any phone. The emulator has no Google account. What Gmail puts in its
notification, on a Galaxy in particular, is taken from other people's notes; if the code is not in the subject or the
first lines of the mail, it will not be found. Nor whether a Galaxy's assistant judges as the Pixel's does.

## Tests

`MailCodesTest` (11): which notifications are read, what is made of one, old mail, repeats, what Android has hidden
(and that a hidden copy of what was already read is no loss), the codes held together with the texts', and what the
source promises (the listener off until switched on, only
Android able to bind it, nothing kept, written or sent, only two fields of a notification read).
`test/mail_codes_settings_test.dart`: the row in every state.

## Not done

- Signing in to Gmail, by its API or by IMAP: see above.
- Any way round Android's hiding that the phone's owner did not choose.
- A code that is a link ("Click to sign in"): there is nothing to type.
- iPhone: none of it. iOS gives a keyboard no notifications.
