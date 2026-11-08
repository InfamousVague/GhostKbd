/// Every section of Ghost Keyboard's Settings and the cards on its page, in the kit's parts (kit/settings_kit.dart):
/// a [PaneSection] per card, a [SettingRow] per setting.
library;

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../art/app_icon.dart';
import '../art/lucide.g.dart';
import '../art/lucide_more.dart';
import '../market/market_screen.dart';
import '../onboarding/onboarding.dart';
import '../plugins/catalogue.dart';
import '../settings_store.dart';
import 'kit/settings_kit.dart';
import 'settings_screen.dart';
import 'theme_editor.dart';

Widget _icon(LucideIcon icon) => AppIcon(icon, size: 18, strokeWidth: 2.1);

Widget _switch(SettingsStore store, String key, String label) =>
    GlacierSwitch(checked: store.flag(key), onCheckedChange: (v) => store.set(key, v), ariaLabel: label);

SettingRow _toggle(SettingsStore store, String key, String label, [String? hint]) =>
    SettingRow(label: label, hint: hint == null ? null : Text(hint), control: _switch(store, key, label));

SettingRow _choice(SettingsStore store, String key, String label, Map<String, String> options, [String? hint]) => SettingRow(
      label: label,
      hint: hint == null ? null : Text(hint),
      layout: SettingRowLayout.stacked,
      control: GlacierSegmentedControl(
        options: [for (final e in options.entries) GlacierSegmentedOption(value: e.key, label: Text(e.value))],
        value: store.choice(key),
        onValueChange: (v) => store.set(key, v),
        fullWidth: true,
        size: GlacierSegmentedSize.sm,
        ariaLabel: label,
      ),
    );

/// Every section, in the list's order; an added plugin's page joins after the Market.
List<SettingsSection> settingsSections(SettingsStore store) => [
      SettingsSection(
        id: 'setup',
        label: 'Setup',
        icon: _icon(Lucide.circleCheck),
        hue: 'mint',
        group: 'start',
        summary: (s) => s.enabled && s.selected ? 'Ready: you’re typing with Ghost Keyboard' : (s.enabled ? 'One step left' : 'Two steps left'),
        page: _setup,
      ),
      SettingsSection(id: 'look', label: 'Look & layout', icon: _icon(LucideMore.keyboard), hue: 'indigo', group: 'keyboard', summary: _lookSummary, page: _look),
      SettingsSection(id: 'typing', label: 'Typing', icon: _icon(LucideMore.type), hue: 'blue', group: 'keyboard', summary: (s) => s.flag('auto_caps') ? 'Capitals, full stops, cursor control' : 'No auto-capitals', page: _typing),
      SettingsSection(
        id: 'keypress',
        label: 'Key press',
        icon: _icon(LucideMore.vibrate),
        hue: 'coral',
        group: 'keyboard',
        summary: (s) => [if (s.flag('vibrate')) 'Vibrate', if (s.flag('sound')) 'Sound', if (s.flag('key_popup')) 'Popup'].join(', ').ifEmptyThen('Silent'),
        page: _keyPress,
      ),
      SettingsSection(
        id: 'market',
        label: 'Plugins',
        icon: _icon(Lucide.store),
        hue: 'purple',
        group: 'plugins',
        summary: (s) => '${s.plugins.length} added · browse the Market',
        page: (context, store) => const [],
        screen: (context) => MarketScreen(store: store),
      ),
      if (store.pluginOn('autocorrect'))
        SettingsSection(id: 'autocorrect', label: 'Autocorrect', icon: _icon(Lucide.spellCheck), hue: 'lime', group: 'plugins', summary: (s) => 'On: backspace right after undoes a fix', page: _autocorrect),
      if (store.pluginOn('dictation'))
        SettingsSection(id: 'dictation', label: 'Dictation', icon: _icon(Lucide.mic), hue: 'rose', group: 'plugins', summary: _dictationSummary, page: _dictation),
      if (store.pluginOn('themes'))
        SettingsSection(
          id: 'themes',
          label: 'Themes',
          icon: _icon(Lucide.palette),
          hue: 'magenta',
          group: 'plugins',
          summary: (s) => s.choice('theme_preset') == 'custom' ? 'Your own theme' : (themePresets.where((p) => p.$1 == s.choice('theme_preset')).firstOrNull?.$2 ?? 'Gboard'),
          page: (context, store) => [ThemeEditor(store: store)],
        ),
      SettingsSection(id: 'updates', label: 'Updates', icon: _icon(Lucide.refreshCw), hue: 'cyan', group: 'app', summary: _updateSummary, page: _updates),
      SettingsSection(id: 'developer', label: 'Developer', icon: _icon(Lucide.terminal), hue: 'slate', group: 'app', summary: (s) => s.flag('tap_log') ? 'Tap log is on' : 'Tap log', page: _developer),
      SettingsSection(id: 'about', label: 'About', icon: _icon(Lucide.info), hue: 'blue', group: 'app', summary: (s) => 'Ghost Keyboard ${s.update['current'] ?? ''}'.trim(), page: _about),
    ];

