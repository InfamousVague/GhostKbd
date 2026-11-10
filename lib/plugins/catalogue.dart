/// Ghost Keyboard's plugins, as the Market lists them: Ghost.md's model (its lib/plugins/types.dart), smaller. A plugin
/// ships inside the app, does nothing until it is added, and says what it is and what it may reach in a manifest. The
/// keyboard's half of each is Kotlin (android/.../Plugins.kt and the plugin's own files); which are added is one
/// setting both sides read.
library;

import 'package:flutter/widgets.dart';

import '../art/app_icon.dart';
import '../art/lucide.g.dart';
import '../art/lucide_more.dart';

/// Where a plugin stands in the Market's filter.
enum PluginCategory {
  typing('Typing'),
  voice('Voice'),
  look('Look'),
  security('Passwords'),
  media('Media'),
  tools('Tools'),
  sound('Sound');

  const PluginCategory(this.label);
  final String label;
}

/// What a plugin may reach.
enum Permission { microphone, network, autofill, download, screen }

const Map<Permission, String> plainPermissionWords = {
  Permission.microphone: 'Your microphone',
  Permission.network: 'The internet',
  Permission.autofill: 'Your password manager',
  Permission.download: 'Space on your phone',
  Permission.screen: 'What’s on your screen',
};

LucideIcon permissionIcon(Permission kind) => switch (kind) {
      Permission.microphone => Lucide.mic,
      Permission.network => Lucide.globe,
      Permission.autofill => Lucide.keyRound,
      Permission.download => LucideMore.hardDriveDownload,
      Permission.screen => LucideMore.scanSearch,
    };

@immutable
class PluginPermission {
  const PluginPermission(this.kind, this.why);
  final Permission kind;
  final String why;
}

@immutable
class PluginManifest {
  const PluginManifest({
    required this.id,
    required this.name,
    required this.line,
    required this.description,
    required this.category,
    required this.hue,
    required this.icon,
    this.adds = const [],
    this.permissions = const [],
    this.hosts = const [],
    this.version = '1.0',
    this.author = 'InfamousVague',
    this.soon,
    this.hasPage = false,
  });

  final String id;
  final String name;

  /// The one line under its name on its card.
  final String line;
  final String description;
  final PluginCategory category;

  /// Its colour, by the names the Market's hues keep (lib/market/market_plugins.dart `marketHues`).
  final String hue;
  final LucideIcon icon;
  final List<String> adds;
  final List<PluginPermission> permissions;

  /// The servers it may reach, for where the app's own ledger cannot be read (off-device). On a phone the Market shows
  /// the ledger's list for this plugin instead (android NetLedger.kt, through SettingsStore.netHosts), which is the
  /// list the guard enforces: keep the two the same.
  final List<String> hosts;
  final String version;
  final String author;

  /// The release it arrives in, where it is listed before it can be added.
  final String? soon;

  /// It has a page of its own in Settings once added.
  final bool hasPage;
}

