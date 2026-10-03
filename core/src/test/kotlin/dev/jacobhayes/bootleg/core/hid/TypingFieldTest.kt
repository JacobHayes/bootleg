package dev.jacobhayes.bootleg.core.hid

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.triple
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TypingFieldTest {
    private val editMax = 64
    private val keptMax = 120

    /** The computer's text field: Backspace drops the last character, typing appends. */
    private class Host {
        var text = ""

        fun apply(edit: TextEdit) {
            text = text.dropLast(edit.deletedCount) + edit.inserted
        }
    }

    private fun accepted(
        before: TypingFieldState,
        after: TypingFieldState,
        keptCharsMax: Int = keptMax,
    ): TypingFieldUpdate.Accepted {
        val update = TypingField.update(before, after, editMax, keptCharsMax)
        assertTrue(update is TypingFieldUpdate.Accepted, "expected Accepted, got $update")
        return update as TypingFieldUpdate.Accepted
    }

    @Test
    fun `text edits become backspaces plus typed suffix`() {
        assertEquals(TextEdit(0, "abc"), TextEdit.between("", "abc"))
        assertEquals(TextEdit(1, ""), TextEdit.between("abc", "ab"))
        assertEquals(TextEdit(2, "Xbc"), TextEdit.between("abc", "aXbc"))
        assertEquals(TextEdit(1, "lo"), TextEdit.between("helo", "hello"))
        assertEquals(TextEdit(2, "😁"), TextEdit.between("😀", "😁"))
        assertEquals(listOf("😁"), accepted(TypingFieldState("a😀"), TypingFieldState("a😁")).unsupported)
    }

    @Test
    fun `appending types only the new characters`() {
        val update = accepted(TypingFieldState("he"), TypingFieldState("hello"))
        assertEquals(TextEdit(0, "llo"), update.edit)
        assertEquals(TypingFieldState("hello"), update.field)
    }

    @Test
    fun `deleting at the end is Backspace`() {
        assertEquals(TextEdit(2, ""), accepted(TypingFieldState("hello"), TypingFieldState("hel")).edit)
    }

    @Test
    fun `an edit in the middle deletes back to it and retypes the rest`() {
        val before = TypingFieldState("helo world")
        val after = TypingFieldState("hello world", selectionStart = 4)
        val update = accepted(before, after)
        assertEquals(TextEdit(7, "lo world"), update.edit)
        assertEquals(4, update.field.selectionStart)
    }

    @Test
    fun `a selection or composition change alone types nothing`() {
        val update = accepted(TypingFieldState("hello"), TypingFieldState("hello", 0, 5, compositionStart = 0, compositionEnd = 5))
        assertTrue(update.edit.isEmpty)
        assertEquals(0, update.field.compositionStart)
    }

    @Test
    fun `composing a word keeps the IME's composing region and never types it twice`() {
        val host = Host()
        var field = TypingFieldState.EMPTY
        // The IME composes "hi", then commits it with a space, as Gboard does for swipe and suggestions.
        val imeStates =
            listOf(
                TypingFieldState("h", compositionStart = 0, compositionEnd = 1),
                TypingFieldState("hi", compositionStart = 0, compositionEnd = 2),
                TypingFieldState("hi"),
                TypingFieldState("hi "),
            )
        for (next in imeStates) {
            val update = accepted(field, next)
            assertEquals(next, update.field, "the IME's state is shown as given")
            host.apply(update.edit)
            field = update.field
        }
        assertEquals("hi ", host.text)
    }

    @Test
    fun `replacing a composing word types the difference`() {
        val host = Host().apply { text = "teh" }
        val before = TypingFieldState("teh", compositionStart = 0, compositionEnd = 3)
        host.apply(accepted(before, TypingFieldState("the ")).edit)
        assertEquals("the ", host.text)
    }

    @Test
    fun `an edit typing more than the limit is refused`() {
        val pasted = "x".repeat(editMax + 1)
        assertEquals(TypingFieldUpdate.TooLarge, TypingField.update(TypingFieldState("a"), TypingFieldState("a$pasted"), editMax, keptMax))
        assertTrue(
            TypingField.update(
                TypingFieldState("a"),
                TypingFieldState("a" + "x".repeat(editMax)),
                editMax,
                keptMax,
            ) is TypingFieldUpdate.Accepted,
        )
    }

    @Test
    fun `the oldest characters scroll away, shifting the selection and composition`() {
        val after = TypingFieldState("abcdefgh", selectionStart = 8, compositionStart = 6, compositionEnd = 8)
        val update = accepted(TypingFieldState("abcdef"), after, keptCharsMax = 5)
        assertEquals(TypingFieldState("defgh", selectionStart = 5, compositionStart = 3, compositionEnd = 5), update.field)
        assertEquals(TextEdit(0, "gh"), update.edit)
    }

    @Test
    fun `trimming never cuts into the cursor or the composing word`() {
        val atStart = accepted(TypingFieldState(""), TypingFieldState("abcdefgh", selectionStart = 1), keptCharsMax = 3)
        assertEquals(TypingFieldState("bcdefgh", selectionStart = 0), atStart.field)
        val composing =
            accepted(TypingFieldState(""), TypingFieldState("abcdefgh", compositionStart = 2, compositionEnd = 8), keptCharsMax = 3)
        assertEquals(TypingFieldState("cdefgh", compositionStart = 0, compositionEnd = 6), composing.field)
    }

    @Test
    fun `trimming never splits a surrogate pair`() {
        val emoji = "😀"
        val update = accepted(TypingFieldState(""), TypingFieldState("a${emoji}bc"), keptCharsMax = 3)
        // Dropping 2 would leave a lone low surrogate; drop only "a".
        assertEquals("${emoji}bc", update.field.text)
    }

    @Test
    fun `invalid field states are rejected`() {
        assertThrows<IllegalArgumentException> { TypingFieldState("ab", selectionStart = 3) }
        assertThrows<IllegalArgumentException> { TypingFieldState("ab", compositionStart = 0) }
        assertThrows<IllegalArgumentException> { TypingFieldState("ab", compositionStart = 2, compositionEnd = 1) }
        assertThrows<IllegalArgumentException> { TextEdit(-1, "") }
    }

    @Test
    fun `characters the layout can't type are reported and never cost a Backspace`() {
        val typed = accepted(TypingFieldState("a"), TypingFieldState("a😀é"))
        assertEquals(TextEdit(0, ""), typed.edit)
        assertEquals(listOf("😀", "é"), typed.unsupported)
        // The emoji is two UTF-16 units, but the computer never got it: nothing to delete.
        assertEquals(TextEdit(0, ""), accepted(TypingFieldState("a😀"), TypingFieldState("a")).edit)
        assertEquals(TextEdit(1, ""), accepted(TypingFieldState("a😀b"), TypingFieldState("a😀")).edit)
    }

    @Test
    fun `a field whose history can't be trimmed is refused past its bound`() {
        val full = "x".repeat(keptMax + editMax)
        // The cursor at the start keeps every character; one more would outgrow the bound.
        val before = TypingFieldState(full, selectionStart = 0)
        assertEquals(
            TypingFieldUpdate.FieldFull,
            TypingField.update(before, TypingFieldState("${full}y", selectionStart = 0), editMax, keptMax),
        )
        assertTrue(
            TypingField.update(
                before,
                TypingFieldState(full.dropLast(1), selectionStart = 0),
                editMax,
                keptMax,
            ) is TypingFieldUpdate.Accepted,
        )
    }

    private val pieces = listOf("a", "b", " ", "é", "😀", "Z", "\n")
    private val textArb = Arb.list(Arb.element(pieces), 0..6).map { it.joinToString("") }

    @Test
    fun `property - the computer's text always ends with the field's typeable text`() =
        runBlocking<Unit> {
            val steps = Arb.list(Arb.triple(textArb, Arb.int(0..200), Arb.int(0..3)), 1..40)
            checkAll(300, steps, Arb.int(1..30)) { updates, keptCharsMax ->
                val host = Host()
                var field = TypingFieldState.EMPTY
                for ((inserted, position, shape) in updates) {
                    // Replace a range ending at the cursor-ish position with new text; sometimes the
                    // inserted text is a composing word, sometimes the cursor lands mid-text.
                    val end = codePointBoundary(field.text, position % (field.text.length + 1))
                    val start = codePointBoundary(field.text, (end - inserted.length).coerceAtLeast(0))
                    val text = field.text.substring(0, start) + inserted + field.text.substring(end)
                    val insertedEnd = start + inserted.length
                    val next =
                        when (shape) {
                            0 -> TypingFieldState(text)
                            1 -> TypingFieldState(text, selectionStart = insertedEnd)
                            2 -> TypingFieldState(text, insertedEnd, insertedEnd, compositionStart = start, compositionEnd = insertedEnd)
                            else -> TypingFieldState(text, selectionStart = 0)
                        }
                    when (val update = TypingField.update(field, next, editMax, keptCharsMax)) {
                        is TypingFieldUpdate.Accepted -> {
                            host.apply(update.edit)
                            field = update.field
                        }
                        TypingFieldUpdate.TooLarge, TypingFieldUpdate.FieldFull -> Unit
                    }
                    val typeable = TypingField.typeable(field.text)
                    assertTrue(host.text.endsWith(typeable), "host '${host.text}' must end with field '$typeable'")
                    assertTrue(field.text.length <= keptCharsMax + editMax, "field kept ${field.text.length} chars")
                    assertTrue(field.text.isEmpty() || !field.text[0].isLowSurrogate(), "field starts with half a pair")
                }
            }
        }

    private fun codePointBoundary(
        text: String,
        index: Int,
    ): Int = if (index in 1 until text.length && text[index].isLowSurrogate()) index - 1 else index
}
