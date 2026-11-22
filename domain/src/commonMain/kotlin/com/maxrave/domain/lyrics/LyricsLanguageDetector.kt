package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Lyrics

private val RICH_TAG_REGEX = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")

/**
 * Lightweight offline language detector for song lyrics.
 *
 * Used to determine whether lyrics are already written in the user's app / target language
 * (e.g., Portuguese lyrics for a pt-BR user) so the translate button can stay hidden and
 * auto-translation can be skipped immediately without network round-trips.
 */
object LyricsLanguageDetector {
    /**
     * Normalizes a BCP-47 / locale tag such as `"pt-BR"`, `"pt_BR"`, or `"en-US"` into its
     * two-letter base language code (`"pt"`, `"en"`, etc.).
     */
    fun normalizeBaseLanguage(language: String?): String {
        val cleaned = language?.trim().orEmpty().replace('_', '-')
        if (cleaned.isEmpty()) return ""
        return cleaned.substringBefore('-').lowercase()
    }

    /**
     * Returns `true` when [lyrics] is confidently detected to already be written in
     * [targetLanguage] (comparing normalized two-letter base codes).
     */
    fun isSameLanguage(
        lyrics: Lyrics?,
        targetLanguage: String?,
    ): Boolean {
        val targetBase = normalizeBaseLanguage(targetLanguage)
        if (targetBase.isEmpty()) return false
        val detected = detectBaseLanguage(lyrics) ?: return false
        return detected == targetBase
    }

    /**
     * Detects the dominant two-letter ISO-639-1 language code of [lyrics], or returns `null`
     * when there is insufficient or ambiguous evidence.
     */
    fun detectBaseLanguage(lyrics: Lyrics?): String? {
        val lines = lyrics?.lines ?: return null
        if (lines.isEmpty()) return null

        val plainText =
            buildString {
                for (line in lines) {
                    val cleaned = RICH_TAG_REGEX.replace(line.words, " ").trim()
                    if (cleaned.isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append(cleaned)
                    }
                }
            }
        if (plainText.isBlank()) return null

        detectNonLatinScript(plainText)?.let { return it }
        return detectLatinLanguage(plainText)
    }

    private fun detectNonLatinScript(text: String): String? {
        var latin = 0
        var kana = 0
        var hangul = 0
        var han = 0
        var cyrillic = 0
        var arabic = 0
        var devanagari = 0
        var thai = 0
        var greek = 0
        var hebrew = 0

        for (ch in text) {
            val code = ch.code
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || code in 0x00C0..0x024F -> latin++
                code in 0x3040..0x30FF -> kana++
                code in 0xAC00..0xD7AF || code in 0x1100..0x11FF || code in 0x3130..0x318F -> hangul++
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF -> han++
                code in 0x0400..0x04FF -> cyrillic++
                code in 0x0600..0x06FF -> arabic++
                code in 0x0900..0x097F -> devanagari++
                code in 0x0E00..0x0E7F -> thai++
                code in 0x0370..0x03FF -> greek++
                code in 0x0590..0x05FF -> hebrew++
            }
        }

        val totalLetters = latin + kana + hangul + han + cyrillic + arabic + devanagari + thai + greek + hebrew
        if (totalLetters < 12) return null

        val nonLatin = totalLetters - latin
        if (nonLatin < 8 || nonLatin.toFloat() / totalLetters.toFloat() < 0.30f) {
            return null
        }