extension on String {
  String ifEmptyThen(String other) => isEmpty ? other : this;
}

String _lookSummary(SettingsStore s) {
  final theme = {'system': 'System theme', 'light': 'Light', 'dark': 'Dark'}[s.choice('theme')] ?? 'System theme';
  return '$theme · ${s.choice('key_height')} keys${s.flag('number_row') ? ' · number row' : ''}';
}

String _updateSummary(SettingsStore s) => switch (s.update['phase']) {
      'upToDate' => 'Up to date',
      'available' || 'ready' => 'Version ${s.update['available']} is ready',
      'downloading' => 'Downloading…',
      _ => s.flag('auto_update') ? 'Updates itself' : 'Automatic updates off',
    };

String _dictationSummary(SettingsStore s) => switch (s.dictation['phase']) {
      'ready' => s.dictation['microphone'] == true ? 'Ready: tap the mic beside space' : 'Allow the microphone',
      'downloading' => 'Downloading the model… ${(((s.dictation['progress'] as num?) ?? 0) * 100).round()}%',
      'failed' => 'The model did not download',
      _ => 'The model is not downloaded',
    };

// --- Setup ------------------------------------------------------------------------------------------------------

List<Widget> _setup(BuildContext context, SettingsStore store) {
  final ready = store.enabled && store.selected;
  return [
    PaneHero(
      glyph: AppIcon(ready ? Lucide.circleCheck : LucideMore.keyboard, size: 24, strokeWidth: 2),
      title: ready ? 'Ready' : 'Two quick steps',
      meta: Text(ready ? 'You’re typing with Ghost Keyboard.' : 'Turn the keyboard on, then switch to it.'),
      status: PaneHeroStatus(ready ? 'Set up' : (store.enabled ? 'One step left' : 'Not turned on yet'), pulse: !ready),
    ),
    PaneSection(
      title: 'Steps',
      children: [
        SettingRow(label: '1. Turn on Ghost Keyboard', hint: const Text('In your phone’s keyboard list.'), value: Text(store.enabled ? 'Done' : 'Open'), onPress: store.openInputSettings),
        SettingRow(label: '2. Switch to Ghost Keyboard', hint: const Text('Make it the keyboard you type with.'), value: Text(store.selected ? 'Done' : 'Switch'), onPress: store.enabled ? store.showPicker : null, disabled: !store.enabled),
      ],
    ),
    PaneSection(
      title: 'Try it',
      children: const [SettingRowBox(child: GlacierInput(placeholder: 'Tap here to try the keyboard', ariaLabel: 'Try the keyboard'))],
    ),
    PaneSection(
      children: [SettingRow(label: 'Watch the walkthrough again', icon: AppIcon(LucideMore.rocket, size: 18), onPress: () => openOnboarding(context, store, replay: true))],
    ),
  ];
}

// --- Look & layout ------------------------------------------------------------------------------------------------

List<Widget> _look(BuildContext context, SettingsStore store) => [
      PaneSection(
        title: 'Theme',
        footer: store.pluginOn('themes') ? null : const Text('Presets and a theme of your own come with the Themes plugin, in the Market.'),
        children: [
          _choice(store, 'theme', 'Light or dark', const {'system': 'System', 'light': 'Light', 'dark': 'Dark'}),
          if (store.pluginOn('themes'))
            SettingRow(
              label: 'Colours',
              icon: AppIcon(Lucide.palette, size: 18),
              value: Text(store.choice('theme_preset') == 'custom' ? 'Your own' : (themePresets.where((p) => p.$1 == store.choice('theme_preset')).firstOrNull?.$2 ?? 'Gboard')),
              onPress: () => pushSettings(context, (_) => ListenableBuilder(listenable: store, builder: (context, _) => SettingsPage(title: 'Themes', hue: 'magenta', child: ThemeEditor(store: store)))),
            ),
        ],
      ),
      PaneSection(
        title: 'Keys',
        children: [
          _choice(store, 'key_height', 'Key height', const {'short': 'Short', 'normal': 'Normal', 'tall': 'Tall'}),
          _toggle(store, 'key_borders', 'Key borders', 'A shape behind every key. Off is Gboard’s look: only ?123, space and enter have one.'),
          _toggle(store, 'number_row', 'Number row', 'Digits in a row of their own above the letters.'),
          _toggle(store, 'hints', 'Symbol hints', 'Each key’s long-press character in its corner.'),
          _toggle(store, 'toolbar', 'Toolbar', 'A strip above the keys with settings and hide. Long-press { } opens settings either way.'),
        ],
      ),
    ];

