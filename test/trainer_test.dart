// The typing trainer's pages (lib/trainer/), over a mocked `dev_keyboard/trainer` channel that answers as Android's
// Trainer.kt does: the first page and its switch, which is off unless turned on; that a run is refused where Ghost
// Keyboard is not the keyboard in use; a run from its first key to its results; a trial layout said and never set
// from here; the test of the fixes (a run after the first is typed on one and says so, its results set the two readings
// of its taps side by side, and switching is offered only where the keyboard says a fix is proven); the profile; that
// an iPhone has no Trainer at all; and that on Android its row stands at the top of Settings, in one card with Setup.
// And the pictures of the keyboard (trainer_board.dart, with tests of its own in trainer_board_test.dart): that each
// page that has one draws it from what the keyboard sent.
// Run with `flutter test`.

import 'package:dev_keyboard/market/market_cards.dart';
import 'package:dev_keyboard/settings/kit/settings_kit.dart';
import 'package:dev_keyboard/settings/panes.dart';
import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/settings/settings_screen.dart';
import 'package:dev_keyboard/settings_store.dart';
import 'package:dev_keyboard/trainer/trainer_board.dart';
import 'package:dev_keyboard/trainer/trainer_screen.dart';
import 'package:dev_keyboard/trainer/trainer_store.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

const _channel = MethodChannel('dev_keyboard/trainer');
const _settings = MethodChannel('dev_keyboard/settings');

const _story = 'The old keeper lived alone at the end of the land.';

/// The test of the fixes as Android's TrainerMaps.fix sends it: fix [number] in hand, and [tests] runs that each
/// tested one, 170 taps a run, the fix reading [won] taps right that the keyboard as it is read wrong and [lost] the
/// other way in each.
Map<String, Object?> _fix({int number = 0, int? last, bool tested = true, int tests = 0, int won = 9, int lost = 1, String verdict = 'none', String? hold, int taken = 0, int dropped = 0, bool adaptive = false, int differs = 0, int oneIn = 0}) => {
      'number': number, 'last': last ?? (tests > 0 ? number : 0), 'tested': tested, 'taken': taken, 'dropped': dropped, 'adaptive': adaptive, 'differs': differs, 'verdict': verdict, 'hold': hold,
      'runs': tests, 'taps': 170 * tests, 'asIs': (160 - won) * tests, 'fixed': (160 - lost) * tests, 'won': won * tests, 'lost': lost * tests, 'ahead': won > lost ? tests : 0, 'behind': lost > won ? tests : 0,
      'asIsShare': tests == 0 ? -1.0 : ((160 - won) * 1000 / 170).round() / 10, 'fixedShare': tests == 0 ? -1.0 : ((160 - lost) * 1000 / 170).round() / 10,
      'oneIn': oneIn, 'needs': 3, 'bar': 100, 'kept': 8,
      'tests': [
        for (var i = 0; i < tests; i++) {'number': number - tests + 1 + i, 'taps': 170, 'asIs': 160 - won, 'fixed': 160 - lost, 'won': won, 'lost': lost},
      ],
    };

/// The keyboard's side: what it would answer, and every call it was sent.
class _Native {
  Map<String, Object?> state = {
    'selected': true, 'latin': true, 'english': true, 'layout': 'qwerty', 'trials': false, 'ownHeight': 'normal', 'ownHeightLabel': 'Normal',
    'next': {'title': 'The lighthouse cat', 'part': 1, 'parts': 6, 'words': 11},
    'stories': 8, 'storyParts': 48, 'canPractise': false, 'runs': 0, 'practiceRuns': 0, 'profileAt': 5, 'best': 0,
    'history': <Object?>[], 'mistakes': <Object?>[], 'pairs': <Object?>[], 'keys': <Object?>[], 'profile': null,
    'layouts': {'own': 'normal', 'ownLabel': 'Normal', 'home': 'normal', 'homeLabel': 'Normal', 'candidate': null, 'settled': false, 'scores': <Object?>[], 'suggestion': null},
    'adaptive': false, 'nudged': 0, 'learnMistakes': true, 'running': false,
    'fix': _fix(),
  };

  /// What `start` answers: a run, or why not.
  Map<String, Object?> run = {
    'ok': true, 'title': 'The lighthouse cat', 'part': 1, 'parts': 6, 'text': _story, 'words': 11, 'practice': false,
    'height': 'normal', 'heightLabel': 'Normal', 'trial': false, 'changed': false, 'ownHeightLabel': 'Normal',
    'fix': 0, 'fixKeys': 0,
  };

  /// The first run's: typed on the keyboard as it is, so it tested nothing and no key was moved for it.
  Map<String, Object?> result = {
    'counted': true, 'ms': 64000, 'wpm': 43, 'gross': 47, 'accuracy': 95, 'words': 11, 'right': 10, 'firstTry': 8, 'typed': 11,
    'missed': [{'meant': 'keeper', 'typed': 'keeepr'}], 'skipped': <Object?>[], 'extraWords': 0, 'keys': 60, 'backspaces': 3,
    'mended': [{'typed': 'teh', 'made': 'the', 'meant': 'the'}], 'spoiled': [{'typed': 'land', 'made': 'lane', 'meant': 'land'}], 'misfixed': <Object?>[], 'left': <Object?>[], 'undone': 0,
    'swiped': 0, 'picked': 0, 'spacesMissed': 0, 'straySpaces': 0, 'taps': 40, 'hits': 38, 'slips': 2, 'wrongKeys': 0, 'extraTaps': 0, 'omitted': 0, 'swaps': 1,
    'weakKeys': [{'key': 'o', 'missed': 2, 'n': 5, 'toward': 'p'}], 'weakPairs': [{'pair': 'ol', 'missed': 1, 'n': 2}],
    'nudged': <Object?>[], 'taught': 1, 'fix': {..._fix(), 'worn': 0, 'run': null},
    'title': 'The lighthouse cat', 'part': 1, 'parts': 6, 'practice': false, 'height': 'normal', 'heightLabel': 'Normal', 'trial': false, 'changed': false, 'moved': null,
    'layouts': {'scores': <Object?>[]}, 'runs': 1, 'profileAt': 5,
  };

  final List<MethodCall> calls = [];

