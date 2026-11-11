# site-dev

Bench pages for the website's keyboard component and its chunks. They were under `site/dev/` until 2026-11-09 and
were moved out so that publishing `site/` as it stands can never serve them. Serve the repository root and open
`site-dev/kbd.html`, `site-dev/keycaps.html` or `site-dev/chunk-<name>.html`.

`retired/` holds the old hero chunk (a three-row letter board with autocorrect), which the page no longer uses.
Some bench pages were written for the chunks as they were before that date (the privacy key's switch, the
three-row swipe board) and may need their notes brought up to date. `kbd.html` has the whole board's options, with
the key heights and one-handed on its phone and a second board on a wide screen for split, full width and docked;
`chunk-look.html` is the look key as it is on the page since 2026-11-11 (a whole keyboard on a small screen, a look
a tap).

## Keeping the page true to the app

The page's words are written by hand and go stale as the app moves: on 2026-11-11 it still said "no next-word
prediction yet" and "six languages", a day after the app had both (they came on 2026-11-10; the page was last read on the 9th). After a release, read `site/index.html` against
the app again, most of all the comparison table, "Where Ghost Keyboard is behind", "Coming next" and "What leaves my
phone?" (which must match `NetLedger.kt` and Settings › Offline).

The settings search on the page (`site/js/chunks/search.js`) is Settings read by hand, except the two pages Settings
draws from what the keyboard sends: the languages and the connections. Those rows are made by
`tool/site_search_rows.py` into `site/js/chunks/search-rows.js` from `Languages.kt`, `Layouts.kt` and `NetLedger.kt`;
`--check` says whether the file is current, and `tool/deploy-site.mjs` will not ship the site when it is not.
