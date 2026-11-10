/// Every section of Ghost Keyboard's Settings and the cards on its page, in the kit's parts (kit/settings_kit.dart):
/// a [PaneSection] per card, a [SettingRow] per setting.
library;

import 'dart:async';

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

SettingRow _toggle(SettingsStore store, String key, String label, [String? hint, LucideIcon? icon]) =>
    SettingRow(label: label, hint: hint == null ? null : Text(hint), icon: icon == null ? null : AppIcon(icon, size: 18), control: _switch(store, key, label));

SettingRow _choice(SettingsStore store, String key, String label, Map<String, String> options, [String? hint, LucideIcon? icon]) => SettingRow(
      label: label,
      hint: hint == null ? null : Text(hint),
      icon: icon == null ? null : AppIcon(icon, size: 18),
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
      SettingsSection(id: 'typing', label: 'Typing', icon: _icon(LucideMore.type), hue: 'blue', group: 'keyboard', summary: _typingSummary, page: _typingPage, parts: _typingParts),
      SettingsSection(id: 'look', label: 'Look & feel', icon: _icon(LucideMore.keyboard), hue: 'indigo', group: 'keyboard', summary: _lookSummary, page: _lookPage, parts: _lookParts),
      SettingsSection(id: 'ai', label: 'AI', icon: _icon(Lucide.sparkles), hue: 'purple', group: 'keyboard', summary: _aiSummary, page: _aiPage, parts: _aiParts),
      SettingsSection(id: 'rowtools', label: 'Row tools', icon: _icon(Lucide.clipboardPaste), hue: 'amber', group: 'keyboard', summary: _rowSummary, page: _rowToolsPage),
      SettingsSection(id: 'apps', label: 'Per-app profiles', icon: _icon(Lucide.smartphone), hue: 'cyan', group: 'keyboard', summary: _appsSummary, page: _appsPage, parts: _appsParts),
      SettingsSection(
        id: 'market',
        label: 'Plugins',
        icon: _icon(Lucide.store),
        hue: 'purple',
        group: 'plugins',
        summary: (s) => '${marketCatalogue.where((p) => s.pluginOn(p.id)).length} added · browse the Market',
        page: (context, store) => const [],
        screen: (context) => MarketScreen(store: store),
      ),
      if (store.pluginOn('dictation'))
        SettingsSection(id: 'dictation', label: 'Dictation', icon: _icon(Lucide.mic), hue: 'rose', group: 'plugins', summary: _dictationSummary, page: _dictation),
      if (store.pluginOn('passwords'))
        SettingsSection(id: 'passwords', label: 'Password managers', icon: _icon(Lucide.keyRound), hue: 'green', group: 'plugins', summary: (s) => 'Logins above the keys', page: _passwords),
      if (store.pluginOn('mockingbird'))
        SettingsSection(id: 'mockingbird', label: 'Mockingbird', icon: _icon(LucideMore.bird), hue: 'magenta', group: 'plugins', summary: _mockingbirdSummary, page: _mockingbirdPage, parts: _mockingbirdParts),
      if (store.pluginOn('ghostwriter'))
        SettingsSection(id: 'ghostwriter', label: 'Ghostwriter', icon: _icon(LucideMore.wandSparkles), hue: 'indigo', group: 'plugins', summary: _ghostwriterSummary, page: _ghostwriterPage, parts: _ghostwriterParts),
      if (store.pluginOn('rewrite'))
        SettingsSection(id: 'rewrite', label: 'Rewrite', icon: _icon(Lucide.penLine), hue: 'mint', group: 'plugins', summary: _rewriteSummary, page: _rewritePage, parts: (s) => [_aiProviderPart()]),
      if (store.pluginOn('ghostmd'))
        SettingsSection(id: 'ghostmd', label: 'Ghost.md', icon: _icon(Lucide.ghost), hue: 'cyan', group: 'plugins', summary: (s) => 'Notes, tags and saving', page: _ghostmd),
      SettingsSection(id: 'about', label: 'About', icon: _icon(Lucide.info), hue: 'blue', group: 'app', summary: _updateSummary, page: _aboutPage),
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

// --- Typing -------------------------------------------------------------------------------------------------------

// --- Key press ----------------------------------------------------------------------------------------------------

// --- Plugins' own pages -------------------------------------------------------------------------------------------

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

/// Key sound volume, as a share of the phone's own: a whisper to loud.
SettingRow _volume(SettingsStore store) =>
    _choice(store, 'sound_volume', 'Volume', const {'10': 'Whisper', '20': 'Soft', '35': 'Medium', '55': 'Loud', '80': 'Max'}, 'How loud key sounds play, under your phone’s own volume.');

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

// --- Developer ----------------------------------------------------------------------------------------------------

// --- About --------------------------------------------------------------------------------------------------------

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

// --- The simplified pages (2026-11-09: "simplify the settings and minimize what we have") ------------------------
//
// Six top pages where there were twenty-two: Typing, Look & feel, AI, Row tools, About, and pages only for
// the plugins that need setting up (Mockingbird, Ghostwriter, Dictation, Password managers, Ghost.md). A page with
// parts shows its main switches, then its parts as a list like the home list's ("if I click on ghostwriter I see
// another settings sidebar with the ghostwriter parts"). The plugins every keyboard has (autocorrect, swipe, adaptive
// keys, the row's buttons) are switches on these pages, not Market items. Each "clear" sits beside what it clears.

/// A plugin as a switch: on adds it, off removes it.
SettingRow _plugin(SettingsStore store, String id, String label, String hint, [LucideIcon? icon]) => SettingRow(
      label: label,
      hint: Text(hint),
      icon: icon == null ? null : AppIcon(icon, size: 18),
      control: GlacierSwitch(checked: store.pluginOn(id), onCheckedChange: (v) => store.setPlugin(id, v), ariaLabel: label),
    );

/// Rarely changed settings: a More row that opens them in place, and Fewer to fold them again.
List<Widget> _more(SettingsStore store, String id, List<Widget> sections) {
  store.moreSeen.add(id);
  // Read open while the search reads the pages, so what waits behind More is found too.
  if (store.indexing || store.moreOpen.contains(id)) {
    return [...sections, PaneSection(children: [SettingRow(label: 'Fewer settings', onPress: () => store.toggleMore(id))])];
  }
  return [PaneSection(children: [SettingRow(label: 'More settings', icon: AppIcon(LucideMore.slidersHorizontal, size: 18), onPress: () => store.toggleMore(id))])];
}

/// A part of a page: a section of its own in the page's list.
SettingsSection _part(String id, String label, LucideIcon icon, String hue, String Function(SettingsStore) summary, List<Widget> Function(BuildContext, SettingsStore) page) =>
    SettingsSection(id: id, label: label, icon: _icon(icon), hue: hue, group: 'parts', summary: summary, page: page);

String _typingSummary(SettingsStore s) =>
    [if (s.pluginOn('autocorrect')) 'Autocorrect', if (s.pluginOn('swipe')) 'swipe', if (s.pluginOn('adaptive')) 'adaptive keys'].join(', ').ifEmptyThen('Plain typing');

String _aiSummary(SettingsStore s) {
  if (s.ai['ready'] != true) return 'Add an AI key';
  return [_providerName(s), if (s.pluginOn('mockingbird')) 'Mockingbird', if (s.pluginOn('ghostwriter')) 'Ghostwriter', if (s.pluginOn('rewrite')) 'Rewrite'].join(', ');
}

String _mockingbirdSummary(SettingsStore s) => s.ai['ready'] != true
    ? 'Add an AI key'
    : (_profileOutdated(s) ? _retrainHint : (s.ai['profile'] != null ? 'Your style, trained ${_when((s.ai['profile'] as Map)['at'])}' : 'Learning your style: ${s.ai['samples'] ?? 0} samples'));

/// What a style profile trained under the old instructions says on its rows (Mockingbird.outdated): those asked the AI
/// for the "typos they leave in", so a slip could be learned as a habit. With an AI key and "Retrain by itself" on it
/// retrains the next time a text field is left (once, whatever the daily and 50-sample rule says, and a day later if
/// that fails: Retrain.kt); this is for when it can't.
const _retrainHint = 'Retrain to pick up the improved style rules';

/// The style profile was trained before slips were told from habits (`profile.outdated` from MainActivity.aiState).
bool _profileOutdated(SettingsStore s) => s.ai['profile'] is Map && (s.ai['profile'] as Map)['outdated'] == true;

String _ghostwriterSummary(SettingsStore s) => s.ai['ready'] != true ? 'Add an AI key' : '<<requests>>, ${_sourcesSummary(s).toLowerCase()}';

const _rowIds = ['clipboard', 'undo', 'emoji', 'gifs', 'markdown', 'terminal', 'incognito'];

String _rowSummary(SettingsStore s) {
  final on = _rowIds.where(s.pluginOn).length + (s.flag('arrow_keys') ? 1 : 0);
  return on == 0 ? 'None' : '$on of ${_rowIds.length + 1} on';
}

// --- Typing -------------------------------------------------------------------------------------------------------

List<Widget> _typingPage(BuildContext context, SettingsStore store) => [
      PaneSection(
        children: [
          _plugin(store, 'autocorrect', 'Autocorrect', 'Fixes a misspelled word as you finish it; backspace straight after puts yours back.'),
          _plugin(store, 'swipe', 'Swipe typing', 'Draw a word across the letters; its other guesses wait in the row.'),
          _plugin(store, 'adaptive', 'Adaptive keys', 'Nudges a key toward your slips, slowly, and lets it back as your aim comes right.'),
          _toggle(store, 'auto_caps', 'Capitals at sentence starts'),
          _toggle(store, 'space_cursor', 'Slide the space bar to move the cursor', 'Sideways by characters; up or down by lines. Hold it first and it never types a space. Off: holding it opens the keyboard picker.'),
          _toggle(store, 'delete_swipe', 'Slide backspace to delete words', 'Slide left from backspace: each stretch takes a word.'),
        ],
      ),
    ];

List<SettingsSection> _typingParts(SettingsStore store) => [
      if (store.pluginOn('autocorrect'))
        _part('autocorrect', 'Autocorrect', Lucide.spellCheck, 'lime', (s) => s.languages.map((l) => autocorrectLanguages[l] ?? l).join(', '), _autocorrectPart),
      if (store.pluginOn('adaptive'))
        _part('adaptive', 'Adaptive keys', LucideMore.target, 'coral', (s) => '${s.adaptive['keys'] ?? 0} keys nudged, ${s.adaptive['mistakes'] ?? 0} slips', _adaptivePart),
      _part('stats', 'Typing stats', Lucide.gauge, 'mint', _statsSummary, _statsPart),
      _part('typing-more', 'More typing', LucideMore.slidersHorizontal, 'slate', (s) => 'Full stops, long press, the { } key', _typingMorePart),
    ];

List<Widget> _autocorrectPart(BuildContext context, SettingsStore store) {
  final learned = store.learnedWords;
  return [
    PaneSection(children: [
      _toggle(store, 'autocorrect', 'Correct on its own', 'Off: it only suggests.'),
      _toggle(store, 'suggestions', 'Suggestions above the keys'),
      _toggle(store, 'slang', 'Texting slang', 'Leaves lmfao, ngl, ts, sybau and the like alone, and suggests them.'),
    ]),
    PaneSection(
      title: 'Languages',
      footer: const Text('They work together, so a message can mix them.'),
      children: [
        for (final e in autocorrectLanguages.entries)
          SettingRow(label: e.value, control: GlacierSwitch(checked: store.languages.contains(e.key), onCheckedChange: (v) => store.setLanguage(e.key, v), ariaLabel: e.value)),
      ],
    ),
    PaneSection(
      title: 'Learned words',
      footer: const Text('Words it leaves alone. Undoing a fix adds the word here; tap one to forget it.'),
      children: [
        SettingRowBox(child: GlacierInput(key: ValueKey('learn-${learned.length}'), placeholder: 'Add a word', ariaLabel: 'Add a learned word', onSubmitted: (text) => store.setLearned(text, true))),
        for (final w in learned) SettingRow(label: w, hint: const Text('Tap to forget'), onPress: () => store.setLearned(w, false)),
      ],
    ),
  ];
}

List<Widget> _adaptivePart(BuildContext context, SettingsStore store) {
  final a = store.adaptive;
  final keys = a['keys'] as int? ?? 0;
  final slips = a['mistakes'] as int? ?? 0;
  final common = [for (final c in (a['common'] as List? ?? const [])) '$c'];
  return [
    PaneSection(
      footer: Text(
        '${slips == 0 ? 'Type as usual.' : 'Most often: ${common.join(', ')}.'} '
        'A key moves a little for each slip, about ten before you feel it, and never more than a third of a key. '
        'Clean touches let it back, and every nudge halves in two weeks, so the keys return to their places as your aim does.',
      ),
      children: [
        _toggle(store, 'adaptive_map', 'Show the heatmap on the keyboard', 'Red glows are your slips; a dot on a line is where a key now listens.'),
        SettingRow(label: 'Helping now', value: Text('$keys ${keys == 1 ? 'key' : 'keys'} nudged, $slips slips remembered')),
      ],
    ),
    PaneSection(children: [SettingRow(label: 'Forget what it learned', onPress: keys == 0 && slips == 0 ? null : store.resetAdaptive)]),
  ];
}

List<Widget> _typingMorePart(BuildContext context, SettingsStore store) => [
      PaneSection(children: [
        _toggle(store, 'double_space_period', 'Double space types a full stop'),
        _choice(store, 'long_press', 'Long-press delay', const {'fast': 'Fast', 'normal': 'Normal', 'slow': 'Slow'}),
      ]),
      PaneSection(
        title: 'The { } key',
        footer: const Text('Pairs leave the cursor inside; long-press a key on the { } page for its compounds (== !== => ->…).'),
        children: [SettingRow(label: 'Code symbols, pairs and snippets', icon: AppIcon(Lucide.braces, size: 18), value: const Text('Beside space'))],
      ),
    ];

// --- Look & feel --------------------------------------------------------------------------------------------------

List<Widget> _lookPage(BuildContext context, SettingsStore store) => [
      PaneSection(children: [
        _choice(store, 'theme', 'Light or dark', const {'system': 'System', 'light': 'Light', 'dark': 'Dark'}),
        _choice(store, 'key_height', 'Key height', const {'condensed': 'Condensed', 'short': 'Short', 'normal': 'Normal', 'tall': 'Tall'}, 'Condensed takes about a fifth less of the screen: shorter rows, smaller letters and a slimmer row above.'),
      ]),
    ];

List<SettingsSection> _lookParts(SettingsStore store) => [
      _part('colours', 'Colours', Lucide.palette, 'magenta', (s) => s.pluginOn('themes') ? (s.choice('theme_preset') == 'custom' ? 'Your own' : (themePresets.where((p) => p.$1 == s.choice('theme_preset')).firstOrNull?.$2 ?? 'Ghost')) : 'Standard', (context, store) => [ThemeEditor(store: store)]),
      _part('backlight', 'Backlight', Lucide.sparkles, 'purple', (s) => s.flag('backlight') ? (backlightEffects.where((e) => e.$1 == s.choice('backlight_effect')).firstOrNull?.$2 ?? 'On') : 'Off', _backlight),
      _part('keys', 'Keys', LucideMore.keyboard, 'indigo', (s) => [if (s.flag('number_row')) 'Number row', if (s.flag('hints')) 'hints', if (s.flag('key_borders')) 'borders'].join(', ').ifEmptyThen('Plain'), _keysPart),
      _part('layout', 'Layout', Lucide.laptop, 'cyan', _layoutSummary, _layoutPart),
      _part('keypress', 'Key press & sound', Lucide.volume2, 'amber', (s) => [if (s.flag('vibrate')) 'Vibrate', if (s.flag('sound')) (stockSounds.where((p) => p.$1 == s.choice('stock_sound')).firstOrNull?.$2 ?? 'sound')].join(', ').ifEmptyThen('Silent'), _keyPressPart),
    ];

List<Widget> _keysPart(BuildContext context, SettingsStore store) => [
      PaneSection(children: [
        _toggle(store, 'number_row', 'Number row'),
        _toggle(store, 'hints', 'Symbol hints', 'Each key’s long-press character in its corner.'),
        _toggle(store, 'key_popup', 'Popup on key press', 'The key you’re pressing, large above your finger.'),
        _toggle(store, 'key_borders', 'Key borders'),
        _toggle(store, 'toolbar', 'Toolbar', 'Settings and hide above the keys. Long-press { } opens settings either way.'),
        _toggle(store, 'hide_row', 'Hide the row above the keys', 'No suggestions, tools or toolbar: only the keys. Long-press { } still opens settings.'),
      ]),
      PaneSection(
        footer: const Text('Room under the bottom row for Android’s own hide and switch-keyboard buttons. Auto keeps the keys clear of them; choose More if they still crowd the keys on your phone, or None for only what Android asks for.'),
        children: [_choice(store, 'bottom_space', 'Space under the keys', const {'none': 'None', 'auto': 'Auto', 'more': 'More'})],
      ),
    ];

List<Widget> _keyPressPart(BuildContext context, SettingsStore store) => [
      PaneSection(children: [
        _toggle(store, 'vibrate', 'Vibrate'),
        if (store.flag('vibrate')) _choice(store, 'haptics', 'Strength', const {'light': 'Light', 'medium': 'Medium', 'strong': 'Strong'}),
        _toggle(store, 'sound', 'Sound'),
      ]),
      if (store.flag('sound'))
        PaneSection(
          title: 'Sound',
          footer: const Text('Tap one to hear it.'),
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

// --- Ghostwriter --------------------------------------------------------------------------------------------------

List<Widget> _aiPage(BuildContext context, SettingsStore store) {
  final mb = store.pluginOn('mockingbird');
  final gw = store.pluginOn('ghostwriter');
  final rw = store.pluginOn('rewrite');
  final ready = store.ai['ready'] == true;
  return [
    PaneHero(
      glyph: AppIcon(Lucide.sparkles, size: 24),
      title: ready ? 'Ready' : 'Add an AI key',
      meta: const Text('Your AI key, shared by the AI plugins: Mockingbird, Ghostwriter and Rewrite. Your own key, straight to the provider.'),
      status: PaneHeroStatus(ready ? 'On' : 'Waiting'),
    ),
    if (!mb || !gw || !rw)
      PaneSection(title: 'AI plugins', children: [
        if (!mb) SettingRow(label: 'Mockingbird', icon: AppIcon(LucideMore.bird, size: 18), hint: const Text('Learns how you write and text. Add it from Plugins.'), onPress: () => pushSettings(context, (_) => MarketScreen(store: store))),
        if (!gw) SettingRow(label: 'Ghostwriter', icon: AppIcon(LucideMore.wandSparkles, size: 18), hint: const Text('Writes <<what you ask for>> in any app. Add it from Plugins.'), onPress: () => pushSettings(context, (_) => MarketScreen(store: store))),
        if (!rw) SettingRow(label: 'Rewrite', icon: AppIcon(Lucide.penLine, size: 18), hint: const Text('Fixes, shortens, restyles or translates what you wrote. Add it from Plugins.'), onPress: () => pushSettings(context, (_) => MarketScreen(store: store))),
      ]),
  ];
}

List<SettingsSection> _aiParts(SettingsStore store) => [
      _aiProviderPart(),
      _part('profile', 'Typing profile', Lucide.fileText, 'slate', (s) => 'Import and export', _profilePart),
    ];

// --- Mockingbird --------------------------------------------------------------------------------------------------

List<Widget> _mockingbirdPage(BuildContext context, SettingsStore store) {
  final ready = store.ai['ready'] == true;
  return [
    PaneHero(
      glyph: AppIcon(LucideMore.bird, size: 24),
      title: ready ? (store.ai['profile'] != null ? 'Trained' : 'Learning') : 'Add an AI key',
      meta: const Text('Keeps samples of what you type, on this phone, and trains a profile of how you write. Obvious slips are cleaned out; only habits you repeat are learned. Ghostwriter and In my voice write with it.'),
      status: PaneHeroStatus(ready ? 'On' : 'Waiting'),
    ),
    PaneSection(children: [_plugin(store, 'mockingbird', 'Learn my style', 'Off removes Mockingbird; add it again from Plugins.')]),
  ];
}

List<SettingsSection> _mockingbirdParts(SettingsStore store) => [
      _aiProviderPart(),
      _part('ai-style', 'Your style', LucideMore.bird, 'magenta', (s) => _profileOutdated(s) ? _retrainHint : (s.ai['profile'] != null ? 'Trained ${_when((s.ai['profile'] as Map)['at'])}' : '${s.ai['samples'] ?? 0} samples'), _stylePart),
    ];

SettingsSection _aiProviderPart() =>
    _part('ai-provider', 'Your AI', Lucide.sparkles, 'purple', (s) => s.ai['ready'] == true ? '${_providerName(s)} · ${_modelOf(s)}' : 'Add a key', (context, store) => _aiSetup(store));

// --- Ghostwriter --------------------------------------------------------------------------------------------------

List<Widget> _ghostwriterPage(BuildContext context, SettingsStore store) {
  final ready = store.ai['ready'] == true;
  return [
    PaneHero(
      glyph: AppIcon(LucideMore.wandSparkles, size: 24),
      title: ready ? 'Ready' : 'Add an AI key',
      meta: const Text('Type <<what you want>> anywhere; close it with >> or press enter, and the AI writes it as you.'),
      status: PaneHeroStatus(ready ? 'On' : 'Waiting'),
    ),
    PaneSection(children: [_plugin(store, 'ghostwriter', 'Fill <<requests>>', 'Off removes Ghostwriter; add it again from Plugins.')]),
  ];
}

List<SettingsSection> _ghostwriterParts(SettingsStore store) => [
      _aiProviderPart(),
      _part('ai-filling', 'Filling', LucideMore.textCursorInput, 'indigo', (s) => [s.flag('ghostwriter_auto') ? 'On >>' : 'On Fill', if (s.flag('ghostwriter_typing')) 'typed out'].join(', '), _fillingPart),
      _part('ai-sources', 'What it can read', Lucide.globe, 'cyan', _sourcesSummary, _sourcesPart),
    ];

String _providerName(SettingsStore s) {
  final id = s.choice('ai_provider');
  return [for (final p in (s.ai['providers'] as List? ?? const [])) if (p is Map && p['id'] == id) p['name'] as String].firstOrNull ?? 'Claude';
}

String _modelOf(SettingsStore s) {
  final id = s.choice('ai_provider');
  final own = s.text('ai_model_$id');
  if (own.isNotEmpty) return own;
  return [for (final p in (s.ai['providers'] as List? ?? const [])) if (p is Map && p['id'] == id) p['defaultModel'] as String].firstOrNull ?? '';
}

String _sourcesSummary(SettingsStore s) => [
      if (s.flag('ghostwriter_memory') && s.pluginOn('mockingbird')) 'messages',
      if (s.flag('ghostwriter_notes')) 'notes',
      if (s.flag('ghostwriter_github') && s.text('github_token').isNotEmpty) 'GitHub',
      if (s.flag('ghostwriter_web')) 'the web',
      if (s.flag('ghostwriter_clipboard') && s.pluginOn('clipboard')) 'clipboard',
    ].join(', ').ifEmptyThen('Only the field');

List<Widget> _stylePart(BuildContext context, SettingsStore store) {
  final ai = store.ai;
  final profile = ai['profile'] is Map ? ai['profile'] as Map : null;
  final samples = ai['samples'] as int? ?? 0;
  final training = ai['training'] == true || store.aiBusy;
  return [
    PaneSection(
      footer: Text(profile != null ? 'Trained ${_when(profile['at'])} from ${profile['samples']} samples by ${profile['provider']}. $samples kept now: ${ai['chatSamples']} texts, ${ai['writingSamples']} writing.' : '$samples kept so far: ${ai['chatSamples'] ?? 0} texts, ${ai['writingSamples'] ?? 0} writing. It trains itself at about 50.'),
      children: [
        SettingRow(label: training ? 'Training…' : 'Train now', hint: _profileOutdated(store) && !training ? const Text(_retrainHint) : null, onPress: training || samples < 10 ? null : store.trainMockingbird),
        if (ai['error'] is String && !training) SettingRow(label: ai['error'] as String),
        _toggle(store, 'mockingbird_collect', 'Keep samples of what I type', 'Never from passwords, addresses, names, searches or incognito. Obvious slips are cleaned out; only habits you repeat are learned.'),
        _toggle(store, 'mockingbird_auto_train', 'Retrain by itself', 'At most daily, after 50 new samples; once more after an update to the style rules.'),
      ],
    ),
    if (profile != null)
      PaneSection(title: 'Your style profile', footer: const Text('What the AI is told about how you write: style only, no private facts.'), children: [SettingRowBox(child: Text(profile['text'] as String? ?? ''))]),
    PaneSection(children: [SettingRow(label: 'Delete samples and profile', onPress: samples == 0 && profile == null ? null : store.clearMockingbird)]),
  ];
}

List<Widget> _fillingPart(BuildContext context, SettingsStore store) => [
      PaneSection(children: [
        _toggle(store, 'ghostwriter_auto', 'Fill as soon as I type >>', 'Off: tap Fill, or press enter, on a request.'),
        _toggle(store, 'ghostwriter_typing', 'Type the answer out', 'Keys press, light and sound as it types. Off: it appears at once.'),
      ]),
      PaneSection(
        title: 'How it works',
        children: const [
          SettingRow(label: '<<engineering standup tldr>>', hint: Text('Something to write: it writes it, as you.')),
          SettingRow(label: '<<why do the metrics not fire on signup>>', hint: Text('A question: it answers from what it can read. Facts it can’t find become [blanks].')),
          SettingRow(label: 'Never', hint: Text('In password fields or while incognito.')),
        ],
      ),
    ];

List<Widget> _sourcesPart(BuildContext context, SettingsStore store) {
  final mb = store.pluginOn('mockingbird');
  return [
    PaneSection(children: [
      _toggle(store, 'ghostwriter_memory', 'My past messages', mb ? 'Your samples that share the request’s words.' : 'Needs Learn my style.'),
      _toggle(store, 'ghostwriter_web', 'The web', const {'anthropic', 'openai', 'gemini', 'openrouter'}.contains(store.choice('ai_provider')) ? 'Public facts, with your provider’s own search.' : 'Your provider has no web search.'),
      _toggle(store, 'ghostwriter_clipboard', 'My recent clipboard', store.pluginOn('clipboard') ? 'Your last few copies.' : 'Needs Clipboard history in Row tools.'),
    ]),
    PaneSection(
      title: 'Ghost.md notes',
      footer: const Text('Ghost.md on this phone is asked first, with nothing going online (it can be turned off in Ghost.md, Settings › AI › Ghost Keyboard). Signed in, notes are also searched online when the phone has none that match.'),
      children: [
        _toggle(store, 'ghostwriter_notes', 'Search my notes', store.ai['ghostInstalled'] == true ? 'Ghost.md is on this phone.' : 'Ghost.md isn’t on this phone.'),
        if (store.ai['ghostOnline'] == true)
          SettingRow(label: 'Online: signed in', value: const Text('Sign out'), onPress: store.aiBusy ? null : store.ghostSignOut)
        else
          SettingRow(label: 'Sign in to search online', hint: const Text('In your browser; your password stays on Ghost.md’s page.'), onPress: store.aiBusy ? null : store.ghostSignIn),
        if (store.ghostMessage != null) SettingRow(label: store.ghostMessage!),
      ],
    ),
    PaneSection(
      title: 'GitHub commits',
      footer: const Text('A classic token with the repo scope, or a fine-grained one with Contents: read, from github.com/settings/tokens. Kept on your phone.'),
      children: [
        _toggle(store, 'ghostwriter_github', 'Search my commits', 'Messages that match the request, from your repositories and organizations.'),
        SettingRowBox(
          child: GlacierInput(
            key: const ValueKey('github-token'),
            type: GlacierInputType.password,
            defaultValue: store.text('github_token'),
            placeholder: 'Paste a GitHub token',
            ariaLabel: 'GitHub token',
            autoCorrect: false,
            onChange: (text) => store.set('github_token', text.trim()),
          ),
        ),
        SettingRow(label: store.aiBusy ? 'Checking…' : 'Check the token', onPress: store.aiBusy ? null : store.checkGithub),
        if (store.githubMessage != null) SettingRow(label: store.githubMessage!),
      ],
    ),
  ];
}

List<Widget> _profilePart(BuildContext context, SettingsStore store) => [
      PaneSection(
        footer: const Text('One open file (ghost-typing-profile, docs/typing-profile.md in the GhostKbd repository) for your key aims, slips, words and writing style. Other apps and AI models read it to build reports and write one back; open or share a .json profile with Ghost Keyboard to bring it in.'),
        children: [
          SettingRow(label: 'Import a typing profile…', onPress: store.importProfile),
          if (store.profileError != null) SettingRow(label: store.profileError!),
          for (final line in store.profileLines) SettingRow(label: line),
          if (store.profileSource != null && store.profileLines.isNotEmpty) SettingRow(label: 'From ${store.profileSource}'),
        ],
      ),
      if (store.profileReport != null) PaneSection(title: 'Its report', children: [SettingRowBox(child: Text(store.profileReport!))]),
      PaneSection(
        title: 'Export',
        footer: const Text('Saved where you choose; nothing is uploaded.'),
        children: [
          SettingRow(
            label: 'Include my message samples',
            hint: const Text('Leave off unless you trust where the file goes.'),
            control: GlacierSwitch(checked: store.profileSamples, onCheckedChange: store.setProfileSamples, ariaLabel: 'Include my message samples'),
          ),
          SettingRow(label: 'Export my typing profile…', onPress: store.exportProfile),
        ],
      ),
    ];

// --- Row tools ----------------------------------------------------------------------------------------------------

List<Widget> _rowToolsPage(BuildContext context, SettingsStore store) {
  final own = store.choice('giphy_key');
  return [
    PaneSection(
      footer: const Text('Buttons in the row above the keys, whenever you aren’t mid-word.'),
      children: [
        _toggle(store, 'arrow_keys', 'Arrow keys', 'Left, up, down and right at the end of the row, always there; hold one to keep moving.', LucideMore.move),
        _plugin(store, 'clipboard', 'Clipboard history', 'Your last 25 copies, a tap away. Never a password manager’s.', Lucide.clipboardPaste),
        _plugin(store, 'undo', 'Undo and redo', 'Takes back what the keyboard did, a word or an action at a time, in any app.', Lucide.undo2),
        _plugin(store, 'emoji', 'Emoji', 'A panel and search; :shortcodes and emoji for the word you type.', LucideMore.smile),
        _plugin(store, 'gifs', 'GIFs', 'Search GIPHY and send one.', Lucide.image),
        _plugin(store, 'markdown', 'Markdown', 'Bold, code, links and lists, in notes apps.', LucideMore.squareM),
        if (store.pluginOn('markdown')) _toggle(store, 'markdown_everywhere', 'Markdown in every app', null, Lucide.globe),
        _plugin(store, 'terminal', 'Terminal keys', 'Esc, Tab, Ctrl, Alt and arrows, in terminal apps.', Lucide.terminal),
        if (store.pluginOn('terminal')) _toggle(store, 'terminal_everywhere', 'Terminal keys in every app', null, Lucide.globe),
        _plugin(store, 'incognito', 'Incognito switch', 'Type without the keyboard learning or keeping anything.', Lucide.eyeOff),
      ],
    ),
    if (store.pluginOn('emoji'))
      PaneSection(
        title: 'Emoji',
        children: [
          _choice(store, 'emoji_skin', 'Skin tone', const {'0': '✋', '1': '✋🏻', '2': '✋🏼', '3': '✋🏽', '4': '✋🏾', '5': '✋🏿'}, null, LucideMore.hand),
          _toggle(store, 'emoji_suggest', 'Emoji for the word you type', null, LucideMore.smile),
        ],
      ),
    if (store.pluginOn('clipboard')) PaneSection(children: [SettingRow(label: 'Clear clipboard history', icon: AppIcon(LucideMore.trash2, size: 18), value: Text('${store.clipCount} saved'), onPress: store.clipCount == 0 ? null : store.clearClipboard)]),
    if (store.pluginOn('gifs'))
      ..._more(store, 'rowtools', [
        PaneSection(
          title: 'GIPHY key',
          footer: const Text('Only if the GIF panel asks for one: paste a key from developers.giphy.com and press enter.'),
          children: [SettingRowBox(child: GlacierInput(key: ValueKey('giphy-$own'), defaultValue: own, placeholder: 'Your GIPHY API key (optional)', ariaLabel: 'GIPHY API key', onSubmitted: (text) => store.set('giphy_key', text.trim())))],
        ),
      ]),
  ];
}

// --- About --------------------------------------------------------------------------------------------------------

List<Widget> _aboutPage(BuildContext context, SettingsStore store) {
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
      glyph: AppIcon(LucideMore.keyboard, size: 24),
      title: 'Ghost Keyboard ${u['current'] ?? ''}',
      meta: Text(line),
      status: PaneHeroStatus(phase == 'upToDate' ? 'Up to date' : (busy ? 'Working' : (canInstall ? 'Update ready' : 'A keyboard for people who write code')), pulse: busy),
    ),
    if (phase == 'downloading') Padding(padding: EdgeInsets.symmetric(horizontal: context.tokens.space(2)), child: GlacierProgressBar(value: (progress ?? 0) * 100, ariaLabel: 'Download')),
    PaneSection(
      title: 'Updates',
      children: [
        if (canInstall)
          SettingRow(label: needsPermission ? 'Allow installs' : 'Update now', onPress: store.installUpdate)
        else
          SettingRow(label: 'Check for updates', onPress: busy || phase == 'disabled' ? null : store.checkForUpdate, disabled: busy || phase == 'disabled'),
        _toggle(store, 'auto_update', 'Update by itself', 'When the app opens and twice a day.'),
      ],
    ),
    PaneSection(children: [
      _VersionRow(store: store),
      SettingRow(label: 'Watch the walkthrough again', icon: AppIcon(LucideMore.rocket, size: 18), onPress: () => openOnboarding(context, store, replay: true)),
    ]),
    if (store.flag('developer'))
      PaneSection(
        title: 'Developer',
        children: [
          SettingRow(label: 'Keyboard', value: Text(store.enabled ? (store.selected ? 'On and in use' : 'On, not in use') : 'Not turned on')),
          SettingRow(label: 'Plugins', hint: Text(store.plugins.join(', '))),
          SettingRow(
            label: 'Copy these details',
            icon: AppIcon(Lucide.copy, size: 18),
            onPress: () => Clipboard.setData(ClipboardData(text: 'Ghost Keyboard ${u['current'] ?? ''}\nplugins: ${store.plugins.join(', ')}\nsettings: ${store.settingsJson}')),
          ),
          SettingRow(label: 'Hide developer settings', onPress: () => store.set('developer', false)),
        ],
      ),
    PaneSection(
      title: 'Made with',
      children: const [
        SettingRow(label: 'Glacier', hint: Text('The UI kit, from Ghost.md.')),
        SettingRow(label: 'whisper.cpp', hint: Text('Speech to text on the phone (MIT).')),
        SettingRow(label: 'Phosphor Icons and Lucide', hint: Text('The icons (MIT, ISC).')),
        SettingRow(label: 'wordfreq', hint: Text('Autocorrect’s word list (CC BY-SA 4.0).')),
      ],
    ),
  ];
}

// --- Typing stats (0.9.0) -----------------------------------------------------------------------------------------

Map<Object?, Object?> _statsOf(SettingsStore s, String part) => s.stats[part] is Map ? s.stats[part] as Map : const {};

int _stat(Map<Object?, Object?> m, String key) => (m[key] as num?)?.toInt() ?? 0;

String _statsSummary(SettingsStore s) {
  if (!s.flag('typing_stats')) return 'Off';
  final today = _statsOf(s, 'today');
  final wpm = _stat(today, 'wpm');
  final words = _stat(today, 'words');
  if (words == 0) return 'Nothing typed today';
  return [if (wpm > 0) '$wpm words a minute', '$words words today'].join(', ');
}

/// Asks the keyboard for its counts while the stats page is open, so typing in the field below shows as it happens.
class _StatsPoll extends StatefulWidget {
  const _StatsPoll({required this.store, required this.child});
  final SettingsStore store;
  final Widget child;

  @override
  State<_StatsPoll> createState() => _StatsPollState();
}

class _StatsPollState extends State<_StatsPoll> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    widget.store.refreshStats();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => widget.store.refreshStats());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

