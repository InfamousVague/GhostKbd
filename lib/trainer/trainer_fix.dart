/// The trainer's test of its fixes, as the keyboard tells it (Android's FixTrial, through TrainerMaps.fix; Matt,
/// 2026-11-11: "make each run after the first test a new fixes and a b test the accuracy and don't suggest switching
/// until its proven we got a better result"). The first run is typed on the keyboard as it is; each story run after
/// it is typed on a new fix, and the keyboard reads every tap of it both ways. This is what it sent, read into
/// something a page can ask, and the sentences the pages say of it.
///
/// The keyboard decides everything here, proof included. Nothing in this file judges: a fix is offered where the
/// keyboard says `proven` and nowhere else.
library;

double _num(Object? v) => v is num && v.isFinite ? v.toDouble() : 0;
int _whole(Object? v) => v is num && v.isFinite ? v.toInt() : 0;

String _taps(int n) => n == 1 ? '1 tap' : '$n taps';
String _runs(int n) => n == 1 ? '1 run' : '$n runs';
String _keys(int n) => n == 1 ? '1 key’s touch area' : '$n keys’ touch areas';

/// 96.4 as "96.4%", a whole number without its ".0", and a dash where there is none to give.
String shareSaid(double? share) {
  if (share == null || share < 0) return '—';
  return '${share == share.roundToDouble() ? share.round() : share.toStringAsFixed(1)}%';
}

/// 1234567 as "a million", 12345 as "12,345".
String _big(int n) {
  if (n >= 1000000) return 'a million';
  final s = '$n';
  final out = StringBuffer();
  for (var i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) out.write(',');
    out.write(s[i]);
  }
  return out.toString();
}

/// One run that tested a fix: how many of its [taps] the keyboard as it is would have put on their key ([asIs]), how
/// many the fix did ([fixed]), and the taps only one of them read right ([won]: the fix; [lost]: the keyboard).
class FixRunData {
  const FixRunData({required this.number, required this.taps, required this.asIs, required this.fixed, required this.won, required this.lost});

  final int number;
  final int taps;
  final int asIs;
  final int fixed;
  final int won;
  final int lost;

  static FixRunData? of(Object? raw) {
    if (raw is! Map) return null;
    final taps = _whole(raw['taps']);
    if (taps <= 0) return null;
    return FixRunData(
      number: _whole(raw['number']),
      taps: taps,
      asIs: _whole(raw['asIs']).clamp(0, taps),
      fixed: _whole(raw['fixed']).clamp(0, taps),
      won: _whole(raw['won']).clamp(0, taps),
      lost: _whole(raw['lost']).clamp(0, taps),
    );
  }

  /// Each reading's taps on their key, as a percentage.
  double get asIsShare => asIs * 100 / taps;
  double get fixedShare => fixed * 100 / taps;

  /// The two readings of this run's taps, in a line.
  String get said => won == 0 && lost == 0
      ? 'The same ${_taps(taps)}, read both ways: the two read every one alike.'
      : 'The same ${_taps(taps)}, read both ways: $won only the fix read right, $lost only your keyboard did.';
}

/// Where the test of the fixes stands.
class FixData {
  const FixData({
    this.number = 0,
    this.last = 0,
    this.tested = true,
    this.taken = 0,
    this.dropped = 0,
    this.adaptive = false,
    this.differs = 0,
    this.next = false,
    this.verdict = 'none',
    this.hold,
    this.runs = 0,
    this.taps = 0,
    this.asIs = 0,
    this.fixed = 0,
    this.won = 0,
    this.lost = 0,
    this.oneIn = 0,
    this.needs = 3,
    this.bar = 100,
    this.tests = const [],
    this.worn = 0,
    this.run,
  });

  /// Which fix is in hand (0: none yet), whether a run has tested it, the fix everyday typing was switched to and
  /// the one last dropped as worse (0: none), whether Adaptive keys is added, and how many keys the fix in hand has
  /// listening somewhere else than the keyboard as it is.
  final int number;

  /// The fix the runs tested: the one the verdict speaks of, and the one a switch takes. The fix in hand may be a
  /// step on from it, made for a run that was then left. 0: none has been tested.
  final int last;
  final bool tested;
  final int taken;
  final int dropped;
  final bool adaptive;
  final int differs;

  /// Whether a story run started now would be typed on a fix and test it.
  final bool next;

  /// `none` (no run has tested a fix since the keyboard last changed), `testing`, `proven` or `worse`; and, while
  /// testing, what keeps it from proven: `runs`, `behind`, `split`, `steady` or `last`.
  final String verdict;
  final String? hold;

  /// The runs that tested a fix, added up: every tap read both ways.
  final int runs;
  final int taps;
  final int asIs;
  final int fixed;
  final int won;
  final int lost;

  /// Chance alone would split the taps read differently this far the fix's way once in this many tries (0: more
  /// often than one time in two). Proof needs [needs] runs and less than once in [bar].
  final int oneIn;
  final int needs;
  final int bar;

  /// Each run that tested a fix, oldest first.
  final List<FixRunData> tests;

  /// On a run's results only: the fix that run was typed on (0: none), and what its test came to (null: it tested
  /// nothing, or was not counted).
  final int worn;
  final FixRunData? run;

