package dev.jacobhayes.bootleg.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.jacobhayes.bootleg.MainActivity
import dev.jacobhayes.bootleg.core.remote.PendingApproval
import dev.jacobhayes.bootleg.ui.Tab

/**
 * Remote's notifications, on one silent channel: the foreground-service notification and a
 * "waiting for approval" one. Both only open the app on the Remote tab; approving needs the
 * unlocked app (a notification action could be triggered from the lock screen).
 */
class RemoteNotifications(
    private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote control", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Remote control status and devices waiting for approval"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun service(
        title: String,
        text: String,
    ): Notification =
        Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openRemoteTab(REQUEST_SERVICE))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    fun updateService(
        title: String,
        text: String,
    ) = manager.notify(SERVICE_NOTIFICATION_ID, service(title, text))

    fun showPending(pending: List<PendingApproval>) {
        if (pending.isEmpty()) {
            manager.cancel(PENDING_NOTIFICATION_ID)
            return
        }
        val names = pending.joinToString(", ") { it.displayName.ifBlank { it.nodeId }.take(DISPLAY_NAME_LENGTH_MAX) }
        manager.notify(
            PENDING_NOTIFICATION_ID,
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(if (pending.size == 1) "A device wants remote control" else "${pending.size} devices want remote control")
                .setContentText("$names · open Bootleg to approve or deny")
                .setContentIntent(openRemoteTab(REQUEST_PENDING))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun cancelPending() = manager.cancel(PENDING_NOTIFICATION_ID)

    private fun openRemoteTab(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, Tab.REMOTE.name)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val CHANNEL_ID = "remote"
        const val SERVICE_NOTIFICATION_ID = 20
        private const val PENDING_NOTIFICATION_ID = 21
        private const val REQUEST_SERVICE = 20
        private const val REQUEST_PENDING = 21

        /** Node names are untrusted display text. */
        private const val DISPLAY_NAME_LENGTH_MAX = 40
    }
}
