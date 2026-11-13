// Settings › Row tools › One-time codes from Ghost Messaging (android GhostMessagingCodes.kt,
// docs/ghost-messaging-codes.md): on to start, since it asks Android for nothing; the clipboard plugin's, and gone
// without it; under the codes from email; and the row says whether Ghost Messaging is on the phone. Run with
// `flutter test`.

import 'package:dev_keyboard/settings/kit/settings_kit.dart';
import 'package:dev_keyboard/settings/panes.dart';
import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/settings/settings_screen.dart';
import 'package:dev_keyboard/settings/settings_search.dart';
import 'package:dev_keyboard/settings_store.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // The phone behind the channel: Ghost Messaging is on it or not, and the switch is what was last set.
  final calls = <String>[];
  var here = false;
  var on = true;
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async {
    calls.add(call.method);
    if (call.method == 'setGhostMessagingCodes') on = (call.arguments as Map)['on'] == true;
    if (call.method == 'ghostMessagingCodesState' || call.method == 'setGhostMessagingCodes') return {'on': on, 'here': here};
    return null;
  });

  testWidgets('codes from Ghost Messaging are on to start, the clipboard plugin’s, and say whether it is on the phone', (tester) async {
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
    SettingRow? row(String label) => rows().where((r) => r.label == label).firstOrNull;
    const label = 'One-time codes from Ghost Messaging';
    String hint() => (row(label)!.hint as Text).data!;

    // On to start, under the codes from email, which stay right under the codes from texts.
    expect(store.flag('ghost_messaging_codes'), isTrue);
    final labels = rows().map((r) => r.label).toList();
    expect(labels.indexOf('One-time codes from email'), labels.indexOf('One-time codes from texts') + 1);
    expect(labels.indexOf(label), greaterThan(labels.indexOf('One-time codes from email')));
    expect(labels.indexOf(label), lessThan(labels.indexOf('Undo and redo')));
    expect((row(label)!.control as GlacierSwitch).checked, isTrue);

    // Without Ghost Messaging on the phone it says so, and where to get it.
    expect(hint(), contains('isn’t on this phone'));
    expect(hint(), contains('ghost-sms.com'));

    // With it: what is handed over and what is not, and that Android is asked for nothing.
    here = true;
    await tester.runAsync(store.refreshGhostMessagingCodes);
    expect(calls, contains('ghostMessagingCodesState'));
    for (final phrase in ['only the code', 'never the words or who sent them', 'three minutes', 'kept in memory only', 'nothing to allow in Android']) {
      expect(hint(), contains(phrase));
    }

    // Off, by its switch: said to the keyboard, and the row says what turning it on would do.
    await tester.runAsync(() => store.setGhostMessagingCodes(false));
    expect(calls, contains('setGhostMessagingCodes'));
    expect(store.flag('ghost_messaging_codes'), isFalse);
    expect((row(label)!.control as GlacierSwitch).checked, isFalse);
    expect(hint(), contains('Ghost Messaging is on this phone'));

    // The search finds it.
    final hits = searchSettings(context, store, settingsSections(store), 'ghost messaging codes');
    expect(hits.any((h) => h.setting == label), isTrue);

    // It is the clipboard plugin's: without the plugin there is nowhere to show a code, and no row.
    await tester.runAsync(() => store.set('plugins', 'autocorrect,swipe,undo,emoji'));
    expect(row(label), isNull);
  });
}
