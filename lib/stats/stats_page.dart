/// The Stats page (Matt, 2026-11-11: "make a stats page that shows the missed keys and key shifting as well as other
/// stats like type speed most misspelled words and other key information use iconography and colors"; docs/stats.md).
///
/// One page of pictures from everyday typing, each in its own colour with its own icon: two dials (speed and
/// accuracy), six tiles (words, time, the days in a row, autocorrect, swipes, slips), the week as bars with the speed
/// drawn over them, the keyboard twice as the trainer draws it (lib/trainer/trainer_board.dart: the keys missed and
/// the key hit in their place; where the taps land and how far a touch area has shifted), the words put right most
/// often, and the busiest keys.
///
/// The keyboard counts all of it (Android's TypingStats.kt, and MainActivity's `statsPage`); this only draws what it
/// is told. Counts only, but for one card: the words under "Most misspelled words" are the ones the keyboard already
/// remembers from the person's own corrections (Mistakes.kt), shown to nobody but them.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../art/app_icon.dart';
import '../art/lucide.g.dart';
import '../art/lucide_more.dart';
import '../settings/kit/settings_kit.dart';
import '../settings/kit/settings_type.dart';
import '../settings_store.dart';
import '../trainer/trainer_board.dart';

int _n(Object? v) => v is num && v.isFinite ? v.round() : 0;
Map<Object?, Object?> _m(Object? v) => v is Map ? v : const {};

/// 1204 as "1,204".
String statCount(int n) {
  final s = n.abs().toString();
  final out = StringBuffer(n < 0 ? '-' : '');
  for (var i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) out.write(',');
    out.write(s[i]);
  }
  return out.toString();
}

/// Minutes as "12 min", "1 h 5 min".
String statTime(int minutes) => minutes < 60 ? '$minutes min' : '${minutes ~/ 60} h ${minutes % 60} min';

String _days(int n) => n == 1 ? '1 day' : '$n days';

/// The colours of the page's parts, by what each stands for. Names of settings.css's hues (settings_css.dart).
abstract final class StatHues {
  static const speed = 'mint';
  static const accuracy = 'green';
  static const words = 'blue';
  static const time = 'amber';
  static const streak = 'orange';
  static const fixes = 'purple';
  static const swipes = 'cyan';
  static const slips = 'rose';
  static const missed = 'red';
  static const shifted = 'indigo';
  static const spelling = 'magenta';
  static const keys = 'lime';
}

/// Asks the keyboard for the page again every couple of seconds while it is open: typing in the field at the page's
/// foot counts as it is typed.
class StatsPoll extends StatefulWidget {
  const StatsPoll({super.key, required this.store, required this.child});
  final SettingsStore store;
  final Widget child;

  @override
  State<StatsPoll> createState() => _StatsPollState();
}

class _StatsPollState extends State<StatsPoll> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    widget.store.refreshStatsPage();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => widget.store.refreshStatsPage());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

/// A part of the page in its own colour: what is under it reads `--hue` as that colour.
class _Hue extends StatelessWidget {
  const _Hue(this.hue, {required this.builder});
  final String hue;
  final Widget Function(BuildContext context, Tokens t, Color colour, Color wash) builder;

  @override
  Widget build(BuildContext context) => GlacierScope(
        attrs: {'hue': hue},
        child: Builder(builder: (context) {
          final t = context.tokens;
          return builder(context, t, settingsHue(t), settingsHueWash(t));
        }),
      );
}

/// An icon on a chip of its colour, as a section's row has it on the list.
class _Chip extends StatelessWidget {
  const _Chip(this.icon, {required this.colour, required this.wash, this.size = 30});
  final LucideIcon icon;
  final Color colour;
  final Color wash;
  final double size;

  @override
  Widget build(BuildContext context) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(color: wash, borderRadius: BorderRadius.circular(size * 0.3)),
        alignment: Alignment.center,
        child: AppIcon(icon, size: size * 0.58, strokeWidth: 2.1, color: colour),
      );
}

