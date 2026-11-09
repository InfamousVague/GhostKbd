/// The Themes plugin's page: the presets as cards, each a small keyboard in its colours, and a theme of one's own made
/// from one colour picked with Glacier's colour picker (deriveTheme works out the
/// five colours the keyboard draws). The keyboard draws whichever is chosen (KeyboardTheme.kt).
library;

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../market/market_cards.dart';
import '../plugins/catalogue.dart';
import '../settings_store.dart';
import 'kit/settings_kit.dart';
import 'kit/settings_type.dart';

List<int> _parse(String spec) {
  final colours = spec.split(',').map((h) => int.tryParse(h.trim().replaceFirst('#', ''), radix: 16)).toList();
  if (colours.length != 5 || colours.any((c) => c == null)) return themePresets.first.$3;
  return [for (final c in colours) 0xFF000000 | c!];
}

String _hex(int c) => '#${(c & 0xFFFFFF).toRadixString(16).padLeft(6, '0').toUpperCase()}';

class ThemeEditor extends StatelessWidget {
  const ThemeEditor({super.key, required this.store});
  final SettingsStore store;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final chosen = store.choice('theme_preset');
    final own = _parse(store.choice('custom_theme'));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        PaneSection(
          title: 'Presets',
          description: const Text('Gboard follows Light or dark in Look & layout; the others are their own.'),
          children: [
            SettingRowBox(
              child: MarketGrid(
                  minWidth: 140,
                  most: 3,
                  children: [
                    for (final (id, name, colours) in themePresets) _ThemeCard(name: name, colours: colours, selected: chosen == id, onPress: () => store.set('theme_preset', id)),
                    _ThemeCard(name: 'Your own', colours: own, selected: chosen == 'custom', onPress: () => store.set('theme_preset', 'custom')),
                  ],
              ),
            ),
          ],
        ),
        SizedBox(height: t.space(4)),
        PaneSection(
          title: 'Your own theme',
          footer: const Text('Pick one colour: the keyboard, its keys and the function keys are drawn from it, and the letters take a faint touch of it too.'),
          children: [
            SettingRow(
              label: 'Look',
              layout: SettingRowLayout.stacked,
              control: GlacierSegmentedControl(
                options: const [GlacierSegmentedOption(value: 'dark', label: Text('Dark')), GlacierSegmentedOption(value: 'light', label: Text('Light'))],
                value: store.choice('theme_mode'),
                onValueChange: (v) => _apply(store, mode: v),
                fullWidth: true,
                size: GlacierSegmentedSize.sm,
                ariaLabel: 'Light or dark',
              ),
            ),
            SettingRowBox(
              child: GlacierColorPicker(
                value: store.choice('theme_tint'),
                presets: _tints,
                size: GlacierColorPickerSize.sm,
                onChangeEnd: (hex) => _apply(store, tint: hex),
              ),
            ),
            SettingRow(
              label: 'Accent matches the colour',
              hint: const Text('Off: pick the enter key’s and the popups’ colour yourself.'),
              control: GlacierSwitch(
                checked: store.choice('theme_accent').isEmpty,
                onCheckedChange: (on) => _apply(store, accent: on ? '' : store.choice('theme_tint')),
                ariaLabel: 'Accent matches the colour',
              ),
            ),
            if (store.choice('theme_accent').isNotEmpty)
              SettingRowBox(
                child: GlacierColorPicker(
                  value: store.choice('theme_accent'),
                  presets: _tints,
                  size: GlacierColorPickerSize.sm,
                  onChangeEnd: (hex) => _apply(store, accent: hex),
                ),
              ),
          ],
        ),
      ],
    );
  }
}

/// Swatches under the picker: the presets' accents and a few more.
const _tints = ['#7CC4FA', '#8AB4F8', '#BD93F9', '#88C0D0', '#B58900', '#A6E22E', '#F28B82', '#F6AE2D', '#5B7CFA', '#2BB673'];

/// Writes the theme of one's own from its colour, accent and look, and chooses it.
void _apply(SettingsStore store, {String? tint, String? accent, String? mode}) {
  if (tint != null) store.set('theme_tint', tint);
  if (accent != null) store.set('theme_accent', accent);
  if (mode != null) store.set('theme_mode', mode);
  final colours = deriveTheme(
    _parseColour(tint ?? store.choice('theme_tint')) ?? const Color(0xFF7CC4FA),
    accent: _parseColour(accent ?? store.choice('theme_accent')),
    dark: (mode ?? store.choice('theme_mode')) != 'light',
  );
  store.set('custom_theme', colours.map(_hex).join(','));
  store.set('theme_preset', 'custom');
}

