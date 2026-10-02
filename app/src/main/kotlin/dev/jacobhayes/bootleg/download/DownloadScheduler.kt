package dev.jacobhayes.bootleg.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle

/** Schedules user-initiated data transfer jobs (API 34+); older releases get a plain network job. */
class DownloadScheduler(
    private val context: Context,
) {
    private val scheduler = context.getSystemService(JobScheduler::class.java)

    fun schedule(
        record: DownloadRecord,
        wifiOnly: Boolean,
    ): Boolean {
        val network =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .apply { if (wifiOnly) addTransportType(NetworkCapabilities.TRANSPORT_WIFI) }
                .build()
        val remainingBytes =
            record.totalBytes?.let { (it - record.downloadedBytes).coerceAtLeast(0L) } ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
        val builder =
            JobInfo
                .Builder(jobId(record), ComponentName(context, DownloadJobService::class.java))
                .setRequiredNetwork(network)
                .setEstimatedNetworkBytes(remainingBytes, 0)
                .setExtras(PersistableBundle().apply { putString(DownloadJobService.EXTRA_FILE_NAME, record.fileName) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) builder.setUserInitiated(true)
        return scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
    }

    fun cancel(record: DownloadRecord) = scheduler.cancel(jobId(record))

    companion object {
        private const val JOB_ID_BASE = 10_000

        fun jobId(record: DownloadRecord): Int {
            require(record.idSlot in 0 until DownloadLimits.RECORDS_MAX) { "no id slot for ${record.fileName}" }
            return JOB_ID_BASE + record.idSlot
        }
    }
}
