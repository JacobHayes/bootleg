package dev.jacobhayes.bootleg.download

import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.storage.StoragePaths
import kotlinx.coroutines.flow.StateFlow

/** UI-facing download operations. All mutations go through [store]; transfers run in [DownloadJobService]. */
class Downloads(
    val store: DownloadStore,
    val engine: DownloadEngine,
    val notifications: DownloadNotifications,
    private val scheduler: DownloadScheduler,
    private val wifiOnly: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    val records: StateFlow<Map<String, DownloadRecord>> = store.records

    sealed interface EnqueueResult {
        data object Started : EnqueueResult

        data object AlreadyActive : EnqueueResult

        data object AlreadyDownloaded : EnqueueResult

        data class Rejected(
            val reason: String,
        ) : EnqueueResult
    }

    fun enqueue(
        release: Release,
        distroName: String,
    ): EnqueueResult {
        if (!StoragePaths.isValidImageName(release.fileName)) return EnqueueResult.Rejected("Bad file name: ${release.fileName}")
        val existing = store[release.fileName]
        if (existing != null && existing.isActive) return EnqueueResult.AlreadyActive
        if (engine.finalFile(release.fileName).exists()) return EnqueueResult.AlreadyDownloaded
        val record =
            existing?.copy(status = DownloadStatus.QUEUED, error = null, rescheduleCount = 0, verify = VerifyState.PENDING)
                ?: DownloadRecord.from(release, distroName, nowMs())
        return schedule(store.put(record))
    }

    fun pause(fileName: String) {
        val record = store.update(fileName) { if (it.isActive) it.copy(status = DownloadStatus.PAUSED) else it } ?: return
        scheduler.cancel(record)
    }

    fun resume(fileName: String): EnqueueResult {
        val record =
            store.update(fileName) {
                it.copy(status = DownloadStatus.QUEUED, error = null, rescheduleCount = 0, verify = VerifyState.PENDING)
            } ?: return EnqueueResult.Rejected("Unknown download")
        return schedule(record)
    }

    /** Stops the transfer, deletes the partial file and forgets the download. */
    fun cancel(fileName: String) {
        val record = store[fileName]
        store.remove(fileName)
        record?.let(scheduler::cancel)
        engine.partialFile(fileName).delete()
    }

    /** Removes a finished/failed entry from the list; the downloaded image stays in the library. */
    fun dismiss(fileName: String) {
        val record = store[fileName] ?: return
        check(!record.isActive) { "cannot dismiss an active download: $fileName" }
        if (record.status != DownloadStatus.COMPLETED) engine.partialFile(fileName).delete()
        store.remove(fileName)
    }

    private fun schedule(record: DownloadRecord): EnqueueResult {
        if (scheduler.schedule(record, wifiOnly())) return EnqueueResult.Started
        store.update(record.fileName) { it.copy(status = DownloadStatus.FAILED, error = "The system refused to schedule the download") }
        return EnqueueResult.Rejected("The system refused to schedule the download")
    }
}
