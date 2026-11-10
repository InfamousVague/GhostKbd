# Submitting Ghost Keyboard for iOS

Step 1 is done (2026-11-10). Nothing has been uploaded, and the app record (step 2) does not exist yet: steps 2 to 4
create things in App Store Connect, so they are Matt's to do or to approve.

## 1. The identifiers and profiles (done)

Registered under Team `F6ZAL7ANAD` at developer.apple.com › Certificates, IDs & Profiles:

| Kind | Identifier | Notes |
| --- | --- | --- |
| App Group | `group.com.infamousvague.ghostkbd` | |
| App ID | `com.infamousvague.ghostkbd` | App Groups, with the group above assigned |
| App ID | `com.infamousvague.ghostkbd.keyboard` | App Groups, with the group above assigned |
| Profile (App Store) | `Ghost Keyboard App Store` | for the app, with the Apple Distribution certificate |
| Profile (App Store) | `Ghost Keyboard Extension App Store` | for the keyboard extension |

Keychain sharing needs nothing registered. The two profiles are installed on this Mac
(`~/Library/Developer/Xcode/UserData/Provisioning Profiles`); on another Mac, download them from the portal and open
them. They expire with the distribution certificate (2027-04-14).

Signing is by those named profiles (`ios/AppStore.xcconfig`), not Xcode's automatic signing: `xcodebuild
-allowProvisioningUpdates` with the API key is refused ("Authentication failed: … bearer token") although the same key
works against the App Store Connect API, and Xcode has no account signed in on this Mac.

## 2. Create the app record (App Store Connect › Apps › +)

Platform iOS; name "Ghost Keyboard" (see `metadata.md` if taken); primary language English (U.S.); bundle ID
`com.infamousvague.ghostkbd`; SKU `ghostkbd-ios`.

## 3. Archive and upload

```bash
flutter build ios --release --no-codesign      # compiles the Dart, writes ios/Flutter/Generated.xcconfig
```

```bash
cd ios && xcodebuild archive -workspace Runner.xcworkspace -scheme Runner -configuration Release -destination 'generic/platform=iOS' -archivePath ../build/GhostKeyboard.xcarchive -xcconfig AppStore.xcconfig
```

```bash
cd ios && xcodebuild -exportArchive -archivePath ../build/GhostKeyboard.xcarchive -exportOptionsPlist ExportOptions.plist -exportPath ../build/ipa
```

That leaves `build/ipa/Ghost Keyboard.ipa`, signed for the App Store, and uploads nothing (`ExportOptions.plist` has
`destination: export`). Both commands have been run and succeed; the archive's app and extension carry the App Group
and the shared Keychain group.

To check it against App Store Connect, and then to send it (each needs the app record of step 2; the key comes from
`~/.config/mattssoftware/signing/asc-api.env`):

```bash
~/Development/Apps/ghost-md-app-store-pack/tools/altool.sh validate "build/ipa/Ghost Keyboard.ipa" ios
```

```bash
~/Development/Apps/ghost-md-app-store-pack/tools/altool.sh upload "build/ipa/Ghost Keyboard.ipa" ios
```

The version and build number come from `pubspec.yaml`: the app and the keyboard extension must carry the same pair,
and both read it from there.

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
