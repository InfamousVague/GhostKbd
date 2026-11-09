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
        summary: (s) => [if (s.flag('vibrate')) 'Vibrate', if (s.flag('key_popup')) 'Popup'].join(', ').ifEmptyThen('Nothing'),
        page: _keyPress,
      ),
      SettingsSection(
        id: 'backlight',
        label: 'Backlight',
        icon: _icon(Lucide.sparkles),
        hue: 'purple',
        group: 'keyboard',
        summary: (s) => s.flag('backlight') ? (backlightEffects.where((e) => e.$1 == s.choice('backlight_effect')).firstOrNull?.$2 ?? 'On') : 'Off',
        page: _backlight,
      ),
      // The keys' sounds: the stock sounds, Android's own, and the volume.
      SettingsSection(
        id: 'sounds-stock',
        label: 'Sounds',
        icon: _icon(Lucide.volume2),
        hue: 'amber',
        group: 'keyboard',
        summary: (s) => !s.flag('sound') ? 'Off' : stockSounds.where((p) => p.$1 == s.choice('stock_sound')).firstOrNull?.$2 ?? 'On',
        page: _soundsStock,
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
        SettingsSection(id: 'autocorrect', label: 'Autocorrect', icon: _icon(Lucide.spellCheck), hue: 'lime', group: 'plugins', summary: (s) => s.languages.map((l) => autocorrectLanguages[l] ?? l).join(', '), page: _autocorrect),
      if (store.pluginOn('dictation'))
        SettingsSection(id: 'dictation', label: 'Dictation', icon: _icon(Lucide.mic), hue: 'rose', group: 'plugins', summary: _dictationSummary, page: _dictation),
      if (store.pluginOn('themes'))
        SettingsSection(
          id: 'themes',
          label: 'Themes',
          icon: _icon(Lucide.palette),
          hue: 'magenta',
          group: 'plugins',
          summary: (s) => s.choice('theme_preset') == 'custom' ? 'Your own theme' : (themePresets.where((p) => p.$1 == s.choice('theme_preset')).firstOrNull?.$2 ?? 'Ghost'),
          page: (context, store) => [ThemeEditor(store: store)],
        ),
      if (store.pluginOn('swipe'))
        SettingsSection(id: 'swipe', label: 'Swipe typing', icon: _icon(LucideMore.pointer), hue: 'cyan', group: 'plugins', summary: (s) => 'Glide across the letters', page: _swipe),
      if (store.pluginOn('passwords'))
        SettingsSection(id: 'passwords', label: 'Password managers', icon: _icon(Lucide.keyRound), hue: 'green', group: 'plugins', summary: (s) => 'Logins above the keys', page: _passwords),
      if (store.pluginOn('gifs'))
        SettingsSection(id: 'gifs', label: 'GIFs', icon: _icon(Lucide.image), hue: 'orange', group: 'plugins', summary: (s) => 'GIPHY search', page: _gifs),
      if (store.pluginOn('markdown'))
        SettingsSection(id: 'markdown', label: 'Markdown', icon: _icon(Lucide.hash), hue: 'indigo', group: 'plugins', summary: (s) => s.flag('markdown_everywhere') ? 'Everywhere' : 'In notes apps', page: _markdown),
      if (store.pluginOn('terminal'))
        SettingsSection(id: 'terminal', label: 'Terminal mode', icon: _icon(Lucide.terminal), hue: 'slate', group: 'plugins', summary: (s) => s.flag('terminal_everywhere') ? 'Everywhere' : 'In terminal apps', page: _terminal),
      if (store.pluginOn('clipboard'))
        SettingsSection(id: 'clipboard', label: 'Clipboard history', icon: _icon(Lucide.clipboardPaste), hue: 'amber', group: 'plugins', summary: (s) => '${s.clipCount} saved', page: _clipboard),
      if (store.pluginOn('undo'))
        SettingsSection(id: 'undo', label: 'Undo and redo', icon: _icon(Lucide.undo2), hue: 'blue', group: 'plugins', summary: (s) => 'In the row', page: _undo),
      if (store.pluginOn('incognito'))
        SettingsSection(id: 'incognito', label: 'Incognito', icon: _icon(Lucide.eyeOff), hue: 'purple', group: 'plugins', summary: (s) => 'A switch in the row', page: _incognito),
      if (store.pluginOn('emoji'))
        SettingsSection(id: 'emoji', label: 'Emoji', icon: _icon(LucideMore.smile), hue: 'yellow', group: 'plugins', summary: (s) => 'Emoji 18.0', page: _emoji),
      if (store.pluginOn('ghostmd'))
        SettingsSection(id: 'ghostmd', label: 'Ghost.md', icon: _icon(Lucide.ghost), hue: 'cyan', group: 'plugins', summary: (s) => 'Notes, tags and saving', page: _ghostmd),
      if (store.pluginOn('mockingbird'))
        SettingsSection(id: 'mockingbird', label: 'Mockingbird', icon: _icon(LucideMore.bird), hue: 'purple', group: 'plugins', summary: _mockingbirdSummary, page: _mockingbird),
      if (store.pluginOn('ghostwriter'))
        SettingsSection(id: 'ghostwriter', label: 'Ghostwriter', icon: _icon(LucideMore.wandSparkles), hue: 'indigo', group: 'plugins', summary: (s) => s.ai['ready'] == true ? '<<request>> anywhere' : 'Add an AI key', page: _ghostwriter),
      SettingsSection(id: 'updates', label: 'Updates', icon: _icon(Lucide.refreshCw), hue: 'cyan', group: 'app', summary: _updateSummary, page: _updates),
      // Hidden until the version number in About is tapped five times.
      if (store.flag('developer'))
        SettingsSection(id: 'developer', label: 'Developer', icon: _icon(Lucide.terminal), hue: 'slate', group: 'app', summary: (s) => 'Versions and diagnostics', page: _developer),
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
              value: Text(store.choice('theme_preset') == 'custom' ? 'Your own' : (themePresets.where((p) => p.$1 == store.choice('theme_preset')).firstOrNull?.$2 ?? 'Ghost')),
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
          _toggle(store, 'split_keyboard', 'Split on wide screens', 'Unfolded, or on a tablet: the keys in two halves with the middle open, a phone’s key size each side.'),
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
        ],
      ),
    ];

