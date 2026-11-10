// The app as an iPhone has it (lib/platform.dart `isIOS`), over a mocked `dev_keyboard/settings` channel that answers
// as ios/Runner's does: the walkthrough's iOS pages, the Market's AI consent, and what Settings hides there.
import 'package:dev_keyboard/ios/ai_consent.dart';
import 'package:dev_keyboard/main.dart';
import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/settings_store.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

const _channel = MethodChannel('dev_keyboard/settings');

/// The native side: its settings, its status, and every call it was sent.
class _Native {
  final Map<String, Object> settings = {'onboarded': false, 'plugins': 'autocorrect,swipe,clipboard,undo', 'ai_consent': false};
  Map<String, Object> status = {'enabled': false, 'selected': false, 'fullAccess': false};
  final List<MethodCall> calls = [];

  Future<Object?> answer(MethodCall call) async {
    calls.add(call);
    final args = call.arguments is Map ? call.arguments as Map : const {};
    switch (call.method) {
      case 'getSettings':
        return settings;
      case 'setSetting':
        settings['${args['key']}'] = args['value'] as Object;
        return null;
      case 'setPlugin':
        final on = '${settings['plugins']}'.split(',').toSet();
        args['on'] == true ? on.add('${args['id']}') : on.remove('${args['id']}');
        settings['plugins'] = on.join(',');
        return settings;
      case 'status':
        return status;
      case 'updateState':
        return {'phase': 'disabled', 'current': '0.9.0'};
      case 'dictationState':
        return {'phase': 'unsupported', 'microphone': false};
      case 'clipboardState':
        return {'count': 0};
      case 'aiState':
        return {'providers': [], 'ready': false, 'samples': 0};
      case 'adaptiveState':
        return {'keys': 0, 'mistakes': 0, 'common': []};
      case 'statsState':
        return {};
      case 'profilesState':
        return {'profiles': [], 'recent': []};
      case 'playAsset':
        return false;
      case 'openAt':
      case 'stopAudio':
      case 'openInputSettings':
        return null;
    }
    throw MissingPluginException(call.method);
  }

  Iterable<MethodCall> sent(String method) => calls.where((c) => c.method == method);
}

Future<(_Native, SettingsStore)> _start(WidgetTester tester, {bool onboarded = false, TargetPlatform platform = TargetPlatform.iOS}) async {
  debugDefaultTargetPlatformOverride = platform;
  // A phone's width, and taller than any: the test font is wide, so a page's words run long, and what a list has not
  // built cannot be found.
  tester.view.physicalSize = const Size(1170, 9000);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  final native = _Native()..settings['onboarded'] = onboarded;
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, native.answer);
  addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, null));
  final sheet = (await tester.runAsync(() => loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css'])))!;
  installSettingsStyles(sheet);
  final store = SettingsStore();
  await tester.runAsync(store.load);
  await tester.pumpWidget(GhostKeyboardApp(sheet: sheet, store: store));
  await tester.pump(const Duration(milliseconds: 400));
  return (native, store);
}

/// Takes the app down (its timers and drifting bands with it) and gives the platform back.
Future<void> _stop(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump(const Duration(seconds: 1));
  debugDefaultTargetPlatformOverride = null;
}

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 8; i++) {
    await tester.pump(const Duration(milliseconds: 120));
  }
}

Future<void> _press(WidgetTester tester, Finder what) async {
  await tester.ensureVisible(what.first);
  await tester.pump();
  await tester.tap(what.first, warnIfMissed: false);
  await _settle(tester);
}

