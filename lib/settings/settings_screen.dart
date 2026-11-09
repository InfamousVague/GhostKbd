/// Ghost Keyboard's Settings, in Ghost.md's shape (its lib/settings/settings_screen.dart): a list of sections in
/// groups, each a row with its icon on a chip in the section's colour, its name and a line of its state, opening the
/// section's page; a page is the kit's cards (kit/settings_kit.dart) under a bar with the way back. Added plugins with
/// a page of their own join the list, as they do in Ghost.md.
library;

import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../settings_store.dart';
import 'keyboard_preview.dart';
import 'kit/settings_icons.dart';
import 'kit/settings_kit.dart';
import 'settings_search.dart';
import 'kit/settings_type.dart';

/// One section: a row on the list and the page it opens.
@immutable
class SettingsSection {
  const SettingsSection({required this.id, required this.label, required this.icon, required this.hue, required this.group, required this.page, this.summary, this.screen, this.parts});

  final String id;
  final String label;
  final Widget icon;

  /// Its colour, by the names settings.css keeps (settings_css.dart).
  final String hue;

  /// Rows with the same group share one card.
  final String group;

  /// The row's second line, read live.
  final String Function(SettingsStore store)? summary;

  /// Its page's cards.
  final List<Widget> Function(BuildContext context, SettingsStore store) page;

  /// A screen of its own in place of a page of cards: the Market.
  final WidgetBuilder? screen;

  /// Its parts, each a section of its own: listed under the page's own cards, as the home list lists sections, and
  /// opening their pages (AI's Your AI and Your style, Ghostwriter's What it can read…).
  final List<SettingsSection> Function(SettingsStore store)? parts;
}

/// Every section and every part of one, for opening any of them by id.
Iterable<SettingsSection> everySection(List<SettingsSection> sections, SettingsStore store) =>
    sections.expand((s) => [s, ...everySection(s.parts?.call(store) ?? const [], store)]);

/// The page every section and the Market stand on: the ground, the bar with the way back and the title, and the
/// content scrolling under it.
class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key, required this.title, required this.child, this.hue, this.back = true, this.finder, this.seeking});

  final String title;
  final Widget child;
  final String? hue;
  final bool back;

  /// The page's finder, where a page looks for a setting once it is drawn ([SectionPage.seek]); a fresh one otherwise.
  final SettingsFinder? finder;

  /// The setting being looked for: the page is then built whole in its first frame.
  final String? seeking;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final pad = MediaQuery.paddingOf(context);
    final gutter = t.space(4);
    Widget page = ColoredBox(
      color: t.color('glacier-bg'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(height: pad.top),
          SizedBox(
            height: t.lenOf('3.25rem'),
            child: Row(
              children: [
                SizedBox(width: t.space(back ? 1 : 4)),
                if (back)
                  GlacierPressable(
                    onPressed: () => Navigator.of(context).maybePop(),
                    semanticLabel: 'Back',
                    builder: (context, state) => SizedBox.square(
                      dimension: t.lenOf('2.75rem'),
                      child: Center(child: SettingsIcon(SettingsGlyph.arrowLeft, size: 22, color: t.color(state.pressed ? 'app-ink-3' : 'app-ink'))),
                    ),
                  ),
                Expanded(
                  child: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: pageText(t, back ? t.fontSize('lg') : t.fontSize('2xl'), weight: back ? 'semibold' : 'bold', color: t.color('app-ink'))),
                ),
                SizedBox(width: t.space(4)),
              ],
            ),
          ),
          Expanded(
            child: ListView(
              padding: EdgeInsets.fromLTRB(gutter, t.space(2), gutter, t.space(4)),
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              children: [child],
            ),
          ),
          // A field on every page to try a setting at once. When it's tapped the page slides up with the keyboard,
          // the field riding just above the keys (the keyboard's own room above them lets the page through).
          AnimatedPadding(
            duration: const Duration(milliseconds: 180),
            curve: Curves.easeOut,
            padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
            child: _TryBar(gutter: gutter, typing: MediaQuery.viewInsetsOf(context).bottom > 0, safeBottom: pad.bottom),
          ),
        ],
      ),
    );
    if (hue != null) page = GlacierScope(attrs: {'hue': hue!}, child: page);
    return SettingsPaneScope(finder: finder ?? SettingsFinder(), whole: seeking != null, seeking: seeking, child: page);
  }
}

/// The foot of every settings page: a field to try the keyboard in, a Preview button beside it, and under them, while
/// the preview is open and the real keyboard is away, the keyboard itself drawn live (keyboard_preview.dart).
class _TryBar extends StatelessWidget {
  const _TryBar({required this.gutter, required this.typing, required this.safeBottom});
  final double gutter;

  /// The real keyboard is up: the preview steps aside for it.
  final bool typing;