/// The last seven days as bars: each as tall as its words against the busiest day's, with the day under it.
class _WeekBars extends StatelessWidget {
  const _WeekBars({required this.week});
  final List<Map<Object?, Object?>> week;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    var most = 1;
    for (final d in week) {
      if (_stat(d, 'words') > most) most = _stat(d, 'words');
    }
    const tall = 72.0;
    final small = TextStyle(fontSize: 11, color: t.color('app-ink-3'));
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        for (var i = 0; i < week.length; i++)
          Expanded(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(_stat(week[i], 'words') == 0 ? '' : '${_stat(week[i], 'words')}', maxLines: 1, style: small),
                SizedBox(height: t.space(1)),
                Container(
                  height: 3 + (tall - 3) * _stat(week[i], 'words') / most,
                  margin: EdgeInsets.symmetric(horizontal: t.space(1)),
                  decoration: BoxDecoration(
                    color: i == week.length - 1 ? settingsHue(t) : settingsHueWash(t),
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
                SizedBox(height: t.space(1)),
                Text('${week[i]['day'] ?? ''}', maxLines: 1, style: small),
              ],
            ),
          ),
      ],
    );
  }
}

List<Widget> _statsPart(BuildContext context, SettingsStore store) {
  final on = store.flag('typing_stats');
  final today = _statsOf(store, 'today');
  final all = _statsOf(store, 'all');
  final week = [for (final d in (store.stats['week'] as List? ?? const [])) if (d is Map) d];
  final top = [for (final k in (store.stats['topKeys'] as List? ?? const [])) '$k'];
  final wpm = _stat(today, 'wpm');
  final words = _stat(today, 'words');
  String speed(int n) => n == 0 ? '—' : '$n wpm';
  String percent(int n) => n == 0 ? '—' : '$n%';
  return [
    _StatsPoll(
      store: store,
      child: PaneHero(
        glyph: AppIcon(Lucide.gauge, size: 24),
        title: !on ? 'Typing stats are off' : (wpm > 0 ? '$wpm words a minute' : (words == 0 ? 'Nothing typed today' : '$words words today')),
        meta: Text(!on ? 'Nothing is being counted.' : (words == 0 ? 'Type in the field below to see it count.' : '$words words in ${_stat(today, 'minutes')} minutes of typing today.')),
        status: PaneHeroStatus(on ? 'Counting' : 'Off'),
      ),
    ),
    PaneSection(
      title: 'Today',
      footer: const Text('Accuracy is how much of what you typed stood: a backspace straight after a key, or an autocorrect fix, is a slip.'),
      children: [
        SettingRow(label: 'Speed', value: Text(speed(wpm))),
        SettingRow(label: 'Accuracy', value: Text(percent(_stat(today, 'accuracy')))),
        SettingRow(label: 'Words', value: Text('$words')),
        SettingRow(label: 'Fixed by autocorrect', value: Text('${_stat(today, 'corrections')}')),
        SettingRow(label: 'Words swiped', value: Text('${_stat(today, 'swipes')}')),
      ],
    ),
    if (week.isNotEmpty) PaneSection(title: 'Last 7 days', footer: const Text('Words typed each day.'), children: [SettingRowBox(child: _WeekBars(week: week))]),
    PaneSection(
      title: 'Last 30 days',
      children: [
        SettingRow(label: 'Speed', value: Text(speed(_stat(all, 'wpm')))),
        SettingRow(label: 'Accuracy', value: Text(percent(_stat(all, 'accuracy')))),
        SettingRow(label: 'Words', value: Text('${_stat(all, 'words')} over ${_stat(all, 'days')} ${_stat(all, 'days') == 1 ? 'day' : 'days'}')),
        if (top.isNotEmpty) SettingRow(label: 'Busiest keys', hint: Text(top.join('  ·  '))),
      ],
    ),
    PaneSection(
      footer: const Text('Counts only, never what you typed. Kept on this phone for 30 days and sent nowhere. Nothing is counted in password fields or while incognito.'),
      children: [
        _toggle(store, 'typing_stats', 'Count my typing'),
        SettingRow(label: 'Reset stats', onPress: _stat(all, 'words') == 0 && words == 0 ? null : store.resetStats),
      ],
    ),
  ];
}

