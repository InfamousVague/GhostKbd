# Dictation: long takes, your words, Tidy

The dictation plugin (`Dictation.kt`, `SpeechCuts.kt`, `DictationWords.kt`, `Whisper.kt`; Android only) as it is since
0.16.0. Matt, 2026-11-12, choosing from a list of plugins to add: "I like prompt dictation…". The list had said of it:
"Lift the cap, show the words live, add a word list for names and repositories, and tidy a ramble into a clear
prompt". Until then a take ended at 60 seconds and its words came only once it was over.

Speech is turned into text on the phone by whisper.cpp, as before. Nothing that is said leaves the phone.

## Takes of any length

whisper hears thirty seconds at a time and gives its words only when it has heard a piece whole. So a take is heard
as a row of pieces, each written down on a thread of its own while the next is being spoken, and its words go into
the field as each piece is done: a sentence or two behind the voice, not word by word.

Where a piece ends (`SpeechCuts`):

- at a pause: once a piece is 4 seconds long and has speech in it, 0.6 seconds of quiet ends it, in the middle of
  that quiet;
- at 24 seconds whatever is being said, at the quietest moment of its last 6 seconds;
- a stretch with nobody speaking is let go without being written down, all but its last 0.3 seconds. whisper makes
  words up when it is given silence. Before anyone has spoken in the take this waits a whole piece's length, so a
  short take in a voice too quiet to be taken for speech is still written down whole, as every take was before.

"Quiet" is quiet for that piece: under 12% of the loudest it has been. "Speech" is a 20 ms frame at least 250 loud
(root mean square of the 16-bit samples, about -42 dB of full scale).

Each piece is given the end of the piece before it (up to 160 characters), which is what keeps the pieces reading as
one text.

A take ends when the mic is tapped again; when the keyboard is hidden (`onFinishInputView`), so a mic is never left
open behind another app; by itself after 45 seconds with nobody speaking; and at 30 minutes whatever is said. The
sound of each piece is let go as soon as it is written down.

## Your words

Settings › Dictation › Your words: up to 60 names and terms, with commas between. whisper can be given a few words
of text as if they had just been said, and then leans toward spelling what it hears their way
(`DictationWords.hint`). The person's words go there after the developer's words the keyboard always gave it
(npm, git, Kotlin…), and only the person's own for a language other than English. It is a lean, not a rule: a word
may still be spelled whisper's way.

## Tidy, and As a prompt

After a take the row above the keys leads with **Tidy** (and **As a prompt**, where the Prompt enhancer plugin is
added), until the next key is pressed. Either works on exactly the words that take put in: they are selected, the
answer takes their place, and undo puts them back.

- Tidy has the AI take out what speech adds (ums, false starts, a word said twice), mend what was plainly misheard
  (it is given Your words for that), and change nothing else (`DictationWords.system`).
- As a prompt is the Prompt enhancer told that the text was spoken ([prompt-enhancer.md](prompt-enhancer.md)).

Both are offered only with an AI set up in Settings › AI, never in a password field, while incognito or with Offline
on. Settings › Dictation › Offer Tidy in the row turns the Tidy chip off.

## What leaves the phone

- Speech: nothing. The model runs on the phone; the network is used only to download a model.
- Tidy, only when tapped: the key and model, the words that take put in (up to 6,000 characters) and Your words, to
  the AI provider chosen in Settings › AI and nowhere else. A ledger path of its own (`NetPath.TIDY`).
- As a prompt, only when tapped: as the Prompt enhancer's path says (`NetPath.ENHANCE`).

## Checked

`SpeechCutsTest` (10 tests, on made sound: a tone for a voice, a hiss for a room): a pause ends a piece in the middle
of the quiet, a breath does not, a voice that never pauses is cut before 24 seconds at its quietest moment, quiet is
let go and never written down, a quiet short take is never thrown away, and how long since anyone spoke.
`DictationWordsTest` (10): the word list, what whisper is given, what Tidy is told, the ledger path.
`test/dictation_settings_test.dart`: the page and the Market's card. `tool/net_check.sh` passes with 19 paths.

Not checked, and it matters most here:

- No take was spoken into a phone or an emulator when this was written. The recorder's loop, the second thread and
  the order the pieces arrive in (`Dictation.start`, `write`) have no test and were not run.
- The numbers above (4 s, 0.6 s, 24 s, 250, 12%) are chosen, not measured. Whether pieces cut this way read well,
  whether whisper keeps up with the voice on a given phone and model, and whether Your words really changes a
  spelling are all unmeasured.
- No text was tidied through a real AI provider.
