// Settings as an iPhone has it, where it differs from Android's (panes.dart asks `isIOS` and comes here). The keyboard
// there is an extension (ios/GhostKeyboard) under iOS's rules, which these pages say where they bite:
// - it reaches the network, and the storage it shares with this app, only with Allow Full Access: the AI plugins,
//   clipboard history and vibration need it, and typing, correcting and theming do not;
// - it is not told which app it is typing in: markdown and terminal keys show everywhere or nowhere, and there are no
//   per-app profiles;
// - it has no microphone, and its key sound is the system's click;
// - the App Store updates the app.
part of 'panes.dart';

/// One row saying what needs Allow Full Access, opening this app's page in the Settings app where it is turned on.
PaneSection _iosFullAccess(SettingsStore store, String what) => PaneSection(
      children: [
        SettingRow(
          label: 'Allow Full Access',
          hint: Text('$what, an iOS rule. Without it the keyboard still types, corrects and themes.'),
          icon: AppIcon(Lucide.shieldCheck, size: 18),
          value: const Text('Open Settings'),
          onPress: store.openInputSettings,
        ),
      ],
    );

/// What stands between the person and the AI on iOS, a row each while it does: Allow Full Access, and the consent.
List<Widget> _iosAiNotes(BuildContext context, SettingsStore store, {bool consent = true}) => [
      if (!store.fullAccess) _iosFullAccess(store, 'The AI needs it to reach your provider'),
      if (consent && !store.aiConsent)
        PaneSection(
          children: [
            SettingRow(
              label: 'Agree to what the AI is sent',
              hint: const Text('The keyboard refuses AI until you do.'),
              icon: AppIcon(Lucide.circleAlert, size: 18),
              value: const Text('Read'),
              onPress: () => ensureAiConsent(context, store),
            ),
          ],
        ),
    ];

/// Settings › AI › Your AI: the consent as it stands, read again or withdrawn.
PaneSection _iosConsentSection(BuildContext context, SettingsStore store) => PaneSection(
      title: 'What the AI is sent',
      footer: const Text('Your request, the text around it and what you let it read go to your AI provider, only when you use an AI feature. Withdraw, and the keyboard refuses AI until you agree again.'),
      children: [
        if (store.aiConsent) ...[
          SettingRow(label: 'You agreed', hint: const Text('Tap to read it again.'), icon: AppIcon(Lucide.circleCheck, size: 18), onPress: () => pushSettings(context, (_) => AiConsentPage(store: store))),
          SettingRow(label: 'Withdraw my agreement', onPress: () => store.set('ai_consent', false)),
        ] else
          SettingRow(label: 'Not agreed yet', hint: const Text('The keyboard refuses AI until you do.'), value: const Text('Read and agree'), onPress: () => ensureAiConsent(context, store)),
      ],
    );

// --- Setup --------------------------------------------------------------------------------------------------------

String _iosSetupSummary(SettingsStore s) {
  if (!s.enabled) return 'Not added yet';
  if (!s.selected) return 'Added: hold the globe key to switch to it';
  return s.fullAccess ? 'Ready: Full Access is on' : 'Ready · Full Access is off';
}

