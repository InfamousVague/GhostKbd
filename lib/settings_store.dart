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
    'theme': 'dark',
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
    'developer': false,
    'autocorrect': true,
    'languages': 'en',
    'learned_words': '',
    'suggestions': true,
    'giphy_key': '',
    'theme_tint': '#51FCFB',
    'theme_accent': '',
    'theme_mode': 'dark',
    'split_keyboard': true,
    'emoji_skin': '0',
    'emoji_suggest': true,
    'markdown_everywhere': false,
    'terminal_everywhere': false,
    'sound_pack': 'mechanical',
    'sound_volume': '35',
    'stock_sound': 'bamboo',
    'plugins': 'autocorrect',
    'theme_preset': 'ghost',
    'custom_theme': '#0A0B0C,#232528,#16181A,#51FCFB,#E8EDED',
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
  /// Every setting as it stands, for Settings › Developer's copy of the details.
  String get settingsJson => _values.entries.map((e) => '${e.key}=${e.value}').join(', ');

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
      final clips = await _channel.invokeMapMethod<String, Object>('clipboardState');
      clipCount = clips?['count'] as int? ?? 0;
      soundPacksHere = _packs(await _channel.invokeListMethod<Object>('soundPacks'));
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

  Future<void> installUpdate() => _call('installUpdate');

  /// Dictation's models: choose one (downloading it if it isn't here), fetch or delete any of them, and the
  /// language a multilingual model expects.
  Future<void> useModel(String id) => _dictationCall('useModel', {'id': id});
  Future<void> downloadModelId(String id) => _dictationCall('downloadModel', {'id': id});
  Future<void> deleteModelId(String id) => _dictationCall('deleteModel', {'id': id});
  Future<void> setDictationLanguage(String code) => _dictationCall('setDictationLanguage', {'code': code});

  /// The clipboard plugin's history: how many it holds, and clearing it.
  int clipCount = 0;
  Future<void> refreshClipboard() async {
    try {
      final r = await _channel.invokeMapMethod<String, Object>('clipboardState');
      clipCount = r?['count'] as int? ?? 0;
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  Future<void> clearClipboard() async {
    try {
      await _channel.invokeMethod<Object>('clearClipboard');
      clipCount = 0;
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  /// A few seconds of typing in the chosen pack, filters and all.
  Future<void> sampleSound([String? pack]) async {
    try {
      await _channel.invokeMethod<void>('sampleSound', {if (pack != null) 'pack': pack});
    } on MissingPluginException {
      return;
    }
  }

  /// The sound packs in this build, as (id, name), in order (assets/sounds/packs.json).
  List<(String, String)> soundPacksHere = const [];
  static List<(String, String)> _packs(List<Object?>? raw) => [
        for (final p in raw ?? const []) if (p is Map) ('${p['id']}', '${p['name']}'),
      ];
  Future<void> refreshSoundPacks() async {
    try {
      soundPacksHere = _packs(await _channel.invokeListMethod<Object>('soundPacks'));
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  Future<void> _dictationCall(String method, Map<String, Object> args) async {
    try {
      _setDictation(await _channel.invokeMethod<Object>(method, args));
    } on MissingPluginException {
      return;
    }
  }

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
