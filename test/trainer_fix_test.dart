// The trainer's test of its fixes as the pages read it (lib/trainer/trainer_fix.dart; Matt, 2026-11-11: "make each run
// after the first test a new fixes and a b test the accuracy and don't suggest switching until its proven we got a
// better result"): what the keyboard sends read into something a page can ask, the sentences said of it, that only
// the keyboard's own verdict is ever called proof, and the picture of the runs that tested a fix drawn at a phone's
// width, a narrow one and a tablet's. Run with `flutter test`.

import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/trainer/trainer_board.dart';
import 'package:dev_keyboard/trainer/trainer_fix.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

/// As Android's TrainerMaps.fix sends it: [splits] are the runs that tested a fix, each the taps it won and lost of 170.
Map<String, Object?> sent({int number = 3, String verdict = 'testing', String? hold, List<(int, int)> splits = const [], int oneIn = 0, bool adaptive = false, int differs = 10, bool tested = true, int taken = 0, int dropped = 0}) {
  final won = splits.fold(0, (n, s) => n + s.$1);
  final lost = splits.fold(0, (n, s) => n + s.$2);
  return {
    'number': number, 'last': splits.isEmpty ? 0 : number, 'tested': tested, 'taken': taken, 'dropped': dropped, 'adaptive': adaptive, 'differs': differs, 'verdict': verdict, 'hold': hold,
    'runs': splits.length, 'taps': 170 * splits.length, 'asIs': 160 * splits.length - won, 'fixed': 160 * splits.length - lost, 'won': won, 'lost': lost,
    'oneIn': oneIn, 'needs': 3, 'bar': 100, 'kept': 8,
    'tests': [
      for (var i = 0; i < splits.length; i++) {'number': number - splits.length + 1 + i, 'taps': 170, 'asIs': 160 - splits[i].$1, 'fixed': 160 - splits[i].$2, 'won': splits[i].$1, 'lost': splits[i].$2},
    ],
  };
}

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

  group('what the keyboard sent, read', () {
    test('the runs that tested a fix, each read both ways, and what they add up to', () {
      final fix = FixData.of(sent(splits: [(4, 1), (9, 1), (12, 2)], verdict: 'testing', hold: 'steady', oneIn: 3200));
      expect(fix.number, 3);
      expect(fix.runs, 3);
      expect(fix.taps, 510);
      expect(fix.won, 25);
      expect(fix.lost, 4);
      expect(fix.tests.map((r) => r.number), [1, 2, 3]);
      expect(fix.tests.last.fixedShare, closeTo(158 * 100 / 170, 1e-9));
      expect(fix.tests.last.asIsShare, closeTo(148 * 100 / 170, 1e-9));
      expect(fix.fixedShare, closeTo(476 * 100 / 510, 1e-9));
      expect(fix.asIsShare, closeTo(455 * 100 / 510, 1e-9));
      expect(fix.proven, isFalse);
    });

    test('nothing sent, or something that is no test at all, is a test with nothing in it', () {
      for (final raw in <Object?>[null, 'fix', 3, <Object?>[], <String, Object?>{}, {'number': 'three', 'tests': 'none', 'run': 7, 'verdict': null}]) {
        final fix = FixData.of(raw);
        expect(fix.number, 0, reason: '$raw');
        expect(fix.runs, 0);
        expect(fix.tests, isEmpty);
        expect(fix.run, isNull);
        expect(fix.proven, isFalse);
        expect(fix.asIsShare, isNull);
        expect(fix.title, 'No fix yet');
        expect(fix.status, 'Nothing to test');
      }
      // A run with no taps is no run, and counts are kept inside their taps.
      expect(FixRunData.of({'number': 2, 'taps': 0, 'asIs': 0, 'fixed': 0}), isNull);
      final odd = FixRunData.of({'number': 2, 'taps': 100, 'asIs': 140, 'fixed': -3, 'won': 500, 'lost': 2})!;
      expect([odd.asIs, odd.fixed, odd.won, odd.lost], [100, 0, 100, 2]);
    });

    test('only the keyboard’s own verdict is proof', () {
      // Far ahead on every count, and the keyboard has not said proven: it is not offered.
      expect(FixData.of(sent(splits: [(30, 0), (30, 0), (30, 0), (30, 0)], verdict: 'testing', hold: 'last', oneIn: 1000000)).proven, isFalse);
      expect(FixData.of(sent(splits: [(30, 0), (30, 0), (30, 0)], verdict: 'none')).proven, isFalse);
      expect(FixData.of(sent(splits: [(30, 0), (30, 0), (30, 0)], verdict: 'worse')).proven, isFalse);
      // Proven with no fix the runs tested is nothing to switch to.
      expect(FixData.of(sent(number: 0, splits: [(9, 1), (9, 1), (9, 1)], verdict: 'proven')).proven, isFalse);
      expect(FixData.of({...sent(splits: [(9, 1), (9, 1), (9, 1)], verdict: 'proven'), 'last': 0}).proven, isFalse);
      // A fix made since for a run that was left: the one the runs tested is the one named and offered.
      final left = FixData.of({...sent(number: 4, splits: [(9, 1), (9, 1), (9, 1)], verdict: 'proven', tested: false), 'last': 3});
      expect(left.proven, isTrue);
      expect(left.title, 'Fix 3');
      expect(left.switchSaid, contains('where fix 3 has it'));
      expect(FixData.of(sent(splits: [(9, 1), (9, 1), (9, 1)], verdict: 'proven')).proven, isTrue);
    });
  });

  group('what is said of it', () {
    test('a share to a tenth of a percent, and a count as words', () {
      expect(shareSaid(96.4), '96.4%');
      expect(shareSaid(90), '90%');
      expect(shareSaid(100), '100%');
      expect(shareSaid(null), '—');
      expect(shareSaid(-1), '—');
    });

    test('where the test stands, from no run to proof', () {
      expect(FixData.of(null).standing(storyRuns: 0), 'The first run is typed on your keyboard as it is, and measures where your taps land. Each run after it tests a fix.');
      expect(FixData.of(null).standing(storyRuns: 2), startsWith('A fix moves each key’s touch area toward where your taps land. There is nothing to test yet'));
      // Runs behind it and no fix yet (an earlier version's runs, or taps that only now land far enough off): the
      // keyboard says the next run will be typed on one.
      final first = FixData.of({...sent(number: 0, verdict: 'none'), 'next': true});
      expect(first.title, 'The first fix');
      expect(first.status, 'Next run tests one');
      expect(first.standing(storyRuns: 6), startsWith('The next run is typed on a fix: the keys’ touch areas a step nearer to where your taps have landed.'));
      final next = FixData.of(sent(number: 1, tested: false, differs: 1, verdict: 'none'));
      expect(next.status, 'Next run tests it');
      expect(next.standing(storyRuns: 1), startsWith('It moves 1 key’s touch area toward where your taps land. The next run is typed on it'));
      final one = FixData.of(sent(number: 1, splits: [(1, 0)], hold: 'runs'));
      expect(one.standing(storyRuns: 2), 'Over 1 run the fix read 1 tap right that your keyboard as it is read wrong, and 0 the other way. A fix is proven on 3 runs or more: 2 more to go.');
      final level = FixData.of(sent(splits: [(0, 0), (0, 0), (0, 0)], hold: 'behind'));
      expect(level.standing(storyRuns: 4), 'Over 3 runs the fix and your keyboard as it is read every tap alike. It has not read more taps right than your keyboard.');
      final proven = FixData.of(sent(splits: [(9, 1), (10, 1), (12, 1)], verdict: 'proven', oneIn: 2610893));
      expect(proven.status, 'Proven better');
      expect(proven.standing(storyRuns: 4), 'Over 3 runs the fix read 31 taps right that your keyboard as it is read wrong, and 3 the other way. Chance would split them that far less than once in a million tries, and it led steadily from run to run.');
      expect(FixData.of(sent(splits: [(9, 1), (10, 1), (12, 1)], verdict: 'proven', oneIn: 12345)).standing(storyRuns: 4), contains('less than once in 12,345 tries'));
    });

    test('a fix that was taken, and one that was dropped', () {
      final taken = FixData.of(sent(number: 4, taken: 4, adaptive: true, verdict: 'none'));
      expect(taken.status, 'In everyday typing');
      expect(taken.title, 'Fix 4');
      // A later fix tested against it stands as any other.
      final later = FixData.of(sent(number: 6, taken: 4, adaptive: true, splits: [(2, 1), (1, 1)], hold: 'runs'));
      expect(later.status, 'Not proven yet');
      expect(later.title, 'Fix 6');
      final dropped = FixData.of(sent(number: 5, dropped: 5, verdict: 'none'));
      expect(dropped.status, 'Dropped');
      expect(dropped.title, 'Fix 5');
      // Once a later fix has been made and tested, the dropped one is history.
      expect(FixData.of(sent(number: 6, dropped: 5, splits: [(3, 1)], hold: 'runs')).status, 'Not proven yet');
    });

    test('a run’s two readings, in a line', () {
      expect(FixRunData.of({'number': 3, 'taps': 171, 'asIs': 157, 'fixed': 165, 'won': 9, 'lost': 1})!.said, 'The same 171 taps, read both ways: 9 only the fix read right, 1 only your keyboard did.');
      expect(FixRunData.of({'number': 3, 'taps': 1, 'asIs': 1, 'fixed': 1, 'won': 0, 'lost': 0})!.said, 'The same 1 tap, read both ways: the two read every one alike.');
    });

    test('what switching does depends on whether Adaptive keys is added', () {
      const proof = [(9, 1), (10, 1), (12, 1)];
      expect(FixData.of(sent(adaptive: false, splits: proof, verdict: 'proven')).switchSaid, startsWith('Adds Adaptive keys, which is not added, with each touch area where fix 3 has it.'));
      expect(FixData.of(sent(adaptive: true, splits: proof, verdict: 'proven')).switchSaid, startsWith('Moves Adaptive keys’ touch areas to where fix 3 has them.'));
    });
  });

  group('the runs that tested a fix, drawn', () {
    testWidgets('two lines over the runs, the fix under each, and both totals in the legend', (tester) async {
      final fix = FixData.of(sent(number: 4, splits: [(4, 1), (9, 1), (12, 2), (14, 1)], verdict: 'proven', oneIn: 90000));
      for (final theme in ['dark', 'light']) {
        for (final width in [280.0, 390.0, 900.0]) {
          await show(tester, FixFigure(fix: fix), width: width, theme: theme);
          expect(tester.takeException(), isNull, reason: '$theme at $width');
          expect(tester.getSize(find.byType(FixFigure)).height, greaterThan(70));
        }
      }
      // 640 − 5 of 680 with the fix, 640 − 39 as it is.
      expect(find.text('with the fix 93.4%'), findsOneWidget);
      expect(find.text('your keyboard as it is 88.4%'), findsOneWidget);
      expect(find.text('fix 1'), findsOneWidget);
      expect(find.text('4'), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp(r'^Taps on their key in each run that tested a fix, oldest first\. Over the 4 runs: 93\.4% with the fix, 88\.4% as your keyboard is\.$')), findsOneWidget);
    });

    testWidgets('one run is two points, eight are a full window, and none is nothing', (tester) async {
      await show(tester, FixFigure(fix: FixData.of(sent(number: 1, splits: [(3, 1)], hold: 'runs'))));
      expect(tester.takeException(), isNull);
      expect(find.text('fix 1'), findsOneWidget);
      await show(tester, FixFigure(fix: FixData.of(sent(number: 9, splits: [for (var i = 0; i < 8; i++) (i + 2, 1)], verdict: 'proven'))), width: 280);
      expect(tester.takeException(), isNull);
      expect(find.text('fix 2'), findsOneWidget);
      expect(find.text('9'), findsOneWidget);
      await show(tester, FixFigure(fix: FixData.of(null)));
      expect(tester.getSize(find.byType(FixFigure)), Size.zero);
      expect(const FixFigure(fix: FixData()).said, 'No run has tested a fix yet.');
    });
  });
}
