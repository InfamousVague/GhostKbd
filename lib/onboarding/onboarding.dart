/// The first-run walkthrough, made as Ghost.md's is (its lib/guide): a page at a time, each with a band of vivid colour
/// and its icons, a title, the words a friendly voice says aloud (ElevenLabs, made at build time:
/// narration.dart), and the step itself where there is one — turning the keyboard on, switching to it, adding
/// dictation, picking a look. Shown once, on first launch; Settings › Setup and About replay it.
library;

import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../art/app_icon.dart';
import '../art/lucide.g.dart';
import '../art/lucide_more.dart';
import '../market/market_cards.dart';
import '../market/market_plugins.dart' show marketHues;
import '../platform.dart';
import '../settings/kit/settings_type.dart';
import '../settings_store.dart';
import 'band.dart';
import 'narration.dart';

// The walkthrough as an iPhone has it (lib/platform.dart `isIOS`): its pages, their words and their steps.
part 'onboarding_ios.dart';

/// How one page looks: its wash, its sign and the tiles round it.
@immutable
class _Look {
  const _Look(this.hues, this.icon, this.tiles);
  final List<String> hues;
  final LucideIcon icon;
  final List<LucideIcon> tiles;
  Color get hue => marketHues[hues.first]!.$1;
}

List<(String, String, _Look)> get _pages => isIOS ? _iosPages : _androidPages;

const List<(String, String, _Look)> _androidPages = [
  ('welcome', 'Hi, I’m Ghost Keyboard', _Look(['indigo', 'purple', 'rose'], LucideMore.keyboard, [Lucide.braces, Lucide.mic, Lucide.palette, Lucide.spellCheck])),
  ('enable', 'Turn it on', _Look(['green', 'mint', 'cyan'], Lucide.settings, [LucideMore.keyboard, Lucide.shieldCheck, Lucide.smartphone, Lucide.check])),
  ('choose', 'Make it yours', _Look(['cyan', 'blue', 'indigo'], LucideMore.textCursorInput, [LucideMore.keyboard, Lucide.repeat, Lucide.circleCheck, Lucide.type])),
  ('code', 'Code, a tap away', _Look(['amber', 'orange', 'coral'], Lucide.braces, [Lucide.code, Lucide.terminal, Lucide.hash, Lucide.equal])),
  ('voice', 'Talk instead', _Look(['rose', 'red', 'orange'], Lucide.mic, [Lucide.audioLines, Lucide.waves, LucideMore.cpu, Lucide.shieldCheck])),
  ('plugins', 'Plugins', _Look(['indigo', 'purple', 'rose', 'orange'], Lucide.store, [Lucide.puzzle, Lucide.palette, Lucide.keyRound, Lucide.image])),
  ('look', 'Pick a look', _Look(['magenta', 'purple', 'blue'], Lucide.palette, [Lucide.sunMoon, Lucide.swatchBook, LucideMore.keyboard, Lucide.sparkles])),
  ('start', 'You’re ready', _Look(['rose', 'orange', 'amber'], LucideMore.rocket, [Lucide.graduationCap, Lucide.circleCheck, Lucide.flame, Lucide.sparkles])),
];

const _sounds = MethodChannel('dev_keyboard/settings');

/// Opens the walkthrough over the app.
Future<void> openOnboarding(BuildContext context, SettingsStore store, {bool replay = false}) => Navigator.of(context).push(
      PageRouteBuilder<void>(
        transitionDuration: const Duration(milliseconds: 320),
        pageBuilder: (context, _, _) => Onboarding(store: store),
        transitionsBuilder: (context, animation, _, child) => FadeTransition(opacity: animation, child: child),
      ),
    );

class Onboarding extends StatefulWidget {
  const Onboarding({super.key, required this.store, this.onDone});
  final SettingsStore store;
  final VoidCallback? onDone;

  @override
  State<Onboarding> createState() => _OnboardingState();
}

class _OnboardingState extends State<Onboarding> with WidgetsBindingObserver {
  final PageController _pager = PageController();
  int _page = 0;
  bool _voice = true;

  /// iOS: the keyboard says it has run, and with Full Access or not, only as it is used, which may be in this app's
  /// own field with the app never leaving the screen. So the walkthrough asks every couple of seconds.
  Timer? _iosStatus;