  /// The room Android's own buttons take under the page.
  final double safeBottom;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final store = SettingsStore.current;
    Widget bar(bool previewing) => DecoratedBox(
          decoration: BoxDecoration(color: t.color('glacier-bg'), border: Border(top: BorderSide(color: t.color('app-rule')))),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: EdgeInsets.fromLTRB(gutter, t.space(2), gutter, typing || previewing ? t.space(2) : t.space(2) + safeBottom),
                child: Row(
                  children: [
                    const Expanded(child: GlacierInput(placeholder: 'Try the keyboard here', ariaLabel: 'Try the keyboard', size: GlacierFieldSize.md)),
                    if (store != null) ...[SizedBox(width: t.space(2)), KeyboardPreviewButton(store: store)],
                  ],
                ),
              ),
              if (previewing && store != null) ...[KeyboardPreview(store: store), SizedBox(height: safeBottom)],
            ],
          ),
        );
    if (store == null) return bar(false);
    return ListenableBuilder(listenable: store, builder: (context, _) => bar(store.flag('preview_open') && !typing));
  }
}

/// Opens a page from the right, as a phone's Settings does.
Future<void> pushSettings(BuildContext context, WidgetBuilder builder) => Navigator.of(context).push(
      PageRouteBuilder<void>(
        transitionDuration: const Duration(milliseconds: 260),
        reverseTransitionDuration: const Duration(milliseconds: 220),
        pageBuilder: (context, _, _) => builder(context),
        transitionsBuilder: (context, animation, _, child) => SlideTransition(
          position: Tween(begin: const Offset(1, 0), end: Offset.zero).animate(CurvedAnimation(parent: animation, curve: Curves.easeOutCubic, reverseCurve: Curves.easeInCubic)),
          child: child,
        ),
      ),
    );

/// A section's page: its cards, staggered in as Ghost.md's arrive. Opened from a search with [seek], the setting of that
/// name is scrolled into view and lit once the page is drawn.
class SectionPage extends StatefulWidget {
  const SectionPage({super.key, required this.section, required this.store, this.seek});

  final SettingsSection section;
  final SettingsStore store;
  final String? seek;

  @override
  State<SectionPage> createState() => _SectionPageState();
}

class _SectionPageState extends State<SectionPage> {
  final _finder = SettingsFinder();

  @override
  void initState() {
    super.initState();
    final seek = widget.seek;
    if (seek == null) return;
    // Two frames: one to build the page whole, one for it to be laid out.
    WidgetsBinding.instance.addPostFrameCallback((_) => WidgetsBinding.instance.addPostFrameCallback((_) {
          final found = _finder.find(seek);
          if (found == null || !mounted) return;
          Scrollable.ensureVisible(found.context, alignment: 0.25, duration: const Duration(milliseconds: 320), curve: Curves.easeOutCubic);
          found.light();
        }));
  }

  @override
  Widget build(BuildContext context) {
    final section = widget.section;
    final store = widget.store;
    return ListenableBuilder(
        listenable: store,
        builder: (context, _) => SettingsPage(
          finder: _finder,
          seeking: widget.seek,
          title: section.label,
          hue: section.hue,
          child: PaneBody(children: [
            ...section.page(context, store),
            if (section.parts != null) SettingsList(store: store, sections: section.parts!(store)),
          ]),
        ),
      );
  }
}

/// Settings' home: a search field over the list of sections. While words are typed the list gives way to what they
/// find (settings_search.dart): sections and settings, each with where it is, opening there.
class SettingsHome extends StatefulWidget {
  const SettingsHome({super.key, required this.store, required this.sections});

  final SettingsStore store;
  final List<SettingsSection> sections;

  @override
  State<SettingsHome> createState() => _SettingsHomeState();
}

class _SettingsHomeState extends State<SettingsHome> {
  String _query = '';
  final _field = TextEditingController();

  @override
  void dispose() {
    _field.dispose();
    super.dispose();
  }