// --- Layout: foldables and one-handed (0.9.0) -------------------------------------------------------------------------

String _layoutSummary(SettingsStore s) {
  final wide = wideLayouts.where((l) => l.$1 == s.choice('wide_layout')).firstOrNull?.$2 ?? 'Split';
  final hand = s.choice('one_handed');
  return ['$wide when unfolded', if (hand != 'off') 'one-handed $hand'].join(', ');
}

List<Widget> _layoutPart(BuildContext context, SettingsStore store) => [
      PaneSection(
        title: 'Unfolded and on tablets',
        footer: const Text('For screens 600 points wide or more: a foldable’s inner screen, a tablet, most phones on their side. Docked, tap the arrow on the empty side to move the keys across.'),
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
        title: 'On a phone',
        footer: const Text('The keys narrow toward one thumb. Tap the arrow on the empty side to switch hands. Open the preview below to see it.'),
        children: [_choice(store, 'one_handed', 'One-handed', const {'off': 'Off', 'left': 'Left hand', 'right': 'Right hand'})],
      ),
    ];

// --- Per-app profiles (0.9.0) -----------------------------------------------------------------------------------------

String _appsSummary(SettingsStore s) {
  final n = s.appProfiles.length;
  return n == 0 ? 'The same in every app' : (n == 1 ? s.appProfiles.single.label : '$n apps');
}