List<Widget> _soundsStock(BuildContext context, SettingsStore store) => [
      PaneSection(children: [_toggle(store, 'sound', 'Sound on key press')]),
      if (store.flag('sound'))
        PaneSection(
          title: 'Sound',
          footer: const Text('Tap one to hear it. More sounds join this list over time.'),
          children: [
            for (final (id, name) in stockSounds)
              SettingRow(
                label: name,
                icon: store.choice('stock_sound') == id ? AppIcon(Lucide.check, size: 18) : const SizedBox(width: 18),
                onPress: () async {
                  await store.set('stock_sound', id);
                  if (id != 'system') store.sampleSound(id);
                },
              ),
          ],
        ),
      if (store.flag('sound')) PaneSection(children: [_volume(store)]),
    ];

// --- Plugins' own pages -------------------------------------------------------------------------------------------

List<Widget> _autocorrect(BuildContext context, SettingsStore store) {
  final learned = store.learnedWords;
  return [
    PaneHero(glyph: AppIcon(Lucide.spellCheck, size: 24), title: 'Autocorrect', meta: const Text('Fixes a misspelled word when you finish it, and suggests words as you type.'), status: const PaneHeroStatus('On')),
    PaneSection(children: [
      _toggle(store, 'autocorrect', 'Correct automatically', 'Off: suggestions only, nothing changes on its own.'),
      _toggle(store, 'suggestions', 'Show suggestions', 'Completions and fixes in the row above the keys. Tap one to type it.'),
    ]),
    PaneSection(
      title: 'Languages',
      footer: const Text('Turn on every language you write in: they work together, so a message can mix them. A word typed without its accents (cafe, senor) gets them.'),
      children: [
        for (final e in autocorrectLanguages.entries)
          SettingRow(
            label: e.value,
            control: GlacierSwitch(checked: store.languages.contains(e.key), onCheckedChange: (v) => store.setLanguage(e.key, v), ariaLabel: e.value),
          ),
      ],
    ),
    PaneSection(
      title: 'Learned words',
      footer: const Text('Backspace straight after a fix puts your word back and adds it here, so it isn’t changed again. Tap a word to forget it.'),
      children: [
        SettingRowBox(
          child: GlacierInput(
            key: ValueKey('learn-${learned.length}'),
            placeholder: 'Add a word',
            ariaLabel: 'Add a learned word',
            onSubmitted: (text) => store.setLearned(text, true),
          ),
        ),
        if (learned.isEmpty) const SettingRow(label: 'None yet', hint: Text('Undo a fix and the word lands here.')),
        for (final w in learned) SettingRow(label: w, hint: const Text('Tap to forget'), onPress: () => store.setLearned(w, false)),
      ],
    ),
    PaneSection(
      title: 'How it works',
      footer: const Text('Dictionaries: each language’s commonest words (wordfreq, CC BY-SA 4.0), English contractions and a programmer’s vocabulary.'),
      children: const [
        SettingRow(label: 'Finish a word', hint: Text('A space, or . , ! ? after a word, checks it.')),
        SettingRow(label: 'Where it stays out', hint: Text('Passwords, web and email addresses, fields that ask for no suggestions, and words after @ # / . _ -')),
      ],
    ),
    PaneSection(children: [SettingRow(label: 'Remove Autocorrect', onPress: () => store.setPlugin('autocorrect', false))]),
  ];
}