  Future<Object?> answer(MethodCall call) async {
    calls.add(call);
    final args = call.arguments is Map ? call.arguments as Map : const {};
    switch (call.method) {
      case 'state':
      case 'again':
        return state;
      case 'setTrials':
        state = {...state, 'trials': args['on'] == true};
        return state;
      case 'start':
        return run;
      case 'finish':
        state = {...state, 'runs': result['runs']};
        return result;
      case 'apply':
        // Taking a fix makes it the keyboard as it is: nothing has tested one against it yet.
        if (args['fix'] == true) {
          final was = state['fix'] as Map;
          state = {...state, 'adaptive': true, 'fix': _fix(number: was['number'] as int, taken: was['number'] as int, adaptive: true)};
          return {...state, 'said': ['Adaptive keys added', 'Everyday typing reads your taps by fix ${was['number']} now']};
        }
        return {...state, 'said': ['Key height set to Short']};
      case 'forget':
        state = {...state, 'runs': 0, 'history': <Object?>[], 'profile': null, 'trials': false};
        return state;
      case 'look':
      case 'leave':
        return null;
    }
    throw MissingPluginException(call.method);
  }

  List<String> get sent => [for (final c in calls) c.method];
  Iterable<MethodCall> of(String method) => calls.where((c) => c.method == method);
}

/// The keyboard says something to the screen, as it does after each key of a run.
Future<void> _tell(WidgetTester tester, String method, [Object? args]) async {
  await tester.binding.defaultBinaryMessenger.handlePlatformMessage(_channel.name, const StandardMethodCodec().encodeMethodCall(MethodCall(method, args)), (_) {});
  await tester.pump();
}

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 6; i++) {
    await tester.pump(const Duration(milliseconds: 120));
  }
}

Future<void> _press(WidgetTester tester, Finder what) async {
  await tester.ensureVisible(what.first);
  await tester.pump();
  await tester.tap(what.first, warnIfMissed: false);
  await _settle(tester);
}