// --- the dials -----------------------------------------------------------------------------------------------------

/// A dial: three quarters of a ring, filled as far as [share] (0 to 1), with [value] in its middle and [unit] under
/// it. Nothing counted yet: the ring empty and a dash.
class StatDial extends StatelessWidget {
  const StatDial({super.key, required this.icon, required this.hue, required this.label, required this.value, required this.unit, required this.share, required this.when});
  final LucideIcon icon;
  final String hue;
  final String label;

  /// The number in the middle, or null where there is nothing to say yet.
  final String? value;
  final String unit;
  final double share;

  /// What the number is of: "today", "last 30 days".
  final String when;

  @override
  Widget build(BuildContext context) => _Hue(hue, builder: (context, t, colour, wash) {
        return Semantics(
          label: value == null ? '$label: nothing counted yet' : '$label, $when: $value $unit',
          child: ExcludeSemantics(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(
                  width: 128,
                  height: 112,
                  child: CustomPaint(
                    painter: _DialPainter(share: value == null ? 0 : share.clamp(0.0, 1.0), colour: colour, track: t.color('app-paper-3')),
                    child: Padding(
                      padding: const EdgeInsets.only(top: 14),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Text(value ?? '—', style: pageText(t, 30, weight: 'bold', color: value == null ? t.color('app-ink-4') : t.color('app-ink'), height: 1.05)),
                          Text(unit, style: pageText(t, 11.5, color: t.color('app-ink-3'))),
                        ],
                      ),
                    ),
                  ),
                ),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    AppIcon(icon, size: 15, strokeWidth: 2.1, color: colour),
                    SizedBox(width: t.space(1)),
                    Text(label, style: pageText(t, 13, weight: 'semibold', color: t.color('app-ink'))),
                  ],
                ),
                Text(when, style: pageText(t, 11.5, color: t.color('app-ink-3'))),
              ],
            ),
          ),
        );
      });
}

class _DialPainter extends CustomPainter {
  _DialPainter({required this.share, required this.colour, required this.track});
  final double share;
  final Color colour;
  final Color track;

  /// The ring opens downward: it starts at 135° and runs 270°.
  static const _from = math.pi * 0.75;
  static const _sweep = math.pi * 1.5;

  @override
  void paint(Canvas canvas, Size size) {
    const stroke = 10.0;
    final radius = math.min(size.width, size.height * 1.16) / 2 - stroke / 2;
    final centre = Offset(size.width / 2, radius + stroke / 2);
    final arc = Rect.fromCircle(center: centre, radius: radius);
    final pen = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round;
    canvas.drawArc(arc, _from, _sweep, false, pen..color = track);
    if (share > 0) canvas.drawArc(arc, _from, _sweep * share, false, pen..color = colour);
  }

  @override
  bool shouldRepaint(_DialPainter old) => old.share != share || old.colour != colour || old.track != track;
}

// --- the tiles -----------------------------------------------------------------------------------------------------

/// One number with its icon, its name and a line under it.
class StatTile extends StatelessWidget {
  const StatTile({super.key, required this.icon, required this.hue, required this.value, required this.label, required this.note});
  final LucideIcon icon;
  final String hue;
  final String value;
  final String label;
  final String note;

  @override
  Widget build(BuildContext context) => _Hue(hue, builder: (context, t, colour, wash) {
        return Semantics(
          label: '$label: $value. $note',
          child: ExcludeSemantics(
            child: Container(
              padding: EdgeInsets.all(t.space(3)),
              decoration: BoxDecoration(color: t.color('app-paper-2'), borderRadius: BorderRadius.circular(t.radius('lg'))),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      _Chip(icon, colour: colour, wash: wash),
                      SizedBox(width: t.space(2)),
                      Expanded(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: pageText(t, 12.5, weight: 'medium', color: t.color('app-ink-2')))),
                    ],
                  ),
                  SizedBox(height: t.space(2)),
                  Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: pageText(t, 24, weight: 'bold', color: t.color('app-ink'), height: 1.1)),
                  SizedBox(height: t.space(1)),
                  Text(note, maxLines: 2, overflow: TextOverflow.ellipsis, style: pageText(t, 11.5, color: t.color('app-ink-3'), height: 1.3)),
                ],
              ),
            ),
          ),
        );
      });
}

