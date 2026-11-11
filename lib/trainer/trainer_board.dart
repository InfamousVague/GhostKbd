/// The trainer's pictures (Matt, 2026-11-11: "Show inforgraphics on the trainer with charts and stuff to show where
/// the mistakes happen and what has shifted where using a simplified keyboard display"): the keyboard drawn simply,
/// its letter keys only, in two ways. Tinted by how often each key is missed, with an arrow from a key to the one hit
/// in its place; and with a ring where the taps meant for each key land and an arrow for how far its touch area has
/// moved. And the runs as two lines: the share of taps on their key, and of words right first time; and the runs that
/// tested a fix as two more, the same taps read by the fix and by the keyboard as it is ([FixFigure]).
///
/// The keyboard measures all of it (Android's TrainerMaps.board); these only draw what they are told.
library;

import 'dart:math' as math;

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../settings/kit/settings_kit.dart';
import '../settings/kit/settings_type.dart';
import 'trainer_fix.dart';

double _num(Object? v) => v is num && v.isFinite ? v.toDouble() : 0;
int _whole(Object? v) => v is num && v.isFinite ? v.toInt() : 0;

/// A share as "12%"; one that rounds to nothing as "under 1%".
String _share(double rate) {
  final n = (rate * 100).round();
  return n < 1 ? 'under 1%' : '$n%';
}

/// An offset in fractions of a key, in words: "12% right and 6% low".
String offsetSaid(double dx, double dy) {
  final x = (dx * 100).round();
  final y = (dy * 100).round();
  final parts = [if (x != 0) '${x.abs()}% ${x > 0 ? 'right' : 'left'}', if (y != 0) '${y.abs()}% ${y > 0 ? 'low' : 'high'}'];
  return parts.isEmpty ? 'a hair' : parts.join(' and ');
}

/// One letter key as the pictures know it.
class BoardKey {
  const BoardKey({
    required this.key,
    required this.x,
    required this.row,
    this.taps = 0,
    this.missed = 0,
    this.landX = 0,
    this.landY = 0,
    this.landed = 0,
    this.shiftX = 0,
    this.shiftY = 0,
    this.moved = false,
    this.instead,
  });

  final String key;

  /// Its left edge, in key widths from the board's left; its [row] counts down from the top.
  final double x;
  final int row;

  /// How often it was meant, and how many of those taps missed it.
  final int taps;
  final int missed;

  /// Where the taps meant for it land about its middle, in fractions of the key, on [landed] taps (0: too few to say).
  final double landX;
  final double landY;
  final int landed;

  /// Where it listens, from its middle, in fractions of the key: in the fix the picture is of, or on the keyboard as
  /// it is where no fix has moved it. [moved] where the fix has it somewhere else than the keyboard as it is.
  final double shiftX;
  final double shiftY;
  final bool moved;

  /// The key hit most often in its place, as the keyboard's own lists name it; null where none, or it was not said.
  final String? instead;

  double get rate => taps <= 0 ? 0 : missed / taps;
  bool get shifted => shiftX != 0 || shiftY != 0;
  double get shift => math.sqrt(shiftX * shiftX + shiftY * shiftY);
}

/// The key [pressed] where [meant] was meant, [count] times.
class BoardSlip {
  const BoardSlip(this.pressed, this.meant, this.count);
  final String pressed;
  final String meant;
  final int count;
}

/// The keyboard as the trainer measured it: what [BoardFigure] draws.
class TrainerBoardData {
  const TrainerBoardData({required this.rows, required this.units, this.slips = const [], this.maxShift = 0.3, this.lean});

  final List<List<BoardKey>> rows;

  /// How many key widths the widest row runs to.
  final double units;
  final List<BoardSlip> slips;

  /// The furthest a touch area is ever moved, in fractions of a key.
  final double maxShift;

  /// Which way the taps lean on the whole, in a sentence; null where that cannot be said yet.
  final String? lean;