/// The trainer's screen over [native], on a phone's width and taller than any (the test font is wide). With [home],
/// that in its place: Settings' own first page, to find the trainer's row on.
Future<(SettingsStore, TrainerStore)> _open(WidgetTester tester, _Native native, {bool selected = true, Widget Function(SettingsStore store)? home}) async {
  tester.view.physicalSize = const Size(1170, 9000);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, native.answer);
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(_settings, (call) async => call.method == 'status' ? {'enabled': true, 'selected': selected} : null);
  addTearDown(() {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, null);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(_settings, null);
  });
  final sheet = (await tester.runAsync(() => loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css'])))!;
  installSettingsStyles(sheet);
  final store = SettingsStore();
  final trainer = TrainerStore();
  await tester.pumpWidget(WidgetsApp(
    color: const Color(0xFF000000),
    pageRouteBuilder: <T>(RouteSettings settings, WidgetBuilder builder) => PageRouteBuilder<T>(settings: settings, pageBuilder: (context, _, _) => builder(context)),
    builder: (context, child) => GlacierTheme(sheet: sheet, attrs: const {'platform': 'mobile', 'theme': 'dark'}, child: HapticsProvider(child: child!)),
    home: home?.call(store) ?? TrainerScreen(store: store, trainer: trainer),
  ));
  await _settle(tester);
  return (store, trainer);
}

Future<void> _close(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump(const Duration(seconds: 1));
}

SettingRow _row(WidgetTester tester, String label) => tester.widget<SettingRow>(find.byWidgetPredicate((w) => w is SettingRow && w.label == label).first);
String? _value(WidgetTester tester, String label) => (_row(tester, label).value as Text?)?.data;
String? _hint(WidgetTester tester, String label) => (_row(tester, label).hint as Text?)?.data;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('the first page offers the next story, and trying layouts is off unless turned on', (tester) async {
    final native = _Native();
    final (_, trainer) = await _open(tester, native);
    expect(native.sent, ['state']);
    expect(find.text('Type a story'), findsOneWidget);
    expect(_hint(tester, 'The lighthouse cat'), 'Part 1 of 6 · 11 words');

    // Off by default, and the page says what that means: the keyboard looks as the person set it.
    final toggle = _row(tester, 'Try different layouts').control as GlacierSwitch;
    expect(toggle.checked, isFalse);
    expect(trainer.flag('trials'), isFalse);
    final card = tester.widget<PaneSection>(find.byWidgetPredicate((w) => w is PaneSection && w.title == 'Next'));
    expect((card.footer as Text).data, contains('the keyboard looks exactly as you set it'));
    expect((card.footer as Text).data, contains('changes nothing you can see'));

    // Nothing to show yet: no history, no mistakes, and the profile waits for its five runs.
    expect(_value(tester, 'History'), 'None yet');
    expect(_row(tester, 'History').onPress, isNull);
    expect(_value(tester, 'Your profile'), 'After 5 runs');
    expect(_row(tester, 'Your profile').onPress, isNull);
    expect(find.text('Practise my mistakes'), findsNothing);

    // What a run never teaches, in the page's own words.
    final promise = tester.widgetList<PaneSection>(find.byType(PaneSection)).map((s) => s.footer).whereType<Text>().map((t) => t.data!).firstWhere((t) => t.contains('A story is not your writing'));
    for (final phrase in ['in a file of its own on this phone', 'next-word suggestions', 'Mockingbird', 'typing stats', 'anonymous report', 'Your corrections', 'worn only by the run that tests it', 'until it is proven and you switch to it']) {
      expect(promise, contains(phrase));
    }

    // The test of the fixes: nothing to test before the first run, what a run tests and what proof is said under it,
    // and no switch to tap.
    expect(_value(tester, 'No fix yet'), 'Nothing to test');
    expect(_hint(tester, 'No fix yet'), 'The first run is typed on your keyboard as it is, and measures where your taps land. Each run after it tests a fix.');
    final test = tester.widget<PaneSection>(find.byWidgetPredicate((w) => w is PaneSection && w.title == 'The test'));
    for (final phrase in ['Each run after it is typed on a new fix', 'reads every tap of that run twice', 'compared on the very same taps', 'proven on three runs or more', 'changes only if a fix is proven and you switch to it']) {
      expect((test.footer as Text).data, contains(phrase));
    }
    expect(find.byKey(const ValueKey('trainer-switch')), findsNothing);
    expect(find.byType(FixFigure), findsNothing);

    // Turned on, the keyboard is told at once, and the page says what the switch does and that nothing is set.
    await _press(tester, find.byType(GlacierSwitch));
    expect(native.of('setTrials').single.arguments, {'on': true});
    final on = tester.widget<PaneSection>(find.byWidgetPredicate((w) => w is PaneSection && w.title == 'Next'));
    for (final phrase in ['shorter or taller keys than your own (Normal)', 'read as each run starts', 'your own keys are back the moment a run ends or you leave', 'a height is suggested only once it is proven better']) {
      expect((on.footer as Text).data, contains(phrase));
    }
    await _close(tester);
  });

  testWidgets('a run is refused, and says why, where Ghost Keyboard is not the keyboard in use', (tester) async {
    final native = _Native()..state['selected'] = false;
    await _open(tester, native, selected: false);
    expect(find.textContaining('Ghost Keyboard is not the keyboard in use. A run would measure another keyboard'), findsOneWidget);
    expect(tester.widget<MarketButton>(find.byKey(const ValueKey('trainer-start'))).onPress, isNull);
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    expect(native.of('start'), isEmpty);
    expect(find.byType(TrainerRun), findsNothing);
    await _close(tester);

    // And where the page thought it was but the keyboard says otherwise as the run starts, it still does not begin.
    final late = _Native()..run = {'error': 'keyboard'};
    await _open(tester, late);
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    expect(late.of('start'), hasLength(1));
    expect(find.byType(TrainerRun), findsNothing);
    expect(find.textContaining('there is nothing of its own to measure'), findsOneWidget);
    await _close(tester);
  });

  testWidgets('a run shows the story, follows what the keyboard says of it, and ends in its results', (tester) async {
    final native = _Native();
    final (_, trainer) = await _open(tester, native);
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    expect(native.of('start').single.arguments, {'practice': false});
    expect(find.byType(TrainerRun), findsOneWidget);
    expect(find.text('The lighthouse cat · 1 of 6'), findsOneWidget);
    expect(tester.widget<StoryText>(find.byType(StoryText)).text, _story);
    expect(find.byKey(const ValueKey('trainer-trial')), findsNothing);
    // The first run tests nothing, and says what it is for.
    expect(tester.widget<Text>(find.byKey(const ValueKey('trainer-testing'))).data, 'The first run: typed on your keyboard as it is, to measure where your taps land.');

    // The field: it asks keyboards not to learn from it, keeps autocorrect, and its cursor cannot be moved.
    final field = tester.widget<EditableText>(find.byType(EditableText));
    expect(field.enableIMEPersonalizedLearning, isFalse);
    expect(field.autocorrect, isTrue);
    expect(field.enableSuggestions, isTrue);
    expect(field.enableInteractiveSelection, isFalse);
    expect(field.textCapitalization, TextCapitalization.sentences);
    expect(field.textInputAction, TextInputAction.done);

    // Typing tells the keyboard to look at the field again (a word tapped in its row is no key press).
    await tester.enterText(find.byType(EditableText), 'The old kee');
    await tester.pump();
    expect(native.of('look'), isNotEmpty);

    // The keyboard says where the run stands: two words done (one wrong), the third being typed.
    await _tell(tester, 'progress', {'fates': [1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0], 'current': 2, 'tail': 'kex', 'done': false, 'wpm': 38, 'ms': 4000});
    final story = tester.widget<StoryText>(find.byType(StoryText));
    expect(story.fates.take(3), [1, 2, 0]);
    expect(story.current, 2);
    expect(story.tail, 'kex');
    expect(tester.widget<Text>(find.byKey(const ValueKey('trainer-wpm'))).data, '38');
    expect(tester.widget<Text>(find.byKey(const ValueKey('trainer-count'))).data, '2 of 11 words · 0:04');
    expect(native.of('finish'), isEmpty);

    // The story done: the run ends by itself, once, and the results are the keyboard's own numbers.
    await _tell(tester, 'progress', {'fates': [1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1], 'current': 11, 'tail': '', 'done': true, 'wpm': 43, 'ms': 64000});
    await _settle(tester);
    expect(native.of('finish'), hasLength(1));
    expect(find.byType(TrainerRun), findsNothing);
    expect(find.text('43 words a minute'), findsOneWidget);
    expect(find.text('95% of taps on their key · 1:04 · 10 of 11 words right'), findsOneWidget);
    expect(_value(tester, 'Right first time'), '8 of 11');
    expect(_value(tester, 'Backspaces'), '3');
    expect(_value(tester, 'keeper'), 'typed keeepr');
    expect(_value(tester, 'Mended'), '1');
    expect(_hint(tester, 'Mended'), 'teh → the');
    expect(_value(tester, 'Spoiled'), '1');
    expect(_hint(tester, 'Spoiled'), 'land → lane');
    expect(_value(tester, 'The o key'), '2 of 5 missed');
    expect(_hint(tester, 'The o key'), 'Mostly p in its place.');
    expect(_value(tester, 'o then l'), '1 of 2 missed');
    // The first run measured, on the keyboard as it is: no fix was tested, and nothing is offered.
    expect(_hint(tester, 'Typed on your keyboard as it is'), startsWith('The first run measures where your taps land. The next run tests the first fix'));
    expect(_value(tester, 'Touch areas'), 'As your keyboard has them');
    expect(_hint(tester, 'Touch areas'), 'Where this run’s taps landed is measured for the next fix.');
    expect(find.byKey(const ValueKey('trainer-switch')), findsNothing);
    expect(_value(tester, 'Your corrections'), '1 mistake remembered');
    expect(_value(tester, 'Your profile'), '4 more runs to go');
    // No trial was on, so nothing is said of layouts.
    expect(find.text('Layout'), findsNothing);
    expect(find.text('Your own keys are back'), findsNothing);
    expect(trainer.run, isNull);

    // Done goes back to the first page; the next run starts from there or from the results.
    await _press(tester, find.text('Done'));
    expect(find.text('Results'), findsNothing);
    expect(find.text('Trainer'), findsOneWidget);
    await _close(tester);
  });

  testWidgets('a trial layout is said on the run and in its results, and the screen never sets one', (tester) async {
    final native = _Native()
      ..state['trials'] = true
      ..run = {..._Native().run, 'height': 'short', 'heightLabel': 'Short', 'trial': true, 'changed': true}
      ..result = {
        ..._Native().result,
        'height': 'short', 'heightLabel': 'Short', 'trial': true, 'changed': true,
        'layouts': {
          'candidate': 'short', 'candidateLabel': 'Short', 'homeLabel': 'Normal', 'pairs': 1, 'wins': 1, 'minPairs': 4, 'maxPairs': 10,
          'scores': [{'height': 'short', 'label': 'Short', 'runs': 1, 'score': 41.2, 'accuracy': 96}, {'height': 'normal', 'label': 'Normal', 'runs': 1, 'score': 39.5, 'accuracy': 94}],
        },
      };
    await _open(tester, native);
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    // The switch is the keyboard's to read as the run starts: the screen sends no layout of its own with it.
    expect(native.of('start').single.arguments, {'practice': false});
    expect(tester.widget<Text>(find.byKey(const ValueKey('trainer-trial'))).data, 'Trying Short keys for this run. Your own (Normal) are back when it ends.');
    await _press(tester, find.text('Finish'));
    expect(native.of('finish'), hasLength(1));
    expect(_value(tester, 'Layout'), 'Short keys, a trial');
    // One pair of runs decides nothing, and the page says so.
    expect(_hint(tester, 'Layout'), 'Short 41.2 over 1 run, Normal 39.5 over 1 run. Short scored higher in 1 of 1 pair of runs so far; nothing is decided before 4.');
    expect(_value(tester, 'Your own keys are back'), 'As you set them');
    // Nothing in any of it changed a setting.
    expect(native.sent.where((m) => m == 'apply'), isEmpty);
    await _close(tester);
  });

  testWidgets('leaving a run drops it, and a run dropped by the keyboard is said', (tester) async {
    final native = _Native();
    final (_, trainer) = await _open(tester, native);
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    expect(find.byType(TrainerRun), findsOneWidget);
    // The way back from a run is to the trainer's first page, and the keyboard is told the run is over.
    await _press(tester, find.bySemanticsLabel('Back'));
    expect(native.of('leave'), hasLength(1));
    expect(find.byType(TrainerRun), findsNothing);
    expect(find.text('Trainer'), findsOneWidget);
    expect(native.of('finish'), isEmpty);

    // The app was left while a run was on: the keyboard dropped it, and the page says so.
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    await _tell(tester, 'left');
    await _settle(tester);
    expect(trainer.run, isNull);
    expect(find.byType(TrainerRun), findsNothing);
    expect(find.textContaining('The run was stopped because the app was left'), findsOneWidget);

    // And the page itself going away drops whatever run is on.
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    final before = native.of('leave').length;
    await _close(tester);
    expect(native.of('leave').length, before + 1);
  });

  testWidgets('after five runs the profile says where the taps land, and switching to what was proven is the person’s own tap', (tester) async {
    final native = _Native()
      ..state.addAll({
        'runs': 6, 'best': 47, 'canPractise': true, 'adaptive': false,
        'fix': _fix(number: 5, tests: 4, verdict: 'proven', differs: 14, oneIn: 250000),
        'history': [
          for (var i = 0; i < 6; i++) {'at': 1762862400000 + i * 60000, 'title': 'The lighthouse cat', 'practice': false, 'height': 'normal', 'heightLabel': 'Normal', 'trial': false, 'ms': 60000, 'wpm': 47 - i, 'accuracy': 95, 'words': 45, 'right': 44},
        ],
        'mistakes': [{'word': 'because', 'typed': 'becuase', 'missed': 3, 'seen': 5, 'last': '00011', 'streak': 2}],
        'pairs': [{'pair': 'ou', 'missed': 9, 'n': 40, 'trend': -1}],
        'keys': [{'key': 'o', 'missed': 5, 'n': 60, 'toward': 'p', 'trend': 0}],
        'profile': {
          'runs': 6, 'wpmFirst': 39, 'wpmNow': 44, 'wpmBest': 47, 'accuracyFirst': 93, 'accuracyNow': 95, 'layout': 'qwerty',
          'lean': 'Your taps land a little to the right of a key’s middle and below it.', 'report': 'Over 6 runs: 44 words a minute (39 at first), 95% of taps on their key.',
          'keys': [{'key': 'e', 'dx': 0.05, 'dy': 0.12, 'samples': 130}, {'key': 'o', 'dx': 0.14, 'dy': 0.06, 'samples': 84}],
          'slips': [{'pressed': 'p', 'meant': 'o', 'count': 9}], 'mistakes': <Object?>[], 'pairs': <Object?>[],
          'suggestion': {'height': 'short', 'label': 'Short', 'score': 44.1, 'runs': 5, 'ownScore': 40.6, 'ownRuns': 5, 'pairs': 5, 'wins': 5},
          'file': '{\n  "format": "ghost-typing-profile"\n}',
        },
        'layouts': {
          'own': 'normal', 'ownLabel': 'Normal', 'home': 'short', 'homeLabel': 'Short', 'candidate': 'condensed', 'candidateLabel': 'Condensed', 'settled': false,
          'pairs': 2, 'wins': 1, 'minPairs': 4, 'maxPairs': 10,
          'scores': [{'height': 'short', 'label': 'Short', 'runs': 5, 'score': 44.1, 'accuracy': 96}, {'height': 'normal', 'label': 'Normal', 'runs': 5, 'score': 40.6}],
          'suggestion': {'height': 'short', 'label': 'Short', 'score': 44.1, 'runs': 5, 'ownScore': 40.6, 'ownRuns': 5, 'pairs': 5, 'wins': 5},
        },
      });
    await _open(tester, native);
    expect(find.text('47 words a minute'), findsOneWidget);
    expect(_value(tester, 'History'), '6 runs');
    expect(_value(tester, 'Your mistakes'), '1 word');
    expect(_value(tester, 'Your profile'), 'Ready');
    expect(find.byType(RunBars), findsOneWidget);
    expect(find.text('Practise my mistakes'), findsOneWidget);

    // The mistakes: the word, what was typed for it, how its latest tries went, and whether a pair is getting better.
    await _press(tester, find.text('Your mistakes'));
    expect(_hint(tester, 'because'), 'Typed becuase · missed 3 of 5 tries · right the last 2');
    expect(_value(tester, 'because'), '✗ ✗ ✗ ✓ ✓');
    expect(_value(tester, 'o then u'), '9 of 40');
    expect(_hint(tester, 'o then u'), 'Getting better.');
    expect(_hint(tester, 'The o key'), 'Mostly p in its place.');
    await _press(tester, find.bySemanticsLabel('Back'));

    await _press(tester, find.text('Your profile'));
    expect(find.text('44 words a minute'), findsOneWidget);
    expect(_value(tester, 'At first'), '39 words a minute · 93%');
    expect(_value(tester, 'Now'), '44 words a minute · 95%');
    expect(_hint(tester, 'On the whole'), 'Your taps land a little to the right of a key’s middle and below it.');
    // The key furthest off comes first.
    expect(_value(tester, 'The o key'), '14% right, 6% low');
    expect(_value(tester, 'The e key'), '5% right, 12% low');
    expect(_value(tester, 'p for o'), '9 slips');
    // Each height's usual score, with its taps on their key where the keyboard said them; the one still being tried
    // is not proven, and the one that was says how.
    expect(_value(tester, 'Normal (yours)'), '40.6 over 5 runs');
    expect(_value(tester, 'Short'), '44.1 · 96% over 5 runs');
    expect(_value(tester, 'Trying Condensed'), 'Not proven yet');
    expect(_hint(tester, 'Trying Condensed'), 'Condensed scored higher in 1 of 2 pairs of runs so far; nothing is decided before 4.');
    expect(_hint(tester, 'Use Short keys'), 'Proven better over 5 pairs of runs, of which it scored higher in 5: 44.1 against 40.6 on your own.');

    // The fix: proven, with both readings of the runs that tested one, and the switch.
    expect(_value(tester, 'Fix 5'), 'Proven better');
    expect(_hint(tester, 'Fix 5'), 'Over 4 runs the fix read 36 taps right that your keyboard as it is read wrong, and 4 the other way. Chance would split them that far less than once in 250,000 tries, and it led steadily from run to run.');
    expect(_value(tester, 'With the fix'), '93.5% of taps on their key');
    expect(_value(tester, 'Your keyboard as it is'), '88.8%');
    expect(_hint(tester, 'Your keyboard as it is'), 'The same 680 taps of 4 runs, read both ways.');
    expect(tester.widget<FixFigure>(find.byKey(const ValueKey('trainer-fix-lines'))).fix.tests, hasLength(4));

    // Switching is two taps of the person's own, each asking for just the one thing; nothing was switched before them.
    expect(native.of('apply'), isEmpty);
    expect(_hint(tester, 'Switch everyday typing to fix 5'), 'Adds Adaptive keys, which is not added, with each touch area where fix 5 has it. Like any nudge there they drift back as your aim comes right.');
    await _press(tester, find.byKey(const ValueKey('trainer-switch')));
    expect(native.of('apply').single.arguments, {'fix': true, 'height': false});
    expect(find.text('Everyday typing reads your taps by fix 5 now'), findsOneWidget);
    // Taken, it is the keyboard as it is: there is nothing left to switch to until a later fix is proven against it.
    expect(_value(tester, 'Fix 5'), 'In everyday typing');
    expect(_hint(tester, 'Fix 5'), 'Everyday typing reads your taps by fix 5. The next run tests a new fix against it.');
    expect(find.byKey(const ValueKey('trainer-switch')), findsNothing);
    await _press(tester, find.text('Use Short keys'));
    expect(native.of('apply').last.arguments, {'fix': false, 'height': true});

    // It is a typing profile, and can be seen as one.
    await _press(tester, find.text('See it as a typing profile'));
    expect(find.textContaining('"format": "ghost-typing-profile"'), findsOneWidget);

    // Forgetting is offered here too, and goes back to the first page.
    await _press(tester, find.text('Forget what the trainer learned'));
    expect(native.of('forget'), hasLength(1));
    await _settle(tester);
    expect(_value(tester, 'Your profile'), 'After 5 runs');
    await _close(tester);
  });

  testWidgets('a run after the first is typed on a fix and says so, and its results set the two readings side by side', (tester) async {
    // Two runs in, fix 2 in hand and tested once; the run about to start is typed on fix 3.
    final native = _Native()
      ..state.addAll({'runs': 2, 'best': 44, 'fix': _fix(number: 2, tests: 1, won: 4, verdict: 'testing', hold: 'runs', differs: 9)})
      ..run = {..._Native().run, 'fix': 3, 'fixKeys': 12}
      ..result = {
        ..._Native().result,
        'runs': 3, 'taps': 40, 'hits': 38, 'accuracy': 95,
        'nudged': [for (final k in 'aehnorst'.split('')) {'key': k, 'dx': 0.06, 'dy': 0.03}],
        'fix': {
          ..._fix(number: 3, tests: 2, won: 4, verdict: 'testing', hold: 'runs', differs: 8),
          'worn': 3, 'run': {'number': 3, 'taps': 40, 'asIs': 36, 'fixed': 38, 'won': 3, 'lost': 1},
        },
      };
    await _open(tester, native);
    // The first page: which fix is in hand, where it stands, and no switch while it is not proven.
    expect(_value(tester, 'Fix 2'), 'Not proven yet');
    expect(_hint(tester, 'Fix 2'), 'Over 1 run the fix read 4 taps right that your keyboard as it is read wrong, and 1 the other way. A fix is proven on 3 runs or more: 2 more to go.');
    expect(_value(tester, 'With the fix'), '93.5% of taps on their key');
    expect(_value(tester, 'Your keyboard as it is'), '91.8%');
    expect(find.byKey(const ValueKey('trainer-switch')), findsNothing);

    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    expect(tester.widget<Text>(find.byKey(const ValueKey('trainer-testing'))).data, 'Testing fix 3: 12 keys’ touch areas moved. Every tap is read both ways.');
    await _press(tester, find.text('Finish'));

    // The results: the same taps as the fix read them and as the keyboard as it is would have, and where that leaves the test.
    expect(_value(tester, 'With fix 3'), '38 of 40 taps · 95%');
    expect(_value(tester, 'Your keyboard as it is'), '36 of 40 · 90%');
    expect(_hint(tester, 'Your keyboard as it is'), 'The same 40 taps, read both ways: 3 only the fix read right, 1 only your keyboard did.');
    expect(_value(tester, 'So far'), 'Not proven yet');
    expect(_hint(tester, 'So far'), 'Over 2 runs the fix read 8 taps right that your keyboard as it is read wrong, and 2 the other way. A fix is proven on 3 runs or more: 1 more to go.');
    expect(tester.widget<FixFigure>(find.byKey(const ValueKey('trainer-fix-lines'))).fix.tests.map((r) => r.number), [2, 3]);
    expect(_value(tester, 'Touch areas'), '8 keys moved in fix 3');
    expect(_hint(tester, 'Touch areas'), 'a e h n o r s t. Where this run’s taps landed is measured for the next fix.');
    // Not proven: nothing is offered, on the results or anywhere else, and nothing was switched.
    expect(find.byKey(const ValueKey('trainer-switch')), findsNothing);
    expect(find.textContaining('Switch everyday typing'), findsNothing);
    expect(native.of('apply'), isEmpty);
    expect(tester.takeException(), isNull);
    await _close(tester);
  });

  testWidgets('switching is offered only where the keyboard says a fix is proven, and is the person’s own tap', (tester) async {
    // Every state that is not proof: ahead but on too few taps, ahead but not steadily, the last run against it,
    // behind, dropped as worse, and a fix no run has tested yet. None offers the switch.
    final unproven = <(Map<String, Object?>, String, String)>[
      (_fix(number: 4, tests: 3, won: 2, lost: 0, verdict: 'testing', hold: 'split'), 'Not proven yet', 'That is too few, or too even, to rule out chance.'),
      (_fix(number: 4, tests: 3, verdict: 'testing', hold: 'steady', oneIn: 5000), 'Not proven yet', 'It is ahead on the count, but not steadily from run to run, and one good run is not proof.'),
      (_fix(number: 4, tests: 3, verdict: 'testing', hold: 'last', oneIn: 5000), 'Not proven yet', 'The run just typed went against it.'),
      (_fix(number: 4, tests: 3, won: 1, lost: 5, verdict: 'testing', hold: 'behind'), 'Not proven yet', 'It has not read more taps right than your keyboard.'),
      (_fix(number: 4, dropped: 4, verdict: 'none'), 'Dropped', 'Fix 4 read fewer of your taps right than your keyboard as it is, so it was dropped. The next fix starts again from your keyboard.'),
      (_fix(number: 4, tested: false, differs: 11), 'Next run tests it', 'It moves 11 keys’ touch areas toward where your taps land. The next run is typed on it, and every tap is read both ways: by the fix, and by your keyboard as it is.'),
      (_fix(number: 0), 'Nothing to test', 'A fix moves each key’s touch area toward where your taps land. There is nothing to test yet: no key’s taps land far enough from where it listens now.'),
    ];
    for (final (fix, status, said) in unproven) {
      final native = _Native()..state.addAll({'runs': 4, 'best': 44, 'fix': fix});
      await _open(tester, native);
      final row = tester.widget<SettingRow>(find.byKey(const ValueKey('trainer-fix')));
      expect((row.value as Text).data, status, reason: '$fix');
      expect((row.hint as Text).data, endsWith(said), reason: '$fix');
      expect(find.byKey(const ValueKey('trainer-switch')), findsNothing, reason: '$fix');
      expect(find.textContaining('Switch everyday typing'), findsNothing);
      await _close(tester);
    }

    // Proven: the switch is there, says what it does, and does nothing until it is tapped.
    final native = _Native()..state.addAll({'runs': 5, 'best': 44, 'adaptive': true, 'fix': _fix(number: 4, tests: 3, verdict: 'proven', adaptive: true, differs: 12, oneIn: 2000000)});
    await _open(tester, native);
    expect(_value(tester, 'Fix 4'), 'Proven better');
    expect(_hint(tester, 'Fix 4'), 'Over 3 runs the fix read 27 taps right that your keyboard as it is read wrong, and 3 the other way. Chance would split them that far less than once in a million tries, and it led steadily from run to run.');
    expect(_hint(tester, 'Switch everyday typing to fix 4'), 'Moves Adaptive keys’ touch areas to where fix 4 has them. Like any nudge there they drift back as your aim comes right.');
    expect(native.of('apply'), isEmpty);
    await _press(tester, find.byKey(const ValueKey('trainer-switch')));
    expect(native.of('apply').single.arguments, {'fix': true, 'height': false});
    expect(find.text('Everyday typing reads your taps by fix 4 now'), findsOneWidget);
    expect(_value(tester, 'Fix 4'), 'In everyday typing');
    expect(find.byKey(const ValueKey('trainer-switch')), findsNothing);
    await _close(tester);

    // A run was started after fix 4 was proven, and left: fix 5 is in hand and no run has typed on it. What is
    // offered is fix 4, the one the runs tested.
    final left = _Native()..state.addAll({'runs': 5, 'best': 44, 'fix': _fix(number: 5, last: 4, tested: false, tests: 3, verdict: 'proven', differs: 13, oneIn: 40000)});
    await _open(tester, left);
    expect(_value(tester, 'Fix 4'), 'Proven better');
    expect(_hint(tester, 'Switch everyday typing to fix 4'), startsWith('Adds Adaptive keys, which is not added, with each touch area where fix 4 has it.'));
    expect(find.textContaining('fix 5'), findsNothing);
    await _close(tester);

    // A run's results offer it too, the moment the run that proved it ends.
    final proving = _Native()
      ..state.addAll({'runs': 3, 'best': 44, 'fix': _fix(number: 3, tests: 2, verdict: 'testing', hold: 'runs', differs: 9)})
      ..run = {..._Native().run, 'fix': 4, 'fixKeys': 12}
      ..result = {
        ..._Native().result,
        'runs': 4,
        'fix': {..._fix(number: 4, tests: 3, verdict: 'proven', differs: 12, oneIn: 40000), 'worn': 4, 'run': {'number': 4, 'taps': 170, 'asIs': 151, 'fixed': 159, 'won': 9, 'lost': 1}},
      };
    await _open(tester, proving);
    expect(find.byKey(const ValueKey('trainer-switch')), findsNothing);
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    await _press(tester, find.text('Finish'));
    expect(_value(tester, 'So far'), 'Proven better');
    expect(native.of('apply'), hasLength(1));
    expect(proving.of('apply'), isEmpty);
    await _press(tester, find.byKey(const ValueKey('trainer-switch')));
    expect(proving.of('apply').single.arguments, {'fix': true, 'height': false});
    await _close(tester);

    // A run that proved its fix worse says it was dropped, and offers nothing.
    final dropping = _Native()
      ..state.addAll({'runs': 3, 'best': 44, 'fix': _fix(number: 3, tests: 2, won: 1, lost: 6, verdict: 'testing', hold: 'runs')})
      ..run = {..._Native().run, 'fix': 3, 'fixKeys': 9}
      ..result = {
        ..._Native().result,
        'runs': 4,
        'fix': {..._fix(number: 3, dropped: 3, verdict: 'worse'), 'worn': 3, 'run': {'number': 3, 'taps': 170, 'asIs': 160, 'fixed': 153, 'won': 1, 'lost': 8}},
      };
    await _open(tester, dropping);
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    await _press(tester, find.text('Finish'));
    expect(_value(tester, 'With fix 3'), '153 of 170 taps · 90%');
    expect(_value(tester, 'Your keyboard as it is'), '160 of 170 · 94.1%');
    expect(_value(tester, 'So far'), 'Dropped');
    expect(_hint(tester, 'So far'), 'Fix 3 read fewer of your taps right than your keyboard as it is, so it was dropped. The next fix starts again from your keyboard.');
    expect(find.byKey(const ValueKey('trainer-switch')), findsNothing);
    await _close(tester);
  });

  testWidgets('Settings has a Trainer on Android and none on an iPhone', (tester) async {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(_settings, (call) async => null);
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(_settings, null));
    final store = SettingsStore();
    final android = settingsSections(store);
    final trainer = android.firstWhere((s) => s.id == 'trainer');
    expect(trainer.label, 'Trainer');
    expect(trainer.screen, isNotNull);
    expect(trainer.summary!(store), 'Type a story; each run tests a fix for your thumbs');
    // At the top, in Setup's group and straight after it (Matt, 2026-11-11: "Place the training section at the top
    // next to the setup section"). The Stats page he asked for the same day is the card's third row, under it.
    expect(android.first.id, 'setup');
    expect(android.indexOf(trainer), 1);
    expect(android.where((s) => s.group == android.first.group).map((s) => s.id), ['setup', 'trainer', statsPageId]);

    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    try {
      final ios = settingsSections(store);
      expect(ios.where((s) => s.id == 'trainer'), isEmpty);
      expect(ios.where((s) => s.group == ios.first.group).map((s) => s.id), ['setup']);
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }

    // The line on Settings' list follows the runs.
    final t = TrainerStore()
      ..state = {'runs': 3, 'best': 44}
      ..loaded = true;
    expect(t.summary, '3 runs · 44 words a minute at best');
  });

  testWidgets('on Settings’ first page the Trainer is in the top card, straight under Setup, and opens from there', (tester) async {
    final native = _Native();
    await _open(tester, native, home: (store) => ListenableBuilder(
          listenable: store,
          builder: (context, _) => SingleChildScrollView(child: SettingsHome(store: store, sections: settingsSections(store))),
        ));
    final setup = find.text('Setup');
    final row = find.text('Trainer');
    final offline = find.text('Offline');
    expect(setup, findsOneWidget);
    expect(row, findsOneWidget);
    expect(offline, findsOneWidget);
    // Down the page: Setup, the Trainer, then Offline.
    double top(Finder f) => tester.getTopLeft(f).dy;
    expect(top(setup), lessThan(top(row)));
    expect(top(row), lessThan(top(offline)));
    // One card (the list's rounded group) holds Setup and the Trainer; Offline's is the next one.
    Set<Element> cards(Finder f) => find.ancestor(of: f, matching: find.byType(ClipRRect)).evaluate().toSet();
    final shared = cards(setup).intersection(cards(row));
    expect(shared, isNotEmpty, reason: 'Setup and the Trainer are rows of one card');
    expect(shared.intersection(cards(offline)), isEmpty, reason: 'Offline is not in their card');
    // Its row says what it is for, and opens the trainer's own screen.
    expect(find.text('Type a story; each run tests a fix for your thumbs'), findsOneWidget);
    await _press(tester, row);
    expect(find.byType(TrainerScreen), findsOneWidget);
    await _close(tester);
  });

  testWidgets('the pictures of the keyboard: on the first page, the mistakes, the profile and a run’s results', (tester) async {
    // The keyboard as Android sends it (TrainerMaps.board): the top row measured where [taps], and nothing else.
    Map<String, Object?> board({bool taps = true}) {
      Map<String, Object?> key(String letter, double x, int i, bool top) => {
            'key': letter, 'x': x, 'taps': taps && top ? 30 : 0, 'missed': taps && top && i == 3 ? 6 : 0,
            'landX': taps && top ? 0.1 : 0.0, 'landY': 0.0, 'landed': taps && top ? 30 : 0,
            'shiftX': taps && top && i < 3 ? 0.04 * (i + 1) : 0.0, 'shiftY': 0.0, 'moved': taps && top && i < 2,
          };
      return {
        'rows': [
          [for (var i = 0; i < 10; i++) key('qwertyuiop'[i], i.toDouble(), i, true)],
          [for (var i = 0; i < 9; i++) key('asdfghjkl'[i], 0.5 + i, i, false)],
          [for (var i = 0; i < 7; i++) key('zxcvbnm'[i], 1.5 + i, i, false)],
        ],
        'units': 10.0, 'taps': taps ? 300 : 0, 'missed': taps ? 6 : 0, 'maxShift': 0.3,
        'slips': taps ? [{'pressed': 'e', 'meant': 'r', 'count': 4}] : <Object?>[],
        'lean': taps ? 'Your taps land a little to the right of a key’s middle.' : null,
      };
    }

    // Nothing typed yet: the keys come all the same, and no picture is made of nothing.
    await _open(tester, _Native()..state['board'] = board(taps: false));
    expect(find.byType(BoardFigure), findsNothing);
    expect(find.byType(RunLines), findsNothing);
    await _close(tester);

    final native = _Native()
      ..state.addAll({
        'runs': 6, 'best': 47, 'canPractise': false,
        'history': [
          for (var i = 0; i < 6; i++)
            {'at': 1762862400000 + i * 60000, 'title': 'The lighthouse cat', 'practice': false, 'height': 'normal', 'heightLabel': 'Normal', 'trial': false, 'ms': 60000, 'wpm': 47 - i, 'accuracy': 96 - i, 'words': 45, 'right': 44, 'firstTry': 42 - i},
        ],
        'mistakes': [{'word': 'because', 'typed': 'becuase', 'missed': 3, 'seen': 5, 'last': '00011', 'streak': 2}],
        'keys': [{'key': 'r', 'missed': 6, 'n': 30, 'toward': 'e', 'trend': 0}],
        'profile': {
          'runs': 6, 'wpmFirst': 39, 'wpmNow': 44, 'wpmBest': 47, 'accuracyFirst': 93, 'accuracyNow': 95, 'layout': 'qwerty',
          'lean': 'Your taps land a little to the right of a key’s middle.', 'report': 'Over 6 runs: 44 words a minute.',
          'keys': [{'key': 'e', 'dx': 0.1, 'dy': 0.0, 'samples': 30}], 'slips': <Object?>[], 'mistakes': <Object?>[], 'pairs': <Object?>[], 'suggestion': null, 'file': '{}',
        },
        'board': board(),
      })
      ..result['board'] = board();
    await _open(tester, native);

    // The first page: where the mistakes happen, what has shifted where, and the runs as bars and lines.
    final misses = tester.widget<BoardFigure>(find.byKey(const ValueKey('trainer-map-misses')));
    expect(misses.view, BoardView.misses);
    expect(misses.run, isFalse);
    expect(misses.said, 'Missed most: r 20% (e in its place).');
    final areas = tester.widget<BoardFigure>(find.byKey(const ValueKey('trainer-map-areas')));
    expect(areas.view, BoardView.areas);
    // q and w are the fix's; e is off its middle on the keyboard as it is.
    expect(areas.said, 'The fix moves 2 keys’ touch areas toward where the taps land. Furthest: w, 8% right. Your keyboard as it is has 1 more off the middle.');
    expect(find.text(misses.said), findsOneWidget);
    expect(find.text(areas.said), findsOneWidget);
    // Which way the taps lean on the whole is said under the second, with what its marks are.
    expect(find.textContaining('Your taps land a little to the right of a key’s middle. A ring is where the taps meant for a key land'), findsOneWidget);
    expect(find.byType(RunBars), findsOneWidget);
    // Oldest first, as the bars over them: the latest run is the last point.
    final lines = tester.widget<RunLines>(find.byKey(const ValueKey('trainer-lines')));
    expect(RunLines.accuracy(lines.runs), [91, 92, 93, 94, 95, 96]);
    expect(find.text('taps on their key 96%'), findsOneWidget);
    expect(find.text('words right first time 93%'), findsOneWidget);
    expect(tester.takeException(), isNull);

    // The mistakes have the same keyboard over their lists.
    await _press(tester, find.text('Your mistakes'));
    expect(tester.widget<BoardFigure>(find.byKey(const ValueKey('trainer-mistakes-map'))).view, BoardView.misses);
    expect(_value(tester, 'The r key'), '6 of 30');
    await _press(tester, find.bySemanticsLabel('Back'));

    // The profile draws where the taps land over its rows of them.
    await _press(tester, find.text('Your profile'));
    expect(tester.widget<BoardFigure>(find.byKey(const ValueKey('trainer-profile-map'))).view, BoardView.areas);
    expect(_value(tester, 'The e key'), '10% right');
    await _press(tester, find.bySemanticsLabel('Back'));

    // A run's results have the run's own two: where it went wrong, and the touch areas of the fix it was typed on.
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    await _tell(tester, 'progress', {'fates': [1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1], 'current': 11, 'tail': '', 'done': true, 'wpm': 43, 'ms': 64000});
    await _settle(tester);
    expect(find.text('43 words a minute'), findsOneWidget);
    final wrong = tester.widget<BoardFigure>(find.byKey(const ValueKey('trainer-run-misses')));
    expect(wrong.view, BoardView.misses);
    expect(wrong.run, isTrue);
    final took = tester.widget<BoardFigure>(find.byKey(const ValueKey('trainer-run-areas')));
    expect(took.view, BoardView.areas);
    expect(took.run, isTrue);
    expect(took.said, 'The fix this run was typed on moves 2 keys’ touch areas toward where the taps land. Furthest: w, 8% right. Your keyboard as it is has 1 more off the middle.');
    expect(find.text('where the fix it was typed on has the key'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await _close(tester);
  });

  testWidgets('a run that was not counted, or sent no keyboard, has no picture of its touch areas', (tester) async {
    // With no keyboard sent to draw, the results are rows alone.
    final old = _Native();
    await _open(tester, old);
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    await _tell(tester, 'progress', {'fates': [1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1], 'current': 11, 'tail': '', 'done': true, 'wpm': 43, 'ms': 64000});
    await _settle(tester);
    expect(_value(tester, 'Touch areas'), 'As your keyboard has them');
    expect(find.byType(BoardFigure), findsNothing);
    await _close(tester);

    // Not counted: nothing was measured or tested, so no touch areas are drawn; where it went wrong is still worth seeing.
    final short = _Native();
    short.result
      ..['counted'] = false
      ..['why'] = 'short'
      ..['nudged'] = <Object?>[]
      ..['board'] = {
        'rows': [
          [
            {'key': 'o', 'x': 8.0, 'taps': 5, 'missed': 2, 'landX': 0.0, 'landY': 0.0, 'landed': 5, 'shiftX': 0.1, 'shiftY': 0.0, 'moved': false},
            {'key': 'p', 'x': 9.0, 'taps': 3, 'missed': 0, 'landX': 0.0, 'landY': 0.0, 'landed': 0, 'shiftX': 0.0, 'shiftY': 0.0, 'moved': false},
          ],
        ],
        'units': 10.0, 'slips': [{'pressed': 'p', 'meant': 'o', 'count': 2}], 'taps': 8, 'missed': 2, 'maxShift': 0.3,
      };
    await _open(tester, short);
    await _press(tester, find.byKey(const ValueKey('trainer-start')));
    await _tell(tester, 'progress', {'fates': [1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1], 'current': 11, 'tail': '', 'done': true, 'wpm': 43, 'ms': 64000});
    await _settle(tester);
    expect(_value(tester, 'Touch areas'), 'Not counted');
    expect(_hint(tester, 'Touch areas'), 'Nothing was measured from this run.');
    expect(tester.widget<BoardFigure>(find.byKey(const ValueKey('trainer-run-misses'))).said, 'Missed most: o 40% (p in its place).');
    expect(find.byKey(const ValueKey('trainer-run-areas')), findsNothing);
    expect(tester.takeException(), isNull);
    await _close(tester);
  });
}
