package com.infamousvague.devkeyboard

/**
 * Korean's syllable blocks, built from the two-set keyboard's letters (jamo) as they are typed: ㅎ ㅏ ㄴ becomes 한.
 * Pure functions over a small [State]; the service keeps the state and swaps the block before the cursor for the
 * new one on each key (DevKeyboardService.typeHangul).
 *
 * A block is an initial consonant, a vowel and an optional final consonant. The rules are the standard two-set ones:
 * a consonant after a vowel becomes the block's final, two finals join where Korean has that pair (ㄹ + ㄱ = ㄺ), two
 * vowels join where it has that one (ㅗ + ㅏ = ㅘ), and a vowel typed after a final takes that final away to start the
 * next block (갑 + ㅅ + ㅣ is 갑시, never 값ㅣ).
 */
object Hangul {
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    /** The finals, by their number in a block (0 is none), as the letters that show them alone. */
    private const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    /** Two vowels that make one: ㅗ + ㅏ = ㅘ. */
    private val VOWEL_PAIRS = mapOf("ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ')

    /** Two finals that make one: ㄱ + ㅅ = ㄳ. */
    private val FINAL_PAIRS = mapOf(
        "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ',
        "ㅂㅅ" to 'ㅄ',
    )
    private val VOWEL_HALVES = VOWEL_PAIRS.entries.associate { it.value to it.key }
    private val FINAL_HALVES = FINAL_PAIRS.entries.associate { it.value to it.key }

    /** Whether [c] is a letter this composer builds with: one of the two-set keyboard's consonants or vowels. */
    fun isJamo(c: Char): Boolean = c in CHO || c in JUNG

    /**
     * The block being built: its initial consonant, its vowel and its final, each as the letter that shows it alone,
     * or null where there is none yet.
     */
    data class State(val cho: Char? = null, val jung: Char? = null, val jong: Char? = null) {
        val empty: Boolean get() = cho == null && jung == null

        /** The block as text: a whole syllable once it has a consonant and a vowel, a lone letter before that. */
        fun render(): String = when {
            cho != null && jung != null -> (0xAC00 + (CHO.indexOf(cho) * 21 + JUNG.indexOf(jung)) * 28 + (jong?.let { JONG.indexOf(it) } ?: 0)).toChar().toString()
            cho != null -> cho.toString()
            jung != null -> jung.toString()
            else -> ""
        }
    }

    /** What a key did: the text that is now finished ([committed], often empty) and the block still being built. */
    data class Step(val committed: String, val state: State)

    /** [jamo] typed after [state]. */
    fun add(state: State, jamo: Char): Step {
        val done = state.render()
        return if (jamo in JUNG) vowel(state, jamo, done) else consonant(state, jamo, done)
    }

    private fun consonant(s: State, c: Char, done: String): Step = when {
        s.empty -> Step("", State(cho = c))
        // A consonant and a vowel, no final yet: this is the final, unless it is one a block cannot end with (ㄸ ㅃ ㅉ).
        s.cho != null && s.jung != null && s.jong == null -> if (c in JONG) Step("", s.copy(jong = c)) else Step(done, State(cho = c))
        // A final already: the two join where Korean has that pair; if not, this starts the next block.
        s.jong != null -> FINAL_PAIRS["${s.jong}$c"]?.let { Step("", s.copy(jong = it)) } ?: Step(done, State(cho = c))
        // A lone consonant or a lone vowel stands as it is.
        else -> Step(done, State(cho = c))
    }

    private fun vowel(s: State, v: Char, done: String): Step = when {
        s.empty -> Step("", State(jung = v))
        s.cho != null && s.jung == null -> Step("", s.copy(jung = v))
        // After a final, the final (or the second half of a double one) leaves to start the next block with this vowel.
        s.jong != null -> {
            val halves = FINAL_HALVES[s.jong]
            if (halves != null) Step(s.copy(jong = halves[0]).render(), State(cho = halves[1], jung = v))
            else Step(s.copy(jong = null).render(), State(cho = s.jong, jung = v))
        }
        // After a vowel: the two join where Korean has that vowel; if not, the block is done and this stands alone.
        else -> VOWEL_PAIRS["${s.jung}$v"]?.let { Step("", s.copy(jung = it)) } ?: Step(done, State(jung = v))
    }

    /**
     * Backspace on the block being built: one letter less, a double final or a joined vowel losing its second half
     * first. Null when nothing is being built, and backspace is the ordinary one.
     */
    fun backspace(s: State): State? = when {
        s.empty -> null
        s.jong != null -> s.copy(jong = FINAL_HALVES[s.jong]?.get(0))
        s.jung != null -> VOWEL_HALVES[s.jung]?.let { s.copy(jung = it[0]) } ?: s.copy(jung = null)
        else -> State()
    }
}
