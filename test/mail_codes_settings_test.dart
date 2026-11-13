// Settings › Row tools › One-time codes from email (android MailCodes.kt, docs/mail-codes.md): off unless the person
// turns it on; the clipboard plugin's, and gone without it; and the row says where things stand with Android: access
// not given yet, given, and what Android 15 and later keep back, with the two ways the phone's owner has to let
// codes through. Run with `flutter test`.

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
  // What a tap on the command's row puts on the clipboard.
  String? copied;
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
    if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String?;
    return null;
  });

  testWidgets('codes from email are off by default, the clipboard plugin’s, and say where Android stands', (tester) async {
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
    String hint() => (row('One-time codes from email')!.hint as Text).data!;
    const command = 'adb shell cmd appops set --user 0 com.infamousvague.devkeyboard RECEIVE_SENSITIVE_NOTIFICATIONS allow';

    // Off unless turned on: only the switch, right under the codes from texts, saying how it works and what it asks.
    expect(store.flag('mail_codes'), isFalse);
    final labels = rows().map((r) => r.label).toList();
    expect(labels.indexOf('One-time codes from email'), labels.indexOf('One-time codes from texts') + 1);
    expect((row('One-time codes from email')!.control as GlacierSwitch).checked, isFalse);
    for (final phrase in ['Gmail or another mail app', 'the mail app’s notification, on this phone', 'no account to connect', 'Android will ask']) {
      expect(hint(), contains(phrase));
    }
    expect(row('Allow notification access'), isNull);
    expect(row('Let codes through, from a computer'), isNull);

    // Switched on, access not yet given: it says it is waiting, with Android's page a tap away, and what to do where
    // Android calls the setting restricted (an app from outside a store).
    await store.setMailCodes(true);
    expect(calls, contains('setMailCodes'));
    expect(store.flag('mail_codes'), isTrue);
    expect((row('One-time codes from email')!.control as GlacierSwitch).checked, isTrue);
    expect(hint(), contains('Waiting for Android'));
    await tester.runAsync(() async => row('Allow notification access')!.onPress!());
    expect(calls, contains('openNotificationAccess'));
    expect((row('If Android says it is restricted')!.hint as Text).data, contains('Allow restricted settings'));
    await tester.runAsync(() async => row('If Android says it is restricted')!.onPress!());
    expect(calls, contains('openAppInfo'));

    // Given, on an Android that hides nothing (14 and earlier): what a code does, what is read, and that it goes nowhere.
    store.mailCodes = const {'on': true, 'allowed': true, 'android': 34, 'shown': true, 'hidden': false, 'command': command};
    for (final phrase in ['three minutes', 'clipboard for ten', 'until you use it', 'Only mail apps’ notifications are read', 'only the code is kept, in memory', 'Nothing is sent anywhere']) {
      expect(hint(), contains(phrase));
    }
    expect(row('Allow notification access'), isNull);
    expect(row('Let codes through, from a computer'), isNull);
    for (final banned in ['100%', 'every code', 'always', 'any app']) {
      expect(hint().toLowerCase(), isNot(contains(banned)));
    }

    // Android 15 and later: it says codes may be hidden, and the two ways to let them through; a tap copies the command.
    store.mailCodes = const {'on': true, 'allowed': true, 'android': 36, 'shown': false, 'hidden': false, 'command': command};
    expect(hint(), contains('Android 15 and later hide'));
    expect(hint(), isNot(contains('Nothing is sent anywhere')));
    final computer = row('Let codes through, from a computer')!;
    expect((computer.hint as Text).data, contains(command));
    computer.onPress!();
    await tester.pump();
    expect(copied, command);
    expect((row('Or turn off Android’s search for codes')!.hint as Text).data, contains('Enhanced notifications'));
    expect(row('Or turn off Android’s search for codes')!.onPress, isNull);
    // Once Android has hidden one, it says that it has.
    store.mailCodes = const {'on': true, 'allowed': true, 'android': 36, 'shown': false, 'hidden': true, 'command': command};
    expect(hint(), contains('Android has hidden a mail’s notification'));
    // And once the phone's owner has let them through, it is as on an older Android.
    store.mailCodes = const {'on': true, 'allowed': true, 'android': 36, 'shown': true, 'hidden': false, 'command': command};
    expect(hint(), contains('Nothing is sent anywhere'));
    expect(row('Let codes through, from a computer'), isNull);

    // Search finds it by what people call it.
    store.mailCodes = const {'on': false, 'allowed': false, 'android': 36, 'shown': false, 'hidden': false, 'command': command};
    await store.set('mail_codes', false);
    for (final query in ['gmail', 'email', 'one-time', 'codes']) {
      final hits = searchSettings(context, store, settingsSections(store), query);
      expect(hits.any((h) => h.setting == 'One-time codes from email'), isTrue, reason: query);
    }

    // It is the clipboard plugin's: without the plugin there is nowhere to show a code, and the row goes.
    await store.setPlugin('clipboard', false);
    expect(row('One-time codes from email'), isNull);
  });
}
