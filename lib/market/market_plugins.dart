/// The Market's plugins, drawn as Ghost.md draws its own (its lib/market/market_plugins.dart and market_items.dart): a
/// tile in the plugin's hue with its mark in white, a card on a shelf, a featured card in colour at the head of the
/// Market, and a plugin's page. Ported for Ghost Keyboard's plugins (lib/plugins/catalogue.dart): no company marks, and
/// where Ghost.md says which devices a plugin works on, the keyboard says what it costs to add.
library;

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../art/app_icon.dart';
import '../art/lucide.g.dart';
import '../plugins/catalogue.dart';
import '../settings_store.dart';
import '../settings/kit/settings_type.dart';
import 'market_cards.dart';

const Color _white = Color(0xFFFFFFFF);

/// Each hue the Market draws, as Ghost.md's settings.css writes it: the deep shade, then the bright one.
const Map<String, (Color, Color)> marketHues = {
  'blue': (Color(0xFF0A6FE0), Color(0xFF4EA1FF)),
  'purple': (Color(0xFF9A45C9), Color(0xFFC77DFF)),
  'red': (Color(0xFFDD3A30), Color(0xFFFF6B61)),
  'coral': (Color(0xFFC8643F), Color(0xFFF0906B)),
  'green': (Color(0xFF2A9D4B), Color(0xFF4CD571)),
  'yellow': (Color(0xFFB58400), Color(0xFFFFD43B)),
  'mint': (Color(0xFF1F9E8F), Color(0xFF5FE0CF)),
  'amber': (Color(0xFFC27C00), Color(0xFFFFB84D)),
  'indigo': (Color(0xFF4F5BD5), Color(0xFF8C95FF)),
  'orange': (Color(0xFFD9600D), Color(0xFFFF9A4D)),
  'cyan': (Color(0xFF0A86A8), Color(0xFF4CC9F0)),
  'lime': (Color(0xFF5A8A00), Color(0xFFA3D93F)),
  'rose': (Color(0xFFD6336C), Color(0xFFFF7AA8)),
  'magenta': (Color(0xFFB5279F), Color(0xFFF07AE0)),
  'slate': (Color(0xFF4A5A70), Color(0xFF8FA3BF)),
};

(Color, Color) pluginColours(PluginManifest plugin) => marketHues[plugin.hue] ?? marketHues['indigo']!;

/// A wash across the plugin's hue, from its deep shade at the top start to its bright one.
BoxDecoration pluginTile(PluginManifest plugin, {required double radius}) {
  final (deep, bright) = pluginColours(plugin);
  return BoxDecoration(
    borderRadius: BorderRadius.circular(radius),
    gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [deep, Color.lerp(deep, bright, 0.55)!, bright]),
  );
}

/// The light over a tile: a glow from the top start corner.
Widget tileLight({required double radius}) => IgnorePointer(
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(radius),
          gradient: const RadialGradient(center: Alignment(-0.8, -0.9), radius: 1.1, colors: [Color(0x40FFFFFF), Color(0x00FFFFFF)]),
        ),
        child: const SizedBox.expand(),
      ),
    );

/// The plugin's mark in white.
Widget pluginMark(PluginManifest plugin, double size, {Color color = _white}) => AppIcon(plugin.icon, size: size, strokeWidth: 1.8, color: color, wash: false);

/// A plugin's tile: its colour, its mark in the middle, [size] square.
class PluginTile extends StatelessWidget {
  const PluginTile({super.key, required this.plugin, required this.size});

  final PluginManifest plugin;
  final double size;

  @override
  Widget build(BuildContext context) {
    final radius = size * 0.28;
    return SizedBox.square(
      dimension: size,
      child: DecoratedBox(
        decoration: pluginTile(plugin, radius: radius),
        child: Stack(fit: StackFit.expand, children: [tileLight(radius: radius), Center(child: pluginMark(plugin, size * 0.5))]),
      ),
    );
  }
}