/// What a profile can set (android AppProfiles.FIELDS): its key, its name, and a line on it.
const List<(String, String, String)> _profileFields = [
  ('number_row', 'Number row', 'Digits above the letters.'),
  ('autocorrect', 'Correct on its own', 'Off: autocorrect only suggests in this app.'),
  ('terminal', 'Terminal keys', 'Esc, Tab, Ctrl, Alt and arrows in the row.'),
  ('markdown', 'Markdown buttons', 'Bold, code, links and lists in the row.'),
];

String _profileSummary(AppProfile p) {
  final set = [
    for (final (key, name, _) in _profileFields)
      if (p.values.containsKey(key)) '$name ${p.values[key]! ? 'on' : 'off'}',
    if (p.values['incognito'] == true) 'Always incognito',
  ];
  return set.isEmpty ? 'Follows your settings' : set.join(', ');
}

List<Widget> _appsPage(BuildContext context, SettingsStore store) {
  final n = store.appProfiles.length;
  final recent = store.recentApps;
  return [
    PaneHero(
      glyph: AppIcon(Lucide.smartphone, size: 24),
      title: 'Per-app profiles',
      meta: const Text('The number row, autocorrect, terminal keys, markdown buttons and incognito, set for one app. Whatever a profile leaves alone follows your settings.'),
      status: PaneHeroStatus(n == 0 ? 'None yet' : (n == 1 ? '1 app' : '$n apps')),
    ),
    PaneSection(
      title: 'Add a profile',
      footer: const Text('The apps you typed in lately with Ghost Keyboard. Only their names are kept, on this phone.'),
      children: [
        if (recent.isEmpty) const SettingRow(label: 'No apps to add yet', hint: Text('Type something in an app with Ghost Keyboard, then come back.')),
        for (final app in recent)
          SettingRow(label: app.label, hint: Text(app.package), icon: AppIcon(LucideMore.circlePlus, size: 18), onPress: () => store.addProfile(app.package)),
      ],
    ),
  ];
}

