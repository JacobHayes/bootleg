package dev.jacobhayes.bootleg.core.gadget

import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.iso
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The fake must model the kernel rules the controller depends on, or the simulation proves nothing. */
class FakeConfigFsTest {
    private val massStorage = "functions/mass_storage.bootleg"

    private fun errno(block: () -> Unit): String = assertThrows<ConfigFsException> { block() }.errno

    @Test
    fun `UDC rules`() {
        val fs = FakeConfigFs.pixel()
        assertEquals("EBUSY", errno { fs.write("UDC", FakeConfigFs.PIXEL_UDC) })
        fs.write("UDC", "")
        assertEquals("ENODEV", errno { fs.write("UDC", "") })
        assertEquals("ENODEV", errno { fs.write("UDC", "dummy_udc.0") })
        fs.write("UDC", FakeConfigFs.PIXEL_UDC)
        assertEquals(FakeConfigFs.PIXEL_UDC, fs.udc)
    }

    @Test
    fun `mass storage creates lun 0 with defaults and enforces LUN rules`() {
        val fs = FakeConfigFs.pixel(setOf(iso(1)))
        fs.mkdir(massStorage)
        assertEquals(listOf("lun.0", "stall"), fs.list(massStorage))
        assertEquals("\n", fs.read("$massStorage/lun.0/file"))
        assertEquals("1\n", fs.read("$massStorage/lun.0/removable"))
        assertEquals("EPERM", errno { fs.rmdir("$massStorage/lun.0") })
        assertEquals("ENOENT", errno { fs.write("$massStorage/lun.0/file", "/data/media/0/bootleg/nope.iso") })
        fs.write("$massStorage/lun.0/file", iso(1))
        assertEquals("EBUSY", errno { fs.write("$massStorage/lun.0/ro", "1") })
        assertEquals("EBUSY", errno { fs.write("$massStorage/lun.0/cdrom", "1") })
        fs.write("$massStorage/lun.0/file", "")
        fs.write("$massStorage/lun.0/cdrom", "1")
        assertEquals("1\n", fs.read("$massStorage/lun.0/ro"), "cdrom=1 implies ro=1")
        fs.mkdir("$massStorage/lun.1")
        assertEquals("ENOTEMPTY", errno { fs.rmdir(massStorage) })
        fs.write("UDC", "")
        fs.symlink(massStorage, "configs/b.1/mass_storage.bootleg")
        assertEquals("EBUSY", errno { fs.mkdir("$massStorage/lun.2") })
        assertEquals("EBUSY", errno { fs.rmdir("$massStorage/lun.1") })
        assertEquals("EEXIST", errno { fs.symlink(massStorage, "configs/b.1/other.bootleg") })
    }

    @Test
    fun `prevented removal blocks eject but not forced eject`() {
        val fs = FakeConfigFs.pixel(setOf(iso(1)))
        fs.mkdir(massStorage)
        fs.write("$massStorage/lun.0/file", iso(1))
        fs.preventRemoval += 0
        assertEquals("EBUSY", errno { fs.write("$massStorage/lun.0/file", "") })
        fs.write("$massStorage/lun.0/forced_eject", "1")
        assertEquals("\n", fs.read("$massStorage/lun.0/file"))
        // The kernel clears prevent-removal on a forced eject, so the LUN then changes normally.
        assertFalse(0 in fs.preventRemoval)
        fs.write("$massStorage/lun.0/file", iso(1))
        fs.write("$massStorage/lun.0/file", "")
        assertEquals("\n", fs.read("$massStorage/lun.0/file"))
    }

    @Test
    fun `linking while bound is refused, unlinking unbinds, HID attrs lock when linked`() {
        val fs = FakeConfigFs.pixel()
        fs.mkdir("functions/hid.bootleg_kbd")
        fs.write("functions/hid.bootleg_kbd/report_length", "8")
        fs.writeBytes("functions/hid.bootleg_kbd/report_desc", FakeHidDescriptors.keyboard())
        assertEquals("EINVAL", errno { fs.symlink("functions/hid.bootleg_kbd", "configs/b.1/hid.bootleg_kbd") })
        assertEquals(listOf("${FakeConfigFs.EXECUTOR} configs/b.1/hid.bootleg_kbd"), fs.boundSymlinkRejections)
        assertFalse(fs.exists("configs/b.1/hid.bootleg_kbd"))
        fs.write("UDC", "")
        fs.symlink("functions/hid.bootleg_kbd", "configs/b.1/hid.bootleg_kbd")
        fs.write("UDC", FakeConfigFs.PIXEL_UDC)
        assertEquals("EBUSY", errno { fs.write("functions/hid.bootleg_kbd/protocol", "1") })
        assertEquals("EBUSY", errno { fs.rmdir("functions/hid.bootleg_kbd") })
        fs.unlink("configs/b.1/hid.bootleg_kbd")
        assertEquals("", fs.udc)
    }

    @Test
    fun `a disconnect clears prevent-removal, and LUN flags stay writable while linked but removable does not`() {
        val fs = FakeConfigFs.pixel(setOf(iso(1)))
        fs.mkdir(massStorage)
        fs.write("UDC", "")
        fs.symlink(massStorage, "configs/b.1/mass_storage.bootleg")
        fs.write("UDC", FakeConfigFs.PIXEL_UDC)
        fs.write("$massStorage/lun.0/ro", "1")
        assertEquals("EBUSY", errno { fs.write("$massStorage/lun.0/removable", "1") })
        fs.write("$massStorage/lun.0/file", iso(1))
        fs.preventRemoval += 0
        fs.write("UDC", "")
        assertEquals(emptySet<Int>(), fs.preventRemoval)
        fs.write("$massStorage/lun.0/file", "")
    }

    @Test
    fun `HID functions without a descriptor fail to bind`() {
        val fs = FakeConfigFs.pixel()
        fs.write("UDC", "")
        fs.mkdir("functions/hid.bootleg_kbd")
        fs.symlink("functions/hid.bootleg_kbd", "configs/b.1/hid.bootleg_kbd")
        assertEquals("EINVAL", errno { fs.write("UDC", FakeConfigFs.PIXEL_UDC) })
    }

    @Test
    fun `executor writes outside Bootleg names are recorded`() {
        val fs = FakeConfigFs.pixel()
        fs.unlink("configs/b.1/function0")
        assertEquals(listOf("configs/b.1/function0"), fs.foreignWrites)
    }
}
