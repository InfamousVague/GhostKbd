import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// The keyboard's settings and setup state, through MainActivity's `dev_keyboard/settings` channel.
///
/// The settings live in the Kotlin side's SharedPreferences (KeyboardPrefs.kt), which the keyboard service reads
/// live, so a change here shows on the keyboard the next time it draws. The keys and their defaults are Kotlin's;
/// [defaults] only covers the first frame before they arrive (and runs off-device, where there is no channel).
class SettingsStore extends ChangeNotifier {
  SettingsStore() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'statusChanged') _setStatus(call.arguments);
      if (call.method == 'updateChanged') _setUpdate(call.arguments);
      if (call.method == 'dictationChanged') _setDictation(call.arguments);
      if (call.method == 'open' && call.arguments is String) openRequests.value = call.arguments as String;
    });
  }

  static const _channel = MethodChannel('dev_keyboard/settings');

  static const Map<String, Object> defaults = {
    'theme': 'system',
    'number_row': false,
    'key_popup': true,
    'sound': false,
    'vibrate': true,
    'hints': true,
    'auto_caps': true,
    'double_space_period': true,
    'space_cursor': true,
    'key_height': 'normal',
    'long_press': 'normal',
    'auto_update': true,
    'key_borders': false,
    'toolbar': false,
    'haptics': 'medium',
    'tap_log': false,
    'autocorrect': true,
    'languages': 'en',
    'learned_words': '',
    'suggestions': true,
    'giphy_key': '',
    'theme_tint': '#7CC4FA',
    'theme_accent': '',
    'theme_mode': 'dark',
    'popup_keys': '',
    'plugins': 'autocorrect',
    'theme_preset': 'gboard',
    'custom_theme': '#1B1B1F,#3B3C40,#2A2B2F,#A8C7FA,#E3E3E3',
    'onboarded': false,
  };

  final Map<String, Object> _values = {...defaults};

  /// Whether Dev Keyboard is turned on in the system's keyboard list.
  bool enabled = false;

  /// Whether Dev Keyboard is the keyboard in use.
  bool selected = false;

  bool loaded = false;

  /// The app's own updates (Updater.kt): `phase` is idle, disabled, checking, upToDate, available, downloading,
  /// ready, installing or failed; `current`, `available`, `progress` and `error` go with it.
  Map<String, Object?> update = const {'phase': 'idle'};

  /// The dictation plugin's model and microphone (WhisperModel.kt): `phase` missing, downloading, ready or failed;
  /// `progress`, `error`, and `microphone` (allowed or not).
  Map<String, Object?> dictation = const {'phase': 'missing', 'microphone': false};

  /// A settings page the keyboard asked the app to open at (its mic key, before the microphone is allowed).
  final ValueNotifier<String?> openRequests = ValueNotifier(null);

  /// The added plugins' ids.
  Set<String> get plugins => choice('plugins').split(',').map((id) => id.trim()).where((id) => id.isNotEmpty).toSet();

  bool pluginOn(String id) => plugins.contains(id);

  Future<void> setPlugin(String id, bool on) async {
    final next = {...plugins};
    on ? next.add(id) : next.remove(id);
    _values['plugins'] = next.join(',');
    notifyListeners();
    try {
      final values = await _channel.invokeMapMethod<String, Object>('setPlugin', {'id': id, 'on': on});
      if (values != null) _values.addAll(values);
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  Future<void> downloadModel() => _call('downloadModel');
  Future<void> deleteModel() => _call('deleteModel');
  Future<void> requestMicrophone() => _call('requestMicrophone');

  /// The page the app was opened at, once.
  Future<String?> openAt() async {
    try {
      return await _channel.invokeMethod<String>('openAt');
    } on MissingPluginException {
      return null;
    }
  }

  bool flag(String key) => _values[key] as bool? ?? defaults[key] as bool;
  String choice(String key) => _values[key] as String? ?? defaults[key] as String;

  /// Autocorrect's languages, as codes ("en", "es").
  List<String> get languages => choice('languages').split(',').where((l) => l.isNotEmpty).toList();
  Future<void> setLanguage(String code, bool on) {
    final now = languages.toSet();
    on ? now.add(code) : now.remove(code);
    if (now.isEmpty) now.add('en');
    return set('languages', autocorrectLanguages.keys.where(now.contains).join(','));
  }

  /// The letters page's long-press popups as they ship (Layouts.defaultPopups), in QWERTY order.
  List<({String key, List<String> alts})> popupDefaults = const [];

  /// The person's own popups, by key: "a=á à â" a line, the keyboard's format.
  Map<String, List<String>> get popupKeys => {
        for (final line in choice('popup_keys').split('\n'))
          if (line.indexOf('=', 1) case final eq when eq > 0) line.substring(0, eq): line.substring(eq + 1).split(' ').where((a) => a.isNotEmpty).toList(),
      };

  /// What [key]'s popup offers now: the person's own, else as it ships.
  List<String> popupFor(String key) => popupKeys[key] ?? popupDefaults.where((p) => p.key == key).firstOrNull?.alts ?? const [];

  /// Sets [key]'s popup; null puts it back as it ships.
  Future<void> setPopup(String key, List<String>? alts) {
    final now = popupKeys;
    final shipped = popupDefaults.where((p) => p.key == key).firstOrNull?.alts;
    if (alts == null || (shipped != null && alts.join(' ') == shipped.join(' '))) {
      now.remove(key);
    } else {
      now[key] = alts;
    }
    return set('popup_keys', [for (final e in now.entries) '${e.key}=${e.value.join(' ')}'].join('\n'));
  }

  /// Words autocorrect keeps as typed: learned when a fix is undone, or added here.
  List<String> get learnedWords => choice('learned_words').split('\n').map((w) => w.trim()).where((w) => w.isNotEmpty).toList();
  Future<void> setLearned(String word, bool keep) {
    final now = learnedWords.toSet();
    final w = word.trim().toLowerCase();
    if (w.isEmpty) return Future.value();
    keep ? now.add(w) : now.remove(w);
    return set('learned_words', (now.toList()..sort()).join('\n'));
  }

  Future<void> load() async {
    try {
      final values = await _channel.invokeMapMethod<String, Object>('getSettings');
      if (values != null) _values.addAll(values);
      _setStatus(await _channel.invokeMethod<Object>('status'));
      _setUpdate(await _channel.invokeMethod<Object>('updateState'));
      _setDictation(await _channel.invokeMethod<Object>('dictationState'));
      final popups = await _channel.invokeListMethod<Object>('popupDefaults');
      if (popups != null) {
        popupDefaults = [
          for (final p in popups.whereType<Map>()) (key: p['key'] as String, alts: [for (final a in (p['alts'] as List? ?? const [])) a as String]),
        ];
      }
    } on MissingPluginException {
      // Not on Android (a test, a desktop run): the defaults stand.
    }
    loaded = true;
    notifyListeners();
  }

  Future<void> refreshStatus() async {
    try {
      _setStatus(await _channel.invokeMethod<Object>('status'));
    } on MissingPluginException {
      return;
    }
  }

  /// Sets one setting here at once, so the control answers the finger, then on the keyboard's side.
  Future<void> set(String key, Object value) async {
    _values[key] = value;
    notifyListeners();
    try {
      await _channel.invokeMethod<void>('setSetting', {'key': key, 'value': value});
    } on MissingPluginException {
      return;
    }
  }

  Future<void> openInputSettings() => _call('openInputSettings');
  Future<void> openAutofillSettings() => _call('openAutofillSettings');
  Future<void> showPicker() => _call('showPicker');
  Future<void> checkForUpdate() => _call('checkUpdate');

  /// The keyboard's tap log (TapLog.kt): its text and how many taps it holds.
  Future<({String text, int count})> tapLog() async {
    try {
      final raw = await _channel.invokeMapMethod<String, Object>('tapLog');
      return (text: raw?['text'] as String? ?? '', count: raw?['count'] as int? ?? 0);
    } on MissingPluginException {
      return (text: '', count: 0);
    }
  }

  Future<void> clearTapLog() => _call('clearTapLog');
  Future<void> installUpdate() => _call('installUpdate');

  Future<void> _call(String method) async {
    try {
      await _channel.invokeMethod<void>(method);
    } on MissingPluginException {
      return;
    }
  }

  void _setDictation(Object? raw) {
    if (raw is! Map) return;
    dictation = raw.map((k, v) => MapEntry('$k', v as Object?));
    notifyListeners();
  }

  void _setUpdate(Object? raw) {
    if (raw is! Map) return;
    update = raw.map((k, v) => MapEntry('$k', v as Object?));
    notifyListeners();
  }

  void _setStatus(Object? raw) {
    if (raw is! Map) return;
    final nextEnabled = raw['enabled'] == true;
    final nextSelected = raw['selected'] == true;
    if (nextEnabled == enabled && nextSelected == selected) return;
    enabled = nextEnabled;
    selected = nextSelected;
    notifyListeners();
  }
}

/// The languages autocorrect has a dictionary for (android/app/src/main/assets/dict).
const autocorrectLanguages = {
  'en': 'English',
  'es': 'Español',
  'fr': 'Français',
  'de': 'Deutsch',
  'it': 'Italiano',
  'pt': 'Português',
};
