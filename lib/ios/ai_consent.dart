/// iOS only: the person's agreement to what the AI features send, asked before Ghostwriter, Mockingbird or Rewrite is
/// turned on and before Test it or Train now (the App Store asks for it wherever personal data goes to a third party's
/// AI). It is one setting, `ai_consent`, which the keyboard reads too: without it the keyboard refuses AI. Agreed once,
/// it is not asked again; Settings › AI › Your AI shows it and withdraws it (settings/panes_ios.dart).
///
/// On Android none of this is shown, and every function here does at once what it did before.
library;

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../art/app_icon.dart';
import '../art/lucide.g.dart';
import '../market/market_cards.dart';
import '../platform.dart';
import '../settings/kit/settings_type.dart';
import '../settings_store.dart';

/// The plugins that send text to the AI provider.
const Set<String> aiPlugins = {'ghostwriter', 'mockingbird', 'rewrite'};

/// The consent's words, as the page draws them and as the privacy page repeats them: a heading and its paragraphs.
const String aiConsentTitle = 'Before the AI writes for you';
const String aiConsentLead = 'Ghostwriter, Mockingbird and Rewrite work by sending text to the AI provider you chose. Here is what is sent, to whom, and when.';
const List<(String, List<String>)> aiConsentWords = [
  (
    'What is sent',
    [
      'The request you type between << and >>.',
      'The text around the cursor, so the answer fits where it lands.',
      'The text you selected, or the field’s text, when you ask for a rewrite.',
      'Samples of what you typed with this keyboard, when a style profile is trained; and your style profile and the samples that match a request, when Ghostwriter writes as you.',
      'Only if you turn their switches on: your recent clipboard, and the messages of your GitHub commits that match a request. GitHub is searched with your own token.',
    ],
  ),
  (
    'Who receives it',
    [
      'The AI provider you chose in Settings, straight from this device, with your own API key. What they do with it is under their terms and privacy policy.',
      'Ghost Keyboard’s maker receives nothing: there is no server in between.',
    ],
  ),
  (
    'When',
    ['Only when you use these features: a <<request>>, a rewrite, training your style, or Test it. Ordinary typing is sent nowhere.'],
  ),
  (
    'Changing your mind',
    ['Turn it off any time in Settings › AI › Your AI. The keyboard then refuses every AI feature until you agree again.'],
  ),
];
const String aiConsentAgree = 'I agree';
const String aiConsentDecline = 'Not now';

/// True where the AI may be used: always on Android; on iOS once agreed, asking now if not yet.
Future<bool> ensureAiConsent(BuildContext context, SettingsStore store) async {
  if (!isIOS || store.aiConsent) return true;
  final agreed = await Navigator.of(context).push<bool>(
    PageRouteBuilder<bool>(
      transitionDuration: const Duration(milliseconds: 260),
      pageBuilder: (context, _, _) => AiConsentPage(store: store),
      transitionsBuilder: (context, animation, _, child) => FadeTransition(opacity: animation, child: child),
    ),
  );
  return agreed == true;
}

/// Adds or removes a plugin; on iOS, adding one of [aiPlugins] asks for the consent first, and Not now leaves it off.
Future<void> setPluginAsking(BuildContext context, SettingsStore store, String id, bool on) {
  if (!isIOS || !on || !aiPlugins.contains(id)) return store.setPlugin(id, on);
  return ensureAiConsent(context, store).then((agreed) => agreed ? store.setPlugin(id, true) : null);
}

/// [action] itself on Android; on iOS, [action] once the consent is given.
VoidCallback aiAsking(BuildContext context, SettingsStore store, Future<void> Function() action) {
  if (!isIOS) return action;
  return () async {
    if (await ensureAiConsent(context, store)) await action();
  };
}

/// The consent, a page of its own over the app: what is sent, to whom and when, with I agree and Not now under it.
/// Pops true when agreed.
class AiConsentPage extends StatelessWidget {
  const AiConsentPage({super.key, required this.store});
  final SettingsStore store;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final pad = MediaQuery.paddingOf(context);
    final body = pageText(t, t.fontSize('md'), height: 1.45, color: t.color('app-ink-2'));
    return ColoredBox(
      color: t.color('glacier-bg'),
      child: Padding(
        padding: EdgeInsets.only(top: pad.top, bottom: pad.bottom),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: ListView(
                padding: EdgeInsets.fromLTRB(t.space(4), t.space(5), t.space(4), t.space(4)),
                children: [
                  AppIcon(Lucide.shieldCheck, size: 32, strokeWidth: 2, color: t.color('app-ink')),
                  SizedBox(height: t.space(4)),
                  Semantics(header: true, child: Text(aiConsentTitle, style: pageText(t, t.fontSize('3xl'), weight: 'bold', height: 1.1, color: t.color('app-ink')))),
                  SizedBox(height: t.space(3)),
                  Text(aiConsentLead, style: body),
                  for (final (heading, lines) in aiConsentWords) ...[
                    SizedBox(height: t.space(5)),
                    Semantics(header: true, child: Text(heading, style: pageText(t, t.fontSize('md'), weight: 'semibold', color: t.color('app-ink')))),
                    for (final line in lines) ...[
                      SizedBox(height: t.space(2)),
                      if (lines.length > 2)
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('•  ', style: body),
                            Expanded(child: Text(line, style: body)),
                          ],
                        )
                      else
                        Text(line, style: body),
                    ],
                  ],
                ],
              ),
            ),
            Padding(
              padding: EdgeInsets.fromLTRB(t.space(4), t.space(2), t.space(4), t.space(4)),
              child: Row(
                children: [
                  MarketButton(key: const ValueKey('ai-consent-decline'), label: aiConsentDecline, look: MarketButtonLook.ring, large: true, onPress: () => Navigator.of(context).pop(false)),
                  const Spacer(),
                  MarketButton(
                    key: const ValueKey('ai-consent-agree'),
                    label: aiConsentAgree,
                    icon: Lucide.check,
                    look: MarketButtonLook.ink,
                    large: true,
                    onPress: () {
                      store.set('ai_consent', true);
                      Navigator.of(context).pop(true);
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
