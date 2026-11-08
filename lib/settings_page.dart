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
            SwitchSetting(
              title: 'Sound on key press',
              value: store.flag('sound'),
              onChanged: (v) => store.set('sound', v),
            ),
          ],
        ),
      ];

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
