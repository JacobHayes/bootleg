package dev.jacobhayes.bootleg.core.hid

/**
 * The on-screen typing field as the IME sees it. Indices are UTF-16. [compositionStart] and
 * [compositionEnd] mark the IME's composing region (a word still being composed), or are both null.
 */
data class TypingFieldState(
    val text: String,
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val compositionStart: Int? = null,
    val compositionEnd: Int? = null,
) {
    init {
        require(selectionStart in 0..text.length && selectionEnd in 0..text.length) {
            "selection $selectionStart..$selectionEnd outside text of length ${text.length}"
        }
        require((compositionStart == null) == (compositionEnd == null)) { "composition needs both ends or neither" }
        if (compositionStart != null && compositionEnd != null) {
            require(compositionStart in 0..compositionEnd && compositionEnd <= text.length) {
                "composition $compositionStart..$compositionEnd outside text of length ${text.length}"
            }
        }
    }

    companion object {
        val EMPTY = TypingFieldState("")
    }
}

/** A text change as a suffix replacement: delete [deletedCount] characters, then type [inserted]. */
data class TextEdit(
    val deletedCount: Int,
    val inserted: String,
) {
    init {
        require(deletedCount >= 0) { "negative delete count $deletedCount" }
    }

    val isEmpty: Boolean get() = deletedCount == 0 && inserted.isEmpty()

    companion object {
        /**
         * The computer's cursor is always at the end of what it was sent, so an edit anywhere else
         * becomes Backspace back to the first change and retyping from there.
         */
        fun between(
            before: String,
            after: String,
        ): TextEdit {
            var common = before.commonPrefixWith(after).length
            // 😀 and 😁 share a high surrogate: never split a pair between kept and replaced.
            if (common > 0 && before[common - 1].isHighSurrogate()) common--
            return TextEdit(before.length - common, after.substring(common))
        }
    }
}

sealed interface TypingFieldUpdate {
    /**
     * Show [field] and type [edit] (possibly empty: a selection or composition change). [unsupported]
     * lists the inserted characters a US layout can't type, one string per code point.
     */
    data class Accepted(
        val field: TypingFieldState,
        val edit: TextEdit,
        val unsupported: List<String>,
    ) : TypingFieldUpdate

    /** The edit inserts more than the limit at once (a paste into the field): keep the old field, type nothing. */
    data object TooLarge : TypingFieldUpdate

    /**
     * The cursor or composing word sits so far back that the old history can't be trimmed and the
     * field would outgrow its bound: keep the old field, type nothing.
     */
    data object FieldFull : TypingFieldUpdate
}

/**
 * Turns IME updates of the typing field into edits for the computer. Everything shown, including a
 * word still being composed, is sent at once (a later change to it becomes Backspace and retyping),
 * so Send never leaves a word behind. The computer only ever received the field's typeable
 * characters, so edits are computed on those: deleting an emoji the layout couldn't type sends no
 * Backspace. The IME's selection and composing region are kept as given:
 * dropping them makes the IME insert its composing word a second time.
 */
object TypingField {
    fun update(
        before: TypingFieldState,
        after: TypingFieldState,
        editCharsMax: Int,
        keptCharsMax: Int,
    ): TypingFieldUpdate {
        require(editCharsMax > 0 && keptCharsMax > 0) { "limits must be positive: $editCharsMax, $keptCharsMax" }
        // The typeable part of an edit is never longer than its raw insertion, so this bounds both.
        val rawInserted = TextEdit.between(before.text, after.text).inserted
        if (rawInserted.length > editCharsMax) return TypingFieldUpdate.TooLarge
        val field = trimStart(after, keptCharsMax)
        check(after.text.endsWith(field.text)) { "trimming changed the end of the text" }
        if (field.text.length > keptCharsMax + editCharsMax) return TypingFieldUpdate.FieldFull
        val edit = TextEdit.between(typeable(before.text), typeable(after.text))
        check(edit.inserted.length <= rawInserted.length) { "typeable insertion longer than the raw one" }
        return TypingFieldUpdate.Accepted(field, edit, unsupportedIn(rawInserted))
    }

    /** What the computer received of [text]: the characters a US layout can type (never a surrogate half). */
    fun typeable(text: String): String = text.filter { UsLayout.strokeFor(it) != null }

    private fun unsupportedIn(text: String): List<String> {
        val unsupported = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            if (charCount != 1 || UsLayout.strokeFor(text[index]) == null) unsupported += text.substring(index, index + charCount)
            index += charCount
        }
        return unsupported
    }

    /**
     * Drops the oldest characters beyond [keptCharsMax] (they're already on the computer), but never
     * the cursor's or the composing word's characters, so the IME's state stays valid.
     */
    private fun trimStart(
        field: TypingFieldState,
        keptCharsMax: Int,
    ): TypingFieldState {
        val excess = field.text.length - keptCharsMax
        if (excess <= 0) return field
        var drop = minOf(excess, field.selectionStart, field.selectionEnd, field.compositionStart ?: field.text.length)
        // Never split a surrogate pair.
        if (drop > 0 && drop < field.text.length && field.text[drop].isLowSurrogate()) drop--
        if (drop <= 0) return field
        return TypingFieldState(
            text = field.text.substring(drop),
            selectionStart = field.selectionStart - drop,
            selectionEnd = field.selectionEnd - drop,
            compositionStart = field.compositionStart?.minus(drop),
            compositionEnd = field.compositionEnd?.minus(drop),
        )
    }
}