  /// Opens a hit: each section on its path in turn, the last looking for the setting.
  Future<void> _open(SettingsHit hit) async {
    final store = widget.store;
    if (hit.setting != null) store.openAllMore();
    for (var i = 0; i < hit.path.length; i++) {
      final s = hit.path[i];
      final last = i == hit.path.length - 1;
      if (s.screen != null) {
        unawaited(pushSettings(context, s.screen!));
        return;
      }
      unawaited(pushSettings(context, (_) => SectionPage(section: s, store: store, seek: last ? hit.setting : null)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final store = widget.store;
    final hits = _query.trim().isEmpty ? const <SettingsHit>[] : searchSettings(context, store, widget.sections, _query);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        GlacierInput(
          controller: _field,
          type: GlacierInputType.search,
          placeholder: 'Search settings',
          ariaLabel: 'Search settings',
          onChange: (text) => setState(() => _query = text),
        ),
        SizedBox(height: t.space(5)),
        if (_query.trim().isEmpty)
          SettingsList(store: store, sections: widget.sections)
        else if (hits.isEmpty)
          Padding(padding: EdgeInsets.all(t.space(4)), child: Text('Nothing called “${_query.trim()}”.', textAlign: TextAlign.center, style: pageText(t, t.fontSize('sm'), color: t.color('app-ink-2'))))
        else
          _Group(rows: [
            for (var i = 0; i < hits.length; i++)
              _SectionRow(
                section: SettingsSection(id: '${hits[i].section.id}-hit', label: hits[i].setting ?? hits[i].section.label, icon: hits[i].section.icon, hue: hits[i].section.hue, group: '', page: hits[i].section.page),
                summary: [
                  (hits[i].setting == null ? hits[i].path.take(hits[i].path.length - 1) : hits[i].path).map((s) => s.label).join(' › '),
                  if (hits[i].setting == null) hits[i].section.summary?.call(store) ?? '',
                ].where((x) => x.isNotEmpty).join(' · '),
                index: i,
                onPress: () => _open(hits[i]),
              ),
          ]),
      ],
    );
  }
}

/// The list of sections: the app's Settings home.
class SettingsList extends StatelessWidget {
  const SettingsList({super.key, required this.store, required this.sections, this.header});

  final SettingsStore store;
  final List<SettingsSection> sections;

  /// What stands above the groups: the setup card, while there is setting up left to do.
  final Widget? header;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final groups = <String, List<SettingsSection>>{};
    for (final section in sections) {
      groups.putIfAbsent(section.group, () => []).add(section);
    }
    var index = 0;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (header != null) ...[header!, SizedBox(height: t.space(5))],
        for (final group in groups.values) ...[
          _Group(rows: [for (final section in group) _SectionRow(section: section, summary: section.summary?.call(store), index: index++, onPress: () => pushSettings(context, section.screen ?? (_) => SectionPage(section: section, store: store)))]),
          SizedBox(height: t.space(5)),
        ],
      ],
    );
  }
}

/// `.settingsScreen__group`: rows sharing one card, a hairline between them.
class _Group extends StatelessWidget {
  const _Group({required this.rows});
  final List<Widget> rows;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final hairline = t.len('glacier-hairline');
    final rule = t.color('app-rule');
    return GlacierScope(
      classes: const {'settingsScreen__group'},
      child: ClipRRect(
        borderRadius: BorderRadius.circular(t.radius('lg')),
        child: ColoredBox(
          color: t.color('app-paper-2'),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var i = 0; i < rows.length; i++)
                i == 0 ? rows[i] : DecoratedBox(decoration: BoxDecoration(border: Border(top: BorderSide(color: rule, width: hairline))), child: Padding(padding: EdgeInsets.only(top: hairline), child: rows[i])),
            ],
          ),
        ),
      ),
    );
  }
}

/// `.settingsScreen__row`: the section's chip in its colour, its name and a line under it, and a chevron.
class _SectionRow extends StatelessWidget {
  const _SectionRow({required this.section, required this.summary, required this.index, required this.onPress});

  final SettingsSection section;
  final String? summary;
  final int index;
  final VoidCallback onPress;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final gap = t.space(3);
    final chip = t.lenOf('1.85rem');
    final hasSummary = summary != null && summary!.isNotEmpty;
    return SettingsRise(
      index: index,
      child: GlacierPressable(
        onPressed: onPress,
        semanticLabel: section.label,
        builder: (context, state) => ColoredBox(
          color: state.pressed ? t.color('app-paper-3') : const Color(0x00000000),
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: t.lenOf('3.5rem')),
            child: Padding(
              padding: EdgeInsets.all(gap),
              child: Row(
                children: [
                  GlacierScope(
                    attrs: {'hue': section.hue},
                    child: Builder(
                      builder: (context) {
                        final hued = context.tokens;
                        return Container(
                          width: chip,
                          height: chip,
                          decoration: BoxDecoration(color: settingsHueWash(hued), borderRadius: BorderRadius.circular(t.lenOf('0.55rem'))),
                          alignment: Alignment.center,
                          child: ExcludeSemantics(child: IconTheme.merge(data: IconThemeData(color: settingsHue(hued)), child: section.icon)),
                        );
                      },
                    ),
                  ),
                  SizedBox(width: gap),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(section.label, style: pageText(t, t.fontSize('md'), weight: 'medium', color: t.color('app-ink'))),
                        if (hasSummary) ...[
                          SizedBox(height: t.lenOf('0.1rem')),
                          Text(summary!, maxLines: 1, softWrap: false, overflow: TextOverflow.ellipsis, style: pageText(t, t.fontSize('sm'), color: t.color('app-ink-3'))),
                        ],
                      ],
                    ),
                  ),
                  SizedBox(width: gap),
                  ExcludeSemantics(child: SettingsIcon(SettingsGlyph.chevronRight, size: 18, color: t.color('app-ink-4'))),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