  SettingsStore get store => widget.store;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (isIOS) _iosStatus = Timer.periodic(const Duration(seconds: 2), (_) => store.refreshStatus());
    WidgetsBinding.instance.addPostFrameCallback((_) => _say());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _iosStatus?.cancel();
    _hush();
    _pager.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Back from the phone's keyboard list or the picker: look again, and go on by itself once the step is done.
    if (state == AppLifecycleState.resumed) {
      store.refreshStatus().then((_) {
        if (!mounted) return;
        final id = _pages[_page].$1;
        if ((id == 'enable' && store.enabled) || (id == 'choose' && store.selected) || (id == 'iosEnable' && store.enabled)) _next();
      });
    } else if (state == AppLifecycleState.paused) {
      _hush();
    }
  }

  Future<void> _say() async {
    if (!_voice) return;
    // iOS: only the pages that say Android's words have its voice.
    if (isIOS && iosNarration.containsKey(_pages[_page].$1)) return;
    try {
      await _sounds.invokeMethod<bool>('playAsset', {'asset': narrationAsset(_pages[_page].$1)});
    } on MissingPluginException {
      return;
    }
  }

  Future<void> _hush() async {
    try {
      await _sounds.invokeMethod<void>('stopAudio');
    } on MissingPluginException {
      return;
    }
  }

  void _go(int page) {
    if (page < 0 || page >= _pages.length) return;
    _pager.animateToPage(page, duration: const Duration(milliseconds: 380), curve: Curves.easeOutCubic);
  }

  void _next() => _page == _pages.length - 1 ? _finish() : _go(_page + 1);

  void _finish() {
    _hush();
    store.set('onboarded', true);
    if (widget.onDone != null) {
      widget.onDone!();
    } else {
      Navigator.of(context).maybePop();
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final pad = MediaQuery.paddingOf(context);
    final look = _pages[_page].$3;
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) => ColoredBox(
        color: t.color('glacier-bg'),
        child: Padding(
          // iOS: the keyboard comes up over the page, so the page makes room for it (the field to try it in is on it).
          padding: EdgeInsets.only(top: pad.top, bottom: isIOS && MediaQuery.viewInsetsOf(context).bottom > pad.bottom ? MediaQuery.viewInsetsOf(context).bottom : pad.bottom),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // The voice on or off, and the way out.
              Padding(
                padding: EdgeInsets.fromLTRB(t.space(4), t.space(2), t.space(4), 0),
                child: Row(
                  children: [
                    MarketButton(
                      label: _voice ? 'Voice on' : 'Voice off',
                      icon: _voice ? Lucide.volume2 : Lucide.minus,
                      look: MarketButtonLook.ring,
                      onPress: () {
                        setState(() => _voice = !_voice);
                        _voice ? _say() : _hush();
                      },
                    ),
                    const Spacer(),
                    MarketWord(word: 'Skip', onPress: _finish),
                  ],
                ),
              ),
              Expanded(
                child: PageView.builder(
                  controller: _pager,
                  itemCount: _pages.length,
                  onPageChanged: (page) {
                    setState(() => _page = page);
                    _hush().then((_) => _say());
                  },
                  itemBuilder: (context, i) => _pageAt(context, i),
                ),
              ),
              // The dots, and back and next.
              Padding(
                padding: EdgeInsets.fromLTRB(t.space(4), t.space(2), t.space(4), t.space(4)),
                child: Row(
                  children: [
                    for (var i = 0; i < _pages.length; i++)
                      AnimatedContainer(
                        duration: const Duration(milliseconds: 220),
                        margin: EdgeInsets.only(right: t.space(1)),
                        width: i == _page ? 18 : 7,
                        height: 7,
                        decoration: BoxDecoration(color: i == _page ? look.hue : t.color('app-rule'), borderRadius: BorderRadius.circular(4)),
                      ),
                    const Spacer(),
                    if (_page > 0) ...[MarketButton(label: 'Back', look: MarketButtonLook.ring, onPress: () => _go(_page - 1)), SizedBox(width: t.space(2))],
                    MarketButton(
                      label: _page == _pages.length - 1 ? 'Start typing' : 'Next',
                      icon: _page == _pages.length - 1 ? Lucide.check : Lucide.arrowRight,
                      look: MarketButtonLook.filled,
                      color: look.hue,
                      large: true,
                      onPress: _next,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _pageAt(BuildContext context, int i) {
    final t = context.tokens;
    final (id, title, look) = _pages[i];
    final screen = MediaQuery.sizeOf(context);
    return ListView(
      padding: EdgeInsets.symmetric(horizontal: t.space(4), vertical: t.space(3)),
      children: [
        HueBand(
          key: ValueKey('band-$id'),
          colours: hueColours(look.hues),
          height: (screen.height * 0.32).clamp(180.0, 320.0).toDouble(),
          sign: (h, w) => bandDisc(look.icon, height: h, width: w),
          tiles: [for (final icon in look.tiles) bandIcon(icon)],
        ),
        SizedBox(height: t.space(5)),
        Semantics(header: true, child: Text(title, style: pageText(t, t.fontSize('3xl'), weight: 'bold', height: 1.1, color: t.color('app-ink')))),
        SizedBox(height: t.space(3)),
        Text((isIOS ? iosNarration[id] : null) ?? narration[id]!, style: pageText(t, t.fontSize('md'), height: 1.5, color: t.color('app-ink-2'))),
        SizedBox(height: t.space(5)),
        ..._step(context, id, look),
      ],
    );
  }

  /// The page's own step, where it has one.
  List<Widget> _step(BuildContext context, String id, _Look look) {
    final t = context.tokens;
    if (isIOS) {
      final own = _iosStep(context, store, id, look);
      if (own != null) return own;
    }
    Widget done(String words) => Row(children: [AppIcon(Lucide.circleCheck, size: 20, color: look.hue), SizedBox(width: t.space(2)), Expanded(child: Text(words, style: pageText(t, t.fontSize('md'), weight: 'medium', color: t.color('app-ink'))))]);
    switch (id) {
      case 'enable':
        return [store.enabled ? done('Ghost Keyboard is on.') : MarketButton(label: 'Open keyboard settings', icon: Lucide.settings, look: MarketButtonLook.filled, color: look.hue, large: true, onPress: store.openInputSettings)];
      case 'choose':
        return [
          store.selected
              ? done('You’re typing with Ghost Keyboard.')
              : MarketButton(label: 'Switch keyboard', icon: Lucide.repeat, look: MarketButtonLook.filled, color: look.hue, large: true, onPress: store.enabled ? store.showPicker : null),
        ];
      case 'voice':
        return [
          store.pluginOn('dictation')
              ? done(store.dictation['phase'] == 'ready' ? 'Dictation is ready.' : 'Dictation is added: its model is downloading.')
              : MarketButton(label: 'Add Dictation', icon: Lucide.plus, look: MarketButtonLook.filled, color: look.hue, large: true, onPress: () => store.setPlugin('dictation', true)),
          SizedBox(height: t.space(2)),
          Text('It downloads 60 MB once and asks for the microphone.', style: pageText(t, t.fontSize('sm'), color: t.color('app-ink-3'))),
        ];
      case 'plugins':
        return [
          Wrap(
            spacing: t.space(2),
            runSpacing: t.space(2),
            children: [
              MarketChip(label: store.pluginOn('autocorrect') ? 'Autocorrect ✓' : 'Add Autocorrect', chosen: store.pluginOn('autocorrect'), onPress: () => store.setPlugin('autocorrect', !store.pluginOn('autocorrect'))),
              MarketChip(label: store.pluginOn('themes') ? 'Themes ✓' : 'Add Themes', chosen: store.pluginOn('themes'), onPress: () => store.setPlugin('themes', !store.pluginOn('themes'))),
            ],
          ),
        ];
      case 'look':
        return [
          GlacierSegmentedControl(
            options: const [
              GlacierSegmentedOption(value: 'system', label: Text('System')),
              GlacierSegmentedOption(value: 'light', label: Text('Light')),
              GlacierSegmentedOption(value: 'dark', label: Text('Dark')),
            ],
            value: store.choice('theme'),
            onValueChange: (v) => store.set('theme', v),
            fullWidth: true,
            ariaLabel: 'Light or dark',
          ),
          SizedBox(height: t.space(3)),
          Row(
            children: [
              Expanded(child: Text('Key borders', style: pageText(t, t.fontSize('md'), weight: 'medium', color: t.color('app-ink')))),
              GlacierSwitch(checked: store.flag('key_borders'), onCheckedChange: (v) => store.set('key_borders', v), ariaLabel: 'Key borders'),
            ],
          ),
        ];
      case 'start':
        return [const GlacierInput(placeholder: 'Try it here', ariaLabel: 'Try the keyboard')];
      default:
        return const [];
    }
  }
}
