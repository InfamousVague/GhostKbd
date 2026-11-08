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
  };

  final Map<String, Object> _values = {...defaults};

  /// Whether Dev Keyboard is turned on in the system's keyboard list.
  bool enabled = false;

  /// Whether Dev Keyboard is the keyboard in use.
  bool selected = false;

  bool loaded = false;

  bool flag(String key) => _values[key] as bool? ?? defaults[key] as bool;
  String choice(String key) => _values[key] as String? ?? defaults[key] as String;

  Future<void> load() async {
    try {
      final values = await _channel.invokeMapMethod<String, Object>('getSettings');
      if (values != null) _values.addAll(values);
      _setStatus(await _channel.invokeMethod<Object>('status'));
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
  Future<void> showPicker() => _call('showPicker');

  Future<void> _call(String method) async {
    try {
      await _channel.invokeMethod<void>(method);
    } on MissingPluginException {
      return;
    }
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
