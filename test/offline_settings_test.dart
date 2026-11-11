// Settings › Offline: the section sits second on the home list with a switch of its own, the search finds it by the
// words a person would try, and its page is drawn from the ledger the keyboard sends (SettingsStore.net), not from a
// list written here. Run with `flutter test`.

import 'package:dev_keyboard/settings/panes.dart';
import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/settings/settings_screen.dart';
import 'package:dev_keyboard/settings/settings_search.dart';
import 'package:dev_keyboard/settings_store.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

/// A ledger as Net.kt's `toMap` sends it, with names no page could have made up.
Map<String, Object?> fakeNet({required bool offline}) => {
      'offline': offline,
      'features': [
        {'id': 'pigeons', 'title': 'Carrier pigeons', 'stops': 'Every bird sent out.', 'sections': ['rowtools']},
      ],
      'sections': ['rowtools'],
      'paths': [
        {'id': 'pigeon-post', 'feature': 'pigeons', 'title': 'Pigeon post', 'hosts': ['loft.example', '*.coop.example'], 'ownServer': false, 'trigger': 'Opening the loft.', 'sends': 'A note on a leg.', 'armed': true},
      ],
      'works': [
        {'id': 'typing', 'title': 'Typing by hand', 'line': 'Needs no bird.'},
      ],
      'hosts': {
        'gifs': ['loft.example'],
      },
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // There is no phone behind the channel here: every call is answered with nothing, so the store keeps what the test gives it.
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async => null);

  test('Offline is the second card, in a group of its own, with its switch on the list', () {
    final store = SettingsStore();
    final sections = settingsSections(store);
    expect(sections[0].id, 'setup');
    // Above it only the top card: Setup, and on Android the Trainer beside it (Matt, 2026-11-11: "Place the training
    // section at the top next to the setup section").
    final at = sections.indexWhere((s) => s.id == 'offline');
    expect(sections.take(at).map((s) => s.group).toSet(), {sections[0].group});
    final offline = sections[at];
    expect(sections.where((s) => s.group == offline.group).length, 1);
    expect(offline.control, isNotNull);
    expect(offline.summary!(store), 'Off · what leaves your phone');
  });

  test('off by default, and the rows that would connect say why they are disabled', () async {
    final store = SettingsStore();
    expect(store.offline, isFalse);
    expect(store.offlineReason, isNull);
    await store.set('offline', true);
    expect(store.offline, isTrue);
    expect(store.offlineReason, 'Offline is on');
    expect(settingsSections(store).firstWhere((s) => s.id == 'offline').summary!(store), 'On · nothing is asked of any server');
  });

  testWidgets('the search finds it, the page is the ledger, and pages that connect say Offline is on', (tester) async {
    final sheet = await tester.runAsync(() => loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css']));
    installSettingsStyles(sheet!);
    final store = SettingsStore()..net = fakeNet(offline: false);
    late BuildContext context;
    await tester.pumpWidget(Directionality(
      textDirection: TextDirection.ltr,
      child: GlacierTheme(sheet: sheet, attrs: const {'platform': 'mobile', 'theme': 'dark'}, child: Builder(builder: (c) {
        context = c;
        return const SizedBox();
      })),
    ));

    List<SettingsHit> find(String words) => searchSettings(context, store, settingsSections(store), words);
    bool inOffline(SettingsHit hit) => hit.path.first.id == 'offline';

    // By its name, the section itself comes first.
    expect(find('offline').first.section.id, 'offline');
    // By the other words a person would try, something on its page or its part is found, with Offline off and on.
    for (final on in [false, true]) {
      await store.set('offline', on);
      store.net = fakeNet(offline: on);
      for (final words in ['internet', 'network', 'connection', 'privacy', 'leaves', 'servers']) {
        expect(find(words).any(inOffline), isTrue, reason: '"$words" finds nothing under Offline while it is ${on ? 'on' : 'off'}');
      }
    }

    // The page lists what the ledger lists.
    final offline = settingsSections(store).firstWhere((s) => s.id == 'offline');
    final page = pageEntries(offline.page(context, store)).map((e) => e.$1).toList();
    expect(page, containsAll(['Offline is on', 'Offline', 'Stopped', 'Carrier pigeons', 'Still works', 'Typing by hand']));
    final leaves = offline.parts!(store).single;
    expect(leaves.label, 'What leaves your phone');
    final rows = pageEntries(leaves.page(context, store));
    final post = rows.firstWhere((e) => e.$1 == 'Pigeon post');
    expect(post.$2, contains('To loft.example and the servers under coop.example.'));
    expect(post.$2, contains('When: Opening the loft.'));
    expect(post.$2, contains('Sent: A note on a leg.'));

    // The Market's servers for a plugin are the ledger's.
    expect(store.netHosts('gifs'), ['loft.example']);
    expect(store.netHosts('dictation'), isNull);

    // A page whose features connect says so first while Offline is on; other pages, and Offline's own, do not.
    final sections = settingsSections(store);
    SettingsSection section(String id) => everySection(sections, store).firstWhere((s) => s.id == id);
    expect(offlineBanner(context, store, section('rowtools')), isNotNull);
    expect(offlineBanner(context, store, section('typing')), isNull);
    expect(offlineBanner(context, store, section('offline')), isNull);
    await store.set('offline', false);
    expect(offlineBanner(context, store, section('rowtools')), isNull);
  });
}
