import Foundation

/// When Mockingbird's style profile is due its training, and which version of the style rules a profile carries
/// through a typing-profile file. A profile trained before `VoiceRules.profileVersion` 2 was asked for the "typos
/// they leave in" and may describe slips as habits: it is `outdated` until it is trained again, wherever it has been
/// in between.
///
/// Plain decisions, so they can be checked without a phone. Times are in milliseconds.
public enum Retrain {
    /// The name Ghost Keyboard signs its typing-profile files with (`source.app`).
    public static let ownApp = "Ghost Keyboard"

    /// New samples before a profile retrains by itself, and before the first one is trained.
    public static let afterSamples = 50

    /// The fewest samples training takes.
    public static let minSamples = 10

    /// "At most daily": the least time between two trainings started by themselves.
    public static let day: Int64 = 20 * 3_600_000

    /// What a profile brought in from a typing-profile file says trained it; `source` is the file's app and model.
    public static func importedBy(_ source: String?) -> String { "Imported" + (source.map { " from \($0)" } ?? "") }

    /// A profile written somewhere else (another app, another AI) and brought in from a file: the person's choice,
    /// not something trained here, so never outdated. One from Ghost Keyboard's own export was trained here, on this
    /// phone or another, and is judged by its version like any other.
    private static func fromElsewhere(_ provider: String, _ model: String) -> Bool {
        model.isEmpty && provider.hasPrefix("Imported") && provider != importedBy(ownApp)
    }

    /// True for a profile trained under older instructions than today's (`version` below
    /// `VoiceRules.profileVersion`): it is due a retrain. `provider` and `model` are what trained it.
    public static func outdated(version: Int, provider: String, model: String) -> Bool {
        version < VoiceRules.profileVersion && !fromElsewhere(provider, model)
    }

    /// The rules' version a typing-profile file carries for its style profile (`style.profileVersion`): the
    /// profile's own while it is outdated, so going through a file cannot make an old profile current; today's for
    /// every other.
    public static func exportVersion(version: Int, provider: String, model: String) -> Int {
        outdated(version: version, provider: provider, model: model) ? version : VoiceRules.profileVersion
    }

    /// The version a style profile from a file is kept at. From Ghost Keyboard's own export (`sourceApp` is
    /// `ownApp`) it is the file's `fileVersion`, and 1 where the file has none: every build that wrote none trained
    /// under the first instructions. From anywhere else it is today's.
    public static func importVersion(sourceApp: String?, fileVersion: Int?) -> Int {
        sourceApp == ownApp ? min(VoiceRules.profileVersion, max(1, fileVersion ?? 1)) : VoiceRules.profileVersion
    }

    /// Whether to train now, by itself. Never trained (`trained` false): once there are `afterSamples` `samples`.
    /// An `outdated` profile: once, without waiting for new samples or for a day since it was trained, as soon as
    /// there are `minSamples`; if that training fails it is tried again a `day` later, not sooner
    /// (`sinceOutdatedTry` is the time since the last try). Any other: after `afterSamples` `newSamples` and a
    /// `day` `sinceTrained`.
    public static func due(trained: Bool, outdated: Bool, samples: Int, newSamples: Int, sinceTrained: Int64, sinceOutdatedTry: Int64) -> Bool {
        if !trained { return samples >= afterSamples }
        if outdated { return samples >= minSamples && sinceOutdatedTry > day }
        return newSamples >= afterSamples && sinceTrained > day
    }
}
