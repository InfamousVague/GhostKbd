// Settings › Row tools › One-time codes from texts (android SmsCodes.kt, docs/sms-codes.md): off unless the person
// turns it on; the clipboard plugin's, and gone without it; and the row says where things stand with Android: not
// asked yet, refused, allowed, and what Android 17 keeps back. Run with `flutter test`.

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
  // No phone behind the channel: each call is answered with nothing.
  final calls = <String>[];
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async {
    calls.add(call.method);
    return null;
  });

  testWidgets('codes from texts are off by default, the clipboard plugin’s, and say where Android stands', (tester) async {
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
    String hint() => (row('One-time codes from texts')!.hint as Text).data!;

    // Off unless turned on: only the switch, right under the clipboard it belongs to, saying what it will ask.
    expect(store.flag('sms_codes'), isFalse);
    final labels = rows().map((r) => r.label).toList();
    expect(labels.indexOf('One-time codes from texts'), labels.indexOf('Clipboard history') + 1);
    expect((row('One-time codes from texts')!.control as GlacierSwitch).checked, isFalse);
    expect(hint(), contains('Android will ask'));
    expect(row('Allow text messages'), isNull);
    expect(row('Open Android’s page for Ghost Keyboard'), isNull);

    // Switched on, Android not yet answered: it says it is waiting, and Android's question is a tap away.
    await store.setSmsCodes(true);
    expect(calls, contains('setSmsCodes'));
    expect(store.flag('sms_codes'), isTrue);
    expect((row('One-time codes from texts')!.control as GlacierSwitch).checked, isTrue);
    expect(hint(), contains('Waiting for Android'));
    expect(row('Allow text messages')!.onPress, isNotNull);
    // Android's own page is offered only once Android has been asked and said no.
    expect(row('Open Android’s page for Ghost Keyboard'), isNull);
    store.smsCodes = const {'on': true, 'allowed': false, 'asked': true, 'android': 36, 'target': 36};
    final page = row('Open Android’s page for Ghost Keyboard')!;
    expect((page.hint as Text).data, contains('Allow restricted settings'));
    await store.openAppInfo();
    expect(calls, contains('openAppInfo'));

    // Allowed: what a code does, how long it is kept, and that it goes nowhere. No promise it cannot keep.
    store.smsCodes = const {'on': true, 'allowed': true, 'asked': true, 'android': 36, 'target': 36};
    for (final phrase in ['three minutes', 'clipboard for ten', 'until you use it', 'Only the code is kept, in memory', 'Nothing is sent anywhere']) {
      expect(hint(), contains(phrase));
    }
    expect(hint(), isNot(contains('Android 17')));
    expect(row('Allow text messages'), isNull);
    for (final banned in ['nothing leaves', 'never leaves', '100%', 'every code', 'always']) {
      expect(hint().toLowerCase(), isNot(contains(banned)));
    }

    // Android 17: codes written for one app or website stay with it, and the row says so.
    store.smsCodes = const {'on': true, 'allowed': true, 'asked': true, 'android': 37, 'target': 36};
    expect(hint(), contains('Android 17 keeps a code written for one app or website'));
    // And a build made for Android 17 is held back from every code: the row says it cannot work rather than seem broken.
    store.smsCodes = const {'on': true, 'allowed': true, 'asked': true, 'android': 37, 'target': 37};
    expect(hint(), contains('holds codes back from this app for three hours'));

    // Search finds it by what people call it.
    store.smsCodes = const {'on': false, 'allowed': false, 'asked': false, 'android': 36, 'target': 36};
    await store.set('sms_codes', false);
    for (final query in ['codes', 'text message', 'one-time']) {
      final hits = searchSettings(context, store, settingsSections(store), query);
      expect(hits.any((h) => h.setting == 'One-time codes from texts'), isTrue, reason: query);
    }

    // It is the clipboard plugin's: without the plugin there is nowhere to show a code, and the row goes.
    await store.setPlugin('clipboard', false);
    expect(row('One-time codes from texts'), isNull);
  });
}