List<SettingsSection> _appsParts(SettingsStore store) => [
      for (final p in store.appProfiles)
        _part('app-${p.package}', p.label, Lucide.smartphone, 'cyan', (s) => _profileSummary(s.appProfiles.where((x) => x.package == p.package).firstOrNull ?? p), (context, store) => _appProfilePage(context, store, p.package)),
    ];

/// One setting of a profile: as Settings has it, or forced on or off for this app.
SettingRow _profileChoice(SettingsStore store, AppProfile p, String key, String label, String hint, {bool offToo = true}) => SettingRow(
      label: label,
      hint: Text(hint),
      layout: SettingRowLayout.stacked,
      control: GlacierSegmentedControl(
        options: [
          const GlacierSegmentedOption(value: 'default', label: Text('As Settings')),
          const GlacierSegmentedOption(value: 'on', label: Text('On')),
          if (offToo) const GlacierSegmentedOption(value: 'off', label: Text('Off')),
        ],
        value: !p.values.containsKey(key) ? 'default' : (p.values[key]! ? 'on' : (offToo ? 'off' : 'default')),
        onValueChange: (v) => store.setProfile(p.package, key, v == 'default' ? null : v == 'on'),
        fullWidth: true,
        size: GlacierSegmentedSize.sm,
        ariaLabel: label,
      ),
    );

