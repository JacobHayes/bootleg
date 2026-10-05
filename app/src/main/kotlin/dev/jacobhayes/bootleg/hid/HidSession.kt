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
import kotlinx.coroutines.selects.select
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

    /** Stopped, reconnecting, or a release-all is still owed on this endpoint. */
    UNAVAILABLE,

    /**
     * The host isn't reading this endpoint (asleep, or firmware and boot menus that poll only the
     * keyboard). Dropped; nothing else is affected and nothing more is owed.
     */
    NOT_READING,
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

    /**
     * Endpoints are open. [notReading] are the endpoints whose reports the host isn't fetching (asleep, off,
     * or firmware and boot menus that poll only the keyboard); their input is dropped, the others' flows.
     */
    data class Open(
        val notReading: Set<HidKind> = emptySet(),
    ) : HidLinkState {
        /** The host reads every endpoint. */
        val hostListening: Boolean get() = notReading.isEmpty()
    }

    data class Reconnecting(
        val attempt: Int,
        val reason: String,
    ) : HidLinkState

    /**
     * Gave up after [HidSessionTuning.reopenAttemptCountMax]; the next [HidSession.send] tries again. With
     * [restartRequired] (root quarantined an fd) nothing works until the phone restarts, so nothing retries.
     */
    data class Failed(
        val reason: String,
        val restartRequired: Boolean = false,
    ) : HidLinkState

    /**
     * How connected the link looks, for [dev.jacobhayes.bootleg.core.hid.DropHold]: the more endpoints the
     * host reads the higher. A stop is deliberate (switch off, background, gadget change) and shows at once.
     */
    val displayRank: Int?
        get() =
            when (this) {
                Stopped -> null
                Opening, is Reconnecting, is Failed -> 0
                is Open -> 1 + HidKind.entries.size - notReading.size
            }
}

/** What the root relay tells the session when it drops the session's fds on its own (see `IHidRelayClient`). */
sealed interface HidRelayNotice {
    /** The fds are gone. [reopenFollows]: wait for [Reopenable] (the gadget change ended, or USB has been stably bound again). */
    data class Closed(
        val reason: String,
        val reopenFollows: Boolean,
        val restartRequired: Boolean,
    ) : HidRelayNotice

    /** Opens are safe again: the gadget change is over, or USB has stayed bound for the stable-bound window. */
    data object Reopenable : HidRelayNotice

    companion object {
        /** Notices come a few per gadget change, and the session takes each one at once. */
        const val BUFFER_COUNT_MAX = 8
    }
}

data class HidSessionStats(
    val reportsWritten: Long = 0,
    val reportsDropped: Long = 0,
    /** Release-all reports delivered, counted per endpoint. */
    val releaseAllCount: Long = 0,
    val lastError: String? = null,
)