  /// What the keyboard sent (TrainerMaps.board); null where it sent no keys.
  static TrainerBoardData? of(Object? raw) {
    if (raw is! Map) return null;
    final rows = <List<BoardKey>>[];
    final given = raw['rows'];
    if (given is List) {
      for (final row in given) {
        if (row is! List) continue;
        final keys = <BoardKey>[
          for (final k in row)
            if (k is Map && '${k['key'] ?? ''}'.isNotEmpty)
              BoardKey(
                key: '${k['key']}',
                x: _num(k['x']),
                row: rows.length,
                taps: math.max(0, _whole(k['taps'])),
                missed: math.max(0, math.min(_whole(k['missed']), _whole(k['taps']))),
                landX: _num(k['landX']),
                landY: _num(k['landY']),
                landed: math.max(0, _whole(k['landed'])),
                shiftX: _num(k['shiftX']),
                shiftY: _num(k['shiftY']),
                moved: k['moved'] == true,
                instead: k['instead'] is String && (k['instead'] as String).isNotEmpty ? k['instead'] as String : null,
              ),
        ];
        if (keys.isNotEmpty) rows.add(keys);
      }
    }
    if (rows.isEmpty) return null;
    final widest = rows.expand((r) => r).map((k) => k.x + 1).reduce(math.max);
    final max = _num(raw['maxShift']);
    return TrainerBoardData(
      rows: rows,
      units: math.max(_num(raw['units']), widest),
      slips: [
        for (final s in (raw['slips'] as List? ?? const []))
          if (s is Map && _whole(s['count']) > 0) BoardSlip('${s['pressed']}', '${s['meant']}', _whole(s['count'])),
      ],
      maxShift: max > 0 ? max : 0.3,
      lean: raw['lean'] is String ? raw['lean'] as String : null,
    );
  }

  Iterable<BoardKey> get keys => rows.expand((r) => r);
  int get taps => keys.fold(0, (n, k) => n + k.taps);
  int get missed => keys.fold(0, (n, k) => n + k.missed);

  /// The keys whose touch area is off its middle, the keys a fix has somewhere else than the keyboard as it is, and
  /// the keys with a landing place.
  int get shifted => keys.where((k) => k.shifted).length;
  int get moved => keys.where((k) => k.moved).length;
  int get landed => keys.where((k) => k.landed > 0).length;

  BoardKey? keyOf(String letter) {
    for (final k in keys) {
      if (k.key == letter) return k;
    }
    return null;
  }

  /// The key hit most often where [letter] was meant, or null: what the keyboard said of that key, so that a picture
  /// and the rows under it name the same one; else the commonest of the slips drawn.
  String? insteadOf(String letter) {
    final said = keyOf(letter)?.instead;
    if (said != null) return said;
    BoardSlip? most;
    for (final s in slips) {
      if (s.meant == letter && (most == null || s.count > most.count)) most = s;
    }
    return most?.pressed;
  }

  /// The keys missed most, worst first: those with [floor] taps or more and at least one miss.
  List<BoardKey> worst({required int floor}) => keys.where((k) => k.taps >= floor && k.missed > 0).toList()
    ..sort((a, b) {
      final by = b.rate.compareTo(a.rate);
      return by != 0 ? by : a.key.compareTo(b.key);
    });

  /// Where the mistakes happen, in a line. A key's misses are said once it has [floor] taps.
  String missesSaid({required int floor}) {
    if (taps == 0) return 'No taps measured yet.';
    final list = worst(floor: floor);
    if (list.isEmpty) return missed == 0 ? 'Every tap hit the key it was meant for.' : 'No key has been missed often enough to say yet.';
    final said = list.take(3).map((k) {
      final instead = insteadOf(k.key);
      return '${k.key} ${_share(k.rate)}${instead == null ? '' : ' ($instead in its place)'}';
    });
    return 'Missed most: ${said.join(', ')}.';
  }

  /// What has shifted where, in a line: the keys the fix has listening somewhere else than the keyboard as it is,
  /// and the keys the keyboard has off their middles already. With [run], the picture is of the fix one run was
  /// typed on.
  String areasSaid({bool run = false}) {
    final fix = keys.where((k) => k.moved).toList()..sort((a, b) => b.shift.compareTo(a.shift));
    final own = keys.where((k) => k.shifted && !k.moved).length;
    String areas(int n) => n == 1 ? '1 key’s touch area' : '$n keys’ touch areas';
    if (fix.isEmpty) {
      final has = own == 0 ? '' : ' It has ${areas(own)} off the middle.';
      if (run) return 'This run was typed on your keyboard as it is.$has';
      if (own > 0) return 'No fix moves a touch area from where your keyboard has it.$has';
      return landed == 0 ? 'No key has enough taps yet to say where they land.' : 'No fix has moved a touch area far enough to be in use yet.';
    }
    final furthest = fix.first.shifted ? ' Furthest: ${fix.first.key}, ${offsetSaid(fix.first.shiftX, fix.first.shiftY)}.' : '';
    final more = own == 0 ? '' : ' Your keyboard as it is has ${own == 1 ? '1 more' : '$own more'} off the middle.';
    return '${run ? 'The fix this run was typed on moves' : 'The fix moves'} ${areas(fix.length)} toward where the taps land.$furthest$more';
  }
}

