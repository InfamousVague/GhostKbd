import AVFoundation
import Flutter
import UIKit

/// The `dev_keyboard/settings` channel on iOS: the same contract Android's MainActivity answers, so
/// lib/settings_store.dart works on both. Settings go to the App Group's UserDefaults (the keyboard extension reads
/// them there), AI keys and the GitHub token to the Keychain group the two share.
final class SettingsChannel: NSObject {
    static let shared = SettingsChannel()

    private var channel: FlutterMethodChannel?
    private let group: UserDefaults = GhostShared.groupDefaults ?? .standard
    private lazy var prefs = KeyboardPrefs(store: group, secrets: GhostShared.secrets())
    private lazy var mockingbird = Mockingbird(directory: GhostShared.filesDirectory())
    private lazy var clips = ClipHistory(store: group)
    private lazy var github = GitHubCommits(store: group)
    private var player: AVAudioPlayer?
    private var lookupAsset: ((String) -> String)?

    /// A settings page the app was opened at (ghostkbd://settings/<page>), handed to Flutter once.
    var openAt: String?

    func attach(messenger: FlutterBinaryMessenger, lookupAsset: @escaping (String) -> String) {
        self.lookupAsset = lookupAsset
        prefs.addNewDefaults()
        group.set(Date().timeIntervalSince1970, forKey: GhostShared.appMarker)
        let channel = FlutterMethodChannel(name: "dev_keyboard/settings", binaryMessenger: messenger)
        channel.setMethodCallHandler { [weak self] call, result in self?.handle(call, result) }
        self.channel = channel
        NotificationCenter.default.addObserver(self, selector: #selector(becameActive), name: UIApplication.didBecomeActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(resignActive), name: UIApplication.willResignActiveNotification, object: nil)
    }

    /// Coming back from the Settings app: tell Flutter to look again.
    @objc private func becameActive() {
        mockingbird.reload()
        clips.reload()
        channel?.invokeMethod("statusChanged", arguments: status())
    }

    @objc private func resignActive() { stopAudio() }

    /// ghostkbd://settings, ghostkbd://settings/<page>: the keyboard's cog, or a link.
    func open(_ url: URL) {
        guard url.scheme == GhostShared.urlScheme else { return }
        #if DEBUG
        // Debug builds only, for the UI tests: ghostkbd://debug-set?key=value&... writes settings (true/false as Bools).
        if url.host == "debug-set" {
            for item in URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [] {
                let v = item.value ?? ""
                let value: Any = (v == "true" || v == "false") ? (v == "true") as Any : v as Any
                if !prefs.set(key: item.name, value: value) { _ = prefs.set(key: item.name, value: v) }
            }
            GhostShared.postSettingsChanged()
            return
        }
        #endif
        let page = url.pathComponents.dropFirst().first ?? "settings"
        if channel != nil { channel?.invokeMethod("open", arguments: page) }
        openAt = page
    }

    private func handle(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any] ?? [:]
        switch call.method {
        case "getSettings": result(prefs.all())
        case "setSetting":
            guard let key = args["key"] as? String, prefs.set(key: key, value: args["value"]) else {
                result(FlutterError(code: "bad_setting", message: "Unknown setting or wrong type: \(args["key"] ?? "")", details: nil)); return
            }
            GhostShared.postSettingsChanged()
            result(nil)
        case "setPlugin":
            guard let id = args["id"] as? String, Plugins.set(prefs, id, on: args["on"] as? Bool == true) else {
                result(FlutterError(code: "bad_plugin", message: "Unknown plugin: \(args["id"] ?? "")", details: nil)); return
            }
            GhostShared.postSettingsChanged()
            result(prefs.all())
        case "status": result(status())
        case "openInputSettings":
            // This app's own page in Settings: Keyboards is on it, with Ghost Keyboard and Allow Full Access.
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            result(nil)
        case "showPicker": result(nil)
        case "playAsset": result(play(args["asset"] as? String ?? ""))
        case "stopAudio": stopAudio(); result(nil)
        case "openAt": result(openAt); openAt = nil
        case "clipboardState": result(["count": clips.all().count])
        case "clearClipboard": clips.clear(); result(["count": 0])
        // The App Store updates the app; there is no microphone for a keyboard on iOS (docs/IOS.md).
        case "updateState": result(["phase": "disabled", "current": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""])
        case "dictationState": result(["phase": "unsupported", "microphone": false])
        case "statsState", "statsReset": result([String: Any]())
        case "profilesState": result(["profiles": [Any](), "recent": [Any]()])
        case "aiState": result(aiState())
        case "aiModels": background(result) { try AiClient.models(prefs: self.prefs) }
        case "aiTest": background(result) { try AiClient.complete(prefs: self.prefs, system: "Reply with the single word OK.", user: "Are you there?", maxTokens: 2000, effort: "low") }
        case "mockingbirdTrain":
            guard prefs.aiConsent else { result(FlutterError(code: "ai", message: Self.noConsent, details: nil)); return }
            mockingbird.train(prefs: prefs) { [weak self] error in
                var state = self?.aiState() ?? [:]
                state["trainError"] = error
                result(state)
            }
        case "mockingbirdClear": mockingbird.clear(); result(aiState())
        case "githubCheck":
            background(result) {
                let token = self.prefs.githubToken.trimmingCharacters(in: .whitespacesAndNewlines)
                if token.isEmpty { throw GitHubCommits.GitHubError("Paste a GitHub token first") }
                let a = try self.github.account(token: token, refresh: true)
                return ["login": a.login, "owners": a.owners]
            }
        case "adaptiveState": result(adaptiveState(AdaptiveKeys(store: group)))
        case "adaptiveReset":
            let model = AdaptiveKeys(store: group)
            model.reset()
            GhostShared.postSettingsChanged()
            result(adaptiveState(model))
        default: result(FlutterMethodNotImplemented)
        }
    }

