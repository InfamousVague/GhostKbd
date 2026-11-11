/// The typing trainer (Matt, 2026-11-11: "a 'trainer' where it has a typeracer style thing where we type stories out
/// and it tracks everything like wpm and missed words etc"): its first page, a run, a run's results, and the pages for
/// the history, the mistakes and the profile. The keyboard measures and reckons (Android's Trainer.kt); these pages
/// show what it says, in the settings kit's cards, and in pictures of the keyboard (trainer_board.dart).
/// docs/trainer.md has the whole of it.
///
/// Android only: the iPhone's keyboard is another port, with no trainer in it, so Settings has no row for this there.
library;

import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../art/app_icon.dart';
import '../art/lucide.g.dart';
import '../market/market_cards.dart';
import '../settings/kit/settings_icons.dart';
import '../settings/kit/settings_kit.dart';
import '../settings/kit/settings_type.dart';
import '../settings/settings_screen.dart';
import '../settings_store.dart';
import 'trainer_board.dart';
import 'trainer_fix.dart';
import 'trainer_store.dart';

/// The trainer's colour on Settings' list and on its own pages (settings_css.dart).
const trainerHue = 'orange';

List<Map<Object?, Object?>> _maps(Object? raw) => [for (final e in (raw as List? ?? const [])) if (e is Map) e];
int _int(Object? v) => (v as num?)?.toInt() ?? 0;

/// 64000 ms as "1:04".
String _clock(int ms) {
  final s = (ms / 1000).round();
  return '${s ~/ 60}:${(s % 60).toString().padLeft(2, '0')}';
}

/// A percentage the keyboard gave, or a dash where it had none to give (-1).
String _percent(Object? v) => _int(v) < 0 ? '—' : '${_int(v)}%';

const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/// When a run was typed, as "11 Nov, 14:32".
String _when(Object? at) {
  final d = DateTime.fromMillisecondsSinceEpoch(_int(at));
  return '${d.day} ${_months[d.month - 1]}, ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
}

String _count(int n, String one, String many) => n == 1 ? '1 $one' : '$n $many';

/// A share to a tenth of a percent, as the keyboard's own totals are said.
double? _tenth(double? share) => share == null ? null : (share * 10).round() / 10;

/// What every page says under the test of the fixes: how a run tests one, and what proof is.
const _testSaid =
    'The first run is typed on your keyboard as it is. Each run after it is typed on a new fix: the keys’ touch areas a small step nearer to where your taps land. '
    'The keyboard reads every tap of that run twice, as the fix reads it and as your keyboard as it is would have, so the two are compared on the very same taps. '
    'A fix is proven on three runs or more, where its lead on the taps read differently is more than chance gives once in a hundred tries and holds from run to run. '
    'Your everyday typing changes only if a fix is proven and you switch to it.';

/// The test of the fixes as rows (Matt, 2026-11-11: "a b test the accuracy and don't suggest switching until its
/// proven we got a better result"): the fix in hand and where it stands, the runs that tested one as two lines, both
/// readings added up, and the switch, which is there only where the keyboard says the fix is proven.
List<Widget> _fixRows(TrainerStore trainer, FixData fix, {required int storyRuns, required Future<void> Function() onSwitch}) => [
      SettingRow(key: const ValueKey('trainer-fix'), label: fix.title, value: Text(fix.status), hint: Text(fix.standing(storyRuns: storyRuns))),
      if (fix.tests.isNotEmpty) SettingRowBox(child: FixFigure(key: const ValueKey('trainer-fix-lines'), fix: fix)),
      if (fix.runs > 0) ...[
        SettingRow(label: 'With the fix', value: Text('${shareSaid(_tenth(fix.fixedShare))} of taps on their key')),
        SettingRow(
          label: 'Your keyboard as it is',
          value: Text(shareSaid(_tenth(fix.asIsShare))),
          hint: Text('The same ${_count(fix.taps, 'tap', 'taps')} of ${_count(fix.runs, 'run', 'runs')}, read both ways.'),
        ),
      ],
      if (fix.proven) SettingRow(key: const ValueKey('trainer-switch'), label: 'Switch everyday typing to fix ${fix.last}', hint: Text(fix.switchSaid), onPress: onSwitch),
      for (final line in trainer.said) SettingRow(label: line),
    ];

/// A page of the trainer: the ground, the bar with the way back and the title, and its cards scrolling under it.
class _Page extends StatelessWidget {
  const _Page({required this.title, required this.children, this.onBack});

  final String title;
  final List<Widget> children;