/// Which of the keyboard's two pictures: where the mistakes happen, or where the taps land and what has shifted.
enum BoardView { misses, areas }

/// How much larger than life an offset is drawn in [BoardView.areas], so that a small one shows.
const boardOffsetScale = 1.5;

/// A missed share of this or more is drawn at the tint's full strength.
const boardHottest = 0.2;

/// The colours and type a picture is drawn with, read from the page's tokens.
class _Look {
  _Look(Tokens t)
      : key = t.color('app-paper-3'),
        rule = t.color('app-rule'),
        ink = t.color('app-ink'),
        ink2 = t.color('app-ink-2'),
        ink3 = t.color('app-ink-3'),
        ink4 = t.color('app-ink-4'),
        hue = settingsHue(t),
        hot = t.isDark ? const Color(0xFFFF6B61) : const Color(0xFFDD3A30),
        letter = pageText(t, 13, weight: 'medium'),
        small = pageText(t, 9.5, weight: 'medium');

  final Color key;
  final Color rule;
  final Color ink;
  final Color ink2;
  final Color ink3;
  final Color ink4;
  final Color hue;
  final Color hot;
  final TextStyle letter;
  final TextStyle small;

  /// How strongly a key missed at [rate] is tinted: a little shows early, and a fifth is the whole of it.
  static double heat(double rate) => math.pow((rate / boardHottest).clamp(0.0, 1.0), 0.6).toDouble();

  Color tint(double rate) => Color.lerp(key, hot, heat(rate))!;

  /// Ink that reads on [fill].
  Color on(Color fill) => fill.computeLuminance() > 0.32 ? const Color(0xFF16120F) : const Color(0xFFFFFFFF);

  @override
  bool operator ==(Object other) => other is _Look && other.key == key && other.rule == rule && other.ink == ink && other.ink3 == ink3 && other.hue == hue && other.hot == hot && other.letter == letter;

  @override
  int get hashCode => Object.hash(key, rule, ink, ink3, hue, hot);
}

void _text(Canvas canvas, String text, TextStyle style, Offset middle) {
  final p = TextPainter(text: TextSpan(text: text, style: style), textDirection: TextDirection.ltr, maxLines: 1)..layout();
  p.paint(canvas, middle - Offset(p.width / 2, p.height / 2));
  p.dispose();
}

/// An arrow from [from] to [to]. One too short for a shaft is all head, pointing the same way; the head is narrower
/// than it is long, so that its way still reads (a head as wide as long points three ways at once).
void _arrow(Canvas canvas, Offset from, Offset to, Color color, {double width = 2, double head = 5}) {
  final d = to - from;
  final length = d.distance;
  if (length < 0.05) return;
  final u = d / length;
  final across = Offset(-u.dy, u.dx);
  final tip = length < head ? from + u * head : to;
  final base = tip - u * head;
  if (length > head) {
    canvas.drawLine(from, base, Paint()
      ..color = color
      ..strokeWidth = width
      ..strokeCap = StrokeCap.round);
  }
  final a = base + across * (head * 0.4);
  final b = base - across * (head * 0.4);
  canvas.drawPath(
    Path()
      ..moveTo(tip.dx, tip.dy)
      ..lineTo(a.dx, a.dy)
      ..lineTo(b.dx, b.dy)
      ..close(),
    Paint()..color = color,
  );
}

class _BoardPainter extends CustomPainter {
  _BoardPainter({required this.board, required this.view, required this.run, required this.floor, required this.look});

  final TrainerBoardData board;
  final BoardView view;
  final bool run;
  final int floor;
  final _Look look;

  /// A key is this much taller than wide, within what reads on a phone and does not sprawl on a tablet.
  static double keyHeight(double unit) => (unit * 1.16).clamp(24.0, 50.0);