data class HidSessionTuning(
    val queueLengthMax: Int = HidLimits.REPORT_QUEUE_LENGTH_MAX,
    val writeDeadlineMs: Long = HidLimits.REPORT_WRITE_DEADLINE_MS,
    /** Pause between nonblocking write attempts within one deadline. */
    val writeRetryIntervalMs: Long = 1,
    /** Pause between single nonblocking release-all attempts on an endpoint the host isn't reading. */
    val stallRetryIntervalMs: Long = 100,
    val reopenBackoffInitialMs: Long = 250,
    val reopenBackoffMaxMs: Long = 5_000,
    val reopenAttemptCountMax: Int = 40,
    /** How often keyboard LED output reports are polled while idle. */
    val ledPollIntervalMs: Long = 100,
    /** How long to wait for [HidRelayNotice.Reopenable] after root closed the fds, before trying anyway (root's own cap). */
    val reopenWaitMaxMs: Long = HidLimits.RELAY_SETTLE_WAIT_MS_MAX,
) {
    init {
        require(queueLengthMax in 1..HidLimits.REPORT_QUEUE_LENGTH_MAX) { "queue length must be 1..${HidLimits.REPORT_QUEUE_LENGTH_MAX}" }
        require(writeDeadlineMs > 0 && writeRetryIntervalMs in 1..writeDeadlineMs) { "bad write deadline: $this" }
        require(stallRetryIntervalMs > 0) { "bad stall timing: $this" }
        require(reopenBackoffInitialMs in 1..reopenBackoffMaxMs && reopenAttemptCountMax > 0) { "bad reopen backoff: $this" }
        require(ledPollIntervalMs > 0) { "bad LED poll interval: $this" }
        require(reopenWaitMaxMs > 0) { "bad reopen wait: $this" }
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
 * - Each write is nonblocking with a deadline. Stalls are tracked per endpoint: firmware and boot menus
 *   often poll the keyboard but never the mouse. An endpoint whose report times out is "not reading":
 *   its queued input is dropped (never replayed late), its new input is refused, and its owed
 *   release-all is retried with one nonblocking attempt every [HidSessionTuning.stallRetryIntervalMs],
 *   so it never holds up the other endpoint. A stall never closes or reopens anything: the fds are
 *   fine, the host just isn't reading.
 * - ENXIO/ESHUTDOWN (function gone, host off) and write failures reopen with bounded backoff.
 * - When root drops the fds itself ([onRelayNotice]: a gadget change, a re-enumeration by someone
 *   else), the link leaves Open at once, input is refused until the endpoints are reopened and the
 *   release-all is delivered, and the reopen waits until root says opens are safe. A quarantined fd
 *   is terminal: [HidLinkState.Failed] with `restartRequired`.
 * - Every error, overflow and stop owes the host a release-all (all-zero keyboard and mouse
 *   reports; a stall only its own endpoint's); that endpoint's [releaseEpochs] entry increments so
 *   its input state (sticky modifiers, held buttons) resets too.
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
     * Guards [queue], [releaseOwedKinds] and [notReading] together: dropping queued input and owing a
     * release is one step, and admission checks the barrier in the same step, so no report can be queued
     * between the drop and the barrier and then reach the host after the release.
     */
    private val queueLock = Any()
    private val queue = ArrayDeque<Pending>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val reopenable = Channel<Unit>(Channel.CONFLATED)

    /** A relay notice the loop hasn't handled yet; guarded by [queueLock], since it also holds input back. */
    private var relayClosed: HidRelayNotice.Closed? = null
    private val endpoints = HashMap<HidKind, HidEndpoint>()

    /** Endpoints owed a release-all; their input is refused until it reaches the host. Guarded by [queueLock]. */
    private val releaseOwedKinds = mutableSetOf<HidKind>()

    /** Endpoints whose last write timed out: the host isn't reading them. Guarded by [queueLock]. */
    private val notReading = mutableSetOf<HidKind>()

    /** When each not-reading endpoint was last probed with its owed release (loop thread only). */
    private val lastProbeMs = HashMap<HidKind, Long>()
    private var failedAttempts = 0

    @Volatile private var job: Job? = null

    private val stateFlow = MutableStateFlow<HidLinkState>(HidLinkState.Stopped)
    private val statsFlow = MutableStateFlow(HidSessionStats())
    private val ledsFlow = MutableStateFlow<KeyboardLeds?>(null)
    private val releaseEpochFlow = MutableStateFlow(kinds.associateWith { 0 })
    private val queueLengthFlow = MutableStateFlow(0)

    val state: StateFlow<HidLinkState> = stateFlow.asStateFlow()
    val stats: StateFlow<HidSessionStats> = statsFlow.asStateFlow()
    val leds: StateFlow<KeyboardLeds?> = ledsFlow.asStateFlow()

    /**
     * Per endpoint, increments whenever its input state must be forgotten because it was (or will be)
     * released. A stall releases only its own endpoint, so the other's held keys or buttons stay known.
     */
    val releaseEpochs: StateFlow<Map<HidKind, Int>> = releaseEpochFlow.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    init {
        require(kinds.isNotEmpty()) { "a session needs at least one HID function" }
    }

    fun start() {
        if (isRunning) return
        // A quarantined fd only goes away with a restart; reopening would just be refused again.
        if ((stateFlow.value as? HidLinkState.Failed)?.restartRequired == true) return
        failedAttempts = 0
        // A notice from before this start is about endpoints that are gone; opening fresh covers it.
        synchronized(queueLock) { relayClosed = null }
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
            // Under the lock, so a relay notice racing the stop can't publish its state after this.
            synchronized(queueLock) {
                relayClosed = null
                stateFlow.value = HidLinkState.Stopped
            }
            bumpReleaseEpoch(kinds)
        }
    }

    /**
     * Queues one report. Returns false (and counts it dropped) when the session isn't ready to
     * deliver it promptly: stopped, reconnecting, the host isn't reading this endpoint, a release-all
     * is still owed on it, or the queue overflowed.
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
        when (stateFlow.value) {
            is HidLinkState.Open -> Unit
            is HidLinkState.Failed -> {
                // User input is the signal to try again (start refuses when only a restart helps).
                start()
                return dropped(HidOffer.UNAVAILABLE)
            }
            else -> return dropped(HidOffer.UNAVAILABLE)
        }
        var droppedCount = 0
        val offer =
            synchronized(queueLock) {
                when {
                    relayClosed != null -> HidOffer.UNAVAILABLE
                    kind in notReading -> HidOffer.NOT_READING
                    // Input waits behind the owed release; queued now, it could reach the host after it.
                    kind in releaseOwedKinds -> HidOffer.UNAVAILABLE
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
        if (offer == HidOffer.ACCEPTED || offer == HidOffer.QUEUE_FULL) wakeUp.trySend(Unit)
        return if (offer == HidOffer.ACCEPTED) offer else dropped(offer)
    }

    /**
     * A release-all is owed on an endpoint the host reads and hasn't reached it yet; the remote controller
     * holds input behind it. One the host isn't reading doesn't count: its input is dropped
     * ([HidOffer.NOT_READING]) until the host takes the release, which may be never (firmware that
     * ignores the mouse).
     */
    val releaseOwed: Boolean get() = isRunning && synchronized(queueLock) { !notReading.containsAll(releaseOwedKinds) }

    /** Owes the host a release-all, e.g. when the app goes to the background. */
    fun releaseAll() {
        if (!isRunning) return
        dropQueueAndOweRelease()
        wakeUp.trySend(Unit)
    }

    /**
     * Root dropped (or refused) this session's fds on its own. Callable from any thread: the link leaves
     * Open and input is refused right away; the loop closes and reopens the endpoints.
     */
    fun onRelayNotice(notice: HidRelayNotice) {
        when (notice) {
            is HidRelayNotice.Closed -> {
                if (!isRunning && !notice.restartRequired) return
                statsFlow.update { it.copy(lastError = notice.reason) }
                val dropped =
                    synchronized(queueLock) {
                        // stop() publishes Stopped under this lock after clearing the job.
                        if (!isRunning && !notice.restartRequired) return
                        val pending = relayClosed
                        // A restart-required notice is never replaced by a milder one.
                        val kept = if (pending?.restartRequired == true) pending else notice
                        relayClosed = kept
                        // Published before the loop can take the notice, so the loop's own later state
                        // (reopened, Open) always lands after this one, never under it.
                        stateFlow.value =
                            if (kept.restartRequired) {
                                HidLinkState.Failed(kept.reason, restartRequired = true)
                            } else {
                                HidLinkState.Reconnecting(1, kept.reason)
                            }
                        dropQueueAndOweReleaseLocked()
                    }
                countDropped(dropped)
                wakeUp.trySend(Unit)
            }
            HidRelayNotice.Reopenable -> reopenable.trySend(Unit)
        }
    }

    /** Waits until everything queued has been written or dropped. */
    suspend fun awaitIdle(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { queueLengthFlow.first { it == 0 } } != null

    private suspend fun runLoop() {
        while (true) {
            val closed = synchronized(queueLock) { relayClosed.also { relayClosed = null } }
            if (closed != null && !handleRelayClosed(closed)) return
            if (endpoints.size != kinds.size) {
                if (failedAttempts >= tuning.reopenAttemptCountMax) {
                    stateFlow.value = HidLinkState.Failed(statsFlow.value.lastError ?: "could not open HID devices")
                    return
                }
                if (failedAttempts > 0) {
                    // The end of a gadget change cuts the backoff short; a new relay notice is handled first.
                    awaitReopenable(tuning.reopenBackoffMs(failedAttempts))
                    if (synchronized(queueLock) { relayClosed != null }) continue
                }
                if (!openEndpoints()) continue
            }
            if (!deliverOwedReleases()) continue
            pollLeds()
            val next = synchronized(queueLock) { queue.removeFirstOrNull() }
            if (next == null) {
                queueLengthFlow.value = 0
                // The wait also paces the probes of endpoints the host isn't reading.
                withTimeoutOrNull(minOf(tuning.ledPollIntervalMs, tuning.stallRetryIntervalMs)) { wakeUp.receive() }
                continue
            }
            val written = writeOrRecover(next.kind, next.report)
            synchronized(queueLock) { queueLengthFlow.value = queue.size }
            if (!written) statsFlow.update { it.copy(reportsDropped = it.reportsDropped + 1) }
        }
    }

    /** Closes what root already dropped and waits out its gadget change; false when only a restart helps. */
    private suspend fun handleRelayClosed(closed: HidRelayNotice.Closed): Boolean {
        closeEndpoints()
        dropQueueAndOweRelease()
        if (closed.restartRequired) {
            stateFlow.value = HidLinkState.Failed(closed.reason, restartRequired = true)
            return false
        }
        failedAttempts = 0
        stateFlow.value = HidLinkState.Reconnecting(1, closed.reason)
        if (closed.reopenFollows) awaitReopenable(tuning.reopenWaitMaxMs)
        return true
    }

    /** Waits at most [timeoutMs] for [HidRelayNotice.Reopenable]; a new [HidRelayNotice.Closed] ends the wait too. */
    private suspend fun awaitReopenable(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            while (synchronized(queueLock) { relayClosed == null }) {
                val reopened =
                    select {
                        reopenable.onReceive { true }
                        wakeUp.onReceive { false }
                    }
                if (reopened) break
            }
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
        dropQueueAndOweRelease()
        synchronized(queueLock) { notReading.clear() }
        lastProbeMs.clear()
        stateFlow.value = HidLinkState.Open()
        return true
    }

    /**
     * Delivers every owed release-all: with the write deadline on endpoints the host reads, and with one
     * nonblocking attempt per [HidSessionTuning.stallRetryIntervalMs] on those it doesn't, so a stalled
     * endpoint never holds up the other. False when the endpoints were closed (a disconnect, or root
     * dropped them meanwhile): the loop starts over.
     */
    private suspend fun deliverOwedReleases(): Boolean {
        val owed = synchronized(queueLock) { releaseOwedKinds.toSortedSet() }
        for (kind in owed) {
            val endpoint = endpoints[kind] ?: return false
            val stalled = synchronized(queueLock) { kind in notReading }
            val result =
                if (stalled) {
                    val nowMs = nowMs()
                    val lastMs = lastProbeMs[kind]
                    if (lastMs != null && nowMs - lastMs < tuning.stallRetryIntervalMs) continue
                    lastProbeMs[kind] = nowMs
                    writeOnce(kind, endpoint, kind.releaseAllReport)
                } else {
                    writeWithDeadline(kind, endpoint, kind.releaseAllReport)
                }
            when (result) {
                HidWriteResult.WRITTEN -> if (!releaseDelivered(kind)) return false
                HidWriteResult.WOULD_BLOCK -> if (!stalled) markNotReading(kind)
                HidWriteResult.DISCONNECTED -> {
                    reconnect("HID device disconnected")
                    return false
                }
                HidWriteResult.FAILED -> {
                    reconnect("HID write failed")
                    return false
                }
            }
        }
        return true
    }

    /** [kind] took its release-all, so its input flows again. False if root dropped the fds meanwhile (the release is owed again). */
    private fun releaseDelivered(kind: HidKind): Boolean {
        val recovered =
            synchronized(queueLock) {
                // Nothing of this kind was admitted since the barrier went up, so the release is the last thing the host saw.
                check(queue.none { it.kind == kind }) { "$kind input was queued behind an owed release" }
                // Root dropped the fds meanwhile, so this release may not have reached the host; owe it to the reopened endpoints.
                if (relayClosed != null) return false
                releaseOwedKinds -= kind
                notReading.remove(kind)
            }
        lastProbeMs.remove(kind)
        resetAttemptsIfRecovered()
        statsFlow.update { it.copy(releaseAllCount = it.releaseAllCount + 1) }
        if (recovered) publishOpen()
        return true
    }

    /**
     * The host didn't fetch a [kind] report within the deadline: it isn't reading that endpoint. Its queued
     * input is dropped and a release owed; the other endpoint carries on, and nothing is reopened.
     */
    private fun markNotReading(kind: HidKind) {
        val dropped =
            synchronized(queueLock) {
                val count = queue.count { it.kind == kind }
                queue.removeAll { it.kind == kind }
                queueLengthFlow.value = queue.size
                if (kind !in releaseOwedKinds) bumpReleaseEpoch(setOf(kind))
                releaseOwedKinds += kind
                notReading += kind
                count
            }
        countDropped(dropped)
        lastProbeMs[kind] = nowMs()
        statsFlow.update { it.copy(lastError = "the computer isn't reading the ${kind.wireName}") }
        publishOpen()
    }

    /**
     * The reopen budget starts over only once every endpoint the host reads has taken its release: one
     * endpoint recovering while the other keeps failing must not reset it, or the reconnects never end.
     */
    private fun resetAttemptsIfRecovered() {
        if (synchronized(queueLock) { notReading.containsAll(releaseOwedKinds) }) failedAttempts = 0
    }

    /** Publishes Open with the current not-reading set, unless root has dropped the fds (the loop shows that). */
    private fun publishOpen() {
        synchronized(queueLock) {
            if (relayClosed != null) return
            val open = HidLinkState.Open(notReading.toSet())
            if (stateFlow.value != open) stateFlow.value = open
        }
    }

    private suspend fun writeOrRecover(
        kind: HidKind,
        report: ByteArray,
    ): Boolean {
        val endpoint = endpoints[kind] ?: return false
        when (writeWithDeadline(kind, endpoint, report)) {
            HidWriteResult.WRITTEN -> {
                resetAttemptsIfRecovered()
                return true
            }
            // Input the host didn't take within the deadline is stale; never replay it late.
            HidWriteResult.WOULD_BLOCK -> markNotReading(kind)
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
        stateFlow.value = HidLinkState.Reconnecting(failedAttempts, reason)
    }

    /** One nonblocking attempt, counted like [writeWithDeadline]'s. */
    private fun writeOnce(
        kind: HidKind,
        endpoint: HidEndpoint,
        report: ByteArray,
    ): HidWriteResult {
        check(report.size == kind.reportSizeBytes)
        val result =
            try {
                endpoint.write(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HidWriteResult.FAILED
            }
        if (result == HidWriteResult.WRITTEN) statsFlow.update { it.copy(reportsWritten = it.reportsWritten + 1) }
        return result
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
        bumpReleaseEpoch(kinds - releaseOwedKinds)
        releaseOwedKinds += kinds
        return count
    }

    private fun countDropped(count: Int) {
        if (count > 0) statsFlow.update { it.copy(reportsDropped = it.reportsDropped + count) }
    }

    private fun bumpReleaseEpoch(released: Set<HidKind>) {
        if (released.isEmpty()) return
        releaseEpochFlow.update { epochs -> epochs.mapValues { (kind, epoch) -> if (kind in released) epoch + 1 else epoch } }
    }

    private fun closeEndpoints() {
        for (endpoint in endpoints.values) runCatching { endpoint.close() }
        endpoints.clear()
    }

    private fun dropped(reason: HidOffer): HidOffer {
        statsFlow.update { it.copy(reportsDropped = it.reportsDropped + 1) }
        return reason
    }
}
