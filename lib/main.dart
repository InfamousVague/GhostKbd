import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'onboarding/onboarding.dart';
import 'settings/panes.dart';
import 'settings/settings_css.dart';
import 'settings/settings_screen.dart';
import 'settings_store.dart';

/// Ghost Keyboard's setup and settings. The keyboard itself is native (android/.../DevKeyboardService.kt); this app
/// walks a new person through turning it on, then is its Settings and its plugin Market, drawn with Glacier and
/// Ghost.md's own settings kit, icons and stylesheets (assets/css/ink.css, app.css).
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  final sheet = await loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css']);
  installSettingsStyles(sheet);
  final store = SettingsStore();
  await store.load();
  runApp(GhostKeyboardApp(sheet: sheet, store: store));
}

class GhostKeyboardApp extends StatelessWidget {
  const GhostKeyboardApp({super.key, required this.sheet, required this.store});

  final TokenSheet sheet;
  final SettingsStore store;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) {
        final theme = store.choice('theme');
        return WidgetsApp(
          title: 'Ghost Keyboard',
          color: const Color(0xFF4F5BD5),
          debugShowCheckedModeBanner: false,
          pageRouteBuilder: <T>(RouteSettings settings, WidgetBuilder builder) =>
              PageRouteBuilder<T>(settings: settings, pageBuilder: (context, _, _) => builder(context)),
          builder: (context, child) => GlacierTheme(
            sheet: sheet,
            attrs: {'platform': 'mobile', if (theme != 'system') 'theme': theme},
            typography: const GlacierTypography(features: [FontFeature('cv11'), FontFeature('ss01')]),
            child: HapticsProvider(enabled: store.flag('vibrate'), child: _InkOverrides(child: _SystemBars(child: child!))),
          ),
          home: _Home(store: store),
        );
      },
    );
  }
}

/// The walkthrough on first launch, then Settings.
class _Home extends StatefulWidget {
  const _Home({required this.store});
  final SettingsStore store;

  @override
  State<_Home> createState() => _HomeState();
}

class _HomeState extends State<_Home> with WidgetsBindingObserver {
  SettingsStore get store => widget.store;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    store.openRequests.addListener(_openRequested);
    store.openAt().then((page) {
      if (page != null) store.openRequests.value = page;
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    store.openRequests.removeListener(_openRequested);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The keyboard runs in this app's process and goes on learning while Settings is in the background: what it has
    // counted (word pairs among it) is read again each time the app comes back to the front.
    if (state == AppLifecycleState.resumed) { store.refreshStatus(); store.refreshAi(); store.refreshAdaptive(); store.refreshStats(); store.refreshProfiles(); store.refreshNet(); store.refreshNextWords(); store.refreshUpdate(); store.refreshSmsCodes(); store.refreshMailCodes(); store.refreshGhostMessagingCodes(); store.refreshStrip(); store.refreshClipboard(); store.refreshShortcuts(); store.refreshConnectors(); }
  }

  /// The keyboard asked for a page: its section, over Settings.
  void _openRequested() {
    final id = store.openRequests.value;
    if (id == null || !mounted) return;
    store.openRequests.value = null;
    final section = everySection(settingsSections(store), store).where((s) => s.id == id).firstOrNull;
    if (section == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      // A section with a screen of its own (the trainer) opens on it, as it does from the list.
      if (mounted) pushSettings(context, section.screen ?? (_) => SectionPage(section: section, store: store));
    });
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) {
        if (!store.flag('onboarded')) return Onboarding(store: store, onDone: () => setState(() {}));
        return SettingsPage(
          title: 'Ghost Keyboard',
          back: false,
          child: SettingsHome(store: store, sections: settingsSections(store)),
        );
      },
    );
  }
}

/// What Ghost.md's ink.css does to the kit's parts that no token reaches (its lib/app/ghost_app.dart `_InkOverrides`):
/// the selected segment's thumb is ink, so its label is paper; the switch's thumb is paper, where the kit's near-white
/// vanished on the dark theme's near-white track; and the segment's thumb has no shadow.
class _InkOverrides extends StatelessWidget {
  const _InkOverrides({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) => GlacierOverrides(switchThumbColor: _paper, segmentedSelectedLabelColor: _paper, segmentedThumbShadow: _none, child: child);

  static Color _paper(Tokens t) => t.color('app-paper');
  static List<BoxShadow> _none(Tokens t) => const [];
}

/// The status and navigation bars' icons, dark on the light theme and light on the dark.
class _SystemBars extends StatelessWidget {
  const _SystemBars({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final dark = context.tokens.isDark;
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: (dark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark).copyWith(
        statusBarColor: const Color(0x00000000),
        systemNavigationBarColor: const Color(0x00000000),
      ),
      child: child,
    );
  }
}