List<Widget> _swipe(BuildContext context, SettingsStore store) => [
      PaneHero(glyph: AppIcon(LucideMore.pointer, size: 24), title: 'Swipe typing', meta: const Text('Draw a word across the letters without lifting your thumb.'), status: const PaneHeroStatus('On')),
      PaneSection(
        title: 'How it works',
        footer: const Text('It reads your path against the words of your autocorrect languages, and words you’ve taught it.'),
        children: const [
          SettingRow(label: 'Glide and lift', hint: Text('Start on the first letter, pass over the rest, lift on the last. A space goes in before the next word.')),
          SettingRow(label: 'Not the word you meant?', hint: Text('Its other guesses sit in the row above the keys: tap one to swap it in.')),
          SettingRow(label: 'Backspace', hint: Text('Straight after a swipe, it takes the whole word.')),
          SettingRow(label: 'Taps still type', hint: Text('A short slide is still a tap: only a slide of most of a key starts a swipe.')),
        ],
      ),
      PaneSection(children: [SettingRow(label: 'Remove Swipe typing', onPress: () => store.setPlugin('swipe', false))]),
    ];

List<Widget> _passwords(BuildContext context, SettingsStore store) => [
      PaneHero(glyph: AppIcon(Lucide.keyRound, size: 24), title: 'Password managers', meta: const Text('Your password manager’s logins, a tap above the keys.'), status: const PaneHeroStatus('On')),
      PaneSection(
        title: 'Set up',
        footer: const Text('Ghost Keyboard never sees your passwords: Android draws your manager’s chips and fills the field itself. Needs Android 11 or later.'),
        children: [
          const SettingRow(label: 'Pick your manager as Android’s autofill service', hint: Text('Settings › Passwords & accounts › Autofill service (the name differs a little between phones), then NordPass, 1Password, Bitwarden…')),
          SettingRow(label: 'Open Android settings', onPress: () => store.openAutofillSettings()),
          const SettingRow(label: 'Then tap a login field', hint: Text('Its logins appear in the row above the keys. Tap one to fill it.')),
        ],
      ),
      PaneSection(children: [SettingRow(label: 'Remove Password managers', onPress: () => store.setPlugin('passwords', false))]),
    ];

List<Widget> _gifs(BuildContext context, SettingsStore store) {
  final own = store.choice('giphy_key');
  return [
    PaneHero(glyph: AppIcon(Lucide.image, size: 24), title: 'GIFs', meta: const Text('Search GIPHY and send a GIF from the keyboard.'), status: const PaneHeroStatus('On')),
    PaneSection(
      title: 'How it works',
      footer: const Text('GIFs come from GIPHY, rated PG-13 at most. Apps that take pictures get the GIF itself; others get its link.'),
      children: const [
        SettingRow(label: 'The GIF button', hint: Text('In the row above the keys whenever you aren’t mid-word. It opens trending GIFs.')),
        SettingRow(label: 'Search', hint: Text('Tap the search chip, type, and press enter. The back arrow returns to the keys.')),
      ],
    ),
    PaneSection(
      title: 'GIPHY key',
      footer: const Text('If the GIF panel asks for a key, or to use your own, paste one from developers.giphy.com and press enter.'),
      children: [
        SettingRowBox(
          child: GlacierInput(
            key: ValueKey('giphy-$own'),
            defaultValue: own,
            placeholder: 'Your GIPHY API key (optional)',
            ariaLabel: 'GIPHY API key',
            onSubmitted: (text) => store.set('giphy_key', text.trim()),
          ),
        ),
      ],
    ),
    PaneSection(children: [SettingRow(label: 'Remove GIFs', onPress: () => store.setPlugin('gifs', false))]),
  ];
}

String _megabytes(Object? bytes) {
  final b = (bytes as num?)?.toDouble() ?? 0;
  return b >= 1e9 ? '${(b / 1e9).toStringAsFixed(1)} GB' : '${(b / 1e6).round()} MB';
}

List<Widget> _rowPluginPage(SettingsStore store, {required String id, required LucideIcon icon, required String title, required String meta, List<Widget> settings = const [], required List<(String, String)> how}) => [
      PaneHero(glyph: AppIcon(icon, size: 24), title: title, meta: Text(meta), status: const PaneHeroStatus('On')),
      if (settings.isNotEmpty) PaneSection(children: settings),
      PaneSection(title: 'How it works', children: [for (final (label, hint) in how) SettingRow(label: label, hint: Text(hint))]),
      PaneSection(children: [SettingRow(label: 'Remove $title', onPress: () => store.setPlugin(id, false))]),
    ];

List<Widget> _markdown(BuildContext context, SettingsStore store) => _rowPluginPage(
      store,
      id: 'markdown',
      icon: Lucide.hash,
      title: 'Markdown',
      meta: 'Bold, code, links and lists a tap above the keys.',
      settings: [_toggle(store, 'markdown_everywhere', 'Everywhere', 'Off: only in notes apps (Ghost.md, Obsidian, Joplin, Markor, Notion, GitHub…).')],
      how: const [
        ('Wraps the selection', 'Select words and tap B: they become **bold**. With nothing selected the cursor waits inside the pair.'),
        ('Line buttons', '# • ☐ ❝ go at the start of the line you are on.'),
        ('In Ghost.md', 'A [[ ]] button links to another note.'),
      ],
    );