/// Every plugin, in the Market's order.
const List<PluginManifest> catalogue = [
  PluginManifest(
    id: 'autocorrect',
    name: 'Autocorrect',
    line: 'Fixes a misspelled word as you finish it, and knows a programmer’s words.',
    description:
        'When you finish a word with a space or punctuation, Autocorrect checks it against the commonest words of your languages, '
        'their contractions and a programmer’s vocabulary (npm, async, Kotlin…), and swaps a slip of the thumb for the '
        'word you meant. Backspace straight after puts back exactly what you typed. It stays out of passwords, '
        'addresses and fields that ask for no suggestions, and leaves code-shaped words alone.',
    category: PluginCategory.typing,
    hue: 'lime',
    icon: Lucide.spellCheck,
    adds: ['Corrections when you finish a word', 'Backspace to undo a correction', 'Don’t → don’t, i → I', 'Three next words after a space, tuned by what you type'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'dictation',
    name: 'Dictation',
    line: 'Speak instead of typing: whisper, running on your phone.',
    description:
        'A microphone key beside the space bar. Tap it, talk, and tap again: whisper turns what you said into text on '
        'your phone, with nothing sent anywhere. It starts with Ghost.md\'s English model, base.en; models that hear '
        'about a hundred languages (tiny, base, small, large v3 turbo) can be downloaded in its settings.',
    category: PluginCategory.voice,
    hue: 'rose',
    icon: Lucide.mic,
    adds: ['A mic key beside the space bar', 'Speech to text on your phone, offline', 'Models for English or about a hundred languages'],
    permissions: [
      PluginPermission(Permission.microphone, 'To hear you while you hold the mic open, and only then.'),
      PluginPermission(Permission.download, 'Its speech model: 60 MB to start; bigger and multilingual models if you choose them.'),
      PluginPermission(Permission.network, 'Only to download the model.'),
    ],
    hosts: ['attack.fm', 'huggingface.co', '*.huggingface.co', '*.hf.co'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'themes',
    name: 'Themes',
    line: 'Colour the keyboard with a preset, or make a theme of your own.',
    description:
        'Presets, from Glacier’s ice blue to Retro’s raised beige keycaps, and a theme of your own from one colour you '
        'pick: the keys, the function keys and the letters are worked out from it.',
    category: PluginCategory.look,
    hue: 'magenta',
    icon: Lucide.palette,
    adds: ['Preset themes, Retro’s raised keycaps among them', 'A theme of your own from one colour'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'swipe',
    name: 'Swipe typing',
    line: 'Draw a word, or a few, across the keys without lifting your thumb.',
    description: 'Glide from letter to letter and lift: the word your path traced is typed. Keep going into the next '
        'word and the next, and the whole phrase comes out in one stroke ("hey what’s up").',
    category: PluginCategory.typing,
    hue: 'cyan',
    icon: LucideMore.pointer,
    adds: ['Glide typing on the letters', 'Several words in one stroke', 'Other guesses in the row above the keys'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'passwords',
    name: 'Password managers',
    line: 'Your password manager’s logins, a tap above the keys.',
    description:
        'NordPass, 1Password, Bitwarden and any other autofill service: their suggestions for the field you are in, as '
        'chips above the keys, on Android 11 and later.',
    category: PluginCategory.security,
    hue: 'green',
    icon: Lucide.keyRound,
    permissions: [PluginPermission(Permission.autofill, 'To show the logins your password manager offers for the field you are in.')],
    adds: ['Logins in the row above the keys'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'gifs',
    name: 'GIFs',
    line: 'Search GIPHY and send a GIF from the keyboard.',
    description: 'A GIF key, a search, and a tap to send into any app that takes pictures.',
    category: PluginCategory.media,
    hue: 'orange',
    icon: Lucide.image,
    permissions: [PluginPermission(Permission.network, 'To search GIPHY and fetch the GIF you pick.')],
    hosts: ['api.giphy.com', '*.giphy.com'],
    adds: ['A GIF button in the row above the keys', 'GIPHY search'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'markdown',
    name: 'Markdown',
    line: 'Bold, code, links and lists a tap above the keys.',
    description:
        'Buttons in the row for bold, italic, strikethrough, code, code blocks, links, headings, lists, tasks and quotes. '
        'They wrap what you selected, or leave the cursor inside an empty pair. They show in notes apps (Ghost.md, '
        'Obsidian, Joplin, Markor, GitHub…) on their own, and everywhere if you like. In Ghost.md, a [[ ]] button links notes.',
    category: PluginCategory.tools,
    hue: 'indigo',
    icon: Lucide.hash,
    adds: ['Markdown buttons in the row', 'A [[note link]] button in Ghost.md'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'terminal',
    name: 'Terminal mode',
    line: 'Esc, Tab, Ctrl, Alt and arrows for SSH and Termux.',
    description:
        'The keys a terminal needs and a phone keyboard lacks: Esc, Tab, sticky Ctrl and Alt (tap Ctrl, then C for Ctrl+C), '
        'arrow keys, and | ~ / - && >. They show in terminal apps (Termux, Termius, JuiceSSH, ConnectBot…) on their own, '
        'and everywhere if you like.',
    category: PluginCategory.tools,
    hue: 'slate',
    icon: Lucide.terminal,
    adds: ['Terminal keys in the row'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'clipboard',
    name: 'Clipboard history',
    line: 'Your last 25 copies, a tap away.',
    description:
        'Everything you copy while Ghost Keyboard is your keyboard, newest first, in the row: tap to paste. Something '
        'you just copied waits as the row\'s first chip. Kept on your phone only, and never what Android marks sensitive '
        '(password managers\' copies) or anything copied while incognito.',
    category: PluginCategory.tools,
    hue: 'amber',
    icon: Lucide.clipboardPaste,
    adds: ['A Clipboard button in the row', 'The last copy as a chip'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'undo',
    name: 'Undo and redo',
    line: 'Take back the last thing the keyboard did, or put it back.',
    description:
        'Undo and Redo arrows in the row. The keyboard keeps its own list of what it did in the field you are in (words '
        'typed, deletions, autocorrect’s fixes, swiped words, pastes, emoji, markdown, snippets, Ghostwriter and Rewrite '
        'answers, dictation) and takes each back the same way in every app, a word or an action at a time, with the '
        'cursor put back where it was. The list is kept in memory for that one field, never saved, and never kept in a '
        'password field. If the text changed some other way, or the keyboard cannot read the field, it forgets its list '
        'rather than guess and asks the app to undo instead. An arrow dims when there is nothing for it to do.',
    category: PluginCategory.tools,
    hue: 'blue',
    icon: Lucide.undo2,
    adds: ['Undo and Redo in the row', 'The same in every app', 'A word, a paste, a fix or an AI answer per step'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'incognito',
    name: 'Incognito',
    line: 'Type without the keyboard learning or remembering anything.',
    description:
        'A switch in the row. While it is on, nothing is learned from your typing and nothing copied joins the clipboard '
        'history. Fields that ask for privacy (a browser\'s private tab) are incognito on their own, plugin or not.',
    category: PluginCategory.security,
    hue: 'purple',
    icon: Lucide.eyeOff,
    adds: ['An Incognito switch in the row'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'emoji',
    name: 'Emoji',
    line: 'Every emoji, searchable, up to Unicode’s newest.',
    description:
        'An emoji panel with tabs and your recent ones, and a search that knows names, keywords and shortcodes: every emoji in '
        'Emoji 18.0, Unicode’s latest (those your phone’s font can draw). Type :shortcode for emoji in the row, and get an emoji '
        'offered for the word you type (pizza 🍕). A skin tone of your choice.',
    category: PluginCategory.typing,
    hue: 'yellow',
    icon: LucideMore.smile,
    adds: ['An emoji panel and search', ':shortcodes', 'Emoji for the word you type'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'ghostmd',
    name: 'Ghost.md',
    line: 'Your notes and the keyboard, working together.',
    description:
        'In Ghost.md: [[ offers your notes’ titles and # your tags, and Ghost.md’s blocks (callouts, tables, math, diagrams, '
        'today’s date, tasks, voice notes) are a tap away. Everywhere else: select text and save it to Ghost.md as a note.',
    category: PluginCategory.tools,
    hue: 'cyan',
    icon: Lucide.ghost,
    adds: ['[[note]] and #tag completion in Ghost.md', 'Ghost.md’s blocks in the row', 'Save a selection to Ghost.md from any app'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'mockingbird',
    name: 'Mockingbird',
    line: 'The AI learns how you write and text, and writes like you.',
    description:
        'Mockingbird keeps samples of what you type on this phone: your texts in chat apps, your writing everywhere else. '
        'The AI you choose (Claude, OpenAI, Gemini, Mistral, Groq, DeepSeek, OpenRouter or your own server) reads them '
        'and writes your style profile: punctuation, length, tone, the words you use. Obvious slips of the thumb are cleaned out '
        'of the samples, and only a habit you repeat (lower case, u for you, no apostrophes) is learned as yours, so it '
        'writes like you without your typos. Ghostwriter writes in that voice, '
        'and selecting text offers In my voice to rewrite it as you. Nothing is kept from passwords, email or web '
        'addresses, or while incognito; the samples never leave the phone except, once, to train.',
    category: PluginCategory.typing,
    hue: 'purple',
    icon: LucideMore.bird,
    adds: ['A style profile of your texting and writing', 'In my voice: rewrite a selection as you', 'Works with Claude, OpenAI, Gemini and more'],
    permissions: [
      PluginPermission(Permission.network, 'To train your profile and rewrite text, with your own AI key, straight to the provider you choose.'),
    ],
    hosts: ['the AI provider you choose'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'ghostwriter',
    name: 'Ghostwriter',
    line: 'Type <<what you want>> in any app and the AI writes it, as you.',
    description:
        'Type <<engineering standup tldr>> or <<why do the metrics not fire on signup>> anywhere. When you close it with '
        '>> the AI writes the answer in your voice and it replaces the request: it reads Mockingbird’s style profile, '
        'your past messages that match the request, the text around it and, if you let it, your clipboard, your Ghost.md '
        'notes, your GitHub commits and the web. It types the answer out, keys lighting as it goes. Where it doesn’t know a '
        'fact it leaves a [blank] rather than guessing. << >> keeps clear of templates and code that use braces.',
    category: PluginCategory.tools,
    hue: 'indigo',
    icon: LucideMore.wandSparkles,
    adds: ['<<request>> filled in place, in any app', 'Answers from your messages, notes, commits and the web', 'A Fill button when the cursor is on a request'],
    permissions: [
      PluginPermission(Permission.network, 'To send the request, the text around it and the matching past messages to the AI you choose, with your own key.'),
    ],
    hosts: ['the AI provider you choose', 'api.github.com', 'ghostmarkdown.com'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'sherlock',
    name: 'Sherlock',
    line: 'Reads the chat on your screen and drafts your reply, in your words.',
    description:
        'Open the keyboard in a chat and Sherlock reads the conversation on the screen, works out who said what, and '
        'drafts the reply you would send, in your voice (Mockingbird’s style profile and your past messages, when you '
        'have Mockingbird). The draft waits in the row: tap it to put it in the box, or Another for a different one. '
        'Type a few words first (“no, busy, maybe sunday”) and tap Reply, and it writes that as you. It never sends '
        'anything: you read it and press send. It reads the app’s own text where it can, and on Android 11 or later '
        'reads a picture of the screen with on-phone OCR where an app draws its own. It needs its switch in Android’s '
        'accessibility settings, and reads nothing in password fields or while incognito.',
    category: PluginCategory.tools,
    hue: 'cyan',
    icon: LucideMore.scanSearch,
    adds: ['A drafted reply as you open a chat', 'Reply: your few words written out in your voice', 'Works in any chat app, with OCR where text can’t be read'],
    permissions: [
      PluginPermission(Permission.screen, 'To read the chat under the keyboard, with a switch you turn on in Android’s accessibility settings. Only when it drafts a reply.'),
      PluginPermission(Permission.network, 'To send the conversation on your screen to the AI you choose, with your own key, for the one reply. Nothing is kept.'),
    ],
    hosts: ['api.anthropic.com', 'api.openai.com', 'generativelanguage.googleapis.com', 'api.mistral.ai', 'api.groq.com', 'api.deepseek.com', 'openrouter.ai'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'rewrite',
    name: 'Rewrite',
    line: 'Fix, shorten, restyle or translate what you wrote, in place.',
    description:
        'A Rewrite button in the row above the keys. Tap it, then Fix (spelling, grammar and punctuation, nothing else), '
        'Shorter, Formal, Casual, Friendlier, or Translate into the language you choose. It works on the text you '
        'selected, or on the whole field when nothing is selected, and the answer replaces it; undo puts yours back. '
        'It uses the AI you choose (Claude, OpenAI, Gemini, Mistral, Groq, DeepSeek, OpenRouter or your own server) '
        'with your own key, straight from your phone. Never in password fields or while incognito.',
    category: PluginCategory.tools,
    hue: 'mint',
    icon: Lucide.penLine,
    adds: ['A Rewrite button in the row', 'Fix, Shorter, Formal, Casual, Friendlier', 'Translate into any language'],
    permissions: [
      PluginPermission(Permission.network, 'To send the text you chose to rewrite to the AI you choose, with your own key.'),
    ],
    hosts: ['the AI provider you choose'],
    hasPage: true,
  ),
  PluginManifest(
    id: 'adaptive',
    name: 'Adaptive keys',
    line: 'Nudges a key toward your slips, slowly, and hands it back as your aim comes right.',
    description:
        'Every keyboard has its slips: an i that keeps landing on o, an a your thumb reaches short of. Adaptive keys watches '
        'what you take back (backspace, then the letter beside it) and what autocorrect fixes, and nudges the invisible '
        'edge of the letter you meant toward where you slipped: a little for each slip, so it takes about ten before you '
        'feel it, and never more than a third of a key. It is training wheels, not a new layout: every clean touch lets '
        'the key back toward its place, and every nudge halves in two weeks, so the keys return to the defaults as your '
        'aim does. Turn on the heatmap to see your mistakes glow on the keyboard and where each key now listens. It all '
        'stays on this phone, and nothing is learned in password fields or while incognito.',
    category: PluginCategory.typing,
    hue: 'coral',
    icon: LucideMore.target,
    adds: ['Touch areas nudged toward your slips', 'Slow to move, and back to the defaults over time', 'A heatmap of your mistakes on the keys'],
    hasPage: true,
  ),
];

/// Settings › Backlight's effects (android Backlight.kt): id, name, what it does.
const List<(String, String, String)> backlightEffects = [
  ('reactive', 'Reactive', 'The key you press glows, then fades.'),
  ('ripple', 'Ripple', 'A ring of light runs out from each key.'),
  ('heatmap', 'Heatmap', 'Keys warm with use and cool when left.'),
  ('rainbow', 'Rainbow wave', 'A slow hue wave across the board; presses flare.'),
  ('starlight', 'Starlight', 'Keys twinkle at random; presses flare.'),
  ('breathe', 'Breathing', 'The board breathes softly; presses flare.'),
];

/// The backlight's colours: id, name, and its colour (0 for the theme's primary colour, -1 for the rainbow).
const List<(String, String, int)> backlightColours = [
  ('accent', 'Primary', 0),
  ('rainbow', 'Rainbow', -1),
  ('ghost', 'Ghost', 0xFF51FCFB),
  ('ice', 'Ice', 0xFF7FD8FF),
  ('phosphor', 'Phosphor', 0xFF39FF88),
  ('warm', 'Warm', 0xFFFF8A3D),
  ('violet', 'Violet', 0xFFB47CFF),
  ('rose', 'Rose', 0xFFFF5C8A),
  ('white', 'White', 0xFFFFFFFF),
];

/// Settings › Sounds' stock sounds, without the sounds plugin (android SoundPacks.STOCK); more come over time.
const List<(String, String)> stockSounds = [
  ('bamboo', 'Thock'),
  ('geiger', 'Geiger counter'),
  ('ping-pong', 'Ping pong'),
  ('felt', 'Felt mallet'),
  ('velvet', 'Velvet'),
  ('button', 'Button'),
  ('system', 'Android’s'),
];


/// Plugins that are part of every keyboard, switched on Settings' own pages (Typing, Look & feel, AI, Row tools)
/// rather than offered in the Market. Mockingbird and Ghostwriter stay Market plugins (Matt, 2026-11-09: "ghostwriter should be
/// a plugin still", "mockingbird should be a plugin as well"), each with a page of its own once added.
const Set<String> builtInPlugins = {
  'autocorrect', 'swipe', 'adaptive', 'themes', 'clipboard', 'undo', 'emoji', 'gifs', 'markdown', 'terminal', 'incognito',
};

/// What the Market offers: the add-ons that need setting up of their own.
List<PluginManifest> get marketCatalogue => [for (final p in catalogue) if (!builtInPlugins.contains(p.id)) p];

/// The plugins every keyboard has, as the Market's Installed tab lists them beside the added ones.
List<PluginManifest> get builtInCatalogue => [for (final p in catalogue) if (builtInPlugins.contains(p.id)) p];

/// Settings › Look & feel › Layout's choices for a wide screen (android KeyboardView.wideLayout): id, name, what it does.
const List<(String, String, String)> wideLayouts = [
  ('split', 'Split', 'Two halves, a phone’s key size each, with the middle open for your thumbs.'),
  ('full', 'Full width', 'One keyboard from edge to edge, with wide keys.'),
  ('left', 'Docked left', 'A phone-sized keyboard under your left thumb.'),
  ('center', 'Centred', 'A phone-sized keyboard in the middle.'),
  ('right', 'Docked right', 'A phone-sized keyboard under your right thumb.'),
];

PluginManifest? pluginById(String id) {
  for (final plugin in catalogue) {
    if (plugin.id == id) return plugin;
  }
  return null;
}

/// A server as the Market says it: a name as it is, and "*.giphy.com" as the servers under giphy.com.
String hostWords(String host) => host.startsWith('*.') ? 'the servers under ${host.substring(2)}' : host;

/// "a", "a and b", "a, b and c".
String joinAnd(List<String> words) => switch (words.length) {
      0 => '',
      1 => words.single,
      _ => '${words.sublist(0, words.length - 1).join(', ')} and ${words.last}',
    };

/// The themes plugin's presets, the ids KeyboardTheme.kt keeps, with their names and five colours for a swatch.
const List<(String, String, List<int>)> themePresets = [
  ('ghost', 'Ghost', [0xFF0A0B0C, 0xFF232528, 0xFF16181A, 0xFF51FCFB, 0xFFE8EDED]),
  ('glacier', 'Glacier', [0xFF0E1621, 0xFF1D2A3A, 0xFF152030, 0xFF7CC4FA, 0xFFE6F1FB]),
  ('midnight', 'Midnight', [0xFF000000, 0xFF1A1A1A, 0xFF0D0D0D, 0xFF8AB4F8, 0xFFFFFFFF]),
  ('paper', 'Paper', [0xFFF4EFE6, 0xFFFFFCF6, 0xFFE4DCCD, 0xFFB5522B, 0xFF2B2622]),
  ('retro', 'Retro', [0xFF34302B, 0xFFE9E1CD, 0xFFA8A190, 0xFFD9542B, 0xFF2E2A24]),
];

