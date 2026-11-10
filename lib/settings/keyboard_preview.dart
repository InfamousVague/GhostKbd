/// The live preview at the foot of every settings page (Matt, 2026-11-09: "live preview for the keyboard on
/// settings"): the keyboard's own native view (android KeyboardPreview.kt), drawn in the app, so a theme, a key
/// height, the number row, the backlight or a layout shows as its switch is flipped, whether or not Ghost Keyboard is
/// the keyboard in use. A Preview button beside the try-it field opens and closes it, and it stays as it was left.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../art/app_icon.dart';
import '../art/lucide_more.dart';
import '../settings_store.dart';
import 'kit/settings_type.dart';

/// How tall the native view draws itself, in logical pixels (KeyboardView's own sums: the strip above the keys, the
/// toolbar or its gap, the rows, and the pad under them).
double keyboardPreviewHeight(SettingsStore store) {
  final condensed = store.choice('key_height') == 'condensed';
  final row = switch (store.choice('key_height')) { 'condensed' => 46.0, 'short' => 52.0, 'tall' => 68.0, _ => 60.0 };
  final rows = store.flag('number_row') ? 5 : 4;
  final toolbar = store.flag('toolbar') && !store.flag('hide_row');
  return 40 + (toolbar ? (condensed ? 34 : 44) : (condensed ? 8 : 18)) + rows * row + 2;
}

/// The keyboard itself, live. Only Android has it; anywhere else (a test, a desktop run) it is nothing.
class KeyboardPreview extends StatelessWidget {
  const KeyboardPreview({super.key, required this.store});
  final SettingsStore store;

  @override
  Widget build(BuildContext context) {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return const SizedBox.shrink();
    return SizedBox(
      height: keyboardPreviewHeight(store),
      child: AndroidView(
        viewType: 'ghost_keyboard_preview',
        layoutDirection: TextDirection.ltr,
        // The keys take every touch that starts on them: a slide along the space bar is not the page's to scroll.
        gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{Factory<OneSequenceGestureRecognizer>(() => EagerGestureRecognizer())},
      ),
    );
  }
}

/// The Preview button beside the try-it field: filled while the preview is open.
class KeyboardPreviewButton extends StatelessWidget {
  const KeyboardPreviewButton({super.key, required this.store});
  final SettingsStore store;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final open = store.flag('preview_open');
    final ink = t.color(open ? 'app-paper' : 'app-ink');
    return GlacierPressable(
      onPressed: () {
        // Opening it puts the real keyboard away: the two would stand on top of each other.
        if (!open) FocusManager.instance.primaryFocus?.unfocus();
        store.set('preview_open', !open);
      },
      semanticLabel: open ? 'Hide the keyboard preview' : 'Show the keyboard preview',
      builder: (context, state) => Opacity(
        opacity: state.pressed ? 0.7 : 1,
        child: Container(
          height: t.len('glacier-control-height-md'),
          padding: EdgeInsets.symmetric(horizontal: t.space(3)),
          decoration: BoxDecoration(color: t.color(open ? 'app-ink' : 'app-paper-2'), borderRadius: BorderRadius.circular(t.radius('md'))),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              AppIcon(LucideMore.keyboard, size: 18, strokeWidth: 2, color: ink, wash: false),
              SizedBox(width: t.space(2)),
              Text('Preview', style: pageText(t, t.fontSize('sm'), weight: 'medium', color: ink)),
            ],
          ),
        ),
      ),
    );
  }
}