List<Widget> _terminal(BuildContext context, SettingsStore store) => _rowPluginPage(
      store,
      id: 'terminal',
      icon: Lucide.terminal,
      title: 'Terminal mode',
      meta: 'Esc, Tab, Ctrl, Alt and arrows for SSH and Termux.',
      settings: [_toggle(store, 'terminal_everywhere', 'Everywhere', 'Off: only in terminal apps (Termux, Termius, JuiceSSH, ConnectBot…).')],
      how: const [
        ('Sticky Ctrl and Alt', 'Tap Ctrl (it turns bold), then a letter: Ctrl+C, Ctrl+D, Ctrl+R. They let go after one key.'),
        ('Keys', 'Esc, Tab and the arrows are sent as real key presses, which terminals and vim read.'),
      ],
    );

List<Widget> _clipboard(BuildContext context, SettingsStore store) => _rowPluginPage(
      store,
      id: 'clipboard',
      icon: Lucide.clipboardPaste,
      title: 'Clipboard history',
      meta: '${store.clipCount} of your last 25 copies are saved on this phone.',
      settings: [SettingRow(label: 'Clear the history', onPress: store.clipCount == 0 ? null : store.clearClipboard)],
      how: const [
        ('Paste', 'Tap Clipboard in the row, then any copy. Something just copied waits as the first chip for a minute and a half.'),
        ('What it skips', 'Copies Android marks sensitive (password managers), and anything copied while incognito.'),
        ('Only while it is your keyboard', 'Android lets just the keyboard in use read the clipboard, so copies made under another keyboard are not seen.'),
      ],
    );

List<Widget> _undo(BuildContext context, SettingsStore store) => _rowPluginPage(
      store,
      id: 'undo',
      icon: Lucide.undo2,
      title: 'Undo and redo',
      meta: 'Take back the last edit, or put it back.',
      how: const [
        ('In the row', 'Undo and Redo show whenever you are not mid-word.'),
        ('Where it works', 'Android’s own text fields and most editors. A few apps ignore Ctrl+Z; there, nothing happens.'),
      ],
    );

List<Widget> _incognito(BuildContext context, SettingsStore store) => _rowPluginPage(
      store,
      id: 'incognito',
      icon: Lucide.eyeOff,
      title: 'Incognito',
      meta: 'Type without the keyboard learning or remembering anything.',
      how: const [
        ('The switch', 'Tap Incognito in the row; it reads “Incognito on” in bold until you tap it again.'),
        ('What stops', 'Learning words when you undo a fix or keep what you typed, and the clipboard history.'),
        ('On by itself', 'Fields that ask for privacy, like a browser’s private tab, are incognito whatever the switch says.'),
      ],
    );

/// Key sound volume, as a share of the phone's own: a whisper to loud.
SettingRow _volume(SettingsStore store) =>
    _choice(store, 'sound_volume', 'Volume', const {'10': 'Whisper', '20': 'Soft', '35': 'Medium', '55': 'Loud', '80': 'Max'}, 'How loud key sounds play, under your phone’s own volume.');

List<Widget> _emoji(BuildContext context, SettingsStore store) => _rowPluginPage(
      store,
      id: 'emoji',
      icon: LucideMore.smile,
      title: 'Emoji',
      meta: 'Every emoji in Emoji 18.0, Unicode’s latest, that your phone can draw.',
      settings: [
        _choice(store, 'emoji_skin', 'Skin tone', const {'0': '✋', '1': '✋🏻', '2': '✋🏼', '3': '✋🏽', '4': '✋🏾', '5': '✋🏿'}),
        _toggle(store, 'emoji_suggest', 'Emoji for the word you type', 'Type pizza and 🍕 is offered in the row; tap it to add it after the word.'),
      ],
      how: const [
        ('The panel', 'Tap 😀 Emoji in the row: tabs for each group, your recent ones first.'),
        ('Search', 'Tap Search emoji and type: names, keywords and shortcodes all match. The results fill the row.'),
        (':shortcodes', 'Type : and a few letters (:fire) anywhere and the matching emoji appear in the row.'),
        ('Newest emoji', 'Emoji your phone’s font doesn’t have yet are hidden rather than shown as boxes; they appear when Android updates its font.'),
      ],
    );