  /// What the arrow does; null and it goes back a page.
  final VoidCallback? onBack;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final pad = MediaQuery.paddingOf(context);
    final gutter = t.space(4);
    return SettingsPaneScope(
      finder: SettingsFinder(),
      whole: true,
      child: GlacierScope(
        attrs: const {'hue': trainerHue},
        child: ColoredBox(
          color: t.color('glacier-bg'),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SizedBox(height: pad.top),
              _Bar(title: title, onBack: onBack ?? () => Navigator.of(context).maybePop()),
              Expanded(
                child: ListView(
                  padding: EdgeInsets.fromLTRB(gutter, t.space(2), gutter, t.space(6) + pad.bottom),
                  children: [PaneBody(children: children)],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The bar over a trainer page: the way back, the title, and what a run puts at its end.
class _Bar extends StatelessWidget {
  const _Bar({required this.title, required this.onBack, this.end});
  final String title;
  final VoidCallback onBack;
  final Widget? end;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return SizedBox(
      height: t.lenOf('3.25rem'),
      child: Row(
        children: [
          SizedBox(width: t.space(1)),
          GlacierPressable(
            onPressed: onBack,
            semanticLabel: 'Back',
            builder: (context, state) => SizedBox.square(
              dimension: t.lenOf('2.75rem'),
              child: Center(child: SettingsIcon(SettingsGlyph.arrowLeft, size: 22, color: t.color(state.pressed ? 'app-ink-3' : 'app-ink'))),
            ),
          ),
          Expanded(child: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: pageText(t, t.fontSize('lg'), weight: 'semibold', color: t.color('app-ink')))),
          ?end,
          SizedBox(width: t.space(4)),
        ],
      ),
    );
  }
}

/// A button of the trainer's pages, a thing of its own to a screen reader (a lone button's tap would otherwise be
/// read as the whole page's). [go] fills it with the trainer's colour, read where it is drawn, under the page's hue.
class _Button extends StatelessWidget {
  const _Button({required this.id, required this.label, required this.onPress, this.go = false, this.large = false});
  final String id;
  final String label;
  final VoidCallback? onPress;
  final bool go;
  final bool large;

  @override
  Widget build(BuildContext context) => Semantics(
        container: true,
        child: MarketButton(
          key: ValueKey(id),
          label: label,
          icon: go ? Lucide.play : null,
          look: go ? MarketButtonLook.filled : MarketButtonLook.ring,
          color: go ? settingsHue(context.tokens) : null,
          large: large,
          onPress: onPress,
        ),
      );
}

/// The trainer: its first page, and over it a run while one is on, then that run's results.
class TrainerScreen extends StatefulWidget {
  const TrainerScreen({super.key, required this.store, this.trainer});

  final SettingsStore store;

  /// The trainer to show; the app's own where none is given.
  final TrainerStore? trainer;

  @override
  State<TrainerScreen> createState() => _TrainerScreenState();
}

class _TrainerScreenState extends State<TrainerScreen> with WidgetsBindingObserver {
  TrainerStore get trainer => widget.trainer ?? TrainerStore.shared;
  SettingsStore get store => widget.store;

  /// Why the last run did not start, in words; null where nothing was refused.
  String? _refused;

  /// The last run's results are on show.
  bool _results = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    store.refreshStatus();
    trainer.refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // A run does not outlive its page: left, it is dropped, and the keyboard is back on the person's own layout.
    trainer.leave();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      store.refreshStatus();
      trainer.refresh();
    } else if (state == AppLifecycleState.paused) {
      trainer.leave();
    }
  }

  Future<void> _start({bool practice = false}) async {
    await store.refreshStatus();
    final why = await trainer.start(practice: practice);
    if (!mounted) return;
    setState(() {
      _results = false;
      _refused = switch (why) {
        null => null,
        'keyboard' => 'Ghost Keyboard is not the keyboard in use, so there is nothing of its own to measure. Switch to it and start again.',
        'layout' => 'The stories are in English, and the layout being typed on has no Latin letters. Switch layouts with the globe key and start again.',
        'practice' => 'There is nothing to practise yet.',
        _ => 'The trainer could not start a run.',
      };
    });
    if (why != null) trainer.refresh();
  }

  Future<void> _finish() async {
    await trainer.finish();
    if (!mounted) return;
    setState(() => _results = trainer.result != null);
    // The run moved nudges in Adaptive keys' model and may have taught a correction: their pages read them again.
    store.refreshAdaptive();
    store.refreshNextWords();
  }

  void _closeResults() => setState(() => _results = false);

  /// Everyday typing is switched to the fix in hand, which the keyboard does only where it is proven. Switching may
  /// add Adaptive keys and move its nudges: Settings reads them again.
  Future<void> _switch() async {
    await trainer.apply(fix: true);
    await store.load();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([trainer, store]),
      builder: (context, _) {
        final running = trainer.run != null;
        final results = !running && _results && trainer.result != null;
        return PopScope(
          // The way back from a run or its results is to the trainer's first page, not out of the trainer.
          canPop: !running && !results,
          onPopInvokedWithResult: (popped, _) {
            if (popped) return;
            if (running) {
              trainer.leave();
            } else {
              _closeResults();
            }
          },
          child: running
              ? TrainerRun(key: ValueKey(trainer.run), trainer: trainer, onFinish: _finish)
              : results
                  ? _Page(title: 'Results', onBack: _closeResults, children: _resultCards(context))
                  : _Page(title: 'Trainer', children: _homeCards(context)),
        );
      },
    );
  }

  // --- the first page -------------------------------------------------------------------------------------------------

  List<Widget> _homeCards(BuildContext context) {
    final t = context.tokens;
    final next = trainer.map('next');
    final runs = trainer.number('runs');
    final best = trainer.number('best');
    final profileAt = trainer.number('profileAt');
    final selected = trainer.loaded ? trainer.flag('selected') : store.selected;
    final latin = !trainer.loaded || trainer.flag('latin');
    final trials = trainer.flag('trials');
    final ownHeight = '${trainer.state['ownHeightLabel'] ?? 'Normal'}';
    final history = trainer.list('history');
    final mistakes = trainer.list('mistakes');
    final ready = trainer.state['profile'] is Map;
    final canStart = selected && latin && next != null;
    final board = TrainerBoardData.of(trainer.state['board']);
    final fix = FixData.of(trainer.state['fix']);
    // The story runs the charts draw, oldest first: a practice run of one's own mistakes is no measure of speed.
    final story = history.where((r) => r['practice'] != true).take(12).toList().reversed.toList();
    return [
      PaneHero(
        glyph: AppIcon(Lucide.graduationCap, size: 24),
        title: runs == 0 ? 'Type a story' : (best > 0 ? '$best words a minute' : _count(runs, 'run', 'runs')),
        meta: Text(runs == 0
            ? 'The first run times you and measures where your taps land. Each run after it tests a fix against your keyboard as it is.'
            : 'Your best of ${_count(runs, 'run', 'runs')}. Each run after the first tests a new fix against your keyboard as it is.'),
        status: PaneHeroStatus(runs == 0 ? 'No runs yet' : (ready ? 'Your profile is ready' : '${_count(profileAt - runs, 'more run', 'more runs')} to your profile')),
      ),
      if (trainer.loaded && !selected)
        SettingsCallout(
          icon: AppIcon(Lucide.triangleAlert, size: 20),
          action: RowAction(onPress: store.showPicker, child: const Text('Switch')),
          child: const Text('Ghost Keyboard is not the keyboard in use. A run would measure another keyboard, so the trainer waits until you switch to it.'),
        ),
      if (trainer.loaded && selected && !latin)
        const SettingsCallout(child: Text('The stories are in English, and the layout being typed on has no Latin letters. Switch layouts with the globe key to train.')),
      if (trainer.loaded && selected && latin && !trainer.flag('english'))
        const SettingsCallout(child: Text('The stories are in English, which is not one of your languages: autocorrect will not know their words.')),
      if (trainer.dropped) const SettingsCallout(child: Text('The run was stopped because the app was left while it was on. It was not counted.')),
      if (_refused != null) SettingsCallout(icon: AppIcon(Lucide.triangleAlert, size: 20), child: Text(_refused!)),
      PaneSection(
        title: 'Next',
        footer: Text(
          trials
              ? 'With “Try different layouts” on, some runs are typed on shorter or taller keys than your own ($ownHeight), to find the height you type best on. It is read as each run starts, your own keys are back the moment a run ends or you leave, and a height is suggested only once it is proven better.'
              : 'With “Try different layouts” off, the keyboard looks exactly as you set it. A fix moves where the keys listen, which changes nothing you can see.',
        ),
        children: [
          SettingRow(
            label: next == null ? 'A story' : '${next['title']}',
            hint: next == null ? null : Text('Part ${next['part']} of ${next['parts']} · ${next['words']} words'),
            control: _Button(id: 'trainer-start', label: 'Start', go: true, onPress: canStart ? () => _start() : null),
          ),
          SettingRow(
            label: 'Try different layouts',
            hint: const Text('Off unless you turn it on.'),
            control: GlacierSwitch(checked: trials, onCheckedChange: trainer.setTrials, ariaLabel: 'Try different layouts'),
          ),
          if (trainer.flag('canPractise'))
            SettingRow(label: 'Practise my mistakes', hint: const Text('A short run of the words and letter pairs you miss.'), onPress: selected && latin ? () => _start(practice: true) : null),
        ],
      ),
      // The test of the fixes: which one the next run tests, both readings of the runs so far, and the switch once
      // one is proven.
      if (trainer.loaded && selected && latin)
        PaneSection(
          title: 'The test',
          footer: const Text(_testSaid),
          children: _fixRows(trainer, fix, storyRuns: runs, onSwitch: _switch),
        ),
      // The keyboard's two pictures, once a run has taps to draw (Matt, 2026-11-11: "Show inforgraphics on the trainer
      // with charts and stuff to show where the mistakes happen and what has shifted where using a simplified keyboard
      // display").
      if (board != null && board.taps > 0) ...[
        PaneSection(
          title: 'Where the mistakes happen',
          footer: const Text('Over every run. A key is tinted by how often a tap meant for it missed, once it has had ten taps, with that share under its letter; an arrow points from a key to the one hit in its place.'),
          children: [SettingRowBox(child: BoardFigure(key: const ValueKey('trainer-map-misses'), board: board, view: BoardView.misses))],
        ),
        PaneSection(
          title: 'What has shifted',
          footer: Text(
            '${board.lean == null ? '' : '${board.lean} '}A ring is where the taps meant for a key land, about its middle. An arrow in the trainer’s colour is where the fix being tested has that key’s touch area: a small step further a fix, and never more than a third of a key. '
            'A grey arrow is a touch area your keyboard already has off its middle. Both are drawn one and a half times their size so that small ones show. The keys look the same as ever; only where each one listens moves, and in everyday typing only once you switch.',
          ),
          children: [SettingRowBox(child: BoardFigure(key: const ValueKey('trainer-map-areas'), board: board, view: BoardView.areas))],
        ),
      ],
      PaneSection(
        title: 'Your runs',
        children: [
          if (story.isNotEmpty && history.length >= 2)
            SettingRowBox(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [RunBars(runs: story), if (story.length >= 2) ...[SizedBox(height: t.space(4)), RunLines(key: const ValueKey('trainer-lines'), runs: story)]],
              ),
            ),
          SettingRow(label: 'History', value: Text(runs == 0 ? 'None yet' : _count(runs, 'run', 'runs')), onPress: history.isEmpty ? null : () => pushSettings(context, (_) => _HistoryPage(trainer: trainer))),
          SettingRow(
            label: 'Your mistakes',
            value: Text(mistakes.isEmpty ? 'None yet' : _count(mistakes.length, 'word', 'words')),
            onPress: mistakes.isEmpty && trainer.list('pairs').isEmpty && trainer.list('keys').isEmpty ? null : () => pushSettings(context, (_) => _MistakesPage(trainer: trainer, onPractise: () => _start(practice: true))),
          ),
          SettingRow(
            label: 'Your profile',
            value: Text(ready ? 'Ready' : 'After ${_count(profileAt, 'run', 'runs')}'),
            hint: ready ? null : Text(runs == 0 ? 'Where your taps land, your speed, and what you miss.' : '${_count(profileAt - runs, 'more run', 'more runs')} to go.'),
            onPress: ready ? () => pushSettings(context, (_) => _ProfilePage(trainer: trainer, store: store)) : null,
          ),
        ],
      ),
      PaneSection(
        footer: const Text(
          'Everything the trainer keeps is in a file of its own on this phone. A story is not your writing: a run teaches nothing to next-word suggestions or Mockingbird, counts nothing for typing stats, and puts nothing in an anonymous report. '
          'What a run hands on: a word you mistype is remembered under Your corrections where that is on. A fix is the trainer’s own, worn only by the run that tests it, until it is proven and you switch to it.',
        ),
        children: [SettingRow(label: 'Forget what the trainer learned', hint: const Text('Its runs, your mistakes, your profile, its fixes, and what a fix you switched to gave the keys.'), onPress: !trainer.loaded || (trainer.list('history').isEmpty && !trials) ? null : trainer.forget)],
      ),
    ];
  }

