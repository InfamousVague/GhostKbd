# Screenshots

## Taken (2026-11-10)

Raw screens from the Simulator at the sizes App Store Connect takes, with no transparency, no captions drawn on, and
no third-party name in any of them. The typing surface is the app's own "Try the keyboard here" field.

- `screenshots/iphone-6.9/` (1320 × 2868, iPhone 17 Pro Max, iOS 27): `01-letters`, `02-developer`, `03-longpress`,
  `04-swipe`, `05-retro`, `06-request`, `07-answer`, `08-look-and-feel`, `09-setup`. Made by
  `ios/RunnerUITests/StoreShots.swift` (`TEST_RUNNER_SHOTS=<folder> ios/tool/ui_test.sh <udid> testShots0_Setup
  testShots1_LettersAndPairs …`, with `STUB_ANSWER="…" python3 ios/tool/stub_server.py` serving the answer).
- `screenshots/ipad-13/` (2064 × 2752, iPad Pro 13-inch, iOS 27): `01-letters` (the split layout), `02-developer`,
  `03-retro`, `04-look-and-feel`, `05-setup`. Taken by hand (`xcrun simctl io <udid> screenshot`): the UI test places
  its taps by the phone's layout.

The iOS strip under the keyboard shows only its microphone: Ghost Keyboard was the Simulator's only keyboard, which
is when iOS leaves the globe out. The plan below is what the set was drawn from; the captions are there if the
pictures are later framed.

Required sets: iPhone 6.9" (1320 × 2868, iPhone 17 Pro Max) and iPad 13" (2064 × 2752), because the app runs on iPad.
Three to ten each. Take them in the Simulator: `xcrun simctl io booted screenshot name.png`.

To put the keyboard in a known state, a debug build takes settings from a link
(`ghostkbd://debug-set?theme_preset=retro&plugins=...`); the UI tests in `ios/RunnerUITests` show how to add the
keyboard and turn on Full Access in the Simulator.

## Must not appear

- Any third-party name or logo: no AI provider or model names (stay off Settings › AI's provider picker, or crop it),
  no other keyboards' names, no other companies' apps as the typing surface. Type in the app's own "Try it" field, in
  Notes, or in a plain page.
- The words "Android", "beta", "test", prices, or a device frame from another maker.
- An API key, a real person's name, or a real conversation.
- The status bar should read 9:41 with full signal and battery:
  `xcrun simctl status_bar booted override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4`.

## The set (same order on both devices)

1. **The keyboard on the letters page, dark theme, typing code** in a plain note: a line such as
   `const result = await fetch(url)` with the suggestion row showing. Caption: "A keyboard for people who code".
2. **The { } page** open, with a pair just typed and the cursor inside. Caption: "Brackets in pairs. Operators in families."
3. **A long-press panel** on `=` (==, ===, !=, !==, <=, >=). Caption: "Hold any key for its family."
4. **Swipe typing** mid-glide, the trail showing. Caption: "Glide to type."
5. **Themes**: the Retro keycaps or a custom theme, with the backlight lit. Caption: "Your look, down to the glow."
6. **The AI assistant**: a field holding `<<reply that I'm running ten minutes late>>` with the row showing
   "✦ Fill: …", and a second shot or a split with the answer in place. Caption: "Ask where you type. Your own key, your own provider."
   Use the stand-in provider (`ios/tool/stub_server.py`) or a real answer with nothing naming its source.
7. **Settings in the app**: the Look & feel page. Caption: "Everything is a switch."
8. **Privacy**: the walkthrough's Full Access page. Caption: "Types, corrects and themes without Full Access."

Captions, if drawn onto the images, use the app's own typeface (Inter) and colours; generic icons only.

## App preview video

Optional; skip for the first version.
