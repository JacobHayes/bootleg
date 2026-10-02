package dev.jacobhayes.bootleg.root

import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CanonicalizeDesiredTest {
    private val store = "/data/media/0/bootleg"

    /** A filesystem where `a.img` and `link-to-a.img` are hard links to one inode. */
    private val inodes =
        mapOf(
            "$store/a.img" to FileIdentity(device = 253, inode = 100),
            "$store/link-to-a.img" to FileIdentity(device = 253, inode = 100),
            "$store/b.img" to FileIdentity(device = 253, inode = 101),
        )

    private fun open(path: String): OpenedImage {
        val kernelPath = ImagePaths.toKernelPath(path)
        return OpenedImage(kernelPath, inodes[kernelPath] ?: throw IllegalArgumentException("no such file: $path"))
    }

    private fun desired(vararg slots: Pair<String, LunMode>) = Desired(slots.mapIndexed { lun, (path, mode) -> SlotSpec(lun, path, mode) })

    @Test
    fun `paths become kernel paths`() {
        val canonical = canonicalizeDesired(desired("/sdcard/bootleg/a.img" to LunMode.DISK_RW, "$store/b.img" to LunMode.DISK_RO), ::open)
        assertEquals(listOf("$store/a.img", "$store/b.img"), canonical.slots.map { it.backingPath })
    }

    @Test
    fun `two hard links to one inode can't be exported when either copy is writable`() {
        val modePairs = listOf(LunMode.DISK_RW to LunMode.DISK_RO, LunMode.CDROM to LunMode.DISK_RW, LunMode.DISK_RW to LunMode.DISK_RW)
        for ((first, second) in modePairs) {
            val error =
                assertThrows<IllegalArgumentException>("$first, $second") {
                    canonicalizeDesired(desired("$store/a.img" to first, "$store/link-to-a.img" to second), ::open)
                }
            assertTrue("writable file may only be exported once" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `read-only copies of one inode are fine`() {
        val canonical = canonicalizeDesired(desired("$store/a.img" to LunMode.CDROM, "$store/link-to-a.img" to LunMode.DISK_RO), ::open)
        assertEquals(2, canonical.slots.size)
    }

    @Test
    fun `the same file through a path alias is caught too`() {
        assertThrows<IllegalArgumentException> {
            canonicalizeDesired(desired("/sdcard/bootleg/b.img" to LunMode.DISK_RW, "$store/b.img" to LunMode.DISK_RO), ::open)
        }
    }
}
