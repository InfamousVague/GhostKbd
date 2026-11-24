# Ghost Messaging's colours, kept in step

Matt, 2026-11-15: "make the color themes available the same as the ghost messaging app and keep the two in sync".

## The colours

Settings › Look & feel › Colours › Colour (Android only) offers Ghost Messaging's bubble colours, by the same ids
and in the same order:

| id | colour |
| --- | --- |
| ghost | the theme's own accent (Ghost: #008B8A light, #51FCFB dark) |
| ink | the keys' own lettering, black or white |
| wallpaper | Android's accent from the wallpaper (accent1 600 light, 200 dark; Android 12+, else the theme's own) |
| white … mint | the fixed colours of Ghost Messaging's `bubbleColors` |

On the keyboard it is the accent (`KeyboardTheme.withAccent`): the enter key, a chosen popup key, and the backlight
where it follows the theme. It is laid over whatever theme is chosen, presets included; Ghost leaves each its own. A
colour that would not be seen on the keyboard's ground (contrast under 1.5, as white on the light keyboard) leaves the
theme's own, as Ghost Messaging's accent does on its light page.

Three lists must stay the same: `AccentSync.COLOURS` (Kotlin), `accentColours` in `lib/settings/theme_editor.dart`,
and Ghost Messaging's `bubbleColors` (messaging/lib/backgrounds.dart in the glyph repository) with its
`AccentSync.IDS`. Each side's unit test pins the ids in order.

## Keeping in step

- Each app keeps its choice and when it was chosen: here `accent` and `accent_at`; in Ghost Messaging `bubble` and
  `bubble_at` in its "ui" preferences.
- Each answers the other at `content://<package>.accent/current` with `accent` and `at` (read-only).
- Choosing one stamps the time and notifies that address.
- Each asks the other as it starts (the keyboard service) or comes to the front (either settings app), and watches the
  other's address while it runs. The other's choice is taken only where it is newer; taking it keeps its time, so the
  two never trade it back and forth. Before either has been chosen (time 0) nothing is taken.

## Who may ask

The two are signed with different keys, so each pins the other's release certificate, as the one-time codes do: the
keyboard pins `GhostMessagingCodes.CERTIFICATE`, Ghost Messaging pins `CodesProvider.KEYBOARDS`. A debug build takes
any build of the other, for trying the two on an emulator. Only the colour's id and its time cross.
