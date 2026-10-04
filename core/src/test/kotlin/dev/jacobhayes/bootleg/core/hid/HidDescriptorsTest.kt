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
    fun `wheel mouse descriptor is appendix B2 with wheel and AC Pan appended after X and Y`() {
        // Bytes 0..2 keep B.2's layout; Wheel (GD 0x38) and AC Pan (Consumer 0x0238) follow as
        // relative int8 fields inside the same physical collection.
        val appended =
            """
            09 38 15 81 25 7F 75 08 95 01 81 06
            05 0C 0A 38 02 15 81 25 7F 75 08 95 01 81 06
            """
        val boot = hex(bootMouseAppendixB2)
        val expected = boot.copyOf(boot.size - 2) + hex(appended) + hex("C0 C0")
        assertArrayEquals(expected, HidDescriptors.mouse())
        assertEquals(50 + 27, HidDescriptors.mouse().size)
    }

    @Test
    fun `the descriptor matches the report length`() {
        // Input bits: buttons 3 + padding 5 + X/Y 16 + wheel 8 + pan 8.
        assertEquals(5, HidLimits.MOUSE_REPORT_SIZE_BYTES)
        assertEquals(HidLimits.MOUSE_REPORT_SIZE_BYTES, dev.jacobhayes.bootleg.core.gadget.HidFunctionSpec.MOUSE_REPORT_LENGTH_BYTES)
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
