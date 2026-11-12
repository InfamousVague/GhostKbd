// Settings › AI › Rewrite › On this phone (android LocalAi.kt, LocalModels.kt, LocalRewrite.kt; docs/local-ai.md):
// Rewrite and its Fix on a language model that runs on the phone. The section is there only in a build that can run
// one, says what that means, and each model is a tap from downloaded, stopped, chosen or deleted. Run with
// `flutter test`.

import 'package:dev_keyboard/settings/kit/settings_kit.dart';
import 'package:dev_keyboard/settings/panes.dart';
import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/settings/settings_screen.dart';
import 'package:dev_keyboard/settings_store.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // The phone behind the channel: it answers the on-phone model's calls with [phone], and everything else with nothing.
  final calls = <MethodCall>[];
  Map<String, Object?> phone = {};
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async {
    calls.add(call);
    return const {'localAiState', 'useLocalModel', 'cancelLocalModel', 'deleteLocalModel'}.contains(call.method) ? phone : null;
  });

  Map<String, Object?> model(String id, String name, int bytes, {bool installed = false, bool downloading = false, double? progress, String? error}) =>
      {'id': id, 'name': name, 'bytes': bytes, 'note': 'A note.', 'installed': installed, 'downloading': downloading, 'progress': progress, 'error': error};

  testWidgets('Rewrite’s page offers the phone’s own model only where the build can run one', (tester) async {
    final sheet = await tester.runAsync(() => loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css']));
    installSettingsStyles(sheet!);
    final store = SettingsStore();
    late BuildContext context;
    await tester.pumpWidget(Directionality(
      textDirection: TextDirection.ltr,
      child: GlacierTheme(sheet: sheet, attrs: const {'platform': 'mobile', 'theme': 'dark'}, child: Builder(builder: (c) {
        context = c;
        return const SizedBox();
      })),
    ));
    await store.setPlugin('rewrite', true);
    SettingsSection page() => settingsSections(store).firstWhere((s) => s.id == 'rewrite');
    List<PaneSection> sections() => page().page(context, store).whereType<PaneSection>().toList();
    PaneSection? local() => sections().where((s) => s.title == 'On this phone').firstOrNull;
    List<SettingRow> rows() => local()!.children.whereType<SettingRow>().toList();
    String value(SettingRow row) => (row.value as Text).data!;

    // A build with no model runner in it says nothing of one.
    phone = {'built': false, 'on': true, 'serves': false, 'active': null, 'models': <Object?>[]};
    await store.refreshLocalAi();
    expect(local(), isNull);

    // With one: the switch, on unless turned off, and both models, neither here yet.
    phone = {
      'built': true, 'on': true, 'serves': false, 'active': null,
      'models': [model('qwen3.5-4b', 'Qwen3.5 4B', 2740937888), model('qwen3.5-2b', 'Qwen3.5 2B', 1280835840)],
    };
    await store.refreshLocalAi();
    expect(local(), isNotNull);
    expect(store.flag('rewrite_local'), isTrue);
    expect(rows().map((r) => r.label), ['Rewrite on this phone', 'Qwen3.5 4B', 'Qwen3.5 2B']);
    expect(rows().skip(1).map(value), ['Get', 'Get']);
    expect((rows()[1].hint as Text).data, contains('2.7 GB'));
    expect((rows()[2].hint as Text).data, contains('1.3 GB'));
    final footer = (local()!.footer as Text).data!;
    for (final phrase in ['no key, no connection', 'never leaves it', 'with Offline on', 'slower than a server', 'not put in the field']) {
      expect(footer, contains(phrase));
    }
    // Until a model is here Rewrite is still the AI's, and the page says what it is waiting for.
    expect(page().summary!(store), 'Add an AI key');

    // A tap on a model that is not here asks the phone to fetch it.
    calls.clear();
    rows()[2].onPress!();
    await tester.pump();
    expect(calls.single.method, 'useLocalModel');
    expect(calls.single.arguments, {'id': 'qwen3.5-2b'});

    // While it comes: how far, and a tap stops it.
    phone = {
      'built': true, 'on': true, 'serves': false, 'active': null,
      'models': [model('qwen3.5-4b', 'Qwen3.5 4B', 2740937888), model('qwen3.5-2b', 'Qwen3.5 2B', 1280835840, downloading: true, progress: 0.42)],
    };
    await store.refreshLocalAi();
    expect(value(rows()[2]), '42% · Stop');
    calls.clear();
    rows()[2].onPress!();
    await tester.pump();
    expect(calls.single.method, 'cancelLocalModel');

    // Here: it is the one in use, Rewrite runs on the phone, and it can be deleted.
    phone = {
      'built': true, 'on': true, 'serves': true, 'active': 'qwen3.5-2b',
      'models': [model('qwen3.5-4b', 'Qwen3.5 4B', 2740937888, error: 'Not enough room on this phone'), model('qwen3.5-2b', 'Qwen3.5 2B', 1280835840, installed: true)],
    };
    await store.refreshLocalAi();
    expect(rows().map((r) => r.label), ['Rewrite on this phone', 'Qwen3.5 4B', 'Qwen3.5 2B', 'Delete Qwen3.5 2B']);
    expect(value(rows()[2]), 'In use');
    expect(rows()[2].onPress, isNull);
    expect((rows()[1].hint as Text).data, contains('Not enough room on this phone'));
    expect(page().summary!(store), startsWith('On this phone: fix, shorten'));
    calls.clear();
    rows()[3].onPress!();
    await tester.pump();
    expect(calls.single.method, 'deleteLocalModel');
    expect(calls.single.arguments, {'id': 'qwen3.5-2b'});
  });
}
