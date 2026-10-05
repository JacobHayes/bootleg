package dev.jacobhayes.bootleg.core.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [RemoteController] as a single actor: events from Go and the app are queued (bounded, with
 * a priority lane for close/revoke/release), handled one at a time on one coroutine, and the
 * resulting effects are dispatched to the ports in order.
 *
 * Bridge methods ([open], [command], [hid], [closed]) only enqueue, so they return within the
 * bridge's 20ms budget on Go's goroutine threads. Device calls run on [deviceDispatcher] with a
 * [RemoteLimits.DEVICE_OP_TIMEOUT_MS] deadline, one at a time: the device lane stays held until the
 * call really finishes, even after a timeout, and a call that finds it held answers `busy`.
 * Download calls ([DownloadPort.start]/[DownloadPort.cancel]) get their own lane with the same
 * rules, so they never wait on a gadget operation.
 *
 * Any failure in a step (the controller's invariants or a port throwing mid-dispatch) fails closed:
 * release-all, close every session Go holds, stop the loop and report it to [onFatal].
 */
class RemoteActor(
    private val controller: RemoteController,
    private val clock: RemoteClock,
    private val transport: TransportPort,
    private val device: DevicePort,
    private val hid: HidPort,
    private val downloads: DownloadPort,
    private val approvalStore: ApprovalStore,
    private val pairingStore: PairingStore,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
    private val deviceDispatcher: CoroutineDispatcher,
    /** For [DownloadPort.start]/[DownloadPort.cancel]; not the device lane's dispatcher, so neither waits on the other. */
    private val downloadDispatcher: CoroutineDispatcher,
    /** Called once if the controller trips an invariant; the actor has already failed closed. */
    private val onFatal: (Throwable) -> Unit = {},
    private val deviceTimeoutMs: Long = RemoteLimits.DEVICE_OP_TIMEOUT_MS,
    private val downloadTimeoutMs: Long = RemoteLimits.DOWNLOAD_OP_TIMEOUT_MS,
) {
    init {
        require(
            deviceTimeoutMs in 1..RemoteLimits.DEVICE_OP_TIMEOUT_MS,
        ) { "device timeout must be 1..${RemoteLimits.DEVICE_OP_TIMEOUT_MS} ms" }
        require(
            downloadTimeoutMs in 1..RemoteLimits.DOWNLOAD_OP_TIMEOUT_MS,
        ) { "download timeout must be 1..${RemoteLimits.DOWNLOAD_OP_TIMEOUT_MS} ms" }
    }

    private val lock = Any()
    private val priority = ArrayDeque<RemoteEvent>()
    private val inbound = LinkedHashMap<Long, ArrayDeque<RemoteEvent>>()

    // Coalescing keys for priority events that must never be dropped or duplicated.
    private val closedQueued = HashSet<Long>()
    private val releaseQueued = HashSet<Long>()
    private val overflowQueued = HashSet<Long>()
    private val inputOverflowQueued = HashSet<Long>()
    private val sendFailedQueued = HashSet<Long>()
    private var hidRejectedQueued = false

    /** Sessions Go opened and hasn't reported closed; failing closed closes them all. */
    private val openSessions = HashSet<Long>()

    /** Set once a step fails; the bridge then queues nothing and closes new sessions at once. */
    private var failed = false
    private var pendingSnapshot: DeviceSnapshot? = null
    private var pendingCatalog: CatalogSnapshot? = null
    private var pendingDownloads: List<RemoteDownload>? = null

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val deviceLane = Semaphore(1)
    private val downloadLane = Semaphore(1)
    private val statusFlow = MutableStateFlow(controller.status())
    private var loop: Job? = null

    val status: StateFlow<RemoteStatus> = statusFlow.asStateFlow()

    fun start() {
        check(loop == null) { "already started" }
        loop = scope.launch { runLoop() }
        scope.launch { device.snapshots.collect { deviceChanged(it) } }
        scope.launch {
            downloads.catalog.collect { snapshot ->
                synchronized(lock) { pendingCatalog = snapshot }
                wakeUp.trySend(Unit)
            }
        }
        scope.launch {
            downloads.downloads.collect { list ->
                synchronized(lock) { pendingDownloads = list }
                wakeUp.trySend(Unit)
            }
        }
    }

    // ---- Go → Kotlin bridge. All nonblocking.

    /** Never dropped (Go bounds connections), so Kotlin always learns about a session Go holds open. */
    fun open(
        sessionId: Long,
        connJson: String,
    ) {
        val accepted =
            synchronized(lock) {
                if (!failed) {
                    check(priority.size < PRIORITY_QUEUE_HARD_MAX) { "priority lane overflow" }
                    openSessions += sessionId
                    priority.addLast(RemoteEvent.Open(sessionId, connJson))
                }
                !failed
            }
        if (accepted) wakeUp.trySend(Unit) else transport.close(sessionId, CloseReason.SHUTDOWN)
    }

    /** A command that doesn't fit the session's queue closes the session (`rate_limited`). */
    fun command(
        sessionId: Long,
        json: String,
    ) {
        if (enqueueInbound(sessionId, RemoteEvent.Command(sessionId, json))) return
        synchronized(lock) {
            if (failed) return
            if (overflowQueued.add(sessionId)) priority.addLast(RemoteEvent.CommandOverflow(sessionId))
        }
        wakeUp.trySend(Unit)
    }

    /** False (`queue_full`, 1 on the bridge): the frame was dropped; held input is released on the priority lane. */
    fun hid(
        sessionId: Long,
        frame: ByteArray,
    ): Boolean {
        if (HidFrameCodec.opOf(frame) == HidFrameCodec.OP_RELEASE_ALL && frame.size == HID_RELEASE_FRAME_SIZE_BYTES) {
            // Release-all overtakes queued input, so the input it supersedes is dropped with it.
            synchronized(lock) {
                if (failed) return false
                inbound[sessionId]?.removeAll { it is RemoteEvent.Hid }
                if (!releaseQueued.add(sessionId)) return true
                priority.addLast(RemoteEvent.Hid(sessionId, frame.copyOf()))
            }
            wakeUp.trySend(Unit)
            return true
        }
        if (enqueueInbound(sessionId, RemoteEvent.Hid(sessionId, frame.copyOf()))) return true
        synchronized(lock) {
            if (failed) return false
            if (inputOverflowQueued.add(sessionId)) priority.addLast(RemoteEvent.InputOverflow(sessionId))
        }
        wakeUp.trySend(Unit)
        return false
    }

    /** Idempotent; never dropped. */
    fun closed(
        sessionId: Long,
        reason: String,
    ) {
        synchronized(lock) {
            if (failed || !closedQueued.add(sessionId)) return
            check(priority.size < PRIORITY_QUEUE_HARD_MAX) { "priority lane overflow" }
            priority.addLast(RemoteEvent.Closed(sessionId, reason))
        }
        wakeUp.trySend(Unit)
    }

    // ---- App → controller.

    fun approve(
        nodeId: String,
        allowRw: Boolean,
    ) = enqueuePriority(RemoteEvent.Approve(nodeId, allowRw))

    fun deny(nodeId: String) = enqueuePriority(RemoteEvent.Deny(nodeId))

    fun revoke(principal: Principal) = enqueuePriority(RemoteEvent.Revoke(principal))

    fun setAllowRw(
        principal: Principal,
        allowRw: Boolean,
    ) = enqueuePriority(RemoteEvent.SetAllowRw(principal, allowRw))

    fun enablePairing() = enqueuePriority(RemoteEvent.PairingEnable)

    fun disablePairing() = enqueuePriority(RemoteEvent.PairingDisable)

    fun resetPairingLockout() = enqueuePriority(RemoteEvent.PairingLockoutReset)

    fun hotspotStopped() = enqueuePriority(RemoteEvent.HotspotStopped)

    fun localCaptureStarted() = enqueuePriority(RemoteEvent.LocalCaptureStarted)

    fun localCaptureStopped() = enqueuePriority(RemoteEvent.LocalCaptureStopped)

    /** Optional: the actor also polls [HidPort.releaseOwed] while the barrier is up. */
    fun hidReleaseDelivered() = enqueuePriority(RemoteEvent.HidReleaseDelivered)

    fun shutdown() = enqueuePriority(RemoteEvent.Shutdown)

    private fun deviceChanged(snapshot: DeviceSnapshot) {
        synchronized(lock) { pendingSnapshot = snapshot }
        wakeUp.trySend(Unit)
    }

    // ---- Queues.

    private fun enqueuePriority(event: RemoteEvent): Boolean {
        synchronized(lock) {
            if (failed || priority.size >= RemoteLimits.PRIORITY_QUEUE_LENGTH_MAX) return false
            priority.addLast(event)
        }
        wakeUp.trySend(Unit)
        return true
    }

    private fun enqueueInbound(
        sessionId: Long,
        event: RemoteEvent,
    ): Boolean {
        synchronized(lock) {
            if (failed) return false
            val queue =
                inbound[sessionId] ?: run {
                    if (inbound.size >= SESSION_QUEUE_COUNT_MAX) return false
                    ArrayDeque<RemoteEvent>().also { inbound[sessionId] = it }
                }
            if (queue.size >= RemoteLimits.INBOUND_QUEUE_LENGTH_MAX) return false
            queue.addLast(event)
        }
        wakeUp.trySend(Unit)
        return true
    }

    /** Priority first (including the latest device snapshot), then sessions round-robin. */
    private fun poll(): RemoteEvent? =
        synchronized(lock) {
            priority.removeFirstOrNull()?.let { event ->
                when (event) {
                    is RemoteEvent.Closed -> {
                        closedQueued.remove(event.sessionId)
                        inbound.remove(event.sessionId)
                        openSessions.remove(event.sessionId)
                    }
                    is RemoteEvent.Hid -> releaseQueued.remove(event.sessionId)
                    is RemoteEvent.InputOverflow -> inputOverflowQueued.remove(event.sessionId)
                    is RemoteEvent.CommandOverflow -> overflowQueued.remove(event.sessionId)
                    is RemoteEvent.SendFailed -> sendFailedQueued.remove(event.sessionId)
                    is RemoteEvent.HidRejected -> hidRejectedQueued = false
                    else -> Unit
                }
                return event
            }
            pendingSnapshot?.let {
                pendingSnapshot = null
                return RemoteEvent.DeviceChanged(it)
            }
            pendingDownloads?.let {
                pendingDownloads = null
                return RemoteEvent.DownloadsChanged(it)
            }
            pendingCatalog?.let {
                pendingCatalog = null
                return RemoteEvent.CatalogChanged(it)
            }
            val sessionId = inbound.entries.firstOrNull { it.value.isNotEmpty() }?.key ?: return null
            val queue = inbound.remove(sessionId)!!
            val event = queue.removeFirst()
            if (queue.isNotEmpty()) inbound[sessionId] = queue
            event
        }

    // ---- Loop.

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val event = poll()
            if (event != null) {
                step(event)
                continue
            }
            if (controller.releaseBarrierActive && !hid.releaseOwed) {
                step(RemoteEvent.HidReleaseDelivered)
                continue
            }
            val waitMs = controller.nextWakeAtElapsedMs() - clock.elapsedMs()
            if (waitMs <= 0) {
                step(RemoteEvent.Tick)
                continue
            }
            // Poll the HID session quickly while input waits on the barrier, slowly once it's reported pending.
            val barrierPolling = controller.releaseBarrierActive && !statusFlow.value.releasePending
            withTimeoutOrNull(if (barrierPolling) minOf(waitMs, RemoteLimits.RELEASE_BARRIER_POLL_MS) else waitMs) { wakeUp.receive() }
        }
    }

    /** One failure path for the whole step: a throw anywhere leaves effects undispatched, so fail closed. */
    private fun step(event: RemoteEvent) {
        try {
            val effects = controller.handle(event)
            for (effect in effects) dispatch(effect)
            statusFlow.value = controller.status()
        } catch (failure: Throwable) {
            failClosed(failure)
            throw failure
        }
    }

    /** Best effort, each port call guarded on its own, so one failing port can't skip the rest. */
    private fun failClosed(failure: Throwable) {
        runCatching { hid.releaseAll() }
        val sessionIds =
            synchronized(lock) {
                failed = true
                priority.clear()
                inbound.clear()
                openSessions + statusFlow.value.sessions.map { it.sessionId }
            }
        for (sessionId in sessionIds) runCatching { transport.close(sessionId, CloseReason.SHUTDOWN) }
        runCatching { onFatal(failure) }
    }

    private fun dispatch(effect: RemoteEffect) {
        when (effect) {
            is RemoteEffect.Authorize -> transport.authorized(effect.sessionId)
            is RemoteEffect.Send ->
                if (!transport.send(effect.sessionId, effect.json)) {
                    synchronized(lock) {
                        if (sendFailedQueued.add(effect.sessionId)) priority.addLast(RemoteEvent.SendFailed(effect.sessionId))
                    }
                }
            is RemoteEffect.Close -> {
                transport.close(effect.sessionId, effect.reason)
                synchronized(lock) { inbound.remove(effect.sessionId) }
            }
            is RemoteEffect.Revoke -> transport.revoke(effect.principal)
            is RemoteEffect.DropQueuedInput -> synchronized(lock) { inbound[effect.sessionId]?.removeAll { it is RemoteEvent.Hid } }
            is RemoteEffect.HidSend -> {
                val result = hid.send(effect.kind, effect.report)
                // NOT_READING drops only that report: the session owes nothing new for it.
                if (result != HidSendResult.ACCEPTED && result != HidSendResult.NOT_READING) {
                    synchronized(lock) {
                        if (!hidRejectedQueued) {
                            hidRejectedQueued = true
                            priority.addLast(RemoteEvent.HidRejected(result))
                        }
                    }
                }
            }
            is RemoteEffect.HidReleaseAll -> hid.releaseAll()
            is RemoteEffect.Device -> startDeviceCall(effect.opId, effect.op)
            is RemoteEffect.Download -> startDownloadCall(effect.opId, effect.op)
            is RemoteEffect.CatalogRefreshIfStale -> downloads.refreshCatalogIfStale()
            is RemoteEffect.SaveApprovals -> approvalStore.save(effect.records)
            is RemoteEffect.SavePairing -> pairingStore.save(effect.record)
            is RemoteEffect.PendingChanged -> notifier.pendingChanged(effect.pending)
            is RemoteEffect.LeaseChanged -> Unit
        }
    }

    private fun startDeviceCall(
        opId: Long,
        op: DeviceOp,
    ) {
        if (!deviceLane.tryAcquire()) {
            // Only a call that timed out can still hold the lane: the controller runs one call at a time.
            enqueueResult(RemoteEvent.DeviceResult(opId, DeviceOutcome.Busy))
            return
        }
        // A started gadget operation finishes atomically, so a timeout stops waiting but never cancels it.
        val work =
            scope.async(deviceDispatcher) {
                runCatching {
                    when (op) {
                        is DeviceOp.Apply -> DeviceOutcome.of(device.apply(op.desired))
                        is DeviceOp.Eject -> DeviceOutcome.of(device.eject(op.lun, op.force))
                        is DeviceOp.Reinsert -> DeviceOutcome.of(device.reinsert(op.lun))
                        is DeviceOp.SetHid -> DeviceOutcome.of(device.setHid(op.spec))
                        is DeviceOp.SetUsbTethering -> DeviceOutcome.of(device.setUsbTethering(op.on))
                    }
                }
            }
        // Fires only once the body has returned (or never started), so the lane covers the real call.
        work.invokeOnCompletion { deviceLane.release() }
        scope.launch {
            val result = withTimeoutOrNull(timeoutMsFor(op)) { work.await() }
            val outcome =
                when {
                    result == null -> DeviceOutcome.TimedOut
                    result.isFailure -> DeviceOutcome.Failed(result.exceptionOrNull()?.message ?: "device operation failed")
                    else -> result.getOrThrow()
                }
            enqueueResult(RemoteEvent.DeviceResult(opId, outcome))
        }
    }

    /** Tethering may set the prefix up and then waits on Android, so it gets the longer deadline (scaled like [deviceTimeoutMs]). */
    private fun timeoutMsFor(op: DeviceOp): Long =
        if (op is DeviceOp.SetUsbTethering) {
            deviceTimeoutMs * RemoteLimits.TETHER_OP_TIMEOUT_MS / RemoteLimits.DEVICE_OP_TIMEOUT_MS
        } else {
            deviceTimeoutMs
        }

    private fun startDownloadCall(
        opId: Long,
        op: DownloadOp,
    ) {
        if (!downloadLane.tryAcquire()) {
            // Only a call that timed out can still hold the lane: the controller runs one call at a time.
            enqueueResult(RemoteEvent.DownloadResult(opId, DownloadOutcome.Busy))
            return
        }
        // Like a gadget operation, a started enqueue or cancel finishes even if nobody waits for it any more.
        val work =
            scope.async(downloadDispatcher) {
                runCatching {
                    when (op) {
                        is DownloadOp.Start -> downloads.start(op.release, op.distroName)
                        is DownloadOp.Cancel -> downloads.cancel(op.fileName)
                    }
                }
            }
        work.invokeOnCompletion { downloadLane.release() }
        scope.launch {
            val result = withTimeoutOrNull(downloadTimeoutMs) { work.await() }
            val outcome =
                when {
                    result == null -> DownloadOutcome.TimedOut
                    result.isFailure -> DownloadOutcome.Rejected(result.exceptionOrNull()?.message ?: "download request failed")
                    else -> result.getOrThrow()
                }
            enqueueResult(RemoteEvent.DownloadResult(opId, outcome))
        }
    }

    /** Device and download results bypass the soft priority bound: the controller waits on every one. */
    private fun enqueueResult(result: RemoteEvent) {
        synchronized(lock) {
            if (failed) return
            priority.addLast(result)
        }
        wakeUp.trySend(Unit)
    }

    companion object {
        private const val HID_RELEASE_FRAME_SIZE_BYTES = 3

        /** Closed/DeviceResult bypass the soft priority bound; this catches a runaway producer. */
        private const val PRIORITY_QUEUE_HARD_MAX = 4 * RemoteLimits.PRIORITY_QUEUE_LENGTH_MAX

        /** Session queues alive at once: live connections plus ones Go is still closing. */
        private const val SESSION_QUEUE_COUNT_MAX = 4 * RemoteLimits.CONNECTION_COUNT_MAX

        /** Loads the stores and builds the controller and actor. */
        fun create(
            clock: RemoteClock,
            random: RandomSource,
            transport: TransportPort,
            device: DevicePort,
            hid: HidPort,
            downloads: DownloadPort,
            approvalStore: ApprovalStore,
            pairingStore: PairingStore,
            notifier: Notifier,
            scope: CoroutineScope,
            deviceDispatcher: CoroutineDispatcher,
            downloadDispatcher: CoroutineDispatcher,
            onFatal: (Throwable) -> Unit = {},
        ): RemoteActor =
            RemoteActor(
                RemoteController(clock, random, approvalStore.load(), pairingStore.load()),
                clock,
                transport,
                device,
                hid,
                downloads,
                approvalStore,
                pairingStore,
                notifier,
                scope,
                deviceDispatcher,
                downloadDispatcher,
                onFatal,
            )
    }
}
