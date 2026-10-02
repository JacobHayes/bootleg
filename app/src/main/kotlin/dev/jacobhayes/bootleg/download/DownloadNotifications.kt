package dev.jacobhayes.bootleg.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.jacobhayes.bootleg.MainActivity
import dev.jacobhayes.bootleg.R

/** Silent download notifications: low importance, no sound, no vibration, alert once. */
class DownloadNotifications(
    private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Image download progress"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    fun progress(record: DownloadRecord): Notification {
        val builder = base(record)
        when (record.status) {
            DownloadStatus.VERIFYING -> {
                builder.setContentText("Verifying checksum…").setProgress(0, 0, true)
            }

            DownloadStatus.QUEUED -> {
                builder.setContentText(record.error ?: "Waiting to start…").setProgress(0, 0, true)
            }

            else -> {
                val fraction = record.progressFraction
                val text = progressText(record)
                if (fraction == null) {
                    builder.setContentText(text).setProgress(0, 0, true)
                } else {
                    builder.setContentText(text).setProgress(PROGRESS_SCALE, (fraction * PROGRESS_SCALE).toInt(), false)
                }
            }
        }
        return builder.setOngoing(true).build()
    }

    fun postFinished(record: DownloadRecord) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val text =
            when {
                record.status == DownloadStatus.FAILED -> "Failed: ${record.error ?: "unknown error"}"
                record.verify == VerifyState.VERIFIED -> "Downloaded and verified"
                record.verify == VerifyState.UNAVAILABLE -> "Downloaded (no published checksum)"
                else -> "Downloaded"
            }
        manager.notify(finishedId(record), base(record).setContentText(text).setAutoCancel(true).build())
    }

    private fun base(record: DownloadRecord): Notification.Builder =
        Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(record.title)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DOWNLOADS),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

    companion object {
        const val CHANNEL_ID = "downloads"
        private const val PROGRESS_SCALE = 1000
        private const val PROGRESS_ID_BASE = 10_000
        private const val FINISHED_ID_BASE = 20_000

        fun progressId(record: DownloadRecord): Int = PROGRESS_ID_BASE + checkedSlot(record)

        fun finishedId(record: DownloadRecord): Int = FINISHED_ID_BASE + checkedSlot(record)

        private fun checkedSlot(record: DownloadRecord): Int {
            require(record.idSlot in 0 until DownloadLimits.RECORDS_MAX) { "no id slot for ${record.fileName}" }
            return record.idSlot
        }

        fun progressText(record: DownloadRecord): String {
            val total = record.totalBytes
            return if (total != null) {
                "${formatBytes(record.downloadedBytes)} of ${formatBytes(total)}"
            } else {
                formatBytes(record.downloadedBytes)
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    require(bytes >= 0) { "bytes must be >= 0, was $bytes" }
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return when {
        unit == 0 -> "$bytes B"
        value >= 100 -> "%.0f %s".format(value, units[unit])
        else -> "%.1f %s".format(value, units[unit])
    }
}