/// Tiles two across (three where the page is wide), each row as tall as its tallest.
class _TileGrid extends StatelessWidget {
  const _TileGrid({required this.tiles});
  final List<Widget> tiles;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final gap = t.space(2);
    return LayoutBuilder(builder: (context, box) {
      final across = box.maxWidth >= 560 ? 3 : 2;
      final rows = <Widget>[];
      for (var i = 0; i < tiles.length; i += across) {
        final row = tiles.sublist(i, math.min(i + across, tiles.length));
        rows.add(IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (var j = 0; j < across; j++) ...[
                if (j > 0) SizedBox(width: gap),
                Expanded(child: j < row.length ? row[j] : const SizedBox()),
              ],
            ],
          ),
        ));
        if (i + across < tiles.length) rows.add(SizedBox(height: gap));
      }
      return Column(mainAxisSize: MainAxisSize.min, children: rows);
    });
  }
}

// --- the week ------------------------------------------------------------------------------------------------------

/// The last seven days, oldest first: the words typed as bars and the speed as a line over them. A day with no
/// typing has no bar and breaks the line.
class WeekChart extends StatelessWidget {
  const WeekChart({super.key, required this.week});
  final List<Map<Object?, Object?>> week;

  String get said {
    final typed = [for (final d in week) if (_n(d['words']) > 0) '${d['day']}: ${statCount(_n(d['words']))} words${_n(d['wpm']) > 0 ? ' at ${_n(d['wpm'])} a minute' : ''}'];
    return typed.isEmpty ? 'Nothing typed in the last seven days.' : typed.join('; ');
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return _Hue(StatHues.words, builder: (context, _, bar, wash) {
      return _Hue(StatHues.time, builder: (context, _, line, _) {
        final small = pageText(t, 11, color: t.color('app-ink-3'));
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Semantics(
              image: true,
              label: 'The last seven days. $said',
              child: ExcludeSemantics(
                child: SizedBox(
                  height: 148,
                  child: CustomPaint(painter: _WeekPainter(week: week, bar: bar, wash: wash, line: line, ink: t.color('app-ink-2'), faint: t.color('app-ink-3'), rule: t.color('app-rule'), small: small)),
                ),
              ),
            ),
            SizedBox(height: t.space(2)),
            ExcludeSemantics(
              child: Wrap(
                spacing: t.space(3),
                runSpacing: t.space(1),
                children: [
                  Row(mainAxisSize: MainAxisSize.min, children: [
                    Container(width: 10, height: 10, decoration: BoxDecoration(color: bar, borderRadius: BorderRadius.circular(2.5))),
                    SizedBox(width: t.space(1)),
                    Text('words typed', style: small),
                  ]),
                  Row(mainAxisSize: MainAxisSize.min, children: [
                    Container(width: 14, height: 2.4, decoration: BoxDecoration(color: line, borderRadius: BorderRadius.circular(2))),
                    SizedBox(width: t.space(1)),
                    Text('words a minute', style: small),
                  ]),
                ],
              ),
            ),
          ],
        );
      });
    });
  }
}

class _WeekPainter extends CustomPainter {
  _WeekPainter({required this.week, required this.bar, required this.wash, required this.line, required this.ink, required this.faint, required this.rule, required this.small});
  final List<Map<Object?, Object?>> week;
  final Color bar;
  final Color wash;
  final Color line;
  final Color ink;
  final Color faint;
  final Color rule;
  final TextStyle small;

  void _label(Canvas canvas, String text, TextStyle style, Offset middleTop) {
    final p = TextPainter(text: TextSpan(text: text, style: style), textDirection: TextDirection.ltr, maxLines: 1)..layout();
    p.paint(canvas, middleTop - Offset(p.width / 2, 0));
    p.dispose();
  }

