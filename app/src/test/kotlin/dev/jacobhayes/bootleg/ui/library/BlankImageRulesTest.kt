package dev.jacobhayes.bootleg.ui.library

import dev.jacobhayes.bootleg.BlankImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BlankImageRulesTest {
    private val gb = BlankImageRules.GB

    private fun request(
        name: String = "blank.img",
        sizeBytes: Long = 8 * gb,
        format: BlankImageFormat = BlankImageFormat.FAT32,
        preallocate: Boolean = false,
    ) = BlankImageRequest(name, sizeBytes, format, preallocate)

    @Test
    fun `adds img extension only when missing`() {
        assertEquals("scratch.img", BlankImageRules.normalizedName(" scratch "))
        assertEquals("scratch.bin", BlankImageRules.normalizedName("scratch.bin"))
    }

    @Test
    fun `valid request has no error or warning`() {
        val verdict = BlankImageRules.check(request(), emptySet(), freeBytes = 100 * gb)
        assertNull(verdict.error)
        assertNull(verdict.warning)
    }

    @Test
    fun `sparse image larger than free space warns but is allowed`() {
        val verdict = BlankImageRules.check(request(sizeBytes = 64 * gb), emptySet(), freeBytes = 10 * gb)
        assertNull(verdict.error)
        assertNotNull(verdict.warning)
    }

    @Test
    fun `preallocated image larger than free space is blocked`() {
        assertNotNull(BlankImageRules.check(request(sizeBytes = 64 * gb, preallocate = true), emptySet(), 10 * gb).error)
    }

    @Test
    fun `format and name limits`() {
        assertNotNull(BlankImageRules.check(request(sizeBytes = 32L * 1024 * 1024), emptySet(), null).error, "FAT32 below 33 MiB")
        assertNull(BlankImageRules.check(request(sizeBytes = 32L * 1024 * 1024, format = BlankImageFormat.RAW), emptySet(), null).error)
        assertNotNull(
            BlankImageRules.check(request(sizeBytes = 3000 * gb, format = BlankImageFormat.EXT4), emptySet(), null).error,
            "MBR cap",
        )
        assertNotNull(BlankImageRules.check(request(name = "a/b.img"), emptySet(), null).error)
        assertNotNull(BlankImageRules.check(request(), setOf("blank.img"), null).error)
        assertNotNull(BlankImageRules.check(request(sizeBytes = 0), emptySet(), null).error)
    }
}
