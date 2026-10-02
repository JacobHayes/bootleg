package dev.jacobhayes.bootleg.remote

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.io.File

/** The settings keep-awake overrides, saved before the first change so they can be put back. */
@Serializable
data class KeepAwakePrior(
    val stayOnWhilePluggedIn: Int,
)

/** Root knobs (BootlegRootService.setKeepAwake). */
fun interface KeepAwakeRoot {
    suspend fun set(
        stayOnWhilePluggedIn: Int,
        lowLatency: Boolean,
        wakeScreen: Boolean,
    )
}

/**
 * Keeps the phone responsive while at least one authenticated remote session is live (SPEC
 * "Keep-awake"): a low-latency Wi-Fi lock and a partial wake lock in the app, and through root
 * forced Wi-Fi low-latency mode, `stay_on_while_plugged_in` = 7 and a screen wake. The prior
 * `stay_on_while_plugged_in` is persisted before it is changed and restored when the last session
 * ends, or on the next process start if the process died first ([restoreLeftover]). Forced
 * low-latency mode can't be read back, so restoring turns it off (its default).
 *
 * Not thread-safe: call from one coroutine (the remote manager's).
 */
class KeepAwake(
    private val context: Context,
    directory: File,
    private val root: KeepAwakeRoot,
) {
    private val priorFile = AtomicJsonFile(File(directory, PRIOR_FILE_NAME), KeepAwakePrior.serializer().nullable) { null }
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    var active: Boolean = false
        private set

    suspend fun setActive(wanted: Boolean) {
        if (wanted == active) return
        if (wanted) acquire() else release()
    }

    /** Puts back settings a previous process changed and never restored (it died while a session was live). */
    suspend fun restoreLeftover() {
        if (active) return
        val prior = priorFile.load() ?: return
        Log.i(TAG, "restoring keep-awake settings left over from a previous process")
        restore(prior)
    }

    @SuppressLint("WakelockTimeout") // Held exactly while a remote session is connected; released when it ends.
    private suspend fun acquire() {
        active = true
        val wifiManager = context.getSystemService(WifiManager::class.java)
        wifiLock =
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        wakeLock =
            context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:remote").apply {
                setReferenceCounted(false)
                acquire()
            }
        // Persist before touching anything, so a crash in between can still be undone.
        if (priorFile.load() == null) {
            val current = Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)
            priorFile.save(KeepAwakePrior(current))
        }
        runCatching { root.set(STAY_ON_ALL_POWER_SOURCES, lowLatency = true, wakeScreen = true) }
            .onFailure { Log.w(TAG, "root keep-awake failed; continuing with the app locks only", it) }
    }

    private suspend fun release() {
        active = false
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        priorFile.load()?.let { restore(it) }
    }

    private suspend fun restore(prior: KeepAwakePrior) {
        runCatching { root.set(prior.stayOnWhilePluggedIn, lowLatency = false, wakeScreen = false) }
            .onSuccess { priorFile.delete() }
            .onFailure { Log.w(TAG, "restoring keep-awake settings failed; will retry on the next start", it) }
    }

    companion object {
        private const val TAG = "BootlegKeepAwake"
        private const val PRIOR_FILE_NAME = "keepawake-prior.json"

        /** Cheap check (no root, no managers) for [restoreLeftover] at process start. */
        fun hasLeftover(context: Context): Boolean = File(File(context.noBackupFilesDir, "remote"), PRIOR_FILE_NAME).isFile

        /** AC | USB | wireless. */
        private const val STAY_ON_ALL_POWER_SOURCES = 7
    }
}
