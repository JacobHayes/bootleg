package dev.jacobhayes.bootleg.remote

import dev.jacobhayes.bootleg.catalog.CatalogRepository
import dev.jacobhayes.bootleg.catalog.CatalogState
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.remote.CatalogDistroSnapshot
import dev.jacobhayes.bootleg.core.remote.CatalogSnapshot
import dev.jacobhayes.bootleg.core.remote.DownloadOutcome
import dev.jacobhayes.bootleg.core.remote.DownloadPort
import dev.jacobhayes.bootleg.core.remote.RemoteDownload
import dev.jacobhayes.bootleg.core.remote.RemoteDownloadStatus
import dev.jacobhayes.bootleg.core.remote.RemoteLimits
import dev.jacobhayes.bootleg.download.DownloadLimits
import dev.jacobhayes.bootleg.download.DownloadRecord
import dev.jacobhayes.bootleg.download.DownloadStatus
import dev.jacobhayes.bootleg.download.Downloads
import dev.jacobhayes.bootleg.download.VerifyState
import dev.jacobhayes.bootleg.storage.StoragePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The catalog and downloads as the remote controller sees them: the same [CatalogRepository] and
 * [Downloads] the Catalog and Downloads screens use, so a remote start is a phone start. Only the
 * repository's state, staleness check and refresh are used.
 */
class RemoteDownloads(
    private val catalogRepository: CatalogRepository,
    private val downloadList: Downloads,
    private val storeDir: File,
    private val freeBytes: (File) -> Long,
    private val scope: CoroutineScope,
) : DownloadPort {
    override val catalog: Flow<CatalogSnapshot> = catalogRepository.state.map(::snapshotOf).distinctUntilChanged()

    override val downloads: Flow<List<RemoteDownload>> =
        downloadList.records.map { records -> records.values.sortedBy { it.createdAtMs }.map(::remoteDownloadOf) }.distinctUntilChanged()

    override suspend fun start(
        release: Release,
        distroName: String,
    ): DownloadOutcome {
        // No-ops answer before the resource checks; enqueueFromRemote checks both again atomically.
        if (downloadList.store[release.fileName]?.isActive == true) return DownloadOutcome.AlreadyActive
        if (withContext(Dispatchers.IO) { StoragePaths.existsLive(downloadList.engine.finalFile(release.fileName)) }) {
            return DownloadOutcome.AlreadyDownloaded
        }
        lowStorage(release)?.let { return DownloadOutcome.StorageLow(it) }
        return when (val result = downloadList.enqueueFromRemote(release, distroName, RemoteLimits.DOWNLOAD_ACTIVE_COUNT_MAX)) {
            Downloads.EnqueueResult.Started -> DownloadOutcome.Started
            Downloads.EnqueueResult.AlreadyActive -> DownloadOutcome.AlreadyActive
            Downloads.EnqueueResult.AlreadyDownloaded -> DownloadOutcome.AlreadyDownloaded
            is Downloads.EnqueueResult.Rejected ->
                if (result.tooMany) DownloadOutcome.TooMany(result.reason) else DownloadOutcome.Rejected(result.reason)
        }
    }

    override suspend fun cancel(fileName: String): DownloadOutcome {
        val record = downloadList.store[fileName]
        if (record == null || record.status == DownloadStatus.COMPLETED) return DownloadOutcome.Cancelled(false)
        withContext(Dispatchers.IO) { downloadList.cancel(fileName) }
        return DownloadOutcome.Cancelled(true)
    }

    override fun refreshCatalogIfStale() {
        scope.launch {
            if (catalogRepository.state.value.entries
                    .isEmpty()
            ) {
                catalogRepository.loadCache()
            }
            if (catalogRepository.isStale(CATALOG_MAX_AGE_MS)) catalogRepository.refresh()
        }
    }

    /** What's left to fetch (a paused download resumes) plus the engine's margin must fit; null if it does. */
    private suspend fun lowStorage(release: Release): String? =
        withContext(Dispatchers.IO) {
            val partialBytes = downloadList.engine.partialFile(release.fileName).length()
            val remainingBytes = ((release.sizeBytes ?: 0L) - partialBytes).coerceAtLeast(0L)
            // A size no phone could hold must not wrap around into a pass.
            if (remainingBytes > Long.MAX_VALUE - DownloadLimits.FREE_SPACE_MARGIN_BYTES) return@withContext "the image is too large"
            val neededBytes = remainingBytes + DownloadLimits.FREE_SPACE_MARGIN_BYTES
            val availableBytes = runCatching { freeBytes(storeDir) }.getOrDefault(Long.MAX_VALUE)
            if (availableBytes >=
                neededBytes
            ) {
                null
            } else {
                "needs ${neededBytes / BYTES_PER_MIB} MiB free, the phone has ${availableBytes / BYTES_PER_MIB} MiB"
            }
        }

    companion object {
        /** The Catalog screen's rule: a distro fetched more than 6 hours ago is refreshed when looked at. */
        const val CATALOG_MAX_AGE_MS = 6L * 60 * 60 * 1000
        private const val BYTES_PER_MIB = 1024L * 1024

        fun snapshotOf(state: CatalogState): CatalogSnapshot =
            CatalogSnapshot(
                distros = state.entries.map { CatalogDistroSnapshot(it.distro, it.releases, it.fetchedAtMs, it.error) },
                refreshing = state.refreshing,
            )

        fun remoteDownloadOf(record: DownloadRecord): RemoteDownload =
            RemoteDownload(
                fileName = record.fileName,
                title = record.title,
                status =
                    when (record.status) {
                        DownloadStatus.QUEUED -> RemoteDownloadStatus.QUEUED
                        DownloadStatus.RUNNING -> RemoteDownloadStatus.RUNNING
                        DownloadStatus.PAUSED -> RemoteDownloadStatus.PAUSED
                        DownloadStatus.VERIFYING -> RemoteDownloadStatus.VERIFYING
                        DownloadStatus.COMPLETED -> RemoteDownloadStatus.COMPLETED
                        DownloadStatus.FAILED -> RemoteDownloadStatus.FAILED
                    },
                downloadedBytes = record.downloadedBytes,
                totalBytes = record.totalBytes,
                verified = record.verify == VerifyState.VERIFIED,
                error = record.error,
            )
    }
}
