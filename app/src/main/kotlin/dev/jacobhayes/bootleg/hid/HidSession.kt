package dev.jacobhayes.bootleg.hid

import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.HidLimits
import dev.jacobhayes.bootleg.core.hid.KeyboardLeds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class HidWriteResult {
    WRITTEN,

    /** EAGAIN: the host hasn't fetched the previous report yet. */
    WOULD_BLOCK,

    /** ENXIO/ESHUTDOWN and friends: the function is gone or the host is off. Reopen. */
    DISCONNECTED,
    FAILED,
}

enum class HidOffer {
    ACCEPTED,

    /** The bounded queue overflowed: everything queued was dropped and a release-all is owed. */
    QUEUE_FULL,

    /** Stopped, reconnecting, or the host isn't reading reports. */
    UNAVAILABLE,
}

/** A nonblocking `/dev/hidgN` file descriptor (or a fake in tests). */
interface HidEndpoint {
    /** One nonblocking attempt to write a whole report. */
    fun write(report: ByteArray): HidWriteResult

    /** One nonblocking read of a host → device output report, or null if none is waiting. */
    fun readOutputReport(): ByteArray?

    fun close()
}

fun interface HidEndpointOpener {
    suspend fun open(kind: HidKind): HidEndpoint
}

sealed interface HidLinkState {
    data object Stopped : HidLinkState

    data object Opening : HidLinkState

    /** Endpoints are open. [hostListening] is false while the host isn't fetching reports (asleep, off, or not configured). */
    data class Open(
        val hostListening: Boolean,
    ) : HidLinkState

    data class Reconnecting(
        val attempt: Int,
        val reason: String,
    ) : HidLinkState

    /** Gave up after [HidSessionTuning.reopenAttemptCountMax]; the next [HidSession.send] tries again. */
    data class Failed(
        val reason: String,
    ) : HidLinkState
}

data class HidSessionStats(
    val reportsWritten: Long = 0,
    val reportsDropped: Long = 0,
    val releaseAllCount: Long = 0,
    val lastError: String? = null,
)

data class HidSessionTuning(
    val queueLengthMax: Int = HidLimits.REPORT_QUEUE_LENGTH_MAX,
    val writeDeadlineMs: Long = HidLimits.REPORT_WRITE_DEADLINE_MS,
    /** Pause between nonblocking write attempts within one deadline. */
    val writeRetryIntervalMs: Long = 1,
    /** How long writes may keep timing out before the endpoints are reopened. */
    val stallReopenMs: Long = 2_000,
    /** Pause between release-all attempts while the host isn't listening. */
    val stallRetryIntervalMs: Long = 100,
    val reopenBackoffInitialMs: Long = 250,
    val reopenBackoffMaxMs: Long = 5_000,
    val reopenAttemptCountMax: Int = 40,
    /** How often keyboard LED output reports are polled while idle. */
    val ledPollIntervalMs: Long = 100,
) {
    init {
        require(queueLengthMax in 1..HidLimits.REPORT_QUEUE_LENGTH_MAX) { "queue length must be 1..${HidLimits.REPORT_QUEUE_LENGTH_MAX}" }
        require(writeDeadlineMs > 0 && writeRetryIntervalMs in 1..writeDeadlineMs) { "bad write deadline: $this" }
        require(stallReopenMs > writeDeadlineMs && stallRetryIntervalMs > 0) { "bad stall timing: $this" }
        require(reopenBackoffInitialMs in 1..reopenBackoffMaxMs && reopenAttemptCountMax > 0) { "bad reopen backoff: $this" }
        require(ledPollIntervalMs > 0) { "bad LED poll interval: $this" }
    }

    /** Backoff before reopen attempt [attempt] (1-based): doubling from the initial value, capped. */
    fun reopenBackoffMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based: $attempt" }
        val doublings = (attempt - 1).coerceAtMost(REOPEN_DOUBLING_COUNT_MAX)
        return (reopenBackoffInitialMs shl doublings).coerceAtMost(reopenBackoffMaxMs)
    }

    private companion object {
        const val REOPEN_DOUBLING_COUNT_MAX = 20
    }
}

