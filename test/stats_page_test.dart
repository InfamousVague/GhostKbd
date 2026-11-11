// Settings › Stats (lib/stats/stats_page.dart; android TypingStats.kt, KeyTallies.kt, MainActivity.statsPage): the
// page of pictures from everyday typing. It is in the top card under Setup and the trainer, draws without a number
// to its name and with a full day's, says why a picture is empty, lists the words put right most, and keeps the
// promise that nothing but counts is kept. Run with `flutter test`.

import 'package:dev_keyboard/settings/kit/settings_kit.dart';
import 'package:dev_keyboard/settings/panes.dart';
import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/settings/settings_search.dart';
import 'package:dev_keyboard/settings_store.dart';
import 'package:dev_keyboard/stats/stats_page.dart';
import 'package:dev_keyboard/trainer/trainer_board.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

/// A QWERTY board as the keyboard sends it (TrainerMaps.board), with e missed for r and t shifted.
Map<String, Object?> _board() {
  const rows = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
  const insets = [0.0, 0.5, 1.5];
  return {
    'rows': [
      for (var r = 0; r < rows.length; r++)
        [
          for (var i = 0; i < rows[r].length; i++)
            {
              'key': rows[r][i],
              'x': insets[r] + i,
              'taps': rows[r][i] == 'e' ? 120 : 40,
              'missed': rows[r][i] == 'e' ? 14 : (rows[r][i] == 'n' ? 3 : 0),
              'landX': 0.12,
              'landY': 0.06,
              'landed': rows[r][i] == 'e' ? 106 : 37,
              'shiftX': rows[r][i] == 't' ? 0.14 : 0.0,
              'shiftY': rows[r][i] == 't' ? 0.05 : 0.0,
              'moved': rows[r][i] == 't',
              'instead': rows[r][i] == 'e' ? 'r' : null,
            },
        ],
    ],
    'units': 10.0,
    'slips': [
      {'pressed': 'r', 'meant': 'e', 'count': 11},
      {'pressed': 'm', 'meant': 'n', 'count': 3},
    ],
    'taps': 1120,
    'missed': 17,
    'maxShift': 0.3,
  };
}

