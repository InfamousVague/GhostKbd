/// What the walkthrough says aloud, page by page, and the same words on the page as its caption: Ghost.md's way (its
/// lib/guide/narration.dart). tool/onboarding_voice.py reads them from here, has ElevenLabs say them in Ghost.md's
/// voice, and writes assets/onboarding/<page>.mp3 with assets/onboarding/narration.json saying which words each file
/// says; the Voice workflow (.github/workflows/voice.yml) runs it when these change. The voice is made once and
/// shipped: no key and no network on the phone. A page with no file yet is shown with its words and no voice.
///
/// Keep each entry on one line, in single quotes, with typographic quotes and apostrophes inside (“ ” ’): that is
/// what the tool reads.
library;

/// Each page's words, said and shown.
const Map<String, String> narration = {
  'welcome': 'Hi, and welcome to Ghost Keyboard. It types like the keyboard you know, with the things people who write code reach for, a tap away.',
  'enable': 'First, turn Ghost Keyboard on. Your phone keeps a list of keyboards: tap the button, find Ghost Keyboard, and switch it on. Android will check you trust it. Then come back here.',
  'choose': 'Now make it the keyboard you type with. Tap Switch, and pick Ghost Keyboard. You can change back any time, by holding the space bar.',
  'code': 'Beside the space bar is the curly braces key. It opens a page of what code is made of. Brackets and quotes come in pairs, with the cursor already inside. Hold any key for its family: equals gives you double and triple equals, the arrow gives you more arrows.',
  'voice': 'Would you rather talk? Add Dictation, and a microphone joins the space bar. It runs whisper on your phone, the same speech model Ghost uses, so nothing you say leaves it.',
  'plugins': 'Autocorrect, swipe typing, the clipboard, undo and emoji are already on, and the rest are switches in Settings. Dictation, your password manager and Ghost.md are in the Market. Adding one is a switch, and so is taking it away.',
  'look': 'Pick a look. Light, dark, or follow your phone. You can turn key borders on if you like a shape behind every key.',
  'start': 'That’s the tour, and you’re ready. Try it in the box below. Everything you’ve seen is in Settings, whenever you want it.',
};

/// Where a page's voice is in the app's assets.
String narrationAsset(String page) => 'assets/onboarding/$page.mp3';
