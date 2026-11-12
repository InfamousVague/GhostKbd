# The strip under the keys

Matt, 2026-11-12: "Id like to be able to detect if the system show hide keyboard and keyboard select are enabled and
if not be able to slide the keyboard down since we don't need to reserve the space anymore."

Under a keyboard a phone draws its gesture bar and, on most, two buttons of its own: one to hide the keyboard and one
to switch to another. Since 0.9.3 the keys have kept 48dp clear for those buttons wherever there is a gesture bar at
all, because Matt's phone drew them over the bottom row while telling the keyboard about less room than they took
(2026-11-09: "The keyboard needs to be higher the bottom stock android keyboard collapse and select are intercepting
the UI"). On a phone with both buttons switched off, that is 48dp of nothing. `SystemStrip.kt` now decides whether the
buttons are there, and Settings › Look & feel › Keys › Space under the keys › Auto keeps only the gesture bar's room
where the phone says they are not.

## What says whether the buttons are there

In the order they are believed (`SystemStrip.buttons`):

1. **Android's own strip in the keyboard's window: the buttons are there.** With gesture navigation, Android 13 and
   later draw the two buttons inside the keyboard's own window. From Android 14's first quarterly release the room
   for them reaches the keyboard as a caption bar inset of its own; the navigation bar's inset is then only the
   gesture bar's 24dp. (Read in Android's source, 13 to 16, and seen on the emulator: Pixel image, Android 16,
   gesture navigation, the keyboard's window 48dp taller than its keys.) A caption bar inset at the bottom, or
   Android's strip found shown in the window, is the buttons, whatever any setting says.
2. **A Galaxy's own settings: the buttons may be gone.** Read by name, without any permission:
   - `navigation_bar_gesture_while_hidden` (global; 1: swipe gestures, 0: its three buttons).
   - `navigation_bar_button_to_hide_keyboard` (global; 1: "Show button to hide keyboard").
   - `navigation_bar_gesture_hint` (global; 0: the gesture bar is not drawn, and then neither is that button).
   - `show_keyboard_button` (looked for in the secure, system and global tables; 1: "Keyboard button on navigation
     bar").
   Only where gestures are on and every one that matters could be read, and they say neither button is on, are the
   buttons taken to be gone.
3. **Otherwise it is not known**, and the keys keep the 48dp as before.

So the keys only sit lower where the phone itself says there is nothing under them. Stock Android has no setting
that turns its buttons off, so on a Pixel nothing changes.

## How sure each of those is

- The first three Galaxy names are Samsung's own: found in its Settings app as two ROM projects carry it (One UI 7
  and 8), and read the same way by another open-source keyboard (CyIME), whose notes are also where "no gesture bar,
  no hide button" comes from. That 0 means "off" for the hide button is taken from its reset writing 1.
- `show_keyboard_button` is **a guess**: the preference in Samsung's Settings is keyed `key_show_keyboard_button`,
  and one settings dump quoted in a search result has `show_keyboard_button=1`. If a Galaxy keeps it under another
  name, it cannot be read, the buttons are "not known", and the keys stay where they were: the guess can fail only
  towards doing nothing.
- **None of this has been seen on a Galaxy.** Matt's phone is never driven from here. What a Galaxy tells the
  keyboard with each button on and off is not known; it is why Settings says what was found (below).
- Samsung's own help says the hide button shows "when using Portrait mode": turned on its side a Galaxy may draw
  none, and the keys keep the room all the same.

## What changed in the keyboard

- `KeyboardView.bottomInset` counts the caption bar inset with the navigation bar's and what can be tapped, and the
  view asks to be laid out again when any of them moves. It used to watch the navigation bar's alone: since Android
  14 the buttons' room arrives after that one, without it changing. That may be what put the buttons on the bottom
  row on Matt's phone in the first place; not known, since nothing was measured there.
- `SystemStrip.room`: None is what Android reports; Auto is the 48dp wherever there is a gesture bar, or the gesture
  bar alone (Android's own measure of it, at most 24dp; 12dp where the phone draws no gesture bar) where the buttons
  are gone, however much Android reports; More is the 48dp and 20dp again, whatever is found.
- The service reads the Galaxy settings once and watches them, so switching a button off in the phone's settings
  moves the keys the next time they are laid out, without restarting anything.

## What Settings says

Under Space under the keys, once the keys have been shown: that both buttons are off and Auto keeps only the gesture
bar's room; or that they are there; or that the phone did not say. Settings › About › Developer › Copy settings ends
with one line of what was found, lengths in dp and the Galaxy settings as read, for instance:

    under the keys: navigation=24dp caption=0dp tappable=0dp gestures=32dp strip=none galaxy(gestures=1 hint=1 hide=0 keyboard=null) buttons=unknown room=48dp

That line from Matt's phone, with each of its two buttons on and off, settles what is guessed above.

## Not done

- Taking Android's own strip away where it cannot be switched off (stock Android). Android 16 lets a keyboard hide
  it and draw its own switch key instead; that is another change, and a setting of its own.
- Any other maker's settings (vivo and others also draw a switch button under a keyboard): not known, so the room
  is kept.

## Tests

`SystemStripTest` (6): Android's strip wins over any setting; a Galaxy's buttons are gone only where both are off;
what cannot be read changes nothing; the room for each choice; the line.
