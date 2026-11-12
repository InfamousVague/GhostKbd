// Settings › Prompt enhancer (android Enhance.kt, docs/prompt-enhancer.md): a Market plugin with a page of its own
// once added: how much a rewrite may add, a few words about the person that go with each prompt, and what it does
// and never does. Run with `flutter test`.

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

  testWidgets('the Prompt enhancer has a page of its own once added, and says what goes with a prompt', (tester) async {
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
    SettingsSection? section() => settingsSections(store).where((s) => s.id == 'enhance').firstOrNull;
    List<Widget> page() => section()!.page(context, store);
    List<SettingRow> rows() => page().whereType<PaneSection>().expand((s) => s.children.whereType<SettingRow>()).toList();

    // Not added: no page. Added: a page among the plugins', which shares the AI's own page as Rewrite's does.
    expect(section(), isNull);
    await store.setPlugin('enhance', true);
    expect(calls.any((c) => c.method == 'setPlugin' && (c.arguments as Map)['id'] == 'enhance'), isTrue);
    expect(section()!.label, 'Prompt enhancer');
    expect(section()!.group, 'plugins');
    expect(section()!.parts!(store).single.id, 'ai-provider');

    // No key yet: the page and its summary say so, and never claim it is ready.
    final hero = page().whereType<PaneHero>().single;
    expect(hero.title, 'Add an AI key');
    expect(section()!.summary!(store), 'Add an AI key');

    // Tight unless changed, and the choice says what each does.
    expect(store.choice('enhance_style'), 'tight');
    final style = rows().firstWhere((r) => r.label == 'How much it writes');
    expect((style.hint as Text).data, allOf(contains('about as long as yours'), contains('as far as you gave it')));
    await store.set('enhance_style', 'thorough');
    expect(calls.any((c) => c.method == 'setSetting' && (c.arguments as Map)['key'] == 'enhance_style' && (c.arguments as Map)['value'] == 'thorough'), isTrue);

    // What the person writes about themselves: optional, limited, and said to be sent with each prompt.
    final about = page().whereType<PaneSection>().firstWhere((s) => s.title == 'About you and your work');
    final footer = (about.footer as Text).data!;
    for (final phrase in ['Optional', '600 characters', 'sent with each prompt you enhance', 'only where it bears on it']) {
      expect(footer, contains(phrase));
    }
    expect(store.choice('enhance_notes'), '');
    final input = about.children.whereType<SettingRowBox>().single.child as GlacierInput;
    expect(input.maxLength, 600);
    input.onSubmitted!('  Kotlin and Flutter. Small changes, with tests.  ');
    await tester.pump();
    expect(calls.any((c) => c.method == 'setSetting' && (c.arguments as Map)['key'] == 'enhance_notes' && (c.arguments as Map)['value'] == 'Kotlin and Flutter. Small changes, with tests.'), isTrue);

    // What it does, and where it never runs, in plain rows.
    final labels = rows().map((r) => r.label).toList();
    for (final label in ['What you want comes first', 'Your particulars are kept', 'Nothing is added', 'Where it leads the row', 'Never', 'Remove Prompt enhancer']) {
      expect(labels, contains(label));
    }
    expect((rows().firstWhere((r) => r.label == 'Never').hint as Text).data, allOf(contains('password fields'), contains('incognito'), contains('Offline')));

    // Ready once the AI is: the summary then says which way it writes.
    store.ai = {...store.ai, 'ready': true};
    expect(section()!.summary!(store), 'Clearer prompts, set out in full');
    expect(page().whereType<PaneHero>().single.title, 'Ready');

    // Removing it takes the page away.
    rows().firstWhere((r) => r.label == 'Remove Prompt enhancer').onPress!();
    await tester.pump();
    expect(section(), isNull);
  });

  test('the Market offers it as an add-on that reaches only the AI the person chose, and not on iPhone', () {
    final plugin = pluginById('enhance')!;
    expect(plugin.name, 'Prompt enhancer');
    expect(builtInPlugins, isNot(contains('enhance')));
    expect(marketCatalogue.any((p) => p.id == 'enhance'), isTrue);
    expect(plugin.hasPage, isTrue);
    expect(plugin.ios, isFalse);
    expect(plugin.permissions.single.kind, Permission.network);
    expect(plugin.hosts, ['the AI provider you choose']);
    for (final phrase in ['Enhance button', 'say what you want first', 'adds nothing you did not say', 'does not answer the prompt', 'undo puts yours back', 'your own key', 'Never in password fields']) {
      expect(plugin.description, contains(phrase));
    }
  });
}
