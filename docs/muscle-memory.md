# Muscle Memory

A Market plugin (`musclememory`, `MuscleMemory.kt`; Market › Look, off until added; Android only). It lays the
keyboard out as Microsoft SwiftKey's, for someone who has typed on SwiftKey for years. Kevin, 2026-11-13, from his own
SwiftKey and the GhostKBD Figma mock (nodes 9:7983 and 9:5099), then 2026-11-14: "make a toggle for the arrows and the
numbers and the layout so if someone just wants the arrows they can have them".

Once added it has a page of its own, Settings › Muscle Memory, with a switch for each of its three parts, all on to
start. Each works without the other two.

## The parts

| Part | Setting | What it does |
| --- | --- | --- |
| Layout | `mm_layout` | SwiftKey's symbols in the letters' corners, first on each long-press: `% ^ ~ \| [ ] < > { }` along the top, `@ # & * - + = ( )` in the middle, `_ $ " ' : ; /` at the bottom. The bottom row is ?123, emoji (with the emoji plugin), comma, space, full stop, enter. Every key has its own face (as Key borders does). |
| Number row | `mm_numbers` | A number row on every page, bare digits in a row 0.75 as tall as a letter row, as SwiftKey's. ?123's own digits make its row; =\< and the code keys get the letters' digits above them. |
| Arrow keys | `mm_arrows` | Up, down, left and right in a row 0.7 tall under every page (`KeyType.ARROW`). A tap moves the cursor a character or a line, as the space bar's slide does; a hold repeats after 400 ms, every 60 ms. |

Every layout gets the same corners by place, so QWERTZ and AZERTY keep their letters.

## The full stop

With the layout on, the full stop shows `,!?` in its corner. A touch on it that slides past 10 dp opens SwiftKey's
quick menu at once, with no wait for a long-press: `! , . ?` in one line, the full stop over the key and already chosen,
so a slide left or right and a lift types the mark under the finger (`MuscleMemory.FLICK`, `KeyboardView.makeFlickPanel`).
A tap types a full stop; a hold that does not move opens its whole panel as before.

The comma beside the space bar is the comma; with the dictation plugin ready, a hold on it starts a dictation, and a
small mic in its corner says so (`Key.holdsMic`).

## One key size on every page

With any part on, rows keep their own height (`KeyRow.height`) on every page instead of the page's rows sharing out the
letters' room. Before, ?123 has one row fewer than the letters, so its keys came out taller. A page shorter than the
letters stands on the same bottom line, the room above it empty, so the bottom rows stay under the thumbs as the pages
change (`KeyboardView.rowsTop`).

## Files

- `MuscleMemory.kt`: the parts, the letters made SwiftKey's from Gboard's (`letters`), the other pages (`page`), the
  number row (`numbers`), the arrow row (`arrows`), and the quick menu's place (`flickLeft`). No Android in it.
- `Layouts.kt`: `KeyType.ARROW`; `Key.bare` (no face, even with borders), `Key.holdsMic`, `Key.flick`; `KeyRow.height`.
- `KeyboardView.kt`: `muscle` (the parts on), `rebuild`, row heights, the arrow repeat, the comma's hold, the quick menu.
- `KeyboardPrefs.kt`: `mm_layout`, `mm_numbers`, `mm_arrows`, all on by default; `DevKeyboardService` and
  `KeyboardPreview` read them through `MuscleMemory.parts`, which is no parts unless the plugin is added.
- `lib/plugins/catalogue.dart` (the Market's entry), `lib/settings/panes.dart` (`_musclePage`), `lib/settings_store.dart`.
- `MuscleMemoryTest.kt`: 12 tests.

## Checks

610 Gradle unit tests, 75 Flutter tests, `dart analyze` of the changed files adds nothing, and a debug build. Typed on
Kevin's phone (a side-by-side debug build): the layout, number row and arrows. The quick menu and the parts' switches
are not yet tried on a phone.