List<Widget> _ghostmd(BuildContext context, SettingsStore store) => _rowPluginPage(
      store,
      id: 'ghostmd',
      icon: Lucide.ghost,
      title: 'Ghost.md',
      meta: 'Your notes and the keyboard, working together.',
      how: const [
        ('[[ links', 'In Ghost.md, type [[ and your notes’ titles fill the row; tap one to finish the link, or New to make one.'),
        ('# tags', 'Type # and a letter for the tags you use.'),
        ('Blocks', 'Callout, Table, Math, Diagram, Today, ☐ and — insert Ghost.md’s own blocks; Voice note opens Ghost.md’s recorder.'),
        ('Save to Ghost.md', 'In any other app, select text: Save to Ghost.md sends it there as a note.'),
        ('Where titles come from', 'The links and tags you type in Ghost.md are remembered on this phone. When Ghost.md shares its whole list with the keyboard, every note shows.'),
      ],
    );

// --- Mockingbird and Ghostwriter ------------------------------------------------------------------------------------

String _mockingbirdSummary(SettingsStore s) {
  if (s.ai['training'] == true) return 'Training…';
  if (s.ai['profile'] != null) return 'Trained';
  return '${s.ai['samples'] ?? 0} samples';
}

String _when(Object? ms) {
  if (ms is! num) return '';
  final d = DateTime.now().difference(DateTime.fromMillisecondsSinceEpoch(ms.toInt()));
  if (d.inMinutes < 2) return 'just now';
  if (d.inHours < 1) return '${d.inMinutes} minutes ago';
  if (d.inDays < 1) return '${d.inHours} hours ago';
  return d.inDays == 1 ? 'yesterday' : '${d.inDays} days ago';
}

/// The AI both plugins use: which provider, its key, its model, and a test. One setting for both pages.
List<Widget> _aiSetup(SettingsStore store) {
  final providers = [for (final p in (store.ai['providers'] as List? ?? const [])) if (p is Map) p];
  final chosen = store.choice('ai_provider');
  final provider = providers.where((p) => p['id'] == chosen).firstOrNull;
  final name = provider?['name'] as String? ?? 'Claude';
  final model = store.text('ai_model_$chosen');
  final defaultModel = provider?['defaultModel'] as String? ?? '';
  return [
    PaneSection(
      title: 'AI',
      footer: const Text('Mockingbird and Ghostwriter share this. Requests go straight from your phone to the provider with your own key; Ghost Keyboard has no server in between.'),
      children: [
        for (final p in providers)
          SettingRow(
            label: p['name'] as String,
            hint: Text(p['id'] == 'custom' ? 'Any OpenAI-compatible https endpoint: Ollama, LM Studio, vLLM.' : 'Key from ${p['keyFrom']}'),
            value: p['id'] == chosen ? const Text('Chosen') : null,
            onPress: p['id'] == chosen ? null : () => store.set('ai_provider', p['id'] as String),
          ),
      ],
    ),
    PaneSection(
      title: '$name key',
      footer: Text(chosen == 'custom' ? 'Optional for a server that asks for none.' : 'Kept in this app’s private storage on your phone. Copy settings in Developer never includes it.'),
      children: [
        if (chosen == 'custom')
          SettingRowBox(
            child: GlacierInput(
              key: const ValueKey('ai-base-url'),
              type: GlacierInputType.url,
              defaultValue: store.text('ai_base_url'),
              placeholder: 'https://your-server/v1',
              ariaLabel: 'Server address',
              onChange: (text) => store.set('ai_base_url', text.trim()),
            ),
          ),
        SettingRowBox(
          child: GlacierInput(
            key: ValueKey('ai-key-$chosen'),
            type: GlacierInputType.password,
            defaultValue: store.text('ai_key_$chosen'),
            placeholder: 'Paste your $name API key',
            ariaLabel: '$name API key',
            autoCorrect: false,
            onChange: (text) => store.set('ai_key_$chosen', text.trim()),
          ),
        ),
      ],
    ),
    PaneSection(
      title: 'Model',
      footer: Text(model.isEmpty ? 'Using $defaultModel until you pick another. Type a model’s id and press enter, or load the list.' : 'Type a model’s id and press enter, or load the list.'),
      children: [
        SettingRowBox(
          child: GlacierInput(
            key: ValueKey('ai-model-$chosen-$model'),
            defaultValue: model,
            placeholder: defaultModel.isEmpty ? 'Model id' : defaultModel,
            ariaLabel: 'Model',
            autoCorrect: false,
            onSubmitted: (text) => store.set('ai_model_$chosen', text.trim()),
          ),
        ),
        SettingRow(label: store.aiBusy ? 'Working…' : 'Load $name’s models', onPress: store.aiBusy ? null : store.fetchAiModels),
        for (final m in store.aiModels.take(40))
          SettingRow(label: m, value: m == (model.isEmpty ? defaultModel : model) ? const Text('Chosen') : null, onPress: () => store.set('ai_model_$chosen', m)),
        SettingRow(label: 'Test it', hint: const Text('Sends one tiny request.'), onPress: store.aiBusy ? null : store.testAi),
        if (store.aiMessage != null) SettingRow(label: store.aiMessage!),
      ],
    ),
  ];
}

