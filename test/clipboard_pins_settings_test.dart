// Settings › Row tools › Pinned clips (android ClipHistory.kt and ClipList.kt, docs/clipboard-pins.md): the pinned
// clips listed under the clipboard they belong to, each removable, one written by hand, and a Clear that says it
// leaves them. Run with `flutter test`.

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
  // The phone's side of the channel: a clipboard whose state the test sets, answered to every clipboard call.
  final calls = <MethodCall>[];
  Map<String, Object?>? phone;
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async {
    calls.add(call);
    return const {'clipboardState', 'clearClipboard', 'clipRemove', 'clipPinAdd'}.contains(call.method) ? phone : null;
  });

  testWidgets('pinned clips are listed under the clipboard, removable, and Clear leaves them', (tester) async {
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
    List<PaneSection> sections() => tools().page(context, store).whereType<PaneSection>().toList();
    PaneSection? pinned() => sections().where((s) => s.title == 'Pinned clips').firstOrNull;
    List<SettingRow> pinRows() => pinned()!.children.whereType<SettingRow>().toList();
    SettingRow clear() => sections().expand((s) => s.children.whereType<SettingRow>()).firstWhere((r) => r.label == 'Clear clipboard history');

    // The clipboard is on by default. Nothing copied, nothing pinned: the section says what a pin is for, and how.
    expect(store.pluginOn('clipboard'), isTrue);
    expect(pinRows().map((r) => r.label), ['Nothing pinned yet']);
    final footer = (pinned()!.footer as Text).data!;
    for (final phrase in ['Hold a clip', 'lead the list', 'not counted among your 25', 'stay when the history is cleared', 'kept on this phone']) {
      expect(footer, contains(phrase));
    }
    expect(pinned()!.children.whereType<SettingRowBox>().length, 1);
    expect((clear().value as Text).data, '0 saved');
    expect(clear().onPress, isNull);
    expect(clear().hint, isNull);

    // Five clips on the phone, two of them pinned: the pins are listed, and Clear counts only the copies.
    phone = {'count': 5, 'pinned': 2, 'pins': ['42 Wallaby Way, Sydney', 'https://ghostkbd.com'], 'maxPins': 25};
    await store.refreshClipboard();
    expect(pinRows().map((r) => r.label), ['42 Wallaby Way, Sydney', 'https://ghostkbd.com']);
    expect(pinRows().every((r) => (r.value as Text).data == 'Remove' && r.onPress != null), isTrue);
    expect((clear().value as Text).data, '3 saved');
    expect((clear().hint as Text).data, 'Pinned clips stay.');
    expect(clear().onPress, isNotNull);

    // Removing one asks the phone for exactly that clip.
    phone = {'count': 4, 'pinned': 1, 'pins': ['https://ghostkbd.com'], 'maxPins': 25};
    pinRows().first.onPress!();
    await tester.pump();
    final removed = calls.lastWhere((c) => c.method == 'clipRemove');
    expect((removed.arguments as Map)['text'], '42 Wallaby Way, Sydney');
    expect(pinRows().map((r) => r.label), ['https://ghostkbd.com']);

    // Clearing leaves the pin: the phone answers with what is left, and there is nothing more to clear.
    phone = {'count': 1, 'pinned': 1, 'pins': ['https://ghostkbd.com'], 'maxPins': 25};
    await store.clearClipboard();
    expect(calls.any((c) => c.method == 'clearClipboard'), isTrue);
    expect(store.clipCount, 1);
    expect((clear().value as Text).data, '0 saved');
    expect(clear().onPress, isNull);
    expect(pinRows().map((r) => r.label), ['https://ghostkbd.com']);

    // A pin written by hand goes to the phone as written; a refusal is said in the phone's own words.
    phone = {'count': 2, 'pinned': 2, 'pins': ['On my way', 'https://ghostkbd.com'], 'maxPins': 25};
    await store.addClipPin('On my way');
    expect((calls.lastWhere((c) => c.method == 'clipPinAdd').arguments as Map)['text'], 'On my way');
    expect(pinRows().map((r) => r.label), ['On my way', 'https://ghostkbd.com']);
    phone = {'count': 2, 'pinned': 2, 'pins': ['On my way', 'https://ghostkbd.com'], 'maxPins': 25, 'error': '25 clips are pinned already. Remove one first.'};
    await store.addClipPin('one too many');
    expect(pinRows().last.label, '25 clips are pinned already. Remove one first.');

    // A long clip is one line in the list, cut short; removing it still names the whole clip.
    final long = 'first line\nsecond line ${'x' * 80}';
    phone = {'count': 1, 'pinned': 1, 'pins': [long], 'maxPins': 25};
    await store.refreshClipboard();
    expect(pinRows().first.label, startsWith('first line second line xxx'));
    expect(pinRows().first.label!.length, 60);
    expect(pinRows().first.label, endsWith('…'));
    pinRows().first.onPress!();
    await tester.pump();
    expect((calls.lastWhere((c) => c.method == 'clipRemove').arguments as Map)['text'], long);

    // Without the clipboard plugin there is no clipboard to pin from, and no section.
    await store.setPlugin('clipboard', false);
    expect(pinned(), isNull);
    expect(sections().expand((s) => s.children.whereType<SettingRow>()).any((r) => r.label == 'Clear clipboard history'), isFalse);
  });

  test('the Market says the clipboard pins, on Android, and not on iPhone', () {
    final plugin = pluginById('clipboard')!;
    expect(plugin.line, contains('pin'));
    expect(plugin.description, contains('Hold a clip to pin it'));
    expect(plugin.adds, contains('Clips you pin, which stay'));
    // The iPhone's keyboard has no pins: its words do not promise them.
    expect(plugin.iosLine, isNot(contains('pin')));
    expect(plugin.iosDescription, isNot(contains('pin')));
    expect(plugin.iosAdds!.any((a) => a.contains('pin')), isFalse);
  });
}
