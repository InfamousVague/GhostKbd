package com.infamousvague.devkeyboard

/*
 * The one-time code in a text message (SmsCodes.kt has what is done with it, and docs/sms-codes.md the whole
 * account). This file is text in and a code or nothing out, with no Android in it, so a table of messages can hold
 * it to account (OtpCodesTest) and tool/otp_check.sh can run that table in a second.
 */

/** Finds the one-time code in a text message. Text in, a code or nothing out: no Android here, so a table of messages can hold it to account (OtpCodesTest). */
object OtpCodes {
    /** A message longer than this is not a code's. */
    const val MAX_LENGTH = 1000
    /** A code and the word that names it ("code", "OTP") are bound when they are this near in one sentence. */
    private const val NEAR = 48
    /** And a number further than this from any such word, though in its sentence, is not that word's. */
    private const val FAR = 200

    private fun words(vararg w: String) = w.joinToString("|") { Regex.escape(it) }

    /**
     * The words that name a code, in the keyboard's languages and the commonest others: "code", "OTP", "PIN",
     * "verification". The first group may begin a longer word (verif-ication, kod-u); the second must stand alone
     * (pin, not shipping); the third is scripts that write without spaces. A number in the same sentence as one of
     * these may be the code.
     */
    private val NAME = Regex(
        "(?<![\\p{L}\\p{N}])(?:" + words(
            "code", "passcode", "verif", "one-time", "one time", "onetime",
            "código", "codigo", "clave", "un solo uso", "uso único", "uso unico",
            "vérif", "usage unique",
            "bestätigung", "bestaetigung", "einmal", "freischalt",
            "codice", "monouso", "eenmalig",
            "kod", "kód", "doğrulama", "dogrulama",
        ) + ")" +
            "|(?<![\\p{L}\\p{N}])(?:pin|tan|otp|2fa|mã)(?![\\p{L}\\p{N}])" +
            "|" + words("код", "验证码", "驗證碼", "校验码", "校驗碼", "动态码", "動態碼", "認証", "確認コード", "コード", "인증", "رمز", "كود", "ओटीपी", "कोड", "รหัส"),
        RegexOption.IGNORE_CASE,
    )

    /**
     * Words a code's message often has and many other messages have too: "password", "log in", "security". One
     * of these names a number only from close by: just after it ("Use 482913 to log in") or straight before it
     * ("password: 482913"). "Log in to see your bill of 1250" is not a code.
     */
    private val HINT = Regex(
        "(?<![\\p{L}\\p{N}])(?:" + words(
            "password", "token", "authenti", "autenti", "login", "log in", "sign in", "sign-in", "security", "to confirm",
            "contraseña", "seguridad", "acceso", "mot de passe", "sécurité", "securite", "connexion",
            "sicherheit", "passwort", "kennwort", "anmeld", "sicurezza", "accesso",
            "senha", "segurança", "seguranca", "şifre", "sifre", "wachtwoord", "sandi",
        ) + ")" +
            "|" + words("пароль", "密码", "密碼", "暗証", "비밀번호"),
        RegexOption.IGNORE_CASE,
    )
    /** How near a [HINT] must be: this far after the number, or this far before it. */
    private const val HINT_AFTER = 24
    private const val HINT_BEFORE = 6

    /** Words that only a code's message has: with one of these an advertisement's words do not rule the message out. */
    private val STRONG = Regex(
        "(?<![\\p{L}])(?:otp|verif|vérif|one[- ]?time|passcode|authenti|autenti|2fa|log ?in|sign[- ]in|bestätigung|doğrulama|einmal)|验证码|驗證碼|認証|인증|ओटीपी",
        RegexOption.IGNORE_CASE,
    )

