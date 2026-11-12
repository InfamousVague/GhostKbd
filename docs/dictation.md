# Dictation: long takes, your words, Tidy

The dictation plugin (`Dictation.kt`, `SpeechCuts.kt`, `DictationWords.kt`, `Whisper.kt`; Android only) as it is since
0.16.0. Matt, 2026-11-12, choosing from a list of plugins to add: "I like prompt dictation…". The list had said of it:
"Lift the cap, show the words live, add a word list for names and repositories, and tidy a ramble into a clear
prompt". Until then a take ended at 60 seconds and its words came only once it was over.

Speech is turned into text on the phone by whisper.cpp, as before. Nothing that is said leaves the phone.

## Takes of any length

whisper hears thirty seconds at a time and gives its words only when it has heard a piece whole. So a take is heard
as a row of pieces, each written down on a thread of its own while the next is being spoken, and each piece's words
are in the field for good when it is done.

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
open behind another app; when the phone is turned or unfolded; by itself after 45 seconds with nobody speaking; and
at 30 minutes whatever is said. The sound of each piece is let go as soon as it is written down.

A take's words are for the field it was started in. In another field, or once that field is gone, what has not been
written down yet is let go and never typed (`Dictation.cancel`, `DevKeyboardService.dropTake`). And where the last
words take more than three seconds to write down (a long take on a slow phone), the panel says so and a tap stops
it there: what the take has typed already stays.

## As you speak

Matt, 2026-11-12, of 0.16.1: "I want dictation to show as I'm typing in the chat box instead of having it all show
up at the very end". A piece ends only at a pause of 0.6 seconds, so someone who speaks a message straight through
saw nothing until they tapped to finish.

Since 0.16.2 (`LiveWords.kt`; Settings › Dictation › Show words as I speak, on unless switched off) the piece in
hand is also written down again and again while it is being spoken, each time whole and from its start, and what
comes back is shown in the field as a draft that the next one replaces. So the words are there about a second
behind the voice, and the last word or two of them can change as more is heard. At the pause the piece is written
down as it always was, and that takes the draft's place.

- **When**: the first draft of a piece once it has 0.9 seconds of speech in it (speech, not sound: a piece begins with
  the end of the pause before it), then one for every 0.9 seconds more, and never two at once: a phone that takes
  longer than that to make one makes them as fast as it can.
- **How**: a draft is whisper's ordinary reading of the sound, made a little quicker: one segment, no time marks, no
  second try at a higher temperature (`nativeDraft`). It is given whisper's whole window. A window cut to the
  piece's length was three times quicker on a desk, but on the emulator those drafts took "Ask" for "It's" and once
  filled the field with "is is is is".
- **What is shown** (`LiveWords.shown`, `believable`): the draft without the full stop, dots or comma whisper ends
  it with (the person is mid-sentence), and nothing at all where it repeats one word four times, says a phrase twice
  over, or has more words than anyone could have said.
- **In the field** (`DevKeyboardService.onDraft`, `settle`; `FieldEditor.replaceBefore`): the draft is ordinary text
  just before the cursor. Each new one changes only what differs from the last, from the first letter that does, and
  a piece is one step in the undo history however often it was revised. Where the draft is no longer just before the
  cursor (a tap in the text, an app that rewrote it) it stands as it is, and the piece as written down goes in where
  the cursor is.
- A draft gives way to a piece that is waiting to be written down (`nativeAbortDraft`), so the end of a take does not
  wait on one.

Everything is still on the phone. It costs processor time for as long as someone speaks.

In a debug build a recording can stand in for the microphone (`DebugSpeech.kt`: `files/debug-speech.wav` in the app's
own storage), which is how this was tried on an emulator, whose microphone hears nothing.

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

**On the emulator, 2026-11-12, with a debug build of 0.16.2's code and the model the app starts with (base.en):** a
recording of eleven seconds of speech (whisper.cpp's own sample, three phrases with pauses between) played in the
microphone's place. The words came into the field about once a second while it played, each draft carrying the last
one on ("Ask me", "Ask NOT", "Ask NOT what you're doin", "Ask NOT what your country c…"), and each phrase's draft
was replaced by the phrase as written down at its pause. `LiveWordsTest` (7 tests) holds the rules, and
`FieldEditorTest` that a revised piece is one undo step and types only what changed. On a phone, with a voice and a
real microphone, it has not been tried.

`SpeechCutsTest` (11 tests, on made sound: a tone for a voice, a hiss for a room): a pause ends a piece in the middle
of the quiet, a breath does not, a voice that never pauses is cut before 24 seconds at its quietest moment, quiet is
let go and never written down, a quiet short take is never thrown away, and how long since anyone spoke.
`DictationWordsTest` (10): the word list, what whisper is given, what Tidy is told, the ledger path.
`test/dictation_settings_test.dart`: the page and the Market's card. `tool/net_check.sh` passes with 19 paths.

Not checked, and it matters most here:

- No take has been spoken into a phone's microphone by me. The recorder's loop, the second thread and the order the
  pieces arrive in (`Dictation.start`, `write`) have no unit test; they were run on the emulator with a recording
  (above) and with a silent microphone.
- The numbers above (4 s, 0.6 s, 24 s, 250, 12%) are chosen, not measured. Whether pieces cut this way read well,
  whether whisper keeps up with the voice on a given phone and model, and whether Your words really changes a
  spelling are all unmeasured.
- No text was tidied through a real AI provider.
