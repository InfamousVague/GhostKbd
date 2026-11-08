import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'settings_store.dart';
import 'widgets.dart';

/// The one screen: setup, a field to try the keyboard in, and its settings.
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.store});
  final SettingsStore store;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> with WidgetsBindingObserver {
  SettingsStore get store => widget.store;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) store.refreshStatus();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) => ColoredBox(
        color: t.color('glacier-bg'),
        child: DefaultTextStyle(
          style: t.text('md'),
          child: SafeArea(
            bottom: false,
            child: ListView(
              padding: EdgeInsets.fromLTRB(t.space(4), t.space(6), t.space(4), t.space(10) + MediaQuery.paddingOf(context).bottom),
              children: [
                _header(t),
                SizedBox(height: t.space(6)),
                _setup(t),
                SizedBox(height: t.space(6)),
                const SectionLabel('Try it'),
                GlacierInput(placeholder: 'Tap here to try the keyboard', ariaLabel: 'Try the keyboard'),
                SizedBox(height: t.space(6)),
                ..._appearance(),
                SizedBox(height: t.space(6)),
                ..._typing(),
                SizedBox(height: t.space(6)),
                ..._feedback(),
                SizedBox(height: t.space(6)),
                ..._updates(t),
                SizedBox(height: t.space(6)),
                _tips(t),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _header(Tokens t) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Dev Keyboard', style: t.text('2xl', weight: 'semibold')),
          SizedBox(height: t.space(1)),
          Text('A keyboard for people who write code.', style: t.text('sm', color: t.color('glacier-text-muted'))),
        ],
      );

  Widget _setup(Tokens t) {
    final ready = store.enabled && store.selected;
    return SettingsCard(
      children: [
        SetupStep(
          number: 1,
          title: 'Turn on Dev Keyboard',
          description: 'In your phone\'s keyboard list.',
          done: store.enabled,
          action: 'Turn on',
          onAction: store.openInputSettings,
        ),
        SetupStep(
          number: 2,
          title: 'Switch to Dev Keyboard',
          description: 'Make it the keyboard you type with.',
          done: store.selected,
          action: 'Switch',
          onAction: store.enabled ? store.showPicker : null,
        ),
        if (ready)
          Padding(
            padding: EdgeInsets.symmetric(horizontal: t.space(4), vertical: t.space(3)),
            child: Row(
              children: [
                const GlacierPill(tone: GlacierPillTone.success, child: Text('Ready')),
                SizedBox(width: t.space(2)),
                Expanded(
                  child: Text('You\'re typing with Dev Keyboard.', style: t.text('sm', color: t.color('glacier-text-muted'))),
                ),
              ],
            ),
          ),
      ],
    );
  }

  List<Widget> _appearance() => [
        const SectionLabel('Appearance'),
        SettingsCard(
          children: [
            ChoiceSetting(
              title: 'Theme',
              value: store.choice('theme'),
              options: const {'system': 'System', 'light': 'Light', 'dark': 'Dark'},
              onChanged: (v) => store.set('theme', v),
            ),
            ChoiceSetting(
              title: 'Key height',
              value: store.choice('key_height'),
              options: const {'short': 'Short', 'normal': 'Normal', 'tall': 'Tall'},
              onChanged: (v) => store.set('key_height', v),
            ),
            SwitchSetting(
              title: 'Number row',
              description: 'Digits in a row of their own above the letters.',
              value: store.flag('number_row'),
              onChanged: (v) => store.set('number_row', v),
            ),
            SwitchSetting(
              title: 'Key borders',
              description: 'A shape behind every key. Off is Gboard\'s look: only ?123, space and enter have one.',
              value: store.flag('key_borders'),
              onChanged: (v) => store.set('key_borders', v),
            ),
            SwitchSetting(
              title: 'Toolbar',
              description: 'A strip above the keys with settings and hide. Long-press comma opens settings either way.',
              value: store.flag('toolbar'),
              onChanged: (v) => store.set('toolbar', v),
            ),
            SwitchSetting(
              title: 'Symbol hints',
              description: 'Show each key\'s long-press character in its corner.',
              value: store.flag('hints'),
              onChanged: (v) => store.set('hints', v),
            ),
          ],
        ),
      ];

  List<Widget> _typing() => [
        const SectionLabel('Typing'),
        SettingsCard(
          children: [
            SwitchSetting(
              title: 'Auto-capitalization',
              description: 'Shift on at the start of each sentence.',
              value: store.flag('auto_caps'),
              onChanged: (v) => store.set('auto_caps', v),
            ),
            SwitchSetting(
              title: 'Double-space period',
              description: 'Two spaces after a word type a period and a space.',
              value: store.flag('double_space_period'),
              onChanged: (v) => store.set('double_space_period', v),
            ),
            SwitchSetting(
              title: 'Space bar cursor control',
              description: 'Slide along the space bar to move the cursor.',
              value: store.flag('space_cursor'),
              onChanged: (v) => store.set('space_cursor', v),
            ),
            ChoiceSetting(
              title: 'Long-press delay',
              value: store.choice('long_press'),
              options: const {'fast': 'Fast', 'normal': 'Normal', 'slow': 'Slow'},
              onChanged: (v) => store.set('long_press', v),
            ),
          ],
        ),
      ];

  List<Widget> _feedback() => [
        const SectionLabel('Key press'),
        SettingsCard(
          children: [
            SwitchSetting(
              title: 'Popup on key press',
              description: 'The key you\'re pressing, shown large above your finger.',
              value: store.flag('key_popup'),
              onChanged: (v) => store.set('key_popup', v),
            ),
            SwitchSetting(
              title: 'Vibrate on key press',
              value: store.flag('vibrate'),
              onChanged: (v) => store.set('vibrate', v),
            ),
            if (store.flag('vibrate'))
              ChoiceSetting(
                title: 'Vibration strength',
                value: store.choice('haptics'),
                options: const {'light': 'Light', 'medium': 'Medium', 'strong': 'Strong'},
                onChanged: (v) => store.set('haptics', v),
              ),
            SwitchSetting(
              title: 'Sound on key press',
              value: store.flag('sound'),
              onChanged: (v) => store.set('sound', v),
            ),
          ],
        ),
      ];

  List<Widget> _updates(Tokens t) {
    final u = store.update;
    final phase = u['phase'] as String? ?? 'idle';
    final current = u['current'] as String?;
    final available = u['available'] as String?;
    final progress = (u['progress'] as num?)?.toDouble();
    final error = u['error'] as String?;
    final needsPermission = error == 'needsPermission';

    final (String line, GlacierPillTone tone, String pill) = switch (phase) {
      'disabled' => ('Updates are off in debug builds.', GlacierPillTone.neutral, 'Debug'),
      'checking' => ('Looking for a new version…', GlacierPillTone.neutral, 'Checking'),
      'upToDate' => ('You have the latest version.', GlacierPillTone.success, 'Up to date'),
      'available' => ('Version $available is available.', GlacierPillTone.accent, 'New'),
      'downloading' => ('Downloading version $available…', GlacierPillTone.accent, '${((progress ?? 0) * 100).round()}%'),
      'ready' when needsPermission => ('Allow Dev Keyboard to install updates, then tap Update.', GlacierPillTone.warning, 'Ready'),
      'ready' => ('Version $available is downloaded and ready.', GlacierPillTone.accent, 'Ready'),
      'installing' => ('Installing version $available…', GlacierPillTone.accent, 'Installing'),
      'failed' => (error ?? 'The update did not finish.', GlacierPillTone.danger, 'Failed'),
      _ => ('Updates come from attack.fm.', GlacierPillTone.neutral, 'Idle'),
    };
    final canInstall = phase == 'available' || phase == 'ready';
    final busy = phase == 'checking' || phase == 'downloading' || phase == 'installing';

    return [
      const SectionLabel('Updates'),
      SettingsCard(
        children: [
          Padding(
            padding: EdgeInsets.symmetric(horizontal: t.space(4), vertical: t.space(3)),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Expanded(child: Text('Dev Keyboard ${current ?? ''}', style: t.text('md', weight: 'medium'))),
                    GlacierPill(tone: tone, size: GlacierPillSize.sm, child: Text(pill)),
                  ],
                ),
                SizedBox(height: t.space(1) / 2),
                Text(line, style: t.text('sm', color: t.color(phase == 'failed' ? 'glacier-danger-text' : 'glacier-text-muted'))),
                if (phase == 'downloading') ...[
                  SizedBox(height: t.space(3)),
                  GlacierProgressBar(value: (progress ?? 0) * 100, size: GlacierProgressBarSize.sm, ariaLabel: 'Download'),
                ],
                SizedBox(height: t.space(3)),
                Align(
                  alignment: Alignment.centerLeft,
                  child: canInstall
                      ? GlacierButton(label: needsPermission ? 'Allow installs' : 'Update now', onPressed: store.installUpdate)
                      : GlacierButton(label: 'Check for updates', onPressed: busy || phase == 'disabled' ? null : store.checkForUpdate),
                ),
              ],
            ),
          ),
          SwitchSetting(
            title: 'Automatic updates',
            description: 'Look for a new version when the app opens and twice a day, and install it.',
            value: store.flag('auto_update'),
            onChanged: (v) => store.set('auto_update', v),
          ),
        ],
      ),
    ];
  }

  Widget _tips(Tokens t) {
    final muted = t.text('sm', color: t.color('glacier-text-muted'));
    Widget tip(List<Widget> parts) => Padding(
          padding: EdgeInsets.only(bottom: t.space(2)),
          child: DefaultTextStyle(
            style: muted,
            child: Wrap(spacing: t.space(1), runSpacing: t.space(1), crossAxisAlignment: WrapCrossAlignment.center, children: parts),
          ),
        );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        tip([const Text('Long-press'), const GlacierKbd(child: Text('space')), const Text('to switch keyboards.')]),
        tip([const Text('Double-tap'), const GlacierKbd(child: Text('⇧')), const Text('for caps lock.')]),
        tip([const Text('Long-press'), const GlacierKbd(child: Text(',')), const Text('to open these settings.')]),
      ],
    );
  }
}
