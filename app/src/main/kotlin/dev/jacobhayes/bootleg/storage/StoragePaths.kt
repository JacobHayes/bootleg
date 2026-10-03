package dev.jacobhayes.bootleg.storage

import dev.jacobhayes.bootleg.core.gadget.LunMode
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

/**
 * Path conventions for the image store. The app reads/writes through the FUSE view
 * (/storage/emulated/0); the kernel is handed the backing path (/data/media/0).
 */
object StoragePaths {
    const val FUSE_ROOT = "/storage/emulated/0"
    const val MEDIA_ROOT = "/data/media/0"
    const val STORE_DIR_NAME = "bootleg"
    const val STORE_FUSE_DIR = "$FUSE_ROOT/$STORE_DIR_NAME"
    const val STORE_MEDIA_DIR = "$MEDIA_ROOT/$STORE_DIR_NAME"
    const val PARTIAL_SUFFIX = ".partial"
    const val SIDECAR_SUFFIX = ".sha256"

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME = "primary"

    fun toMediaPath(fusePath: String): String {
        require(fusePath == FUSE_ROOT || fusePath.startsWith("$FUSE_ROOT/")) { "not under $FUSE_ROOT: $fusePath" }
        return MEDIA_ROOT + fusePath.removePrefix(FUSE_ROOT)
    }

    fun toFusePath(mediaPath: String): String {
        require(mediaPath == MEDIA_ROOT || mediaPath.startsWith("$MEDIA_ROOT/")) { "not under $MEDIA_ROOT: $mediaPath" }
        return FUSE_ROOT + mediaPath.removePrefix(MEDIA_ROOT)
    }

    /**
     * Maps an ExternalStorageProvider document ("primary:Download/x.iso") to its /data/media/0 path.
     * Returns null for other providers, other volumes (SD cards, USB) and anything with `..`.
     */
    fun mediaPathForDocument(
        authority: String?,
        documentId: String?,
    ): String? {
        if (authority != EXTERNAL_STORAGE_AUTHORITY || documentId == null) return null
        val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        val relative = documentId.substringAfter(':', missingDelimiterValue = "")
        if (volume != PRIMARY_VOLUME || relative.isEmpty()) return null
        if (relative.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return "$MEDIA_ROOT/$relative"
    }

    /** Auto mode: ISO → CD-ROM, everything else → read-only disk. */
    fun autoMode(path: String): LunMode = if (path.endsWith(".iso", ignoreCase = true)) LunMode.CDROM else LunMode.DISK_RO

    fun kindLabel(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "iso" -> "ISO"
            "img", "raw", "bin" -> "Disk image"
            else -> "File"
        }

    /**
     * Staging file for an import of [name]. Hidden (leading dot, never a valid image name), so it can't
     * collide with a download's `<name>.partial` or with another image.
     */
    fun importStagingName(name: String): String = ".$name.import$PARTIAL_SUFFIX"

    /** Staging file for the image extracted from a zipped download of [name]; hidden like [importStagingName]. */
    fun extractStagingName(name: String): String = ".$name.extract$PARTIAL_SUFFIX"

    /**
     * Moves a finished staging file into place, refusing to replace an existing image (a download and an
     * import of the same name can both finish). Throws FileAlreadyExistsException then.
     *
     * A stale FUSE entry (the file was removed under `/data/media`, behind the FUSE daemon's back) is
     * not an image: the staging file is renamed over it, which the daemon carries out on the backing
     * directory and which clears the entry (an app's unlink of a stale entry fails with ENOENT).
     */
    fun moveIntoPlace(
        staging: File,
        target: File,
    ) {
        try {
            Files.move(staging.toPath(), target.toPath())
        } catch (exists: FileAlreadyExistsException) {
            if (!isStaleEntry(target)) throw exists
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** True when [file] is really there: it exists and opens (a stale FUSE entry stats fine but won't open). */
    fun existsLive(file: File): Boolean = file.exists() && !isStaleEntry(file)

    /** True when [file] stats as present but opening it says it doesn't exist. */
    fun isStaleEntry(file: File): Boolean =
        try {
            Files.newByteChannel(file.toPath()).close()
            false
        } catch (missing: NoSuchFileException) {
            Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (other: IOException) {
            false
        }

    fun isValidImageName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= FILE_NAME_LENGTH_MAX &&
            name.none { it == '/' || it == '\u0000' || it == '\n' } &&
            !name.startsWith(".") &&
            !name.endsWith(PARTIAL_SUFFIX) &&
            !name.endsWith(SIDECAR_SUFFIX)

    const val FILE_NAME_LENGTH_MAX = 200
}
