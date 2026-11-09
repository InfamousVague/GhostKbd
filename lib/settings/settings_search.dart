/// Settings search (Matt, 2026-11-09: "add a settings search like we have in Ghost markdown"): what a few typed words
/// find among Settings' sections, their parts, and the settings on their pages, matched as Ghost.md matches them
/// (its lib/settings/settings_search.dart): every word typed is found, in any order, each at the start of a word.
///
/// Unlike Ghost.md's, nothing is listed by hand: the index is read off the pages themselves (each page is a list of
/// the kit's cards, rows and heroes, which say their own names), so a setting is found the day it is added.
library;

import 'package:flutter/widgets.dart';

import '../settings_store.dart';
import 'kit/settings_kit.dart';
import 'settings_screen.dart';

/// Lower case, with curly quotes and apostrophes made straight, so what is typed matches what is drawn. Ghost.md's
/// `fold` without its accent folding, which these English words need not.
String fold(String text) => text.toLowerCase().replaceAll(RegExp('[‘’]'), "'").replaceAll(RegExp('[“”]'), '"');

final RegExp _notWord = RegExp(r'[^\p{L}\p{N}]+', unicode: true);

List<String> _words(String text) => fold(text).split(_notWord).where((w) => w.isNotEmpty).toList();

/// Every word of [query] starts a word of [text].
bool matches(String query, String text) {
  final want = _words(query);
  if (want.isEmpty) return false;
  final have = _words(text);
  return want.every((w) => have.any((h) => h.startsWith(w)));
}

/// One thing found: a section (with the sections it sits in, outermost first), or a setting on its page.
class SettingsHit {
  const SettingsHit(this.path, {this.setting, this.detail});

  /// The sections to open, outermost first; the last is the one found, or the one the setting is on.
  final List<SettingsSection> path;

  /// The setting found on the last section's page, as the page names it; null when the section itself was found.
  final String? setting;

  /// The setting's hint, for the line under it.
  final String? detail;

  SettingsSection get section => path.last;
}

/// What a page draws that can be looked for: each card's title, each row's label with its hint, a hero's title.
List<(String, String)> pageEntries(List<Widget> widgets) {
  final out = <(String, String)>[];
  String textOf(Widget? w) => w is Text ? (w.data ?? '') : '';
  void walk(Widget w) {
    if (w is PaneSection) {
      if ((w.title ?? '').isNotEmpty) out.add((w.title!, textOf(w.footer)));
      for (final c in w.children) {
        walk(c);
      }
    } else if (w is SettingRow) {
      out.add((w.label, textOf(w.hint)));
    } else if (w is PaneHero) {
      out.add((w.title, textOf(w.meta)));
    }
  }

  for (final w in widgets) {
    walk(w);
  }
  return out;
}

/// What [query] finds: sections and parts by their names and state lines first, then settings on their pages by
/// their names and hints (with their section's name counting too, so "ghostwriter github" finds GitHub's switch).
/// The pages are read with their More folds open ([SettingsStore.indexing]), so what hides there is found too.
List<SettingsHit> searchSettings(BuildContext context, SettingsStore store, List<SettingsSection> sections, String query, {int limit = 40}) {
  final found = <SettingsHit>[];
  final settings = <SettingsHit>[];
  final seen = <String>{};
  store.indexing = true;
  try {
    void visit(List<SettingsSection> list, List<SettingsSection> above) {
      for (final s in list) {
        final path = [...above, s];
        final where = path.map((p) => p.label).join(' ');
        if (matches(query, '${s.label} ${s.summary?.call(store) ?? ''}')) found.add(SettingsHit(path));
        if (s.screen == null) {
          for (final (label, detail) in pageEntries(s.page(context, store))) {
            if (label == s.label || !seen.add('${s.id}|$label')) continue;
            if (matches(query, '$label $detail $where')) settings.add(SettingsHit(path, setting: label, detail: detail));
          }
        }
        visit(s.parts?.call(store) ?? const [], path);
      }
    }

    visit(sections, const []);
  } finally {
    store.indexing = false;
  }
  return [...found, ...settings].take(limit).toList();
}
