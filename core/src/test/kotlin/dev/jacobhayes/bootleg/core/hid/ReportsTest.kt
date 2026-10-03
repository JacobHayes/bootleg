package dev.jacobhayes.bootleg.core.hid

import dev.jacobhayes.bootleg.core.gadget.MouseLayout
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class KeyboardReportTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun `release all is eight zero bytes`() {
        assertArrayEquals(ByteArray(8), KeyboardReport.RELEASE_ALL.encode())
        assertTrue(KeyboardReport.RELEASE_ALL.isReleaseAll)
    }

    @Test
    fun `ctrl alt delete encodes modifiers in byte 0 and the key in slot 0`() {
        val report = KeyboardReport(Modifier.LEFT_CONTROL.bit or Modifier.LEFT_ALT.bit, listOf(KeyUsage.DELETE))
        assertArrayEquals(bytes(0x05, 0, 0x4C, 0, 0, 0, 0, 0), report.encode())
    }

    @Test
    fun `six keys fill all slots in order`() {
        val keys = listOf(KeyUsage.A, KeyUsage.B, KeyUsage.C, KeyUsage.D, KeyUsage.E, KeyUsage.F)
        assertArrayEquals(bytes(0x80, 0, 4, 5, 6, 7, 8, 9), KeyboardReport(Modifier.RIGHT_GUI.bit, keys).encode())
    }

    @Test
    fun `pressing more than six keys becomes a rollover report`() {
        val keys = listOf(KeyUsage.A, KeyUsage.B, KeyUsage.C, KeyUsage.D, KeyUsage.E, KeyUsage.F, KeyUsage.G)
        val report = KeyboardReport.pressing(Modifier.LEFT_SHIFT.bit, keys)
        assertTrue(report.isRollover)
        assertArrayEquals(bytes(0x02, 0, 1, 1, 1, 1, 1, 1), report.encode())
    }

    @Test
    fun `pressing six or fewer keys is not rollover and deduplicates`() {
        val report = KeyboardReport.pressing(0, listOf(KeyUsage.A, KeyUsage.A, KeyUsage.B))
        assertFalse(report.isRollover)
        assertEquals(listOf(KeyUsage.A, KeyUsage.B), report.keys)
    }

    @Test
    fun `invalid reports are rejected`() {
        assertThrows<IllegalArgumentException> { KeyboardReport(0x100) }
        assertThrows<IllegalArgumentException> { KeyboardReport(-1) }
        assertThrows<IllegalArgumentException> { KeyboardReport(0, List(7) { KeyUsage.entries[it + 1] }) }
        assertThrows<IllegalArgumentException> { KeyboardReport(0, listOf(KeyUsage.LEFT_SHIFT)) }
        assertThrows<IllegalArgumentException> { KeyboardReport(0, listOf(KeyUsage.A, KeyUsage.A)) }
        assertThrows<IllegalArgumentException> { KeyboardReport(0, listOf(KeyUsage.ERROR_ROLL_OVER)) }
        assertThrows<IllegalArgumentException> { KeyboardReport(0, listOf(KeyUsage.ERROR_ROLL_OVER, KeyUsage.A)) }
        assertThrows<IllegalArgumentException> { KeyboardReport.pressing(0, listOf(KeyUsage.ERROR_ROLL_OVER)) }
        // abs(Int.MIN_VALUE) is negative: the range checks must not wave it through.
        assertThrows<IllegalArgumentException> { MouseReport(deltaX = Int.MIN_VALUE) }
        assertThrows<IllegalArgumentException> { MouseReport(deltaY = Int.MIN_VALUE) }
        assertThrows<IllegalArgumentException> { MouseReport(wheel = Int.MIN_VALUE) }
        assertThrows<IllegalArgumentException> { MouseReport(pan = Int.MIN_VALUE) }
    }

    @Test
    fun `decode rejects malformed reports`() {
        assertThrows<IllegalArgumentException> { KeyboardReport.decode(ByteArray(7)) }
        assertThrows<IllegalArgumentException> { KeyboardReport.decode(bytes(0, 1, 0, 0, 0, 0, 0, 0)) }
        assertThrows<IllegalArgumentException> { KeyboardReport.decode(bytes(0, 0, 0, 4, 0, 0, 0, 0)) }
        assertThrows<IllegalArgumentException> { KeyboardReport.decode(bytes(0, 0, 0x02, 0, 0, 0, 0, 0)) }
    }

    @Test
    fun `encode then decode round-trips`() =
        runBlocking<Unit> {
            val pressable = KeyUsage.entries.filter { !it.isModifier && it != KeyUsage.ERROR_ROLL_OVER }
            checkAll(Arb.int(0..0xFF), Arb.list(Arb.element(pressable), 0..10)) { modifierBits, keys ->
                val report = KeyboardReport.pressing(modifierBits, keys)
                assertEquals(report, KeyboardReport.decode(report.encode()))
            }
        }

    @Test
    fun `LED output report decodes each bit`() {
        assertEquals(
            KeyboardLeds(numLock = true, capsLock = false, scrollLock = true, compose = false, kana = true),
            KeyboardLeds.decode(bytes(0x15)),
        )
        assertEquals(
            KeyboardLeds(numLock = false, capsLock = true, scrollLock = false, compose = true, kana = false),
            KeyboardLeds.decode(bytes(0xEA)),
        )
        assertThrows<IllegalArgumentException> { KeyboardLeds.decode(ByteArray(2)) }
    }
}

