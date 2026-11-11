// The trainer's pictures (lib/trainer/trainer_board.dart; Matt, 2026-11-11: "Show inforgraphics on the trainer with
// charts and stuff to show where the mistakes happen and what has shifted where using a simplified keyboard display"):
// what the keyboard sends read into a board, what each picture says of it in a line, and that both pictures and the
// runs' lines draw at a phone's width, a narrow one and a tablet's, with nothing measured and with everything.
// Run with `flutter test`.

import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/trainer/trainer_board.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

/// QWERTY as Android's TrainerMaps.board sends it, with [over] laid on the keys it names.
Map<String, Object?> qwerty({Map<String, Map<String, Object?>> over = const {}, List<Object?> slips = const [], String? lean}) {
  const rows = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
  const inset = [0.0, 0.5, 1.5];
  final keys = [
    for (var r = 0; r < rows.length; r++)
      [
        for (var i = 0; i < rows[r].length; i++)
          <String, Object?>{'key': rows[r][i], 'x': inset[r] + i, 'taps': 0, 'missed': 0, 'landX': 0.0, 'landY': 0.0, 'landed': 0, 'shiftX': 0.0, 'shiftY': 0.0, 'moved': false, ...?over[rows[r][i]]},
      ],
  ];
  return {'rows': keys, 'units': 10.0, 'slips': slips, 'maxShift': 0.3, 'lean': lean};
}

/// Runs as the keyboard gives them in `history`.
List<Map<Object?, Object?>> runsOf(List<(int accuracy, int firstTry, int words)> list) => [
      for (final (accuracy, firstTry, words) in list) {'wpm': 40, 'accuracy': accuracy, 'firstTry': firstTry, 'words': words, 'practice': false},
    ];

