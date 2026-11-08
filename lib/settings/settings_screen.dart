/// Ghost Keyboard's Settings, in Ghost.md's shape (its lib/settings/settings_screen.dart): a list of sections in
/// groups, each a row with its icon on a chip in the section's colour, its name and a line of its state, opening the
/// section's page; a page is the kit's cards (kit/settings_kit.dart) under a bar with the way back. Added plugins with
/// a page of their own join the list, as they do in Ghost.md.
library;

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../settings_store.dart';
import 'kit/settings_icons.dart';
import 'kit/settings_kit.dart';
import 'kit/settings_type.dart';

/// One section: a row on the list and the page it opens.
@immutable
class SettingsSection {
  const SettingsSection({required this.id, required this.label, required this.icon, required this.hue, required this.group, required this.page, this.summary, this.screen});

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
}

/// The page every section and the Market stand on: the ground, the bar with the way back and the title, and the
/// content scrolling under it.
class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key, required this.title, required this.child, this.hue, this.back = true});

  final String title;
  final Widget child;
  final String? hue;
  final bool back;

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
              padding: EdgeInsets.fromLTRB(gutter, t.space(2), gutter, t.space(10) + pad.bottom),
              children: [child],
            ),
          ),
        ],
      ),
    );
    if (hue != null) page = GlacierScope(attrs: {'hue': hue!}, child: page);
    return SettingsPaneScope(finder: SettingsFinder(), child: page);
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

/// A section's page: its cards, staggered in as Ghost.md's arrive.
class SectionPage extends StatelessWidget {
  const SectionPage({super.key, required this.section, required this.store});

  final SettingsSection section;
  final SettingsStore store;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: store,
        builder: (context, _) => SettingsPage(title: section.label, hue: section.hue, child: PaneBody(children: section.page(context, store))),
      );
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
