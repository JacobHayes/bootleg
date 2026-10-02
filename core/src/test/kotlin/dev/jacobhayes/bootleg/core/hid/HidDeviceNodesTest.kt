package dev.jacobhayes.bootleg.core.hid

import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HidDeviceNodesTest {
    @Test
    fun `dev attribute parses major and minor`() {
        assertEquals(DeviceNumber(239, 2), DeviceNumber.parse("239:2\n"))
        assertThrows<IllegalArgumentException> { DeviceNumber.parse("239") }
        assertThrows<IllegalArgumentException> { DeviceNumber.parse("a:b") }
        assertThrows<IllegalArgumentException> { DeviceNumber.parse("-1:0") }
    }

    @Test
    fun `rdev decoding matches the Linux encoding, including large minors`() {
        // new_encode_dev: (minor & 0xff) | (major << 8) | ((minor & ~0xff) << 12)
        fun encode(
            major: Int,
            minor: Int,
        ): Long = (minor and 0xFF).toLong() or (major.toLong() shl 8) or ((minor and 0xFF.inv()).toLong() shl 12)
        for ((major, minor) in listOf(239 to 0, 239 to 3, 10 to 300, 4095 to 0xFFFFF)) {
            assertEquals(DeviceNumber(major, minor), DeviceNumber.fromRdev(encode(major, minor)))
        }
    }

    @Test
    fun `select picks the node by device number, never by index`() {
        // USB HID Client owns hidg0 and hidg1; our keyboard landed on hidg2.
        val nodes =
            mapOf(
                "/dev/hidg0" to DeviceNumber(239, 0),
                "/dev/hidg1" to DeviceNumber(239, 1),
                "/dev/hidg2" to DeviceNumber(239, 2),
            )
        assertEquals("/dev/hidg2", HidDeviceNodes.select(DeviceNumber(239, 2), nodes))
    }

    @Test
    fun `select fails loudly when nothing or several nodes match`() {
        assertThrows<IllegalStateException> { HidDeviceNodes.select(DeviceNumber(239, 5), mapOf("/dev/hidg0" to DeviceNumber(239, 0))) }
        assertThrows<IllegalStateException> {
            HidDeviceNodes.select(DeviceNumber(239, 0), mapOf("/dev/hidg0" to DeviceNumber(239, 0), "/dev/hidg9" to DeviceNumber(239, 0)))
        }
        val tooMany = (0..HidDeviceNodes.NODE_COUNT_MAX).associate { "/dev/hidg$it" to DeviceNumber(239, it) }
        assertThrows<IllegalArgumentException> { HidDeviceNodes.select(DeviceNumber(239, 0), tooMany) }
    }

    @Test
    fun `only hidgN names are candidates`() {
        assertTrue(HidDeviceNodes.isCandidateName("hidg0"))
        assertTrue(HidDeviceNodes.isCandidateName("hidg12"))
        assertFalse(HidDeviceNodes.isCandidateName("hidg"))
        assertFalse(HidDeviceNodes.isCandidateName("hidraw0"))
        assertFalse(HidDeviceNodes.isCandidateName("hidg0x"))
    }

    @Test
    fun `kinds map to our functions and release everything`() {
        assertEquals(GadgetNames.HID_KEYBOARD_FUNCTION, HidKind.fromWireName("keyboard")?.functionName)
        assertEquals(GadgetNames.HID_MOUSE_FUNCTION, HidKind.fromWireName("mouse")?.functionName)
        assertEquals(null, HidKind.fromWireName("hid.usb0"))
        assertArrayEquals(ByteArray(8), HidKind.KEYBOARD.releaseAllReport)
        assertArrayEquals(ByteArray(3), HidKind.MOUSE.releaseAllReport)
    }
}
