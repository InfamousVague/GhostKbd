# Ghost Keyboard against the field

Written 2026-11-09 for Ghost Keyboard 0.8.13+58 (`pubspec.yaml`). Competitor facts were checked on that date and
carry their source; where a fact could only be read through a search index, or is an absence ("we found no…"), it
says so. Ghost Keyboard's own facts were read from the code, and the file is named beside each.

Three rules run through the whole document:

1. A competitor claim is used only if it was confirmed, or is worded with its qualifier.
2. A planned feature is called "coming", never described as if it exists.
3. Where Ghost Keyboard is behind, it says so first.

## The short version

- **Who it is for.** People who write code and talk to AI from their phone, and anyone who wants AI in the keyboard
  without handing their typing to a vendor. Not "a better Gboard for everyone".
- **Tagline.** "Your keys. Your model. Your words." Keep "A keyboard for people who write code" as the line over the
  developer section.
- **What nobody mainstream has together.** AI written in place in any app with your own key and model, a learned
  personal voice, terminal keys in every app, a touch model you can see, and a typing profile that is an open file.
- **Where we are behind, plainly.** No next-word prediction. Six autocorrect languages. Swipe and autocorrect
  quality unmeasured. Dictation takes are 60 seconds, text only after you stop. No one-handed or floating mode. Not on
  Google Play, F-Droid or iOS. The public repository has no licence, so it is not open source. AI needs the person
  to get an API key.
- **First moves.** Make the trust story checkable (a list of every host, a licence decision, keys stored properly,
  published signing fingerprint), build the website around a live keyboard, register for Android developer
  verification, and start on next-word prediction and a swipe benchmark.

## 1. Where the market stands

