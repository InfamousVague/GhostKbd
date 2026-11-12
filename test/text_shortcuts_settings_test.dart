// Settings › Typing › Text shortcuts (android Shortcuts.kt, docs/text-shortcuts.md): one of the keyboard's own
// plugins, off until switched on; its page lists the shortcuts, removes one at a tap, and has a form of two fields
// whose refusals are the keyboard's own words. Run with `flutter test`.

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
  // The phone's side of the channel: the shortcuts it keeps, answered to every shortcut call.
  final calls = <MethodCall>[];
  Map<String, Object?>? phone;
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async {
    calls.add(call);
    return const {'shortcutsState', 'shortcutAdd', 'shortcutRemove'}.contains(call.method) ? phone : null;
  });

  testWidgets('Text shortcuts is a switch on Typing, with a page of its own once it is on', (tester) async {
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
    SettingsSection typing() => settingsSections(store).firstWhere((s) => s.id == 'typing');
    List<SettingRow> typingRows() => typing().page(context, store).whereType<PaneSection>().first.children.whereType<SettingRow>().toList();
    SettingsSection? part() => typing().parts!(store).where((s) => s.id == 'shortcuts').firstOrNull;
    List<PaneSection> sections() => part()!.page(context, store).whereType<PaneSection>().toList();
    List<SettingRow> rows(String title) => sections().firstWhere((s) => s.title == title).children.whereType<SettingRow>().toList();

    // Off until switched on: a switch on the Typing page, and no page of its own yet.
    final toggle = typingRows().firstWhere((r) => r.label == 'Text shortcuts');
    expect((toggle.control as GlacierSwitch).checked, isFalse);
    expect((toggle.hint as Text).data, contains('omw'));
    expect(part(), isNull);

    await store.setPlugin('shortcuts', true);
    expect(calls.any((c) => c.method == 'setPlugin' && (c.arguments as Map)['id'] == 'shortcuts'), isTrue);
    expect(part(), isNotNull);
    expect(part()!.summary!(store), 'None yet');

    // Its page: the switch for expanding at a space (on by default), an empty list that says what a shortcut is for,
    // and the form.
    expect(store.flag('shortcuts_auto'), isTrue);
    final auto = sections().first.children.whereType<SettingRow>().single;
    expect(auto.label, 'Expand when I press space');
    expect((auto.hint as Text).data, allOf(contains('Backspace straight after puts your letters back'), contains('only a tap')));
    expect(rows('Your shortcuts').map((r) => r.label), ['None yet']);
    final form = sections().firstWhere((s) => s.title == 'Add a shortcut');
    expect(form.children.whereType<SettingRowBox>().length, 2);
    for (final phrase in ['2 to 24 characters', 'no space', '{date}', '{time}', '{clipboard}', 'already have is changed']) {
      expect((form.footer as Text).data, contains(phrase));
    }

    // Two shortcuts on the phone: listed with what they stand for, each a tap from removed.
    phone = {
      'shortcuts': [
        {'shortcut': ';sig', 'text': 'Thanks,\\nMatt'},
        {'shortcut': 'omw', 'text': 'On my way!'},
      ],
      'max': 200,
    };
    await store.refreshShortcuts();
    expect(rows('Your shortcuts').map((r) => r.label), [';sig', 'omw']);
    expect((rows('Your shortcuts').last.hint as Text).data, 'On my way!');
    expect(part()!.summary!(store), '2 shortcuts');
    phone = {
      'shortcuts': [
        {'shortcut': 'omw', 'text': 'On my way!'},
      ],
      'max': 200,
    };
    rows('Your shortcuts').first.onPress!();
    await tester.pump();
    expect((calls.lastWhere((c) => c.method == 'shortcutRemove').arguments as Map)['shortcut'], ';sig');
    expect(part()!.summary!(store), '1 shortcut');

    // The form: what is written goes to the keyboard trimmed; a refusal stays on the page with the fields as written.
    store.shortcutDraft = ' on my ';
    store.shortcutTextDraft = 'On my way!';
    phone = {
      'shortcuts': [
        {'shortcut': 'omw', 'text': 'On my way!'},
      ],
      'max': 200,
      'error': 'A shortcut is one word, with no space in it.',
    };
    await store.addShortcut();
    final refused = calls.lastWhere((c) => c.method == 'shortcutAdd').arguments as Map;
    expect(refused['shortcut'], 'on my');
    expect(refused['text'], 'On my way!');
    expect(rows('Add a shortcut').last.label, 'A shortcut is one word, with no space in it.');
    expect(store.shortcutDraft, ' on my ');
    expect(store.shortcutForm, 0);

    // Kept: the list has it, and the form is empty again, with new fields.
    store.shortcutDraft = 'brb';
    store.shortcutTextDraft = 'Be right back';
    phone = {
      'shortcuts': [
        {'shortcut': 'brb', 'text': 'Be right back'},
        {'shortcut': 'omw', 'text': 'On my way!'},
      ],
      'max': 200,
    };
    await store.addShortcut();
    expect(rows('Your shortcuts').map((r) => r.label), ['brb', 'omw']);
    expect(store.shortcutDraft, '');
    expect(store.shortcutTextDraft, '');
    expect(store.shortcutForm, 1);
    expect(rows('Add a shortcut').any((r) => r.label!.contains('one word')), isFalse);

    // Switched off, the page goes; the shortcuts themselves are kept for when it is on again.
    await store.setPlugin('shortcuts', false);
    expect(part(), isNull);
    expect(store.shortcuts.length, 2);
  });

  test('the Market has it among the keyboard’s own plugins, and not on iPhone', () {
    final plugin = pluginById('shortcuts')!;
    expect(plugin.name, 'Text shortcuts');
    expect(builtInPlugins, contains('shortcuts'));
    expect(marketCatalogue.any((p) => p.id == 'shortcuts'), isFalse);
    expect(plugin.ios, isFalse);
    expect(plugin.permissions, isEmpty);
    expect(plugin.hosts, isEmpty);
    for (final phrase in ['omw', 'press space', 'Backspace straight after', '{date}', 'Kept on your phone only', 'password field']) {
      expect(plugin.description, contains(phrase));
    }
  });
}
