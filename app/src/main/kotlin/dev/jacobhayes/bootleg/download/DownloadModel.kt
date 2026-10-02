package dev.jacobhayes.bootleg.download

import dev.jacobhayes.bootleg.core.catalog.Release
import kotlinx.serialization.Serializable

object DownloadLimits {
    const val CONCURRENT_DOWNLOADS_MAX = 2
    const val ATTEMPTS_PER_RUN_MAX = 3
    const val RESCHEDULES_MAX = 5
    const val RECORDS_MAX = 64
    const val BUFFER_BYTES = 1 shl 16
    const val PROGRESS_INTERVAL_MS = 500L
    const val CONNECT_TIMEOUT_MS = 20_000L
    const val READ_TIMEOUT_MS = 30_000L

    /** Keep this much free beyond the image so the phone doesn't run dry. */
    const val FREE_SPACE_MARGIN_BYTES = 512L * 1024 * 1024
}

@Serializable
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, VERIFYING, COMPLETED, FAILED }

@Serializable
enum class VerifyState {
    PENDING,
    VERIFIED,
    MISMATCH,

    /** The distro publishes no usable checksum; the file is kept but marked unverified. */
    UNAVAILABLE,
}

/** One download, persisted as JSON so it survives process death and resumes from its `.partial`. */
@Serializable
data class DownloadRecord(
    /** The stored file name; also the record key. */
    val fileName: String,
    val title: String,
    val url: String,
    /** Hash of what [url] serves (for a zipped release, the archive). */
    val expectedSha256: String? = null,
    /** Sums file listing what [url] serves. */
    val sumsUrl: String? = null,
    /** For a zipped release: the entry to extract and store as [fileName]. */
    val archiveEntry: String? = null,
    /** For a zipped release: the extracted image's hash, if upstream publishes it. */
    val imageSha256: String? = null,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val verify: VerifyState = VerifyState.PENDING,
    val etag: String? = null,
    val lastModified: String? = null,
    val error: String? = null,
    val rescheduleCount: Int = 0,
    val createdAtMs: Long,
    /**
     * Unique among stored records (0 until [DownloadLimits.RECORDS_MAX]), assigned by [DownloadStore.put].
     * Job and notification ids derive from it, so two downloads never share one.
     */
    val idSlot: Int = UNASSIGNED_ID_SLOT,
) {
    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING || status == DownloadStatus.VERIFYING

    val progressFraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }

    companion object {
        const val UNASSIGNED_ID_SLOT = -1

        fun from(
            release: Release,
            distroName: String,
            nowMs: Long,
        ): DownloadRecord {
            val variant =
                release.variant
                    .takeIf { it.isNotBlank() }
                    ?.let { " $it" }
                    .orEmpty()
            return DownloadRecord(
                fileName = release.fileName,
                title = "$distroName ${release.version}$variant",
                url = release.url,
                expectedSha256 = (if (release.archiveEntry != null) release.archiveSha256 else release.sha256)?.lowercase(),
                sumsUrl = release.sumsUrl,
                archiveEntry = release.archiveEntry,
                imageSha256 = release.sha256?.takeIf { release.archiveEntry != null }?.lowercase(),
                totalBytes = release.sizeBytes,
                createdAtMs = nowMs,
            )
        }
    }
}