  /// The row under the letters (the space bar's) is drawn this much shorter: it is there to say "keyboard", no more.
  static const _foot = 0.72;

  static Size sizeFor(double width, TrainerBoardData board) {
    final w = width.isFinite && width > 0 ? width : 320.0;
    final h = keyHeight(w / board.units);
    return Size(w, h * board.rows.length + h * _foot);
  }

  @override
  void paint(Canvas canvas, Size size) {
    final unit = size.width / board.units;
    final h = keyHeight(unit);
    final gap = math.min(4.0, unit * 0.1);
    final round = Radius.circular(math.min(7.0, unit * 0.2));
    Rect box(BoardKey k) => Rect.fromLTWH(k.x * unit + gap / 2, k.row * h + gap / 2, unit - gap, h - gap);

    // The row under the letters, with no letters and nothing measured: shapes only.
    final footTop = board.rows.length * h + gap / 2;
    final footHeight = h * _foot - gap;
    final quiet = Paint()..color = look.key.withValues(alpha: look.key.a * 0.5);
    var x = 0.0;
    for (final w in [1.5, 1.0, math.max(1.0, board.units - 5.0), 1.0, 1.5]) {
      canvas.drawRRect(RRect.fromRectAndRadius(Rect.fromLTWH(x * unit + gap / 2, footTop, w * unit - gap, footHeight), round), quiet);
      x += w;
    }

    final letterSize = (unit * 0.38).clamp(10.0, 15.0);
    if (view == BoardView.misses) {
      final fills = <BoardKey, Color>{};
      for (final k in board.keys) {
        final fill = k.taps >= floor ? look.tint(k.rate) : look.key;
        fills[k] = fill;
        canvas.drawRRect(RRect.fromRectAndRadius(box(k), round), Paint()..color = fill);
      }
      // From a key to the one hit in its place, between the two letters.
      for (final s in board.slips) {
        final meant = board.keyOf(s.meant);
        final pressed = board.keyOf(s.pressed);
        if (meant == null || pressed == null) continue;
        final from = box(meant).center;
        final to = box(pressed).center;
        _arrow(canvas, Offset.lerp(from, to, 0.38)!, Offset.lerp(from, to, 0.82)!, look.ink.withValues(alpha: 0.9), width: 1.6, head: 4.5);
      }
      for (final k in board.keys) {
        final r = box(k);
        final counted = k.taps >= floor;
        final ink = !counted ? look.ink4 : (_Look.heat(k.rate) > 0.45 ? look.on(fills[k]!) : look.ink);
        final percent = counted && k.missed > 0 && h >= 30;
        _text(canvas, k.key, look.letter.copyWith(fontSize: letterSize, color: ink), r.center - Offset(0, percent ? h * 0.13 : 0));
        if (percent) _text(canvas, _share(k.rate).replaceFirst('under ', '<'), look.small.copyWith(fontSize: math.min(9.5, unit * 0.27), color: ink.withValues(alpha: 0.85)), r.center + Offset(0, h * 0.2));
      }
      return;
    }

    for (final k in board.keys) {
      final r = box(k);
      // Lit: the fix has this key somewhere the keyboard as it is does not. Grey: the keyboard has it there already.
      final lit = k.shifted && k.moved;
      final strength = (k.shift / board.maxShift).clamp(0.0, 1.0);
      canvas.drawRRect(RRect.fromRectAndRadius(r, round), Paint()..color = lit ? Color.lerp(look.key, look.hue, 0.1 + 0.26 * strength)! : look.key);
      _text(canvas, k.key, look.small.copyWith(fontSize: math.min(10.0, unit * 0.3), color: k.shifted || k.landed > 0 ? look.ink2 : look.ink4), r.topLeft + Offset(math.min(8.0, unit * 0.24), math.min(8.5, h * 0.22)));
      // Marks keep inside the key, so a far one never reads as its neighbour's.
      final room = r.deflate(math.min(4.5, unit * 0.14));
      Offset at(double dx, double dy) {
        final p = r.center + Offset(dx * boardOffsetScale * r.width, dy * boardOffsetScale * r.height);
        return Offset(p.dx.clamp(room.left, room.right), p.dy.clamp(room.top, room.bottom));
      }

      // A key the fix has back at its middle, where the keyboard as it is has it off it, is a dot in the fix's colour.
      if (!k.shifted) canvas.drawCircle(r.center, k.moved ? 2.2 : 1.3, Paint()..color = k.moved ? look.hue : look.ink4);
      if (k.landed > 0) {
        canvas.drawCircle(at(k.landX, k.landY), math.min(3.4, unit * 0.1), Paint()
          ..color = look.ink2
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.4);
      }
      if (k.shifted) _arrow(canvas, r.center, at(k.shiftX, k.shiftY), lit ? look.hue : look.ink3, width: 2, head: math.min(5.0, unit * 0.16));
    }
  }

