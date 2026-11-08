/// The Themes plugin's page: the presets as cards, each a small keyboard in its colours, and a theme of one's own made
/// from five colours. The keyboard draws whichever is chosen (KeyboardTheme.kt), from the same ids and colours.
library;

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../market/market_cards.dart';
import '../plugins/catalogue.dart';
import '../settings_store.dart';
import 'kit/settings_kit.dart';
import 'kit/settings_type.dart';

const _parts = ['Keyboard', 'Keys', 'Function keys', 'Accent', 'Letters'];

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
          footer: const Text('Colours as #RRGGBB. The keyboard works out the pressed keys, the hints and the popups from these five.'),
          children: [
            for (var i = 0; i < 5; i++)
              SettingRow(
                label: _parts[i],
                icon: Container(width: 22, height: 22, decoration: BoxDecoration(color: Color(own[i]), borderRadius: BorderRadius.circular(6), border: Border.all(color: t.color('app-rule')))),
                control: SizedBox(
                  width: 120,
                  child: GlacierInput(
                    key: ValueKey('theme-part-$i-${own[i]}'),
                    size: GlacierFieldSize.sm,
                    defaultValue: _hex(own[i]),
                    autoCapitalize: TextCapitalization.characters,
                    ariaLabel: '${_parts[i]} colour',
                    onSubmitted: (text) {
                      final v = int.tryParse(text.trim().replaceFirst('#', ''), radix: 16);
                      if (v == null || text.trim().replaceFirst('#', '').length != 6) return;
                      final next = [...own]..[i] = 0xFF000000 | v;
                      store.set('custom_theme', next.map(_hex).join(','));
                      store.set('theme_preset', 'custom');
                    },
                  ),
                ),
              ),
          ],
        ),
      ],
    );
  }
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
