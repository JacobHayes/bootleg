package dev.jacobhayes.bootleg

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.TetherMode
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.tether.TetherResult
import dev.jacobhayes.bootleg.hid.HidEndpoint
import dev.jacobhayes.bootleg.hid.HidRelayNotice
import kotlinx.coroutines.flow.Flow
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

    /** Exposes or removes the HID keyboard/mouse, keeping the current drives (re-enumerates USB). */
    suspend fun setHid(spec: HidSpec): ApplyResult

    /** Opens one of Bootleg's HID functions through the root relay; the caller closes it. */
    suspend fun openHid(kind: HidKind): HidEndpoint

    /** What the root relay says when it drops (or refuses) the HID fds on its own; the open HID session follows it. */
    val hidRelayNotices: Flow<HidRelayNotice>

    /** USB tethering over either transport: on, off, or null until Android has reported it. */
    val usbTethering: StateFlow<Boolean?>

    /** A tethering change is running. */
    val usbTetheringBusy: StateFlow<Boolean>

    /** The transport tethering uses (a preference); a caller switches tethering that is on over to a new one. */
    val usbTetherMode: StateFlow<TetherMode>

    fun setUsbTetherMode(mode: TetherMode)

    /** Turns USB tethering on over [usbTetherMode], or off. NCM re-plugs USB and is refused while a disk is exposed; RNDIS doesn't. */
    suspend fun setUsbTethering(on: Boolean): TetherResult
}