/**
 * Owns the HID file descriptors and writes reports to them from one coroutine.
 *
 * - The queue is bounded; overflow drops it and releases everything.
 * - Each write is nonblocking with a deadline. A host that stops polling (asleep, rebooting) makes
 *   reports time out: queued input is dropped (never replayed late), release-all is retried until
 *   the host takes it, and after [HidSessionTuning.stallReopenMs] the endpoints are reopened.
 * - ENXIO/ESHUTDOWN (function gone, host off) reopen with bounded backoff.
 * - Every error, overflow and stop owes the host a release-all (all-zero keyboard and mouse
 *   reports); [releaseEpoch] increments so input state (sticky modifiers, held buttons) resets too.
 *
 * [scope] should run on a single thread; [nowMs] is a monotonic clock.
 */
class HidSession(
    private val opener: HidEndpointOpener,
    private val scope: CoroutineScope,
    val kinds: Set<HidKind>,
    private val nowMs: () -> Long,
    private val tuning: HidSessionTuning = HidSessionTuning(),
) {
    private class Pending(
        val kind: HidKind,
        val report: ByteArray,
    )

    /**
     * Guards [queue] and [releaseAllOwed] together: dropping the queue and owing a release is one step,
     * and admission checks the barrier in the same step, so no report can be queued between the drop
     * and the barrier and then reach the host after the release.
     */
    private val queueLock = Any()
    private val queue = ArrayDeque<Pending>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val endpoints = HashMap<HidKind, HidEndpoint>()

    @Volatile private var releaseAllOwed = false
    private var stallStartMs: Long? = null
    private var failedAttempts = 0

    @Volatile private var job: Job? = null

    private val stateFlow = MutableStateFlow<HidLinkState>(HidLinkState.Stopped)
    private val statsFlow = MutableStateFlow(HidSessionStats())
    private val ledsFlow = MutableStateFlow<KeyboardLeds?>(null)
    private val releaseEpochFlow = MutableStateFlow(0)
    private val queueLengthFlow = MutableStateFlow(0)

    val state: StateFlow<HidLinkState> = stateFlow.asStateFlow()
    val stats: StateFlow<HidSessionStats> = statsFlow.asStateFlow()
    val leds: StateFlow<KeyboardLeds?> = ledsFlow.asStateFlow()

    /** Increments whenever input state must be forgotten because everything was (or will be) released. */
    val releaseEpoch: StateFlow<Int> = releaseEpochFlow.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    init {
        require(kinds.isNotEmpty()) { "a session needs at least one HID function" }
    }

    fun start() {
        if (isRunning) return
        failedAttempts = 0
        stallStartMs = null
        dropQueueAndOweRelease()
        stateFlow.value = HidLinkState.Opening
        job = scope.launch { runLoop() }
    }

    /** Releases everything (best effort, within the write deadline) and closes the endpoints. */
    suspend fun stop() {
        val running = job ?: return
        job = null
        running.cancel()
        running.join()
        withContext(NonCancellable) {
            clearQueue()
            for ((kind, endpoint) in endpoints) writeWithDeadline(kind, endpoint, kind.releaseAllReport)
            closeEndpoints()
            stateFlow.value = HidLinkState.Stopped
            bumpReleaseEpoch()
        }
    }

    /**
     * Queues one report. Returns false (and counts it dropped) when the session isn't ready to
     * deliver it promptly: stopped, reconnecting, the host isn't listening, a release-all is still
     * owed, or the queue overflowed.
     */
    fun send(
        kind: HidKind,
        report: ByteArray,
    ): Boolean = offer(kind, report) == HidOffer.ACCEPTED

    /** [send], saying why a report was dropped (the remote controller reacts differently to each). */
    fun offer(
        kind: HidKind,
        report: ByteArray,
    ): HidOffer {
        require(kind in kinds) { "$kind is not part of this session" }
        require(report.size == kind.reportSizeBytes) { "$kind report must be ${kind.reportSizeBytes} bytes, got ${report.size}" }
        when (val current = stateFlow.value) {
            is HidLinkState.Open -> if (!current.hostListening) return dropped(HidOffer.UNAVAILABLE)
            is HidLinkState.Failed -> {
                // User input is the signal to try again.
                start()
                return dropped(HidOffer.UNAVAILABLE)
            }
            else -> return dropped(HidOffer.UNAVAILABLE)
        }
        var droppedCount = 0
        val offer =
            synchronized(queueLock) {
                when {
                    // Input waits behind the owed release; queued now, it could reach the host after it.
                    releaseAllOwed -> HidOffer.UNAVAILABLE
                    queue.size >= tuning.queueLengthMax -> {
                        droppedCount = dropQueueAndOweReleaseLocked()
                        HidOffer.QUEUE_FULL
                    }
                    else -> {
                        queue.addLast(Pending(kind, report.copyOf()))
                        queueLengthFlow.value = queue.size
                        HidOffer.ACCEPTED
                    }
                }
            }
        countDropped(droppedCount)
        if (offer == HidOffer.QUEUE_FULL) statsFlow.update { it.copy(lastError = "report queue overflowed") }
        if (offer != HidOffer.UNAVAILABLE) wakeUp.trySend(Unit)
        return if (offer == HidOffer.ACCEPTED) offer else dropped(offer)
    }

    /** A release-all is owed and hasn't reached the host yet; the remote controller holds input behind it. */
    val releaseOwed: Boolean get() = isRunning && releaseAllOwed

    /** Owes the host a release-all, e.g. when the app goes to the background. */
    fun releaseAll() {
        if (!isRunning) return
        dropQueueAndOweRelease()
        wakeUp.trySend(Unit)
    }

    /** Waits until everything queued has been written or dropped. */
    suspend fun awaitIdle(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { queueLengthFlow.first { it == 0 } } != null

    private suspend fun runLoop() {
        while (true) {
            if (endpoints.size != kinds.size) {
                if (failedAttempts >= tuning.reopenAttemptCountMax) {
                    stateFlow.value = HidLinkState.Failed(statsFlow.value.lastError ?: "could not open HID devices")
                    return
                }
                if (failedAttempts > 0) delay(tuning.reopenBackoffMs(failedAttempts))
                if (!openEndpoints()) continue
            }
            if (releaseAllOwed) {
                if (!deliverReleaseAll()) continue
            }
            pollLeds()
            val next = synchronized(queueLock) { queue.removeFirstOrNull() }
            if (next == null) {
                queueLengthFlow.value = 0
                withTimeoutOrNull(tuning.ledPollIntervalMs) { wakeUp.receive() }
                continue
            }
            val written = writeOrRecover(next.kind, next.report)
            synchronized(queueLock) { queueLengthFlow.value = queue.size }
            if (!written) statsFlow.update { it.copy(reportsDropped = it.reportsDropped + 1) }
        }
    }

    private suspend fun openEndpoints(): Boolean {
        closeEndpoints()
        try {
            for (kind in kinds) endpoints[kind] = opener.open(kind)
        } catch (e: CancellationException) {
            closeEndpoints()
            throw e
        } catch (e: Exception) {
            closeEndpoints()
            failedAttempts++
            val reason = "open failed: ${e.message ?: e.javaClass.simpleName}"
            statsFlow.update { it.copy(lastError = reason) }
            stateFlow.value = HidLinkState.Reconnecting(failedAttempts, reason)
            return false
        }
        stallStartMs = null
        dropQueueAndOweRelease()
        stateFlow.value = HidLinkState.Open(hostListening = true)
        return true
    }

    /** Returns true once both endpoints took their all-zero report. */
    private suspend fun deliverReleaseAll(): Boolean {
        for ((kind, endpoint) in endpoints.toSortedMap()) {
            val result = writeWithDeadline(kind, endpoint, kind.releaseAllReport)
            if (!handleResult(result)) {
                if (result == HidWriteResult.WOULD_BLOCK && endpoints.isNotEmpty()) delay(tuning.stallRetryIntervalMs)
                return false
            }
        }
        // Nothing was admitted since the barrier went up, so the release is the last thing the host saw.
        synchronized(queueLock) {
            check(queue.isEmpty()) { "input was queued behind an owed release" }
            releaseAllOwed = false
        }
        statsFlow.update { it.copy(releaseAllCount = it.releaseAllCount + 1) }
        markHostListening()
        return true
    }

    private fun markHostListening() {
        stallStartMs = null
        failedAttempts = 0
        if (stateFlow.value != HidLinkState.Open(hostListening = true)) stateFlow.value = HidLinkState.Open(hostListening = true)
    }

    private suspend fun writeOrRecover(
        kind: HidKind,
        report: ByteArray,
    ): Boolean {
        val endpoint = endpoints[kind] ?: return false
        val written = handleResult(writeWithDeadline(kind, endpoint, report))
        if (written) markHostListening()
        return written
    }

    /** Updates link state for a write outcome; returns true only if the report was written. */
    private fun handleResult(result: HidWriteResult): Boolean {
        when (result) {
            // The caller decides when the link counts as healthy (every owed release delivered).
            HidWriteResult.WRITTEN -> return true
            HidWriteResult.WOULD_BLOCK -> {
                val now = nowMs()
                val stallStart = stallStartMs ?: now.also { stallStartMs = it }
                // Input the host didn't take within the deadline is stale; never replay it late.
                dropQueueAndOweRelease()
                if (now - stallStart >= tuning.stallReopenMs) {
                    reconnect("host stopped reading reports for ${now - stallStart} ms")
                } else {
                    stateFlow.value = HidLinkState.Open(hostListening = false)
                }
            }
            HidWriteResult.DISCONNECTED -> reconnect("HID device disconnected")
            HidWriteResult.FAILED -> reconnect("HID write failed")
        }
        return false
    }

    private fun reconnect(reason: String) {
        failedAttempts++
        statsFlow.update { it.copy(lastError = reason) }
        dropQueueAndOweRelease()
        closeEndpoints()
        stallStartMs = null
        stateFlow.value = HidLinkState.Reconnecting(failedAttempts, reason)
    }

    private suspend fun writeWithDeadline(
        kind: HidKind,
        endpoint: HidEndpoint,
        report: ByteArray,
    ): HidWriteResult {
        check(report.size == kind.reportSizeBytes)
        val deadlineMs = nowMs() + tuning.writeDeadlineMs
        while (true) {
            val result =
                try {
                    endpoint.write(report)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    HidWriteResult.FAILED
                }
            if (result == HidWriteResult.WRITTEN) statsFlow.update { it.copy(reportsWritten = it.reportsWritten + 1) }
            if (result != HidWriteResult.WOULD_BLOCK || nowMs() >= deadlineMs) return result
            delay(tuning.writeRetryIntervalMs)
        }
    }

    private fun pollLeds() {
        val keyboard = endpoints[HidKind.KEYBOARD] ?: return
        val report = runCatching { keyboard.readOutputReport() }.getOrNull() ?: return
        if (report.isNotEmpty()) ledsFlow.value = KeyboardLeds.decode(byteArrayOf(report[0]))
    }

    private fun clearQueue() {
        countDropped(
            synchronized(queueLock) {
                val count = queue.size
                queue.clear()
                queueLengthFlow.value = 0
                count
            },
        )
    }

    /** Drops queued input and raises the release barrier in one step (see [queueLock]). */
    private fun dropQueueAndOweRelease() = countDropped(synchronized(queueLock) { dropQueueAndOweReleaseLocked() })

    /** Returns how many queued reports were dropped. Caller holds [queueLock]. */
    private fun dropQueueAndOweReleaseLocked(): Int {
        val count = queue.size
        queue.clear()
        queueLengthFlow.value = 0
        if (!releaseAllOwed) bumpReleaseEpoch()
        releaseAllOwed = true
        return count
    }

    private fun countDropped(count: Int) {
        if (count > 0) statsFlow.update { it.copy(reportsDropped = it.reportsDropped + count) }
    }

    private fun bumpReleaseEpoch() = releaseEpochFlow.update { it + 1 }

    private fun closeEndpoints() {
        for (endpoint in endpoints.values) runCatching { endpoint.close() }
        endpoints.clear()
    }

    private fun dropped(reason: HidOffer): HidOffer {
        statsFlow.update { it.copy(reportsDropped = it.reportsDropped + 1) }
        return reason
    }
}