    static let noConsent = "Agree to how the AI features use your text first (Settings › AI)"

    /// Runs `work` off the main thread; its value, or its error's message as an "ai" error, goes back to Flutter.
    private func background(_ result: @escaping FlutterResult, _ work: @escaping () throws -> Any?) {
        guard prefs.aiConsent else { result(FlutterError(code: "ai", message: Self.noConsent, details: nil)); return }
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let value = try work()
                DispatchQueue.main.async { result(value) }
            } catch {
                let message = (error as? LocalizedError)?.errorDescription ?? "Something went wrong"
                DispatchQueue.main.async { result(FlutterError(code: "ai", message: message, details: nil)) }
            }
        }
    }

    /// `enabled`: Ghost Keyboard is in the phone's list of keyboards. `selected`: it has been typed with at least
    /// once. `fullAccess`: it had Allow Full Access the last time it ran (it tells us through the App Group, which
    /// it can only write to with Full Access).
    private func status() -> [String: Bool] {
        let extensionId = (Bundle.main.bundleIdentifier ?? "") + ".keyboard"
        // iOS has no call for "is my keyboard added": the phone's list of keyboards is in the defaults every app reads.
        // Where iOS keeps that list from us, having run at all is the next best sign.
        let list = UserDefaults.standard.object(forKey: "AppleKeyboards") as? [String]
        let enabled = list.map { $0.contains(extensionId) } ?? (group.double(forKey: GhostShared.keyboardSeen) > 0)
        let seen = group.double(forKey: GhostShared.keyboardSeen) > 0
        return ["enabled": enabled, "selected": enabled && seen, "fullAccess": enabled && group.bool(forKey: GhostShared.keyboardFullAccess)]
    }

    /// Settings › AI and Mockingbird: the providers, whether one is ready, the samples and the style profile.
    private func aiState() -> [String: Any?] {
        let samples = mockingbird.samples()
        let profile = mockingbird.profile()
        return [
            "providers": AiClient.providers.map { ["id": $0.id, "name": $0.name, "defaultModel": $0.defaultModel, "keyFrom": $0.keyFrom] },
            "ready": AiClient.ready(prefs: prefs),
            "samples": samples.count,
            "chatSamples": samples.filter { $0.chat }.count,
            "writingSamples": samples.filter { !$0.chat }.count,
            "profile": profile.map { ["text": $0.text, "at": $0.at, "samples": $0.samples, "provider": $0.provider, "model": $0.model] as [String: Any] },
            "training": mockingbird.training,
            "error": mockingbird.lastError,
            "ghostInstalled": false,
            "ghostOnline": false,
        ]
    }

    /// Settings › Adaptive keys: how many letters have learned aims, and how many mistakes it remembers.
    private func adaptiveState(_ model: AdaptiveKeys) -> [String: Any] {
        var counts: [String: Int] = [:]
        for m in model.mistakes { counts["\(m.pressed)→\(m.meant)", default: 0] += 1 }
        let common = counts.sorted { $0.value > $1.value }.prefix(5).map { "\($0.key) ×\($0.value)" }
        return ["keys": model.learnedKeys(), "mistakes": model.mistakes.count, "common": Array(common)]
    }

    /// The walkthrough's voice: one of the app's own assets, played once.
    private func play(_ asset: String) -> Bool {
        stopAudio()
        guard let key = lookupAsset?(asset), let path = Bundle.main.path(forResource: key, ofType: nil) else { return false }
        do {
            try AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
            let p = try AVAudioPlayer(contentsOf: URL(fileURLWithPath: path))
            player = p
            return p.play()
        } catch { return false }
    }

    private func stopAudio() { player?.stop(); player = nil }
}