List<Widget> _iosSetup(BuildContext context, SettingsStore store) {
  final ready = store.enabled && store.selected;
  return [
    PaneHero(
      glyph: AppIcon(ready ? Lucide.circleCheck : LucideMore.keyboard, size: 24, strokeWidth: 2),
      title: ready ? 'Ready' : (store.enabled ? 'One step left' : 'Add the keyboard'),
      meta: Text(ready ? 'You’ve switched to Ghost Keyboard.' : (store.enabled ? 'Hold the globe key on any keyboard and pick Ghost Keyboard.' : 'Add Ghost Keyboard in the Settings app, then switch to it.')),
      status: PaneHeroStatus(ready ? (store.fullAccess ? 'Set up' : 'Full Access off') : (store.enabled ? 'Added' : 'Not added yet'), pulse: !ready),
    ),
    PaneSection(
      title: 'Steps',
      footer: const Text('The long way to the same switch: Settings › General › Keyboard › Keyboards › Add New Keyboard… › Ghost Keyboard.'),
      children: [
        SettingRow(label: '1. Add Ghost Keyboard', hint: const Text('Opens Settings: tap Keyboards, then switch Ghost Keyboard on.'), value: Text(store.enabled ? 'Done' : 'Open'), onPress: store.openInputSettings),
        SettingRow(label: '2. Allow Full Access', hint: const Text('In the same place. Yours to choose: see below. It shows here once the keyboard has been opened with it on.'), value: Text(store.fullAccess ? 'On' : 'Off'), onPress: store.openInputSettings),
        SettingRow(label: '3. Switch to Ghost Keyboard', hint: const Text('Hold the globe key on any keyboard and pick it. Try it in the field below.'), value: Text(store.selected ? 'Done' : 'Not yet')),
      ],
    ),
    PaneSection(
      title: 'What Full Access is',
      footer: const Text('Nothing you type is sent anywhere unless you use an AI feature.'),
      children: const [
        SettingRow(label: 'What it allows', hint: Text('The keyboard may reach the internet and the storage it shares with this app. That is why iOS warns you before you turn it on.')),
        SettingRow(label: 'What Ghost Keyboard does with it', hint: Text('The AI features you turn on (your own API key, sent straight to the provider you chose), clipboard history, vibration, and keeping your settings and learned words in step with this app. Nothing else.')),
        SettingRow(label: 'Without it', hint: Text('The keyboard still types, corrects and themes.')),
      ],
    ),
    PaneSection(
      title: 'Good to know',
      children: const [
        SettingRow(label: 'Passwords', hint: Text('iOS shows its own keyboard for passwords, and in some apps.')),
        SettingRow(label: 'Dictation', hint: Text('iOS doesn’t let a keyboard use the microphone. The dictation key under the keyboard still works.')),
        SettingRow(label: 'Emoji', hint: Text('The iPhone’s own emoji keyboard is one tap of the globe away.')),
        SettingRow(label: 'The globe and microphone under the keyboard', hint: Text('iOS draws them, not Ghost Keyboard. The globe goes when Ghost Keyboard is the only keyboard in Settings › General › Keyboard › Keyboards. The microphone goes when Enable Dictation is off in Settings › General › Keyboard.')),
      ],
    ),
    PaneSection(
      children: [SettingRow(label: 'Watch the walkthrough again', icon: AppIcon(LucideMore.rocket, size: 18), onPress: () => openOnboarding(context, store, replay: true))],
    ),
  ];
}

// --- Look & feel --------------------------------------------------------------------------------------------------

String _iosKeyPressSummary(SettingsStore s) => [if (s.flag('vibrate')) 'Vibrate', if (s.flag('sound')) 'click'].join(', ').ifEmptyThen('Silent');

/// Vibration as on Android; sound is the system's click, so no sounds to pick, no volume and nothing to sample.
List<Widget> _iosKeyPressPart(BuildContext context, SettingsStore store) => [
      PaneSection(
        footer: const Text('Sound is the iPhone’s own keyboard click, as loud as your phone has it: there are no sound packs on iPhone.'),
        children: [
          _toggle(store, 'vibrate', 'Vibrate'),
          if (store.flag('vibrate')) _choice(store, 'haptics', 'Strength', const {'light': 'Light', 'medium': 'Medium', 'strong': 'Strong'}),
          _toggle(store, 'sound', 'Sound'),
        ],
      ),
      if (store.flag('vibrate') && !store.fullAccess) _iosFullAccess(store, 'Vibration needs it'),
    ];

String _iosLayoutSummary(SettingsStore s) {
  final wide = wideLayouts.where((l) => l.$1 == s.choice('wide_layout')).firstOrNull?.$2 ?? 'Split';
  final hand = s.choice('one_handed');
  return ['$wide on iPad', if (hand != 'off') 'one-handed $hand'].join(', ');
}

List<Widget> _iosLayoutPart(BuildContext context, SettingsStore store) => [
      PaneSection(
        title: 'On an iPad',
        footer: const Text('For screens 600 points wide or more: an iPad, or an iPhone on its side. Docked, tap the arrow on the empty side to move the keys across.'),
        children: [
          for (final (id, name, line) in wideLayouts)
            SettingRow(
              label: name,
              hint: Text(line),
              icon: store.choice('wide_layout') == id ? AppIcon(Lucide.check, size: 18) : const SizedBox(width: 18),
              onPress: () => store.set('wide_layout', id),
            ),
        ],
      ),
      PaneSection(
        title: 'On an iPhone',
        footer: const Text('The keys narrow toward one thumb. Tap the arrow on the empty side to switch hands.'),
        children: [_choice(store, 'one_handed', 'One-handed', const {'off': 'Off', 'left': 'Left hand', 'right': 'Right hand'})],
      ),
    ];

// --- Row tools ----------------------------------------------------------------------------------------------------

