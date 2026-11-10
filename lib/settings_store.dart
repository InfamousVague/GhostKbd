import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// The keyboard's settings and setup state, through MainActivity's `dev_keyboard/settings` channel.
///
/// The settings live in the Kotlin side's SharedPreferences (KeyboardPrefs.kt), which the keyboard service reads
/// live, so a change here shows on the keyboard the next time it draws. The keys and their defaults are Kotlin's;
/// [defaults] only covers the first frame before they arrive (and runs off-device, where there is no channel).
class SettingsStore extends ChangeNotifier {
  SettingsStore() {
    current = this;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'statusChanged') _setStatus(call.arguments);
      if (call.method == 'updateChanged') _setUpdate(call.arguments);
      if (call.method == 'dictationChanged') _setDictation(call.arguments);
      if (call.method == 'profileDone' && call.arguments is Map) {
        final r = call.arguments as Map;
        profileError = r['error'] as String?;
        if (r['exported'] == true) {
          profileLines = const ['Exported.'];
          profileReport = null;
        } else if (profileError == null) {
          profileLines = [for (final l in (r['lines'] as List? ?? const [])) '$l'];
          profileReport = r['report'] as String?;
          profileSource = r['source'] as String?;
        }
        notifyListeners();
        await refreshAdaptive();
        await refreshAi();
      }
      if (call.method == 'aiChanged') {
        ghostMessage = call.arguments as String?;
        await refreshAi();
      }
      if (call.method == 'open' && call.arguments is String) openRequests.value = call.arguments as String;
    });
  }

  static const _channel = MethodChannel('dev_keyboard/settings');

  /// The app's one store, for the parts every page shares that are built without it (the live preview at a page's foot).
  static SettingsStore? current;

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
    'hide_row': false,
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
    'backlight': true,
    'backlight_effect': 'reactive',
    'backlight_colour': 'accent',
    'backlight_strength': 'medium',
    'backlight_mode': 'brighten',
    'backlight_fade': 'medium',
    'emoji_skin': '0',
    'emoji_suggest': true,
    'markdown_everywhere': false,
    'terminal_everywhere': false,
    'sound_volume': '35',
    'stock_sound': 'bamboo',
    'plugins': 'autocorrect,swipe,clipboard,undo,emoji',
    'theme_preset': 'ghost',
    'custom_theme': '#0A0B0C,#232528,#16181A,#51FCFB,#E8EDED',
    'onboarded': false,
    'ai_provider': 'anthropic',
    'ai_base_url': '',
    'mockingbird_collect': true,
    'mockingbird_auto_train': true,
    'ghostwriter_auto': true,
    'sherlock_auto': true,
    'sherlock_fill': false,
    'ghostwriter_memory': true,
    'ghostwriter_clipboard': false,
    'ghostwriter_web': true,
    'ghostwriter_github': true,
    'github_token': '',
    'ghostwriter_typing': true,
    'ghostwriter_notes': true,
    'adaptive_map': false,
    'arrow_keys': false,
    'slang': true,
    'wide_layout': 'split',
    'one_handed': 'off',
    'delete_swipe': true,
    'bottom_space': 'auto',
    'typing_stats': true,
    'rewrite_language': 'Spanish',
    'preview_open': false,
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
  /// API keys are never in it: it is made to be copied and pasted.
  String get settingsJson => _values.entries
      .map((e) => e.key.startsWith('ai_key_') || e.key == 'github_token' ? '${e.key}=${'${e.value}'.isEmpty ? '' : '(set)'}' : '${e.key}=${e.value}')
      .join(', ');

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
      _setAi(await _channel.invokeMethod<Object>('aiState'));
      await refreshSherlock();
      await refreshAdaptive();
      await refreshStats();
      await refreshProfiles();
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

  /// Sherlock's screen reader: whether it is turned on in Android's accessibility settings, and taking you there.
  bool sherlockEnabled = false;
  bool sherlockOcr = true;
  Future<void> refreshSherlock() async {
    try {
      final r = await _channel.invokeMapMethod<String, Object>('sherlockState');
      sherlockEnabled = r?['enabled'] as bool? ?? false;
      sherlockOcr = r?['ocr'] as bool? ?? true;
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  Future<void> openSherlockSettings() => _call('sherlockOpen');

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

  /// A text setting with no default of its own (each AI provider's key and model): empty until set.
  String text(String key) => _values[key] as String? ?? '';

  /// Settings › AI and Mockingbird (AiClient.kt, Mockingbird.kt): `providers` (id, name, defaultModel, keyFrom),
  /// `ready`, `samples`, `chatSamples`, `writingSamples`, `profile` (text, at, samples, provider, model, and
  /// `outdated`: trained before slips were told from habits, so due a retrain) or null, `training` and `error`.
  Map<String, Object?> ai = const {'providers': [], 'ready': false, 'samples': 0};

  /// The models the chosen provider offers, once fetched; and the last thing an AI action said.
  List<String> aiModels = const [];
  String? aiMessage;
  bool aiBusy = false;

  Future<void> refreshAi() async {
    try {
      _setAi(await _channel.invokeMethod<Object>('aiState'));
    } on MissingPluginException {
      return;
    }
  }

  Future<void> fetchAiModels() => _aiAction(() async {
        final list = await _channel.invokeListMethod<String>('aiModels');
        aiModels = list ?? const [];
        aiMessage = aiModels.isEmpty ? 'No models came back.' : null;
      });

  Future<void> testAi() => _aiAction(() async {
        final reply = await _channel.invokeMethod<String>('aiTest');
        aiMessage = 'It answered: ${(reply ?? '').trim()}';
      });

  Future<void> trainMockingbird() => _aiAction(() async {
        final r = await _channel.invokeMethod<Object>('mockingbirdTrain');
        _setAi(r);
        final error = r is Map ? r['trainError'] as String? : null;
        aiMessage = error ?? 'Trained. Your style profile is below.';
      });

  /// The pages' More folds that are open (panes.dart `_more`), for this visit to Settings.
  final Set<String> moreOpen = {};

  /// Every More fold a page has drawn, and whether the search is reading the pages (every fold read open).
  final Set<String> moreSeen = {};
  bool indexing = false;

  /// Opens every fold, so a setting the search found behind one is there to be scrolled to.
  void openAllMore() => moreOpen.addAll(moreSeen);

  void toggleMore(String id) {
    if (!moreOpen.remove(id)) moreOpen.add(id);
    notifyListeners();
  }

  /// Typing profiles (TypingProfile.kt, docs/typing-profile.md): what the last import or export said.
  List<String> profileLines = const [];
  String? profileReport;
  String? profileSource;
  String? profileError;
  bool profileSamples = false;

  void setProfileSamples(bool on) {
    profileSamples = on;
    notifyListeners();
  }

  Future<void> importProfile() => _call('profileImport');

  Future<void> exportProfile() async {
    try {
      await _channel.invokeMethod<void>('profileExport', {'samples': profileSamples});
    } on MissingPluginException {
      return;
    }
  }

  /// Adaptive keys (AdaptiveKeys.kt): `keys` with learned aims, `mistakes` remembered, `common` (the top pairs).
  Map<String, Object?> adaptive = const {'keys': 0, 'mistakes': 0, 'common': []};

  Future<void> refreshAdaptive() async {
    try {
      final r = await _channel.invokeMethod<Object>('adaptiveState');
      if (r is Map) adaptive = r.map((k, v) => MapEntry('$k', v as Object?));
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  Future<void> resetAdaptive() async {
    try {
      final r = await _channel.invokeMethod<Object>('adaptiveReset');
      if (r is Map) adaptive = r.map((k, v) => MapEntry('$k', v as Object?));
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  /// Typing stats (TypingStats.kt): `today` (chars, words, wpm, accuracy, slips, corrections, swipes, minutes),
  /// `week` (seven of day, words, wpm, oldest first), `all` (words, wpm, accuracy, days, minutes) and `topKeys`.
  Map<String, Object?> stats = const {};

  Future<void> refreshStats() => _statsCall('statsState');
  Future<void> resetStats() => _statsCall('statsReset');

  Future<void> _statsCall(String method) async {
    try {
      final r = await _channel.invokeMethod<Object>(method);
      if (r is! Map) return;
      final next = r.map((k, v) => MapEntry('$k', v as Object?));
      // The page asks every couple of seconds while it is open: only a change redraws it.
      if ('$next' == '$stats') return;
      stats = next;
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  /// Per-app profiles (AppProfiles.kt): `profiles` (package, label, values) and `recent` (package, label), the apps
  /// typed in lately that have none.
  Map<String, Object?> profiles = const {'profiles': [], 'recent': []};

  List<AppProfile> get appProfiles => _apps(profiles['profiles']);
  List<AppProfile> get recentApps => _apps(profiles['recent']);

  static List<AppProfile> _apps(Object? raw) => [
        for (final p in (raw as List? ?? const []))
          if (p is Map)
            AppProfile('${p['package']}', '${p['label']}', {for (final e in ((p['values'] as Map?) ?? const {}).entries) '${e.key}': e.value == true}),
      ];

  Future<void> refreshProfiles() => _profileCall('profilesState', const {});
  Future<void> addProfile(String package) => _profileCall('profileAdd', {'package': package});
  Future<void> removeProfile(String package) => _profileCall('profileRemove', {'package': package});

  /// Sets one of a profile's settings on or off; null gives it back to Settings.
  Future<void> setProfile(String package, String key, bool? value) => _profileCall('profileSet', {'package': package, 'key': key, if (value != null) 'value': value});

  Future<void> _profileCall(String method, Map<String, Object> args) async {
    try {
      final r = await _channel.invokeMethod<Object>(method, args);
      if (r is! Map) return;
      profiles = r.map((k, v) => MapEntry('$k', v as Object?));
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  /// Ghostwriter's Ghost.md notes online: signing in opens the browser on Ghost.md's page; the answer comes back
  /// through `aiChanged`.
  String? ghostMessage;

  Future<void> ghostSignIn() => _aiAction(() async {
        await _channel.invokeMethod<Object>('ghostSignIn');
        ghostMessage = 'Finish signing in in your browser.';
      }, onError: (m) => ghostMessage = m);

  Future<void> ghostSignOut() => _aiAction(() async {
        _setAi(await _channel.invokeMethod<Object>('ghostSignOut'));
        ghostMessage = 'Signed out of Ghost.md.';
      }, onError: (m) => ghostMessage = m);

  /// Ghostwriter's GitHub: whose token it is and the owners searched, once checked.
  String? githubMessage;

  Future<void> checkGithub() => _aiAction(() async {
        final r = await _channel.invokeMethod<Object>('githubCheck');
        if (r is Map) {
          final owners = (r['owners'] as List? ?? const []).join(', ');
          githubMessage = 'Signed in as ${r['login']}. Searching commits in $owners.';
        }
      }, onError: (m) => githubMessage = m);

  Future<void> clearMockingbird() => _aiAction(() async {
        _setAi(await _channel.invokeMethod<Object>('mockingbirdClear'));
        aiMessage = 'Samples and profile deleted.';
      });

  Future<void> _aiAction(Future<void> Function() action, {void Function(String message)? onError}) async {
    aiBusy = true;
    aiMessage = null;
    notifyListeners();
    try {
      await action();
    } on PlatformException catch (e) {
      final message = e.message ?? 'Something went wrong';
      if (onError != null) {
        onError(message);
      } else {
        aiMessage = message;
      }
    } on MissingPluginException {
      aiMessage = null;
    }
    aiBusy = false;
    await refreshAi();
    notifyListeners();
  }

  void _setAi(Object? raw) {
    if (raw is! Map) return;
    ai = raw.map((k, v) => MapEntry('$k', v as Object?));
    notifyListeners();
  }

  /// A few seconds of typing in the chosen pack, filters and all.
  Future<void> sampleSound([String? pack]) async {
    try {
      await _channel.invokeMethod<void>('sampleSound', {if (pack != null) 'pack': pack});
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

/// An app with a profile, or one typed in lately: its package, its name, and what its profile sets (on or off; a
/// setting it leaves alone is absent).
@immutable
class AppProfile {
  const AppProfile(this.package, this.label, this.values);
  final String package;
  final String label;
  final Map<String, bool> values;
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
