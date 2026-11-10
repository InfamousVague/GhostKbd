# Submitting Ghost Keyboard for iOS

Nothing below has been done yet: no identifiers registered, nothing uploaded. Steps 1 to 3 create things in the Apple
Developer account (Team `F6ZAL7ANAD`) and App Store Connect, so they are Matt's to do or to approve.

## 1. Register the identifiers (developer.apple.com › Certificates, IDs & Profiles)

| Kind | Identifier | Capabilities |
| --- | --- | --- |
| App Group | `group.com.infamousvague.ghostkbd` | |
| App ID | `com.infamousvague.ghostkbd` | App Groups (the group above), Keychain Sharing |
| App ID | `com.infamousvague.ghostkbd.keyboard` | App Groups (the group above), Keychain Sharing |

Or let Xcode do all three: open `ios/Runner.xcworkspace`, select each of the Runner and GhostKeyboard targets ›
Signing & Capabilities, with "Automatically manage signing" on and the team chosen. (From the command line the same
happens with `-allowProvisioningUpdates`, below.)

This is the only thing between the branch and a signed archive. Today a signed archive fails with:

    Provisioning profile "iOS Team Provisioning Profile: *" doesn't support the group.com.infamousvague.ghostkbd App Group.

An unsigned release archive builds (`CODE_SIGNING_ALLOWED=NO`), so the code and project are ready.

## 2. Create the app record (App Store Connect › Apps › +)

Platform iOS; name "Ghost Keyboard" (see `metadata.md` if taken); primary language English (U.S.); bundle ID
`com.infamousvague.ghostkbd`; SKU `ghostkbd-ios`.

## 3. Archive and upload

```bash
flutter build ios --release --no-codesign      # compiles the Dart, writes ios/Flutter/Generated.xcconfig
```

```bash
cd ios && xcodebuild archive -workspace Runner.xcworkspace -scheme Runner -configuration Release -destination 'generic/platform=iOS' -archivePath ../build/GhostKeyboard.xcarchive -allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID"
```

```bash
cd ios && xcodebuild -exportArchive -archivePath ../build/GhostKeyboard.xcarchive -exportOptionsPlist ExportOptions.plist -exportPath ../build/ipa -allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID"
```

The key variables come from `~/.config/mattssoftware/signing/asc-api.env` (use whatever names that file defines).
`ios/ExportOptions.plist` is set to `app-store-connect` with `destination: upload`, so the export step uploads the
build to App Store Connect; change `destination` to `export` to get an `.ipa` without uploading.

The version and build number come from `pubspec.yaml` (`version: 0.9.0+59`): the app and the keyboard extension must
carry the same pair, and both read it from there.

## 4. Fill in App Store Connect

| Where | From |
| --- | --- |
| App Information, version page | `metadata.md` |
| Screenshots | `screenshots.md` |
| App Privacy | `app-privacy.md`; Privacy Policy URL: wherever `privacy-policy.md` is hosted |
| App Review Information › Notes | `review-notes.md` (needs a review API key with a small limit) |
| Age rating, pricing | `metadata.md` |

## 5. Before pressing Submit

- [ ] Run on a real iPhone once. The Simulator cannot show haptics, the real memory limit, or the keyboard on a
      phone with a home button (where the globe key appears on the keyboard itself). See "Not verified" in
      `docs/IOS.md`.
- [ ] Type in Messages, Notes, Safari and Mail; lock and unlock; rotate.
- [ ] Privacy policy and support pages are live at the URLs entered.
- [ ] Screenshots and metadata name no third party.
- [ ] The review API key works and has a limit.

## Known review risks

- **4.1(a) / 2.3.10**: no third-party names in metadata or screenshots (handled in `metadata.md`). The provider picker
  inside the app necessarily names the providers; that is the app's function, not marketing.
- **Full Access**: explained in the walkthrough, in the review notes, and by the keyboard itself.
- **5.1.2(i)** (sharing data with third-party AI): the consent screen, before anything is sent.
- **Opening the companion app from the keyboard**: one switch turns it off (`review-notes.md`).