const Map<String, Object?> _full = {
  'today': {'chars': 2400, 'words': 480, 'wpm': 52, 'accuracy': 96, 'slips': 31, 'corrections': 22, 'swipes': 14, 'minutes': 9, 'correctionsKept': 91, 'swipesFirst': 86},
  'week': [
    {'day': 'Thu', 'words': 310, 'wpm': 44, 'accuracy': 94, 'minutes': 7},
    {'day': 'Fri', 'words': 0, 'wpm': 0, 'accuracy': 0, 'minutes': 0},
    {'day': 'Sat', 'words': 1204, 'wpm': 49, 'accuracy': 95, 'minutes': 25},
    {'day': 'Sun', 'words': 90, 'wpm': 0, 'accuracy': 97, 'minutes': 2},
    {'day': 'Mon', 'words': 640, 'wpm': 55, 'accuracy': 96, 'minutes': 12},
    {'day': 'Tue', 'words': 220, 'wpm': 47, 'accuracy': 93, 'minutes': 5},
    {'day': 'Wed', 'words': 480, 'wpm': 52, 'accuracy': 96, 'minutes': 9},
  ],
  'all': {'words': 12840, 'wpm': 48, 'accuracy': 95, 'days': 19, 'minutes': 262, 'corrections': 510, 'correctionsKept': 93, 'swipes': 204, 'swipesFirst': 88, 'slips': 730, 'undone': 36, 'chars': 61000},
  'topKeys': ['E 11%'],
  'streak': 4,
  'fastest': {'day': '9 Nov', 'wpm': 61},
  'fullest': {'day': '7 Nov', 'words': 1204},
  'pressed': 48211,
  'letters': [
    {'key': 'e', 'count': 5400, 'share': 11.2},
    {'key': 't', 'count': 4300, 'share': 8.9},
    {'key': 'a', 'count': 3900, 'share': 8.1},
  ],
  'adaptive': true,
  'counting': true,
  'learning': true,
  'layout': 'QWERTY',
  'misspelled': [
    {'typed': 'teh', 'meant': 'the', 'count': 7},
    {'typed': 'form', 'meant': 'from', 'count': 3},
    {'typed': 'anout', 'meant': 'about', 'count': 1},
  ],
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // No phone behind the channel: each call is answered with nothing.
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async => null);

  test('counts and times read as people say them', () {
    expect(statCount(0), '0');
    expect(statCount(999), '999');
    expect(statCount(1204), '1,204');
    expect(statCount(1234567), '1,234,567');
    expect(statTime(0), '0 min');
    expect(statTime(59), '59 min');
    expect(statTime(262), '4 h 22 min');
  });

  test('everyday key shifting is said in the plugin’s terms, never a trainer’s', () {
    final board = TrainerBoardData.of(_board())!;
    expect(everydayAreasSaid(board, adaptive: true), 'Adaptive keys has 1 key’s touch area off the middle. Furthest: t, 14% right and 5% low.');
    expect(everydayAreasSaid(board, adaptive: false), contains('Adaptive keys is off'));
    final still = TrainerBoardData.of({..._board(), 'rows': [for (final row in _board()['rows'] as List) [for (final k in row as List) {...k as Map, 'shiftX': 0.0, 'shiftY': 0.0, 'moved': false}]]})!;
    expect(everydayAreasSaid(still, adaptive: true), 'Adaptive keys has not moved a touch area far enough to be in use yet.');
    for (final said in [everydayAreasSaid(board, adaptive: true), everydayAreasSaid(board, adaptive: false), everydayAreasSaid(still, adaptive: true)]) {
      expect(said.toLowerCase(), isNot(contains('fix')));
      expect(said.toLowerCase(), isNot(contains('run')));
    }
  });

  testWidgets('the Stats page is in the top card, and draws with nothing counted and with a full day', (tester) async {
    final sheet = await tester.runAsync(() => loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css']));
    installSettingsStyles(sheet!);
    final store = SettingsStore();
    await tester.binding.setSurfaceSize(const Size(412, 2400));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    // In the top card, straight after the trainer, with a summary that says what the page is before anything is typed.
    final sections = settingsSections(store);
    final at = sections.indexWhere((s) => s.id == statsPageId);
    expect(sections[at].label, 'Stats');
    expect(sections[at].group, 'start');
    expect(sections[at - 1].id, 'trainer');
    expect(sections.where((s) => s.group == 'start').map((s) => s.id).toList(), ['setup', 'trainer', statsPageId]);
    expect(statsPageSummary(store), 'Speed, missed keys, shifted keys, misspelled words');

    late BuildContext context;
    Future<void> draw() async {
      await tester.pumpWidget(Directionality(
        textDirection: TextDirection.ltr,
        child: GlacierTheme(
          sheet: sheet,
          attrs: const {'platform': 'mobile', 'theme': 'dark'},
          child: ListenableBuilder(
            listenable: store,
            builder: (c, _) {
              context = c;
              return SingleChildScrollView(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: statsPage(c, store)));
            },
          ),
        ),
      ));
      await tester.pump();
      expect(tester.takeException(), isNull);
    }

    List<String> titles() => statsPage(context, store).whereType<PaneSection>().map((s) => s.title ?? '').where((t) => t.isNotEmpty).toList();
    PaneSection card(String title) => statsPage(context, store).whereType<PaneSection>().firstWhere((s) => s.title == title);

    // Nothing counted: every card is there and says why it is empty.
    await draw();
    expect(titles(), ['Last 7 days', 'Missed keys', 'Key shifting', 'Most misspelled words', 'Busiest keys', 'Last 30 days']);
    expect(find.descendant(of: find.byType(StatDial), matching: find.text('—')), findsNWidgets(2));      // both dials
    expect(find.textContaining('No taps counted yet'), findsOneWidget);
    expect(find.textContaining('None yet. Put a word right'), findsOneWidget);
    expect(find.text('Not enough typing counted yet.'), findsOneWidget);
    expect(find.byType(BoardFigure), findsNothing);

    // A full day: the dials, the tiles, the week, both pictures, the words and the keys.
    store.statsPage = {..._full, 'board': _board()};
    await draw();
    expect(statsPageSummary(store), '52 words a minute, 480 words today');
    expect(find.text('52'), findsWidgets);
    expect(find.text('96%'), findsWidgets);
    expect(find.text('today'), findsNWidgets(2));
    for (final label in ['Words today', 'Time typing today', 'In a row', 'Autocorrect fixes today', 'Words swiped today', 'Taken back today']) {
      expect(find.text(label), findsOneWidget, reason: label);
    }
    expect(find.text('480'), findsOneWidget);
    expect(find.text('4 days'), findsOneWidget);
    expect(find.text('12,840 in the last 30 days'), findsOneWidget);
    expect(find.text('93% of 510 kept in 30 days'), findsOneWidget);
    expect(find.byType(WeekChart), findsOneWidget);
    expect((card('Last 7 days').footer as Text).data, 'Fastest day kept: 9 Nov, 61 words a minute. Fullest: 7 Nov, 1,204 words.');
    expect(find.byType(BoardFigure), findsNWidgets(2));
    expect(tester.widgetList<BoardFigure>(find.byType(BoardFigure)).map((f) => (f.view, f.everyday)).toList(), [(BoardView.misses, true), (BoardView.areas, true)]);
    expect(find.textContaining('Missed most: e 12% (r in its place)'), findsOneWidget);
    expect(find.textContaining('Adaptive keys has 1 key’s touch area off the middle'), findsOneWidget);
    expect(find.text('where Adaptive keys has the key listening'), findsOneWidget);
    expect(find.textContaining('the fix'), findsNothing);
    // The slips as chips, the words put right, the busiest keys.
    expect(find.text('11×', findRichText: true), findsNothing);
    expect(find.textContaining('11×'), findsOneWidget);
    expect(find.textContaining('teh', findRichText: true), findsOneWidget);
    expect(find.textContaining('7×'), findsOneWidget);
    expect(find.text('11.2%'), findsOneWidget);
    expect((card('Busiest keys').footer as Text).data, 'Each letter’s share of the 48,211 letters counted.');
    expect((card('Missed keys').footer as Text).data, contains('Counted on the QWERTY keys.'));

    // What the week says to a screen reader is the week.
    final week = tester.widget<WeekChart>(find.byType(WeekChart));
    expect(week.said, contains('Sat: 1,204 words at 49 a minute'));
    expect(week.said, isNot(contains('Fri')));

    // Adaptive keys off: the picture stays, and the card says nothing has shifted and where to turn it on.
    store.statsPage = {..._full, 'board': _board(), 'adaptive': false};
    await draw();
    expect((card('Key shifting').footer as Text).data, contains('Adaptive keys is off'));
    expect(find.textContaining('every key listens at its middle'), findsOneWidget);

    // Corrections not learned: the words card says so instead of listing.
    await store.set('learn_mistakes', false);
    store.statsPage = {..._full, 'board': _board(), 'misspelled': const []};
    await draw();
    expect(find.textContaining('Learn from my corrections is off'), findsOneWidget);
    await store.set('learn_mistakes', true);

    // The promise at the foot, and the switch: off, the page says nothing is counted.
    final foot = statsPage(context, store).whereType<PaneSection>().last;
    final says = (foot.footer as Text).data!;
    for (final phrase in ['Counts only, never what you typed', 'which key for which', 'Kept on this phone and sent nowhere', 'password fields or while incognito']) {
      expect(says, contains(phrase));
    }
    expect(foot.children.whereType<SettingRow>().map((r) => r.label).toList(), ['Count my typing', 'Reset stats']);
    await store.set('typing_stats', false);
    await draw();
    expect(statsPageSummary(store), 'Off');
    expect(find.textContaining('Typing stats are off: nothing is being counted'), findsOneWidget);
    await store.set('typing_stats', true);

    // Search finds the page's cards, and the old page under Typing points here.
    for (final query in ['missed keys', 'key shifting', 'misspelled', 'busiest']) {
      final hits = searchSettings(context, store, settingsSections(store), query);
      expect(hits.any((h) => h.path.last.id == statsPageId), isTrue, reason: query);
    }
    final typing = settingsSections(store).firstWhere((s) => s.id == 'typing').parts!(store).firstWhere((s) => s.id == 'stats');
    final link = typing.page(context, store).whereType<PaneSection>().first.children.whereType<SettingRow>().first;
    expect(link.label, 'Your stats in pictures');
    expect(link.onPress, isNotNull);
  });
}
