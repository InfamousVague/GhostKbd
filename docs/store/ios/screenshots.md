# Screenshots

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