List<Widget> _appProfilePage(BuildContext context, SettingsStore store, String package) {
  final p = store.appProfiles.where((x) => x.package == package).firstOrNull;
  if (p == null) return [const PaneSection(children: [SettingRow(label: 'This profile was removed')])];
  return [
    PaneSection(
      footer: Text('Only while you type in ${p.label} (${p.package}). “As Settings” follows the rest of Settings.'),
      children: [for (final (key, name, line) in _profileFields) _profileChoice(store, p, key, name, line)],
    ),
    PaneSection(
      children: [_profileChoice(store, p, 'incognito', 'Incognito', 'On: nothing typed in this app is learned, counted or kept.', offToo: false)],
    ),
    PaneSection(
      children: [
        SettingRow(
          label: 'Remove this profile',
          onPress: () {
            store.removeProfile(package);
            Navigator.of(context).maybePop();
          },
        ),
      ],
    ),
  ];
}

// --- Rewrite (0.9.0) ----------------------------------------------------------------------------------------------

String _rewriteSummary(SettingsStore s) => s.ai['ready'] != true ? 'Add an AI key' : 'Fix, shorten, restyle, translate into ${s.choice('rewrite_language')}';

List<Widget> _rewritePage(BuildContext context, SettingsStore store) {
  final ready = store.ai['ready'] == true;
  final language = store.choice('rewrite_language');
  return [
    PaneHero(
      glyph: AppIcon(Lucide.penLine, size: 24),
      title: ready ? 'Ready' : 'Add an AI key',
      meta: const Text('Tap Rewrite in the row above the keys, then how: it works on what you selected, or on the whole field, and replaces it.'),
      status: PaneHeroStatus(ready ? 'On' : 'Waiting'),
    ),
    PaneSection(
      title: 'Translate into',
      footer: const Text('Any language, written however you like: Spanish, Deutsch, 日本語. Press enter to keep it.'),
      children: [
        SettingRowBox(
          child: GlacierInput(
            key: ValueKey('rewrite-language-$language'),
            defaultValue: language,
            placeholder: 'Spanish',
            ariaLabel: 'Language to translate into',
            onSubmitted: (text) => store.set('rewrite_language', text.trim().isEmpty ? 'Spanish' : text.trim()),
          ),
        ),
      ],
    ),
    PaneSection(
      title: 'How it works',
      children: [
        const SettingRow(label: 'Fix', hint: Text('Spelling, grammar and punctuation. Nothing else changes.')),
        const SettingRow(label: 'Shorter', hint: Text('The same thing in fewer words.')),
        const SettingRow(label: 'Formal, Casual, Friendlier', hint: Text('The tone changes; the meaning and the facts stay.')),
        SettingRow(label: '→ $language', hint: const Text('Translated, keeping names, links and code as they are.')),
        const SettingRow(label: 'Never', hint: Text('In password fields or while incognito.')),
      ],
    ),
    PaneSection(children: [SettingRow(label: 'Remove Rewrite', onPress: () => store.setPlugin('rewrite', false))]),
  ];
}
