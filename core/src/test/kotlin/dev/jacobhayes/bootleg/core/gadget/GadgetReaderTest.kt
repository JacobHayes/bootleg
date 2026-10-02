package dev.jacobhayes.bootleg.core.gadget

import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.iso
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class GadgetReaderTest {
    private val props =
        object : UsbProps {
            override fun usbState() = "adb"

            override fun usbConfig() = "adb"
        }

    @Test
    fun `reads the Pixel gadget`() {
        val state = GadgetReader(FakeConfigFs.pixel(), props).read()
        assertEquals(
            GadgetState(
                udc = FakeConfigFs.PIXEL_UDC,
                functions = FakeConfigFs.PIXEL_FOREIGN_FUNCTIONS.toSet(),
                configLinks = mapOf("function0" to "ffs.adb", "hid.keyboard" to "hid.keyboard", "hid.touchpad" to "hid.touchpad"),
                luns = emptyMap(),
                usbState = "adb",
                usbConfig = "adb",
            ),
            state,
        )
    }

    @Test
    fun `reads LUN attributes, trimming newlines and allowing an empty file`() {
        val fs = FakeConfigFs.pixel(setOf(iso(1)))
        fs.mkdir("functions/mass_storage.bootleg")
        fs.mkdir("functions/mass_storage.bootleg/lun.1")
        fs.write("functions/mass_storage.bootleg/lun.1/cdrom", "1")
        fs.write("functions/mass_storage.bootleg/lun.1/file", iso(1) + "\n")
        fs.write("functions/mass_storage.bootleg/lun.0/nofua", "1")
        val luns = GadgetReader(fs, props).read().luns
        assertEquals(LunState("", ro = false, cdrom = false, removable = true, nofua = true, inquiryString = ""), luns[0])
        assertEquals(LunState(iso(1), ro = true, cdrom = true, removable = true, nofua = false, inquiryString = ""), luns[1])
    }

    @Test
    fun `a kernel without inquiry_string reads as no marker support`() {
        val fs = FakeConfigFs.pixel()
        fs.mkdir("functions/mass_storage.bootleg")
        val withoutInquiry =
            object : ConfigFs by fs {
                override fun exists(path: String) = !path.endsWith("/inquiry_string") && fs.exists(path)
            }
        assertEquals(
            null,
            GadgetReader(withoutInquiry, props)
                .read()
                .luns
                .getValue(0)
                .inquiryString,
        )
    }

    @Test
    fun `an unbound gadget reads as an empty UDC`() {
        val fs = FakeConfigFs.pixel()
        fs.write("UDC", "")
        assertEquals("", GadgetReader(fs, props).read().udc)
    }

    @Test
    fun `garbage in a flag attribute fails loudly`() {
        val fs =
            object : ConfigFs by FakeConfigFs.pixel() {
                override fun list(path: String) = if (path.endsWith("bootleg")) listOf("lun.0") else listOf("mass_storage.bootleg")

                override fun read(path: String) = if (path.endsWith("/file")) "\n" else "yes\n"

                override fun exists(path: String) = false
            }
        assertThrows(IllegalStateException::class.java) { GadgetReader(fs, props).read() }
    }

    @Test
    fun `a link that vanishes between list and readlink reads as absent`() {
        val fs =
            object : ConfigFs by FakeConfigFs.pixel() {
                override fun readLink(path: String): String? = if (path.endsWith("function0")) throw IOException("ENOENT") else null
            }
        assertEquals(emptyMap<String, String>(), GadgetReader(fs, props).read().configLinks)
    }
}