  // --- a run's results ------------------------------------------------------------------------------------------------

  List<Widget> _resultCards(BuildContext context) {
    final t = context.tokens;
    final r = trainer.result!;
    final counted = r['counted'] == true;
    final practice = r['practice'] == true;
    final words = _int(r['words']);
    final missed = _maps(r['missed']);
    final skipped = [for (final w in (r['skipped'] as List? ?? const [])) '$w'];
    final mended = _maps(r['mended']);
    final spoiled = _maps(r['spoiled']);
    final misfixed = _maps(r['misfixed']);
    final left = _maps(r['left']);
    final keys = _maps(r['weakKeys']);
    final pairs = _maps(r['weakPairs']);
    final nudged = _maps(r['nudged']);
    final taught = _int(r['taught']);
    final runs = _int(r['runs']);
    final profileAt = _int(r['profileAt']);
    final layouts = r['layouts'] is Map ? r['layouts'] as Map : const {};
    final moved = r['moved'] is Map ? r['moved'] as Map : null;
    // The run's own picture of the keyboard: where it went wrong, and where the keys listened while it was typed.
    final board = TrainerBoardData.of(r['board']);
    final wrong = board != null && board.missed > 0;
    // The test this run was: the fix it was typed on, and what the two readings of its taps came to.
    final fix = FixData.of(r['fix']);
    final test = fix.run;
    String fixes(List<Map<Object?, Object?>> list) => list.map((f) => '${f['typed']} → ${f['made']}').join(', ');
    return [
      PaneHero(
        glyph: AppIcon(Lucide.gauge, size: 24),
        title: '${_int(r['wpm'])} words a minute',
        meta: Text('${_percent(r['accuracy'])} of taps on their key · ${_clock(_int(r['ms']))} · ${_int(r['right'])} of $words words right'),
        status: PaneHeroStatus(counted
            ? (practice ? 'A practice run' : 'Run $runs')
            : switch (r['why']) {
                'quick' => 'Not counted: it was over in under three seconds',
                'long' => 'Not counted: it ran to more keys than a story takes',
                _ => 'Not counted: under four fifths of the story was typed',
              }),
      ),
      // What comes next is the first thing under the numbers: the rest is there to be read, or not.
      Wrap(
        spacing: t.space(2),
        runSpacing: t.space(2),
        children: [
          _Button(id: 'trainer-next', label: 'Next story', go: true, large: true, onPress: () => _start()),
          if (trainer.flag('canPractise')) _Button(id: 'trainer-practise', label: 'Practise my mistakes', large: true, onPress: () => _start(practice: true)),
          _Button(id: 'trainer-done', label: 'Done', large: true, onPress: _closeResults),
        ],
      ),
      if (!practice)
        PaneSection(
          key: const ValueKey('trainer-test'),
          title: 'The test',
          footer: const Text(_testSaid),
          children: [
            if (test != null) ...[
              SettingRow(label: 'With fix ${fix.worn}', value: Text('${test.fixed} of ${test.taps} taps · ${shareSaid(_tenth(test.fixedShare))}')),
              SettingRow(label: 'Your keyboard as it is', value: Text('${test.asIs} of ${test.taps} · ${shareSaid(_tenth(test.asIsShare))}'), hint: Text(test.said)),
            ] else
              SettingRow(
                label: fix.worn > 0 ? 'Fix ${fix.worn}' : 'Typed on your keyboard as it is',
                value: fix.worn > 0 ? const Text('Not tested') : null,
                hint: Text(fix.worn > 0
                    ? 'This run was typed on it, and was not counted. The next run tests it again.'
                    : (runs <= 1 && counted
                        ? 'The first run measures where your taps land. The next run tests the first fix, where they land far enough from a key’s middle to make one.'
                        : 'No fix was tested: there was none in use for this run.')),
              ),
            if (test != null || fix.verdict == 'worse' || fix.runs > 0)
              SettingRow(key: const ValueKey('trainer-fix'), label: 'So far', value: Text(fix.status), hint: Text(fix.standing(storyRuns: runs))),
            if (fix.tests.length >= 2) SettingRowBox(child: FixFigure(key: const ValueKey('trainer-fix-lines'), fix: fix)),
            if (fix.proven) SettingRow(key: const ValueKey('trainer-switch'), label: 'Switch everyday typing to fix ${fix.last}', hint: Text(fix.switchSaid), onPress: _switch),
            for (final line in trainer.said) SettingRow(label: line),
          ],
        ),
      PaneSection(
        title: 'Words',
        footer: const Text('Speed counts only the words that came out right, at five characters a word. Right first time: no backspace and no fix.'),
        children: [
          SettingRow(label: 'Right first time', value: Text('${_int(r['firstTry'])} of $words')),
          SettingRow(label: 'Backspaces', value: Text('${_int(r['backspaces'])}')),
          for (final m in missed) SettingRow(label: '${m['meant']}', value: Text('typed ${m['typed']}')),
          for (final w in skipped) SettingRow(label: w, value: const Text('not typed')),
          if (_int(r['spacesMissed']) > 0) SettingRow(label: 'Spaces missed', value: Text('${_int(r['spacesMissed'])}')),
          if (_int(r['swiped']) > 0) SettingRow(label: 'Words swiped', value: Text('${_int(r['swiped'])}')),
          if (_int(r['picked']) > 0) SettingRow(label: 'Words from the row', value: Text('${_int(r['picked'])}')),
        ],
      ),
      PaneSection(
        title: 'Autocorrect',
        footer: const Text('The trainer knows what was meant, so it can say which fixes were right: a word mended to the story’s, or a right word changed to another.'),
        children: [
          SettingRow(label: 'Mended', value: Text('${mended.length}'), hint: mended.isEmpty ? null : Text(fixes(mended))),
          SettingRow(label: 'Spoiled', value: Text('${spoiled.length}'), hint: spoiled.isEmpty ? null : Text(fixes(spoiled))),
          if (misfixed.isNotEmpty) SettingRow(label: 'Changed to another wrong word', value: Text('${misfixed.length}'), hint: Text(fixes(misfixed))),
          if (left.isNotEmpty) SettingRow(label: 'Typed wrong and let stand', value: Text('${left.length}'), hint: Text(left.map((m) => '${m['typed']} for ${m['meant']}').join(', '))),
        ],
      ),
      if (keys.isNotEmpty || pairs.isNotEmpty || wrong)
        PaneSection(
          title: 'Where it went wrong',
          children: [
            if (wrong) SettingRowBox(child: BoardFigure(key: const ValueKey('trainer-run-misses'), board: board, view: BoardView.misses, run: true)),
            for (final k in keys)
              SettingRow(label: 'The ${k['key']} key', value: Text('${k['missed']} of ${k['n']} missed'), hint: k['toward'] == null ? null : Text('Mostly ${k['toward']} in its place.')),
            for (final p in pairs) SettingRow(label: '${'${p['pair']}'[0]} then ${'${p['pair']}'[1]}', value: Text('${p['missed']} of ${p['n']} missed')),
          ],
        ),
      PaneSection(
        title: 'What the keyboard took from it',
        footer: const Text(
          'A fix has a touch area at most a twenty-fifth of a key further than the fix before it, toward where the taps meant for that key have landed, and never more than a third of a key in all. It is worn only by the run that tests it: nothing you can see changes, and your everyday typing is as it was.',
        ),
        children: [
          SettingRow(
            label: 'Touch areas',
            value: Text(!counted ? 'Not counted' : (nudged.isEmpty ? 'As your keyboard has them' : '${_count(nudged.length, 'key', 'keys')} moved in fix ${fix.worn}')),
            hint: Text(!counted
                ? 'Nothing was measured from this run.'
                : '${nudged.isEmpty ? '' : '${nudged.map((n) => '${n['key']}').join(' ')}. '}Where this run’s taps landed is measured for the next fix.'),
          ),
          if (counted && board != null && (board.shifted > 0 || board.landed > 0)) SettingRowBox(child: BoardFigure(key: const ValueKey('trainer-run-areas'), board: board, view: BoardView.areas, run: true)),
          if (taught > 0) SettingRow(label: 'Your corrections', value: Text(_count(taught, 'mistake remembered', 'mistakes remembered'))),
          if (r['trial'] == true)
            SettingRow(
              label: 'Layout',
              value: Text(r['changed'] == true ? '${r['heightLabel']} keys, a trial' : 'Your own keys, in a trial'),
              hint: Text([
                    for (final s in _maps(layouts['scores'])) '${s['label']} ${s['score']} over ${_count(_int(s['runs']), 'run', 'runs')}',
                  ].join(', ') +
                  (moved == null ? '. ${_layoutStanding(layouts)}.' : '. ${moved['to']} is proven better than ${moved['from']}, over ${_count(_int(moved['pairs']), 'pair', 'pairs')} of runs: the trials go on from there.')),
            ),
          if (r['changed'] == true) const SettingRow(label: 'Your own keys are back', value: Text('As you set them')),
        ],
      ),
      if (!practice)
        PaneSection(children: [
          SettingRow(
            label: 'Your profile',
            value: Text(runs >= profileAt ? 'Ready' : '${_count(profileAt - runs, 'more run', 'more runs')} to go'),
            onPress: runs >= profileAt && trainer.state['profile'] is Map ? () => pushSettings(context, (_) => _ProfilePage(trainer: trainer, store: store)) : null,
          ),
        ]),
    ];
  }
}