  @override
  void paint(Canvas canvas, Size size) {
    if (week.isEmpty) return;
    const top = 30.0;      // the speed's own numbers and the line's room
    const foot = 34.0;     // the day's name, and the words under it
    final plot = Rect.fromLTRB(0, top, size.width, size.height - foot);
    final column = size.width / week.length;
    final most = week.fold<int>(1, (m, d) => math.max(m, _n(d['words'])));
    final fastest = week.fold<int>(1, (m, d) => math.max(m, _n(d['wpm'])));
    canvas.drawLine(plot.bottomLeft, plot.bottomRight, Paint()..color = rule);

    final points = <Offset?>[];
    for (var i = 0; i < week.length; i++) {
      final words = _n(week[i]['words']);
      final wpm = _n(week[i]['wpm']);
      final x = column * (i + 0.5);
      final today = i == week.length - 1;
      if (words > 0) {
        final h = math.max(3.0, plot.height * words / most);
        final w = math.min(34.0, column * 0.56);
        final r = RRect.fromRectAndCorners(Rect.fromLTWH(x - w / 2, plot.bottom - h, w, h), topLeft: const Radius.circular(5), topRight: const Radius.circular(5));
        canvas.drawRRect(r, Paint()..color = today ? bar : Color.lerp(wash, bar, 0.45)!);
      }
      _label(canvas, '${week[i]['day'] ?? ''}', small.copyWith(color: today ? ink : faint, fontWeight: today ? FontWeight.w600 : null), Offset(x, plot.bottom + 5));
      _label(canvas, words > 0 ? statCount(words) : '·', small.copyWith(color: faint, fontSize: 10), Offset(x, plot.bottom + 19));
      // The speed is drawn in the plot's upper two thirds, so the line stands over the bars and not behind them.
      points.add(wpm > 0 ? Offset(x, plot.top + plot.height * 0.62 * (1 - wpm / fastest)) : null);
    }
    final pen = Paint()
      ..color = line
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.2
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    for (var i = 1; i < points.length; i++) {
      final a = points[i - 1], b = points[i];
      if (a != null && b != null) canvas.drawLine(a, b, pen);
    }
    for (var i = 0; i < points.length; i++) {
      final p = points[i];
      if (p == null) continue;
      canvas.drawCircle(p, 4.2, Paint()..color = line);
      _label(canvas, '${_n(week[i]['wpm'])}', small.copyWith(color: line, fontWeight: FontWeight.w600), p - const Offset(0, 21));
    }
  }

  @override
  bool shouldRepaint(_WeekPainter old) => '${old.week}' != '$week' || old.bar != bar || old.line != line || old.ink != ink;
}

// --- the lists -----------------------------------------------------------------------------------------------------

/// The slips drawn on the keyboard above, as chips: "r for e", and how often.
class _SlipChips extends StatelessWidget {
  const _SlipChips({required this.slips});
  final List<BoardSlip> slips;

  @override
  Widget build(BuildContext context) => _Hue(StatHues.missed, builder: (context, t, colour, wash) {
        return Wrap(
          spacing: t.space(2),
          runSpacing: t.space(2),
          children: [
            for (final s in slips)
              Semantics(
                label: '${s.pressed} hit for ${s.meant}, ${s.count} times',
                child: ExcludeSemantics(
                  child: Container(
                    padding: EdgeInsets.symmetric(horizontal: t.space(2), vertical: t.space(1)),
                    decoration: BoxDecoration(color: wash, borderRadius: BorderRadius.circular(999)),
                    child: Row(mainAxisSize: MainAxisSize.min, children: [
                      Text(s.pressed, style: pageText(t, 13, weight: 'bold', color: colour)),
                      Text(' for ', style: pageText(t, 12, color: t.color('app-ink-3'))),
                      Text(s.meant, style: pageText(t, 13, weight: 'bold', color: t.color('app-ink'))),
                      Text('  ${s.count}×', style: pageText(t, 11.5, color: t.color('app-ink-3'))),
                    ]),
                  ),
                ),
              ),
          ],
        );
      });
}

