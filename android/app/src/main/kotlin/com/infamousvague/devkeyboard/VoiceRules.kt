package com.infamousvague.devkeyboard

/**
 * What the AIs are told about the person's spelling (Matt, 2026-11-09: "Is the write like me functionality taking
 * into account auto correct so it doesn't assume I'm just leaving mistakes"): the one that trains Mockingbird's
 * style profile, and the ones that write with it (Ghostwriter, "In my voice").
 *
 * A habit the person keeps to is style and is imitated, a spelling habit as much as any other ("u" for "you", no
 * accents, "sooo"); a slip is not, and is never learned or copied. The AI that trains and the AIs that write are
 * given the same test for telling them apart: a habit is named in the profile or recurs, a slip is seen once. The
 * samples have their obvious slips repaired before any AI sees them ([SampleCleaner]); these instructions are for
 * the rest.
 *
 * No Android here, so the texts can be read and checked off the phone (tool/check_sample_cleaner.sh prints them).
 */
object VoiceRules {
    /**
     * The training instructions' version, kept with each profile. 1: before 2026-11-09, when the AI was asked for
     * the "typos they leave in". 2: only repeated conventions are style; slips are ignored.
     */
    const val PROFILE_VERSION = 2

    /** What the AI that trains the style profile is told (Mockingbird.train). */
    val TRAIN_SYSTEM = """
        You study one person's writing so that another AI can write exactly as they do. You get real things they typed
        on their phone: TEXTING (messages in chat apps) and WRITING (everything else: email, notes, posts, forms).

        Write their style profile, addressed to the AI that will imitate them ("They write…"). Cover, for texting and
        for writing separately where they differ:
        - capitalisation, punctuation and spelling conventions (read SPELLING below first)
        - length and rhythm: how long messages and sentences run, line breaks, lists
        - tone and register: warmth, bluntness, humour, sarcasm, swearing, formality, and how it shifts by context
        - vocabulary: words and phrases they reach for, slang, abbreviations, filler, words they never use
        - emoji, emoticons and reactions: which, how often, where
        - how they open, ask, agree, decline, apologise, thank and sign off
        - technical or work vocabulary and how they explain things
        End with 8 to 12 short phrases quoted from the samples that sound most like them. Quote none that contains
        a slip.

        SPELLING: HABITS ARE STYLE, SLIPS ARE NOT
        These were typed with thumbs on a phone, and a few slips are still in them. A slip says nothing about how
        this person writes, and the AI that imitates them must never be taught one.
        - Style is only a convention they keep to: something they do consistently, seen again and again across
          different messages. Always lower case. "u" for "you". No apostrophes. Dropped full stops. A recurring
          abbreviation or nickname. A word they stretch ("sooo") time after time.
        - Ignore one-off misspellings, neighbouring-key slips ("teh", "tge"), doubled or dropped letters, and
          autocorrect artefacts (a wrong word sitting where a similar one was meant). Never describe these as
          habits, never say they make or leave typos, and never put one among the quoted phrases.
        - A spelling that departs from the dictionary counts only if it recurs: then name the convention exactly
          ("writes 'u' for 'you'", "no apostrophes: dont, cant, im"). Where none recurs, the profile must say
          so in these words: "writes correctly spelled words".
        - When unsure whether something is a habit or a slip, treat it as a slip and leave it out.

        Describe style only. Leave out private facts: names other than common greetings, addresses, numbers,
        passwords, health, money, or anything about other people. Plain text with short headings, under 700 words.
    """.trimIndent()

    /**
     * What counts as a convention to keep, in all three writing instructions: the same things the training
     * instructions call style and [SampleCleaner] leaves alone, spelling habits among them. A list without those
     * reads "correctly spelled" as an order to undo them (to put the accents back, to turn "sooo" into "so").
     */
    private const val CONVENTIONS = "(casing, punctuation and apostrophe habits, missing accents, abbreviations, slang, " +
        "stretched words, any spelling the profile names as theirs or that recurs across their messages, emoji use)"

    /**
     * Ghostwriter's instruction, one of its list: the answer keeps the person's spelling habits and none of their
     * slips, whatever the past messages hold. [SPELLING_RULE], which always follows, says which is which.
     */
    const val WRITING = "Write correctly spelled text in their voice: reproduce their deliberate conventions " + CONVENTIONS +
        ", but never introduce or copy an accidental misspelling, even if one of their past messages contains one. " +
        "SPELLING, below, says how to tell a convention from a slip."

    /** The same for "In my voice", where the text being rewritten may hold a slip too. */
    const val REWRITING = "Write correctly spelled text in their voice: reproduce their deliberate conventions " + CONVENTIONS +
        ", but never introduce or copy an accidental misspelling, even if a sample or the text itself contains one. " +
        "SPELLING, below, says how to tell a convention from a slip."

    /**
     * What follows the style profile wherever it is sent, whatever the profile says. It gives the AI that writes
     * the test the AI that trains has: a spelling the profile names as a convention, or one that recurs, is the
     * person's and is kept; a one-off is a slip. And it says which wins: a profile trained before
     * [PROFILE_VERSION] 2 may call typos a habit, and a sample may still hold a slip, but neither is copied.
     */
    const val SPELLING_RULE = "SPELLING\n" +
        "Write correctly spelled text in their voice. Reproduce their deliberate conventions " + CONVENTIONS + ", but " +
        "never introduce or copy an accidental misspelling, even if a sample contains one or the profile describes " +
        "typos as a habit: a slip is not their style.\n" +
        "Telling them apart: a spelling is deliberate when the profile names it as a convention they keep to (\"u\" for " +
        "\"you\", no accents, \"sooo\"), or when it recurs across their messages; so is slang, a texting abbreviation or " +
        "a stretched word, which nobody types by accident. Keeping to these is their style, not a misspelling: " +
        "\"correctly spelled\" never means undoing them. A slip is an accident of the thumbs seen once (letters " +
        "swapped, a neighbouring key, a letter doubled or dropped: \"teh\", \"tge\"), or anything the profile itself " +
        "calls a typo or a mistake. When unsure, spell the word the ordinary way."

    /**
     * The [profile] as a system-prompt section, or a note that there isn't one yet (null); then, always,
     * [SPELLING_RULE], so neither an old profile nor a slip in a sample is ever copied, and a spelling habit is
     * never "corrected" away.
     */
    fun voiceSection(profile: String?): String =
        "THEIR STYLE PROFILE\n" + (profile ?: "(Not trained yet: copy the voice of the past messages below.)") + "\n\n" + SPELLING_RULE
}
