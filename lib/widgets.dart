import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

/// A section's small heading above its card.
class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key});
  final String text;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return Padding(
      padding: EdgeInsets.only(left: t.space(1), bottom: t.space(2)),
      child: Text(text.toUpperCase(), style: t.text('xs', weight: 'semibold', color: t.color('glacier-text-subtle')).copyWith(letterSpacing: 0.6)),
    );
  }
}

/// A raised card of rows with hairlines between them, as Glacier's grouped settings are drawn.
class SettingsCard extends StatelessWidget {
  const SettingsCard({super.key, required this.children});
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final radius = t.radius('xl');
    final hairline = t.len('glacier-hairline');
    return DecoratedBox(
      decoration: BoxDecoration(
        color: t.color('glacier-surface-raised'),
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: t.color('glacier-border-subtle'), width: hairline),
        boxShadow: t.shadow('glacier-shadow-1'),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: Column(
          children: [
            for (var i = 0; i < children.length; i++) ...[
              if (i > 0) Container(height: hairline, margin: EdgeInsets.only(left: t.space(4)), color: t.color('glacier-border-subtle')),
              children[i],
            ],
          ],
        ),
      ),
    );
  }
}

/// A row's title and optional description, stacked.
class _RowText extends StatelessWidget {
  const _RowText({required this.title, this.description});
  final String title;
  final String? description;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(title, style: t.text('md', weight: 'medium')),
        if (description != null) ...[
          SizedBox(height: t.space(1) / 2),
          Text(description!, style: t.text('sm', color: t.color('glacier-text-muted'))),
        ],
      ],
    );
  }
}

/// A setting that is on or off: the whole row toggles it, as a settings list does.
class SwitchSetting extends StatelessWidget {
  const SwitchSetting({super.key, required this.title, this.description, required this.value, required this.onChanged});
  final String title;
  final String? description;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return GlacierPressable(
      onPressed: () => onChanged(!value),
      toggled: value,
      semanticLabel: title,
      builder: (context, state) => AnimatedContainer(
        duration: t.duration('glacier-duration-fast'),
        color: state.pressed ? t.color('glacier-hover') : t.colorOf('transparent'),
        padding: EdgeInsets.symmetric(horizontal: t.space(4), vertical: t.space(3)),
        child: Row(
          children: [
            Expanded(child: _RowText(title: title, description: description)),
            SizedBox(width: t.space(3)),
            ExcludeSemantics(child: GlacierSwitch(checked: value, onCheckedChange: onChanged, ariaLabel: title)),
          ],
        ),
      ),
    );
  }
}

/// A setting with a few named choices: a full-width segmented control under its title.
class ChoiceSetting extends StatelessWidget {
  const ChoiceSetting({super.key, required this.title, required this.value, required this.options, required this.onChanged});
  final String title;
  final String value;
  final Map<String, String> options;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return Padding(
      padding: EdgeInsets.symmetric(horizontal: t.space(4), vertical: t.space(3)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _RowText(title: title),
          SizedBox(height: t.space(2)),
          GlacierSegmentedControl(
            options: [for (final e in options.entries) GlacierSegmentedOption(value: e.key, label: Text(e.value))],
            value: value,
            onValueChange: onChanged,
            fullWidth: true,
            size: GlacierSegmentedSize.sm,
            ariaLabel: title,
          ),
        ],
      ),
    );
  }
}

/// A capsule button in the accent colour, Glacier's primary button.
class GlacierButton extends StatelessWidget {
  const GlacierButton({super.key, required this.label, required this.onPressed});
  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final disabled = onPressed == null;
    return GlacierPressable(
      onPressed: onPressed,
      semanticLabel: label,
      builder: (context, state) => AnimatedOpacity(
        opacity: disabled ? 0.45 : 1,
        duration: t.duration('glacier-duration-fast'),
        child: AnimatedContainer(
          duration: t.duration('glacier-duration-fast'),
          curve: t.curve('glacier-ease-out'),
          height: t.len('glacier-control-height-sm'),
          padding: EdgeInsets.symmetric(horizontal: t.space(4)),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: t.color(state.pressed ? 'glacier-accent-solid-hover' : 'glacier-accent-solid'),
            borderRadius: BorderRadius.circular(t.radius('full')),
          ),
          child: Text(label, style: t.text('sm', weight: 'semibold', color: t.color('glacier-accent-contrast')).copyWith(height: 1)),
        ),
      ),
    );
  }
}

/// One step of setup: a numbered badge that turns to a check once done, and the button that does it.
class SetupStep extends StatelessWidget {
  const SetupStep({
    super.key,
    required this.number,
    required this.title,
    required this.description,
    required this.done,
    required this.action,
    required this.onAction,
  });

  final int number;
  final String title;
  final String description;
  final bool done;
  final String action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final badge = t.len('glacier-control-height-sm') * 0.8;
    return Padding(
      padding: EdgeInsets.symmetric(horizontal: t.space(4), vertical: t.space(3)),
      child: Row(
        children: [
          AnimatedContainer(
            duration: t.duration('glacier-duration-fast'),
            width: badge,
            height: badge,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: t.color(done ? 'glacier-success-soft' : 'glacier-accent-soft'),
              shape: BoxShape.circle,
            ),
            child: done
                ? CustomPaint(size: Size.square(badge * 0.45), painter: _CheckPainter(t.color('glacier-success-text')))
                : Text('$number', style: t.text('sm', weight: 'semibold', color: t.color('glacier-accent-text')).copyWith(height: 1)),
          ),
          SizedBox(width: t.space(3)),
          Expanded(child: _RowText(title: title, description: description)),
          SizedBox(width: t.space(3)),
          if (done)
            const GlacierPill(tone: GlacierPillTone.success, size: GlacierPillSize.sm, child: Text('Done'))
          else
            GlacierButton(label: action, onPressed: onAction),
        ],
      ),
    );
  }
}

class _CheckPainter extends CustomPainter {
  _CheckPainter(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = size.width * 0.16
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    final path = Path()
      ..moveTo(size.width * 0.1, size.height * 0.55)
      ..lineTo(size.width * 0.4, size.height * 0.85)
      ..lineTo(size.width * 0.9, size.height * 0.2);
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(_CheckPainter old) => old.color != color;
}