// --- Typing -------------------------------------------------------------------------------------------------------

List<Widget> _typing(BuildContext context, SettingsStore store) => [
      PaneSection(
        children: [
          _toggle(store, 'auto_caps', 'Auto-capitalization', 'Shift on at the start of each sentence.'),
          _toggle(store, 'double_space_period', 'Double-space period', 'Two spaces after a word type a period and a space.'),
          _toggle(store, 'space_cursor', 'Space bar cursor control', 'Slide along the space bar to move the cursor.'),
          _choice(store, 'long_press', 'Long-press delay', const {'fast': 'Fast', 'normal': 'Normal', 'slow': 'Slow'}),
        ],
      ),
      PaneSection(
        title: 'The { } key',
        footer: const Text('Pairs leave the cursor inside; long-press any key on the { } page for its compounds (== !== => ->…).'),
        children: [SettingRow(label: 'Code symbols, pairs and snippets', icon: AppIcon(Lucide.braces, size: 18), value: const Text('Beside space'))],
      ),
    ];

// --- Key press ----------------------------------------------------------------------------------------------------

List<Widget> _keyPress(BuildContext context, SettingsStore store) => [
      PaneSection(
        children: [
          _toggle(store, 'key_popup', 'Popup on key press', 'The key you’re pressing, shown large above your finger.'),
          _toggle(store, 'vibrate', 'Vibrate on key press'),
          if (store.flag('vibrate')) _choice(store, 'haptics', 'Vibration strength', const {'light': 'Light', 'medium': 'Medium', 'strong': 'Strong'}),
          _toggle(store, 'sound', 'Sound on key press'),
        ],
      ),
    ];

// --- Plugins' own pages -------------------------------------------------------------------------------------------

List<Widget> _autocorrect(BuildContext context, SettingsStore store) => [
      PaneHero(glyph: AppIcon(Lucide.spellCheck, size: 24), title: 'Autocorrect', meta: const Text('Fixes a misspelled word when you finish it.'), status: const PaneHeroStatus('On')),
      PaneSection(
        title: 'How it works',
        footer: const Text('Its dictionary: the 40,000 commonest English words (wordfreq, CC BY-SA 4.0), their contractions and a programmer’s vocabulary.'),
        children: const [
          SettingRow(label: 'Finish a word', hint: Text('A space, or . , ! ? after a word, checks it.')),
          SettingRow(label: 'Undo a fix', hint: Text('Backspace straight after puts back exactly what you typed.')),
          SettingRow(label: 'Where it stays out', hint: Text('Passwords, web and email addresses, fields that ask for no suggestions, and words after @ # / . _ -')),
        ],
      ),
      PaneSection(children: [SettingRow(label: 'Remove Autocorrect', onPress: () => store.setPlugin('autocorrect', false))]),
    ];

List<Widget> _dictation(BuildContext context, SettingsStore store) {
  final d = store.dictation;
  final phase = d['phase'] as String? ?? 'missing';
  final progress = ((d['progress'] as num?) ?? 0).toDouble();
  final mic = d['microphone'] == true;
  final t = context.tokens;
  return [
    PaneHero(
      glyph: AppIcon(Lucide.mic, size: 24),
      title: phase == 'ready' ? (mic ? 'Ready' : 'Allow the microphone') : (phase == 'downloading' ? 'Downloading' : 'Not ready'),
      meta: Text(switch (phase) {
        'ready' => 'Tap the mic beside the space bar, talk, and tap again.',
        'downloading' => 'The speech model, 60 MB, once. ${(progress * 100).round()}%',
        'failed' => d['error'] as String? ?? 'The model did not download.',
        _ => 'The speech model is not on this phone yet.',
      }),
      status: PaneHeroStatus(phase == 'downloading' ? 'Downloading' : (phase == 'ready' && mic ? 'On' : 'Waiting'), pulse: phase == 'downloading'),
    ),
    if (phase == 'downloading') Padding(padding: EdgeInsets.symmetric(horizontal: t.space(2)), child: GlacierProgressBar(value: progress * 100, ariaLabel: 'Model download')),
    PaneSection(
      title: 'What it needs',
      children: [
        SettingRow(
          label: 'Microphone',
          hint: const Text('Only while the mic is open.'),
          icon: AppIcon(Lucide.mic, size: 18),
          value: Text(mic ? 'Allowed' : 'Allow'),
          onPress: mic ? null : store.requestMicrophone,
        ),
        SettingRow(
          label: 'Speech model',
          hint: const Text('whisper base.en, 59.7 MB, on this phone. Nothing you say leaves it.'),
          icon: AppIcon(LucideMore.hardDriveDownload, size: 18),
          value: Text(switch (phase) { 'ready' => 'Downloaded', 'downloading' => '${(progress * 100).round()}%', 'failed' => 'Try again', _ => 'Download' }),
          onPress: phase == 'missing' || phase == 'failed' ? store.downloadModel : null,
        ),
      ],
    ),
    PaneSection(
      children: [
        if (phase == 'ready') SettingRow(label: 'Delete the model', hint: const Text('Frees 60 MB; the mic key goes until it is downloaded again.'), onPress: store.deleteModel),
        SettingRow(label: 'Remove Dictation', onPress: () => store.setPlugin('dictation', false)),
      ],
    ),
  ];
}

