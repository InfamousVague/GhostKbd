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
enum Permission { microphone, network, autofill, download }

const Map<Permission, String> plainPermissionWords = {
  Permission.microphone: 'Your microphone',
  Permission.network: 'The internet',
  Permission.autofill: 'Your password manager',
  Permission.download: 'Space on your phone',
};

LucideIcon permissionIcon(Permission kind) => switch (kind) {
      Permission.microphone => Lucide.mic,
      Permission.network => Lucide.globe,
      Permission.autofill => Lucide.keyRound,
      Permission.download => LucideMore.hardDriveDownload,
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
    adds: ['Corrections when you finish a word', 'Backspace to undo a correction', 'Don’t → don’t, i → I'],
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
    hosts: ['attack.fm', 'huggingface.co'],
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
    line: 'Draw a word across the keys without lifting your thumb.',
    description: 'Glide from letter to letter and lift: the word your path traced is typed, with a space after it.',
    category: PluginCategory.typing,
    hue: 'cyan',
    icon: LucideMore.pointer,
    adds: ['Glide typing on the letters', 'Other guesses in the row above the keys'],
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
    hosts: ['api.giphy.com', 'media.giphy.com'],
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
    line: 'Take back the last edit, or put it back.',
    description: 'Undo and Redo buttons in the row, sent as Ctrl+Z and Ctrl+Shift+Z, which Android\'s text fields and most editors understand.',
    category: PluginCategory.tools,
    hue: 'blue',
    icon: Lucide.undo2,
    adds: ['Undo and Redo in the row'],
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
    id: 'sounds',
    name: 'Sound packs',
    line: 'Over fifty key sounds, from thocky switches to snow and chess pieces.',
    description:
        'Key sounds made with ElevenLabs: a clicky mechanical board, a deep thock, a typewriter, bubble pops and 8-bit blips. '
        'They play when Sound is on in Settings › Sounds.',
    category: PluginCategory.sound,
    hue: 'coral',
    icon: Lucide.volume2,
    adds: ['Over fifty sound packs'],
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
    id: 'backlight',
    name: 'Backlight',
    line: 'Letters lit from behind, reacting as you type.',
    description:
        'An RGB mechanical keyboard’s shine-through legends for your phone: letters that glow and fade as you press them, ripples that run '
        'out from each key, a heatmap of the keys you use, a rainbow wave, twinkling starlight or a slow breath. In your '
        'theme’s accent, a colour of your own, or a rainbow.',
    category: PluginCategory.look,
    hue: 'purple',
    icon: Lucide.sparkles,
    adds: ['Six lighting effects', 'Eight colours and a rainbow'],
    hasPage: true,
  ),
];

/// The backlight plugin's effects (android Backlight.kt): id, name, what it does.
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
const List<(String, String)> stockSounds = [('bamboo', 'Thock'), ('geiger', 'Geiger counter'), ('system', 'Android’s')];

/// The sounds plugin's packs (android SoundPacks.kt).
const List<(String, String)> soundPacks = [
  ('mechanical', 'Clicky mechanical'),
  ('thock', 'Deep thock'),
  ('typewriter', 'Typewriter'),
  ('bubble', 'Bubble pop'),
  ('arcade', '8-bit arcade'),
];

PluginManifest? pluginById(String id) {
  for (final plugin in catalogue) {
    if (plugin.id == id) return plugin;
  }
  return null;
}

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

