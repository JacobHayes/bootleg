package dev.jacobhayes.bootleg.core.hid

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HidDescriptorsTest {
    private fun hex(text: String): ByteArray =
        text
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** HID 1.11 appendix B.1, verbatim. */
    private val bootKeyboardAppendixB1 =
        """
        05 01 09 06 A1 01
        05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02
        95 01 75 08 81 01
        95 05 75 01 05 08 19 01 29 05 91 02
        95 01 75 03 91 01
        95 06 75 08 15 00 25 65 05 07 19 00 29 65 81 00
        C0
        """

    /** HID 1.11 appendix B.2, verbatim. */
    private val bootMouseAppendixB2 =
        """
        05 01 09 02 A1 01 09 01 A1 00
        05 09 19 01 29 03 15 00 25 01 95 03 75 01 81 02
        95 01 75 05 81 01
        05 01 09 30 09 31 15 81 25 7F 75 08 95 02 81 06
        C0 C0
        """

    @Test
    fun `mouse descriptor is appendix B2 byte for byte`() {
        assertArrayEquals(hex(bootMouseAppendixB2), HidDescriptors.mouse())
        assertEquals(50, HidDescriptors.mouse().size)
    }

    @Test
    fun `keyboard descriptor is appendix B1 with the key array widened to 0xFF`() {
        // Logical Maximum 0x65 (25 65) → 0xFF (26 FF 00, two bytes because 1-byte items are signed);
        // Usage Maximum 0x65 (29 65) → 0xFF (29 FF). Everything else is verbatim.
        val expected =
            hex(
                bootKeyboardAppendixB1
                    .replace("25 65", "26 FF 00")
                    .replace("29 65", "29 FF"),
            )
        assertArrayEquals(expected, HidDescriptors.keyboard())
        assertEquals(64, HidDescriptors.keyboard().size)
    }

    @Test
    fun `keyboard key array reaches F24 and the right GUI usage`() {
        val descriptor = HidDescriptors.keyboard()
        assertEquals(true, KeyUsage.entries.all { it.id <= 0xFF })
        // Last item before End Collection is Input(Data,Array,Abs) of the key array.
        assertArrayEquals(hex("29 FF 81 00 C0"), descriptor.copyOfRange(descriptor.size - 5, descriptor.size))
    }

    @Test
    fun `descriptors are fresh copies`() {
        val first = HidDescriptors.keyboard()
        first[0] = 0
        assertEquals(0x05.toByte(), HidDescriptors.keyboard()[0])
    }
}
