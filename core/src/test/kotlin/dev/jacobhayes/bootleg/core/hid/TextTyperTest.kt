package dev.jacobhayes.bootleg.core.hid

import io.kotest.property.Arb
import io.kotest.property.arbitrary.char
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TextTyperTest {
    private val shift = Modifier.LEFT_SHIFT.bit

    @Test
    fun `US layout covers printable ASCII plus newline and tab, nothing else`() {
        assertEquals(((' '..'~') + '\n' + '\t').toSet(), UsLayout.characters)
    }

    @Test
    fun `US layout spot checks`() {
        assertEquals(KeyStroke(KeyUsage.A, shift = false), UsLayout.strokeFor('a'))
        assertEquals(KeyStroke(KeyUsage.A, shift = true), UsLayout.strokeFor('A'))
        assertEquals(KeyStroke(KeyUsage.DIGIT_2, shift = true), UsLayout.strokeFor('@'))
        assertEquals(KeyStroke(KeyUsage.DIGIT_0, shift = true), UsLayout.strokeFor(')'))
        assertEquals(KeyStroke(KeyUsage.GRAVE, shift = true), UsLayout.strokeFor('~'))
        assertEquals(KeyStroke(KeyUsage.QUOTE, shift = true), UsLayout.strokeFor('"'))
        assertEquals(KeyStroke(KeyUsage.ENTER, shift = false), UsLayout.strokeFor('\n'))
        assertEquals(null, UsLayout.strokeFor('é'))
    }

    @Test
    fun `each character is a press then a release-all`() {
        val plan = TextTyper.plan("Hi!")
        assertEquals(
            listOf(
                KeyboardReport(shift, listOf(KeyUsage.H)),
                KeyboardReport.RELEASE_ALL,
                KeyboardReport(0, listOf(KeyUsage.I)),
                KeyboardReport.RELEASE_ALL,
                KeyboardReport(shift, listOf(KeyUsage.DIGIT_1)),
                KeyboardReport.RELEASE_ALL,
            ),
            plan.reports,
        )
        assertEquals(emptyList<UnsupportedCharacter>(), plan.unsupported)
    }

    @Test
    fun `repeated characters are separated by a release`() {
        val plan = TextTyper.plan("aa")
        assertEquals(4, plan.reports.size)
        assertTrue(plan.reports[1].isReleaseAll)
    }

    @Test
    fun `unsupported characters are listed with their index, not silently dropped`() {
        val plan = TextTyper.plan("aéb😀c\u0007")
        assertEquals(
            listOf(UnsupportedCharacter(1, 0xE9), UnsupportedCharacter(3, 0x1F600), UnsupportedCharacter(6, 0x07)),
            plan.unsupported,
        )
        assertEquals("abc", TextTyper.typedText(plan.reports))
    }

    @Test
    fun `CRLF and lone CR both type a single Enter`() {
        assertEquals("a\nb\nc", TextTyper.typedText(TextTyper.plan("a\r\nb\rc").reports))
        assertEquals(emptyList<UnsupportedCharacter>(), TextTyper.plan("a\r\nb\rc").unsupported)
    }

    @Test
    fun `empty text is an empty plan`() {
        assertEquals(TypingPlan(emptyList(), emptyList()), TextTyper.plan(""))
    }

    @Test
    fun `input is bounded to 64 KiB of UTF-8`() {
        TextTyper.plan("a".repeat(HidLimits.PASTE_SIZE_BYTES_MAX))
        assertThrows<IllegalArgumentException> { TextTyper.plan("a".repeat(HidLimits.PASTE_SIZE_BYTES_MAX + 1)) }
        // Two UTF-8 bytes each: half as many characters fit.
        assertThrows<IllegalArgumentException> { TextTyper.plan("é".repeat(HidLimits.PASTE_SIZE_BYTES_MAX / 2 + 1)) }
        TextTyper.plan("é".repeat(HidLimits.PASTE_SIZE_BYTES_MAX / 2))
        // Four bytes per surrogate pair, three per other BMP character.
        TextTyper.plan("\uD83D\uDE00".repeat(HidLimits.PASTE_SIZE_BYTES_MAX / 4))
        assertThrows<IllegalArgumentException> { TextTyper.plan("\uD83D\uDE00".repeat(HidLimits.PASTE_SIZE_BYTES_MAX / 4 + 1)) }
        assertThrows<IllegalArgumentException> { TextTyper.plan("€".repeat(HidLimits.PASTE_SIZE_BYTES_MAX / 3 + 1)) }
    }

    @Test
    fun `a huge paste is rejected without encoding it first`() {
        // 64 MiB of UTF-16; encoding it would allocate far more than the limit before rejecting.
        val huge = "é".repeat(32 * 1024 * 1024)
        assertThrows<IllegalArgumentException> { TextTyper.plan(huge) }
    }

    @Test
    fun `round trip - typing then decoding yields the supported characters`() =
        runBlocking<Unit> {
            val supported = UsLayout.characters.toList()
            checkAll(Arb.list(Arb.element(supported), 0..200)) { chars ->
                val text = chars.joinToString("")
                val plan = TextTyper.plan(text)
                assertEquals(text, TextTyper.typedText(plan.reports))
                assertTrue(plan.unsupported.isEmpty())
                // Decoding the wire bytes gives the same reports.
                assertEquals(plan.reports, plan.reports.map { KeyboardReport.decode(it.encode()) })
            }
        }

    @Test
    fun `round trip - arbitrary text keeps supported characters and reports the rest`() =
        runBlocking<Unit> {
            checkAll(Arb.list(Arb.char(Char(0)..Char(0xFF)), 0..100)) { chars ->
                val text = chars.joinToString("")
                val plan = TextTyper.plan(text)
                val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                val expected = normalized.filter { it in UsLayout.characters }
                assertEquals(expected, TextTyper.typedText(plan.reports))
                assertEquals(text.count { it !in UsLayout.characters && it != '\r' }, plan.unsupported.size)
                assertTrue(plan.unsupported.all { text[it.index].code == it.codePoint })
            }
        }
}