/// A word as it was typed, an arrow, the word it was meant to be, and how often it was put right.
class _Misspelled extends StatelessWidget implements PaneRow {
  const _Misspelled({required this.typed, required this.meant, required this.count, required this.most});
  final String typed;
  final String meant;
  final int count;
  final int most;

  @override
  Widget build(BuildContext context) => _Hue(StatHues.spelling, builder: (context, t, colour, wash) {
        return Semantics(
          label: '$typed, meant $meant: put right ${count == 1 ? 'once' : '$count times'}',
          child: ExcludeSemantics(
            child: Padding(
              padding: EdgeInsets.symmetric(horizontal: t.space(3), vertical: t.space(2) + 1),
              child: Row(
                children: [
                  Expanded(
                    child: Text.rich(
                      TextSpan(children: [
                        TextSpan(text: typed, style: pageText(t, 14.5, weight: 'medium', color: colour)),
                        TextSpan(text: '  →  ', style: pageText(t, 13, color: t.color('app-ink-4'))),
                        TextSpan(text: meant, style: pageText(t, 14.5, weight: 'semibold', color: t.color('app-ink'))),
                      ]),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  SizedBox(width: t.space(2)),
                  // How often, as a short bar and the number: the list's longest is the most.
                  SizedBox(
                    width: 54,
                    child: Align(
                      alignment: Alignment.centerRight,
                      child: Container(width: math.max(6.0, 54.0 * count / math.max(1, most)), height: 6, decoration: BoxDecoration(color: colour, borderRadius: BorderRadius.circular(3))),
                    ),
                  ),
                  SizedBox(width: t.space(2)),
                  SizedBox(width: 30, child: Text('$count×', textAlign: TextAlign.right, style: pageText(t, 12.5, weight: 'medium', color: t.color('app-ink-2')))),
                ],
              ),
            ),
          ),
        );
      });
}

/// The letters pressed most, each as a keycap, a bar and its share of every letter pressed.
class _LetterBars extends StatelessWidget {
  const _LetterBars({required this.letters});
  final List<Map<Object?, Object?>> letters;

  @override
  Widget build(BuildContext context) => _Hue(StatHues.keys, builder: (context, t, colour, wash) {
        final most = letters.fold<double>(0.1, (m, l) => math.max(m, (l['share'] as num?)?.toDouble() ?? 0));
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 0; i < letters.length; i++) ...[
              if (i > 0) SizedBox(height: t.space(2)),
              Semantics(
                label: '${letters[i]['key']}: ${letters[i]['share']} percent of the letters you press',
                child: ExcludeSemantics(
                  child: Row(
                    children: [
                      Container(
                        width: 26,
                        height: 26,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(color: t.color('app-paper-3'), borderRadius: BorderRadius.circular(6)),
                        child: Text('${letters[i]['key']}'.toUpperCase(), style: pageText(t, 13, weight: 'semibold', color: t.color('app-ink'))),
                      ),
                      SizedBox(width: t.space(2)),
                      Expanded(
                        child: LayoutBuilder(
                          builder: (context, box) => Align(
                            alignment: Alignment.centerLeft,
                            child: Container(
                              width: math.max(4.0, box.maxWidth * (((letters[i]['share'] as num?)?.toDouble() ?? 0) / most)),
                              height: 10,
                              decoration: BoxDecoration(color: Color.lerp(wash, colour, i == 0 ? 1 : 0.62), borderRadius: BorderRadius.circular(5)),
                            ),
                          ),
                        ),
                      ),
                      SizedBox(width: t.space(2)),
                      SizedBox(width: 44, child: Text('${letters[i]['share']}%', textAlign: TextAlign.right, style: pageText(t, 12.5, color: t.color('app-ink-2')))),
                    ],
                  ),
                ),
              ),
            ],
          ],
        );
      });
}

