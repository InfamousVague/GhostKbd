// Settings › Row tools › Agent keys (android AgentKeys.kt, docs/agent-keys.md): one of the keyboard's own plugins,
// off until switched on, straight after the terminal keys it goes with; a per-app profile can set it for one app.
// Run with `flutter test`.

import 'package:dev_keyboard/plugins/catalogue.dart';
import 'package:dev_keyboard/settings/kit/settings_kit.dart';
import 'package:dev_keyboard/settings/panes.dart';
import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/settings/settings_screen.dart';
import 'package:dev_keyboard/settings_store.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // No phone behind the channel: each call is answered with nothing.
  final calls = <MethodCall>[];
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async {
    calls.add(call);
    return null;
  });

  testWidgets('Agent keys is a switch in Row tools, off by default, under the terminal keys', (tester) async {
    final sheet = await tester.runAsync(() => loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css']));
    installSettingsStyles(sheet!);
    final store = SettingsStore();
    late BuildContext context;
    await tester.pumpWidget(Directionality(
      textDirection: TextDirection.ltr,
      child: GlacierTheme(sheet: sheet, attrs: const {'platform': 'mobile', 'theme': 'dark'}, child: Builder(builder: (c) {
        context = c;
        return const SizedBox();
      })),
    ));
    SettingsSection tools() => settingsSections(store).firstWhere((s) => s.id == 'rowtools');
    List<SettingRow> rows() => tools().page(context, store).whereType<PaneSection>().first.children.whereType<SettingRow>().toList();
    SettingRow row(String label) => rows().firstWhere((r) => r.label == label);

    // Off until added, and listed straight after the terminal keys.
    expect(store.pluginOn('agent'), isFalse);
    expect((row('Agent keys').control as GlacierSwitch).checked, isFalse);
    final labels = rows().map((r) => r.label).toList();
    expect(labels.indexOf('Agent keys'), labels.indexOf('Terminal keys') + 1);
    // The row says what it adds, in words a person who has not met the plugin can follow.
    final hint = (row('Agent keys').hint as Text).data!;
    for (final phrase in ['coding agent', 'Shift+Tab', 'Esc twice', 'Ctrl+C', 'new line that doesn’t send', 'AI chat apps']) {
      expect(hint, contains(phrase));
    }
    expect(tools().summary!(store), '3 of 9 on');

    // Switched on: the keyboard is told, and the row counts one more.
    await store.setPlugin('agent', true);
    expect(calls.any((c) => c.method == 'setPlugin' && (c.arguments as Map)['id'] == 'agent' && (c.arguments as Map)['on'] == true), isTrue);
    expect((row('Agent keys').control as GlacierSwitch).checked, isTrue);
    expect(tools().summary!(store), '4 of 9 on');
    // With the terminal keys on too, its own "every app" switch stays between the two.
    await store.setPlugin('terminal', true);
    final both = rows().map((r) => r.label).toList();
    expect(both.indexOf('Agent keys'), both.indexOf('Terminal keys in every app') + 1);
  });

  test('the Market has it among the keyboard’s own plugins, and not on iPhone', () {
    final plugin = pluginById('agent')!;
    expect(plugin.name, 'Agent keys');
    expect(builtInPlugins, contains('agent'));
    expect(marketCatalogue.any((p) => p.id == 'agent'), isFalse);
    expect(builtInCatalogue.any((p) => p.id == 'agent'), isTrue);
    expect(plugin.ios, isFalse);
    // It reaches nothing: no permission and no server.
    expect(plugin.permissions, isEmpty);
    expect(plugin.hosts, isEmpty);
    for (final phrase in ['Shift+Tab', 'Esc twice', 'Ctrl+C', 'Ctrl+J', 'Ctrl+R', 'AI chat apps', 'per-app profile']) {
      expect(plugin.description, contains(phrase));
    }
  });
}