Widget _markChip(PluginManifest plugin, {required double size, required double radius, required double markSize}) => Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: const Color(0x2EFFFFFF), borderRadius: BorderRadius.circular(radius), border: Border.all(color: const Color(0x40FFFFFF))),
      alignment: Alignment.center,
      child: pluginMark(plugin, markSize),
    );

/// Add, Added, or the release it arrives in: the end of a plugin's card.
Widget _addOrAdded(PluginManifest plugin, {required bool added, required VoidCallback? onAdd, bool onColour = false}) {
  if (plugin.soon != null) {
    return MarketTag(label: 'In ${plugin.soon}', ground: onColour ? const Color(0x33000000) : null, ink: onColour ? _white : null);
  }
  // iOS, for a plugin its keyboard has not (catalogue.dart `unavailable`): said, where Add would stand.
  if (plugin.unavailable != null) return MarketTag(label: 'Not on iPhone', ground: onColour ? const Color(0x33000000) : null, ink: onColour ? _white : null);
  if (added) return MarketAdded(name: plugin.name, color: onColour ? _white : null);
  return MarketButton(
    key: ValueKey('market-add-${plugin.id}'),
    label: 'Add',
    semantics: 'Add ${plugin.name}',
    icon: Lucide.plus,
    look: onColour ? MarketButtonLook.glass : MarketButtonLook.filled,
    color: pluginColours(plugin).$1,
    onPress: onAdd,
  );
}

/// What adding it costs, quietly: "Built in", or the download it makes.
class PluginCostLine extends StatelessWidget {
  const PluginCostLine({super.key, required this.plugin, this.color});

  final PluginManifest plugin;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final ink = color ?? t.color('app-ink-3');
    final downloads = plugin.permissions.any((p) => p.kind == Permission.download);
    return Text(
      plugin.soon != null ? 'Coming in ${plugin.soon}' : (plugin.unavailable != null ? 'Its page says why' : (downloads ? 'Downloads 60 MB once' : 'Built in, nothing to download')),
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: pageText(t, t.fontSize('xs'), weight: 'medium', color: ink),
    );
  }
}

/// One plugin on a shelf: its tile, name, line and Add; pressed, its page.
class MarketPluginCard extends StatelessWidget {
  const MarketPluginCard({super.key, required this.plugin, required this.added, required this.onAdd, required this.onOpen});

  final PluginManifest plugin;
  final bool added;
  final VoidCallback? onAdd;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return MarketCard(
      key: ValueKey('market-plugin-${plugin.id}'),
      onPress: onOpen,
      label: plugin.name,
      padding: EdgeInsets.all(t.space(3)),
      child: Row(
        children: [
          PluginTile(plugin: plugin, size: t.lenOf('3.4rem')),
          SizedBox(width: t.space(3)),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(plugin.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: pageText(t, t.fontSize('md'), weight: 'semibold', color: t.color('app-ink'))),
                SizedBox(height: t.lenOf('0.1rem')),
                Text(plugin.line, maxLines: 2, overflow: TextOverflow.ellipsis, style: pageText(t, t.fontSize('sm'), height: 1.35, color: t.color('app-ink-2'))),
                SizedBox(height: t.space(1)),
                PluginCostLine(plugin: plugin),
              ],
            ),
          ),
          SizedBox(width: t.space(2)),
          _addOrAdded(plugin, added: added, onAdd: onAdd),
        ],
      ),
    );
  }
}

/// A plugin at the head of the Market: the whole card in its colour, its mark large, its name and line in white.
class FeaturedPluginCard extends StatelessWidget {
  const FeaturedPluginCard({super.key, required this.plugin, required this.added, required this.onAdd, required this.onOpen});

