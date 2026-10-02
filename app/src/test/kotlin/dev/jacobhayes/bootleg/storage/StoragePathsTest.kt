package dev.jacobhayes.bootleg.storage

import dev.jacobhayes.bootleg.core.gadget.LunMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
}