List<Widget> _mockingbird(BuildContext context, SettingsStore store) {
  final ai = store.ai;
  final profile = ai['profile'] is Map ? ai['profile'] as Map : null;
  final samples = ai['samples'] as int? ?? 0;
  final training = ai['training'] == true || store.aiBusy;
  return [
    PaneHero(
      glyph: AppIcon(LucideMore.bird, size: 24),
      title: profile != null ? 'Trained on your voice' : 'Learning your voice',
      meta: Text(profile != null
          ? 'Profile from ${profile['samples']} samples, ${_when(profile['at'])}, by ${profile['provider']} (${profile['model']}). $samples samples now: ${ai['chatSamples']} texts, ${ai['writingSamples']} writing.'
          : '$samples samples so far: ${ai['chatSamples'] ?? 0} texts, ${ai['writingSamples'] ?? 0} writing. It trains once there are ${samples < 50 ? 50 : 10} or so and an AI key is set.'),
      status: PaneHeroStatus(training ? 'Training' : (profile != null ? 'On' : 'Learning'), pulse: training),
    ),
    PaneSection(
      title: 'Learning',
      footer: const Text('Never from passwords, email or web addresses, names, searches or number fields, nor while incognito.'),
      children: [
        _toggle(store, 'mockingbird_collect', 'Keep samples of what I type', 'A message sent, or a field you leave, is kept on this phone (the last 1,500).'),
        _toggle(store, 'mockingbird_auto_train', 'Retrain by itself', 'At most once a day, after 50 new samples. One small request each time.'),
        SettingRow(
          label: training ? 'Training…' : 'Train now',
          hint: const Text('Sends a selection of your newest samples, once, to make the profile.'),
          onPress: training || samples < 10 ? null : store.trainMockingbird,
        ),
        if (ai['error'] is String && !training) SettingRow(label: ai['error'] as String),
      ],
    ),
    if (profile != null)
      PaneSection(
        title: 'Your style profile',
        footer: const Text('What the AI is told about how you write. It describes style only, not private facts.'),
        children: [SettingRowBox(child: Text(profile['text'] as String? ?? ''))],
      ),
    ..._aiSetup(store),
    PaneSection(
      title: 'How it works',
      children: const [
        SettingRow(label: 'Texting and writing', hint: Text('Chat apps (WhatsApp, Signal, Messages, Slack, Discord…) teach your texting; everything else your writing.')),
        SettingRow(label: 'In my voice', hint: Text('Select text anywhere and tap In my voice in the row: it is rewritten as you.')),
        SettingRow(label: 'A profile, not fine-tuning', hint: Text('The same profile works with every provider, and switching loses nothing.')),
      ],
    ),
    PaneSection(children: [
      SettingRow(label: 'Delete samples and profile', onPress: samples == 0 && profile == null ? null : store.clearMockingbird),
      SettingRow(label: 'Remove Mockingbird', onPress: () => store.setPlugin('mockingbird', false)),
    ]),
  ];
}

List<Widget> _ghostwriter(BuildContext context, SettingsStore store) {
  final ready = store.ai['ready'] == true;
  return [
    PaneHero(
      glyph: AppIcon(LucideMore.wandSparkles, size: 24),
      title: ready ? 'Ready' : 'Add an AI key',
      meta: const Text('Type <<what you want>> in any app. Close it with >> and the AI writes it there, as you.'),
      status: PaneHeroStatus(ready ? 'On' : 'Waiting'),
    ),
    PaneSection(
      children: [
        _toggle(store, 'ghostwriter_auto', 'Fill when I type >>', 'Off: tap Fill in the row when the cursor is on a request.'),
        _toggle(store, 'ghostwriter_memory', 'Answer from my past messages', store.pluginOn('mockingbird') ? 'The samples Mockingbird keeps that share the request’s words.' : 'Add Mockingbird to keep samples; until then only the field’s own text.'),
        _toggle(store, 'ghostwriter_clipboard', 'Read my recent clipboard', store.pluginOn('clipboard') ? 'Your last few copies go with the request.' : 'Needs the Clipboard history plugin.'),
      ],
    ),
    ..._aiSetup(store),
    PaneSection(
      title: 'How it works',
      children: const [
        SettingRow(label: '<<engineering standup tldr>>', hint: Text('Something to write: it writes it, from what you have typed lately.')),
        SettingRow(label: '<<why do the metrics not fire on signup>>', hint: Text('A question: it writes the answer you would give. Facts it doesn’t have become [blanks] to fill.')),
        SettingRow(label: 'While it writes', hint: Text('The row shows Writing… with ✕ to stop. Keep typing elsewhere and the answer waits in the row to insert.')),
        SettingRow(label: 'Never', hint: Text('In password fields or while incognito.')),
      ],
    ),
    PaneSection(children: [SettingRow(label: 'Remove Ghostwriter', onPress: () => store.setPlugin('ghostwriter', false))]),
  ];
}

