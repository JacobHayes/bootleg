package dev.jacobhayes.bootleg.remote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.jacobhayes.bootleg.BootlegApp
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground (connectedDevice) while Remote is on, so the tailnet node
 * keeps serving with the app in the background. Started only from a visible user action (the
 * Remote switch); after a process death the system restarts it (sticky) if Remote is still on.
 * The node itself lives in [RemoteManager].
 */
class RemoteService : Service() {
    private val app by lazy { BootlegApp.from(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(
            RemoteNotifications.SERVICE_NOTIFICATION_ID,
            app.remote.serviceNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val remote = app.remote
        if (!remote.isEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        app.appScope.launch { remote.startNode() }
        return START_STICKY
    }

    override fun onDestroy() {
        // The app scope outlives the service; stopping the node waits for Go to close.
        val remote = app.remote
        app.appScope.launch { remote.stopNode() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BootlegRemoteService"

        /** False if Android refused (the app isn't in the foreground). */
        fun start(context: Context): Boolean =
            try {
                context.startForegroundService(Intent(context, RemoteService::class.java))
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException when started from the background.
                Log.w(TAG, "cannot start the remote foreground service now: ${e.message}")
                false
            }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteService::class.java))
        }
    }
}
