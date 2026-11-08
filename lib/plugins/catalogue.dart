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
  media('Media');

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
        'your phone, with nothing sent anywhere. It is the same speech model Ghost.md dictates with, base.en, '
        'downloaded once when you add the plugin.',
    category: PluginCategory.voice,
    hue: 'rose',
    icon: Lucide.mic,
    adds: ['A mic key beside the space bar', 'Speech to text on your phone, offline'],
    permissions: [
      PluginPermission(Permission.microphone, 'To hear you while you hold the mic open, and only then.'),
      PluginPermission(Permission.download, 'Its speech model: 60 MB, downloaded once.'),
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
        'Eight presets, from Glacier’s ice blue to Dracula, Nord and Solarized, and a theme of your own built from five '
        'colours: the keyboard, its keys, the function keys, the accent and the letters.',
    category: PluginCategory.look,
    hue: 'magenta',
    icon: Lucide.palette,
    adds: ['Nine preset themes, Retro’s raised keycaps among them', 'A theme of your own from one colour'],
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
  ('gboard', 'Gboard', [0xFF1B1B1F, 0xFF3B3C40, 0xFF2A2B2F, 0xFFA8C7FA, 0xFFE3E3E3]),
  ('glacier', 'Glacier', [0xFF0E1621, 0xFF1D2A3A, 0xFF152030, 0xFF7CC4FA, 0xFFE6F1FB]),
  ('midnight', 'Midnight', [0xFF000000, 0xFF1A1A1A, 0xFF0D0D0D, 0xFF8AB4F8, 0xFFFFFFFF]),
  ('dracula', 'Dracula', [0xFF21222C, 0xFF343746, 0xFF282A36, 0xFFBD93F9, 0xFFF8F8F2]),
  ('nord', 'Nord', [0xFF2E3440, 0xFF434C5E, 0xFF3B4252, 0xFF88C0D0, 0xFFECEFF4]),
  ('solarized', 'Solarized', [0xFF002B36, 0xFF0E4252, 0xFF073642, 0xFFB58900, 0xFFEEE8D5]),
  ('monokai', 'Monokai', [0xFF1E1F1C, 0xFF3E3D32, 0xFF272822, 0xFFA6E22E, 0xFFF8F8F2]),
  ('github', 'GitHub light', [0xFFF6F8FA, 0xFFFFFFFF, 0xFFD0D7DE, 0xFF0969DA, 0xFF1F2328]),
  ('paper', 'Paper', [0xFFF4EFE6, 0xFFFFFCF6, 0xFFE4DCCD, 0xFFB5522B, 0xFF2B2622]),
  ('retro', 'Retro', [0xFF34302B, 0xFFE9E1CD, 0xFFA8A190, 0xFFD9542B, 0xFF2E2A24]),
];
