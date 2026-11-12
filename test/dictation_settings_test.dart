// Settings › Dictation (android Dictation.kt, SpeechCuts.kt, DictationWords.kt; docs/dictation.md): takes with no time
// limit, the person's own words for the speech model, and Tidy after a take, which is the one thing here that sends
// anything and says so. Run with `flutter test`.

import 'package:dev_keyboard/plugins/catalogue.dart';
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
  // No phone behind the channel: each call is answered with nothing.
  final calls = <MethodCall>[];
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async {
    calls.add(call);
    return null;
  });

  testWidgets('Dictation’s page has long takes, your words and Tidy, and says what Tidy sends', (tester) async {
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
    await store.setPlugin('dictation', true);
    SettingsSection page() => settingsSections(store).firstWhere((s) => s.id == 'dictation');
    List<PaneSection> sections() => page().page(context, store).whereType<PaneSection>().toList();
    PaneSection section(String title) => sections().firstWhere((s) => s.title == title);
    String footer(String title) => (section(title).footer as Text).data!;

    // Long takes: no limit, the words as you talk, and when a take ends by itself. No "60 seconds" anywhere.
    final long = section('Long takes').children.whereType<SettingRow>().toList();
    expect(long.map((r) => r.label), ['No time limit', 'It stops by itself']);
    expect((long[0].hint as Text).data, contains('a piece at a time, each ending at a pause'));

    // As you speak: on unless switched off, and the page says what it is, that the last words can change, and what it costs.
    expect(store.flag('dictation_live'), isTrue);
    final live = section('As you speak').children.whereType<SettingRow>().single;
    expect(live.label, 'Show words as I speak');
    expect((live.hint as Text).data, allOf(contains('while you are still talking'), contains('can change until you pause')));
    for (final phrase in ['on this phone', 'battery', 'at each pause', 'all at the end if you never pause']) {
      expect(footer('As you speak'), contains(phrase));
    }
    expect((long[1].hint as Text).data, allOf(contains('45 seconds'), contains('30 minutes')));
    for (final s in sections()) {
      for (final r in s.children.whereType<SettingRow>()) {
        expect('${r.label} ${(r.hint as Text?)?.data ?? ''}', isNot(contains('60 seconds')));
      }
    }

    // Your words: what they are for, said as a lean and not a promise, and that they stay on the phone unless Tidy is tapped.
    for (final phrase in ['up to 60', 'commas between', 'likelier, not certain', 'stay on this phone unless you tap Tidy']) {
      expect(footer('Your words'), contains(phrase));
    }
    expect(store.choice('dictation_words'), '');
    final input = section('Your words').children.whereType<SettingRowBox>().single.child as GlacierInput;
    input.onSubmitted!('  Ghost.md, Mockingbird ');
    await tester.pump();
    expect(calls.any((c) => c.method == 'setSetting' && (c.arguments as Map)['key'] == 'dictation_words' && (c.arguments as Map)['value'] == 'Ghost.md, Mockingbird'), isTrue);

    // After a take: Tidy is offered unless switched off, and the page says what it sends, to whom, and only when tapped.
    expect(store.flag('dictation_tidy'), isTrue);
    final tidy = section('After a take').children.whereType<SettingRow>().single;
    expect(tidy.label, 'Offer Tidy in the row');
    for (final phrase in ['ums', 'changes nothing else', 'sends the words that take put in', 'your words above', 'the AI you chose', 'only when you tap it']) {
      expect(footer('After a take'), contains(phrase));
    }
    // With no AI key it says the chip waits for one; with the enhancer added it names As a prompt.
    expect(footer('After a take'), contains('It shows once an AI key is set.'));
    expect(footer('After a take'), contains('With the Prompt enhancer plugin added'));
    store.ai = {...store.ai, 'ready': true};
    await store.setPlugin('enhance', true);
    expect(footer('After a take'), isNot(contains('It shows once an AI key is set.')));
    expect(footer('After a take'), contains('As a prompt, beside it, makes a clear prompt of them'));

    // The speech model's own promise stands: nothing said leaves the phone.
    expect(footer('Speech model'), contains('nothing you say leaves it'));
  });

  test('the Market’s card says takes have no limit, and that only Tidy sends anything', () {
    final plugin = pluginById('dictation')!;
    for (final phrase in ['for as long as you like', 'about a second behind your voice', 'Nothing you say is sent anywhere', 'your own words', 'that sends the words, never the sound, and only when you tap it']) {
      expect(plugin.description, contains(phrase));
    }
    expect(plugin.adds, contains('Takes of any length, written down as you talk'));
    final network = plugin.permissions.firstWhere((p) => p.kind == Permission.network);
    expect(network.why, allOf(contains('To download the model'), contains('only when you tap Tidy')));
    expect(plugin.hosts, contains('the AI provider you choose'));
  });
}
