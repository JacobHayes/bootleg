package dev.jacobhayes.bootleg

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.Journal
import kotlinx.coroutines.flow.StateFlow

enum class BlankImageFormat { RAW, FAT32, EXFAT, EXT4 }

/** An image file Bootleg can expose. [path] is the real path the kernel opens (/data/media/0/...). */
data class ImageFile(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    /** Allocated bytes on disk (smaller than [sizeBytes] for sparse images). */
    val allocatedBytes: Long,
    /** Verified sha256 from a `.sha256` sidecar, if any. */
    val verifiedSha256: String?,
)

sealed interface RootStatus {
    data object Unknown : RootStatus

    data object Granted : RootStatus

    data class Unavailable(
        val reason: String,
    ) : RootStatus
}

/**
 * The boundary between the UI and the system side (root service, gadget, image store).
 * The UI only talks to this; the system side implements it.
 */
interface BootlegBackend {
    val rootStatus: StateFlow<RootStatus>
    val gadgetState: StateFlow<GadgetState?>
    val journal: StateFlow<Journal>
    val images: StateFlow<List<ImageFile>>

    suspend fun refreshImages()

    suspend fun apply(desired: Desired): ApplyResult

    suspend fun eject(
        lun: Int,
        force: Boolean,
    ): ApplyResult

    /** Re-exports a slot the host or user ejected (or that failed). Bootleg never does this on its own. */
    suspend fun reinsert(lun: Int): ApplyResult

    suspend fun bootlegOff(): ApplyResult

    suspend fun createBlankImage(
        name: String,
        sizeBytes: Long,
        format: BlankImageFormat,
        preallocate: Boolean,
    ): Result<ImageFile>

    suspend fun deleteImage(path: String): Result<Unit>
}