  @override
  bool shouldRepaint(_BoardPainter old) => old.board != board || old.view != view || old.run != run || old.floor != floor || old.look != look;
}

/// A legend's mark: what [draw] paints in a small box.
class _Mark extends StatelessWidget {
  const _Mark({required this.width, required this.draw});
  final double width;
  final void Function(Canvas canvas, Size size) draw;

  @override
  Widget build(BuildContext context) => CustomPaint(size: Size(width, 12), painter: _MarkPainter(draw));
}

class _MarkPainter extends CustomPainter {
  _MarkPainter(this.draw);
  final void Function(Canvas canvas, Size size) draw;

  @override
  void paint(Canvas canvas, Size size) => draw(canvas, size);

  @override
  bool shouldRepaint(_MarkPainter old) => true;
}

/// A picture's legend: each mark with what it stands for, wrapping where the page is narrow.
class _Legend extends StatelessWidget {
  const _Legend({required this.items});
  final List<(Widget, String)> items;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final style = pageText(t, 11.5, color: t.color('app-ink-3'));
    return Wrap(
      spacing: t.space(3),
      runSpacing: t.space(1),
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        for (final (mark, label) in items)
          Row(mainAxisSize: MainAxisSize.min, children: [mark, SizedBox(width: t.space(1)), Flexible(child: Text(label, style: style))]),
      ],
    );
  }
}

/// The keyboard drawn simply in one of its two [view]s, its legend under it, and what it shows said in a line.
class BoardFigure extends StatelessWidget {
  const BoardFigure({super.key, required this.board, required this.view, this.run = false});

  final TrainerBoardData board;
  final BoardView view;

  /// The picture is one run's own: a key is tinted from its second tap, and the fix drawn is the one it was typed on.
  final bool run;

  /// How many taps a key needs before it is tinted: fewer say little over many runs, and a run has few to give.
  int get floor => run ? 2 : 10;

  /// What the picture shows, in a line: under it for everyone, and what a screen reader is told of it.
  String get said => view == BoardView.misses ? board.missesSaid(floor: floor) : board.areasSaid(run: run);

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final look = _Look(t);
    final legend = switch (view) {
      BoardView.misses => <(Widget, String)>[
          (
            _Mark(
              width: 40,
              draw: (canvas, size) {
                final r = RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(3));
                canvas.drawRRect(r, Paint()..shader = LinearGradient(colors: [for (final s in const [0.0, 0.02, 0.05, 0.1, 0.2]) look.tint(s)]).createShader(Offset.zero & size));
              },
            ),
            'never missed to 1 in 5 or more'
          ),
          (_Mark(width: 18, draw: (canvas, size) => _arrow(canvas, Offset(1, size.height / 2), Offset(size.width - 1, size.height / 2), look.ink, width: 1.6, head: 4.5)), 'the key hit in its place'),
        ],
      BoardView.areas => <(Widget, String)>[
          (
            _Mark(
              width: 12,
              draw: (canvas, size) => canvas.drawCircle(size.center(Offset.zero), 3.4, Paint()
                ..color = look.ink2
                ..style = PaintingStyle.stroke
                ..strokeWidth = 1.4),
            ),
            run ? 'where this run’s taps landed' : 'where your taps land'
          ),
          (_Mark(width: 18, draw: (canvas, size) => _arrow(canvas, Offset(1, size.height / 2), Offset(size.width - 1, size.height / 2), look.hue)), run ? 'where the fix it was typed on has the key' : 'where the fix has the key'),
          if (board.keys.any((k) => k.shifted && !k.moved))
            (_Mark(width: 18, draw: (canvas, size) => _arrow(canvas, Offset(1, size.height / 2), Offset(size.width - 1, size.height / 2), look.ink3)), 'your keyboard as it is'),
        ],
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Semantics(
          image: true,
          label: view == BoardView.misses ? 'The keyboard, each key tinted by how often it is missed. $said' : 'The keyboard, with where the taps land and where the fix has each key listening. $said',
          child: ExcludeSemantics(
            child: LayoutBuilder(
              builder: (context, box) => CustomPaint(
                size: _BoardPainter.sizeFor(box.maxWidth, board),
                painter: _BoardPainter(board: board, view: view, run: run, floor: floor, look: look),
              ),
            ),
          ),
        ),
        SizedBox(height: t.space(2)),
        ExcludeSemantics(child: _Legend(items: legend)),
        SizedBox(height: t.space(2)),
        ExcludeSemantics(child: Text(said, style: pageText(t, t.fontSize('sm'), height: t.len('glacier-leading-sm'), color: t.color('app-ink-2')))),
      ],
    );
  }
}

