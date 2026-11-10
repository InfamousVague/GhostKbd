// Settings › Typing › Languages: the page is drawn from the keyboard's own catalogue (SettingsStore.languageCatalogue,
// Languages.kt through `languagesState`), not from a list written in the page; a language is found by its own name
// and its English one; turning one on appends it, goes to its layout and stops at six. Run with `flutter test`.

import 'package:dev_keyboard/settings/panes.dart';
import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/settings/settings_screen.dart';
import 'package:dev_keyboard/settings/settings_search.dart';
import 'package:dev_keyboard/settings_store.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

/// A catalogue as `languagesState` sends it, with one language no page could have made up.
const shared = [('qwerty', 'QWERTY'), ('qwertz', 'QWERTZ'), ('azerty', 'AZERTY')];
const catalogue = [
  LanguageInfo('en', 'English', 'English', layouts: shared),
  LanguageInfo('fr', 'Français', 'French', layouts: [('azerty', 'AZERTY'), ('qwerty', 'QWERTY'), ('qwertz', 'QWERTZ')]),
  LanguageInfo('pl', 'Polski', 'Polish', layouts: shared),
  LanguageInfo('ru', 'Русский', 'Russian', script: 'cyrillic', layouts: [('russian', 'Русская')]),
  LanguageInfo('ko', '한국어', 'Korean', script: 'hangul', layouts: [('korean', '한국어')], dictionary: false, note: 'Typing only: no autocorrect, suggestions or swipe yet.'),
  LanguageInfo('tlh', 'tlhIngan Hol', 'Klingon', layouts: shared),
  LanguageInfo('es', 'Español', 'Spanish', layouts: shared),
  LanguageInfo('de', 'Deutsch', 'German', layouts: shared),
  LanguageInfo('it', 'Italiano', 'Italian', layouts: shared),
];