/// A run: the story with what has been typed of it marked, the count so far, and the field the keyboard types in.
///
/// The field asks keyboards not to learn from it (`enableIMEPersonalizedLearning: false`): a story is not the
/// person's writing. Its cursor stays at its end, so what the keyboard reads back is always the whole of what was
/// typed. Each change is told to the keyboard, which looks at the field itself (a word tapped in its row is no key).
class TrainerRun extends StatefulWidget {
  const TrainerRun({super.key, required this.trainer, required this.onFinish});
  final TrainerStore trainer;
  final Future<void> Function() onFinish;

  @override
  State<TrainerRun> createState() => _TrainerRunState();
}

class _TrainerRunState extends State<TrainerRun> {
  final _field = TextEditingController();
  final _focus = FocusNode();
  final _scroll = ScrollController();
  final _editable = GlobalKey<EditableTextState>();
  bool _finishing = false;

  /// The keyboard was put away (the back key does that first): a tap on the story or the field brings it back.
  void _showKeyboard() {
    _focus.requestFocus();
    _editable.currentState?.requestKeyboard();
  }

  TrainerStore get trainer => widget.trainer;

  @override
  void initState() {
    super.initState();
    _field.addListener(_keepCursorAtEnd);
    trainer.addListener(_progressed);
  }

