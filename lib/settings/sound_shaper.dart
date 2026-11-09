/// Settings › Sound packs › Shape the sounds: each kind of key sound (keys, space, delete, enter) put through its own
/// filters, the keyboard's SoundPacks.kt doing the work: volume, bass, treble, tone (a low-pass that rounds the top
/// off), pitch and length. Kept as one setting, `sound_filters`, JSON by kind.
library;

import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../settings_store.dart';
import 'kit/settings_kit.dart';

/// One filter: its key in the JSON, its name, range, the value that leaves the sound alone, and how it reads.
class _Knob {
  const _Knob(this.key, this.label, this.min, this.max, this.flat, this.readout);
  final String key;
  final String label;
  final double min;
  final double max;
  final double flat;
  final String Function(double) readout;
}

String _db(double v) => '${v > 0 ? '+' : ''}${v.toStringAsFixed(0)} dB';

final _knobs = [
  _Knob('gain', 'Volume', -18, 6, 0, _db),
  _Knob('bass', 'Bass', -12, 12, 0, _db),
  _Knob('treble', 'Treble', -12, 12, 0, _db),
  _Knob('tone', 'Tone', 800, 16000, 16000, (v) => v >= 15999 ? 'Open' : '${(v / 1000).toStringAsFixed(1)} kHz'),
  _Knob('pitch', 'Pitch', -12, 12, 0, (v) => '${v > 0 ? '+' : ''}${v.toStringAsFixed(0)} st'),
  _Knob('length', 'Length', 0.2, 1, 1, (v) => '${(v * 100).round()}%'),
];

const _kinds = {'key': 'Keys', 'space': 'Space', 'delete': 'Delete', 'enter': 'Enter'};

/// Ready-made shapes for every kind at once.
final Map<String, Map<String, double>> _presets = {
  'Warm': {'bass': 6, 'treble': -5, 'tone': 7000},
  'Deep': {'bass': 9, 'treble': -8, 'tone': 4500, 'pitch': -3},
  'Soft': {'gain': -8, 'treble': -6, 'tone': 6000, 'length': 0.6},
  'Crisp': {'bass': -2, 'treble': 4},
  'Tiny': {'gain': -4, 'pitch': 5, 'length': 0.5},
};

class SoundShaper extends StatefulWidget {
  const SoundShaper({super.key, required this.store});
  final SettingsStore store;

  @override
  State<SoundShaper> createState() => _SoundShaperState();
}

class _SoundShaperState extends State<SoundShaper> {
  String _kind = 'key';
  final Map<String, double> _dragging = {};

  Map<String, Map<String, double>> get _all {
    final raw = widget.store.choice('sound_filters');
    try {
      final o = jsonDecode(raw.isEmpty ? '{}' : raw) as Map<String, dynamic>;
      return {
        for (final e in o.entries)
          if (e.value is Map) e.key: {for (final f in (e.value as Map).entries) '${f.key}': (f.value as num).toDouble()},
      };
    } catch (_) {
      return {};
    }
  }

  double _value(_Knob k) => _dragging[k.key] ?? _all[_kind]?[k.key] ?? k.flat;

  Future<void> _save(Map<String, Map<String, double>> all) async {
    // Leave out what is flat, so an untouched sound stays exactly as made.
    final clean = <String, Map<String, double>>{};
    for (final e in all.entries) {
      final knobs = <String, double>{};
      for (final k in _knobs) {
        final v = e.value[k.key];
        if (v != null && (v - k.flat).abs() > 0.001) knobs[k.key] = v;
      }
      if (knobs.isNotEmpty) clean[e.key] = knobs;
    }
    await widget.store.set('sound_filters', clean.isEmpty ? '' : jsonEncode(clean));
  }