class MouseReportTest {
    @Test
    fun `encodes buttons and signed deltas`() {
        val report = MouseReport(MouseButton.bitsOf(listOf(MouseButton.LEFT, MouseButton.MIDDLE)), -1, 127)
        assertArrayEquals(byteArrayOf(0x05, 0xFF.toByte(), 0x7F, 0, 0), report.encode())
        assertArrayEquals(byteArrayOf(0x05, 0xFF.toByte(), 0x7F), report.encodeFor(MouseLayout.BOOT))
        assertEquals(report, MouseReport.decode(report.encode()))
        assertEquals(report, MouseReport.decode(report.encodeFor(MouseLayout.BOOT)))
    }

    @Test
    fun `scroll goes in bytes 3 and 4 and keeps the held buttons`() {
        val report = MouseReport.scroll(MouseButton.LEFT.bit, wheel = -3, pan = 2)
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0xFD.toByte(), 0x02), report.encode())
        assertTrue(report.isScroll)
        assertEquals(MouseReport(0, wheel = 127, pan = -127), MouseReport.scroll(0, 1000, -1000))
        assertThrows<IllegalArgumentException> { MouseReport(0, wheel = 128) }
        assertThrows<IllegalArgumentException> { MouseReport(0, pan = -128) }
        assertThrows<IllegalArgumentException> { MouseReport.forLayout(ByteArray(3), MouseLayout.BOOT) }
    }

    @Test
    fun `bytes 0 to 2 of every wheel report are the boot report (HID 1_11 B_2) (property)`() =
        runBlocking<Unit> {
            checkAll(
                Arb.int(0..7),
                Arb.int(-127..127),
                Arb.int(-127..127),
                Arb.int(-127..127),
                Arb.int(-127..127),
            ) { buttons, x, y, wheel, pan ->
                val wheelReport = MouseReport(buttons, x, y, wheel, pan)
                val bootOnly = MouseReport(buttons, x, y)
                val bytes = wheelReport.encode()
                assertEquals(MouseLayout.WHEEL.reportLengthBytes, bytes.size)
                // A boot-protocol host reads only bytes 0..2: identical to the 3-byte boot mouse.
                assertArrayEquals(bootOnly.encodeFor(MouseLayout.BOOT), bytes.copyOf(3))
                assertArrayEquals(bytes.copyOf(3), MouseReport.forLayout(bytes, MouseLayout.BOOT))
                assertArrayEquals(bytes, MouseReport.forLayout(bytes, MouseLayout.WHEEL))
                assertEquals(wheelReport, MouseReport.decode(bytes))
            }
        }

    @Test
    fun `out of range reports are rejected and clamped reports are not`() {
        assertThrows<IllegalArgumentException> { MouseReport(0, 128, 0) }
        assertThrows<IllegalArgumentException> { MouseReport(0, 0, -128) }
        assertThrows<IllegalArgumentException> { MouseReport(0x08, 0, 0) }
        assertEquals(MouseReport(1, 127, -127), MouseReport.clamped(1, 1000, -1000))
    }

    @Test
    fun `zero motion is one report so button changes still go out`() {
        assertEquals(listOf(MouseReport(MouseButton.RIGHT.bit, 0, 0)), MouseReport.split(MouseButton.RIGHT.bit, 0, 0))
    }

    @Test
    fun `split of 300 by 10 is three even steps`() {
        assertEquals(listOf(MouseReport(0, 100, 3), MouseReport(0, 100, 3), MouseReport(0, 100, 4)), MouseReport.split(0, 300, 10))
    }

    @Test
    fun `split preserves the total, fits int8, and is bounded`() =
        runBlocking<Unit> {
            val totalMax = HidLimits.MOUSE_DELTA_MAX * HidLimits.MOUSE_SPLIT_REPORT_COUNT_MAX
            checkAll(Arb.int(-20_000..20_000), Arb.int(-20_000..20_000), Arb.int(0..7)) { deltaX, deltaY, buttons ->
                val reports = MouseReport.split(buttons, deltaX, deltaY)
                assertTrue(reports.size in 1..HidLimits.MOUSE_SPLIT_REPORT_COUNT_MAX)
                assertEquals(deltaX.coerceIn(-totalMax, totalMax), reports.sumOf { it.deltaX })
                assertEquals(deltaY.coerceIn(-totalMax, totalMax), reports.sumOf { it.deltaY })
                assertTrue(reports.all { it.buttonBits == buttons && abs(it.deltaX) <= 127 && abs(it.deltaY) <= 127 })
                // Direction never reverses mid-gesture.
                assertTrue(reports.all { it.deltaX * deltaX >= 0 && it.deltaY * deltaY >= 0 })
            }
        }
}