// --- Updates ------------------------------------------------------------------------------------------------------

List<Widget> _updates(BuildContext context, SettingsStore store) {
  final u = store.update;
  final phase = u['phase'] as String? ?? 'idle';
  final available = u['available'] as String?;
  final progress = (u['progress'] as num?)?.toDouble();
  final error = u['error'] as String?;
  final needsPermission = error == 'needsPermission';
  final canInstall = phase == 'available' || phase == 'ready';
  final busy = phase == 'checking' || phase == 'downloading' || phase == 'installing';
  final line = switch (phase) {
    'disabled' => 'Updates are off in debug builds.',
    'checking' => 'Looking for a new version…',
    'upToDate' => 'You have the latest version.',
    'available' => 'Version $available is available.',
    'downloading' => 'Downloading version $available… ${((progress ?? 0) * 100).round()}%',
    'ready' when needsPermission => 'Allow Ghost Keyboard to install updates, then tap Update.',
    'ready' => 'Version $available is downloaded and ready.',
    'installing' => 'Installing version $available…',
    'failed' => error ?? 'The update did not finish.',
    _ => 'Updates come from attack.fm.',
  };
  return [
    PaneHero(
      glyph: AppIcon(Lucide.refreshCw, size: 24),
      title: 'Ghost Keyboard ${u['current'] ?? ''}',
      meta: Text(line),
      status: PaneHeroStatus(phase == 'upToDate' ? 'Up to date' : (busy ? 'Working' : (canInstall ? 'Update ready' : 'Idle')), pulse: busy),
    ),
    if (phase == 'downloading') Padding(padding: EdgeInsets.symmetric(horizontal: context.tokens.space(2)), child: GlacierProgressBar(value: (progress ?? 0) * 100, ariaLabel: 'Download')),
    PaneSection(
      children: [
        if (canInstall)
          SettingRow(label: needsPermission ? 'Allow installs' : 'Update now', onPress: store.installUpdate)
        else
          SettingRow(label: 'Check for updates', onPress: busy || phase == 'disabled' ? null : store.checkForUpdate, disabled: busy || phase == 'disabled'),
        _toggle(store, 'auto_update', 'Automatic updates', 'Look for a new version when the app opens and twice a day, and install it.'),
      ],
    ),
  ];
}

// --- Developer ----------------------------------------------------------------------------------------------------

List<Widget> _developer(BuildContext context, SettingsStore store) => [
      PaneSection(
        title: 'Tap log',
        footer: const Text('Where each tap lands and which key it typed, to tune the keyboard. Never in password fields. Turn it off when you’re done.'),
        children: [
          _toggle(store, 'tap_log', 'Tap log'),
          SettingRow(
            label: 'Copy tap log',
            icon: AppIcon(Lucide.copy, size: 18),
            onPress: () async {
              final log = await store.tapLog();
              await Clipboard.setData(ClipboardData(text: log.text));
            },
          ),
          SettingRow(label: 'Clear tap log', icon: AppIcon(LucideMore.trash2, size: 18), onPress: store.clearTapLog),
        ],
      ),
    ];

// --- About --------------------------------------------------------------------------------------------------------

List<Widget> _about(BuildContext context, SettingsStore store) => [
      PaneHero(glyph: AppIcon(LucideMore.keyboard, size: 24), title: 'Ghost Keyboard', meta: Text('Version ${store.update['current'] ?? '—'} · a keyboard for people who write code')),
      PaneSection(
        children: [
          SettingRow(label: 'Watch the walkthrough again', icon: AppIcon(LucideMore.rocket, size: 18), onPress: () => openOnboarding(context, store, replay: true)),
          SettingRow(label: 'Plugins', icon: AppIcon(Lucide.store, size: 18), onPress: () => pushSettings(context, (_) => MarketScreen(store: store))),
        ],
      ),
      PaneSection(
        title: 'Made with',
        children: const [
          SettingRow(label: 'Glacier', hint: Text('The UI kit, from Ghost.md.')),
          SettingRow(label: 'whisper.cpp', hint: Text('Speech to text on the phone (MIT).')),
          SettingRow(label: 'Lucide', hint: Text('The icons (ISC).')),
          SettingRow(label: 'wordfreq', hint: Text('Autocorrect’s word list (CC BY-SA 4.0).')),
        ],
      ),
    ];
