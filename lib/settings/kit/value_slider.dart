/// A slider in Glacier's look, for a setting that is a number (Settings › Typing › Backspace momentum). Glacier has none,
/// and the app draws nothing from Material, so this one is drawn here: a track in the border colour, the part up to
/// the thumb in the accent, a white thumb as the switch's, and over it what the value means as it moves.
library;

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

class ValueSlider extends StatefulWidget {
  const ValueSlider({
    super.key,
    required this.value,
    required this.min,
    required this.max,
    required this.onChanged,
    required this.describe,
    this.ariaLabel,
    this.marks = const [],
  });

  final int value;
  final int min;
  final int max;

  /// The value let go on: saved once, not at every step of the drag.
  final ValueChanged<int> onChanged;

  /// What the value says while it moves: its number and, where it has one, the zone it is in ("20 a second", "Brisk").
  final (String, String?) Function(int value) describe;
  final String? ariaLabel;

  /// Small ticks under the track (the keyboard's own default, say).
  final List<int> marks;

  @override
  State<ValueSlider> createState() => _ValueSliderState();
}

class _ValueSliderState extends State<ValueSlider> {
  int? _dragging;

  int get _shown => _dragging ?? widget.value.clamp(widget.min, widget.max);

  int _valueAt(double dx, double width) {
    final f = (dx / width).clamp(0.0, 1.0);
    return (widget.min + f * (widget.max - widget.min)).round();
  }

  void _move(double dx, double width) {
    final v = _valueAt(dx, width);
    if (v != _dragging) {
      HapticFeedback.selectionClick();
      setState(() => _dragging = v);
    }
  }

  void _end() {
    final v = _dragging;
    setState(() => _dragging = null);
    if (v != null && v != widget.value) widget.onChanged(v);
  }

  void _nudge(int by) {
    final v = (widget.value + by).clamp(widget.min, widget.max);
    if (v != widget.value) widget.onChanged(v);
  }

  @override
  Widget build(BuildContext context) {
    final t = GlacierTheme.of(context);
    final (number, zone) = widget.describe(_shown);
    final accent = t.color('glacier-accent-solid');
    final track = t.color('glacier-border');
    final text = t.color('glacier-text');
    final muted = t.color('glacier-text-muted');
    final thumb = t.lenOf('1.375rem');
    final span = (widget.max - widget.min).toDouble();

    return Semantics(
      label: widget.ariaLabel,
      value: zone == null ? number : '$number, $zone',
      increasedValue: widget.describe((_shown + 1).clamp(widget.min, widget.max)).$1,
      decreasedValue: widget.describe((_shown - 1).clamp(widget.min, widget.max)).$1,
      onIncrease: () => _nudge(1),
      onDecrease: () => _nudge(-1),
      slider: true,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Text(number, style: TextStyle(color: text, fontSize: 15, fontWeight: FontWeight.w600, fontFeatures: const [FontFeature.tabularFigures()])),
              const Spacer(),
              if (zone != null) Text(zone, style: TextStyle(color: _dragging == null ? muted : accent, fontSize: 13, fontWeight: FontWeight.w500)),
            ],
          ),
          SizedBox(height: t.lenOf('0.5rem')),
          LayoutBuilder(builder: (context, box) {
            final width = box.maxWidth - thumb;
            final f = span == 0 ? 0.0 : (_shown - widget.min) / span;
            return GestureDetector(
              behavior: HitTestBehavior.opaque,
              onHorizontalDragStart: (d) => _move(d.localPosition.dx - thumb / 2, width),
              onHorizontalDragUpdate: (d) => _move(d.localPosition.dx - thumb / 2, width),
              onHorizontalDragEnd: (_) => _end(),
              onHorizontalDragCancel: _end,
              onTapDown: (d) => _move(d.localPosition.dx - thumb / 2, width),
              onTapUp: (_) => _end(),
              onTapCancel: _end,
              child: SizedBox(
                height: thumb + 10,
                child: Stack(
                  clipBehavior: Clip.none,
                  children: [
                    // The track, and the part of it up to the thumb.
                    Positioned(
                      left: thumb / 2,
                      right: thumb / 2,
                      top: (thumb + 10) / 2 - 2,
                      height: 4,
                      child: DecoratedBox(decoration: BoxDecoration(color: track, borderRadius: BorderRadius.circular(2))),
                    ),
                    Positioned(
                      left: thumb / 2,
                      width: width * f,
                      top: (thumb + 10) / 2 - 2,
                      height: 4,
                      child: DecoratedBox(decoration: BoxDecoration(color: accent, borderRadius: BorderRadius.circular(2))),
                    ),
                    for (final m in widget.marks)
                      if (span > 0)
                        Positioned(
                          left: thumb / 2 + width * (m - widget.min) / span - 1,
                          top: (thumb + 10) / 2 + 5,
                          width: 2,
                          height: 5,
                          child: DecoratedBox(decoration: BoxDecoration(color: muted, borderRadius: BorderRadius.circular(1))),
                        ),
                    Positioned(
                      left: width * f,
                      top: 5,
                      width: thumb,
                      height: thumb,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: t.colorOf('oklch(0.995 0 0)'),
                          shape: BoxShape.circle,
                          border: Border.all(color: accent, width: 2),
                          boxShadow: const [BoxShadow(color: Color(0x33000000), blurRadius: 4, offset: Offset(0, 1))],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          }),
        ],
      ),
    );
  }
}
