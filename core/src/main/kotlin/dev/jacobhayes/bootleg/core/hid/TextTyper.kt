package dev.jacobhayes.bootleg.core.hid

/** A key and whether Shift must be held to produce a character on a US ANSI layout. */
data class KeyStroke(
    val usage: KeyUsage,
    val shift: Boolean,
)

/** US ANSI layout: printable ASCII plus newline and tab. The host must be set to a US layout. */
object UsLayout {
    private val strokeByChar: Map<Char, KeyStroke> =
        buildMap {
            for (letter in 'a'..'z') {
                val usage = KeyUsage.valueOf(letter.uppercase())
                put(letter, KeyStroke(usage, shift = false))
                put(letter.uppercaseChar(), KeyStroke(usage, shift = true))
            }
            for (digit in '0'..'9') put(digit, KeyStroke(KeyUsage.valueOf("DIGIT_$digit"), shift = false))
            val shiftedDigits = ")!@#$%^&*("
            shiftedDigits.forEachIndexed { digit, symbol -> put(symbol, KeyStroke(KeyUsage.valueOf("DIGIT_$digit"), shift = true)) }
            val punctuation =
                listOf(
                    Triple(KeyUsage.SPACE, ' ', null),
                    Triple(KeyUsage.MINUS, '-', '_'),
                    Triple(KeyUsage.EQUAL, '=', '+'),
                    Triple(KeyUsage.BRACKET_LEFT, '[', '{'),
                    Triple(KeyUsage.BRACKET_RIGHT, ']', '}'),
                    Triple(KeyUsage.BACKSLASH, '\\', '|'),
                    Triple(KeyUsage.SEMICOLON, ';', ':'),
                    Triple(KeyUsage.QUOTE, '\'', '"'),
                    Triple(KeyUsage.GRAVE, '`', '~'),
                    Triple(KeyUsage.COMMA, ',', '<'),
                    Triple(KeyUsage.PERIOD, '.', '>'),
                    Triple(KeyUsage.SLASH, '/', '?'),
                    Triple(KeyUsage.ENTER, '\n', null),
                    Triple(KeyUsage.TAB, '\t', null),
                )
            for ((usage, plain, shifted) in punctuation) {
                put(plain, KeyStroke(usage, shift = false))
                if (shifted != null) put(shifted, KeyStroke(usage, shift = true))
            }
        }

    private val charByStroke: Map<KeyStroke, Char> = strokeByChar.entries.associate { (char, stroke) -> stroke to char }

    init {
        check(charByStroke.size == strokeByChar.size) { "US layout maps two characters to one stroke" }
        check((' '..'~').all { it in strokeByChar }) { "US layout must cover printable ASCII" }
    }

    fun strokeFor(char: Char): KeyStroke? = strokeByChar[char]

    fun charFor(stroke: KeyStroke): Char? = charByStroke[stroke]

    val characters: Set<Char> get() = strokeByChar.keys
}

data class UnsupportedCharacter(
    /** UTF-16 index into the original text. */
    val index: Int,
    val codePoint: Int,
)

/** Reports to send in order, each pair a press then a release-all. */
data class TypingPlan(
    val reports: List<KeyboardReport>,
    val unsupported: List<UnsupportedCharacter>,
)

/** Paste-as-typing: turns text into boot keyboard reports for a US layout host. */
object TextTyper {
    fun plan(text: String): TypingPlan {
        require(utf8SizeBytesWithin(text, HidLimits.PASTE_SIZE_BYTES_MAX)) {
            "paste is more than ${HidLimits.PASTE_SIZE_BYTES_MAX} bytes of UTF-8"
        }
        val reports = ArrayList<KeyboardReport>()
        val unsupported = ArrayList<UnsupportedCharacter>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val stroke = strokeFor(text, index, codePoint)
            when {
                // CRLF: the CR is dropped and the LF types Enter.
                codePoint == '\r'.code && text.getOrNull(index + 1) == '\n' -> Unit
                stroke == null -> unsupported += UnsupportedCharacter(index, codePoint)
                else -> {
                    val modifierBits = if (stroke.shift) Modifier.LEFT_SHIFT.bit else 0
                    reports += KeyboardReport(modifierBits, listOf(stroke.usage))
                    reports += KeyboardReport.RELEASE_ALL
                }
            }
            index += charCount
        }
        check(reports.size % 2 == 0)
        return TypingPlan(reports, unsupported)
    }

    /** Inverse of [plan] for testing and logging: the text a US-layout host would see. */
    fun typedText(reports: List<KeyboardReport>): String =
        buildString {
            for (report in reports) {
                if (report.isReleaseAll) continue
                check(report.keys.size == 1) { "typing reports press exactly one key: $report" }
                val shift = report.modifierBits == Modifier.LEFT_SHIFT.bit
                check(shift || report.modifierBits == 0) { "typing reports use only left shift: $report" }
                append(checkNotNull(UsLayout.charFor(KeyStroke(report.keys.single(), shift))))
            }
        }

    /** Counts UTF-8 bytes without copying [text], stopping as soon as [limitBytes] is exceeded. */
    private fun utf8SizeBytesWithin(
        text: String,
        limitBytes: Int,
    ): Boolean {
        // Every UTF-16 unit encodes to at least one UTF-8 byte.
        if (text.length > limitBytes) return false
        var sizeBytes = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            sizeBytes +=
                when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    // A valid surrogate pair is one 4-byte code point; a lone surrogate encodes as '?' (1 byte).
                    char.isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true -> 4.also { index++ }
                    char.isSurrogate() -> 1
                    else -> 3
                }
            if (sizeBytes > limitBytes) return false
            index++
        }
        return true
    }

    private fun strokeFor(
        text: String,
        index: Int,
        codePoint: Int,
    ): KeyStroke? =
        when {
            codePoint == '\r'.code -> UsLayout.strokeFor('\n')
            Character.isBmpCodePoint(codePoint) -> UsLayout.strokeFor(text[index])
            else -> null
        }
}
