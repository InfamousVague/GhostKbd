import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'settings_page.dart';
import 'settings_store.dart';

/// Dev Keyboard's setup and settings. The keyboard itself is native (android/.../DevKeyboardService.kt); this app
/// turns it on and configures it, drawn with the Glacier kit.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  final sheet = await loadTokenSheet();
  final store = SettingsStore();
  await store.load();
  runApp(DevKeyboardApp(sheet: sheet, store: store));
}

class DevKeyboardApp extends StatelessWidget {
  const DevKeyboardApp({super.key, required this.sheet, required this.store});

  final TokenSheet sheet;
  final SettingsStore store;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) {
        // The app wears the theme the keyboard is set to, so the two always match.
        final theme = store.choice('theme');
        return WidgetsApp(
          title: 'Dev Keyboard',
          color: const Color(0xFF2F6FEB),
          debugShowCheckedModeBanner: false,
          pageRouteBuilder: <T>(RouteSettings settings, WidgetBuilder builder) =>
              PageRouteBuilder<T>(settings: settings, pageBuilder: (context, _, _) => builder(context)),
          builder: (context, child) => GlacierTheme(
            sheet: sheet,
            attrs: {
              'platform': 'mobile',
              if (theme != 'system') 'theme': theme,
            },
            typography: const GlacierTypography(features: [FontFeature('cv11'), FontFeature('ss01')]),
            child: HapticsProvider(
              enabled: store.flag('vibrate'),
              child: _SystemBars(child: child!),
            ),
          ),
          home: SettingsPage(store: store),
        );
      },
    );
  }
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