/// A card with nothing to draw yet: its icon, faint, and why.
class _Nothing extends StatelessWidget {
  const _Nothing({required this.icon, required this.hue, required this.words});
  final LucideIcon icon;
  final String hue;
  final String words;

  @override
  Widget build(BuildContext context) => _Hue(hue, builder: (context, t, colour, wash) {
        return Row(
          children: [
            _Chip(icon, colour: colour, wash: wash, size: 34),
            SizedBox(width: t.space(3)),
            Expanded(child: Text(words, style: pageText(t, t.fontSize('sm'), height: t.len('glacier-leading-sm'), color: t.color('app-ink-2')))),
          ],
        );
      });
}

/// A card's heading inside the card: its icon on its chip, and what the card is, for the pictures that need a word
/// of their own above them.
class _Heading extends StatelessWidget {
  const _Heading({required this.icon, required this.hue, required this.words});
  final LucideIcon icon;
  final String hue;
  final String words;

  @override
  Widget build(BuildContext context) => _Hue(hue, builder: (context, t, colour, wash) {
        return Padding(
          padding: EdgeInsets.only(bottom: t.space(3)),
          child: Row(
            children: [
              _Chip(icon, colour: colour, wash: wash, size: 26),
              SizedBox(width: t.space(2)),
              Expanded(child: Text(words, style: pageText(t, 13, weight: 'medium', color: t.color('app-ink-2')))),
            ],
          ),
        );
      });
}

// --- the page ------------------------------------------------------------------------------------------------------

/// The row's second line on the Settings list.
String statsPageSummary(SettingsStore s) {
  if (!s.flag('typing_stats')) return 'Off';
  final today = _m((s.statsPage.isEmpty ? s.stats : s.statsPage)['today']);
  final wpm = _n(today['wpm']);
  final words = _n(today['words']);
  if (words == 0) return 'Speed, missed keys, shifted keys, misspelled words';
  return [if (wpm > 0) '$wpm words a minute', '${statCount(words)} words today'].join(', ');
}

