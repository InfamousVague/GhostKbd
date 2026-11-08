/// A field of colour with a sign in the middle and glass tiles drifting round it: Ghost.md's walkthrough band (its
/// lib/guide/pages/band.dart `HueBand`), which is also its Market's head. Ported without Ghost.md's dotwork ghost and
/// Claude's mark; the drift's pace is fixed (six seconds a turn) and stills for a person who asked for less motion.
library;

import 'dart:math' as math;

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../art/app_icon.dart';
import '../market/market_plugins.dart' show marketHues, tileLight;

const Color _white = Color(0xFFFFFFFF);

/// Each hue's deep shade, by the names [marketHues] keeps.
List<Color> hueColours(List<String> hues) => [for (final hue in hues) marketHues[hue]!.$1];

/// Where each tile floats: its centre across and down (0 to 1), its size as a share of the band's height, its turn.
const List<(double, double, double, double)> _spots = [
  (0.16, 0.24, 0.24, -0.12),
  (0.85, 0.30, 0.20, 0.10),
  (0.82, 0.78, 0.22, -0.06),
  (0.14, 0.76, 0.18, 0.14),
];

/// A tile's mark: [icon] in white, half the tile across.
Widget Function(double size) bandIcon(LucideIcon icon) => (size) => AppIcon(icon, size: size * 0.5, strokeWidth: 2.2, color: _white);

/// The usual sign: a large [icon] on a disc of glass.
Widget bandDisc(LucideIcon icon, {required double height, required double width}) {
  final side = math.min(height * 0.46, width * 0.36);
  return Container(
    width: side,
    height: side,
    alignment: Alignment.center,
    decoration: BoxDecoration(
      shape: BoxShape.circle,
      color: const Color(0x33FFFFFF),
      border: Border.all(color: const Color(0x59FFFFFF), width: 1.5),
      boxShadow: const [BoxShadow(color: Color(0x33000000), blurRadius: 30, offset: Offset(0, 12))],
    ),
    child: AppIcon(icon, size: side * 0.5, strokeWidth: 2, color: _white),
  );
}

class HueBand extends StatefulWidget {
  const HueBand({super.key, required this.colours, required this.height, required this.sign, this.tiles = const []});

  final List<Color> colours;
  final double height;
  final Widget Function(double height, double width) sign;
  final List<Widget Function(double size)> tiles;

  @override
  State<HueBand> createState() => _HueBandState();
}

class _HueBandState extends State<HueBand> with SingleTickerProviderStateMixin {
  late final AnimationController _drift = AnimationController(vsync: this, duration: const Duration(seconds: 6));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (context.tokens.reducedMotion) {
      _drift
        ..stop()
        ..value = 0;
    } else if (!_drift.isAnimating) {
      _drift.repeat();
    }
  }

  @override
  void dispose() {
    _drift.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final height = widget.height;
    final radius = t.radius('lg') * 1.6;
    final colours = widget.colours;
    final tiles = widget.tiles.take(_spots.length).toList();
    return ExcludeSemantics(
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: SizedBox(
          height: height,
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: colours,
                stops: [for (var i = 0; i < colours.length; i++) i / (colours.length - 1)],
              ),
            ),
            child: LayoutBuilder(
              builder: (context, box) {
                final width = box.maxWidth;
                return Stack(
                  clipBehavior: Clip.none,
                  children: [
                    Positioned.fill(child: tileLight(radius: radius)),
                    for (var i = 0; i < tiles.length; i++) _tile(i, tiles[i], width: width, height: height),
                    Center(child: _breathing(widget.sign(height, width))),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
  }

  Widget _tile(int i, Widget Function(double) mark, {required double width, required double height}) {
    final (x, y, share, turn) = _spots[i];
    final size = math.min(height * share, width * 0.2);
    final glass = Transform.rotate(
      angle: turn,
      child: Container(
        width: size,
        height: size,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: const Color(0x2EFFFFFF),
          borderRadius: BorderRadius.circular(size * 0.28),
          border: Border.all(color: const Color(0x40FFFFFF)),
          boxShadow: const [BoxShadow(color: Color(0x26000000), blurRadius: 14, offset: Offset(0, 6))],
        ),
        child: mark(size),
      ),
    );
    return Positioned(
      left: x * width - size / 2,
      top: y * height - size / 2,
      child: AnimatedBuilder(
        animation: _drift,
        child: glass,
        builder: (context, child) => Transform.translate(offset: Offset(0, 5 * math.sin(2 * math.pi * (_drift.value + i * 0.23))), child: child),
      ),
    );
  }

  Widget _breathing(Widget sign) => AnimatedBuilder(
        animation: _drift,
        child: sign,
        builder: (context, child) => Transform.scale(scale: 1 + 0.025 * math.sin(2 * math.pi * _drift.value), child: child),
      );
}
