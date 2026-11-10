// Settings › Typing › Autocorrect › Next word: on by default with its learning; the card says which of the languages
// that are on start with word pairs and which suggest from the person's typing only, from what the keyboard says it
// has (never from a list written in the page); forgetting is offered only when there is something to forget; an
// export carries the word pairs unless its switch is off; and the search finds it. Run with `flutter test`.

import 'package:dev_keyboard/settings/kit/settings_kit.dart';
import 'package:dev_keyboard/settings/panes.dart';
import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/settings/settings_screen.dart';
import 'package:dev_keyboard/settings/settings_search.dart';
import 'package:dev_keyboard/settings_store.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

const shared = [('qwerty', 'QWERTY')];

/// A catalogue as `languagesState` sends it: one language with a starter table, one without, one with no word list.
const catalogue = [
  LanguageInfo('en', 'English', 'English', layouts: shared, nextWords: true),
  LanguageInfo('de', 'Deutsch', 'German', layouts: shared, nextWords: true),
  LanguageInfo('hu', 'Magyar', 'Hungarian', layouts: shared),
  LanguageInfo('ko', '한국어', 'Korean', script: 'hangul', layouts: [('korean', '한국어')], dictionary: false),
];

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  // No phone behind the channel: the calls are kept to look at, and each is answered with nothing.
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async {
    calls.add(call);
    return null;
  });

  test('on by default, with its learning, and an export carries the word pairs unless told not to', () async {
    final store = SettingsStore();
    expect(store.flag('next_word'), isTrue);
    expect(store.flag('next_word_learn'), isTrue);
    expect(store.profilePairs, isTrue);
    calls.clear();
    await store.exportProfile();
    expect(calls.single.arguments, {'samples': false, 'pairs': true});
    store.setProfilePairs(false);
    calls.clear();
    await store.exportProfile();
    expect(calls.single.arguments, {'samples': false, 'pairs': false});
  });

  test('the card names the languages that start with word pairs and the ones that do not', () async {
    final store = SettingsStore()..languageCatalogue = catalogue;
    expect(nextWordFooter(store), 'English starts with common word pairs counted from Tatoeba’s sentences. Older habits fade as you type new ones.');
    await store.setLanguage('de', true);
    await store.setLanguage('hu', true);
    await store.setLanguage('ko', true);
    final footer = nextWordFooter(store);
    expect(footer, startsWith('English, Deutsch start with common word pairs counted from Tatoeba’s sentences.'));
    expect(footer, contains('Magyar has no starter pairs yet: it suggests from your own typing and how common words are.'));
    expect(footer, isNot(contains('한국어')), reason: 'a language with no word list has no suggestions at all, and its own note says so');
    await store.set('suggestions', false);
    expect(nextWordFooter(store), endsWith('Turn on Suggestions above the keys to see them.'));
  });

  testWidgets('the page has the card, forgetting is always within reach, and the search finds it', (tester) async {
    final sheet = await tester.runAsync(() => loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css']));
    installSettingsStyles(sheet!);
    final store = SettingsStore()..languageCatalogue = catalogue;
    late BuildContext context;
    await tester.pumpWidget(Directionality(
      textDirection: TextDirection.ltr,
      child: GlacierTheme(sheet: sheet, attrs: const {'platform': 'mobile', 'theme': 'dark'}, child: Builder(builder: (c) {
        context = c;
        return const SizedBox();
      })),
    ));
    SettingsSection part(String id) => settingsSections(store).firstWhere((s) => s.id == 'typing').parts!(store).firstWhere((s) => s.id == id);

    final rows = pageEntries(part('autocorrect').page(context, store));
    expect(rows.map((e) => e.$1), containsAllInOrder(['Suggestions above the keys', 'Next word', 'Suggest the next word', 'Learn from what I type', 'Learned so far', 'Forget what it learned', 'Learned words']));
    expect(rows.firstWhere((e) => e.$1 == 'Learn from what I type').$2, contains('on this phone'));
    expect(rows.firstWhere((e) => e.$1 == 'Learn from what I type').$2, contains('Never in password fields, while incognito'));
    expect(part('autocorrect').summary!(store), contains('next words'));

    // The wording rule (docs/COMPETITION.md, section 9): "on this phone", and no promise about what leaves it or how good it is.
    final words = rows.map((e) => '${e.$1} ${e.$2}').join(' ').toLowerCase();
    for (final banned in ['nothing leaves', 'never leaves', 'as good as', 'accurate', 'gboard']) {
      expect(words, isNot(contains(banned)));
    }

    // Counts come from the keyboard, which goes on learning while Settings is in the background: the count shown
    // may be behind, so forgetting is never greyed out on it.
    List<SettingRow> card() => part('autocorrect').page(context, store).whereType<PaneSection>().firstWhere((s) => s.title == 'Next word').children.whereType<SettingRow>().toList();
    expect(card().last.label, 'Forget what it learned');
    expect(card().last.onPress, isNotNull);
    expect((card()[2].value as Text).data, '0 word pairs');
    store.nextWords = const {'pairs': 42, 'words': 30, 'bundled': ['de', 'en']};
    expect(card().last.onPress, isNotNull);
    expect((card()[2].value as Text).data, '42 word pairs');

    // Languages says which of them have no starter pairs, once the keyboard has said which do.
    final languages = pageEntries(part('languages').page(context, store));
    expect(languages.firstWhere((e) => e.$1 == 'Magyar').$2, 'Hungarian · Next words from your typing only');
    expect(languages.firstWhere((e) => e.$1 == 'Deutsch').$2, 'German');
    expect(languages.firstWhere((e) => e.$1 == '한국어').$2, 'Korean');

    // The export's switch, and About's credit.
    final profile = settingsSections(store).expand((s) => [s, ...?s.parts?.call(store)]).firstWhere((s) => s.id == 'profile');
    expect(pageEntries(profile.page(context, store)).map((e) => e.$1), contains('Include my word pairs'));
    final about = settingsSections(store).firstWhere((s) => s.id == 'about');
    expect(pageEntries(about.page(context, store)).firstWhere((e) => e.$1 == 'Tatoeba').$2, contains('CC BY 2.0 FR'));

    List<SettingsHit> find(String text) => searchSettings(context, store, settingsSections(store), text);
    for (final text in ['next word', 'suggest the next', 'word pairs', 'forget', 'tatoeba']) {
      expect(find(text), isNotEmpty, reason: '"$text" finds nothing');
    }
    expect(find('next word').any((h) => h.path.any((s) => s.id == 'autocorrect')), isTrue);
  });
}