        val jaCount = kana + if (kana >= 4) han else 0
        val buckets =
            listOf(
                "ja" to jaCount,
                "ko" to hangul,
                "zh" to if (kana < 4 && hangul < 4) han else 0,
                "ru" to cyrillic,
                "ar" to arabic,
                "hi" to devanagari,
                "th" to thai,
                "el" to greek,
                "he" to hebrew,
            )
        val best = buckets.maxByOrNull { it.second } ?: return null
        return if (best.second >= 8) best.first else null
    }

    private fun tokenizeWords(text: String): List<String> {
        val tokens = ArrayList<String>()
        val buf = StringBuilder()
        val lower = text.lowercase()
        for (i in lower.indices) {
            val ch = lower[i]
            val isApostropheInside =
                (ch == '\'' || ch == '’') &&
                    buf.isNotEmpty() &&
                    i + 1 < lower.length &&
                    lower[i + 1].isLetter()
            if (ch.isLetter()) {
                buf.append(ch)
            } else if (isApostropheInside) {
                buf.append('\'')
            } else if (buf.isNotEmpty()) {
                tokens.add(buf.toString())
                buf.setLength(0)
            }
        }
        if (buf.isNotEmpty()) {
            tokens.add(buf.toString())
        }
        return tokens
    }

    private fun detectLatinLanguage(text: String): String? {
        val tokens = tokenizeWords(text)
        if (tokens.size < 8) return null

        val scores = LinkedHashMap<String, Double>(PROFILES.size)
        for (profile in PROFILES) {
            scores[profile.code] = 0.0
        }

        for (token in tokens) {
            for (profile in PROFILES) {
                when {
                    token in profile.strongWords -> scores[profile.code] = (scores[profile.code] ?: 0.0) + 2.2
                    token in profile.commonWords -> scores[profile.code] = (scores[profile.code] ?: 0.0) + 1.0
                }
            }

            // Portuguese-distinctive diacritics and suffixes
            if ('ã' in token || 'õ' in token) {
                scores["pt"] = (scores["pt"] ?: 0.0) + 2.8
            }
            if ('ç' in token) {
                scores["pt"] = (scores["pt"] ?: 0.0) + 0.8
                scores["fr"] = (scores["fr"] ?: 0.0) + 0.6
                scores["tr"] = (scores["tr"] ?: 0.0) + 0.6
            }
            if (token.endsWith("ção") || token.endsWith("ções") || token.endsWith("mente")) {
                scores["pt"] = (scores["pt"] ?: 0.0) + 1.4
            }

            // Spanish-distinctive markers
            if ('ñ' in token) {
                scores["es"] = (scores["es"] ?: 0.0) + 3.0
            }

            // German-distinctive markers
            if ('ß' in token || 'ä' in token || 'ö' in token || 'ü' in token) {
                scores["de"] = (scores["de"] ?: 0.0) + 1.2
            }

            // Turkish-distinctive markers
            if ('ğ' in token || 'ş' in token || 'ı' in token) {
                scores["tr"] = (scores["tr"] ?: 0.0) + 2.5
            }

            // Polish-distinctive markers
            if ('ł' in token || 'ą' in token || 'ę' in token || 'ż' in token || 'ź' in token || 'ń' in token) {
                scores["pl"] = (scores["pl"] ?: 0.0) + 2.5
            }
        }

        // Spanish inverted punctuation bonus
        if ('¿' in text || '¡' in text) {
            scores["es"] = (scores["es"] ?: 0.0) + 3.5
        }

        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.value ?: 0.0

        val minScore = (tokens.size * 0.18).coerceAtLeast(6.0)
        if (top.value < minScore) return null
        if (runnerUp > 0.0 && top.value < runnerUp * 1.35) return null
        if (top.value - runnerUp < 3.5) return null

        return top.key
    }

    private data class LanguageProfile(
        val code: String,
        val strongWords: Set<String>,
        val commonWords: Set<String>,
    )

    private val PROFILES: List<LanguageProfile> =
        listOf(
            LanguageProfile(
                code = "pt",
                strongWords =
                    setOf(
                        "não", "nao", "você", "voce", "cê", "ce", "pra", "pro", "pros", "pras",
                        "tá", "tô", "tava", "tamo", "né", "aí", "nóis", "nois", "nós", "nos",
                        "minha", "meu", "meus", "minhas", "eu", "eles", "elas", "ela", "ele",
                        "isso", "tudo", "aqui", "hoje", "então", "entao", "muito", "muita",
                        "quando", "porque", "porquê", "onde", "quem", "ainda", "agora",
                        "sempre", "nunca", "ninguém", "ninguem", "já", "só", "é", "são",
                        "estão", "está", "estou", "vou", "vai", "vão", "fazer", "quero",
                        "posso", "pode", "tenho", "tem", "têm", "foi", "era", "fui", "ser",
                        "ter", "mim", "comigo", "contigo", "pelo", "pela", "pelos", "pelas",
                        "num", "numa", "dos", "das", "aos", "coração", "coracao", "saudade",
                        "moleque", "cadê", "cade", "fala", "olhar", "noite", "gente",
                    ),
                commonWords =
                    setOf(
                        "que", "de", "do", "da", "em", "no", "na", "um", "uma", "os", "as",
                        "com", "sem", "nem", "mas", "mais", "como", "para", "por", "se", "te",
                        "me", "seu", "sua", "seus", "suas", "teu", "tua", "nada", "bem",
                        "dia", "vida", "amor", "tempo", "mundo", "assim", "ate", "até", "ao",
                    ),
            ),
            LanguageProfile(
                code = "en",
                strongWords =
                    setOf(
                        "the", "and", "you", "that", "this", "with", "for", "are", "was", "were",
                        "have", "has", "had", "not", "but", "what", "when", "your", "can", "will",
                        "just", "don't", "i'm", "you're", "it's", "can't", "won't", "we're",
                        "they're", "ain't", "didn't", "doesn't", "isn't", "wasn't", "gonna",
                        "wanna", "gotta", "my", "she", "they", "them", "their", "from", "into",
                        "about", "know", "like", "never", "always", "been", "where", "there",
                        "here", "would", "could", "should", "because", "through", "before",
                        "after", "every", "everything", "nothing", "something", "someone",
                        "heart", "night", "love", "feel", "want", "need", "think", "baby",
                    ),
                commonWords =
                    setOf(
                        "i", "me", "we", "he", "it", "to", "of", "in", "on", "at", "by", "up",
                        "out", "all", "one", "get", "got", "go", "going", "see", "say", "said",
                        "now", "time", "way", "back", "down", "still", "only", "make", "take",
                    ),
            ),
            LanguageProfile(
                code = "es",
                strongWords =
                    setOf(
                        "el", "los", "las", "del", "pero", "yo", "tú", "mis", "muy", "más",
                        "mas", "ya", "esta", "este", "esto", "ese", "esa", "eso", "soy", "eres",
                        "son", "estoy", "estás", "estas", "están", "estan", "voy", "vas",
                        "vamos", "van", "tengo", "tienes", "tiene", "quiero", "quieres",
                        "quiere", "puedo", "puedes", "puede", "hay", "fue", "aquí", "aqui",
                        "ahora", "siempre", "nadie", "también", "tambien", "así", "asi",
                        "corazón", "corazon", "noche", "quién", "quien", "dónde", "donde",
                        "cuándo", "cuando", "cómo", "qué", "sin", "con", "hacer", "saber",
                        "mi", "tus", "sus", "ella", "ellos", "ellas", "nosotros", "contigo",
                        "conmigo", "todo", "toda", "todos", "todas", "algo", "nunca",
                    ),
                commonWords =
                    setOf(
                        "la", "un", "una", "de", "en", "al", "por", "para", "que", "se", "te",
                        "me", "lo", "le", "les", "no", "si", "sí", "ni", "es", "era", "ser",
                        "ver", "bien", "vida", "amor", "nada", "solo", "sólo", "como", "porque",
                    ),
            ),
            LanguageProfile(
                code = "fr",
                strongWords =
                    setOf(
                        "je", "tu", "il", "elle", "nous", "vous", "ils", "elles", "les", "des",
                        "une", "dans", "pour", "avec", "sans", "mais", "plus", "tout", "tous",
                        "c'est", "j'ai", "n'est", "qu'on", "d'un", "l'amour", "suis", "sont",
                        "être", "etre", "avoir", "faire", "comme", "quand", "rien", "jamais",
                        "toujours", "cœur", "coeur", "nuit", "vie", "mon", "ton", "son", "mes",
                        "tes", "ses", "notre", "votre", "leur", "pas", "qui", "quoi", "où",
                    ),
                commonWords =
                    setOf(
                        "le", "la", "un", "de", "du", "au", "aux", "et", "ou", "en", "ne", "se",
                        "me", "te", "ce", "ces", "sur", "sous", "par", "si", "moi", "toi", "lui",
                    ),
            ),
            LanguageProfile(
                code = "de",
                strongWords =
                    setOf(
                        "ich", "du", "wir", "ihr", "sie", "der", "die", "das", "den", "dem",
                        "ein", "eine", "einen", "einem", "nicht", "und", "aber", "oder", "mit",
                        "ohne", "für", "von", "auf", "aus", "bei", "nach", "über", "unter",
                        "bin", "bist", "ist", "sind", "war", "haben", "hast", "mein", "meine",
                        "dein", "deine", "immer", "nie", "nichts", "alles", "wenn", "weil",
                        "wie", "was", "wer", "wo", "doch", "schon", "noch", "nur", "herz",
                    ),
                commonWords =
                    setOf(
                        "es", "er", "zu", "im", "in", "am", "an", "so", "da", "ja", "nein",
                        "mich", "dich", "sich", "uns", "euch", "hier", "welt", "nacht", "zeit",
                    ),
            ),
            LanguageProfile(
                code = "it",
                strongWords =
                    setOf(
                        "io", "noi", "voi", "loro", "gli", "delle", "degli", "della", "dello",
                        "nella", "nello", "nelle", "negli", "che", "chi", "cosa", "dove",
                        "quando", "perché", "perche", "però", "pero", "senza", "sempre", "mai",
                        "niente", "nulla", "tutto", "tutti", "sono", "sei", "siamo", "siete",
                        "voglio", "posso", "cuore", "notte", "vita", "amore", "mio", "mia",
                        "tuo", "tua", "suo", "sua", "questo", "questa", "quello", "quella",
                        "non", "più", "piu", "ancora", "adesso", "ora", "così", "cosi",
                    ),
                commonWords =
                    setOf(
                        "il", "lo", "la", "le", "un", "una", "di", "da", "in", "con", "su",
                        "per", "tra", "fra", "ma", "se", "ti", "mi", "ci", "vi", "si", "è",
                    ),
            ),
            LanguageProfile(
                code = "id",
                strongWords =
                    setOf(
                        "aku", "kamu", "kau", "kita", "kami", "mereka", "yang", "dan", "tidak",
                        "tak", "bukan", "jangan", "dengan", "untuk", "dari", "dalam", "pada",
                        "akan", "sudah", "telah", "bisa", "ingin", "mau", "hati", "cinta",
                        "rindu", "selalu", "pernah", "semua", "hanya", "cuma", "karena",
                        "karna", "kalau", "jika", "bila", "tapi", "tetapi", "namun", "masih",
                    ),
                commonWords =
                    setOf(
                        "di", "ke", "ini", "itu", "dia", "ku", "mu", "nya", "ada", "jadi",
                        "lagi", "saja", "pun", "atau", "saat", "waktu", "hari", "malam",
                    ),
            ),
            LanguageProfile(
                code = "tr",
                strongWords =
                    setOf(
                        "ben", "sen", "biz", "siz", "onlar", "beni", "seni", "bana", "sana",
                        "benim", "senin", "bir", "ve", "ama", "fakat", "için", "icin", "gibi",
                        "kadar", "daha", "çok", "cok", "her", "hiç", "hic", "yok", "var",
                        "değil", "degil", "şimdi", "simdi", "artık", "artik", "hep", "neden",
                        "niye", "nasıl", "nasil", "kalbim", "aşk", "ask", "gece", "sevgi",
                    ),
                commonWords =
                    setOf(
                        "bu", "şu", "su", "o", "da", "de", "ki", "mi", "mı", "mu", "mü",
                        "ne", "ya", "ile", "en", "hem", "ise", "bile", "sonra", "önce",
                    ),
            ),
            LanguageProfile(
                code = "pl",
                strongWords =
                    setOf(
                        "nie", "się", "sie", "jest", "jesteś", "jestem", "będzie", "bedzie",
                        "mnie", "tobie", "ciebie", "moje", "twoje", "wszystko", "zawsze",
                        "nigdy", "kiedy", "gdzie", "dlaczego", "tylko", "jeszcze", "teraz",
                        "tutaj", "serce", "miłość", "milosc", "życie", "zycie", "przez",
                        "przed", "bardzo", "wiem", "chcę", "chce", "mogę", "moge", "już",
                    ),
                commonWords =
                    setOf(
                        "ja", "ty", "on", "ona", "my", "wy", "oni", "to", "ten", "ta", "te",
                        "na", "do", "od", "po", "za", "ze", "jak", "tak", "ale", "czy", "lub",
                    ),
            ),
            LanguageProfile(
                code = "nl",
                strongWords =
                    setOf(
                        "ik", "jij", "wij", "jullie", "zij", "het", "een", "niet", "geen",
                        "maar", "want", "omdat", "met", "zonder", "voor", "naar", "van", "uit",
                        "bij", "over", "onder", "ben", "bent", "zijn", "was", "waren", "heb",
                        "hebt", "heeft", "mijn", "jouw", "altijd", "nooit", "niets", "alles",
                        "wanneer", "waarom", "waar", "wie", "wat", "nog", "meer", "liefde",
                    ),
                commonWords =
                    setOf(
                        "de", "en", "of", "in", "op", "te", "je", "we", "ze", "hij", "mij",
                        "jou", "ons", "dit", "dat", "die", "dan", "als", "ook", "wel", "hier",
                    ),
            ),
        )
}