/// The story runs as two lines, oldest first, over the same columns as the speed bars above them: the share of taps
/// that hit their key, and the share of words right first time. The foot of the chart is a little under the lowest
/// point, so a few points' difference can be seen; its top is 100%.
class RunLines extends StatelessWidget {
  const RunLines({super.key, required this.runs});

  /// Each run as the keyboard gives it in `history`: `accuracy` (a percentage, -1 where it has none), `firstTry`, `words`.
  final List<Map<Object?, Object?>> runs;

  /// The share of taps on their key in each run, as a percentage; null where the run has none to give.
  static List<double?> accuracy(List<Map<Object?, Object?>> runs) => [for (final r in runs) _whole(r['accuracy']) < 0 || r['accuracy'] == null ? null : _num(r['accuracy']).clamp(0, 100).toDouble()];

  /// The share of each run's words that were right first time, as a percentage; null where it was not kept.
  static List<double?> firstTime(List<Map<Object?, Object?>> runs) => [for (final r in runs) r['firstTry'] == null || _whole(r['words']) <= 0 ? null : (_num(r['firstTry']) * 100 / _whole(r['words'])).clamp(0, 100).toDouble()];

  /// The percentage at the chart's foot: the next five under the lowest point with a little room, and never under 0.
  static double footOf(Iterable<double?> values) {
    final real = values.whereType<double>();
    if (real.isEmpty) return 0;
    final low = real.reduce(math.min);
    return math.max(0, math.min(95, ((low - 3) / 5).floor() * 5)).toDouble();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final look = _Look(t);
    final taps = accuracy(runs);
    final words = firstTime(runs);
    if (taps.whereType<double>().isEmpty && words.whereType<double>().isEmpty) return const SizedBox.shrink();
    final foot = footOf([...taps, ...words]);
    String last(List<double?> list) => list.whereType<double>().isEmpty ? '' : ' ${list.whereType<double>().last.round()}%';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Semantics(
          image: true,
          label: 'Accuracy by run, oldest first.${taps.whereType<double>().isEmpty ? '' : ' Taps on their key:${last(taps)} in the latest run.'}${words.whereType<double>().isEmpty ? '' : ' Words right first time:${last(words)}.'}',
          child: ExcludeSemantics(
            child: CustomPaint(size: const Size(double.infinity, 58), painter: _LinesPainter(series: [(words, look.ink3), (taps, look.hue)], foot: foot, look: look)),
          ),
        ),
        SizedBox(height: t.space(2)),
        ExcludeSemantics(
          child: _Legend(items: [
            if (taps.whereType<double>().isNotEmpty) (_Mark(width: 12, draw: (canvas, size) => canvas.drawCircle(size.center(Offset.zero), 3.4, Paint()..color = look.hue)), 'taps on their key${last(taps)}'),
            if (words.whereType<double>().isNotEmpty) (_Mark(width: 12, draw: (canvas, size) => canvas.drawCircle(size.center(Offset.zero), 3.4, Paint()..color = look.ink3)), 'words right first time${last(words)}'),
          ]),
        ),
      ],
    );
  }
}

class _LinesPainter extends CustomPainter {
  _LinesPainter({required this.series, required this.foot, required this.look});

  /// Each line's points (null: that run has none) and its colour; the last one is drawn over the others.
  final List<(List<double?>, Color)> series;
  final double foot;
  final _Look look;