List<Widget> _backlight(BuildContext context, SettingsStore store) {
  final t = context.tokens;
  return [
    PaneSection(
      footer: const Text('Letters lit from behind as you type, like shine-through keycaps. Never in password fields. Try it in the field below.'),
      children: [_toggle(store, 'backlight', 'Backlight')],
    ),
    if (store.flag('backlight')) ...[
    PaneSection(
      children: [
        SettingRowBox(
          child: GlacierSegmentedControl(
            options: const [GlacierSegmentedOption(value: 'brighten', label: Text('Brighten')), GlacierSegmentedOption(value: 'dim', label: Text('Dim'))],
            value: store.choice('backlight_mode'),
            onValueChange: (v) => store.set('backlight_mode', v),
            fullWidth: true,
            size: GlacierSegmentedSize.sm,
            ariaLabel: 'Brighten or dim',
          ),
        ),
      ],
      footer: Text(store.choice('backlight_mode') == 'dim'
          ? 'Like shine-through keycaps: the letters the effect touches fade, then come back. The keys stay as they are.'
          : 'Like shine-through keycaps: the letters the effect touches take on the colour below, then fade back. The keys stay as they are.'),
    ),
    PaneSection(
      title: 'Effect',
      children: [
        for (final (id, name, line) in backlightEffects)
          SettingRow(
            label: name,
            hint: Text(line),
            icon: store.choice('backlight_effect') == id ? AppIcon(Lucide.check, size: 18) : const SizedBox(width: 18),
            onPress: () => store.set('backlight_effect', id),
          ),
      ],
    ),
    if (store.choice('backlight_mode') != 'dim')
    PaneSection(
      title: 'Colour',
      footer: const Text('Primary is your theme’s own colour; with the heatmap it runs cool to hot.'),
      children: [
        SettingRowBox(
          child: Wrap(
            spacing: t.space(3),
            runSpacing: t.space(3),
            children: [
              for (final (id, name, colour) in backlightColours)
                GlacierPressable(
                  onPressed: () => store.set('backlight_colour', id),
                  semanticLabel: name,
                  builder: (context, state) => Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 36,
                        height: 36,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: colour > 0 ? Color(colour) : (colour == 0 ? t.color('glacier-accent-solid') : null),
                          gradient: colour == -1 ? const SweepGradient(colors: [Color(0xFFFF4D4D), Color(0xFFFFD84D), Color(0xFF4DFF88), Color(0xFF4DC3FF), Color(0xFFB04DFF), Color(0xFFFF4D4D)]) : null,
                          border: Border.all(color: store.choice('backlight_colour') == id ? t.color('app-ink') : t.color('app-rule'), width: store.choice('backlight_colour') == id ? 2.5 : 1),
                        ),
                      ),
                      SizedBox(height: t.space(1)),
                      Text(name, style: TextStyle(fontSize: 12, color: t.color('glacier-text-muted'))),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ],
    ),
    PaneSection(
      children: [
        _choice(store, 'backlight_strength', store.choice('backlight_mode') == 'dim' ? 'How dark' : 'Brightness', const {'low': 'Low', 'medium': 'Medium', 'high': 'High'}),
        _choice(store, 'backlight_fade', 'Fade', const {'fast': 'Fast', 'medium': 'Medium', 'slow': 'Slow'}),
      ],
      footer: const Text('Fade is how long a key stays lit after you press it: about half a second, a second, or two.'),
    ),
    ],
  ];
}

List<Widget> _dictation(BuildContext context, SettingsStore store) {
  final d = store.dictation;
  final phase = d['phase'] as String? ?? 'missing';
  final progress = ((d['progress'] as num?) ?? 0).toDouble();
  final mic = d['microphone'] == true;
  final t = context.tokens;
  final models = [for (final m in (d['models'] as List? ?? const [])) if (m is Map) m];
  final activeId = d['model'] as String? ?? 'base.en';
  final active = models.where((m) => m['id'] == activeId).firstOrNull;
  final multilingual = active?['multilingual'] == true;
  final language = d['language'] as String? ?? 'auto';
  final languages = [for (final l in (d['languages'] as List? ?? const [])) if (l is Map) (code: l['code'] as String, name: l['name'] as String)];
  return [
    PaneHero(
      glyph: AppIcon(Lucide.mic, size: 24),
      title: phase == 'ready' ? (mic ? 'Ready' : 'Allow the microphone') : (phase == 'downloading' ? 'Downloading' : 'Not ready'),
      meta: Text(switch (phase) {
        'ready' => 'Tap the mic beside the space bar, talk, and tap again. ${active?['name'] ?? ''}.',
        'downloading' => '${active?['name'] ?? 'The speech model'}, ${_megabytes(d['bytes'])}, once. ${(progress * 100).round()}%',
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
      ],
    ),
    PaneSection(
      title: 'Speech model',
      footer: const Text('Models run on this phone: nothing you say leaves it. Tap one to use it; it downloads first if it isn’t here. Bigger is more accurate and slower.'),
      children: [
        for (final m in models)
          SettingRow(
            label: m['name'] as String,
            hint: Text('${m['note']}  ${_megabytes(m['bytes'])}${m['error'] != null ? ' · ${m['error']}' : ''}'),
            icon: AppIcon(m['id'] == activeId ? Lucide.circleCheck : LucideMore.hardDriveDownload, size: 18),
            value: Text(switch (m) {
              _ when m['downloading'] == true => '${(((m['progress'] as num?) ?? 0) * 100).round()}%',
              _ when m['id'] == activeId && m['installed'] == true => 'In use',
              _ when m['installed'] == true => 'Use',
              _ => 'Get',
            }),
            onPress: m['id'] == activeId && m['installed'] == true ? null : () => store.useModel(m['id'] as String),
          ),
      ],
    ),
    PaneSection(
      title: 'Language',
      footer: Text(multilingual
          ? 'Detect it works out the language from your first few words. Choosing yours is a little faster and surer.'
          : 'This model hears English only. Pick an “all languages” model above to dictate in others.'),
      children: [
        for (final l in languages)
          SettingRow(
            label: l.name,
            disabled: !multilingual && l.code != 'en',
            icon: (multilingual ? language == l.code : l.code == 'en') ? AppIcon(Lucide.check, size: 18) : null,
            onPress: multilingual && language != l.code ? () => store.setDictationLanguage(l.code) : null,
          ),
      ],
    ),
    PaneSection(
      title: 'Downloaded',
      children: [
        for (final m in models)
          if (m['installed'] == true && m['id'] != activeId)
            SettingRow(label: 'Delete ${m['name']}', hint: Text('Frees ${_megabytes(m['bytes'])}.'), onPress: () => store.deleteModelId(m['id'] as String)),
        if (phase == 'ready') SettingRow(label: 'Delete the model in use', hint: const Text('The mic key goes until a model is downloaded again.'), onPress: store.deleteModel),
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

List<Widget> _developer(BuildContext context, SettingsStore store) {
  final u = store.update;
  return [
    PaneSection(
      title: 'This build',
      children: [
        SettingRow(label: 'Version', value: Text('${u['current'] ?? '—'}')),
        SettingRow(label: 'Keyboard', value: Text(store.enabled ? (store.selected ? 'On and in use' : 'On, not in use') : 'Not turned on')),
        SettingRow(label: 'Plugins', hint: Text(store.plugins.join(', '))),
        SettingRow(
          label: 'Copy these details',
          icon: AppIcon(Lucide.copy, size: 18),
          onPress: () => Clipboard.setData(ClipboardData(text: 'Ghost Keyboard ${u['current'] ?? ''}\nplugins: ${store.plugins.join(', ')}\nsettings: ${store.settingsJson}')),
        ),
      ],
    ),
    PaneSection(children: [SettingRow(label: 'Hide developer settings', onPress: () { store.set('developer', false); Navigator.of(context).maybePop(); })]),
  ];
}

// --- About --------------------------------------------------------------------------------------------------------

List<Widget> _about(BuildContext context, SettingsStore store) => [
      PaneHero(glyph: AppIcon(LucideMore.keyboard, size: 24), title: 'Ghost Keyboard', meta: const Text('A keyboard for people who write code')),
      PaneSection(children: [_VersionRow(store: store)]),
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
          SettingRow(label: 'Phosphor Icons', hint: Text('The keyboard’s icons (MIT).')),
          SettingRow(label: 'Lucide', hint: Text('The icons (ISC).')),
          SettingRow(label: 'wordfreq', hint: Text('Autocorrect’s word list (CC BY-SA 4.0).')),
        ],
      ),
    ];

/// About's version number. Tapped five times in a row it shows Settings › Developer, as Android's build number does.
class _VersionRow extends StatefulWidget {
  const _VersionRow({required this.store});
  final SettingsStore store;

  @override
  State<_VersionRow> createState() => _VersionRowState();
}

class _VersionRowState extends State<_VersionRow> {
  int _taps = 0;
  DateTime _last = DateTime(0);

  @override
  Widget build(BuildContext context) {
    final store = widget.store;
    final unlocked = store.flag('developer');
    final left = 5 - _taps;
    return SettingRow(
      label: 'Version',
      value: Text('${store.update['current'] ?? '—'}'),
      hint: unlocked ? const Text('Developer settings are on') : (_taps >= 2 ? Text('$left more ${left == 1 ? 'tap' : 'taps'} for developer settings') : null),
      onPress: unlocked
          ? null
          : () {
              final now = DateTime.now();
              setState(() {
                _taps = now.difference(_last) < const Duration(seconds: 2) ? _taps + 1 : 1;
                _last = now;
              });
              if (_taps >= 5) {
                HapticFeedback.mediumImpact();
                store.set('developer', true);
              }
            },
    );
  }
}