SettingsStore storeWithCatalogue() => SettingsStore()
  ..languageCatalogue = catalogue
  ..unavailableLanguages = const [(name: '中文', english: 'Chinese', why: 'Needs a conversion engine (pinyin, kana). Not built yet.')];

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // There is no phone behind the channel here: every call is answered with nothing, so the store keeps what the test gives it.
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async => null);

  SettingsSection typing(SettingsStore store) => settingsSections(store).firstWhere((s) => s.id == 'typing');

  test('Languages is a part of Typing with or without Autocorrect, and says which are on', () async {
    final store = storeWithCatalogue();
    expect(typing(store).parts!(store).first.id, 'languages');
    await store.setPlugin('autocorrect', false);
    final part = typing(store).parts!(store).firstWhere((s) => s.id == 'languages');
    expect(part.summary!(store), 'English');
    await store.setLanguage('ru', true);
    expect(part.summary!(store), 'English, Русский');
  });

  test('turning a language on appends it and goes to its layout; a seventh is refused; the last is never turned off', () async {
    final store = storeWithCatalogue();
    await store.setLanguage('fr', true);
    expect(store.languages, ['en', 'fr']);
    expect(store.choice('layout'), 'azerty', reason: 'French brings AZERTY, and the preview goes to it');
    await store.setLanguage('nope', true);
    expect(store.languages, ['en', 'fr'], reason: 'a language the keyboard does not have is not added');
    for (final code in ['pl', 'ru', 'ko', 'tlh']) {
      await store.setLanguage(code, true);
    }
    expect(store.languages.length, 6);
    await store.setLanguage('es', true);
    expect(store.languages, ['en', 'fr', 'pl', 'ru', 'ko', 'tlh'], reason: 'six at once');
    for (final code in ['fr', 'pl', 'ru', 'ko', 'tlh', 'en']) {
      await store.setLanguage(code, false);
    }
    expect(store.languages, ['en'], reason: 'never none');
  });

  test('a language’s layout can be chosen; its own first layout is no choice at all', () async {
    final store = storeWithCatalogue();
    await store.setLanguage('fr', true);
    final fr = store.languageOf('fr')!;
    expect(store.layoutOf(fr), 'azerty');
    await store.setLanguageLayout('fr', 'qwerty');
    expect(store.choice('language_layouts'), 'fr:qwerty');
    expect(store.layoutOf(fr), 'qwerty');
    expect(store.choice('layout'), 'qwerty');
    await store.setLanguageLayout('fr', 'russian');
    expect(store.layoutOf(fr), 'qwerty', reason: 'a layout the language may not use is refused');
    await store.setLanguageLayout('fr', 'azerty');
    expect(store.choice('language_layouts'), '');
    // A keyboard from before layouts has Spanish pinned to QWERTY: the page shows that, not Spanish's own.
    await store.set('language_layouts', 'es:qwerty, de:qwertz');
    expect(store.languageLayouts, {'es': 'qwerty', 'de': 'qwertz'});
  });

  testWidgets('the page is the catalogue, and the search finds a language by either name', (tester) async {
    final sheet = await tester.runAsync(() => loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css']));
    installSettingsStyles(sheet!);
    final store = storeWithCatalogue();
    late BuildContext context;
    await tester.pumpWidget(Directionality(
      textDirection: TextDirection.ltr,
      child: GlacierTheme(sheet: sheet, attrs: const {'platform': 'mobile', 'theme': 'dark'}, child: Builder(builder: (c) {
        context = c;
        return const SizedBox();
      })),
    ));
    await store.setLanguage('fr', true);

    final part = typing(store).parts!(store).firstWhere((s) => s.id == 'languages');
    final rows = pageEntries(part.page(context, store));
    final labels = rows.map((e) => e.$1).toList();
    expect(labels, containsAll(['Your languages', 'English', 'Français', 'Keys for Français', 'Switching', 'Globe key beside space', 'Add a language', 'Polski', 'Русский', 'tlhIngan Hol', 'Not yet', '中文']));
    expect(labels.indexOf('Français'), lessThan(labels.indexOf('Switching')), reason: 'a language that is on is under Your languages');
    expect(labels.indexOf('Polski'), greaterThan(labels.indexOf('Add a language')));
    expect(rows.firstWhere((e) => e.$1 == 'Français').$2, 'French · AZERTY');
    expect(rows.firstWhere((e) => e.$1 == 'Polski').$2, 'Polish · QWERTY');
    expect(rows.firstWhere((e) => e.$1 == 'Русский').$2, 'Russian');
    expect(rows.firstWhere((e) => e.$1 == '한국어').$2, 'Korean. Typing only: no autocorrect, suggestions or swipe yet.');
    final adding = rows.firstWhere((e) => e.$1 == 'Add a language').$2;
    expect(adding, startsWith('Every word list is inside the app; nothing is downloaded.'));
    // Said plainly: beyond the first six, corrections are cautious and nobody who reads the language has checked them.
    expect(adding, contains('autocorrect is cautious'));
    expect(adding, contains('No reader of each language has checked its corrections yet'));
    expect(rows.firstWhere((e) => e.$1 == '中文').$2, startsWith('Chinese. Needs a conversion engine'));

    // The search, with no list of its own, finds a language by its own name and by its English one.
    List<SettingsHit> find(String words) => searchSettings(context, store, settingsSections(store), words);
    for (final words in ['polish', 'polski', 'klingon', 'русский', 'russian', 'korean', 'globe', 'languages', 'azerty']) {
      expect(find(words).any((h) => h.path.any((s) => s.id == 'languages')), isTrue, reason: '"$words" finds nothing under Languages');
    }

    // What is typed in "Find a language" narrows the list for the person, never for the search.
    store.setLanguageFilter('pol');
    final narrowed = pageEntries(part.page(context, store)).map((e) => e.$1).toList();
    expect(narrowed, contains('Polski'));
    expect(narrowed, isNot(contains('Русский')));
    expect(find('russian').any((h) => h.setting == 'Русский'), isTrue);
    store.setLanguageFilter('');

    // Autocorrect's page no longer lists languages of its own.
    final autocorrect = typing(store).parts!(store).firstWhere((s) => s.id == 'autocorrect');
    expect(pageEntries(autocorrect.page(context, store)).map((e) => e.$1), isNot(contains('Languages')));
  });
}
