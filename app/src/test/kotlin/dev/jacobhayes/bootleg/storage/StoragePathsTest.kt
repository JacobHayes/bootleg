package dev.jacobhayes.bootleg.storage

import dev.jacobhayes.bootleg.core.gadget.LunMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

class StoragePathsTest {
    private val authority = "com.android.externalstorage.documents"

    @Test
    fun `primary volume documents map to data media`() {
        assertEquals("/data/media/0/Download/x.iso", StoragePaths.mediaPathForDocument(authority, "primary:Download/x.iso"))
        assertEquals("/data/media/0/bootleg/a b.img", StoragePaths.mediaPathForDocument(authority, "primary:bootleg/a b.img"))
    }

    @Test
    fun `other providers, volumes and traversal are rejected`() {
        assertNull(StoragePaths.mediaPathForDocument("com.android.providers.downloads.documents", "primary:Download/x.iso"))
        assertNull(StoragePaths.mediaPathForDocument(authority, "1234-ABCD:x.iso"))
        assertNull(StoragePaths.mediaPathForDocument(authority, "primary:"))
        assertNull(StoragePaths.mediaPathForDocument(authority, "primary:../../data/x"))
        assertNull(StoragePaths.mediaPathForDocument(authority, "primary:a//b"))
        assertNull(StoragePaths.mediaPathForDocument(authority, "no-colon"))
        assertNull(StoragePaths.mediaPathForDocument(authority, null))
    }

    @Test
    fun `fuse and media paths round trip`() {
        val media = StoragePaths.toMediaPath("/storage/emulated/0/bootleg/x.iso")
        assertEquals("/data/media/0/bootleg/x.iso", media)
        assertEquals("/storage/emulated/0/bootleg/x.iso", StoragePaths.toFusePath(media))
        assertThrows<IllegalArgumentException> { StoragePaths.toMediaPath("/storage/emulated/01/x") }
        assertThrows<IllegalArgumentException> { StoragePaths.toFusePath("/data/media/10/x") }
    }

    @Test
    fun `auto mode by extension`() {
        assertEquals(LunMode.CDROM, StoragePaths.autoMode("/a/b.ISO"))
        assertEquals(LunMode.DISK_RO, StoragePaths.autoMode("/a/b.img"))
        assertEquals(LunMode.DISK_RO, StoragePaths.autoMode("/a/iso"))
    }

    @Test
    fun `image names`() {
        assertTrue(StoragePaths.isValidImageName("ubuntu.iso"))
        assertFalse(StoragePaths.isValidImageName(""))
        assertFalse(StoragePaths.isValidImageName("a/b.iso"))
        assertFalse(StoragePaths.isValidImageName(".hidden"))
        assertFalse(StoragePaths.isValidImageName("x.iso.partial"))
        assertFalse(StoragePaths.isValidImageName("x.iso.sha256"))
        assertFalse(StoragePaths.isValidImageName("x".repeat(StoragePaths.FILE_NAME_LENGTH_MAX + 1)))
    }

    @Test
    fun `move into place never replaces a real image`(
        @TempDir dir: File,
    ) {
        val staging = File(dir, ".x.iso.partial").apply { writeText("new") }
        val target = File(dir, "x.iso").apply { writeText("old") }
        assertThrows<FileAlreadyExistsException> { StoragePaths.moveIntoPlace(staging, target) }
        assertEquals("old", target.readText())
        assertTrue(staging.exists())
    }

    @Test
    fun `a stale entry (stats, won't open) is cleared and the move goes through`(
        @TempDir dir: File,
    ) {
        // A dangling symlink behaves like a stale FUSE entry: it's there to lstat, and opening it says ENOENT.
        val staging = File(dir, ".x.iso.partial").apply { writeText("new") }
        val target = File(dir, "x.iso")
        Files.createSymbolicLink(target.toPath(), File(dir, "gone").toPath())
        assertTrue(StoragePaths.isStaleEntry(target))
        assertFalse(StoragePaths.existsLive(target))

        StoragePaths.moveIntoPlace(staging, target)

        assertEquals("new", target.readText())
        assertFalse(Files.isSymbolicLink(target.toPath()))
        assertFalse(StoragePaths.isStaleEntry(target))
        assertTrue(StoragePaths.existsLive(target))
        assertFalse(StoragePaths.isStaleEntry(File(dir, "missing.iso")))
    }
}