  final PluginManifest plugin;
  final bool added;
  final VoidCallback? onAdd;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final radius = t.radius('lg') * 1.5;
    return GlacierPressable(
      key: ValueKey('market-featured-${plugin.id}'),
      onPressed: onOpen,
      semanticLabel: plugin.name,
      builder: (context, state) => AnimatedScale(
        duration: const Duration(milliseconds: 140),
        scale: state.pressed ? 0.98 : 1,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(radius),
          child: DecoratedBox(
            decoration: pluginTile(plugin, radius: radius),
            child: Stack(
              fit: StackFit.expand,
              children: [
                tileLight(radius: radius),
                Positioned(right: -t.space(6), top: -t.space(4), child: Opacity(opacity: 0.18, child: pluginMark(plugin, t.lenOf('9rem')))),
                Padding(
                  padding: EdgeInsets.all(t.space(4)),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _markChip(plugin, size: t.lenOf('2.6rem'), radius: t.lenOf('0.75rem'), markSize: t.lenOf('1.4rem')),
                      const Spacer(),
                      Text(plugin.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: pageText(t, t.fontSize('xl'), weight: 'bold', color: _white)),
                      SizedBox(height: t.lenOf('0.1rem')),
                      Text(plugin.line, maxLines: 2, overflow: TextOverflow.ellipsis, style: pageText(t, t.fontSize('sm'), height: 1.3, color: const Color(0xE6FFFFFF))),
                      SizedBox(height: t.space(3)),
                      Row(
                        children: [
                          Expanded(child: PluginCostLine(plugin: plugin, color: const Color(0xD9FFFFFF))),
                          SizedBox(width: t.space(2)),
                          _addOrAdded(plugin, added: added, onAdd: onAdd, onColour: true),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// A plugin's page in the Market: its colour across the head with its mark, name and line; Add or Remove; the way to
/// its page in Settings once added; what it does, what it adds, and what it may reach.
class PluginDetail extends StatelessWidget {
  const PluginDetail({super.key, required this.plugin, required this.added, required this.onSwitch, this.onSettings});

  final PluginManifest plugin;
  final bool added;
  final ValueChanged<bool> onSwitch;
  final VoidCallback? onSettings;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final (deep, bright) = pluginColours(plugin);
    final radius = t.radius('lg') * 1.5;
    final gap = t.space(4);
    final body = pageText(t, t.fontSize('md'), height: 1.45, color: t.color('app-ink'));
    // The servers it may reach: the guard's own list where the app has it (Net.kt's ledger), the catalogue's otherwise.
    final hosts = [for (final h in SettingsStore.current?.netHosts(plugin.id) ?? plugin.hosts) hostWords(h)];
    final quiet = pageText(t, t.fontSize('sm'), height: 1.4, color: t.color('app-ink-3'));
    final unavailable = plugin.unavailable;
    final status = plugin.soon != null ? 'Coming in ${plugin.soon}' : (unavailable != null ? 'Not on iPhone' : (added ? 'Added' : 'Not added'));

    Widget section(String title, List<Widget> children) => Padding(
          padding: EdgeInsets.only(top: gap * 1.5),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Semantics(header: true, child: Text(title, style: pageText(t, t.fontSize('md'), weight: 'semibold', color: t.color('app-ink')))),
              SizedBox(height: t.space(2)),
              ...children,
            ],
          ),
        );

    Widget point(LucideIcon icon, String label, {String? hint}) => Padding(
          padding: EdgeInsets.only(top: t.space(2)),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: t.lenOf('1.9rem'),
                height: t.lenOf('1.9rem'),
                decoration: BoxDecoration(color: deep.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(t.lenOf('0.55rem'))),
                alignment: Alignment.center,
                child: AppIcon(icon, size: 15, strokeWidth: 2.2, color: t.isDark ? bright : deep, wash: false),
              ),
              SizedBox(width: t.space(3)),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(padding: EdgeInsets.only(top: t.lenOf('0.2rem')), child: Text(label, style: pageText(t, t.fontSize('md'), weight: hint == null ? 'regular' : 'medium', height: 1.35, color: t.color('app-ink')))),
                    if (hint != null) Padding(padding: EdgeInsets.only(top: t.lenOf('0.15rem')), child: Text(hint, style: quiet)),
                  ],
                ),
              ),
            ],
          ),
        );

    return Column(
      key: ValueKey('market-detail-${plugin.id}'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(radius),
          child: DecoratedBox(
            decoration: pluginTile(plugin, radius: radius),
            child: Stack(
              children: [
                Positioned.fill(child: tileLight(radius: radius)),
                Positioned(right: -t.space(8), bottom: -t.space(10), child: Opacity(opacity: 0.16, child: pluginMark(plugin, t.lenOf('12rem')))),
                Padding(
                  padding: EdgeInsets.all(t.space(5)),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _markChip(plugin, size: t.lenOf('3.6rem'), radius: t.lenOf('1rem'), markSize: t.lenOf('1.9rem')),
                      SizedBox(height: t.space(4)),
                      Semantics(header: true, child: Text(plugin.name, style: pageText(t, t.fontSize('2xl'), weight: 'bold', color: _white))),
                      SizedBox(height: t.space(1)),
                      Text(plugin.line, style: pageText(t, t.fontSize('md'), height: 1.35, color: const Color(0xE6FFFFFF))),
                      SizedBox(height: t.space(3)),
                      Wrap(
                        spacing: t.space(2),
                        runSpacing: t.space(2),
                        children: [
                          MarketTag(label: status, ground: const Color(0x33000000), ink: _white),
                          MarketTag(label: plugin.category.label, ground: const Color(0x26FFFFFF), ink: _white),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        SizedBox(height: gap),
        Wrap(
          spacing: t.space(2),
          runSpacing: t.space(2),
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            MarketButton(
              key: ValueKey(added ? 'market-remove-${plugin.id}' : 'market-add-${plugin.id}'),
              label: plugin.soon != null ? 'Coming in ${plugin.soon}' : (unavailable != null ? 'Not on iPhone' : (added ? 'Remove' : 'Add to Ghost Keyboard')),
              semantics: '${added ? 'Remove' : 'Add'} ${plugin.name}',
              icon: added ? Lucide.minus : Lucide.plus,
              look: added ? MarketButtonLook.ring : MarketButtonLook.filled,
              color: deep,
              large: true,
              onPress: plugin.soon != null || unavailable != null ? null : () => onSwitch(!added),
            ),
            if (added && onSettings != null && unavailable == null)
              MarketButton(label: 'Settings', semantics: '${plugin.name} settings', icon: Lucide.settings, look: MarketButtonLook.ring, large: true, onPress: onSettings),
          ],
        ),
        SizedBox(height: t.space(2)),
        Text(
          plugin.soon != null
              ? 'Listed now so you know it is on its way.'
              : unavailable ?? (added
                  ? 'Removing it keeps its settings: add it again and it is as it was.'
                  : 'Built in: adding it is a switch.'),
          style: quiet,
        ),
        // What follows describes Android's plugin: left unsaid for one this phone's keyboard has not.
        if (unavailable == null) ...[
        section('What it does', [Text(plugin.description, style: body)]),
        if (plugin.adds.isNotEmpty || plugin.hasPage)
          section('What it adds', [
            for (final add in plugin.adds) point(Lucide.sparkles, add),
            if (plugin.hasPage) point(Lucide.settings, 'A page of its own in Settings', hint: added ? null : 'Among Settings’ own pages, once it is added.'),
          ]),
        section('What it may reach', [
          if (plugin.permissions.isEmpty) point(Lucide.shieldCheck, 'Nothing past the keyboard', hint: 'It asks for nothing: it works on what you type, on your phone.'),
          for (final permission in plugin.permissions)
            point(
              permissionIcon(permission.kind),
              plainPermissionWords[permission.kind]!,
              hint: permission.kind == Permission.network && hosts.isNotEmpty ? '${permission.why} It talks to ${joinAnd(hosts)} and nowhere else.' : permission.why,
            ),
        ]),
        ],
        Padding(
          padding: EdgeInsets.only(top: gap * 1.5),
          child: Text('${plugin.category.label} · ${plugin.author} · version ${plugin.version}', style: quiet),
        ),
      ],
    );
  }
}
