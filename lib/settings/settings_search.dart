/// Lower case, with curly quotes and apostrophes made straight, so what is typed matches what is drawn. Ghost.md's
/// `fold` (lib/settings/settings_search.dart) without its accent folding, which Dev Keyboard's English words need not.
String fold(String text) => text.toLowerCase().replaceAll(RegExp('[‘’]'), "'").replaceAll(RegExp('[“”]'), '"');
