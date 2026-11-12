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
      if (call.method == 'smsCodesChanged') _setSmsCodes(call.arguments);
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
        await refreshNextWords();
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
    'offline': false,
    'key_borders': false,
    'toolbar': false,
    'hide_row': false,
    'haptics': 'medium',
    'developer': false,
    'autocorrect': true,
    'languages': 'en',
    'language_layouts': '',
    'layout': '',
    'globe_key': true,
    'learned_words': '',
    'suggestions': true,
    'next_word': true,
    'next_word_learn': true,
    'next_word_mockingbird': true,
    'learn_mistakes': true,
    'share_reports': false,
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
    'sms_codes': false,
    'ai_provider': 'anthropic',
    'ai_base_url': '',
    'mockingbird_collect': true,
    'mockingbird_auto_train': true,
    'ghostwriter_auto': true,
    'accuracy_log': false,
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

  /// iOS only: whether the keyboard last ran with Allow Full Access on (false until it has run with it). Android's
  /// `status` has no such key, and it stays false there.
  bool fullAccess = false;

  /// iOS only: whether the person agreed to what the AI features send (lib/ios/ai_consent.dart). The keyboard refuses
  /// AI without it. Not in [defaults]: Android has no such setting.
  bool get aiConsent => _values['ai_consent'] == true;

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

  /// Settings › Languages: every language the keyboard has (Languages.kt, through `languagesState`), the ones it
  /// says it does not have yet, and how many may be on at once. Off-device the first six stand in.
  List<LanguageInfo> languageCatalogue = _firstSix;
  List<({String name, String english, String why})> unavailableLanguages = const [];
  int maxLanguages = 6;

  LanguageInfo? languageOf(String code) => languageCatalogue.where((l) => l.code == code).firstOrNull;

  /// The languages that are on, as codes in the order they were turned on ("en", "pl", "ru"): those the keyboard
  /// has, at most [maxLanguages], never none (as KeyboardPrefs.languages reads the same setting).
  List<String> get languages {
    final known = {for (final l in languageCatalogue) l.code};
    final on = <String>[];
    for (final code in choice('languages').split(',').map((c) => c.trim())) {
      if (known.contains(code) && !on.contains(code) && on.length < maxLanguages) on.add(code);
    }
    return on.isEmpty ? ['en'] : on;
  }

  /// The layout chosen for a language in place of its own ("fr:qwerty,de:qwerty").
  Map<String, String> get languageLayouts => {
        for (final part in choice('language_layouts').split(','))
          if (part.split(':').length == 2 && part.split(':').every((p) => p.trim().isNotEmpty)) part.split(':')[0].trim(): part.split(':')[1].trim(),
      };

  /// The layout [language] types on: the one chosen for it where it may use that one, else its own (its first).
  String layoutOf(LanguageInfo language) {
    final chosen = languageLayouts[language.code];
    return language.layouts.any((l) => l.$1 == chosen) ? chosen! : language.layouts.first.$1;
  }

  /// Turns a language on (after the others; a seventh is refused) or off (never the last). Turning one on also goes
  /// to its layout, so the live preview shows the keys it brings.
  Future<void> setLanguage(String code, bool on) async {
    final now = languages;
    if (on) {
      if (now.contains(code) || now.length >= maxLanguages || languageOf(code) == null) return;
      now.add(code);
    } else {
      now.remove(code);
      if (now.isEmpty) now.add('en');
    }
    await set('languages', now.join(','));
    final added = on ? languageOf(code) : null;
    if (added != null) await set('layout', layoutOf(added));
  }

  /// Chooses the layout [code] types on (one of its own `layouts`), and goes to it. Its own first layout is no choice
  /// at all, so it is kept as nothing.
  Future<void> setLanguageLayout(String code, String layout) async {
    final language = languageOf(code);
    if (language == null || !language.layouts.any((l) => l.$1 == layout)) return;
    final next = {...languageLayouts};
    layout == language.layouts.first.$1 ? next.remove(code) : next[code] = layout;
    await set('language_layouts', next.entries.map((e) => '${e.key}:${e.value}').join(','));
    await set('layout', layout);
  }

  /// What is typed in Settings › Languages › Add a language to narrow its list.
  String languageFilter = '';

  void setLanguageFilter(String text) {
    languageFilter = text;
    notifyListeners();
  }

  Future<void> refreshLanguages() async {
    try {
      final raw = await _channel.invokeMethod<Object>('languagesState');
      if (raw is! Map) return;
      final list = [
        for (final l in (raw['languages'] as List? ?? const []))
          if (l is Map)
            LanguageInfo(
              '${l['code']}',
              '${l['name']}',
              '${l['english']}',
              script: '${l['script'] ?? 'latin'}',
              layouts: [for (final y in (l['layouts'] as List? ?? const [])) if (y is Map) ('${y['id']}', '${y['label']}')],
              dictionary: l['dictionary'] != false,
              nextWords: l['nextWords'] == true,
              note: l['note'] as String?,
            ),
      ].where((l) => l.layouts.isNotEmpty).toList();
      if (list.isEmpty) return;
      languageCatalogue = list;
      unavailableLanguages = [
        for (final u in (raw['unavailable'] as List? ?? const []))
          if (u is Map) (name: '${u['name']}', english: '${u['english']}', why: '${u['why']}'),
      ];
      maxLanguages = (raw['max'] as num?)?.toInt() ?? 6;
      notifyListeners();
    } on MissingPluginException {
      return;
    }
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

  /// Settings › Offline: Ghost Keyboard opens no connection while this is on (Net.kt refuses every one).
  bool get offline => flag('offline');

  /// What a row that would connect says while Offline is on, and why it is disabled; null while it is off.
  String? get offlineReason => offline ? 'Offline is on' : null;

  /// The ledger of every connection the app can make, as Net.kt's `toMap` gives it: `features` (what the Offline
  /// switch stops), `sections` (the pages that say so), `paths` (each reason to connect: its servers, what starts it,
  /// what is sent, and whether it can happen as things stand), `works` (what needs no connection) and `hosts` (each
  /// plugin's servers). Empty off-device.
  Map<String, Object?> net = const {};

  /// One of the ledger's lists (`features`, `paths`, `works`), as maps.
  List<Map<Object?, Object?>> netList(String key) => [for (final e in (net[key] as List? ?? const [])) if (e is Map) e];

  /// The settings pages (by section id) whose connections the Offline switch stops.
  Set<String> get offlineSections => {for (final id in (net['sections'] as List? ?? const [])) '$id'};

  /// The servers plugin [id] may reach, from the ledger; null where the ledger has not arrived (off-device).
  List<String>? netHosts(String id) {
    final hosts = (net['hosts'] as Map?)?[id];
    return hosts is List ? [for (final h in hosts) '$h'] : null;
  }

  Future<void> refreshNet() async {
    try {
      final raw = await _channel.invokeMethod<Object>('netState');
      if (raw is! Map) return;
      net = raw.map((k, v) => MapEntry('$k', v as Object?));
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  Future<void> load() async {
    try {
      final values = await _channel.invokeMapMethod<String, Object>('getSettings');
      if (values != null) _values.addAll(values);
      await refreshNet();
      await refreshLanguages();
      _setStatus(await _channel.invokeMethod<Object>('status'));
      _setUpdate(await _channel.invokeMethod<Object>('updateState'));
      _setDictation(await _channel.invokeMethod<Object>('dictationState'));
      _setClipboard(await _channel.invokeMethod<Object>('clipboardState'));
      _setAi(await _channel.invokeMethod<Object>('aiState'));
      await refreshAdaptive();
      await refreshNextWords();
      await refreshStats();
      await refreshProfiles();
    } on MissingPluginException {
      // Not on Android (a test, a desktop run): the defaults stand.
    }
    loaded = true;
    notifyListeners();
    await refreshSmsCodes();
  }

  Future<void> refreshStatus() async {
    try {
      _setStatus(await _channel.invokeMethod<Object>('status'));
    } on MissingPluginException {
      return;
    }
  }

  /// Where an update stands (Updater.kt), read again: the keyboard may have found or downloaded one in the background.
  Future<void> refreshUpdate() async {
    try {
      _setUpdate(await _channel.invokeMethod<Object>('updateState'));
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
      // The Offline switch changes what every page that connects can say: read them all again.
      if (key == 'offline') {
        await refreshNet();
        await refreshAi();
        _setUpdate(await _channel.invokeMethod<Object>('updateState'));
        _setDictation(await _channel.invokeMethod<Object>('dictationState'));
      }
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

  /// The accuracy record (AccuracyLog): how many words and swipes it holds, saving it to Downloads, deleting it.
  int accuracyCount = 0;
  Future<void> refreshAccuracy() async {
    try {
      final r = await _channel.invokeMapMethod<String, Object>('accuracyState');
      accuracyCount = r?['count'] as int? ?? 0;
      notifyListeners();
    } on MissingPluginException {
      return;
    }
  }

  /// The anonymous typing report that is waiting (Reports.kt): `waiting`, the report exactly as it would be sent (or
  /// empty), the `words`, `mistakes` and `strokes` in it, whether there are `enough` words for it to go by itself,
  /// the day one was `lastSent`, and the `server` it goes to.
  Map<String, Object?> report = const {'waiting': '', 'words': 0, 'mistakes': 0, 'strokes': 0, 'enough': false, 'lastSent': '', 'server': 'ghostkbd.com'};

  /// What the last "Send it now" said.
  String reportSaid = '';

  /// Whether the report is shown on the page.
  bool reportShown = false;
  void showReport(bool on) {
    reportShown = on;
    notifyListeners();
  }

  Future<void> refreshReport() => _reportCall('reportState');

  /// Settings › Typing stats › Discard what is waiting: the report that has not gone, deleted.
  Future<void> discardReport() async {
    reportSaid = '';
    reportShown = false;
    await _reportCall('reportDiscard');
  }

  /// Settings › Typing stats › Send it now: what is waiting, sent without waiting for its day (never while Offline is on).
  Future<void> sendReport() async {
    reportSaid = 'Sending…';
    notifyListeners();
    await _reportCall('reportSend');
  }

  Future<void> _reportCall(String method) async {
    try {
      final r = await _channel.invokeMethod<Object>(method);
      if (r is! Map) return;
      final next = r.map((k, v) => MapEntry('$k', v as Object?));
      final said = next.remove('said');
      if (said is String) reportSaid = said;
      if ('$next' == '$report' && said == null) return;
      report = next;
      notifyListeners();
    } on MissingPluginException {
      return;
    } on PlatformException {
      if (method == 'reportSend') reportSaid = 'Not sent';
      notifyListeners();
    }
  }

  /// What the last Save said: the file's name in Downloads, or why it couldn't be saved.
  String accuracySaid = '';
  Future<void> exportAccuracy() async {
    try {
      final name = await _channel.invokeMethod<String>('accuracyExport');
      accuracySaid = 'Saved to Downloads as $name';
    } on PlatformException catch (e) {
      accuracySaid = e.message ?? 'Couldn’t save it';
    } on MissingPluginException {
      accuracySaid = 'Only on Android';
    }
    notifyListeners();
  }

  Future<void> clearAccuracy() async {
    try {
      await _channel.invokeMethod<Object>('accuracyClear');
    } on MissingPluginException {
      return;
    }
    accuracyCount = 0;
    notifyListeners();
  }

  /// One-time codes from texts (android SmsCodes.kt): the switch (`on`), whether Android has given the app text
  /// messages (`allowed`), whether it has been asked for them before (`asked`), and the Android version (`android`,
  /// its API level) with the one the app is built for (`target`), for the note about what Android 17 keeps back.
  Map<String, Object?> smsCodes = const {'on': false, 'allowed': false, 'asked': false, 'android': 0, 'target': 0};

  void _setSmsCodes(Object? raw) {
    if (raw is! Map) return;
    smsCodes = raw.map((k, v) => MapEntry('$k', v as Object?));
    _values['sms_codes'] = smsCodes['on'] == true;
    notifyListeners();
  }

  /// Read again each time the app comes back to the front: the answer may have been given on Android's own page.
  Future<void> refreshSmsCodes() async {
    try {
      _setSmsCodes(await _channel.invokeMethod<Object>('smsCodesState'));
    } on MissingPluginException {
      return;
    }
  }

  /// The switch. On, Android asks for text messages where it has not said yes; its answer comes as `smsCodesChanged`.
  Future<void> setSmsCodes(bool on) async {
    _values['sms_codes'] = on;
    smsCodes = {...smsCodes, 'on': on};
    notifyListeners();
    try {
      _setSmsCodes(await _channel.invokeMethod<Object>('setSmsCodes', {'on': on}));
    } on MissingPluginException {
      return;
    }
  }

  Future<void> askForTexts() => _call('askForTexts');
  Future<void> openAppInfo() => _call('openAppInfo');

  /// The clipboard plugin's history: how many clips it holds, the pinned ones among them (Android's ClipHistory.kt),
  /// and clearing it. Clearing takes the copies and leaves what is pinned.
  int clipCount = 0;
  List<String> clipPins = const [];

  /// Why a pin written in Settings was not kept (nothing written, too long, too many pinned), until the next change.
  String? clipMessage;

  void _setClipboard(Object? raw, {bool keepMessage = false}) {
    if (raw is! Map) return;
    clipCount = raw['count'] as int? ?? 0;
    clipPins = [for (final p in (raw['pins'] as List? ?? const [])) '$p'];
    if (!keepMessage) clipMessage = raw['error'] as String?;
    notifyListeners();
  }

  Future<void> refreshClipboard() => _clipCall('clipboardState');

  Future<void> clearClipboard() async {
    try {
      final r = await _channel.invokeMethod<Object>('clearClipboard');
      // Android answers with what is left (the pinned clips). The iPhone's keyboard has no pins and answers nothing.
      if (r is Map) {
        _setClipboard(r);
      } else {
        clipCount = clipPins.length;
        notifyListeners();
      }
    } on MissingPluginException {
      return;
    }
  }

  /// Removes a pinned clip for good.
  Future<void> removeClip(String text) => _clipCall('clipRemove', {'text': text});

  /// Pins a clip written by hand in Settings.
  Future<void> addClipPin(String text) => _clipCall('clipPinAdd', {'text': text});

  Future<void> _clipCall(String method, [Map<String, Object>? args]) async {
    try {
      _setClipboard(await _channel.invokeMethod<Object>(method, args));
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

  /// An export carries the word pairs next-word prediction has counted, unless this is turned off for it.
  bool profilePairs = true;

  void setProfileSamples(bool on) {
    profileSamples = on;
    notifyListeners();
  }

  void setProfilePairs(bool on) {
    profilePairs = on;
    notifyListeners();
  }

  Future<void> importProfile() => _call('profileImport');

  Future<void> exportProfile() async {
    try {
      await _channel.invokeMethod<void>('profileExport', {'samples': profileSamples, 'pairs': profilePairs});
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

  /// Next-word prediction (NextWords.kt): `pairs` of words counted from the person's typing, the `words` in them, and
  /// `bundled`, the languages (word-list codes) that have a starter table inside the app.
  Map<String, Object?> nextWords = const {'pairs': 0, 'words': 0, 'bundled': []};

  /// The languages with a starter table, as codes; empty until the keyboard has said (and off-device).
  Set<String> get nextWordTables => {for (final c in (nextWords['bundled'] as List? ?? const [])) '$c'};

  Future<void> refreshNextWords() async {
    await _nextWordsCall('nextWordsState');
    await _mistakesCall('mistakesState');
  }

  /// What the keyboard has learned from the person's own corrections (Mistakes.kt): `typed` words put right and
  /// `swiped` readings chosen, how many of the typed ones are `settled` (mended that way from now on), and
  /// `repairs`, the slips Mockingbird mended in its samples that are gone by too.
  Map<String, Object?> mistakes = const {'typed': 0, 'swiped': 0, 'settled': 0, 'repairs': 0};

  /// Settings › Autocorrect › Forget my corrections: every remembered mistake and swipe choice, in memory and in its file.
  Future<void> resetMistakes() => _mistakesCall('mistakesReset');

  Future<void> _mistakesCall(String method) async {
    try {
      final r = await _channel.invokeMethod<Object>(method);
      if (r is Map) mistakes = r.map((k, v) => MapEntry('$k', v as Object?));
      notifyListeners();
    } on MissingPluginException {
      return;
    } on PlatformException {
      return;
    }
  }

  /// Settings › Autocorrect › Forget what it learned: every counted word pair, in memory and in its file.
  Future<void> resetNextWords() => _nextWordsCall('nextWordsReset');

  Future<void> _nextWordsCall(String method) async {
    try {
      final r = await _channel.invokeMethod<Object>(method);
      if (r is Map) nextWords = r.map((k, v) => MapEntry('$k', v as Object?));
      notifyListeners();
    } on MissingPluginException {
      return;
    } on PlatformException {
      return;
    }
  }

  /// Typing stats (TypingStats.kt): `today` (chars, words, wpm, accuracy, slips, corrections, swipes, minutes),
  /// `week` (seven of day, words, wpm, oldest first), `all` (words, wpm, accuracy, days, minutes) and `topKeys`.
  Map<String, Object?> stats = const {};

  Future<void> refreshStats() async {
    await _statsCall('statsState');
    await refreshAccuracy();
    await refreshReport();
  }
  Future<void> resetStats() async {
    await _statsCall('statsReset');
    await refreshStatsPage();
  }

  /// The Stats page (lib/stats/stats_page.dart; MainActivity's `statsPage`): everything [stats] has, and `streak`,
  /// `fastest` and `fullest` (a day and its number), `letters` (key, count, share) of `pressed`, the keyboard's
  /// pictures from everyday typing (`board`, as the trainer's), whether the adaptive keys plugin is on (`adaptive`),
  /// the `layout` they were counted on, and `misspelled` (typed, meant, count: the words put right most often).
  Map<String, Object?> statsPage = const {};

  Future<void> refreshStatsPage() async {
    try {
      final r = await _channel.invokeMethod<Object>('statsPage');
      if (r is! Map) return;
      final next = r.map((k, v) => MapEntry('$k', v as Object?));
      // The page asks every couple of seconds while it is open: only a change redraws it.
      if ('$next' == '$statsPage') return;
      statsPage = next;
      notifyListeners();
    } on MissingPluginException {
      return;
    } on PlatformException {
      return;
    }
  }

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
    final nextFullAccess = raw['fullAccess'] == true;
    if (nextEnabled == enabled && nextSelected == selected && nextFullAccess == fullAccess) return;
    enabled = nextEnabled;
    selected = nextSelected;
    fullAccess = nextFullAccess;
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

/// A language the keyboard has (Languages.kt): its [code], its [name] as its speakers write it and in [english], its
/// [script], the [layouts] it may type on as (id, label) with its own first, whether it has a word list
/// ([dictionary]: without one nothing is corrected, suggested or swiped), whether next-word prediction has a starter
/// table for it inside the app ([nextWords]: without one it suggests from the person's own typing only), and a [note]
/// shown under it.
@immutable
class LanguageInfo {
  const LanguageInfo(this.code, this.name, this.english, {this.script = 'latin', this.layouts = const [('qwerty', 'QWERTY')], this.dictionary = true, this.nextWords = false, this.note});
  final String code;
  final String name;
  final String english;
  final String script;
  final List<(String, String)> layouts;
  final bool dictionary;
  final bool nextWords;
  final String? note;
}

/// The languages the keyboard began with: what the store knows before the keyboard's own list arrives, and off-device.
const _firstSix = [
  LanguageInfo('en', 'English', 'English', nextWords: true),
  LanguageInfo('es', 'Español', 'Spanish', nextWords: true),
  LanguageInfo('fr', 'Français', 'French', nextWords: true),
  LanguageInfo('de', 'Deutsch', 'German', nextWords: true),
  LanguageInfo('it', 'Italiano', 'Italian', nextWords: true),
  LanguageInfo('pt', 'Português', 'Portuguese', nextWords: true),
];
