// The walkthrough on an iPhone. The pages about the keyboard itself are Android's (code, look, start); the ones about
// turning it on are iOS's own: a keyboard there is added in the Settings app, is offered Allow Full Access, and is
// switched to with the globe key. No dictation page: iOS gives a keyboard no microphone.
//
// The words that differ from Android's are here, not in narration.dart, so tool/onboarding_voice.py makes no voice for
// them yet: those pages are shown with their words and no voice.
part of 'onboarding.dart';

const List<(String, String, _Look)> _iosPages = [
  ('welcome', 'Hi, I’m Ghost Keyboard', _Look(['indigo', 'purple', 'rose'], LucideMore.keyboard, [Lucide.braces, Lucide.sparkles, Lucide.palette, Lucide.spellCheck])),
  ('iosEnable', 'Add the keyboard', _Look(['green', 'mint', 'cyan'], Lucide.settings, [LucideMore.keyboard, Lucide.shieldCheck, Lucide.smartphone, Lucide.check])),
  ('iosAccess', 'Full Access, your call', _Look(['blue', 'indigo', 'purple'], Lucide.shieldCheck, [Lucide.globe, Lucide.clipboardPaste, Lucide.sparkles, LucideMore.vibrate])),
  ('iosSwitch', 'Switch to it', _Look(['cyan', 'blue', 'indigo'], Lucide.globe, [LucideMore.keyboard, Lucide.repeat, Lucide.circleCheck, Lucide.type])),
  ('code', 'Code, a tap away', _Look(['amber', 'orange', 'coral'], Lucide.braces, [Lucide.code, Lucide.terminal, Lucide.hash, Lucide.equal])),
  ('plugins', 'Plugins', _Look(['indigo', 'purple', 'rose', 'orange'], Lucide.store, [Lucide.puzzle, Lucide.palette, LucideMore.wandSparkles, Lucide.penLine])),
  ('look', 'Pick a look', _Look(['magenta', 'purple', 'blue'], Lucide.palette, [Lucide.sunMoon, Lucide.swatchBook, LucideMore.keyboard, Lucide.sparkles])),
  ('start', 'You’re ready', _Look(['rose', 'orange', 'amber'], LucideMore.rocket, [Lucide.graduationCap, Lucide.circleCheck, Lucide.flame, Lucide.sparkles])),
];

/// The words of the pages that are iOS's own, or say something Android's cannot on an iPhone. A page not here says
/// narration.dart's words, in its voice.
const Map<String, String> iosNarration = {
  'iosEnable': 'First, add Ghost Keyboard to your iPhone’s keyboards. Tap the button to open Settings, tap Keyboards, and switch Ghost Keyboard on. Then come back here.',
  'iosAccess': 'Under that switch is another: Allow Full Access. It lets the keyboard reach the internet, and the storage it shares with this app. Ghost Keyboard uses it only for the AI features you turn on, clipboard history, vibration, and keeping your settings and learned words in step with this app. Nothing you type is sent anywhere unless you use an AI feature. Without it, the keyboard still types, corrects and themes.',
  'iosSwitch': 'Now switch to it. Tap the box below, then hold the globe key at the bottom of the keyboard and pick Ghost Keyboard. The globe takes you back the same way.',
  'plugins': 'Autocorrect, swipe typing, the clipboard and undo are already on, and the rest are switches in Settings. Ghostwriter, Mockingbird and Rewrite, which write with the AI provider you choose, are in the Market. Adding one is a switch, and so is taking it away.',
};

/// An iOS page's own step; null where the page's step is Android's.
List<Widget>? _iosStep(BuildContext context, SettingsStore store, String id, _Look look) {
  final t = context.tokens;
  Widget done(String words) => Row(children: [AppIcon(Lucide.circleCheck, size: 20, color: look.hue), SizedBox(width: t.space(2)), Expanded(child: Text(words, style: pageText(t, t.fontSize('md'), weight: 'medium', color: t.color('app-ink'))))]);
  Widget quiet(String words) => Text(words, style: pageText(t, t.fontSize('sm'), height: 1.4, color: t.color('app-ink-3')));
  Widget openSettings() => MarketButton(label: 'Open Settings', icon: Lucide.settings, look: MarketButtonLook.filled, color: look.hue, large: true, onPress: store.openInputSettings);
  switch (id) {
    case 'iosEnable':
      return [
        store.enabled ? done('Ghost Keyboard is added.') : openSettings(),
        SizedBox(height: t.space(3)),
        quiet('The long way to the same switch: Settings › General › Keyboard › Keyboards › Add New Keyboard… › Ghost Keyboard.'),
      ];
    case 'iosAccess':
      return [
        store.fullAccess ? done('Full Access is on.') : openSettings(),
        SizedBox(height: t.space(3)),
        quiet('AI requests go straight to the provider you choose, with your own API key. Ghost Keyboard has no server in between.'),
        if (!store.fullAccess) ...[
          SizedBox(height: t.space(2)),
          quiet('It shows here once the keyboard has been opened with it on. You can leave it off and go on.'),
        ],
      ];
    case 'iosSwitch':
      return [
        const GlacierInput(placeholder: 'Tap here, then hold the globe key', ariaLabel: 'Try the keyboard'),
        SizedBox(height: t.space(3)),
        if (store.selected) ...[done('You’ve switched to Ghost Keyboard.'), SizedBox(height: t.space(3))],
        quiet('iOS shows its own keyboard for passwords, and in some apps.'),
      ];
    default:
      return null;
  }
}