  /// What the keyboard sent; one with nothing in it where it sent none.
  static FixData of(Object? raw) {
    if (raw is! Map) return const FixData();
    final needs = _whole(raw['needs']);
    final bar = _whole(raw['bar']);
    return FixData(
      number: _whole(raw['number']),
      last: _whole(raw['last']),
      tested: raw['tested'] != false,
      taken: _whole(raw['taken']),
      dropped: _whole(raw['dropped']),
      adaptive: raw['adaptive'] == true,
      differs: _whole(raw['differs']),
      next: raw['next'] == true,
      verdict: '${raw['verdict'] ?? 'none'}',
      hold: raw['hold'] is String ? raw['hold'] as String : null,
      runs: _whole(raw['runs']),
      taps: _whole(raw['taps']),
      asIs: _whole(raw['asIs']),
      fixed: _whole(raw['fixed']),
      won: _whole(raw['won']),
      lost: _whole(raw['lost']),
      oneIn: _whole(raw['oneIn']),
      needs: needs > 0 ? needs : 3,
      bar: bar > 0 ? bar : 100,
      tests: [
        for (final r in (raw['tests'] is List ? raw['tests'] as List : const <Object?>[]))
          if (FixRunData.of(r) case final FixRunData run) run,
      ],
      worn: _whole(raw['worn']),
      run: FixRunData.of(raw['run']),
    );
  }

  double? get asIsShare => taps > 0 ? _num(asIs * 100 / taps) : null;
  double? get fixedShare => taps > 0 ? _num(fixed * 100 / taps) : null;

  /// Proven, by the keyboard's own verdict, with a tested fix to switch to.
  bool get proven => verdict == 'proven' && last > 0 && runs > 0;

  /// The fix just dropped as worse, with nothing tested since; and the fix everyday typing uses, with nothing tested since.
  bool get justDropped => dropped > 0 && runs == 0 && (verdict == 'worse' || dropped == number);
  bool get justTaken => taken > 0 && taken == number && runs == 0 && !justDropped;

  /// The row's name: the fix in hand.
  String get title {
    if (justDropped) return 'Fix $dropped';
    if (number == 0) return next ? 'The first fix' : 'No fix yet';
    // With runs behind it the row is about the fix they tested.
    return 'Fix ${runs > 0 && last > 0 ? last : number}';
  }

  /// Where it stands, in two or three words.
  String get status {
    if (justDropped || verdict == 'worse') return 'Dropped';
    if (justTaken) return 'In everyday typing';
    if (number == 0 || (runs == 0 && tested)) return next ? 'Next run tests one' : 'Nothing to test';
    if (runs == 0) return 'Next run tests it';
    return proven ? 'Proven better' : 'Not proven yet';
  }

  /// Where it stands, in a sentence or two. [storyRuns] is how many stories have been typed.
  String standing({required int storyRuns}) {
    if (justDropped || verdict == 'worse') {
      return 'Fix $dropped read fewer of your taps right than your keyboard as it is, so it was dropped. The next fix starts again from your keyboard.';
    }
    if (justTaken) return 'Everyday typing reads your taps by fix $taken. The next run tests a new fix against it.';
    if (number == 0 || (runs == 0 && tested)) {
      if (storyRuns == 0) return 'The first run is typed on your keyboard as it is, and measures where your taps land. Each run after it tests a fix.';
      if (next) return 'The next run is typed on a fix: the keys’ touch areas a step nearer to where your taps have landed. Every tap of it is read both ways, by the fix and by your keyboard as it is.';
      return 'A fix moves each key’s touch area toward where your taps land. There is nothing to test yet: no key’s taps land far enough from where it listens now.';
    }
    if (runs == 0) {
      return 'It moves ${_keys(differs)} toward where your taps land. The next run is typed on it, and every tap is read both ways: by the fix, and by your keyboard as it is.';
    }
    final split = won == 0 && lost == 0
        ? 'Over ${_runs(runs)} the fix and your keyboard as it is read every tap alike.'
        : 'Over ${_runs(runs)} the fix read ${_taps(won)} right that your keyboard as it is read wrong, and $lost the other way.';
    if (proven) {
      return '$split ${oneIn > 0 ? 'Chance would split them that far less than once in ${_big(oneIn)} tries, and it' : 'It'} led steadily from run to run.';
    }
    final why = switch (hold) {
      'runs' => 'A fix is proven on $needs runs or more: ${needs - runs == 1 ? '1 more' : '${needs - runs} more'} to go.',
      'behind' => 'It has not read more taps right than your keyboard.',
      'split' => 'That is too few, or too even, to rule out chance.',
      'steady' => 'It is ahead on the count, but not steadily from run to run, and one good run is not proof.',
      'last' => 'The run just typed went against it.',
      _ => 'That is not proof yet.',
    };
    return '$split $why';
  }

  /// What switching does, under its row.
  String get switchSaid => adaptive
      ? 'Moves Adaptive keys’ touch areas to where fix $last has them. Like any nudge there they drift back as your aim comes right.'
      : 'Adds Adaptive keys, which is not added, with each touch area where fix $last has it. Like any nudge there they drift back as your aim comes right.';
}