  @override
  void dispose() {
    trainer.removeListener(_progressed);
    _field.dispose();
    _focus.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _keepCursorAtEnd() {
    final end = _field.text.length;
    final s = _field.selection;
    if (s.baseOffset != end || s.extentOffset != end) _field.selection = TextSelection.collapsed(offset: end);
  }

  /// The keyboard said where the run stands: the story follows the word being typed, and a story that is done ends the run.
  void _progressed() {
    if (!mounted || trainer.run == null) return;
    final p = trainer.progress;
    if (p['done'] == true) {
      _finish();
      return;
    }
    final fates = p['fates'] as List? ?? const [];
    if (_scroll.hasClients && fates.isNotEmpty && _scroll.position.maxScrollExtent > 0) {
      final whole = _scroll.position.maxScrollExtent + _scroll.position.viewportDimension;
      final at = whole * _int(p['current']) / fates.length - _scroll.position.viewportDimension * 0.4;
      _scroll.animateTo(at.clamp(0.0, _scroll.position.maxScrollExtent), duration: const Duration(milliseconds: 160), curve: Curves.easeOut);
    }
  }

  Future<void> _finish() async {
    if (_finishing) return;
    _finishing = true;
    await widget.onFinish();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final run = trainer.run ?? const {};
    final p = trainer.progress;
    final pad = MediaQuery.paddingOf(context);
    final keyboard = MediaQuery.viewInsetsOf(context).bottom;
    final gutter = t.space(4);
    final text = '${run['text'] ?? ''}';
    final fates = [for (final f in (p['fates'] as List? ?? const [])) _int(f)];
    final words = _int(run['words']);
    final current = _int(p['current']);
    final quiet = pageText(t, t.fontSize('sm'), color: t.color('app-ink-3'));
    final title = run['practice'] == true ? 'Your mistakes' : '${run['title']} · ${run['part']} of ${run['parts']}';
    return GlacierScope(
      attrs: const {'hue': trainerHue},
      child: ColoredBox(
        color: t.color('glacier-bg'),
        child: Padding(
          padding: EdgeInsets.only(bottom: keyboard > 0 ? keyboard : pad.bottom),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SizedBox(height: pad.top),
              _Bar(
                title: title,
                onBack: () => Navigator.of(context).maybePop(),
                end: GlacierPressable(
                  onPressed: _finish,
                  semanticLabel: 'Finish the run',
                  builder: (context, state) => Padding(
                    padding: EdgeInsets.symmetric(horizontal: t.space(2), vertical: t.space(2)),
                    child: Text('Finish', style: pageText(t, t.fontSize('md'), weight: 'semibold', color: t.color(state.pressed ? 'app-ink-3' : 'app-ink'))),
                  ),
                ),
              ),
              Padding(
                padding: EdgeInsets.fromLTRB(gutter, 0, gutter, t.space(2)),
                child: Row(
                  children: [
                    Text('${_int(p['wpm'])}', key: const ValueKey('trainer-wpm'), style: pageText(t, t.fontSize('2xl'), weight: 'semibold', color: t.color('app-ink')).copyWith(fontFeatures: const [FontFeature.tabularFigures()])),
                    SizedBox(width: t.space(1)),
                    Flexible(child: Text('words a minute', maxLines: 1, overflow: TextOverflow.ellipsis, style: quiet)),
                    SizedBox(width: t.space(2)),
                    Expanded(
                      child: Text('${current.clamp(0, words)} of $words words · ${_clock(_int(p['ms']))}', key: const ValueKey('trainer-count'), textAlign: TextAlign.end, maxLines: 1, overflow: TextOverflow.ellipsis, style: quiet.copyWith(fontFeatures: const [FontFeature.tabularFigures()])),
                    ),
                  ],
                ),
              ),
              if (run['changed'] == true)
                Padding(
                  padding: EdgeInsets.fromLTRB(gutter, 0, gutter, t.space(2)),
                  child: Text('Trying ${run['heightLabel']} keys for this run. Your own (${run['ownHeightLabel']}) are back when it ends.', key: const ValueKey('trainer-trial'), style: quiet),
                ),
              // Which fix this run is typed on and tests; the first run is typed on the keyboard as it is.
              if (run['practice'] != true && (_int(run['fix']) > 0 || trainer.number('runs') == 0))
                Padding(
                  padding: EdgeInsets.fromLTRB(gutter, 0, gutter, t.space(2)),
                  child: Text(
                    _int(run['fix']) > 0
                        ? 'Testing fix ${run['fix']}: ${_count(_int(run['fixKeys']), 'key’s touch area', 'keys’ touch areas')} moved. Every tap is read both ways.'
                        : 'The first run: typed on your keyboard as it is, to measure where your taps land.',
                    key: const ValueKey('trainer-testing'),
                    style: quiet,
                  ),
                ),
              Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: _showKeyboard,
                  child: SingleChildScrollView(
                    controller: _scroll,
                    padding: EdgeInsets.fromLTRB(gutter, t.space(1), gutter, t.space(3)),
                    child: StoryText(text: text, fates: fates, current: current, tail: '${p['tail'] ?? ''}'),
                  ),
                ),
              ),
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: _showKeyboard,
                child: Padding(
                  padding: EdgeInsets.fromLTRB(gutter, 0, gutter, t.space(2)),
                  child: DecoratedBox(
                    decoration: BoxDecoration(color: t.color('app-paper-2'), borderRadius: BorderRadius.circular(t.radius('lg')), border: Border.all(color: t.color('app-rule'), width: t.len('glacier-hairline'))),
                    child: Padding(
                      padding: EdgeInsets.symmetric(horizontal: t.space(3), vertical: t.space(2)),
                      child: Stack(
                        children: [
                          if (_field.text.isEmpty) ExcludeSemantics(child: Text('Type the story here', style: pageText(t, t.fontSize('md'), color: t.color('app-ink-4')))),
                          Semantics(
                            label: 'Type the story here',
                            textField: true,
                            child: EditableText(
                              key: _editable,
                              controller: _field,
                              focusNode: _focus,
                              autofocus: true,
                              style: pageText(t, t.fontSize('md'), color: t.color('app-ink')),
                              cursorColor: t.color('app-ink'),
                              backgroundCursorColor: t.color('app-ink-4'),
                              cursorWidth: 1.5,
                              minLines: 2,
                              maxLines: 3,
                              // One line of text to the keyboard, so its enter key is Done and ends the run; it wraps here.
                              keyboardType: TextInputType.text,
                              textInputAction: TextInputAction.done,
                              textCapitalization: TextCapitalization.sentences,
                              autocorrect: true,
                              enableSuggestions: true,
                              // A story is not the person's writing: the field asks keyboards not to learn from it.
                              enableIMEPersonalizedLearning: false,
                              enableInteractiveSelection: false,
                              spellCheckConfiguration: const SpellCheckConfiguration.disabled(),
                              smartDashesType: SmartDashesType.disabled,
                              smartQuotesType: SmartQuotesType.disabled,
                              keyboardAppearance: t.isDark ? Brightness.dark : Brightness.light,
                              onChanged: (_) {
                                setState(() {});
                                trainer.look();
                              },
                              // Done keeps the keyboard up until the run has been scored.
                              onEditingComplete: () {},
                              onSubmitted: (_) => _finish(),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The story as a run shows it: the words typed right faded behind, the ones typed wrong in red, the word being
/// typed underlined with each of its letters marked as it is typed, and the rest waiting in ink.
class StoryText extends StatelessWidget {
  const StoryText({super.key, required this.text, required this.fates, required this.current, required this.tail});

  final String text;

  /// One per word: 0 to come, 1 right, 2 wrong, 3 skipped.
  final List<int> fates;
  final int current;

  /// What has been typed of the word at [current].
  final String tail;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final size = t.fontSize('lg');
    final ink = t.color('app-ink');
    final done = t.color('app-ink-3');
    final wrong = t.isDark ? const Color(0xFFFF6B61) : const Color(0xFFDD3A30);
    final words = text.split(' ');
    final spans = <InlineSpan>[];
    for (var i = 0; i < words.length; i++) {
      if (i > 0) spans.add(const TextSpan(text: ' '));
      final fate = i < fates.length ? fates[i] : 0;
      if (i == current && fate == 0) {
        final word = words[i];
        for (var c = 0; c < word.length; c++) {
          final typed = c < tail.length;
          spans.add(TextSpan(
            text: word[c],
            style: TextStyle(color: !typed ? ink : (tail[c] == word[c] ? done : wrong), decoration: TextDecoration.underline, decorationColor: ink, decorationThickness: 2),
          ));
        }
        continue;
      }
      spans.add(TextSpan(
        text: words[i],
        style: switch (fate) {
          1 => TextStyle(color: done),
          2 => TextStyle(color: wrong),
          3 => TextStyle(color: wrong, decoration: TextDecoration.lineThrough, decorationColor: wrong),
          _ => TextStyle(color: ink),
        },
      ));
    }
    return Text.rich(TextSpan(children: spans), style: pageText(t, size, color: ink, height: 1.55));
  }
}

/// The story runs as bars, oldest first: each as tall as its speed against the fastest, with the speed over it.
class RunBars extends StatelessWidget {
  const RunBars({super.key, required this.runs});
  final List<Map<Object?, Object?>> runs;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    var most = 1;
    for (final r in runs) {
      if (_int(r['wpm']) > most) most = _int(r['wpm']);
    }
    const tall = 64.0;
    final small = TextStyle(fontSize: 11, color: t.color('app-ink-3'));
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        for (var i = 0; i < runs.length; i++)
          Expanded(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('${_int(runs[i]['wpm'])}', maxLines: 1, style: small),
                SizedBox(height: t.space(1)),
                Container(
                  height: 3 + (tall - 3) * _int(runs[i]['wpm']) / most,
                  margin: EdgeInsets.symmetric(horizontal: t.space(1)),
                  decoration: BoxDecoration(color: i == runs.length - 1 ? settingsHue(t) : settingsHueWash(t), borderRadius: BorderRadius.circular(4)),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

class _HistoryPage extends StatelessWidget {
  const _HistoryPage({required this.trainer});
  final TrainerStore trainer;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: trainer,
        builder: (context, _) {
          final history = trainer.list('history');
          return _Page(title: 'History', children: [
            PaneSection(
              footer: const Text(
                'The latest runs, newest first. Speed is words a minute; the percentage is the share of taps that hit the key they were meant for. A run that tested a fix says which, and what your keyboard as it is would have made of the same taps.',
              ),
              children: [
                for (final r in history)
                  SettingRow(
                    label: '${r['title']}',
                    hint: Text([
                      _when(r['at']),
                      if (r['practice'] == true) 'practice',
                      if (r['trial'] == true) '${r['heightLabel']} keys, a trial',
                      '${r['right']} of ${r['words']} words',
                      if (_int(r['fix']) > 0) 'fix ${r['fix']}${r['asIs'] != null && _int(r['asIs']) >= 0 ? ', ${_percent(r['asIs'])} as it is' : ''}',
                    ].join(' · ')),
                    value: Text('${r['wpm']} · ${_percent(r['accuracy'])}'),
                  ),
              ],
            ),
          ]);
        },
      );
}

/// How a word's latest tries went, as marks: a tick for right, a cross for missed, newest last.
String _marks(Object? last) => '$last'.split('').map((c) => c == '1' ? '✓' : '✗').join(' ');

String? _trend(Object? trend) => switch (_int(trend)) { -1 => 'Getting better.', 1 => 'Getting worse.', _ => null };

class _MistakesPage extends StatelessWidget {
  const _MistakesPage({required this.trainer, required this.onPractise});
  final TrainerStore trainer;
  final VoidCallback onPractise;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: trainer,
        builder: (context, _) {
          final words = trainer.list('mistakes');
          final pairs = trainer.list('pairs');
          final keys = trainer.list('keys');
          final board = TrainerBoardData.of(trainer.state['board']);
          return _Page(title: 'Your mistakes', children: [
            if (board != null && board.taps > 0)
              PaneSection(
                title: 'On the keyboard',
                footer: const Text('Over every run. A key is tinted by how often a tap meant for it missed, once it has had ten taps; an arrow points from a key to the one hit in its place.'),
                children: [SettingRowBox(child: BoardFigure(key: const ValueKey('trainer-mistakes-map'), board: board, view: BoardView.misses))],
              ),
            if (words.isNotEmpty)
              PaneSection(
                title: 'Words',
                footer: const Text('A word counts as missed when its first try was not the word, whatever autocorrect or a backspace made of it after. The marks are its latest tries, newest last.'),
                children: [
                  for (final m in words)
                    SettingRow(
                      label: '${m['word']}',
                      hint: Text(['${m['typed']}'.isEmpty ? null : 'Typed ${m['typed']}', 'missed ${m['missed']} of ${_count(_int(m['seen']), 'try', 'tries')}', _int(m['streak']) >= 2 ? 'right the last ${m['streak']}' : null].whereType<String>().join(' · ')),
                      value: Text(_marks(m['last'])),
                    ),
                ],
              ),
            if (pairs.isNotEmpty)
              PaneSection(
                title: 'Letter pairs',
                footer: const Text('Two letters one after the other, where the second goes wrong or the two are swapped.'),
                children: [
                  for (final p in pairs)
                    SettingRow(label: '${'${p['pair']}'[0]} then ${'${p['pair']}'[1]}', value: Text('${p['missed']} of ${p['n']}'), hint: _trend(p['trend']) == null ? null : Text(_trend(p['trend'])!)),
                ],
              ),
            if (keys.isNotEmpty)
              PaneSection(
                title: 'Keys',
                children: [
                  for (final k in keys)
                    SettingRow(
                      label: 'The ${k['key']} key',
                      value: Text('${k['missed']} of ${k['n']}'),
                      hint: Text([if (k['toward'] != null) 'Mostly ${k['toward']} in its place.', ?_trend(k['trend'])].join(' ')),
                    ),
                ],
              ),
            if (trainer.flag('canPractise'))
              PaneSection(children: [
                SettingRow(
                  label: 'Practise my mistakes',
                  hint: const Text('A short run of these words, and of words with these letter pairs.'),
                  onPress: () {
                    Navigator.of(context).maybePop();
                    onPractise();
                  },
                ),
              ]),
          ]);
        },
      );
}

/// Where the trial of a key height stands, in a line with no full stop: how its pairs of runs have gone so far.
String _layoutStanding(Map<Object?, Object?> layouts) {
  final candidate = layouts['candidateLabel'];
  if (candidate == null) return layouts['settled'] == true ? 'No height was proven better than your own' : 'Nothing is being tried against your own keys now';
  final pairs = _int(layouts['pairs']);
  final least = _int(layouts['minPairs']);
  if (pairs == 0) return '$candidate is being tried against ${layouts['homeLabel']}, a pair of runs at a time';
  final soFar = '$candidate scored higher in ${layouts['wins']} of ${_count(pairs, 'pair', 'pairs')} of runs so far';
  return pairs < least ? '$soFar; nothing is decided before $least' : '$soFar, which is not proof yet';
}

/// A lean as the profile's rows say it: "14% right, 6% low" of a key.
String _lean(Object? dx, Object? dy) {
  final x = ((dx as num?) ?? 0) * 100;
  final y = ((dy as num?) ?? 0) * 100;
  return [if (x.abs() >= 1) '${x.abs().round()}% ${x > 0 ? 'right' : 'left'}', if (y.abs() >= 1) '${y.abs().round()}% ${y > 0 ? 'low' : 'high'}'].join(', ').ifEmpty('in the middle');
}

extension on String {
  String ifEmpty(String other) => isEmpty ? other : this;
}

class _ProfilePage extends StatefulWidget {
  const _ProfilePage({required this.trainer, required this.store});
  final TrainerStore trainer;
  final SettingsStore store;

  @override
  State<_ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<_ProfilePage> {
  bool _file = false;

  Future<void> _apply({bool fix = false, bool height = false}) async {
    await widget.trainer.apply(fix: fix, height: height);
    // Switching may add Adaptive keys, move its nudges, and change the key height: Settings reads them again.
    await widget.store.load();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: widget.trainer,
        builder: (context, _) {
          final trainer = widget.trainer;
          final p = trainer.map('profile');
          if (p == null) return const _Page(title: 'Your profile', children: [SettingsCallout(child: Text('There is no profile yet.'))]);
          final keys = _maps(p['keys'])..sort((a, b) => (((b['dx'] as num?) ?? 0).abs() + ((b['dy'] as num?) ?? 0).abs()).compareTo(((a['dx'] as num?) ?? 0).abs() + ((a['dy'] as num?) ?? 0).abs()));
          final slips = _maps(p['slips']);
          final suggestion = p['suggestion'] is Map ? p['suggestion'] as Map : null;
          final layouts = trainer.map('layouts') ?? const {};
          final scores = _maps(layouts['scores']);
          final fix = FixData.of(trainer.state['fix']);
          final board = TrainerBoardData.of(trainer.state['board']);
          return _Page(title: 'Your profile', children: [
            PaneHero(glyph: AppIcon(Lucide.circleUser, size: 24), title: '${p['wpmNow']} words a minute', meta: Text('${p['report']}'), status: PaneHeroStatus('From ${_count(_int(p['runs']), 'run', 'runs')}')),
            PaneSection(
              title: 'Speed and accuracy',
              footer: const Text('At first: your first three runs. Now: your last three. Accuracy is the share of taps that hit the key they were meant for.'),
              children: [
                SettingRow(label: 'At first', value: Text('${p['wpmFirst']} words a minute · ${_percent(p['accuracyFirst'])}')),
                SettingRow(label: 'Now', value: Text('${p['wpmNow']} words a minute · ${_percent(p['accuracyNow'])}')),
                SettingRow(label: 'Best run', value: Text('${p['wpmBest']} words a minute')),
              ],
            ),
            PaneSection(
              title: 'Where your taps land',
              footer: const Text('Measured over every tap of your runs, from the middle of the key each was meant for, as a share of the key. The keys furthest off come first.'),
              children: [
                if (board != null && (board.landed > 0 || board.shifted > 0)) SettingRowBox(child: BoardFigure(key: const ValueKey('trainer-profile-map'), board: board, view: BoardView.areas)),
                SettingRow(label: 'On the whole', hint: Text('${p['lean']}')),
                for (final k in keys.take(8)) SettingRow(label: 'The ${k['key']} key', value: Text(_lean(k['dx'], k['dy'])), hint: Text(_count(_int(k['samples']), 'tap', 'taps'))),
                for (final s in slips.take(5)) SettingRow(label: '${s['pressed']} for ${s['meant']}', value: Text(_count(_int(s['count']), 'slip', 'slips'))),
              ],
            ),
            PaneSection(
              title: 'Key height',
              footer: Text(scores.isEmpty
                  ? 'Turn on “Try different layouts” on the trainer’s first page to have runs compare key heights. Nothing is tried without it.'
                  : 'A height is tried against your own in pairs of runs, turn about, and is suggested only once it is proven better: over four pairs or more, a lead steadier from pair to pair than chance gives once in two hundred tries. Scores are words a minute times accuracy; the percentage is the taps on their key.'),
              children: [
                if (scores.isEmpty) SettingRow(label: 'Not tried', value: Text('${layouts['ownLabel'] ?? 'Your own'} keys')),
                for (final s in scores)
                  SettingRow(
                    label: '${s['label']}${s['height'] == layouts['own'] ? ' (yours)' : ''}',
                    value: Text('${s['score']}${s['accuracy'] != null && _int(s['accuracy']) >= 0 ? ' · ${_percent(s['accuracy'])}' : ''} over ${_count(_int(s['runs']), 'run', 'runs')}'),
                  ),
                if (scores.isNotEmpty && layouts['candidate'] != null)
                  SettingRow(key: const ValueKey('trainer-height-trial'), label: 'Trying ${layouts['candidateLabel']}', value: const Text('Not proven yet'), hint: Text('${_layoutStanding(layouts)}.')),
                if (suggestion != null)
                  SettingRow(
                    key: const ValueKey('trainer-height'),
                    label: 'Use ${suggestion['label']} keys',
                    hint: Text(
                      'Proven better${_int(suggestion['pairs']) > 0 ? ' over ${_count(_int(suggestion['pairs']), 'pair', 'pairs')} of runs, of which it scored higher in ${suggestion['wins']}' : ''}: ${suggestion['score']} against ${suggestion['ownScore']} on your own.',
                    ),
                    onPress: () => _apply(height: true),
                  ),
                if (suggestion == null && layouts['settled'] == true) const SettingRow(label: 'No height was proven better than your own', value: Text('Settled')),
                if (scores.isNotEmpty && layouts['settled'] == true) SettingRow(label: 'Try the layouts again', onPress: trainer.again),
              ],
            ),
            PaneSection(
              title: 'Everyday typing',
              footer: const Text(_testSaid),
              children: [
                ..._fixRows(trainer, fix, storyRuns: _int(p['runs']), onSwitch: () => _apply(fix: true)),
                SettingRow(label: _file ? 'Hide the typing profile' : 'See it as a typing profile', hint: const Text('Where your taps were measured to land, as the open file Settings imports and exports.'), onPress: () => setState(() => _file = !_file)),
                if (_file) SettingRowBox(child: Text('${p['file']}', style: const TextStyle(fontFamily: 'monospace', fontSize: 11, height: 1.35))),
              ],
            ),
            PaneSection(children: [
              SettingRow(
                label: 'Forget what the trainer learned',
                hint: const Text('Its runs, your mistakes, this profile, its fixes, and what a fix you switched to gave the keys.'),
                onPress: () {
                  trainer.forget();
                  Navigator.of(context).maybePop();
                },
              ),
            ]),
          ]);
        },
      );
}