Future<void> show(WidgetTester tester, Widget child, {double width = 390, String theme = 'dark'}) async {
  tester.view.physicalSize = Size(width * 3, 3000);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  final sheet = (await tester.runAsync(() => loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css'])))!;
  installSettingsStyles(sheet);
  await tester.pumpWidget(WidgetsApp(
    color: const Color(0xFF000000),
    builder: (context, _) => GlacierTheme(
      sheet: sheet,
      attrs: {'platform': 'mobile', 'theme': theme},
      child: GlacierScope(attrs: const {'hue': 'orange'}, child: Align(alignment: Alignment.topCenter, child: Padding(padding: const EdgeInsets.all(16), child: child))),
    ),
  ));
  await tester.pump();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final measured = qwerty(
    over: {
      'e': {'taps': 120, 'missed': 6, 'landX': 0.14, 'landY': 0.08, 'landed': 120, 'shiftX': 0.12, 'shiftY': 0.06, 'moved': true},
      'r': {'taps': 60, 'missed': 9, 'landX': -0.2, 'landY': 0.1, 'landed': 60, 'shiftX': -0.08, 'shiftY': 0.0, 'moved': true},
      'o': {'taps': 80, 'missed': 6, 'landX': 0.05, 'landY': 0.02, 'landed': 80},
      'q': {'taps': 4, 'missed': 3},
      'z': {'taps': 12, 'missed': 0, 'shiftX': 0.0, 'shiftY': -0.03},
    },
    slips: [
      {'pressed': 'e', 'meant': 'r', 'count': 7},
      {'pressed': 't', 'meant': 'r', 'count': 2},
      {'pressed': 'p', 'meant': 'o', 'count': 5},
    ],
    lean: 'Your taps land a little to the right of a key’s middle.',
  );

  group('what the keyboard sends, read', () {
    test('the keys row by row, each with what was measured of it', () {
      final board = TrainerBoardData.of(measured)!;
      expect(board.rows.map((r) => r.map((k) => k.key).join()), ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']);
      expect(board.units, 10);
      expect(board.keyOf('a')!.x, 0.5);
      expect(board.keyOf('z')!.row, 2);
      expect(board.taps, 276);
      expect(board.missed, 24);
      expect(board.keyOf('r')!.rate, closeTo(0.15, 1e-9));
      expect(board.shifted, 3);
      expect(board.moved, 2);
      expect(board.landed, 3);
      expect(board.keyOf('e')!.shift, closeTo(0.1342, 1e-3));
      expect(board.insteadOf('r'), 'e');
      expect(board.insteadOf('e'), isNull);
      // What the keyboard says was hit in a key's place is gone by before the slips drawn: its lists name that one.
      final said = TrainerBoardData.of(qwerty(over: {'h': {'instead': 'j'}, 'g': {'instead': ''}}, slips: [{'pressed': 'b', 'meant': 'h', 'count': 1}, {'pressed': 'j', 'meant': 'h', 'count': 1}, {'pressed': 'f', 'meant': 'g', 'count': 2}]))!;
      expect(said.insteadOf('h'), 'j');
      expect(said.insteadOf('g'), 'f');
      expect(board.lean, 'Your taps land a little to the right of a key’s middle.');
      expect(board.maxShift, 0.3);
    });

    test('nothing to draw where no keys came, and what is not a number is nothing', () {
      expect(TrainerBoardData.of(null), isNull);
      expect(TrainerBoardData.of('qwerty'), isNull);
      expect(TrainerBoardData.of({'rows': <Object?>[]}), isNull);
      expect(TrainerBoardData.of({'rows': [<Object?>[]], 'units': 10}), isNull);
      final odd = TrainerBoardData.of({
        'rows': [
          [
            {'key': 'a', 'x': 0, 'taps': 5, 'missed': 9, 'landX': double.nan, 'shiftX': 'far', 'landed': -4},
            {'key': '', 'x': 1},
            'b',
            {'key': 'c', 'x': 14.0},
          ],
        ],
        'units': 3,
        'slips': [
          {'pressed': 'a', 'meant': 'c', 'count': 0},
          'x',
        ],
      })!;
      expect(odd.rows.single.map((k) => k.key), ['a', 'c']);
      // Never more misses than taps, and the board is as wide as its furthest key whatever it was told.
      expect(odd.keyOf('a')!.missed, 5);
      expect(odd.keyOf('a')!.landX, 0);
      expect(odd.keyOf('a')!.shiftX, 0);
      expect(odd.keyOf('a')!.landed, 0);
      expect(odd.units, 15);
      expect(odd.slips, isEmpty);
      expect(odd.maxShift, 0.3);
    });
  });

  group('what a picture says in a line', () {
    test('where the mistakes happen: the keys missed most of those with taps enough, and what was hit in their place', () {
      final board = TrainerBoardData.of(measured)!;
      // q was missed three times in four, on four taps: too few to say over many runs.
      expect(board.missesSaid(floor: 10), 'Missed most: r 15% (e in its place), o 8% (p in its place), e 5%.');
      expect(board.missesSaid(floor: 2), startsWith('Missed most: q 75%, r 15% (e in its place)'));
      expect(board.worst(floor: 10).map((k) => k.key), ['r', 'o', 'e']);
      expect(TrainerBoardData.of(qwerty())!.missesSaid(floor: 10), 'No taps measured yet.');
      expect(TrainerBoardData.of(qwerty(over: {'a': {'taps': 30}}))!.missesSaid(floor: 10), 'Every tap hit the key it was meant for.');
      expect(TrainerBoardData.of(qwerty(over: {'a': {'taps': 3, 'missed': 1}}))!.missesSaid(floor: 10), 'No key has been missed often enough to say yet.');
      expect(TrainerBoardData.of(qwerty(over: {'a': {'taps': 400, 'missed': 1}}))!.missesSaid(floor: 10), 'Missed most: a under 1%.');
    });

    test('what has shifted: how many keys, and the one furthest off', () {
      final board = TrainerBoardData.of(measured)!;
      expect(board.areasSaid(), '3 keys’ touch areas have moved. Furthest: e, 12% right and 6% low.');
      expect(TrainerBoardData.of(qwerty())!.areasSaid(), 'No key has enough taps yet to say where they land.');
      expect(TrainerBoardData.of(qwerty(over: {'a': {'landed': 20, 'landX': 0.01}}))!.areasSaid(), 'No touch area has moved far enough to be in use yet.');
      expect(TrainerBoardData.of(qwerty(over: {'a': {'shiftX': -0.05}}))!.areasSaid(), '1 key’s touch area has moved. Furthest: a, 5% left.');
    });

    test('a run’s own picture says what that run moved', () {
      // z had moved before it (an everyday slip, or an earlier run).
      expect(TrainerBoardData.of(measured)!.areasSaid(run: true), 'This run moved 2 keys’ touch areas a step toward where their taps land; 1 had moved before it.');
      expect(TrainerBoardData.of(qwerty())!.areasSaid(run: true), 'This run moved no touch area.');
      expect(TrainerBoardData.of(qwerty(over: {'a': {'shiftX': 0.05}, 'b': {'shiftY': 0.05}}))!.areasSaid(run: true), 'This run moved no touch area; 2 had moved before it.');
      // Moved, and not yet far enough off to be in use: it is counted all the same.
      expect(TrainerBoardData.of(qwerty(over: {'a': {'moved': true}}))!.areasSaid(run: true), 'This run moved 1 key’s touch area a step toward where its taps land.');
    });

    test('an offset in words', () {
      expect(offsetSaid(0.12, 0.06), '12% right and 6% low');
      expect(offsetSaid(-0.2, 0), '20% left');
      expect(offsetSaid(0, -0.04), '4% high');
      expect(offsetSaid(0.001, 0), 'a hair');
    });
  });

  group('the runs as lines', () {
    test('each run’s share of taps on their key and of words right first time, where it has them', () {
      final runs = runsOf([(95, 40, 45), (-1, 30, 45), (97, 45, 45)])
        ..add({'wpm': 40, 'accuracy': 99, 'words': 45})
        ..add({'wpm': 40, 'accuracy': 90, 'firstTry': 4, 'words': 0});
      expect(RunLines.accuracy(runs), [95, null, 97, 99, 90]);
      final first = RunLines.firstTime(runs);
      expect(first[0], closeTo(88.9, 0.05));
      expect(first[2], 100);
      // Not kept by a run from before this was drawn, and no share of no words.
      expect(first[3], isNull);
      expect(first[4], isNull);
    });

    test('the foot of the chart is the next five under the lowest point with room, never under nothing', () {
      expect(RunLines.footOf([95, 97, null, 89]), 85);
      expect(RunLines.footOf([100, 100]), 95);
      expect(RunLines.footOf([2, 60]), 0);
      expect(RunLines.footOf([88]), 85);
      expect(RunLines.footOf([null]), 0);
      expect(RunLines.footOf([]), 0);
    });

    testWidgets('they draw, with the latest of each said beside its mark', (tester) async {
      await show(tester, RunLines(runs: runsOf([(92, 38, 45), (95, 40, 45), (97, 44, 45)])));
      expect(tester.takeException(), isNull);
      expect(find.text('taps on their key 97%'), findsOneWidget);
      expect(find.text('words right first time 98%'), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp(r'Accuracy by run, oldest first\. Taps on their key: 97% in the latest run\. Words right first time: 98%\.')), findsOneWidget);
      // One run is a point, and none is nothing at all.
      await show(tester, RunLines(runs: runsOf([(92, 38, 45)])));
      expect(tester.takeException(), isNull);
      await show(tester, RunLines(runs: [
        {'wpm': 40, 'accuracy': -1, 'words': 45}
      ]));
      expect(tester.takeException(), isNull);
      expect(tester.getSize(find.byType(RunLines)), Size.zero);
    });
  });

  group('the keyboard, drawn', () {
    for (final width in [280.0, 390.0, 900.0]) {
      for (final theme in ['dark', 'light']) {
        testWidgets('both pictures draw at $width wide, $theme', (tester) async {
          final board = TrainerBoardData.of(measured)!;
          for (final view in BoardView.values) {
            for (final run in [false, true]) {
              await show(tester, BoardFigure(key: const ValueKey('figure'), board: board, view: view, run: run), width: width, theme: theme);
              expect(tester.takeException(), isNull, reason: '$view, run $run');
              final size = tester.getSize(find.byKey(const ValueKey('figure')));
              expect(size.width, closeTo(width - 32, 0.5));
              // Three rows of keys and the space bar's, a legend and a line: more than a strip, less than a screen.
              expect(size.height, inInclusiveRange(110, 420));
            }
          }
        });
      }
    }

    testWidgets('a board with nothing measured draws too, and says so', (tester) async {
      final board = TrainerBoardData.of(qwerty())!;
      await show(tester, BoardFigure(board: board, view: BoardView.misses));
      expect(tester.takeException(), isNull);
      expect(find.text('No taps measured yet.'), findsOneWidget);
      await show(tester, BoardFigure(board: board, view: BoardView.areas));
      expect(tester.takeException(), isNull);
      expect(find.text('No key has enough taps yet to say where they land.'), findsOneWidget);
    });

    testWidgets('each picture has its legend, its line, and words for a screen reader', (tester) async {
      final board = TrainerBoardData.of(measured)!;
      await show(tester, BoardFigure(board: board, view: BoardView.misses));
      expect(find.text('never missed to 1 in 5 or more'), findsOneWidget);
      expect(find.text('the key hit in its place'), findsOneWidget);
      expect(find.text('Missed most: r 15% (e in its place), o 8% (p in its place), e 5%.'), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp(r'^The keyboard, each key tinted by how often it is missed\. Missed most: r 15%')), findsOneWidget);

      await show(tester, BoardFigure(board: board, view: BoardView.areas));
      expect(find.text('where your taps land'), findsOneWidget);
      expect(find.text('how far the touch area has moved'), findsOneWidget);
      expect(find.text('moved before this run'), findsNothing);
      expect(find.bySemanticsLabel(RegExp(r'^The keyboard, with where the taps land and how far each touch area has moved\. 3 keys’ touch areas have moved')), findsOneWidget);

      // A run's own: what it moved is told from what had moved before it (z here).
      await show(tester, BoardFigure(board: board, view: BoardView.areas, run: true));
      expect(find.text('where this run’s taps landed'), findsOneWidget);
      expect(find.text('a touch area this run moved'), findsOneWidget);
      expect(find.text('moved before this run'), findsOneWidget);
    });

    testWidgets('a layout with eleven keys to a row is drawn to its own width', (tester) async {
      final wide = TrainerBoardData.of({
        'rows': [
          [for (var i = 0; i < 11; i++) {'key': 'qwertzuiopü'[i], 'x': i.toDouble(), 'taps': 20, 'missed': i}],
          [for (var i = 0; i < 11; i++) {'key': 'asdfghjklöä'[i], 'x': i.toDouble(), 'shiftX': 0.3, 'shiftY': -0.3, 'landed': 30, 'landX': 0.9, 'landY': -0.9}],
          [for (var i = 0; i < 7; i++) {'key': 'yxcvbnm'[i], 'x': 2.0 + i}],
        ],
        'units': 11.0,
        'slips': [
          {'pressed': 'ü', 'meant': 'p', 'count': 3},
          {'pressed': '?', 'meant': 'p', 'count': 3},
        ],
      })!;
      expect(wide.units, 11);
      for (final view in BoardView.values) {
        await show(tester, BoardFigure(board: wide, view: view), width: 320);
        expect(tester.takeException(), isNull);
      }
    });
  });
}
