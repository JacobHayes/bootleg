package dev.jacobhayes.bootleg.root

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON codec shared by both sides of [IBootlegRoot]. */
val BootlegJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/** An image file as reported by the root process; mapped to [dev.jacobhayes.bootleg.ImageFile] in the app. */
@Serializable
data class RootImage(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val allocatedBytes: Long,
    val verifiedSha256: String?,
)

/** Path rules for images. The kernel is always given the real `/data/media/0/...` path, never the FUSE view. */
object ImagePaths {
    const val MEDIA_ROOT = "/data/media/0/"
    const val STORE_DIR = "/data/media/0/bootleg"
    val allowedRoots: List<String> = listOf(MEDIA_ROOT)

    private val fuseAliases = listOf("/sdcard/", "/storage/emulated/0/", "/storage/self/primary/", "/mnt/user/0/emulated/0/")

    /** Maps a user-visible shared-storage path to the path the kernel can open. */
    fun toKernelPath(path: String): String {
        for (alias in fuseAliases) {
            if (path.startsWith(alias)) return MEDIA_ROOT + path.removePrefix(alias)
        }
        return path
    }

    /** The user-visible path for media scanning (`/storage/emulated/0/...`). */
    fun toSharedStoragePath(kernelPath: String): String {
        require(kernelPath.startsWith(MEDIA_ROOT)) { "not under $MEDIA_ROOT: $kernelPath" }
        return "/storage/emulated/0/" + kernelPath.removePrefix(MEDIA_ROOT)
    }
}
