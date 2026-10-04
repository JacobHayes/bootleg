package dev.jacobhayes.bootleg.core.gadget

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LunModesTest {
    /** The kernel's rule (storage_common.c fsg_lun_open): blocks = size >> 11, truncated at 256*60*75, refused below 300. */
    private fun kernelExportsWhole(sizeBytes: Long): Boolean {
        val blocks = sizeBytes shr 11
        return blocks in 300 until 256L * 60 * 75
    }

    @Test
    fun `the limits are the kernel's`() {
        assertEquals(1_151_999L, CdromLimits.BLOCK_COUNT_MAX)
        assertEquals(2_359_295_999L, CdromLimits.SIZE_BYTES_MAX)
        assertEquals(614_400L, CdromLimits.SIZE_BYTES_MIN)
    }

    @Test
    fun `fits matches the kernel at and around both boundaries`() {
        val edges = listOf(CdromLimits.SIZE_BYTES_MIN, CdromLimits.SIZE_BYTES_MAX + 1)
        for (edge in edges) {
            for (delta in -4_097L..4_097L) {
                val size = edge + delta
                assertEquals(kernelExportsWhole(size), CdromLimits.fits(size), "size $size")
            }
        }
        assertTrue(CdromLimits.fits(CdromLimits.SIZE_BYTES_MAX))
        assertFalse(CdromLimits.fits(CdromLimits.SIZE_BYTES_MAX + 1))
        assertFalse(CdromLimits.fits(CdromLimits.SIZE_BYTES_MIN - 1))
        assertThrows<IllegalArgumentException> { CdromLimits.fits(-1) }
    }

    @Test
    fun `fits matches the kernel for any size`() =
        runBlocking<Unit> {
            checkAll(2_000, Arb.long(0L..20_000_000_000L)) { size ->
                assertEquals(kernelExportsWhole(size), CdromLimits.fits(size), "size $size")
            }
        }

    @Test
    fun `auto never picks a CD-ROM the kernel would truncate`() =
        runBlocking<Unit> {
            checkAll(2_000, Arb.long(0L..20_000_000_000L)) { size ->
                val mode = LunModes.auto("/data/media/0/bootleg/x.iso", size)
                assertEquals(if (kernelExportsWhole(size)) LunMode.CDROM else LunMode.DISK_RO, mode, "size $size")
                assertEquals(LunMode.DISK_RO, LunModes.auto("/data/media/0/bootleg/x.img", size))
            }
        }

    @Test
    fun `auto by extension when the size is unknown`() {
        assertEquals(LunMode.CDROM, LunModes.auto("/a/b.ISO", null))
        assertEquals(LunMode.DISK_RO, LunModes.auto("/a/b.img", null))
        assertEquals(LunMode.DISK_RO, LunModes.auto("/a/iso", null))
        // The Omarchy ISO from the Windows 7 session.
        assertEquals(LunMode.DISK_RO, LunModes.auto("/a/omarchy-4.0.4.iso", 6_200_000_000L))
    }

    @Test
    fun `problems name the file and the alternative`() {
        assertNull(CdromLimits.problem("x.iso", 700_000_000))
        val big = checkNotNull(CdromLimits.problem("omarchy.iso", 6_200_000_000L))
        assertTrue(big.startsWith("omarchy.iso is 6.20 GB; a CD-ROM holds at most 2.36 GB"), big)
        assertTrue(big.endsWith("Use Disk (read-only)"), big)
        val small = checkNotNull(CdromLimits.problem("tiny.iso", 1_000))
        assertTrue(small.contains("smaller"), small)
    }
}
