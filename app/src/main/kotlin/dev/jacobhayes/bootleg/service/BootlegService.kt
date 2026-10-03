package dev.jacobhayes.bootleg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.jacobhayes.bootleg.BootlegApp
import dev.jacobhayes.bootleg.MainActivity
import dev.jacobhayes.bootleg.R
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that runs while anything is exported: shows the ongoing notification and
 * reconciles on USB state changes. The kernel serves the disks; this only watches.
 */
class BootlegService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backend by lazy { BootlegApp.from(this).systemBackend }
    private var reconcileJob: Job? = null
    private var watching = false
    private var reconciledOnce = false
    private var usbStateRegistered = false

    private val usbStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = scheduleReconcile()
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Reconcile runs on USB_STATE (sticky, so also right after registering), not on every start:
        // the backend pokes this service after each gadget call.
        when (intent?.action) {
            ACTION_EJECT_ALL -> scope.launch { ejectAllWithFeedback(this@BootlegService, backend) }
            ACTION_FORGET ->
                scope.launch {
                    // After a reboot without opt-in restore: drop the journal before any reconcile can re-export it.
                    // Ejected and failed slots (and HID) go too, or they would come back as stale drives.
                    runCatching {
                        backend.refreshGadget()
                        val journal = backend.journal.value
                        if (backend.hasExports || !journal.desired.isEmpty || journal.slotIntents.isNotEmpty()) backend.bootlegOff()
                    }.onFailure { Log.w(TAG, "clearing stale exports failed", it) }
                    watchUsbState()
                }
            ACTION_RESTORE ->
                scope.launch {
                    // The journal holds the mouse layout of the last apply; the restore follows the setting.
                    runCatching {
                        backend.refreshGadget()
                        val app = BootlegApp.from(this@BootlegService)
                        val hid = backend.journal.value.desired.hid
                        if (hid.mouse && hid.mouseLayout != app.prefs.mouseLayout.value) app.hid.setSpec(hid)
                    }.onFailure { Log.w(TAG, "applying the mouse layout setting failed", it) }
                    watchUsbState()
                }
            else -> watchUsbState()
        }
        if (!watching) {
            watching = true
            scope.launch {
                backend.gadgetState.combine(backend.journal) { state, _ -> state }.collect { state ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
                    if (reconciledOnce && !backend.hasExports) stopSelf()
                }
            }
            scope.launch { pollHostEjects() }
        }
        return START_STICKY
    }

    private fun watchUsbState() {
        if (usbStateRegistered) return
        usbStateRegistered = true
        registerReceiver(usbStateReceiver, IntentFilter(ACTION_USB_STATE), RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        if (usbStateRegistered) unregisterReceiver(usbStateReceiver)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * A host eject empties the LUN without re-enumerating, so no USB_STATE arrives: poll the root
     * side (cheap configfs reads) while something is exported so the UI and notification show it.
     */
    private suspend fun pollHostEjects() {
        while (true) {
            delay(HOST_EJECT_POLL_INTERVAL_MS)
            if (!backend.hasExports) continue
            try {
                backend.refreshGadget()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "polling for host ejects failed", e)
            }
        }
    }

    /** Debounced: USB_STATE arrives in bursts while the HAL re-enumerates. */
    private fun scheduleReconcile() {
        reconcileJob?.cancel()
        reconcileJob =
            scope.launch {
                delay(RECONCILE_DEBOUNCE_MS)
                val result = backend.reconcile()
                Log.i(TAG, "reconcile: ${result::class.simpleName}")
                reconciledOnce = true
                if (!backend.hasExports) stopSelf()
            }
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_exports), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: GadgetState?): Notification {
        val loaded =
            state
                ?.luns
                .orEmpty()
                .filterValues { it.file.isNotEmpty() }
                .toSortedMap()
        val title =
            if (loaded.isEmpty()) {
                getString(
                    R.string.notification_idle,
                )
            } else {
                resources.getQuantityString(R.plurals.notification_exported, loaded.size, loaded.size)
            }
        val text =
            loaded.entries.joinToString("\n") { (lun, lunState) ->
                val mode =
                    when {
                        lunState.cdrom -> "CD"
                        lunState.ro -> "RO"
                        else -> "RW"
                    }
                "Slot ${lun + 1} · ${lunState.file.substringAfterLast('/')} ($mode)"
            }
        val open =
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val ejectAll =
            PendingIntent.getService(
                this,
                1,
                Intent(this, BootlegService::class.java).setAction(ACTION_EJECT_ALL),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_drive)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply {
                if (loaded.isNotEmpty()) {
                    addAction(Notification.Action.Builder(null, getString(R.string.notification_eject_all), ejectAll).build())
                }
            }.build()
    }

    companion object {
        private const val TAG = "BootlegService"
        private const val CHANNEL_ID = "exports"
        private const val NOTIFICATION_ID = 1
        private const val RECONCILE_DEBOUNCE_MS = 1_000L
        private const val HOST_EJECT_POLL_INTERVAL_MS = 3_000L
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val ACTION_EJECT_ALL = "dev.jacobhayes.bootleg.action.EJECT_ALL"

        /** Boot with restore enabled: reconcile re-exports the journal's EXPORTED slots. */
        const val ACTION_RESTORE = "dev.jacobhayes.bootleg.action.RESTORE"

        /** Boot without restore: forget the previous boot's exports. */
        const val ACTION_FORGET = "dev.jacobhayes.bootleg.action.FORGET"

        /** Starts (or pokes) the service; it reconciles and stops itself once nothing is exported. */
        fun start(
            context: Context,
            action: String? = null,
        ) {
            try {
                context.startForegroundService(Intent(context, BootlegService::class.java).setAction(action))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException when started from the background.
                Log.w(TAG, "cannot start foreground service now", e)
            }
        }
    }
}