/// Markdown or terminal keys as one switch. Android shows them in the apps that want them, with a second switch for
/// everywhere; iOS doesn't say which app the keyboard is in, so on is everywhere: the plugin and its `_everywhere`
/// setting together.
SettingRow _iosEverywhere(SettingsStore store, String id, String label, String hint, LucideIcon icon) => SettingRow(
      label: label,
      hint: Text(hint),
      icon: AppIcon(icon, size: 18),
      control: GlacierSwitch(
        checked: store.pluginOn(id) && store.flag('${id}_everywhere'),
        onCheckedChange: (v) {
          if (v) store.set('${id}_everywhere', true);
          store.setPlugin(id, v);
        },
        ariaLabel: label,
      ),
    );

List<Widget> _iosRowToolsPage(BuildContext context, SettingsStore store) => [
      PaneSection(
        footer: const Text('Buttons in the row above the keys, whenever you aren’t mid-word. iOS doesn’t tell a keyboard which app it’s typing in, so Markdown and Terminal keys show in every app while they’re on, and nothing can be set app by app. For emoji, tap the globe: the iPhone’s own emoji keyboard is next.'),
        children: [
          _toggle(store, 'arrow_keys', 'Arrow keys', 'Left, up, down and right at the end of the row, always there; hold one to keep moving.', LucideMore.move),
          _plugin(store, 'clipboard', 'Clipboard history', 'Your last 25 copies, a tap away.', Lucide.clipboardPaste),
          _plugin(store, 'undo', 'Undo and redo', 'Back and forward arrows. They undo what this keyboard typed, not the app’s own undo.', Lucide.undo2),
          _iosEverywhere(store, 'markdown', 'Markdown', 'Bold, code, links and lists, in every app.', LucideMore.squareM),
          _iosEverywhere(store, 'terminal', 'Terminal keys', 'Esc, Tab, Ctrl, Alt and arrows, in every app.', Lucide.terminal),
          _plugin(store, 'incognito', 'Incognito switch', 'Type without the keyboard learning or keeping anything.', Lucide.eyeOff),
        ],
      ),
      if (store.pluginOn('clipboard') && !store.fullAccess) _iosFullAccess(store, 'Clipboard history needs it'),
      if (store.pluginOn('clipboard')) PaneSection(children: [SettingRow(label: 'Clear clipboard history', icon: AppIcon(LucideMore.trash2, size: 18), value: Text('${store.clipCount} saved'), onPress: store.clipCount == 0 ? null : store.clearClipboard)]),
    ];

// --- About --------------------------------------------------------------------------------------------------------

String _iosAboutSummary(SettingsStore s) => s.update['current'] == null ? 'Updates come from the App Store' : 'Version ${s.update['current']}';

/// About without the updater: the App Store updates the app.
List<Widget> _iosAboutPage(BuildContext context, SettingsStore store) {
  final current = store.update['current'];
  return [
    PaneHero(
      glyph: AppIcon(LucideMore.keyboard, size: 24),
      title: current == null ? 'Ghost Keyboard' : 'Ghost Keyboard $current',
      meta: const Text('Updates come from the App Store.'),
      status: const PaneHeroStatus('A keyboard for people who write code'),
    ),
    PaneSection(children: [
      _VersionRow(store: store),
      SettingRow(label: 'Watch the walkthrough again', icon: AppIcon(LucideMore.rocket, size: 18), onPress: () => openOnboarding(context, store, replay: true)),
    ]),
    if (store.flag('developer'))
      PaneSection(
        title: 'Developer',
        children: [
          SettingRow(label: 'Keyboard', value: Text(!store.enabled ? 'Not added' : '${store.selected ? 'Added and used' : 'Added, not used yet'}, Full Access ${store.fullAccess ? 'on' : 'off'}')),
          SettingRow(label: 'Plugins', hint: Text(store.plugins.join(', '))),
          SettingRow(
            label: 'Copy these details',
            icon: AppIcon(Lucide.copy, size: 18),
            onPress: () => Clipboard.setData(ClipboardData(text: 'Ghost Keyboard ${current ?? ''} (iOS)\nplugins: ${store.plugins.join(', ')}\nsettings: ${store.settingsJson}')),
          ),
          SettingRow(label: 'Hide developer settings', onPress: () => store.set('developer', false)),
        ],
      ),
    PaneSection(
      title: 'Made with',
      children: const [
        SettingRow(label: 'Glacier', hint: Text('The UI kit, from Ghost.md.')),
        SettingRow(label: 'Phosphor Icons and Lucide', hint: Text('The icons (MIT, ISC).')),
        SettingRow(label: 'wordfreq', hint: Text('Autocorrect’s word list (CC BY-SA 4.0).')),
      ],
    ),
  ];
}