**Two defaults and one alternative.** Gboard's Play listing shows 10B+ installs, 4.5 stars from about 19M reviews
and no ads ([Play](https://play.google.com/store/apps/details?id=com.google.android.inputmethod.latin)); it is
preinstalled on most non-Samsung phones. SwiftKey shows 1B+ installs, 4.3 stars from 4.46M reviews, updated
27 Oct 2026, and is tagged "Contains ads" ([Play](https://play.google.com/store/apps/details?id=com.touchtype.swiftkey)).
Samsung Keyboard ships on every Galaxy; Samsung was 22.74% of mobile web traffic by vendor in October 2026
([Statcounter](https://gs.statcounter.com/vendor-market-share/mobile/worldwide)), which is a traffic share, not an
installed base. Nobody publishes active-user share for keyboards. An Android Authority reader poll from early
February 2026 (just under 2,000 self-selected votes) is reported as Gboard 54%, SwiftKey 39%, other 7%; that page
could only be read through a search index, so do not publish the figures.

**The incumbents spent 2025 and 2026 on AI, with strings attached.**

- Gboard's Writing tools offer seven presets (Proofread, Rephrase, Professional, Friendly, Emojify, Elaborate,
  Shorten) on a list of supported phones; the first load "might take a few hours"
  ([Google help](https://support.google.com/gboard/answer/16515540)). Custom instructions were seen in a beta in
  May 2026 and are not confirmed shipped as of 2026-11-09.
- Rambler, Google's dictation that tidies speech, was announced 12 May 2026
  ([TechCrunch](https://techcrunch.com/2026/05/12/google-adds-gemini-powered-dictation-to-gboard-which-could-be-bad-news-for-dictation-startups/)).
  Google's help page lists "Pixel 11 series devices", "An active internet connection for full features", and
  "Rambler is subject to usage limits" ([Google help](https://support.google.com/gboard/answer/17468539)).
- Microsoft's own FAQ says "The Copilot and Compose integration in SwiftKey have been removed" and that an improved
  Compose "will come soon"
  ([Microsoft](https://support.microsoft.com/topic/faqs-for-copilot-changes-in-swiftkey-c02289e6-c5b3-401c-af8d-f6c88409a2d2)),
  while the Play listing still says SwiftKey "comes with Copilot".
- Microsoft retired SwiftKey accounts by 31 May 2026. Backup and sync now need a Microsoft account and use OneDrive;
  the keyboard still works with no account
  ([Microsoft](https://support.microsoft.com/en-US/SwiftKey-Keyboard/account),
  [heise](https://heise.de/-11217783),
  [Windows Central](https://www.windowscentral.com/software-apps/swiftkey-will-soon-require-a-microsoft-account-data-to-be-moved-to-onedrive)).
- Samsung's Writing assist (Composer, Writing style, Spelling and grammar, Chat translation) is free as a "basic"
  Galaxy AI feature ([Samsung US](https://www.samsung.com/us/support/answer/ANS10000943/)). Since One UI 7 it works
  with any keyboard: select text and tap the Galaxy AI icon
  ([9to5Google](https://9to5google.com/2025/01/31/one-ui-7-galaxy-ai-writing-features-without-samsung-keyboard/)).
  A Galaxy owner who switches to Ghost Keyboard keeps it.

**Challengers each win on one thing.**

- *Private and offline:* HeliBoard (GPL-3.0, no internet permission, v4.2 in October 2026;
  [F-Droid](https://f-droid.org/en/packages/helium314.keyboard/)) and FUTO Keyboard (works offline, its own swipe
  model and offline voice input, optional one-time payment; [docs](https://docs.keyboard.futo.tech/print)). Neither
  writes or rewrites text for you.
- *Developer keys:* Unexpected Keyboard (GPL-3.0, real Ctrl/Alt/Esc/Tab, autocorrect only since 2.0.0 in 2026;
  [releases](https://github.com/Julow/Unexpected-Keyboard/releases)).
- *AI voice:* Wispr Flow (Android since 23 Feb 2026 as a floating bubble, $15 a month or $12 billed annually;
  [pricing](https://wisprflow.ai/pricing),
  [TechCrunch](https://techcrunch.com/2026/02/23/wispr-flow-launches-an-android-app-for-ai-powered-dictation/)).
- *AI writing:* CleverType (1M+ installs; its policy of 7 Oct 2026 says the AI features "cannot run on your device
  alone" and requests carry "Your account identifier"; [policy](https://www.clevertype.co/privacy-policy)).

**Two "AI keyboards" are no longer keyboards.** Grammarly on Android now "works with any Android keyboard, such as
Gboard or SwiftKey" through accessibility and overlay permissions
([Play](https://play.google.com/store/apps/details?id=com.grammarly.android.keyboard),
[Grammarly](https://support.grammarly.com/hc/en-us/articles/115003350672-How-Grammarly-protects-your-privacy-on-Android)).
Fleksy moved to a B2B SDK in 2020 and its consumer app was no longer listed as of December 2024
([Wikipedia](https://en.wikipedia.org/wiki/Fleksy), the only source checked).

**What people complain about, most often first** (a judgement from thread sizes and press, not a count): autocorrect
that changes correct words; hitting the neighbouring key; swipe quality once they leave Gboard or SwiftKey; lag;
forced accounts; distrust of telemetry; AI buttons they did not ask for; voice typing that stops on a pause;
clipboard limits. Pixel owners reported worse Gboard typing in mid-2025
([Tech Issues Today](https://techissuestoday.com/has-your-typing-experience-on-gboard-degraded-google-pixel-users-claim-increase-in-errors-and-typos),
a small blog summarising Reddit, which also notes some people saw the same after switching keyboards).

**Sideloading is on a clock.** Google set Android developer verification to begin 30 Sep 2026 in Brazil, Indonesia,
Singapore and Thailand and to go global in 2027. An app from an unverified developer needs "Allow apps from
unverified developers" in Developer options and a one-time 24-hour wait
([9to5Google](https://9to5google.com/2026/08/18/google-gradually-rolling-out-androids-advanced-sideloading-ahead-of-developer-verification/),
[Android developers](https://developer.android.com/developer-verification/guides)). Full distribution needs a $25
fee and identity verification; a free limited account covers 20 devices
([Google](https://support.google.com/android-developer-console/answer/16561738)). Only pre-enforcement coverage was
found, so say "was scheduled".

## 2. Each rival: good at, bad at

### Gboard

Good at: being already there; mature glide typing and prediction across a very wide range of languages; free
on-device Writing tools on supported phones, with Google stating "Your input text isn't shared with Google or any
app"; Emoji Kitchen, Translate, handwriting; one-handed, floating and split layouts; undo and redo since v15.0
([9to5Google](https://9to5google.com/2025/03/19/gboard-undo-redo-android/)); also on iOS.

Bad at:

- AI is tied to the phone. Writing tools need a listed device; Rambler needs a Pixel 11.
- No choice. Google documents no option to pick a model or use your own key; its tones are seven presets, not your
  own voice.
- The clipboard forgets. Unpinned clips are deleted after about an hour; a 1 to 24 hour slider was found in beta
  18.0.3 in August 2026 and is not confirmed shipped
  ([Android Authority](https://androidauthority.com/gboard-clipboard-duration-slider-apk-teardown-3698099), read
  through search summaries).
- Data. Federated learning "is turned on by default" (it can be turned off) and sends what it learns, not the text
  ([Google help](https://support.google.com/gboard/answer/12373137)). The Play data-safety section declares device
  or other IDs and crash logs, plus optional app interactions, installed apps, in-app search history and voice
  recordings, and says the developer provides no way to request deletion; it also says nothing is shared with other
  companies ([Play](https://play.google.com/store/apps/datasafety?id=com.google.android.inputmethod.latin)).
- No developer keys. We found no Ctrl or Esc keys, markdown row or snippet page; a Tab key appears on its tablet
  layout ([9to5Google, 2022](https://9to5google.com/2022/10/10/gboard-android-tablet/)). Check a phone and a Fold
  before publishing this. NOT DONE as of 2026-11-09: the website words the cell as what we found in the help pages and
  the press report, and its note 5 says no device was checked.

### SwiftKey

Good at: learned prediction, its reputation; up to five languages at once out of 700+
([Microsoft](https://support.microsoft.com/en-us/swiftkey-keyboard/what-languages-are-currently-supported-for-microsoft-swiftkey-keyboard));
Full, One-handed, Float, Thumb and Tablet modes; an optional arrow-key row; a clipboard whose clips expire after an
hour unless pinned (see "Sources opened for the website" below); inline
password autofill; 100+ themes; works with no account, with learned data kept on the phone
([Microsoft](https://support.microsoft.com/en-gb/swiftkey-keyboard/microsoft-swiftkey-keyboard-privacy-questions-and-your-data)).

Bad at:

- AI came and went: Bing in 2023, Copilot in 2024, removed since, per Microsoft's FAQ. Tone, where it remains, is
  four fixed styles per a help page that may be out of date.
- The 2026 account move. Cloud data not moved to a Microsoft account was to be deleted on 31 May 2026.
- Ads and marketing data. Tagged "Contains ads"; data safety lists optional location and device IDs for
  "Advertising or marketing" (collected, not shared)
  ([Play](https://play.google.com/store/apps/datasafety?id=com.touchtype.swiftkey)). It sends anonymous usage
  statistics and crash reports
  ([Microsoft](https://support.microsoft.com/en-us/topic/50260e0e-9ed8-4f2c-99b8-9b025fba4b7d)).
- Voice. "Currently we support English only for voice to text", and anonymised clips may be sent to Microsoft unless
  you opt out
  ([Microsoft](https://support.microsoft.com/en-us/swiftkey-keyboard/how-do-i-use-voice-to-text-with-microsoft-swiftkey-keyboard)).
- Recent featured Play reviews complain of autocorrect and of swipe showing only three candidates. These are three
  reviews against a 4.3-star average: examples, not a verdict.
- No developer keys, model choice or open export documented.

### Samsung Keyboard

Good at: default on every Galaxy; Writing assist with an on-device processing switch; Keys Cafe (in Good Lock) for
per-key resizing, extra rows, colours, effects and sounds
([SamMobile](https://www.sammobile.com/?p=16600262)); a split layout on the Fold's inner screen per Samsung's
(old) support page; Giphy and Tenor GIFs.

Bad at:

- Typing quality is its reputation problem. 9to5Google called autocorrect "incredibly aggressive, but also not
  aggressive enough in catching typos" in February 2024 (an opinion piece;
  [9to5Google](https://9to5google.com/2024/02/04/samsung-keyboard-terrible/)); some S25 Ultra owners said the same of
  swipe and multilingual typing in February 2025
  ([Samsung Community](https://community.samsung.com/ca/conversations/galaxy-s/2025-once-again-the-s25-ultra-samsung-keyboard-is-still-junk-vs/r1_31958859)).
- Clipboard history was system-level with no auto-delete as of April 2025, and a Samsung moderator advised clearing
  it by hand
  ([Samsung Community](https://community.samsung.com/us-bp/conversations/Suggestions/Implement-Auto-Delete-Clipboard-History-to-Prevent-Sensitive/us_3200743)).
  Keep that dated.
- With on-device processing on, only part of Writing assist works (style, grammar and chat translation stay;
  Composer and summaries go) ([SamMobile](https://www.sammobile.com/news/use-galaxy-ai-without-sending-data-to-samsung-heres-what-you-lose/)).
- Samsung Canada tells users to be signed in to a Samsung account for Writing assist
  ([Samsung](https://www.samsung.com/ca/support/mobile-devices/enhance-your-messages-with-writing-assist-on-your-samsung-galaxy/)).
  Its privacy policy lists the words typed with Predictive text on as collected "Samsung Keyboard information" that
  may sync through the account, and says it can be cleared
  ([policy](https://www.samsung.com/us/account/privacy-policy/)).
- Galaxy only. We found no built-in terminal keys, model choice or open export in Samsung's documentation; Keys Cafe
  can add custom rows, so say "no built-in".

### HeliBoard and FUTO Keyboard (the trust tier)

Good at: HeliBoard has no internet permission, is GPL-3.0, releases often, and has clipboard history with images,
floating, split and one-handed modes, backup of learned words, and separate scale settings for folded phones
([releases](https://github.com/HeliBorg/HeliBoard/releases)). FUTO has built-in swipe, offline voice with a choice
of models, a transformer language model for prediction, arrow keys, clipboard search, Standard / One Hand / Split /
Float modes, and is on Google Play.

Bad at:

- HeliBoard has glide typing only after the user loads a closed-source library themselves
  ([FAQ](https://github.com/Helium314/HeliBoard/wiki/FAQ)), and no voice input of its own.
- FUTO's licence is the "FUTO Source First License 1.1", not an OSI licence
  ([repo](https://github.com/futo-org/android-keyboard)); its transformer model "only currently works for English";
  voice stops at 30 seconds unless long-form is on; GIF search is not planned.
- Neither generates or rewrites text. That is true of FlorisBoard, AnySoftKeyboard, OpenBoard and Thumb-Key too, per
  their docs as of November 2026.

One caution: FUTO says its June 2026 swipe engine makes fewer errors than Gboard on its own test. Do not describe
FUTO's swipe as weak. And **LeanType**, an open-source HeliBoard fork, already offers bring-your-own AI providers
(Gemini, Groq, OpenAI, local servers) and says network access is used only when a key is set and a request is
triggered ([F-Droid](https://f-droid.org/en/packages/com.leanbitlab.leantype/index.html)). "No other keyboard lets
you bring your own key" would be false. "No mainstream keyboard" is defensible.

### Developer keyboards and key bars

Good at: Unexpected Keyboard reaches every symbol by corner swipes and has real modifiers, macros and custom
layouts. Termux's extra-keys row and Termius's toolbar give Esc, Ctrl, Tab and arrows inside their own app; Termius
also turns a description into a shell command ([Termius](https://termius.com/android)).

Bad at:

- Prose. Unexpected Keyboard had no autocorrect until 2026 and has no word-level glide typing (an open request,
  [issues](https://github.com/Julow/Unexpected-Keyboard/issues?q=is%3Aissue+glide)). Its 2.x builds on F-Droid
  request network access; the reason is not stated there.
- Age. Hacker's Keyboard's author calls it "a rather ancient project" with no significant updates planned
  ([repo](https://github.com/klausw/hackerskeyboard)); its F-Droid build is from December 2018. CodeBoard's last
  release (6.0.3, July 2025) was an Android 15 update.
- The app boundary. Termux's and Termius's bars exist only inside those apps, and Termux's terminal does not support
  "autocorrection, prediction and swipe typing" ([Termux wiki](https://wiki.termux.com/wiki/Touch_Keyboard)).
- A 29 Oct 2026 guide to running Claude Code from a phone says the on-screen keyboard has no Esc and no Ctrl key and
  the agent needs both ([SSD Nodes](https://www.ssdnodes.com/learn/claude-code-from-phone)).

### AI overlays and voice apps

Good at: Wispr Flow's and Typeless's dictation clean-up is well reviewed; Grammarly explains each correction;
CleverType has the widest in-keyboard AI menu.

Bad at: every one routes text through the vendor's own cloud under the vendor's account. Grammarly Free has 100 AI
prompts a month; Pro is $12 a month billed annually, $30 monthly ([plans](https://www.grammarly.com/plans)). Wispr's
Play data-safety section declares voice recordings, web browsing history and device IDs as shared with other
companies ([Play](https://play.google.com/store/apps/datasafety?id=com.wispr.flowapp)). Typeless's own reviewers say
its keyboard has no slide typing, emoji or GIFs
([Play](https://play.google.com/store/apps/details?id=com.typeless.mobile)). We found no mention of a user-supplied
key or model choice in their Play listings as of 2026-11-09.

## 3. Ghost Keyboard today, from the code

Every line below was checked in the source on 2026-11-09.

| What | True today | Where |
| --- | --- | --- |
| Shape | Native `InputMethodService`, one custom view; Flutter settings app; Android 8+ (minSdk 26) | `DevKeyboardService.kt`, `KeyboardView.kt`, `build.gradle.kts` |
| Autocorrect | Six languages (en, es, fr, de, it, pt), 30,000 to 40,000 words each, several loaded at once; programmer's vocabulary; texting slang switch; backspace restores what you typed and the word is learned; a learned-words list with "tap to forget" | `Autocorrect.kt`, `assets/dict`, `DevKeyboardService.kt` (`undoCorrection`), `panes.dart` |
| Next-word prediction | **None.** The row completes and corrects the current word only | no bigram or next-word code in the Kotlin sources |
| Swipe typing | Built in, with other guesses in the row. A simple decoder indexed by first and last letter; accuracy never measured | `Autocorrect.kt`, `KeyboardView.kt` |
| Adaptive keys | Learns aim per letter from backspaces and corrections; moves touch edges at most 0.35 of a key, after 15 touches; heatmap overlay; "Forget what it learned" | `AdaptiveKeys.kt`, `panes.dart` |
| Ghostwriter | `<<request>>` in any app, replaced in place, optionally typed out key by key; never in password fields or incognito; unknown facts become [blanks] | `Ghostwriter.kt` |
| What Ghostwriter reads | Text around the request (1,500 characters before, 400 after), matching past messages, and, each behind its own switch, recent clipboard, Ghost.md notes, GitHub commits, the web | `Ghostwriter.kt`, `panes.dart` |
| Web lookups | Only with Claude, OpenAI, Gemini or OpenRouter (the provider's own search) | `AiClient.kt` (`SEARCHES`) |
| Mockingbird | Keeps up to 1,500 samples on the phone; the chosen provider writes a style profile from them; "In my voice" rewrites a selection; can retrain by itself at most daily; "Delete samples and profile" | `Mockingbird.kt`, `panes.dart` |
| Your AI | Claude, OpenAI, Gemini, Mistral, Groq, DeepSeek, OpenRouter, or a custom OpenAI-compatible server whose address must start with `https://`; requests go to the provider's own API root | `AiClient.kt` |
| Dictation | whisper.cpp on the phone; tap, talk, tap; **60-second cap**; text appears after the second tap; English model to start, multilingual models downloadable | `Dictation.kt` (`MAX_SECONDS = 60`), `WhisperModel.kt` |
| Clipboard | Last 25 text copies, no timer, on the phone; skips what Android marks sensitive and anything copied in incognito; no pins | `ClipHistory.kt` |
| Developer keys | `{ }` page of pairs and snippets; markdown row (on by itself in Obsidian, Joplin, Markor, GitHub and others); terminal row with Esc, Tab, sticky Ctrl and Alt, arrows (on by itself in Termux, Termius, JuiceSSH, ConnectBot); both can be on in every app | `Layouts.kt`, `AppContext.kt`, `panes.dart` |
| Row tools | Arrow keys, undo and redo, emoji (Emoji 18.0, search, :shortcodes), GIFs (GIPHY), incognito switch, password-manager chips on Android 11+ | `catalogue.dart`, `Emoji.kt`, `Gifs.kt` |
| Look | Five theme presets plus one made from a colour; six backlight effects; seven stock sounds; haptics | `catalogue.dart`, `Backlight.kt`, `SoundPacks.kt` |
| Wide screens | Splits in two at 600dp and wider; one on/off switch, fixed gap | `KeyboardView.kt` |
| Typing profile | `ghost-typing-profile` v1, open JSON with a published schema; import and export; samples left out of an export unless asked | `docs/typing-profile.md`, `TypingProfile.kt` |
| Updates | Checks attack.fm on opening the app and at most twice a day from the keyboard; installs only a file matching apk.json's size and SHA-256, the package, a newer version and the installed signing certificate; an "Automatic updates" switch | `Updater.kt` |
| Permissions | INTERNET, RECORD_AUDIO, VIBRATE, REQUEST_INSTALL_PACKAGES | `AndroidManifest.xml` |
| Analytics | No analytics or crash-reporting library found in the Kotlin sources, `pubspec.yaml` or Gradle files (a search, not an audit) | search of the tree |

**Things the code says that the words around it do not.** Fix these before the site quotes them.

1. `README.md` is still titled "Dev Keyboard" and describes "Stage 1". There is no licence file.
2. The catalogue says keys move "never more than a third of a key"; the limit is 0.35. Say "about a third".
3. Mockingbird's card says samples leave the phone "once, to train". Retraining sends them again (at most daily if
   "Retrain by itself" is on), and Ghostwriter sends the samples that match a request when "My past messages" is on.
4. The plugin `hosts` lists are incomplete as a ledger. Ghostwriter's omits `api.github.com`,
   `ghostmarkdown.com` and a custom server; the update check to `attack.fm` belongs to no plugin; OpenRouter
   requests carry an app title and referer header.
5. Turning "Automatic updates" off stops the install, not the check: the keyboard still asks attack.fm so the cog
   can show a badge.
6. AI keys and the GitHub token are plain strings in the app's private SharedPreferences (`KeyboardPrefs.kt`), and
   the manifest sets no `android:allowBackup` or data-extraction rules, so Android's default of allowing backup
   applies. Confirm whether that file can reach a cloud backup, and exclude it.

## 4. A fair comparison

Checked 2026-11-09. "Not documented" means we found no mention in the vendor's own pages; it is not a statement by
the vendor.

| | Ghost Keyboard 0.8.13 | Gboard | SwiftKey | Samsung Keyboard | HeliBoard / FUTO |
| --- | --- | --- | --- | --- | --- |
| AI writing in the text field | Yes: `<<request>>` in any app | Seven preset tools, listed phones | Copilot and Compose removed, per Microsoft | Writing assist panel | No |
| Choose the model, use your own key | Yes: seven providers or your own server | Not documented | Not documented | Not documented | No (LeanType, a HeliBoard fork: yes) |
| Rewrites in your own voice | Yes: learned style profile | Preset tones | Four preset tones (help page) | Preset tones | No |
| AI needs a particular phone | No: any Android 8+ phone with a key | Yes: listed devices | — | Galaxy with Galaxy AI | — |
| Next-word prediction | **No** | Yes | Yes, its strength | Yes (Predictive text) | FUTO: yes (English model) |
| Autocorrect languages | **6** | Far more (count not checked) | 700+, five at once | A dictionary per added language | More, with multilingual typing |
| Swipe typing | Yes; accuracy unmeasured | Yes | Yes (Flow) | Yes | FUTO yes; HeliBoard with a library you add |
| Touch model you can see | Yes: heatmap, reset | Not documented | Not documented | Not documented | Not documented |
| Dictation | On the phone, offline, 60 s takes | Voice typing; Rambler on Pixel 11 | English only, or Google's | Yes | FUTO: offline; HeliBoard: none |
| Clipboard history | Last 25, no timer, no pins | Unpinned clips go in about an hour | Unpinned clips expire after 1 hour; pins | Yes; no auto-delete (Apr 2025) | Yes |
| Terminal keys | Esc, Tab, sticky Ctrl/Alt, arrows, any app | No Ctrl or Esc found | Arrow row only | No built-in | Custom layouts only |
| One-handed / floating | **No** | Yes | Yes | Floating mode | Yes |
| Split on a foldable | Yes, fixed | Yes | Yes | Yes, default on Fold | HeliBoard yes; FUTO split mode |
| Open export of what it learned | Yes: JSON with a schema | Not documented | Dictionary file in OneDrive | Not documented | HeliBoard: backup zip |
| Account | None (your AI provider's key if you use AI) | Not needed to type | Not needed to type; Microsoft account for backup | Samsung account for Writing assist | None |
| Ads | None | None | "Contains ads" on Play | None | None |
| Open source | **No**: source public, no licence | No | No | No | HeliBoard GPL-3.0; FUTO source-available |
| Internet permission | Yes | Yes | Yes | Yes | HeliBoard: none |
| Where you get it | **APK from attack.fm only** | Play, preinstalled, iOS | Play, App Store | Preinstalled on Galaxy | F-Droid, GitHub; FUTO also on Play |

## 5. Positioning

**One sentence.** Ghost Keyboard is the Android keyboard for people who write code and talk to AI: it writes in
place in any app, in your voice, with the model and key you choose; it has real terminal keys everywhere; and what
it learns about your typing stays in a file you own.

**Tagline.** "Your keys. Your model. Your words."
It reads three ways at once (the keys you type on and the API key you hold; the AI you picked; your own voice) and
every word of it is true today. Secondary line, over the developer section: "A keyboard for people who write code."

**Why this and not "a better Gboard".** The three strategies agree on more than they differ:

- The *wedge* is developers and people who use AI all day. Prose keyboards have no Esc or Ctrl and lock AI to one
  vendor; developer keyboards have the keys and weak prose. Ghost Keyboard has both halves in one native keyboard.
- The *promise* is AI on your terms. No mainstream keyboard documents a model choice or a user-supplied key, Google
  gates its tools by phone, and Microsoft has pulled its own.
- The *proof* is trust you can check. Against Gboard and SwiftKey this is our strongest ground; against HeliBoard
  and FUTO it is our weakest, until the gaps in section 7 are closed.
- The *floor* is plain typing. A switcher leaves in the first hundred words if typing feels worse. We do not have
  to beat Gboard at prediction; we do have to stop losing people to its absence.

**What we do not compete on, and say so:** language count, stickers, Emoji Kitchen, handwriting, in-keyboard
translation, iOS, store reach.

## 6. Moves, ranked

Effort: S is days, M is a week or two, L is a month or more for one developer.

### Now

| # | Move | Why | Effort |
| --- | --- | --- | --- |
| 1 | **Publish "What leaves your phone"**: every host, which switch triggers it, what is sent, when. On the site and in Settings. Complete the `hosts` lists first (section 3, item 4) so it can be generated from them. | The honest substitute for "no internet permission", and a claim Gboard and SwiftKey cannot match. | S |
| 2 | **Decide the licence.** Add one, or say "source public on GitHub" everywhere. Bring `README.md` up to date. Publish the signing certificate's SHA-256 and each release's hash. | The developer and privacy audiences check this first; without a licence the code is all rights reserved. | S |
| 3 | **Guard the keys.** Move AI keys and the GitHub token to Keystore-backed storage, exclude them from backup and from the profile export, and say so. | A bring-your-own-key product is judged first on how it holds the key. Do this before the site leads with it. | S |
| 4 | **Build the site around a live keyboard** (brief in section 9). | Rival pages are static pictures; the two things nobody shows together are inline AI and terminal keys. | L |
| 5 | **Register for Android developer verification.** | Without it a sideload install was scheduled to need a 24-hour wait, globally from 2027. | S |
| 6 | **Offline switch**: one setting that turns off every network path (AI, GIFs, GitHub, Ghost.md online, model download, update check) and shows its state. | Gives the HeliBoard crowd the outcome they want without removing AI for everyone else. | M |
| 7 | **Agent row**: a terminal-row preset for coding agents (Esc as a thumb key, Esc-Esc, Shift+Tab, backslash-Enter, Ctrl+C). | A small extension of a row that exists; the 2026 guide names missing Esc and Ctrl as the obstacle. | S |
| 8 | **Start the measurements**: a private swipe and autocorrect benchmark (top-1 and top-3 on a fixed list), cold-open time and key latency, tracked per release. | Nothing stronger than "built in" can be said about swipe, or "instant" about opening, until numbers exist. | M |
| 9 | **Fix the wording** in section 3 (a third of a key; "once, to train"). | The site will quote these. | S |

### Next

| # | Move | Why | Effort |
| --- | --- | --- | --- |
| 10 | **Next-word prediction** with a personal model learned on the phone, off in incognito and password fields, carried in the typing profile. Start now; it will land here. | The largest single thing a Gboard or SwiftKey user loses on day one. | L |
| 11 | **Improve the swipe decoder** against the benchmark; always offer three or more alternates. | Swipe is where alternatives lose switchers. | L |
| 12 | **"What was sent" receipt** for each AI request: provider, model, size, which sources were included. Kept on the phone. | Ghostwriter can send past messages and clipboard; showing it per request is the honest version of privacy. | M |
| 13 | **Local-model path**: document Ollama, LM Studio or llama.cpp as the Custom provider. The address must be https today, so publish a TLS guide or allow http on private ranges behind a warning. | "AI with nothing leaving your network" is something no mainstream keyboard offers. Do not promise it before it works out of the box. | S to M |
| 14 | **Dictation parity**: lift or chunk past 60 seconds, show text while speaking, keep listening through pauses. Then **Ramble to tidy** on top. | Google validated the category with Rambler but ties it to Pixel 11; Wispr charges $15 a month. | M |
| 15 | **Google Play listing** beside the self-updating APK: data-safety form, in-app disclosure for text sent to the AI provider, a build without the self-installer. | The largest reach limit and the clearest trust signal. Play requires prominent disclosure ([policy](https://support.google.com/googleplay/android-developer/answer/10144311)). | M |
| 16 | **Secret guard**, on the phone, then optional clipboard auto-expiry and pins. | No rival found with it; it extends the trust story and fits developers exactly. | M |
| 17 | **Shell from words** and **PR and commit writer**. | Brings Termius's best trick to every terminal app with your own model; GitHub on a phone is unserved. | M |
| 18 | **Guided key setup** per provider, and a first run that works with no key at all: languages, a typing warm-up that seeds Adaptive keys, import of a dictionary from another keyboard. | Bring-your-own-key is the moat and the biggest drop-off. | M |
| 19 | **Fold and large phones**: adjustable split gap, remembered per posture, one-tap split and merge; one-handed and floating modes. | Every mainstream rival has these; Samsung's split is the Fold default. | M |
| 20 | **More Latin-script dictionaries** (the format is a frequency list), by demand. | Six languages is the gap reviews hit first. Other scripts are a different project; decline them openly. | M |

### Later

| # | Move | Why | Effort |
| --- | --- | --- | --- |
| 21 | Tone dial, Your phrases, Cool-off, Translate as you, Clipboard actions, Typing Wrapped, profile sync through Ghost.md. | They deepen what a person cannot get back on Gboard, after the floor holds. | L |
| 22 | Make the typing profile a standard: licence and version the schema, a viewer on the site, importers. | SwiftKey's 2026 move is the opening for "your typing is a file you own". | M |
| 23 | Independent evidence: a published traffic capture with every plugin toggled, reproducible-build notes, an F-Droid-style repository, then a third-party review. | Self-declared privacy is what the incumbents already offer. | L |
| 24 | An optional one-time supporter payment, and the business model stated on the site. | Answers "how does a free keyboard pay for itself". There is no AI cost to us. | S |
| 25 | iOS. | A separate product under Apple's keyboard limits. Decide later; say "Android only" until then. | L |

## 7. Table-stakes gaps to close

In the order a switcher meets them.

1. **Trust before the first keystroke.** Sideloaded, self-installing, with INTERNET and RECORD_AUDIO, no licence,
   and nothing public about what it contacts. Moves 1, 2, 3, 5, 6.
2. **An empty row after a space.** No next-word prediction. Move 10.
3. **Swipe and autocorrect of unknown standing.** Moves 8 and 11.
4. **Six languages.** Move 20, and a plain sentence about who it is for today.
5. **Dictation.** 60-second takes, no live text. Move 14.
6. **One-handed, floating, adjustable split.** Move 19.
7. **Clipboard pins.** Move 16.
8. **A key is needed for AI.** Move 18.
9. **Distribution.** No Play, F-Droid or iOS. Moves 5 and 15.

Not worth chasing: stickers, Emoji Kitchen, handwriting, Morse, hundreds of languages. Put them in a "what you give
up" section on the site.

## 8. Risks

- **Typing quality is unmeasured.** A frequency dictionary and a simple swipe decoder are a long way from models
  trained on billions of users. If the benchmark is poor, lead with AI, voice and developer keys and say only
  "built in" about swipe.
- **One developer, many fronts.** Prediction, swipe, Play, languages and modes compete with eleven planned features.
  Keep "now" to trust, the site, verification and the start of prediction.
- **Privacy wording can backfire.** "No Ghost server between you and your AI" is true. "Nothing leaves your phone"
  is false: Ghostwriter sends the request, surrounding text and whatever sources are switched on to the provider;
  Mockingbird sends samples to train; GIPHY sees searches; updates and models come from attack.fm and
  huggingface.co; Ghost.md online signs in to ghostmarkdown.com. Bring-your-own-key moves trust to the provider; it
  does not remove it.
- **"No telemetry" rests on a search, not an audit.** The update check still tells attack.fm an address and a
  version. Disclose it.
- **Keys in plain preferences** (section 3, item 6) would be a damaging finding if someone else reports it first.
- **The self-updater is a high-value target.** The hash and certificate checks limit a compromised server; they do
  not help if the signing key in `~/.config/devkeyboard/` is lost or stolen. Say how it is held.
- **Sideloading on a clock**, and Play review of a keyboard that records audio and sends text to third-party AI may
  be slow.
- **Incumbents can close parts of the gap.** Custom instructions and a clipboard slider are in Gboard betas, Rambler
  may widen, Microsoft promises a new Compose, LeanType already has bring-your-own providers. What lasts is the
  combination and the terminal and agent focus, not one feature.
- **A narrow wedge is a small market.** It is a beachhead.
- **Comparison claims.** Several competitor facts are absences or were read through a search index. Use vendor
  pages and Play listings, dated; word absences as "not documented"; no competitor or provider logos.
- **Mockingbird can read as surveillance** if a person finds the samples later. Plain wording, an inspectable
  profile and the delete button belong on the site.

## 9. What the website may say

Give the site a "last checked 2026-11-09" line and regenerate it per release from `pubspec.yaml`.

### It may say

- Type `<<what you want>>` in any app and Ghost Keyboard writes it in place, typed out key by key. Where it does not
  know a fact it leaves a [blank].
- Your model, your key: Claude, OpenAI, Gemini, Mistral, Groq, DeepSeek, OpenRouter or your own OpenAI-compatible
  https server. Requests go from your phone to the provider you chose. There is no Ghost server in between.
- The text you ask about does go to that provider, with the sources you switched on. (Say this beside the claim
  above, every time.)
- AI works on the Android phone you already have (Android 8 or later); it needs a provider key, not a particular
  phone.
- Mockingbird learns how you write from samples kept on your phone, and "In my voice" rewrites a selection as you.
  Samples are sent to your own provider when the profile is trained.
- Adaptive keys learns where you aim, moves touch areas by up to about a third of a key, shows a heatmap, and can be
  reset. It stays on the phone.
- Autocorrect in English, Spanish, French, German, Italian and Portuguese, with a programmer's vocabulary and
  texting slang. Backspace straight after a correction puts back exactly what you typed, and that word is not
  corrected again.
- Swipe typing is built in.
- Dictation runs on your phone with whisper; the network is used only to download the model. Takes are up to 60
  seconds.
- Terminal keys (Esc, Tab, sticky Ctrl and Alt, arrows) appear on their own in Termux, Termius, JuiceSSH and
  ConnectBot, and anywhere else if you turn them on. A `{ }` page of pairs and snippets, a markdown row, arrow keys,
  undo and redo.
- Clipboard history keeps your last 25 copies on the phone, with no timer, and never what Android marks sensitive
  or anything copied in incognito.
- Password-manager logins above the keys on Android 11 and later; Emoji 18.0 with search and :shortcodes; GIFs from
  GIPHY (GIPHY sees your searches).
- Nothing is learned in password fields or while incognito.
- Your typing profile is an open JSON file with a published schema, to export and import.
- Splits in two on an unfolded foldable or a tablet. Themes, six backlight effects, key sounds.
- No account. No ads. Free. If you use AI you pay your provider directly.
- Updates come from attack.fm; each download is checked for size, SHA-256 and the signing certificate of the app
  you installed before it is installed.
- Source is public on GitHub.
- About rivals, each with its link and date: Google's help page lists specific phones for Writing tools and Pixel 11
  series for Rambler; Microsoft's support site says Copilot and Compose have been removed from SwiftKey; SwiftKey's
  Play listing is tagged "Contains ads"; Gboard's unpinned clipboard items are deleted after about an hour (Android
  Authority, August 2026).

### It may say only as "coming"

Secret guard, Tone dial, Shell from words, Cool-off, Ramble to tidy, Translate as you, Your phrases, Clipboard
actions, PR and commit writer, Typing Wrapped, profile sync through Ghost.md. Any mock-up of these is labelled as a
preview. The moves proposed in this document that are not yet agreed (Agent row, Offline switch, next-word
prediction, the "what was sent" receipt, a Play listing) do not appear on the site until they are decided.

### It must say (the honest limits)

Android only. Installed from attack.fm, not Google Play. No next-word prediction yet. Six autocorrect languages. No
one-handed or floating mode. No stickers, Emoji Kitchen, handwriting or in-keyboard translation. AI needs your own
provider key.

### It must not say

- "Open source" (until a licence is added), "100% offline", "no internet", "nothing leaves your phone".
- "No analytics" as an audited fact; say "no analytics or crash-reporting library in the app" and list the hosts.
- "More accurate than Gboard", "best swipe", "instant" with a number, or any accuracy comparison, until measured.
- "The only keyboard with your own AI key" (LeanType exists). "No mainstream keyboard documents one" is fine.
- "Gboard has no Tab key", "Gboard is Gemini only" as Google's statement, FUTO's swipe as weak, the Wispr screenshot
  story, Reddit vote counts, the Android Authority poll percentages, CleverType's yearly price, forum insults.
- "Samples leave the phone once", "never more than a third of a key", or "automatic updates off means no contact
  with attack.fm", until section 3 is fixed.
- Any competitor's or AI provider's logo. Names in text only.

## Sources opened for the website, 2026-11-09

The comparison table on the site (`site/index.html`, notes 2, 3, 5, 6 and 9) cites four pages this document stated
without a link. Each was opened on 2026-11-09; the sentence relied on is quoted.

| Site cell | Page | Sentence relied on |
|---|---|---|
| Gboard: "Tab on its tablet layout, per 9to5Google" (note 5) | [9to5Google, "Google finally updates Gboard with Android tablet layout", 10 Oct 2022](https://9to5google.com/2022/10/10/gboard-android-tablet/) | "At the left, you now have keys for Tab and Caps Lock in the first two rows." |
| SwiftKey: "An optional arrow row" (note 5) | [Microsoft, "How do I enable/disable arrow keys on my Microsoft SwiftKey Keyboard?"](https://support.microsoft.com/en-us/swiftkey-keyboard/how-do-i-enable-disable-arrow-keys-on-my-microsoft-swiftkey-keyboard) | "Check or uncheck 'Arrow Keys'" |
| SwiftKey: "Four preset tones, per its help page" (notes 2 and 3) | [Microsoft, "How do I access Toolbar in Microsoft SwiftKey Keyboard?"](https://support.microsoft.com/en-us/swiftkey-keyboard/how-do-i-access-toolbar-in-microsoft-swiftkey-keyboard) | "We have "Professional", "Casual", "Polite" and "Social Post" tones." The page says nothing of choosing a model or using a key of your own. |
| SwiftKey: "Unpinned clips expire after an hour; clips can be pinned" (note 6) | [Microsoft, "How does the Clipboard work with Microsoft SwiftKey Keyboard?"](https://support.microsoft.com/en-us/topic/how-does-the-clipboard-work-with-microsoft-swiftkey-keyboard-322f9977-dab7-4edb-a7aa-e1c0611cf7eb) | "Pin copied clips to stop them expiring after 1 hour." and "Your clips can be pinned to the top of your Clipboard by selecting the pin icon to the right of each selection." |

Two corrections came out of this:

- The page the site first cited for SwiftKey's clipboard
  ([copy and paste between SwiftKey and Windows](https://support.microsoft.com/en-au/swiftkey-keyboard/how-to-use-microsoft-swiftkey-keyboard-to-copy-and-paste-text-between-swiftkey-and-windows))
  is about the Cloud Clipboard and does not mention pinning; the site now cites the clipboard page above.
- SwiftKey's clipboard is not "no timer": unpinned clips expire after an hour, as Gboard's do. The site's cell says
  so. Ghost Keyboard's "no timer" is a difference from both; "no pins" is where it is behind both.

Still open: the Gboard terminal-key row has not been checked on a phone or a Fold, and the two Play data-safety pages
were not re-read for the site (the SwiftKey cell follows this document: optional location and device IDs for
advertising or marketing, collected and not shared, with usage statistics and crash reports from the Microsoft
support page).

