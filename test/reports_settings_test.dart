// Settings › Typing › Typing stats › Anonymous reports (Reports.kt): off unless the person turns it on; with it off
// nothing else of it shows; with it on, what is waiting is counted, can be seen exactly as it would go, sent now and
// discarded; and the page says what it never sends. Run with `flutter test`.

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
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async => null);

  testWidgets('reports are off by default, say what they never send, and show what is waiting only when on', (tester) async {
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
    SettingsSection part(String id) => settingsSections(store).firstWhere((s) => s.id == 'typing').parts!(store).firstWhere((s) => s.id == id);
    PaneSection card() => part('stats').page(context, store).whereType<PaneSection>().firstWhere((s) => s.title == 'Anonymous reports');
    List<String> labels() => card().children.whereType<SettingRow>().map((r) => r.label).toList();

    // Off unless turned on, and with it off there is only the switch.
    expect(store.flag('share_reports'), isFalse);
    expect(labels(), ['Share anonymous typing reports']);

    // The promise, in the page's own words: where it goes, what it is, what it never is.
    final says = (card().footer as Text).data!;
    for (final phrase in ['Off unless you turn it on', 'attack.fm', 'at most once a day', 'No account, no id and no time of day', 'never a word of your own or a rarer one', 'commonest words', 'password fields', 'incognito', 'Turn this off and it is deleted', 'Offline stops it too', 'exactly as it would go']) {
      expect(says, contains(phrase));
    }
    // The wording rule (docs/COMPETITION.md, section 9): no promise it cannot keep.
    for (final banned in ['nothing leaves', 'never leaves', 'cannot be traced', 'untraceable', '100%']) {
      expect(says.toLowerCase(), isNot(contains(banned)));
    }

    // On, with nothing counted yet.
    await store.set('share_reports', true);
    expect(labels(), ['Share anonymous typing reports', 'Waiting to go']);
    expect((card().children.whereType<SettingRow>().last.value as Text).data, 'Nothing yet');

    // On, with a report waiting: its size, the way to see it, to send it and to discard it.
    store.report = const {'waiting': '{\n  "v": 1\n}', 'words': 42, 'mistakes': 1, 'strokes': 3, 'enough': false, 'lastSent': '', 'server': 'attack.fm'};
    expect(labels(), ['Share anonymous typing reports', 'Waiting to go', 'See the report', 'Send it now', 'Discard what is waiting']);
    final waiting = card().children.whereType<SettingRow>().firstWhere((r) => r.label == 'Waiting to go');
    expect((waiting.value as Text).data, '42 words, 1 mistake, 3 swipes');
    expect((waiting.hint as Text).data, contains('100 words'));
    // Shown, it is the report itself and nothing made up for the page.
    store.showReport(true);
    expect(labels(), contains('Hide the report'));
    final shown = card().children.whereType<SettingRowBox>().single.child as Text;
    expect(shown.data, '{\n  "v": 1\n}');
    // With Offline on it cannot be sent by hand either.
    expect(card().children.whereType<SettingRow>().firstWhere((r) => r.label == 'Send it now').onPress, isNotNull);
    await store.set('offline', true);
    expect(card().children.whereType<SettingRow>().firstWhere((r) => r.label == 'Send it now').onPress, isNull);
    await store.set('offline', false);

    // Once one has gone the day is shown, whether or not anything waits.
    store.report = const {'waiting': '', 'words': 0, 'mistakes': 0, 'strokes': 0, 'enough': false, 'lastSent': '2026-11-10', 'server': 'attack.fm'};
    expect(labels(), ['Share anonymous typing reports', 'Waiting to go', 'Last sent']);

    // And the search finds the switch.
    final rows = pageEntries(part('stats').page(context, store));
    expect(rows.map((e) => e.$1), contains('Share anonymous typing reports'));
  });
}
