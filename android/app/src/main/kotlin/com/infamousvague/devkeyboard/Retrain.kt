package com.infamousvague.devkeyboard

/**
 * When Mockingbird's style profile is due its training, and which version of the style rules a profile carries
 * through a typing-profile file (Matt, 2026-11-09: "Do those fixes please"). A profile trained before
 * [VoiceRules.PROFILE_VERSION] 2 was asked for the "typos they leave in" and may describe slips as habits: it is
 * [outdated] until it is trained again, wherever it has been in between.
 *
 * Plain decisions, no Android, so they can be checked off the phone (tool/check_sample_cleaner.sh).
 */
object Retrain {
    /** The name Ghost Keyboard signs its typing-profile files with (`source.app`). */
    const val OWN_APP = "Ghost Keyboard"

    /** New samples before a profile retrains by itself, and before the first one is trained. */
    const val AFTER_SAMPLES = 50

    /** The fewest samples training takes. */
    const val MIN_SAMPLES = 10

    /** "At most daily": the least time between two trainings started by themselves. */
    const val DAY = 20 * 3_600_000L

    /** What a profile brought in from a typing-profile file says trained it; [source] is the file's app and model. */
    fun importedBy(source: String?): String = "Imported" + (source?.let { " from $it" } ?: "")

    /**
     * A profile written somewhere else (another app, another AI) and brought in from a file: the person's choice,
     * not something trained here, so never outdated. One from Ghost Keyboard's own export was trained here, on this
     * phone or another, and is judged by its version like any other.
     */
    private fun fromElsewhere(provider: String, model: String) =
        model.isEmpty() && provider.startsWith("Imported") && provider != importedBy(OWN_APP)

    /**
     * True for a profile trained under older instructions than today's ([version] below
     * [VoiceRules.PROFILE_VERSION]): it is due a retrain. [provider] and [model] are what trained it.
     */
    fun outdated(version: Int, provider: String, model: String): Boolean =
        version < VoiceRules.PROFILE_VERSION && !fromElsewhere(provider, model)

    /**
     * The rules' version a typing-profile file carries for its style profile (`style.profileVersion`): the
     * profile's own while it is outdated, so going through a file cannot make an old profile current; today's for
     * every other.
     */
    fun exportVersion(version: Int, provider: String, model: String): Int =
        if (outdated(version, provider, model)) version else VoiceRules.PROFILE_VERSION

    /**
     * The version a style profile from a file is kept at. From Ghost Keyboard's own export ([sourceApp] is
     * [OWN_APP]) it is the file's [fileVersion], and 1 where the file has none: every build that wrote none
     * trained under the first instructions. From anywhere else it is today's.
     */
    fun importVersion(sourceApp: String?, fileVersion: Int?): Int =
        if (sourceApp == OWN_APP) (fileVersion ?: 1).coerceIn(1, VoiceRules.PROFILE_VERSION) else VoiceRules.PROFILE_VERSION

    /**
     * Whether to train now, by itself. Never trained ([trained] false): once there are [AFTER_SAMPLES] [samples].
     * An [outdated] profile: once, without waiting for new samples or for a day since it was trained, as soon as
     * there are [MIN_SAMPLES]; if that training fails it is tried again a [DAY] later, not sooner
     * ([sinceOutdatedTry] is the time since the last try). Any other: after [AFTER_SAMPLES] [newSamples] and a
     * [DAY] [sinceTrained].
     */
    fun due(trained: Boolean, outdated: Boolean, samples: Int, newSamples: Int, sinceTrained: Long, sinceOutdatedTry: Long): Boolean = when {
        !trained -> samples >= AFTER_SAMPLES
        outdated -> samples >= MIN_SAMPLES && sinceOutdatedTry > DAY
        else -> newSamples >= AFTER_SAMPLES && sinceTrained > DAY
    }
}