    /** An advertisement's words: "Use code SAVE20 for 20% off" has a code, and it is not one of these. */
    private val PROMO = Regex(
        "\\d\\s?%|%\\s*off|(?<![\\p{L}])(?:discount|coupon|promo|voucher|sale|deal|gutschein|rabatt|descuento|cup[oó]n|cupom|desconto|sconto|réduction|reduction|soldes|oferta|offerta|angebot)(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )

    /** A message written for one website to read (WebOTP): a last line of "@site.example #123456". */
    private val ORIGIN_BOUND = Regex("(?m)^@[\\p{L}\\p{N}.-]+\\s+#([A-Za-z0-9]{4,10})(?:\\s+%\\S+)?\\s*$")

    /** Addresses: a number inside one is part of the address ("t.me/login/48291" beside the code itself). */
    private val URL = Regex("(?:https?://|www\\.)\\S+|(?<![\\p{L}\\p{N}@])[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+/\\S*", RegexOption.IGNORE_CASE)

    /** "482 913" and "482-913": a six-figure code written in two halves. */
    private val HALVES = Regex("(\\d{3})[ -](\\d{3})")
    private val FIGURES = Regex("\\d{4,8}")

    /** A code that is letters and figures, where the message says outright that it is the code: "code is K7M2PQ", "Code: 4F8X2A". */
    private val SPELLED = Regex(
        "(?<![\\p{L}\\p{N}])(?iu:code|otp|passcode|password|pin|c[oó]digo|clave|codice|senha|kode?|passwort|kennwort)\\s*" +
            "(?:(?iu:is|est|es|ist|lautet|è|é)\\s*:?|[:=])\\s*([A-Z0-9]{5,8})(?![\\p{L}\\p{N}-])",
    )

    /**
     * What stands just before a number that is not a code: the end of a card or account, an order, a reference, a
     * postcode, a phone number's "call", an amount's currency, the stars of a hidden number.
     */
    private val NOT_BEFORE = Regex(
        "(?:(?<![\\p{L}])(?:ending(?: in| with)?|ends (?:in|with)|last (?:4|four|6|six) digits(?: are| is)?|order|ref(?:erence)?|invoice|ticket|tracking|booking|" +
            "account|acct|a/c|card|member(?:ship)?|customer|policy|case|claim|pincode|zip(?: code)?|postal code|postcode|call|tel|phone|fax|id)" +
            "(?:[ .]*(?:number|num|no|nr|id))?[ .:#]*(?:is[ :]*)?" +
            "|[$€£¥₹₩₽*•×]\\s?|(?<![\\p{L}])(?:rs|inr|usd|eur|gbp|aed|sar|mxn|brl|cad|aud|chf|r\\$)\\.?\\s?" +
            "|\\d{3,}[ -]|\\+\\d{1,3}[ -]|\\d{1,4}[/.-]|\\d[.,])$",
        RegexOption.IGNORE_CASE,
    )

    /** What stands just after one: the rest of an amount, a date or a phone number; a currency; a length of time; a count of something. */
    private val NOT_AFTER = Regex(
        "^(?:[.,]\\d|[/.-]\\d{1,4}(?!\\d)|[ -]\\d{3}|\\s?(?:[€$£¥₹%]|(?:eur|usd|gbp|inr|dollars?|euros?|pesos?|reais|rupees?|" +
            "secs?|seconds?|mins?|minutes?|hours?|hrs?|days?|weeks?|std|stunden?|minuten|sekunden|minutos?|segundos?|horas?|d[ií]as?|" +
            "minuti|secondi|ore|giorni|jours?|heures?|secondes?|am|pm|" +
            "points?|pts|coins|credits|miles|km|kg|kb|mb|gb|kwh)(?![\\p{L}])|分钟|分鐘|分|秒|小时|小時))",
        RegexOption.IGNORE_CASE,
    )

    private val SENTENCE_END = Regex("[.!?。！？](?:\\s|$)")

    private class Found(val code: String, val start: Int, val end: Int)

    /** A letter or figure of a script written with spaces: beside one, a run of figures is part of a longer name ("BA2490", "xx4821"). */
    private fun wordy(c: Char) = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c in 'À'..'ɏ' || c in 'Ͱ'..'ԯ'

    /** The one-time code in [message], as it is typed (figures only where it was written in halves), or null where the message has none. */
    fun find(message: String): String? {
        if (message.isBlank() || message.length > MAX_LENGTH) return null
        ORIGIN_BOUND.find(message)?.let { return it.groupValues[1] }
        val names = NAME.findAll(message).map { it.range }.toList()
        val hints = HINT.findAll(message).map { it.range }.toList()
        if (names.isEmpty() && hints.isEmpty()) return null
        if (PROMO.containsMatchIn(message) && !STRONG.containsMatchIn(message)) return null
        SPELLED.find(message)?.groupValues?.get(1)?.let { token ->
            if (token.any { it in '0'..'9' } && token.any { it in 'A'..'Z' }) return token
        }

        val addresses = URL.findAll(message).map { it.range }.toList()
        fun standsAlone(start: Int, end: Int): Boolean {
            if (start > 0 && wordy(message[start - 1])) return false
            if (end < message.length && wordy(message[end])) return false
            if (addresses.any { start >= it.first && end - 1 <= it.last }) return false
            if (NOT_BEFORE.containsMatchIn(message.substring(maxOf(0, start - 48), start))) return false
            return !NOT_AFTER.containsMatchIn(message.substring(end, minOf(message.length, end + 16)))
        }

        val found = ArrayList<Found>()
        for (m in HALVES.findAll(message)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (standsAlone(start, end)) found += Found(m.groupValues[1] + m.groupValues[2], start, end)
        }
        for (m in FIGURES.findAll(message)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (found.any { start < it.end && end > it.start }) continue
            if (standsAlone(start, end)) found += Found(m.value, start, end)
        }
        if (found.isEmpty()) return null

        /** How far [f] is from a word that names it in its own sentence (a [HINT] only from close by), or -1 where there is none. */
        fun gap(f: Found): Int {
            var best = -1
            for (name in names) {
                val between = when {
                    name.last < f.start -> message.substring(name.last + 1, f.start)
                    name.first >= f.end -> message.substring(f.end, name.first)
                    else -> continue
                }
                if (between.length > FAR || SENTENCE_END.containsMatchIn(between)) continue
                if (best < 0 || between.length < best) best = between.length
            }
            for (hint in hints) {
                val between = when {
                    hint.last < f.start -> message.substring(hint.last + 1, f.start).takeIf { it.length <= HINT_BEFORE }
                    hint.first >= f.end -> message.substring(f.end, hint.first).takeIf { it.length <= HINT_AFTER }
                    else -> null
                } ?: continue
                if (SENTENCE_END.containsMatchIn(between)) continue
                if (best < 0 || between.length < best) best = between.length
            }
            return best
        }
        fun yearLike(code: String) = code.length == 4 && code.toInt() in 1900..2099
        fun shape(code: String) = when (code.length) { 6 -> 0; 5, 7 -> 1; else -> 2 }

        // The code is a number in the same sentence as the word that names it: one bound to that word (near it)
        // before one that only shares its sentence, a six-figure one before another, the nearer before the further.
        // A number in a sentence of its own is not taken, and a year only where it is bound.
        val ranked = found.map { it to gap(it) }.filter { (f, gap) -> gap >= 0 && (gap <= NEAR || !yearLike(f.code)) }
        val order = compareBy<Pair<Found, Int>>({ if (it.second <= NEAR) 0 else 1 }, { shape(it.first.code) }, { it.second }, { it.first.start })
        return ranked.minWithOrNull(order)?.first?.code
    }
}
