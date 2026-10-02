package dev.jacobhayes.bootleg.download

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Release
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveExtractorTest {
    @TempDir
    lateinit var dir: File

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = File(dir, "test.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `extracts exactly the named entry and hashes it`() {
        val image = ByteArray(200_000) { (it % 251).toByte() }
        val archive = zip("README.txt" to "hi".toByteArray(), "memtest.iso" to image)
        val destination = File(dir, "out.iso")
        var lastProgress = 0L
        val extracted = ArchiveExtractor.extract(archive, "memtest.iso", destination) { lastProgress = it }
        assertEquals(image.size.toLong(), extracted.sizeBytes)
        assertEquals(sha256(image), extracted.sha256)
        assertEquals(image.size.toLong(), lastProgress)
        assertTrue(destination.readBytes().contentEquals(image))
    }

    @Test
    fun `missing entry fails`() {
        val archive = zip("other.iso" to ByteArray(10))
        assertThrows<IllegalStateException> { ArchiveExtractor.extract(archive, "memtest.iso", File(dir, "out")) }
    }

    @Test
    fun `entry is matched exactly, not by base name`() {
        val archive = zip("nested/memtest.iso" to ByteArray(10))
        assertThrows<IllegalStateException> { ArchiveExtractor.extract(archive, "memtest.iso", File(dir, "out")) }
    }

    @Test
    fun `unsafe entry names are rejected before opening the archive`() {
        for (name in listOf("", "../x.iso", "a/../../x.iso", "/etc/passwd", "a\\b.iso", "C:x.iso", "dir/", "a//b", "./x")) {
            assertFalse(ArchiveExtractor.isSafeEntryName(name), name)
        }
        assertTrue(ArchiveExtractor.isSafeEntryName("memtest.iso"))
        assertTrue(ArchiveExtractor.isSafeEntryName("images/boot.img"))
        assertThrows<IllegalArgumentException> { ArchiveExtractor.extract(File(dir, "none.zip"), "../x", File(dir, "out")) }
    }

    @Test
    fun `existing destination is never overwritten`() {
        val archive = zip("memtest.iso" to ByteArray(10))
        val destination = File(dir, "out").apply { writeText("keep") }
        assertThrows<IllegalArgumentException> { ArchiveExtractor.extract(archive, "memtest.iso", destination) }
        assertEquals("keep", destination.readText())
    }

    @Test
    fun `size limit follows the compression ratio with a small exempt floor`() {
        assertEquals(ArchiveLimits.RATIO_EXEMPT_BYTES, ArchiveExtractor.entrySizeLimitBytes(0))
        assertEquals(ArchiveLimits.RATIO_EXEMPT_BYTES, ArchiveExtractor.entrySizeLimitBytes(1024))
        assertEquals(10L * 1024 * 1024 * ArchiveLimits.COMPRESSION_RATIO_MAX, ArchiveExtractor.entrySizeLimitBytes(10L * 1024 * 1024))
        assertEquals(ArchiveLimits.ENTRY_SIZE_BYTES_MAX, ArchiveExtractor.entrySizeLimitBytes(1L shl 40))
        assertEquals(ArchiveLimits.ENTRY_SIZE_BYTES_MAX, ArchiveExtractor.entrySizeLimitBytes(Long.MAX_VALUE))
    }

    @Test
    fun `a zip bomb is stopped at the limit`() {
        // 65 MiB of zeros deflates to ~65 KiB: far beyond the ratio and above the exempt floor.
        val file = File(dir, "bomb.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("memtest.iso"))
            val zeros = ByteArray(1 shl 20)
            repeat(65) { out.write(zeros) }
            out.closeEntry()
        }
        assertThrows<IllegalStateException> { ArchiveExtractor.extract(file, "memtest.iso", File(dir, "out")) }
    }

    @Test
    fun `a stored entry with a wrong crc fails`() {
        val bytes = "image".toByteArray()
        val file = File(dir, "crc.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            val entry =
                ZipEntry("memtest.iso").apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
            out.putNextEntry(entry)
            out.write(bytes)
            out.closeEntry()
        }
        // Flip a payload byte; the central directory still holds the original CRC.
        val raw = file.readBytes()
        val at = raw.indexOfSlice(bytes)
        raw[at] = 'X'.code.toByte()
        file.writeBytes(raw)
        assertThrows<IllegalStateException> { ArchiveExtractor.extract(file, "memtest.iso", File(dir, "out")) }
    }

    @Test
    fun `download record keeps archive and image hashes apart`() {
        val archiveHash = "a".repeat(64)
        val imageHash = "b".repeat(64)
        val zipped =
            Release(
                distroId = "memtest86plus",
                version = "8.10",
                arch = Arch.X86_64,
                url = "https://example.org/mt86plus_8.10_x86_64.iso.zip",
                sha256 = imageHash,
                sumsUrl = "https://example.org/sha256sum.txt",
                fileName = "mt86plus_8.10_x86_64.iso",
                archiveEntry = "memtest.iso",
                archiveSha256 = archiveHash,
            )
        val record = DownloadRecord.from(zipped, "Memtest86+", nowMs = 0)
        assertEquals(archiveHash, record.expectedSha256)
        assertEquals(imageHash, record.imageSha256)
        assertEquals("memtest.iso", record.archiveEntry)

        val plain = DownloadRecord.from(zipped.copy(archiveEntry = null, archiveSha256 = null), "Memtest86+", nowMs = 0)
        assertEquals(imageHash, plain.expectedSha256)
        assertNull(plain.imageSha256)
    }

    private fun ByteArray.indexOfSlice(slice: ByteArray): Int =
        (0..size - slice.size).first { start -> slice.indices.all { this[start + it] == slice[it] } }
}
