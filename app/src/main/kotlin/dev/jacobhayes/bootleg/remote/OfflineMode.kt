package dev.jacobhayes.bootleg.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Android's side of offline mode: the LocalOnlyHotspot reservation ([Hotspot] on the phone). */
interface HotspotReservation {
    val isOn: Boolean

    /** Starts the hotspot; returns its details or throws. Cancelling it closes a late reservation. */
    suspend fun start(onStopped: () -> Unit): HotspotInfo

    fun stop()
}

/**
 * Offline mode's lifecycle: the hotspot reservation, then Go's hotspot listener. It shares
 * [lifecycle] with the node's start/stop, so the listener only starts while the node that asked
 * for it is still current, and a stop that lands while Android is still starting the hotspot
 * supersedes that start (its reservation is closed and the listener never starts).
 */
class OfflineMode(
    private val lifecycle: Mutex,
    private val reservation: HotspotReservation,
    private val stopListener: suspend () -> Unit,
    private val onState: (HotspotState) -> Unit,
) {
    /** Bumped by every stop; a start that sees a different generation was superseded. Guarded by [lifecycle]. */
    private var generation = 0L

    /** A start is between taking its generation and finishing. Guarded by [lifecycle]. */
    private var starting = false

    /** Go's listener is (or may be) up. Guarded by [lifecycle]. */
    private var listenerStarted = false

    /**
     * Starts the hotspot, then (if [isCurrent] still holds and no stop came meanwhile) Go's
     * listener via [startListener]. Returns whether offline mode is now on.
     */
    suspend fun start(
        isCurrent: () -> Boolean,
        onStopped: () -> Unit,
        startListener: suspend (interfaceCidr: String) -> Unit,
    ): Boolean {
        val startGeneration =
            lifecycle.withLock {
                if (!isCurrent() || starting || reservation.isOn) return false
                starting = true
                onState(HotspotState.Starting)
                generation
            }
        val info =
            try {
                try {
                    withTimeout(HOTSPOT_START_TIMEOUT_MS) { reservation.start(onStopped) }
                } catch (e: TimeoutCancellationException) {
                    throw IllegalStateException("the hotspot didn't start within ${HOTSPOT_START_TIMEOUT_MS / 1000}s", e)
                }
            } catch (e: CancellationException) {
                lifecycle.withLock { starting = false }
                throw e
            } catch (e: Exception) {
                lifecycle.withLock {
                    starting = false
                    reservation.stop()
                    if (generation == startGeneration) onState(HotspotState.Failed(e.message ?: e.javaClass.simpleName))
                }
                return false
            }
        return lifecycle.withLock {
            starting = false
            if (generation != startGeneration || !isCurrent()) {
                // Superseded while Android started the hotspot: the stop already ran, so close what
                // this start opened. The listener was never started for it.
                reservation.stop()
                return@withLock false
            }
            if (!reservation.isOn) {
                // Android closed the reservation after handing it over; a listener now would serve a dead network.
                reservation.stop()
                onState(HotspotState.Failed(HOTSPOT_LOST_REASON))
                return@withLock false
            }
            try {
                listenerStarted = true
                startListener(info.interfaceCidr)
                onState(HotspotState.On(info))
                true
            } catch (e: CancellationException) {
                stopLocked()
                throw e
            } catch (e: Exception) {
                stopLocked()
                onState(HotspotState.Failed(e.message ?: e.javaClass.simpleName))
                false
            }
        }
    }

    /** Stops the listener and the hotspot, superseding any start in flight. */
    suspend fun stop() = lifecycle.withLock { stopLocked() }

    /**
     * Android stopped the hotspot (the reservation's onStopped). Ends offline mode like [stop], whether
     * it was on or still starting (the start is superseded and never brings the listener up), and
     * reports [HotspotState.Failed]. Returns false if offline mode wasn't running, so a late callback
     * for an old reservation changes nothing.
     */
    suspend fun hotspotLost(): Boolean =
        lifecycle.withLock {
            if (!starting && !listenerStarted) return@withLock false
            stopLocked()
            onState(HotspotState.Failed(HOTSPOT_LOST_REASON))
            true
        }

    /** [stop] for a caller that already holds [lifecycle] (the node's stop). */
    suspend fun stopLocked() {
        check(lifecycle.isLocked) { "stopLocked needs the lifecycle lock" }
        generation++
        if (listenerStarted) {
            listenerStarted = false
            stopListener()
        }
        reservation.stop()
        onState(HotspotState.Off)
    }

    companion object {
        /** Android normally answers within a few seconds; past this the start counts as failed. */
        const val HOTSPOT_START_TIMEOUT_MS = 15_000L
        const val HOTSPOT_LOST_REASON = "Android stopped the hotspot"
    }
}
