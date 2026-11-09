/// The Market: Ghost Keyboard's plugins, laid out as Ghost.md's Market is (its lib/market/market_screen.dart): a band of
/// colour at its head, the featured plugins in colour, a filter by category, and every plugin on a shelf of cards; a
/// card opens the plugin's page, where it is added or removed.
library;

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../art/lucide.g.dart';
import '../art/lucide_more.dart';
import '../onboarding/band.dart';
import '../plugins/catalogue.dart';
import '../settings/panes.dart';
import '../settings/settings_screen.dart';
import '../settings_store.dart';
import 'market_cards.dart';
import 'market_plugins.dart';

class MarketScreen extends StatefulWidget {
  const MarketScreen({super.key, required this.store});
  final SettingsStore store;

  @override
  State<MarketScreen> createState() => _MarketScreenState();
}

class _MarketScreenState extends State<MarketScreen> {
  PluginCategory? _filter;

  SettingsStore get store => widget.store;

  void _open(PluginManifest plugin) => pushSettings(
        context,
        (_) => ListenableBuilder(
          listenable: store,
          builder: (context, _) => SettingsPage(
            title: plugin.name,
            child: PluginDetail(
              plugin: plugin,
              added: store.pluginOn(plugin.id),
              onSwitch: (on) => store.setPlugin(plugin.id, on),
              onSettings: plugin.hasPage ? () => _openSettings(plugin) : null,
            ),
          ),
        ),
      );

  void _openSettings(PluginManifest plugin) {
    final section = settingsSections(store).where((s) => s.id == plugin.id).firstOrNull;
    if (section != null) pushSettings(context, (_) => SectionPage(section: section, store: store));
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) {
        final featured = marketCatalogue.where((p) => p.soon == null).toList();
        final shown = marketCatalogue.where((p) => _filter == null || p.category == _filter).toList();
        final categories = {for (final p in marketCatalogue) p.category}.toList();
        return SettingsPage(
          title: 'Plugins',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              HueBand(
                colours: hueColours(const ['indigo', 'purple', 'rose', 'orange']),
                height: t.lenOf('10rem'),
                sign: (h, w) => bandDisc(Lucide.store, height: h, width: w),
                tiles: [bandIcon(Lucide.puzzle), bandIcon(Lucide.palette), bandIcon(Lucide.mic), bandIcon(Lucide.spellCheck)],
              ),
              SizedBox(height: t.space(4)),
              Text(
                'Built in, and off until you add them. Adding one is a switch, and so is taking it away.',
                style: TextStyle(color: t.color('app-ink-2'), fontSize: t.fontSize('sm'), height: 1.4),
              ),
              SizedBox(height: t.space(5)),
              const MarketHeading(title: 'Featured', meta: 'Ready to add'),
              MarketRow(
                width: t.lenOf('16rem'),
                height: t.lenOf('13rem'),
                children: [
                  for (final plugin in featured)
                    FeaturedPluginCard(plugin: plugin, added: store.pluginOn(plugin.id), onAdd: () => store.setPlugin(plugin.id, true), onOpen: () => _open(plugin)),
                ],
              ),
              SizedBox(height: t.space(6)),
              MarketHeading(title: 'All plugins', meta: '${marketCatalogue.length} · ${marketCatalogue.where((p) => store.pluginOn(p.id)).length} added', icon: LucideMore.slidersHorizontal),
              Wrap(
                spacing: t.space(2),
                runSpacing: t.space(2),
                children: [
                  MarketChip(label: 'All', chosen: _filter == null, onPress: () => setState(() => _filter = null)),
                  for (final category in categories) MarketChip(label: category.label, chosen: _filter == category, onPress: () => setState(() => _filter = category)),
                ],
              ),
              SizedBox(height: t.space(3)),
              MarketGrid(
                minWidth: 320,
                most: 2,
                children: [
                  for (final plugin in shown)
                    MarketPluginCard(plugin: plugin, added: store.pluginOn(plugin.id), onAdd: () => store.setPlugin(plugin.id, true), onOpen: () => _open(plugin)),
                ],
              ),
            ],
          ),
        );
      },
    );
  }
}