  Future<void> _set(_Knob k, double v) async {
    final all = _all;
    (all[_kind] ??= {})[k.key] = v;
    await _save(all);
    _dragging.remove(k.key);
    widget.store.previewSound(_kind);
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final shaped = _all.isNotEmpty;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        PaneSection(
          title: 'Shape the sounds',
          footer: const Text('Each kind of sound has its own filters. Let go of a slider to hear it; the keyboard uses it from the next key.'),
          children: [
            SettingRowBox(
              child: GlacierSegmentedControl(
                options: [for (final e in _kinds.entries) GlacierSegmentedOption(value: e.key, label: Text(e.value))],
                value: _kind,
                onValueChange: (v) => setState(() => _kind = v),
                fullWidth: true,
                size: GlacierSegmentedSize.sm,
                ariaLabel: 'Which sound',
              ),
            ),
            for (final k in _knobs)
              SettingRowBox(
                child: _Slider(
                  label: k.label,
                  readout: k.readout(_value(k)),
                  value: _value(k),
                  min: k.min,
                  max: k.max,
                  onChanged: (v) => setState(() => _dragging[k.key] = v),
                  onChangeEnd: (v) => _set(k, v),
                ),
              ),
            SettingRow(label: 'Play ${_kinds[_kind]!.toLowerCase()}', onPress: () => widget.store.previewSound(_kind)),
            SettingRow(
              label: 'Reset ${_kinds[_kind]!.toLowerCase()}',
              onPress: (_all[_kind]?.isNotEmpty ?? false) ? () async { final all = _all..remove(_kind); await _save(all); widget.store.previewSound(_kind); } : null,
            ),
          ],
        ),
        SizedBox(height: t.space(4)),
        PaneSection(
          title: 'Quick shapes',
          footer: const Text('A shape for every sound at once; adjust from there.'),
          children: [
            for (final e in _presets.entries)
              SettingRow(
                label: e.key,
                onPress: () async {
                  await _save({for (final kind in _kinds.keys) kind: Map.of(e.value)});
                  widget.store.previewSound(_kind);
                },
              ),
            if (shaped) SettingRow(label: 'Back to the pack as made', onPress: () async { await _save({}); widget.store.previewSound(_kind); }),
          ],
        ),
      ],
    );
  }
}

/// A labelled slider in the kit's colours: track, filled part, round thumb.
class _Slider extends StatelessWidget {
  const _Slider({required this.label, required this.readout, required this.value, required this.min, required this.max, required this.onChanged, required this.onChangeEnd});

  final String label;
  final String readout;
  final double value;
  final double min;
  final double max;
  final ValueChanged<double> onChanged;
  final ValueChanged<double> onChangeEnd;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    const thumb = 24.0;
    const track = 6.0;
    return Semantics(
      slider: true,
      label: label,
      value: readout,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(children: [
            Text(label, style: TextStyle(fontSize: 14, color: t.color('glacier-text'))),
            const Spacer(),
            Text(readout, style: TextStyle(fontSize: 13, color: t.color('glacier-text-muted'), fontFeatures: const [FontFeature.tabularFigures()])),
          ]),
          SizedBox(height: t.space(1)),
          LayoutBuilder(builder: (context, box) {
            final usable = (box.maxWidth - thumb).clamp(1.0, double.infinity);
            double at(double dx) => min + ((dx - thumb / 2) / usable).clamp(0.0, 1.0) * (max - min);
            final f = ((value - min) / (max - min)).clamp(0.0, 1.0);
            double last = value;
            return GestureDetector(
              behavior: HitTestBehavior.opaque,
              dragStartBehavior: DragStartBehavior.down,
              onTapUp: (d) => onChangeEnd(at(d.localPosition.dx)),
              onHorizontalDragUpdate: (d) { last = at(d.localPosition.dx); onChanged(last); },
              onHorizontalDragEnd: (_) { HapticFeedback.selectionClick(); onChangeEnd(last); },
              child: SizedBox(
                height: thumb + 4,
                child: Stack(children: [
                  Positioned(
                    left: thumb / 2, right: thumb / 2, top: (thumb + 4 - track) / 2, height: track,
                    child: DecoratedBox(decoration: BoxDecoration(color: t.color('glacier-border'), borderRadius: BorderRadius.circular(track))),
                  ),
                  Positioned(
                    left: thumb / 2, width: f * usable, top: (thumb + 4 - track) / 2, height: track,
                    child: DecoratedBox(decoration: BoxDecoration(color: t.color('glacier-accent-solid'), borderRadius: BorderRadius.circular(track))),
                  ),
                  Positioned(
                    left: f * usable, top: 2, width: thumb, height: thumb,
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: const Color(0xFFFFFFFF),
                        shape: BoxShape.circle,
                        boxShadow: const [BoxShadow(color: Color(0x40000000), blurRadius: 4, offset: Offset(0, 1))],
                        border: Border.all(color: t.color('glacier-border-strong')),
                      ),
                    ),
                  ),
                ]),
              ),
            );
          }),
        ],
      ),
    );
  }
}
