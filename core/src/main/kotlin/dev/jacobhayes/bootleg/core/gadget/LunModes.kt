package dev.jacobhayes.bootleg.core.gadget

import java.util.Locale

/**
 * What f_mass_storage accepts for a CD-ROM LUN (`fsg_lun_open` in
 * `drivers/usb/gadget/function/storage_common.c`): 2048-byte blocks, at most `256*60*75 - 1` of them
 * (a larger file is silently truncated: "file too big ... using only first 1151999 blocks"), and at
 * least 300 (a smaller file fails to open). A disk LUN has no such cap.
 */
object CdromLimits {
    const val BLOCK_SIZE_BYTES = 2_048L
    const val BLOCK_COUNT_MAX = 256L * 60 * 75 - 1
    const val BLOCK_COUNT_MIN = 300L

    /** The largest file a CD-ROM LUN exports whole (2,359,295,999 bytes; the kernel drops a partial last block). */
    const val SIZE_BYTES_MAX = (BLOCK_COUNT_MAX + 1) * BLOCK_SIZE_BYTES - 1
    const val SIZE_BYTES_MIN = BLOCK_COUNT_MIN * BLOCK_SIZE_BYTES

    fun fits(sizeBytes: Long): Boolean {
        require(sizeBytes >= 0) { "negative size: $sizeBytes" }
        return sizeBytes / BLOCK_SIZE_BYTES in BLOCK_COUNT_MIN..BLOCK_COUNT_MAX
    }

    /** Why [name] ([sizeBytes]) can't be a CD-ROM, or null when it can. */
    fun problem(
        name: String,
        sizeBytes: Long,
    ): String? =
        when {
            fits(sizeBytes) -> null
            sizeBytes > SIZE_BYTES_MAX ->
                "$name is ${gigabytes(sizeBytes)}; a CD-ROM holds at most ${gigabytes(SIZE_BYTES_MAX)}. Use Disk (read-only)"
            else -> "$name is smaller than the ${SIZE_BYTES_MIN / 1_024} KB a CD-ROM needs. Use Disk (read-only)"
        }

    private fun gigabytes(sizeBytes: Long): String = String.format(Locale.ROOT, "%.2f GB", sizeBytes / 1e9)
}

/** The Auto mode rule, shared by the app, the remote and restore. */
object LunModes {
    /**
     * An `.iso` is a CD-ROM when it fits one ([CdromLimits]); everything else, and an ISO too big or too
     * small for a CD-ROM, is a read-only disk. With the size unknown ([sizeBytes] null) the extension
     * decides, and root's size check still refuses an oversized CD-ROM.
     */
    fun auto(
        path: String,
        sizeBytes: Long?,
    ): LunMode {
        val iso = path.endsWith(".iso", ignoreCase = true)
        return if (iso && (sizeBytes == null || CdromLimits.fits(sizeBytes))) LunMode.CDROM else LunMode.DISK_RO
    }
}
