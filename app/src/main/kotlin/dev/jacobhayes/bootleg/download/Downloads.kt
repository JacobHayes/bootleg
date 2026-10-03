package dev.jacobhayes.bootleg.download

import android.util.Log
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
    /** For downloads the remote starts while the app is in the background; null where root isn't available. */
    private val backgroundScheduling: BackgroundJobScheduling? = null,
) {
    /** Admission, cancel and a refused schedule's failure are one at a time, so one never acts on another's record. */
    private val admissionLock = Any()
    private var admissionCount = 0L

    init {
        // A queued record whose job is gone (the process died between admission and scheduling, or the
        // phone rebooted) would stay "active" forever; it becomes a paused download to resume instead.
        for (record in store.records.value.values) {
            if (record.status == DownloadStatus.QUEUED && !scheduler.isScheduled(record)) {
                store.update(record.fileName) {
                    if (it.status ==
                        DownloadStatus.QUEUED
                    ) {
                        it.copy(status = DownloadStatus.PAUSED, error = "Interrupted; resume it")
                    } else {
                        it
                    }
                }
            }
        }
    }

    val records: StateFlow<Map<String, DownloadRecord>> = store.records

    sealed interface EnqueueResult {
        data object Started : EnqueueResult

        data object AlreadyActive : EnqueueResult

        data object AlreadyDownloaded : EnqueueResult

        data class Rejected(
            val reason: String,
            /** Too many active downloads (a remote start's bound) or the download list is full. */
            val tooMany: Boolean = false,
        ) : EnqueueResult
    }

    fun enqueue(
        release: Release,
        distroName: String,
    ): EnqueueResult {
        val record =
            when (val admitted = admit(release, distroName, activeCountMax = null)) {
                is Admission.Record -> admitted.record
                is Admission.Answer -> return admitted.result
            }
        return schedule(record)
    }

    /**
     * [enqueue] for the remote: at most [activeCountMax] active downloads (checked and recorded
     * atomically with other remote starts), and when Android refuses the job because the app is in
     * the background, the same job is scheduled through [BackgroundJobScheduling].
     */
    suspend fun enqueueFromRemote(
        release: Release,
        distroName: String,
        activeCountMax: Int,
    ): EnqueueResult {
        require(activeCountMax > 0) { "activeCountMax must be positive" }
        val record =
            when (val admitted = admit(release, distroName, activeCountMax)) {
                is Admission.Record -> admitted.record
                is Admission.Answer -> return admitted.result
            }
        if (trySchedule(record)) return EnqueueResult.Started
        val background = backgroundScheduling ?: return refused(record)
        if (background.schedule(record.fileName, record.attemptId)) {
            // The L4 remote check reads this to tell which path scheduled the job.
            Log.i(TAG, "scheduled ${record.fileName} through root's background broadcast")
            return EnqueueResult.Started
        }
        return refused(record)
    }

    /** Inside root's privileged broadcast: schedules the job for a download that's still waiting for it. */
    fun onBackgroundScheduleBroadcast(
        fileName: String,
        token: String,
    ) {
        backgroundScheduling?.onBroadcast(fileName, token) { attemptId ->
            val record = store[fileName] ?: return@onBroadcast false
            if (record.status != DownloadStatus.QUEUED || record.attemptId != attemptId) return@onBroadcast false
            trySchedule(record)
        }
    }

    private sealed interface Admission {
        data class Record(
            val record: DownloadRecord,
        ) : Admission

        data class Answer(
            val result: EnqueueResult,
        ) : Admission
    }

    /** Already active or downloaded first, then the bounds; stores the queued record. */
    private fun admit(
        release: Release,
        distroName: String,
        activeCountMax: Int?,
    ): Admission =
        synchronized(admissionLock) {
            if (!StoragePaths.isValidImageName(release.fileName)) {
                return Admission.Answer(EnqueueResult.Rejected("Bad file name: ${release.fileName}"))
            }
            val existing = store[release.fileName]
            if (existing != null && existing.isActive) return Admission.Answer(EnqueueResult.AlreadyActive)
            if (StoragePaths.existsLive(engine.finalFile(release.fileName))) return Admission.Answer(EnqueueResult.AlreadyDownloaded)
            val records = store.records.value
            if (activeCountMax != null && records.values.count { it.isActive } >= activeCountMax) {
                return Admission.Answer(EnqueueResult.Rejected("$activeCountMax downloads are already queued or running", tooMany = true))
            }
            if (existing == null && records.size >= DownloadLimits.RECORDS_MAX) {
                return Admission.Answer(
                    EnqueueResult.Rejected("The download list is full; remove finished downloads first", tooMany = true),
                )
            }
            val record =
                existing?.copy(status = DownloadStatus.QUEUED, error = null, rescheduleCount = 0, verify = VerifyState.PENDING)
                    ?: DownloadRecord.from(release, distroName, nowMs())
            Admission.Record(store.put(record.copy(attemptId = nextAttemptId())))
        }

    /** Unique within the process and across restarts (wall clock plus a counter); held under [admissionLock]. */
    private fun nextAttemptId(): String = "${nowMs()}-${++admissionCount}"

    fun pause(fileName: String) {
        val record = store.update(fileName) { if (it.isActive) it.copy(status = DownloadStatus.PAUSED) else it } ?: return
        scheduler.cancel(record)
    }

    fun resume(fileName: String): EnqueueResult {
        val record =
            synchronized(admissionLock) {
                store.update(fileName) {
                    it.copy(
                        status = DownloadStatus.QUEUED,
                        error = null,
                        rescheduleCount = 0,
                        verify = VerifyState.PENDING,
                        attemptId = nextAttemptId(),
                    )
                }
            } ?: return EnqueueResult.Rejected("Unknown download")
        return schedule(record)
    }

    /** Stops the transfer, deletes the partial file and forgets the download. */
    fun cancel(fileName: String) {
        synchronized(admissionLock) {
            // The job goes before the record, so its id slot can't be handed to a new download first.
            store[fileName]?.let(scheduler::cancel)
            store.remove(fileName)
            engine.partialFile(fileName).delete()
        }
    }

    /** Removes a finished/failed entry from the list; the downloaded image stays in the library. */
    fun dismiss(fileName: String) {
        val record = store[fileName] ?: return
        check(!record.isActive) { "cannot dismiss an active download: $fileName" }
        if (record.status != DownloadStatus.COMPLETED) engine.partialFile(fileName).delete()
        store.remove(fileName)
    }

    /**
     * Drops completed records whose image is no longer in the library. [libraryNames] is a fresh
     * listing; a name missing from it is only forgotten once the file is really gone, so a listing
     * taken just before a download finished can't forget it.
     */
    fun forgetDeletedImages(libraryNames: Set<String>): Set<String> =
        store.forgetMissingImages { name -> name in libraryNames || StoragePaths.existsLive(engine.finalFile(name)) }

    private fun schedule(record: DownloadRecord): EnqueueResult {
        if (trySchedule(record)) return EnqueueResult.Started
        return refused(record)
    }

    private fun trySchedule(record: DownloadRecord): Boolean = runCatching { scheduler.schedule(record, wifiOnly()) }.getOrDefault(false)

    /** Fails only this attempt: a record cancelled and started again meanwhile is someone else's. */
    private fun refused(record: DownloadRecord): EnqueueResult {
        synchronized(admissionLock) {
            store.update(record.fileName) {
                if (it.attemptId == record.attemptId && it.status == DownloadStatus.QUEUED) {
                    it.copy(status = DownloadStatus.FAILED, error = "The system refused to schedule the download")
                } else {
                    it
                }
            }
        }
        return EnqueueResult.Rejected("The system refused to schedule the download")
    }

    private companion object {
        const val TAG = "BootlegDownloads"
    }
}