void main() {
  testWidgets('the walkthrough teaches adding the keyboard, Full Access and the globe key', (tester) async {
    final (native, store) = await _start(tester);
    expect(find.text('Hi, I’m Ghost Keyboard'), findsOneWidget);
    expect(store.fullAccess, isFalse);

    await _press(tester, find.text('Next'));
    expect(find.text('Add the keyboard'), findsOneWidget);
    expect(find.text('Talk instead'), findsNothing);
    await _press(tester, find.text('Open Settings'));
    expect(native.sent('openInputSettings'), hasLength(1));

    // Added in Settings: the page says so the next time the status is asked for (every two seconds on iOS).
    native.status = {'enabled': true, 'selected': false, 'fullAccess': false};
    await tester.runAsync(store.refreshStatus);
    await tester.pump();
    expect(find.text('Ghost Keyboard is added.'), findsOneWidget);

    await _press(tester, find.text('Next'));
    expect(find.text('Full Access, your call'), findsOneWidget);
    expect(find.textContaining('Nothing you type is sent anywhere unless you use an AI feature.'), findsOneWidget);
    native.status = {'enabled': true, 'selected': true, 'fullAccess': true};
    await tester.runAsync(store.refreshStatus);
    await tester.pump();
    expect(store.fullAccess, isTrue);
    expect(find.text('Full Access is on.'), findsOneWidget);

    await _press(tester, find.text('Next'));
    expect(find.text('Switch to it'), findsOneWidget);
    expect(find.text('You’ve switched to Ghost Keyboard.'), findsOneWidget);
    expect(find.text('iOS shows its own keyboard for passwords, and in some apps.'), findsOneWidget);

    // Your call: what starts switched on, each a switch, and nothing an iPhone's keyboard has not.
    await _press(tester, find.text('Next'));
    expect(find.text('Code, a tap away'), findsOneWidget);
    await _press(tester, find.text('Next'));
    expect(find.text('Your call'), findsOneWidget);
    for (final label in ['Autocorrect', 'Swipe typing', 'Adaptive keys', 'Cursor slides', 'Clipboard history']) {
      expect(find.text(label), findsOneWidget);
    }
    expect(find.text('Typing stats'), findsNothing);
    expect(find.textContaining('typing stats'), findsNothing);
    expect(find.byType(GlacierSwitch), findsNWidgets(5));
    await _press(tester, find.byType(GlacierSwitch).at(1));
    expect(native.sent('setPlugin').single.arguments, {'id': 'swipe', 'on': false});
    expect(store.pluginOn('swipe'), isFalse);
    await _press(tester, find.text('Next'));
    expect(find.text('Plugins'), findsOneWidget);
    await _stop(tester);
  });

  testWidgets('an AI plugin asks for consent before it is added', (tester) async {
    final (native, store) = await _start(tester, onboarded: true);
    // Settings' home, without what an iPhone has not.
    expect(find.text('Setup'), findsOneWidget);
    expect(find.text('Per-app profiles'), findsNothing);

    await _press(tester, find.text('Plugins'));
    expect(find.text('Not on iPhone'), findsWidgets);

    // Not now leaves it off.
    await _press(tester, find.byKey(const ValueKey('market-add-ghostwriter')));
    expect(find.text(aiConsentTitle), findsOneWidget);
    expect(find.textContaining('Ghost Keyboard’s maker receives nothing'), findsOneWidget);
    await _press(tester, find.text(aiConsentDecline));
    expect(find.text(aiConsentTitle), findsNothing);
    expect(store.pluginOn('ghostwriter'), isFalse);
    expect(native.sent('setPlugin'), isEmpty);

    // I agree keeps the consent and adds it.
    await _press(tester, find.byKey(const ValueKey('market-add-ghostwriter')));
    await _press(tester, find.text(aiConsentAgree));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pump();
    expect(store.aiConsent, isTrue);
    expect(native.settings['ai_consent'], isTrue);
    expect(store.pluginOn('ghostwriter'), isTrue);
    expect(native.sent('setPlugin').single.arguments, {'id': 'ghostwriter', 'on': true});
    await _stop(tester);
  });

  testWidgets('Settings on iOS: the setup steps, the consent row, and nothing the iPhone has not', (tester) async {
    final (native, store) = await _start(tester, onboarded: true);
    Future<void> back() async {
      tester.state<NavigatorState>(find.byType(Navigator)).pop();
      await _settle(tester);
    }

    await _press(tester, find.text('Setup'));
    expect(find.text('1. Add Ghost Keyboard'), findsOneWidget);
    expect(find.text('2. Allow Full Access'), findsOneWidget);
    expect(find.text('3. Switch to Ghost Keyboard'), findsOneWidget);
    await back();

    await _press(tester, find.text('Row tools'));
    expect(find.text('Markdown'), findsOneWidget);
    expect(find.text('GIFs'), findsNothing);
    expect(find.text('Emoji'), findsNothing);
    expect(find.text('Allow Full Access'), findsOneWidget);
    await back();

    await _press(tester, find.text('About'));
    expect(find.text('Updates come from the App Store.'), findsOneWidget);
    expect(find.text('Check for updates'), findsNothing);
    expect(find.text('Update by itself'), findsNothing);
    await back();

    await _press(tester, find.text('Typing'));
    expect(find.text('Typing stats'), findsNothing);
    await back();

    await _press(tester, find.text('Look & feel'));
    await _press(tester, find.text('Key press & sound'));
    expect(find.text('Vibrate'), findsOneWidget);
    expect(find.text('Volume'), findsNothing);
    await back();
    await back();

    // The consent as it stands, on Settings › AI › Your AI; Test it asks for it; agreed, it can be withdrawn.
    await _press(tester, find.text('AI'));
    expect(find.text('Typing profile'), findsOneWidget); // import and export, through the Files picker and save sheet
    await _press(tester, find.text('Your AI'));
    expect(find.text('Not agreed yet'), findsOneWidget);
    await _press(tester, find.text('Test it'));
    expect(find.text(aiConsentTitle), findsOneWidget);
    expect(native.sent('aiTest'), isEmpty);
    await _press(tester, find.text(aiConsentAgree));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await _settle(tester);
    expect(store.aiConsent, isTrue);
    expect(find.text('You agreed'), findsOneWidget);
    await _press(tester, find.text('Withdraw my agreement'));
    expect(store.aiConsent, isFalse);
    expect(native.settings['ai_consent'], isFalse);
    await _stop(tester);
  });

  testWidgets('Android is as it was: every page, and no consent', (tester) async {
    final (native, store) = await _start(tester, onboarded: true, platform: TargetPlatform.android);
    expect(find.text('Per-app profiles'), findsOneWidget);
    await _press(tester, find.text('Plugins'));
    expect(find.text('Not on iPhone'), findsNothing);
    await _press(tester, find.byKey(const ValueKey('market-add-ghostwriter')));
    expect(find.text(aiConsentTitle), findsNothing);
    expect(store.pluginOn('ghostwriter'), isTrue);
    expect(native.sent('setSetting'), isEmpty);
    expect(store.fullAccess, isFalse);
    await _stop(tester);
  });
}
