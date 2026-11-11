/// The typing trainer's side of the app (Android's Trainer.kt, through TrainerChannel's `dev_keyboard/trainer`): what
/// the trainer has kept, the run in hand, and where that run stands. The keyboard does the measuring and the
/// reckoning; this only asks and is told.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class TrainerStore extends ChangeNotifier {
  TrainerStore() {
    _channel.setMethodCallHandler((call) async {
      // After each key of a run: every story word's fate, the word being typed, the speed so far, and whether the story is done.
      if (call.method == 'progress' && call.arguments is Map && run != null) {
        progress = _strings(call.arguments as Map);
        notifyListeners();
      }
      // The run was dropped under the screen: the app was left while it was on.
      if (call.method == 'left' && run != null) {
        run = null;
        progress = const {};
        dropped = true;
        notifyListeners();
      }
    });
  }

  /// The app's one trainer, as Settings' list and the trainer's screen share it.
  static final TrainerStore shared = TrainerStore();

  static const _channel = MethodChannel('dev_keyboard/trainer');

  /// Everything the trainer shows that is not a run in hand (Trainer.state): whether Ghost Keyboard is `selected`,
  /// the `next` story part, the `trials` switch, `runs` and `history`, `mistakes`, `pairs` and `keys`, the `profile`
  /// once there is one, and the `layouts` trials. Empty until the keyboard has answered (and off Android).
  Map<String, Object?> state = const {};
  bool loaded = false;

  /// The run in hand, as it was started: its `text`, `title`, `part` of `parts`, and the `height` it is typed on.
  Map<String, Object?>? run;

  /// Where the run stands: `fates` (one per story word: 0 to come, 1 right, 2 wrong, 3 skipped), `current`, `tail`,
  /// `wpm`, `ms`, `done`.
  Map<String, Object?> progress = const {};

  /// The last run, scored (Trainer.finish).
  Map<String, Object?>? result;

  /// A run was dropped because the app was left while it was on; cleared by the next run.
  bool dropped = false;

  /// What applying the profile said, a line each.
  List<String> said = const [];

  static Map<String, Object?> _strings(Map raw) => raw.map((k, v) => MapEntry('$k', v as Object?));

  Future<Map<String, Object?>?> _ask(String method, [Map<String, Object?>? args]) async {
    try {
      final r = await _channel.invokeMethod<Object>(method, args);
      return r is Map ? _strings(r) : null;
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  void _take(Map<String, Object?>? next) {
    if (next == null) return;
    final lines = next.remove('said');
    if (lines is List) said = [for (final l in lines) '$l'];
    state = next;
    loaded = true;
    notifyListeners();
  }

  Future<void> refresh() async => _take(await _ask('state'));

  /// "Try different layouts": off unless turned on, and read by the keyboard as each run starts.
  Future<void> setTrials(bool on) async {
    state = {...state, 'trials': on};
    notifyListeners();
    _take(await _ask('setTrials', {'on': on}));
  }

  /// Starts a run of the next story part, or with [practice] of the person's own mistakes. Null where it started;
  /// else why it did not: `keyboard` (Ghost Keyboard is not the keyboard in use), `layout` (its keys cannot type the
  /// stories), `practice` (nothing to practise), or `unavailable`.
  Future<String?> start({bool practice = false}) async {
    final r = await _ask('start', {'practice': practice});
    if (r == null) return 'unavailable';
    if (r['ok'] != true) return '${r['error'] ?? 'unavailable'}';
    run = r;
    progress = const {};
    result = null;
    dropped = false;
    notifyListeners();
    return null;
  }

  /// The run's field changed: the keyboard looks at it again (a word tapped in its row is no key press).
  void look() {
    if (run == null) return;
    _ask('look');
  }

  /// Ends the run and has it scored.
  Future<void> finish() async {
    if (run == null) return;
    final r = await _ask('finish');
    run = null;
    progress = const {};
    if (r != null && r['error'] == null) result = r;
    notifyListeners();
    await refresh();
  }

  /// Drops the run in hand, uncounted.
  Future<void> leave() async {
    if (run == null) return;
    run = null;
    progress = const {};
    notifyListeners();
    await _ask('leave');
  }

  /// Puts the profile to use in everyday typing: with [aims] where the taps land, with [height] the key height the runs point to.
  Future<void> apply({bool aims = false, bool height = false}) async => _take(await _ask('apply', {'aims': aims, 'height': height}));

  /// Opens every layout to trial again.
  Future<void> again() async => _take(await _ask('again'));

  /// Deletes everything the trainer kept.
  Future<void> forget() async {
    result = null;
    said = const [];
    _take(await _ask('forget'));
  }

  // --- what the screen reads --------------------------------------------------------------------------------------

  int number(String key) => (state[key] as num?)?.toInt() ?? 0;
  bool flag(String key) => state[key] == true;
  List<Map<Object?, Object?>> list(String key) => [for (final e in (state[key] as List? ?? const [])) if (e is Map) e];
  Map<Object?, Object?>? map(String key) => state[key] is Map ? state[key] as Map : null;

  bool _asked = false;

  /// The line under "Trainer" on Settings' list. The first time it is wanted the keyboard is asked for what the
  /// trainer has kept, and [redraw] is called once it has answered, so the line is right before the trainer is opened.
  String summaryOr(void Function() redraw) {
    if (!loaded && !_asked) {
      _asked = true;
      refresh().then((_) {
        if (loaded) redraw();
      });
    }
    return summary;
  }

  String get summary {
    final runs = number('runs');
    if (!loaded || runs == 0) return 'Type a story; the keys learn your thumbs';
    final best = number('best');
    return '${runs == 1 ? '1 run' : '$runs runs'}${best > 0 ? ' · $best words a minute at best' : ''}';
  }
}
