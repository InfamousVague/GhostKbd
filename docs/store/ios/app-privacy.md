# App Privacy questionnaire (App Store Connect › App Privacy)

## The answer

**"Do you or your third-party partners collect data from this app?" → No.** The label reads "Data Not Collected".

## Why that is true

Apple's definition: data is "collected" when it is transmitted off the device in a way that lets **you or your
third-party partners** access it beyond what is needed to service the request in real time.

- The developer receives nothing: no server, no analytics, no crash reporter, no advertising SDK. The only third-party
  code is the Flutter engine (which ships its own privacy manifest and collects nothing).
- The AI requests go from the device to a provider the **user** chose, authenticated with the **user's own** API key
  under the user's own account and contract with that provider. The provider is the user's service, not the
  developer's partner: the developer has no agreement with it about this data and no access to it. This is the same
  footing as a mail client sending mail to the user's own mail server, or a Git client pushing to the user's host.
- GitHub requests, likewise, use the user's own token against the user's own account.
- Everything else (settings, learned words, touch model, clipboard history, writing samples, style profile, keys)
  stays on the device, in the App Group container and the Keychain.

## If App Review disagrees

Should a reviewer take the view that text sent to the chosen AI provider is "collected", the conservative label is:

| Data type | Linked to the user | Used for tracking | Purpose |
| --- | --- | --- | --- |
| User Content › Other User Content | No | No | App Functionality |

and nothing else. Do not add this pre-emptively; it would overstate what the developer can see.

## Related answers elsewhere in App Store Connect

- **Tracking / App Tracking Transparency**: no tracking; the ATT prompt is not used.
- **Privacy manifest** (`ios/Runner/PrivacyInfo.xcprivacy`, `ios/GhostKeyboard/PrivacyInfo.xcprivacy`): no tracking,
  no tracking domains, no collected data types; required-reason APIs declared: UserDefaults (`CA92.1` the app's own
  settings, `1C8F.1` the App Group shared with the keyboard) and file timestamps (`C617.1`, files in the app's own
  container).
- **Export compliance**: the app uses only the HTTPS that iOS provides. `ITSAppUsesNonExemptEncryption` is `false` in
  Info.plist, so the question is not asked on upload.
- **Content rights**: the app shows no third-party content. Bundled: the wordfreq-derived word lists (CC BY-SA 4.0,
  credited on the app's About page), Phosphor icons (MIT), Inter and JetBrains Mono (OFL).
- **Sign-in required**: no. There is no account.

## Consent in the app (guideline 5.1.2(i))

Before any typed text can go to an AI provider, the app shows a consent screen ("Before the AI writes for you",
`lib/ios/ai_consent.dart`) naming what is sent, to whom, and when, with "I agree" and "Not now". The agreement is the
setting `ai_consent`; the keyboard checks it before every request (`KeyboardAI.swift`, `aiBlocked()`), the app before
Test it and Train (`SettingsChannel.swift`), and automatic style training does not run without it. It can be withdrawn
in Settings › AI › Your AI.
