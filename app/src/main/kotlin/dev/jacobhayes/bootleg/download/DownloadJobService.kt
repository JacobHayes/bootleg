package dev.jacobhayes.bootleg.download

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import dev.jacobhayes.bootleg.BootlegApp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Thin JobScheduler shell around [DownloadEngine]; one job per download. */
class DownloadJobService : JobService() {
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as BootlegApp
        val fileName = params.extras.getString(EXTRA_FILE_NAME) ?: return false
        val downloads = app.downloads
        val record = downloads.store[fileName] ?: return false
        val notifications = downloads.notifications
        val notificationId = DownloadNotifications.progressId(record)
        val showProgress = { current: DownloadRecord ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setNotification(params, notificationId, notifications.progress(current), JOB_END_NOTIFICATION_POLICY_REMOVE)
                updateTransferredNetworkBytes(params, current.downloadedBytes, 0)
            }
        }
        showProgress(record)
        val job =
            app.appScope.launch(start = CoroutineStart.LAZY) {
                var reschedule = false
                try {
                    reschedule = downloads.engine.run(fileName, showProgress) == DownloadEngine.Outcome.RESCHEDULE
                    downloads.store[fileName]?.let { if (!it.isActive) notifications.postFinished(it) }
                } finally {
                    // Absent means onStopJob already ran, and JobScheduler forbids jobFinished after that.
                    if (running.remove(params.jobId) != null) jobFinished(params, reschedule)
                }
            }
        running[params.jobId] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        val stoppedByUserOrApp =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                params.stopReason in setOf(JobParameters.STOP_REASON_USER, JobParameters.STOP_REASON_CANCELLED_BY_APP)
        val fileName = params.extras.getString(EXTRA_FILE_NAME) ?: return false
        val store = (application as BootlegApp).downloads.store
        if (stoppedByUserOrApp) {
            store.update(fileName) { if (it.isActive) it.copy(status = DownloadStatus.PAUSED) else it }
            return false
        }
        // Lost network / quota: let JobScheduler bring it back; the .partial resumes it.
        store.update(fileName) { if (it.isActive) it.copy(status = DownloadStatus.QUEUED, error = "Waiting for network") else it }
        return true
    }

    companion object {
        const val EXTRA_FILE_NAME = "file_name"
    }
}
