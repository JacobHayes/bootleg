package dev.jacobhayes.bootleg.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import dev.jacobhayes.bootleg.Prefs
import dev.jacobhayes.bootleg.core.tether.TetherChoice
import dev.jacobhayes.bootleg.core.tether.TetherLimits
import dev.jacobhayes.bootleg.core.tether.TetherPolicy
import dev.jacobhayes.bootleg.core.tether.TetherResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bootleg's USB tethering as the app sees it: the sticky `TETHER_STATE_CHANGED` broadcast for
 * display (no polling), the root call for changes, and the remembered choice ([TetherChoice]) that
 * restore after reboot reads. Everything that touches [requestCount], the state or the choice runs
 * on the main thread, so observations and request completions are applied one at a time.
 */
class UsbTethering(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val rootSet: suspend (on: Boolean) -> TetherResult,
    private val rootTethered: suspend () -> Boolean,
) {
    /** The pending confirmation of an on-to-off broadcast; main thread only. */
    private var confirmJob: Job? = null

    private val stateFlow = MutableStateFlow<Boolean?>(null)
    private val busyFlow = MutableStateFlow(false)

    /** On, off, or null until Android has reported it. */
    val state: StateFlow<Boolean?> = stateFlow.asStateFlow()

    /** A Bootleg request is running. */
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    private var requestCount = 0
    private var shuttingDown = false
    private var registered = false

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = onBroadcast(intent)
        }

    /** Registers for the broadcasts once; the sticky one reports the current state right away. */
    fun start() {
        if (registered) return
        registered = true
        val filter =
            IntentFilter(ACTION_TETHER_STATE_CHANGED).apply {
                addAction(Intent.ACTION_SHUTDOWN)
            }
        // Sent by the network stack (its own uid, not the system's), so the receiver must be exported.
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    suspend fun set(on: Boolean): TetherResult =
        withContext(Dispatchers.Main) {
            requestCount++
            busyFlow.value = true
            try {
                val result =
                    try {
                        rootSet(on)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "tethering request failed", e)
                        TetherResult.Failed(stateFlow.value == true, e.message ?: e.javaClass.simpleName)
                    }
                stateFlow.value = result.active
                TetherChoice.afterRequest(result)?.let(prefs::setUsbTetherChoice)
                result
            } finally {
                requestCount--
                check(requestCount >= 0) { "tethering request count went negative" }
                busyFlow.value = requestCount > 0
            }
        }

    private fun onBroadcast(intent: Intent) {
        if (intent.action == Intent.ACTION_SHUTDOWN) {
            shuttingDown = true
            return
        }
        val interfaces = intent.getStringArrayListExtra(EXTRA_ACTIVE_TETHER).orEmpty()
        val active = TetherPolicy.bootlegTethered(interfaces)
        val wasActive = stateFlow.value
        stateFlow.value = active
        val choice = prefs.usbTetherChoice.value
        val after = TetherChoice.afterObservation(choice, wasActive, active, requestCount > 0, shuttingDown)
        if (after != choice) {
            confirmExternalStop()
        } else if (active) {
            // On again: the earlier off was no stop.
            confirmJob?.cancel()
            confirmJob = null
        }
    }

    /**
     * A broadcast can be stale (sent before a request that has since turned tethering on), so the stop
     * is confirmed only after [TetherLimits.EXTERNAL_STOP_CONFIRM_MS], with no broadcast since saying
     * on and root's own view still off ([TetherChoice.externalStopConfirmed]). A newer broadcast
     * cancels a pending confirmation.
     */
    private fun confirmExternalStop() {
        confirmJob?.cancel()
        confirmJob =
            scope.launch(Dispatchers.Main) {
                delay(TetherLimits.EXTERNAL_STOP_CONFIRM_MS)
                val tethered =
                    try {
                        rootTethered()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "could not confirm that tethering stopped; keeping the choice", e)
                        return@launch
                    }
                val confirmed =
                    TetherChoice.externalStopConfirmed(
                        stillOff = stateFlow.value == false,
                        rootTethered = tethered,
                        requestInFlight = requestCount > 0,
                        shuttingDown = shuttingDown,
                    )
                if (confirmed) {
                    Log.i(TAG, "USB tethering stopped outside Bootleg; it won't be restored after a reboot")
                    prefs.setUsbTetherChoice(false)
                }
            }
    }

    private companion object {
        const val TAG = "BootlegTether"

        // TetheringManager.ACTION_TETHER_STATE_CHANGED / EXTRA_ACTIVE_TETHER (system API names).
        const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        const val EXTRA_ACTIVE_TETHER = "tetherArray"
    }
}