Color? _parseColour(String text) {
  final v = int.tryParse(text.trim().replaceFirst('#', ''), radix: 16);
  return v == null || text.trim().replaceFirst('#', '').length != 6 ? null : Color(0xFF000000 | v);
}

/// The five colours the keyboard draws (KeyboardTheme.fromBase's order: keyboard, keys, function keys, accent,
/// letters) from one colour, in OKLCH so every hue comes out equally light. Surfaces keep only a little of its
/// chroma; the letters keep a trace (0.018), enough to sit in the colour without reading as coloured text.
List<int> deriveTheme(Color tint, {Color? accent, bool dark = true}) {
  final c = Oklch.fromColor(tint);
  final h = c.achromatic ? 250.0 : c.h;
  double cap(double max) => c.c < max ? c.c : max;
  final Oklch background, key, function, text;
  if (dark) {
    background = Oklch(0.17, cap(0.03), h);
    key = Oklch(0.285, cap(0.035), h);
    function = Oklch(0.225, cap(0.03), h);
    text = Oklch(0.95, c.c < 0.02 ? c.c : 0.018, h);
  } else {
    background = Oklch(0.925, cap(0.025), h);
    key = Oklch(0.995, cap(0.008), h);
    function = Oklch(0.85, cap(0.03), h);
    text = Oklch(0.24, c.c < 0.02 ? c.c : 0.02, h);
  }
  final a = accent ?? tint;
  return [for (final o in [background, key, function]) o.toColor().toARGB32(), a.toARGB32() | 0xFF000000, text.toColor().toARGB32()];
}

/// A theme as a small keyboard: three rows of keys and a space bar in its colours, under its name.
class _ThemeCard extends StatelessWidget {
  const _ThemeCard({required this.name, required this.colours, required this.selected, required this.onPress});

  final String name;
  final List<int> colours;
  final bool selected;
  final VoidCallback onPress;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return MarketCard(
      selected: selected,
      onPress: onPress,
      label: name,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          AspectRatio(aspectRatio: 1.6, child: CustomPaint(painter: _MiniKeyboard(colours))),
          Padding(
            padding: EdgeInsets.all(t.space(2)),
            child: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis, style: pageText(t, t.fontSize('sm'), weight: 'medium', color: t.color('app-ink'))),
          ),
        ],
      ),
    );
  }
}

class _MiniKeyboard extends CustomPainter {
  _MiniKeyboard(this.c);
  final List<int> c;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = Color(c[0]));
    final pad = size.width * 0.04;
    final rowH = (size.height - pad * 2) / 4;
    final unit = (size.width - pad * 2) / 10;
    final r = Radius.circular(unit * 0.18);
    void key(double x, double y, double w, int colour) => canvas.drawRRect(
          RRect.fromRectAndRadius(Rect.fromLTWH(pad + x * unit + unit * 0.08, pad + y * rowH + rowH * 0.12, w * unit - unit * 0.16, rowH * 0.76), r),
          Paint()..color = Color(colour),
        );
    for (var i = 0; i < 10; i++) {
      key(i.toDouble(), 0, 1, c[1]);
    }
    for (var i = 0; i < 9; i++) {
      key(0.5 + i, 1, 1, c[1]);
    }
    key(0, 2, 1.5, c[2]);
    for (var i = 0; i < 7; i++) {
      key(1.5 + i, 2, 1, c[1]);
    }
    key(8.5, 2, 1.5, c[2]);
    key(0, 3, 1.5, c[2]);
    key(1.5, 3, 1, c[1]);
    key(2.5, 3, 5, c[1]);
    key(7.5, 3, 1, c[1]);
    key(8.5, 3, 1.5, c[3]);
    // A letter on the first key, in the ink.
    canvas.drawCircle(Offset(pad + unit * 0.5, pad + rowH * 0.5), unit * 0.12, Paint()..color = Color(c[4]));
  }

  @override
  bool shouldRepaint(_MiniKeyboard old) => old.c != c;
}