/// The Stats page's cards.
List<Widget> statsPage(BuildContext context, SettingsStore store) {
  final s = store.statsPage.isEmpty ? store.stats : store.statsPage;
  final counting = store.flag('typing_stats');
  final today = _m(s['today']);
  final all = _m(s['all']);
  final week = [for (final d in (s['week'] as List? ?? const [])) if (d is Map) d];
  final board = TrainerBoardData.of(s['board']);
  final adaptive = s['adaptive'] == true || (s['adaptive'] == null && store.pluginOn('adaptive'));
  final learning = store.flag('learn_mistakes');
  final misspelled = [for (final w in (s['misspelled'] as List? ?? const [])) if (w is Map && '${w['typed'] ?? ''}'.isNotEmpty) w];
  final letters = [for (final l in (s['letters'] as List? ?? const [])) if (l is Map) l];
  final fastest = _m(s['fastest']);
  final fullest = _m(s['fullest']);
  final streak = _n(s['streak']);
  final layout = '${s['layout'] ?? ''}';

  // The dials are today's once today has something to say, else the month's.
  final speedToday = _n(today['wpm']) > 0;
  final wpm = speedToday ? _n(today['wpm']) : _n(all['wpm']);
  final accuracyToday = _n(today['accuracy']) > 0;
  final accuracy = accuracyToday ? _n(today['accuracy']) : _n(all['accuracy']);
  final wordsToday = _n(today['words']);
  final minutesToday = _n(today['minutes']);
  String percent(int n) => n == 0 ? '—' : '$n%';
  final typedOn = _n(all['days']);

  return [
    StatsPoll(
      store: store,
      child: PaneSection(
        footer: Text(!counting
            ? 'Typing stats are off: nothing is being counted. The switch is at the foot of this page.'
            : wordsToday == 0
                ? 'Nothing typed today yet. Type in the field below to see it count.'
                : '${statCount(wordsToday)} ${wordsToday == 1 ? 'word' : 'words'} in ${statTime(minutesToday)} of typing today.'),
        children: [
          SettingRowBox(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                StatDial(icon: Lucide.gauge, hue: StatHues.speed, label: 'Speed', value: wpm > 0 ? '$wpm' : null, unit: 'words a minute', share: wpm / 100, when: speedToday ? 'today' : 'last 30 days'),
                StatDial(icon: LucideMore.target, hue: StatHues.accuracy, label: 'Accuracy', value: accuracy > 0 ? '$accuracy%' : null, unit: 'kept as typed', share: accuracy / 100, when: accuracyToday ? 'today' : 'last 30 days'),
              ],
            ),
          ),
        ],
      ),
    ),
    _TileGrid(tiles: [
      StatTile(icon: LucideMore.type, hue: StatHues.words, value: statCount(wordsToday), label: 'Words today', note: '${statCount(_n(all['words']))} in the last 30 days'),
      StatTile(icon: Lucide.clock, hue: StatHues.time, value: statTime(minutesToday), label: 'Time typing today', note: '${statTime(_n(all['minutes']))} in the last 30 days'),
      StatTile(icon: Lucide.flame, hue: StatHues.streak, value: _days(streak), label: 'In a row', note: typedOn == 0 ? 'No typing counted yet' : 'Typed on ${_days(typedOn)} of the last 30'),
      StatTile(icon: Lucide.sparkles, hue: StatHues.fixes, value: statCount(_n(today['corrections'])), label: 'Autocorrect fixes today', note: _n(all['correctionsKept']) == 0 ? '${statCount(_n(all['corrections']))} in 30 days' : '${percent(_n(all['correctionsKept']))} of ${statCount(_n(all['corrections']))} kept in 30 days'),
      StatTile(icon: LucideMore.moveHorizontal, hue: StatHues.swipes, value: statCount(_n(today['swipes'])), label: 'Words swiped today', note: _n(all['swipesFirst']) == 0 ? '${statCount(_n(all['swipes']))} in 30 days' : '${percent(_n(all['swipesFirst']))} of ${statCount(_n(all['swipes']))} right first time'),
      StatTile(icon: Lucide.eraser, hue: StatHues.slips, value: statCount(_n(today['slips'])), label: 'Taken back today', note: 'A backspace straight after a key. ${statCount(_n(all['slips']))} in 30 days'),
    ]),
    SizedBox(height: context.tokens.space(5)),
    PaneSection(
      title: 'Last 7 days',
      footer: Text([
        if (_n(fastest['wpm']) > 0) 'Fastest day kept: ${fastest['day']}, ${_n(fastest['wpm'])} words a minute.',
        if (_n(fullest['words']) > 0) 'Fullest: ${fullest['day']}, ${statCount(_n(fullest['words']))} words.',
        if (_n(fullest['words']) == 0) 'Words typed each day, and how fast.',
      ].join(' ')),
      children: [SettingRowBox(child: WeekChart(week: week))],
    ),
    PaneSection(
      title: 'Missed keys',
      footer: Text('A key counts as missed when you took the letter back and typed its neighbour, or autocorrect swapped it for its neighbour.${layout.isEmpty ? '' : ' Counted on the $layout keys.'}'),
      children: [
        SettingRowBox(
          child: board == null || board.taps == 0
              ? _Nothing(icon: Lucide.circleAlert, hue: StatHues.missed, words: counting ? 'No taps counted yet. Type a little, and the keys you miss show here with the key you hit in their place.' : 'Typing stats are off, so no taps are counted.')
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const _Heading(icon: Lucide.circleAlert, hue: StatHues.missed, words: 'Where your mistakes happen'),
                    GlacierScope(attrs: const {'hue': StatHues.missed}, child: BoardFigure(board: board, view: BoardView.misses, everyday: true)),
                    if (board.slips.isNotEmpty) ...[SizedBox(height: context.tokens.space(3)), _SlipChips(slips: board.slips)],
                  ],
                ),
        ),
      ],
    ),
    PaneSection(
      title: 'Key shifting',
      footer: Text(adaptive
          ? 'Adaptive keys moves a key’s touch area, slowly, toward where your taps land, and lets it drift back as your aim comes right. The keys you see never move.'
          : 'Adaptive keys is off, so no touch area has shifted. Turn it on in Typing, and each key’s touch area follows where your taps land.'),
      children: [
        SettingRowBox(
          child: board == null || (board.landed == 0 && board.shifted == 0)
              ? _Nothing(icon: LucideMore.move, hue: StatHues.shifted, words: counting ? 'No key has taps counted yet. Type a little, and a ring shows where your taps land on each key.' : 'Typing stats are off, so no taps are counted.')
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const _Heading(icon: LucideMore.move, hue: StatHues.shifted, words: 'Where your taps land, and what has shifted'),
                    GlacierScope(attrs: const {'hue': StatHues.shifted}, child: BoardFigure(board: board, view: BoardView.areas, everyday: true, adaptive: adaptive)),
                  ],
                ),
        ),
      ],
    ),
    PaneSection(
      title: 'Most misspelled words',
      footer: const Text('The words you put right most often, by typing over them or picking another above the keys. They are the ones the keyboard already remembers from your corrections (Typing, Autocorrect, Your corrections), kept on this phone. Older ones fade.'),
      children: [
        if (misspelled.isEmpty)
          SettingRowBox(
            child: _Nothing(
              icon: LucideMore.spellCheck,
              hue: StatHues.spelling,
              words: learning ? 'None yet. Put a word right, and it is counted here.' : 'Learn from my corrections is off (Typing, Autocorrect), so no word is remembered.',
            ),
          )
        else
          for (final w in misspelled) _Misspelled(typed: '${w['typed']}', meant: '${w['meant']}', count: math.max(1, _n(w['count'])), most: misspelled.fold<int>(1, (m, x) => math.max(m, _n(x['count'])))),
      ],
    ),
    PaneSection(
      title: 'Busiest keys',
      footer: Text(letters.isEmpty ? 'Shown once fifty letters have been counted.' : 'Each letter’s share of the ${statCount(_n(s['pressed']))} letters counted.'),
      children: [
        SettingRowBox(child: letters.isEmpty ? const _Nothing(icon: LucideMore.keyboard, hue: StatHues.keys, words: 'Not enough typing counted yet.') : _LetterBars(letters: letters)),
      ],
    ),
    PaneSection(
      title: 'Last 30 days',
      children: [
        SettingRow(label: 'Speed', icon: AppIcon(Lucide.gauge, size: 18), value: Text(_n(all['wpm']) == 0 ? '—' : '${_n(all['wpm'])} wpm')),
        SettingRow(label: 'Accuracy', icon: AppIcon(LucideMore.target, size: 18), value: Text(percent(_n(all['accuracy'])))),
        SettingRow(label: 'Words', icon: AppIcon(LucideMore.type, size: 18), value: Text('${statCount(_n(all['words']))} over ${_days(typedOn)}')),
        SettingRow(label: 'Time typing', icon: AppIcon(Lucide.clock, size: 18), value: Text(statTime(_n(all['minutes'])))),
      ],
    ),
    PaneSection(
      footer: const Text('Counts only, never what you typed: how many, how fast, which key for which, and where your taps land. Kept on this phone and sent nowhere. Nothing is counted in password fields or while incognito. Reset deletes the counts; your corrections have their own Forget.'),
      children: [
        SettingRow(label: 'Count my typing', control: GlacierSwitch(checked: counting, onCheckedChange: (v) => store.set('typing_stats', v), ariaLabel: 'Count my typing')),
        SettingRow(label: 'Reset stats', onPress: _n(all['words']) == 0 && wordsToday == 0 && (board?.taps ?? 0) == 0 ? null : store.resetStats),
      ],
    ),
  ];
}