  @override
  void paint(Canvas canvas, Size size) {
    const top = 6.0;
    const bottom = 5.0;
    final tall = size.height - top - bottom;
    double y(double v) => top + (1 - ((v - foot) / (100 - foot)).clamp(0.0, 1.0)) * tall;
    final rule = Paint()
      ..color = look.rule
      ..strokeWidth = 1;
    canvas.drawLine(Offset(0, y(100)), Offset(size.width, y(100)), rule);
    canvas.drawLine(Offset(0, y(foot)), Offset(size.width, y(foot)), rule);
    final scale = look.small.copyWith(fontSize: 9.5, color: look.ink4);
    for (final (value, dy) in [(100.0, 7.0), (foot, -7.0)]) {
      final p = TextPainter(text: TextSpan(text: '${value.round()}%', style: scale), textDirection: TextDirection.ltr, maxLines: 1)..layout();
      p.paint(canvas, Offset(0, y(value) + dy - p.height / 2));
      p.dispose();
    }
    for (final (values, color) in series) {
      final n = values.length;
      if (n == 0) continue;
      final points = <Offset>[
        for (var i = 0; i < n; i++)
          if (values[i] != null) Offset(size.width * (i + 0.5) / n, y(values[i]!)),
      ];
      if (points.isEmpty) continue;
      if (points.length > 1) {
        final path = Path()..moveTo(points.first.dx, points.first.dy);
        for (final p in points.skip(1)) {
          path.lineTo(p.dx, p.dy);
        }
        canvas.drawPath(path, Paint()
          ..color = color
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2
          ..strokeJoin = StrokeJoin.round
          ..strokeCap = StrokeCap.round);
      }
      for (final p in points) {
        canvas.drawCircle(p, p == points.last ? 3.6 : 2.6, Paint()..color = color);
      }
    }
  }

  @override
  bool shouldRepaint(_LinesPainter old) => true;
}

/// The runs that tested a fix as two lines, oldest first: the share of each run's taps on their key as the fix read
/// them, and as the keyboard as it is would have read the very same taps. The gap between the two is the fix's lead;
/// under each run is the fix it tested.
class FixFigure extends StatelessWidget {
  const FixFigure({super.key, required this.fix});
  final FixData fix;

  /// What the picture shows, in a line: what a screen reader is told of it.
  String get said => fix.tests.isEmpty
      ? 'No run has tested a fix yet.'
      : 'Taps on their key in each run that tested a fix, oldest first. Over ${fix.tests.length == 1 ? 'the 1 run' : 'the ${fix.tests.length} runs'}: ${shareSaid(_one(fix.fixedShare))} with the fix, ${shareSaid(_one(fix.asIsShare))} as your keyboard is.';

  /// To a tenth of a percent, as the keyboard's own totals are said.
  static double? _one(double? v) => v == null ? null : (v * 10).round() / 10;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final look = _Look(t);
    final tests = fix.tests;
    if (tests.isEmpty) return const SizedBox.shrink();
    final asIs = <double?>[for (final r in tests) r.asIsShare];
    final fixed = <double?>[for (final r in tests) r.fixedShare];
    final foot = RunLines.footOf([...asIs, ...fixed]);
    final small = pageText(t, 10, color: t.color('app-ink-3'));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Semantics(
          image: true,
          label: said,
          child: ExcludeSemantics(
            child: CustomPaint(size: const Size(double.infinity, 58), painter: _LinesPainter(series: [(asIs, look.ink3), (fixed, look.hue)], foot: foot, look: look)),
          ),
        ),
        ExcludeSemantics(
          child: Row(children: [
            for (var i = 0; i < tests.length; i++) Expanded(child: Text(i == 0 ? 'fix ${tests[i].number}' : '${tests[i].number}', textAlign: TextAlign.center, maxLines: 1, overflow: TextOverflow.clip, softWrap: false, style: small)),
          ]),
        ),
        SizedBox(height: t.space(2)),
        ExcludeSemantics(
          child: _Legend(items: [
            (_Mark(width: 12, draw: (canvas, size) => canvas.drawCircle(size.center(Offset.zero), 3.4, Paint()..color = look.hue)), 'with the fix ${shareSaid(_one(fix.fixedShare))}'),
            (_Mark(width: 12, draw: (canvas, size) => canvas.drawCircle(size.center(Offset.zero), 3.4, Paint()..color = look.ink3)), 'your keyboard as it is ${shareSaid(_one(fix.asIsShare))}'),
          ]),
        ),
      ],
    );
  }
}
