package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.gadget.CdromLimits
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetLimits
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.MouseLayout
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import dev.jacobhayes.bootleg.core.hid.BootKey
import dev.jacobhayes.bootleg.core.hid.BootKeyMasher
import dev.jacobhayes.bootleg.core.hid.BootKeyPresets
import dev.jacobhayes.bootleg.core.hid.BootVendor
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.HidLimits
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.hid.KeyboardReport
import dev.jacobhayes.bootleg.core.hid.MouseReport
import dev.jacobhayes.bootleg.core.hid.TextTyper
import dev.jacobhayes.bootleg.core.hid.TimedReport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/**
 * All remote-control authority: authorization, pairing, sessions, the lease, mutation gating,
 * input ownership and paste/boot-key jobs.
 *
 * Pure and deterministic: [handle] maps one event (at the [clock]'s current time) to an ordered
 * list of effects and never calls a port. One actor ([RemoteActor]) calls it at a time.
 *
 * HID effects come first in each step, in the order they were emitted, so a release-all is
 * dispatched before any transport or store call that could fail and abort the rest of the step.
 * Moving reports ahead of non-HID effects is safe: each was fenced against the lease when emitted,
 * and HID effects keep their relative order, so a release-all still follows the reports it clears.
 */
class RemoteController(
    private val clock: RemoteClock,
    private val random: RandomSource,
    approvals: List<ApprovalRecord> = emptyList(),
    pairing: PairingRecord = PairingRecord(),
) {
    private enum class SessionAuth { APPROVED, PENDING, PAIRING }

    private class Session(
        val id: Long,
        val conn: ConnInfo,
        var auth: SessionAuth,
        var principal: Principal?,
        nowMs: Long,
    ) {
        var helloDone = false

        /** A `pair` exchange gets exactly one attempt. */
        var pairAttempted = false

        /** Request id (its JSON text) → cached reply, or null while the reply is still in flight. Insertion order = age. */
        val replies = LinkedHashMap<String, String?>()
        val plans = LinkedHashMap<String, PastePlan>()
        var stateDirty = false
        var leaseDirty = false
        var catalogDirty = false
        var downloadsDirty = false
        var lastStatePushMs = nowMs - RemoteLimits.PUSH_INTERVAL_MS_MIN
        var lastLeasePushMs = nowMs - RemoteLimits.PUSH_INTERVAL_MS_MIN
        var lastCatalogPushMs = nowMs - RemoteLimits.PUSH_INTERVAL_MS_MIN
        var lastDownloadsPushMs = nowMs - RemoteLimits.DOWNLOAD_PUSH_INTERVAL_MS_MIN
        var frameBudgetMilli = FRAME_BUDGET_MILLI_MAX
        var frameBudgetRefilledAtMs = nowMs

        val displayName: String get() = conn.displayName
    }

    private class PastePlan(
        val text: String,
        val epoch: Int,
        val expiresAtMs: Long,
    )

    private class Job(
        val kind: JobKind,
        val sessionId: Long,
        val requestId: JsonPrimitive,
        val epoch: Int,
        val reports: List<TimedReport>,
        val startMs: Long,
        val durationMs: Long,
    ) {
        var nextIndex = 0
        val nextAtMs: Long get() = startMs + reports[nextIndex].atMs
    }

    private class Takeover(
        val requesterId: Long,
        val requestId: JsonPrimitive,
        val holderId: Long,
        val epoch: Int,
        val deadlineMs: Long,
    )

    private class DeviceCall(
        val opId: Long,
        val sessionId: Long,
        val requestId: JsonPrimitive,
        val timeoutMs: Long,
    )

    private class CatalogEntry(
        val release: Release,
        val distroName: String,
    )

    private class DownloadCall(
        val opId: Long,
        val sessionId: Long,
        val requestId: JsonPrimitive,
        val entryId: String,
        val fileName: String,
        val isStart: Boolean,
    )

    private class Barrier(
        val owedSinceMs: Long,
        var nextRetryMs: Long,
        var retryDelayMs: Long,
        var pendingReported: Boolean = false,
    )

    private class PairingCode(
        val code: String,
        val expiresAtMs: Long,
    )

    /** What the lease holder currently holds down on the host. */
    private class HeldInput {
        var modifierBits = 0
        val keys = LinkedHashSet<KeyUsage>()
        var buttonBits = 0

        val keyboardHeld: Boolean get() = modifierBits != 0 || keys.isNotEmpty()

        fun clear() {
            modifierBits = 0
            keys.clear()
            buttonBits = 0
        }
    }

    private sealed interface Outcome {
        data class Ok(
            val result: JsonObject?,
        ) : Outcome

        data class Err(
            val code: ErrorCode,
            val msg: String,
        ) : Outcome

        data object Deferred : Outcome
    }

    private val sessions = LinkedHashMap<Long, Session>()
    private val approvalsByNode = LinkedHashMap<String, ApprovalRecord>()
    private val pending = LinkedHashMap<String, PendingApproval>()
    private var pairing: PairingRecord = pairing
    private var pairingCode: PairingCode? = null
    private var leaseHolder: LeaseHolder? = null
    private var leaseEpoch = 0
    private var localCapture = false
    private var device = DeviceSnapshot()
    private var stateRev = 1L
    private var deviceCall: DeviceCall? = null
    private var nextOpId = 1L
    private var job: Job? = null
    private var takeover: Takeover? = null
    private val held = HeldInput()
    private var barrier: Barrier? = null
    private var lastInputMs = 0L
    private var catalog = CatalogSnapshot()
    private var catalogRev = 1L
    private val catalogEntries = LinkedHashMap<String, CatalogEntry>()
    private var downloads: List<RemoteDownload> = emptyList()
    private var downloadCall: DownloadCall? = null
    private var catalogRefreshRequestedAtMs: Long? = null

    private var nowMs = 0L
    private var wallMs = 0L
    private var effects = ArrayList<RemoteEffect>()
    private var handling = false

    init {
        for (record in approvals.sortedBy { it.approvedAtMs }.takeLast(RemoteLimits.APPROVAL_COUNT_MAX)) {
            approvalsByNode[record.nodeId] = record
        }
        this.pairing =
            pairing.copy(
                tokens = pairing.tokens.sortedBy { it.createdAtMs }.takeLast(RemoteLimits.TOKEN_COUNT_MAX),
                attemptAtMs = pairing.attemptAtMs.takeLast(RemoteLimits.PAIRING_ATTEMPT_COUNT_MAX),
            )
        nowMs = clock.elapsedMs()
        lastInputMs = nowMs
    }

    val leaseView: Pair<LeaseHolder?, Int> get() = leaseHolder to leaseEpoch

    /** The owed-release barrier is up; the actor polls [HidPort] and reports [RemoteEvent.HidReleaseDelivered]. */
    val releaseBarrierActive: Boolean get() = barrier != null

    fun handle(event: RemoteEvent): List<RemoteEffect> {
        check(!handling) { "RemoteController is not reentrant" }
        handling = true
        try {
            val elapsed = clock.elapsedMs()
            check(elapsed >= nowMs) { "elapsed clock went backwards: $elapsed < $nowMs" }
            nowMs = elapsed
            wallMs = clock.wallMs()
            advance()
            onEvent(event)
            // Jobs run after the event, so a close, revoke or cancel stops them before any more reports.
            runJob()
            flushPushes()
            checkInvariants()
            return dispatchOrder(effects).also { effects = ArrayList() }
        } finally {
            handling = false
        }
    }

    /** When the actor should deliver the next [RemoteEvent.Tick], on the elapsed clock. */
    fun nextWakeAtElapsedMs(): Long {
        var wakeMs = nowMs + RemoteLimits.TICK_INTERVAL_MS_MAX
        job?.let { wakeMs = minOf(wakeMs, it.nextAtMs) }
        takeover?.let { wakeMs = minOf(wakeMs, it.deadlineMs) }
        barrier?.let {
            if (!it.pendingReported) wakeMs = minOf(wakeMs, it.nextRetryMs, it.owedSinceMs + RemoteLimits.RELEASE_RETRY_WINDOW_MS)
        }
        pairingCode?.let { wakeMs = minOf(wakeMs, it.expiresAtMs) }
        for (session in sessions.values) {
            if (session.stateDirty) wakeMs = minOf(wakeMs, session.lastStatePushMs + RemoteLimits.PUSH_INTERVAL_MS_MIN)
            if (session.leaseDirty) wakeMs = minOf(wakeMs, session.lastLeasePushMs + RemoteLimits.PUSH_INTERVAL_MS_MIN)
            if (session.catalogDirty) wakeMs = minOf(wakeMs, session.lastCatalogPushMs + RemoteLimits.PUSH_INTERVAL_MS_MIN)
            if (session.downloadsDirty) {
                wakeMs = minOf(wakeMs, session.lastDownloadsPushMs + RemoteLimits.DOWNLOAD_PUSH_INTERVAL_MS_MIN)
            }
        }
        return wakeMs
    }

    fun status(): RemoteStatus =
        RemoteStatus(
            sessions =
                sessions.values.map {
                    SessionStatus(
                        sessionId = it.id,
                        transport = it.conn.transport,
                        displayName = it.displayName,
                        auth = if (it.auth == SessionAuth.APPROVED) AuthStatus.APPROVED else AuthStatus.PENDING,
                        holdsLease = leaseHolder == LeaseHolder.Remote(it.id),
                    )
                },
            pending = pending.values.toList(),
            approvals = approvalsByNode.values.toList(),
            tokens = pairing.tokens,
            leaseHolder = leaseHolder,
            leaseEpoch = leaseEpoch,
            pairing =
                PairingStatus(
                    code = pairingCode?.code,
                    codeExpiresAtElapsedMs = pairingCode?.expiresAtMs,
                    lockedOut = pairing.lockedOut,
                    attemptsInWindow = pairing.attemptAtMs.count { it > wallMs - RemoteLimits.PAIRING_ATTEMPT_WINDOW_MS },
                ),
            releasePending = barrier?.pendingReported == true,
            runningJob = job?.kind,
        )

    // ---- Events.

    private fun onEvent(event: RemoteEvent) {
        when (event) {
            is RemoteEvent.Open -> onOpen(event.sessionId, event.connJson)
            is RemoteEvent.Command -> sessions[event.sessionId]?.let { onCommand(it, event.json) }
            is RemoteEvent.Hid -> sessions[event.sessionId]?.let { onHidFrame(it, event.frame) }
            is RemoteEvent.Closed -> sessions[event.sessionId]?.let { endSession(it) }
            is RemoteEvent.Tick -> Unit
            is RemoteEvent.SendFailed -> sessions[event.sessionId]?.let { closeSession(it, CloseReason.SLOW_CONSUMER) }
            is RemoteEvent.CommandOverflow -> sessions[event.sessionId]?.let { closeSession(it, CloseReason.RATE_LIMITED) }
            is RemoteEvent.InputOverflow -> if (leaseHolder == LeaseHolder.Remote(event.sessionId)) abortInput("input overflowed")
            is RemoteEvent.HidRejected -> onHidRejected()
            is RemoteEvent.DeviceResult -> onDeviceResult(event.opId, event.outcome)
            is RemoteEvent.DeviceChanged -> onDeviceChanged(event.snapshot)
            is RemoteEvent.DownloadResult -> onDownloadResult(event.opId, event.outcome)
            is RemoteEvent.CatalogChanged -> onCatalogChanged(event.snapshot)
            is RemoteEvent.DownloadsChanged -> onDownloadsChanged(event.downloads)
            is RemoteEvent.HidReleaseDelivered -> onReleaseDelivered()
            is RemoteEvent.LocalCaptureStarted -> onLocalCaptureStarted()
            is RemoteEvent.LocalCaptureStopped -> onLocalCaptureStopped()
            is RemoteEvent.Approve -> onApprove(event.nodeId, event.allowRw)
            is RemoteEvent.Deny -> onDeny(event.nodeId)
            is RemoteEvent.Revoke -> onRevoke(event.principal)
            is RemoteEvent.SetAllowRw -> onSetAllowRw(event.principal, event.allowRw)
            is RemoteEvent.PairingEnable -> onPairingEnable()
            is RemoteEvent.PairingDisable -> pairingCode = null
            is RemoteEvent.PairingLockoutReset -> {
                pairing = pairing.copy(lockedOut = false, attemptAtMs = emptyList())
                emit(RemoteEffect.SavePairing(pairing))
            }
            is RemoteEvent.HotspotStopped -> {
                pairingCode = null
                for (session in sessions.values.filter { it.conn.transport == ConnInfo.TRANSPORT_HOTSPOT }) {
                    closeSession(session, CloseReason.LISTENER_STOPPED)
                }
            }
            is RemoteEvent.Shutdown -> {
                pairingCode = null
                for (session in sessions.values.toList()) closeSession(session, CloseReason.SHUTDOWN)
                emit(RemoteEffect.HidReleaseAll)
                held.clear()
            }
        }
    }

    private fun onOpen(
        sessionId: Long,
        connJson: String,
    ) {
        if (sessionId in sessions) return
        val conn = ConnInfo.parse(connJson) ?: return emit(RemoteEffect.Close(sessionId, CloseReason.MALFORMED))
        if (sessions.size >= RemoteLimits.CONNECTION_COUNT_MAX) return emit(RemoteEffect.Close(sessionId, CloseReason.TOO_MANY_CONNECTIONS))
        val principal = validPrincipalFor(conn.identity)
        if (principal == null && conn.identity is ConnIdentity.Token) return emit(RemoteEffect.Close(sessionId, CloseReason.UNAUTHORIZED))
        if (principal == null && unauthorizedSessionCount() >= RemoteLimits.PENDING_CONNECTION_COUNT_MAX) {
            return emit(RemoteEffect.Close(sessionId, CloseReason.TOO_MANY_CONNECTIONS))
        }
        val auth =
            when {
                principal != null -> SessionAuth.APPROVED
                conn.identity is ConnIdentity.Pairing -> SessionAuth.PAIRING
                else -> SessionAuth.PENDING
            }
        sessions[sessionId] = Session(sessionId, conn, auth, principal, nowMs)
        if (principal != null) emit(RemoteEffect.Authorize(sessionId))
        if (auth == SessionAuth.PENDING) addPending(conn.identity as ConnIdentity.Node)
    }

    private fun addPending(identity: ConnIdentity.Node) {
        if (identity.nodeId in pending) return
        if (pending.size >= RemoteLimits.PENDING_APPROVAL_COUNT_MAX) {
            val evictedNodeId = pending.keys.first()
            pending.remove(evictedNodeId)
            for (session in pendingSessionsOf(evictedNodeId)) closeSession(session, CloseReason.PENDING_EVICTED)
        }
        pending[identity.nodeId] =
            PendingApproval(
                nodeId = identity.nodeId,
                displayName = identity.name.ifBlank { identity.nodeId },
                user = identity.user,
                tags = identity.tags,
                firstSeenWallMs = wallMs,
            )
        emit(RemoteEffect.PendingChanged(pending.values.toList()))
    }

    private fun onCommand(
        session: Session,
        text: String,
    ) {
        if (text.length > RemoteLimits.COMMAND_SIZE_CHARS_MAX) return closeSession(session, CloseReason.MALFORMED)
        val message =
            runCatching { ProtocolJson.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return closeSession(session, CloseReason.MALFORMED)
        val type = message.stringField("t") ?: return closeSession(session, CloseReason.MALFORMED)
        if (session.auth == SessionAuth.PAIRING) return onPairCommand(session, type, message)
        if (type == "pair") return closeSession(session, CloseReason.MALFORMED)
        if (type == "hello") return onHello(session, message)
        val id = requestIdOf(message) ?: return closeSession(session, CloseReason.MALFORMED)
        val key = id.toString()
        if (session.replies.containsKey(key)) {
            // A duplicate gets the cached reply; one still in flight is answered once, when it completes.
            session.replies[key]?.let { emit(RemoteEffect.Send(session.id, it)) }
            return
        }
        when (val outcome = dispatch(session, id, type, message)) {
            is Outcome.Deferred -> {
                session.replies[key] = null
                trimReplyCache(session)
            }
            else -> finishReply(session, id, outcome)
        }
    }

    /** A string or integer id of at most [RemoteLimits.REQUEST_ID_LENGTH_MAX] characters, echoed as sent. */
    private fun requestIdOf(message: JsonObject): JsonPrimitive? {
        val id = message["id"] as? JsonPrimitive ?: return null
        if (id.toString().length > RemoteLimits.REQUEST_ID_LENGTH_MAX) return null
        return when {
            id.isString -> id.takeIf { it.content.isNotEmpty() }
            id.longOrNull != null -> id
            else -> null
        }
    }

    private fun onHello(
        session: Session,
        message: JsonObject,
    ) {
        if (session.helloDone) return closeSession(session, CloseReason.MALFORMED)
        val hello =
            runCatching { decode(RequestBody.serializer(), JsonObject(message - "id")) as? HelloRequest }.getOrNull()
                ?: return closeSession(session, CloseReason.MALFORMED)
        if (hello.v != RemoteLimits.PROTOCOL_VERSION) return closeSession(session, CloseReason.VERSION)
        session.helloDone = true
        val approved = session.auth == SessionAuth.APPROVED
        send(
            session,
            WelcomePush(
                v = RemoteLimits.PROTOCOL_VERSION,
                sessionId = session.id,
                auth = if (approved) AuthStatus.APPROVED else AuthStatus.PENDING,
                allowRw = allowRw(session),
                stateRev = if (approved) stateRev else null,
                lease = if (approved) leaseViewFor() else null,
            ),
        )
        if (approved) {
            session.stateDirty = true
            session.downloadsDirty = true
        }
    }

    private fun dispatch(
        session: Session,
        id: JsonPrimitive,
        type: String,
        message: JsonObject,
    ): Outcome {
        if (type !in ProtocolJson.requestTypes) return err(ErrorCode.UNKNOWN_TYPE, "unknown request type")
        if (!session.helloDone) return err(ErrorCode.HELLO_REQUIRED, "send hello first")
        val epochElement = message["epoch"]
        val epoch: Int? =
            if (epochElement == null) {
                null
            } else {
                (epochElement as? JsonPrimitive)
                    ?.takeIf { !it.isString }
                    ?.intOrNull
                    ?.takeIf { it in 0 until RemoteLimits.LEASE_EPOCH_MODULUS }
                    ?: return err(ErrorCode.BAD_REQUEST, "epoch must be an integer 0..65535")
            }
        val body =
            runCatching { decode(RequestBody.serializer(), JsonObject(message - "id" - "epoch")) }
                .getOrElse { return err(ErrorCode.BAD_REQUEST, (it.message ?: "malformed request").take(ERROR_MESSAGE_LENGTH_MAX)) }
        if (session.auth != SessionAuth.APPROVED) return err(ErrorCode.PENDING, "waiting for approval on the phone")
        return when (body) {
            is HelloRequest, is PairRequest -> err(ErrorCode.BAD_REQUEST, "unexpected $type")
            is StateGetRequest -> ok(StateResult.serializer(), StateResult(stateView(), leaseViewFor()))
            is LeaseTakeRequest -> onLeaseTake(session)
            is LeaseTakeoverRequest -> onLeaseTakeover(session, id, body.holderSession, epoch)
            is LeaseTakeoverReplyRequest -> onTakeoverReply(session, epoch, body.allow)
            is LeaseReleaseRequest -> admit(session, epoch) ?: onLeaseRelease()
            is SlotsApplyRequest -> admit(session, epoch) ?: onSlotsApply(session, id, body)
            is SlotEjectRequest ->
                admit(session, epoch) ?: admitDevice(body.ifRev) ?: run {
                    if (device.slots.none { it.lun == body.lun }) return err(ErrorCode.NOT_FOUND, "no slot ${body.lun}")
                    startDeviceCall(session, id, DeviceOp.Eject(body.lun, body.force))
                }
            is SlotReinsertRequest ->
                admit(session, epoch) ?: admitDevice(body.ifRev) ?: run {
                    val slot = device.slots.firstOrNull { it.lun == body.lun } ?: return err(ErrorCode.NOT_FOUND, "no slot ${body.lun}")
                    if (slot.mode == LunMode.DISK_RW &&
                        !allowRw(session)
                    ) {
                        return err(ErrorCode.RW_NOT_ALLOWED, "writable disks are not allowed")
                    }
                    startDeviceCall(session, id, DeviceOp.Reinsert(body.lun))
                }
            is HidSetRequest ->
                admit(session, epoch) ?: admitDevice(body.ifRev)
                    // The mouse layout is a phone setting; remote only turns the functions on and off.
                    ?: startDeviceCall(session, id, DeviceOp.SetHid(device.hid.copy(keyboard = body.keyboard, mouse = body.mouse)))
            // Whether a disk is exposed is decided by root from the live LUNs: the snapshot can lag a host eject.
            is TetherSetRequest ->
                admit(session, epoch) ?: admitDevice(body.ifRev)
                    ?: startDeviceCall(session, id, DeviceOp.SetUsbTethering(body.on))
            is BootKeyRunRequest -> admit(session, epoch) ?: onBootKeyRun(session, id, body)
            is BootKeyCancelRequest -> admit(session, epoch) ?: onJobCancel(JobKind.BOOTKEY)
            is PastePrepareRequest -> admit(session, epoch) ?: onPastePrepare(session, body.text)
            is PasteRunRequest -> admit(session, epoch) ?: onPasteRun(session, id, body.planId)
            is PasteCancelRequest -> admit(session, epoch) ?: onJobCancel(JobKind.PASTE)
            is CatalogListRequest -> onCatalogList(body)
            is DownloadStartRequest -> admit(session, epoch) ?: onDownloadStart(session, id, body.entryId)
            is DownloadCancelRequest -> admit(session, epoch) ?: onDownloadCancel(session, id, body.entryId)
        }
    }

    /** Lease + epoch fence for every mutation; null when admitted. */
    private fun admit(
        session: Session,
        epoch: Int?,
    ): Outcome? {
        if (leaseHolder != LeaseHolder.Remote(session.id)) return err(ErrorCode.LEASE_REQUIRED, "take the lease first")
        if (epoch != leaseEpoch) return err(ErrorCode.STALE_EPOCH, "lease epoch is $leaseEpoch")
        lastInputMs = nowMs
        return null
    }

    private fun admitDevice(ifRev: Long?): Outcome? {
        if (deviceCall != null) return err(ErrorCode.BUSY, "another device operation is running")
        if (ifRev != null && ifRev != stateRev) return err(ErrorCode.REV_CONFLICT, "state rev is $stateRev")
        return null
    }

    // ---- Lease.

    private fun onLeaseTake(session: Session): Outcome {
        if (localCapture) return err(ErrorCode.LOCAL_PRIORITY, "the phone is controlling the host")
        return when (leaseHolder) {
            LeaseHolder.Remote(session.id) -> leaseResult()
            null -> {
                setLease(LeaseHolder.Remote(session.id))
                leaseResult()
            }
            else -> err(ErrorCode.LEASE_HELD, "the lease is held; use lease.takeover")
        }
    }

    private fun leaseResult(): Outcome.Ok = ok(LeaseResult.serializer(), LeaseResult(leaseEpoch, leaseViewFor()))

    private fun onLeaseTakeover(
        session: Session,
        requestId: JsonPrimitive,
        holderSession: Long,
        epoch: Int?,
    ): Outcome {
        if (epoch == null) return err(ErrorCode.BAD_REQUEST, "lease.takeover needs the holder's epoch")
        if (localCapture) return err(ErrorCode.LOCAL_PRIORITY, "the phone is controlling the host")
        if (holderSession == session.id) return err(ErrorCode.BAD_REQUEST, "you hold the lease")
        if (leaseHolder != LeaseHolder.Remote(holderSession) || epoch != leaseEpoch) {
            return err(ErrorCode.LEASE_CHANGED, "the lease changed")
        }
        if (takeover != null) return err(ErrorCode.BUSY, "another takeover is pending")
        val holder = checkNotNull(sessions[holderSession]) { "lease holder $holderSession has no session" }
        takeover = Takeover(session.id, requestId, holder.id, leaseEpoch, nowMs + RemoteLimits.TAKEOVER_PROMPT_TIMEOUT_MS)
        send(holder, TakeoverRequestPush(session.id, session.displayName, leaseEpoch, RemoteLimits.TAKEOVER_PROMPT_TIMEOUT_MS))
        return Outcome.Deferred
    }

    private fun onTakeoverReply(
        session: Session,
        epoch: Int?,
        allow: Boolean,
    ): Outcome {
        admit(session, epoch)?.let { return it }
        val pendingTakeover = takeover?.takeIf { it.holderId == session.id } ?: return err(ErrorCode.NOT_FOUND, "no takeover is pending")
        takeover = null
        val requester = sessions[pendingTakeover.requesterId]
        if (!allow) {
            requester?.let { completeDeferred(it, pendingTakeover.requestId, err(ErrorCode.TAKEOVER_DECLINED, "the holder declined")) }
            return Outcome.Ok(null)
        }
        transferTo(pendingTakeover)
        return Outcome.Ok(null)
    }

    /** Consent or holder death: re-check holder, epoch and requester, then move the lease. */
    private fun transferTo(pendingTakeover: Takeover) {
        val requester = sessions[pendingTakeover.requesterId] ?: return
        val stillValid =
            !localCapture &&
                leaseHolder == LeaseHolder.Remote(pendingTakeover.holderId) &&
                leaseEpoch == pendingTakeover.epoch &&
                requester.auth == SessionAuth.APPROVED
        if (!stillValid) {
            completeDeferred(requester, pendingTakeover.requestId, err(ErrorCode.LEASE_CHANGED, "the lease changed"))
            return
        }
        loseLease(LeaseHolder.Remote(requester.id), "taken over")
        completeDeferred(requester, pendingTakeover.requestId, leaseResult())
    }

    private fun onLeaseRelease(): Outcome {
        releaseHolder("released")
        return leaseResult()
    }

    /** The holder lost the lease without consent (released, dead, revoked, idle): a waiting takeover gets it. */
    private fun releaseHolder(reason: String) {
        val holder = leaseHolder as? LeaseHolder.Remote ?: return
        val pendingTakeover = takeover?.takeIf { it.holderId == holder.sessionId }
        if (pendingTakeover != null) {
            takeover = null
            transferTo(pendingTakeover)
            if (leaseHolder == LeaseHolder.Remote(pendingTakeover.requesterId)) return
        }
        loseLease(null, reason)
    }

    /**
     * End-of-lease ordering: bump the epoch, cancel producers, drop queued input, release-all on the
     * priority lane (input is then refused until the barrier clears).
     */
    private fun loseLease(
        newHolder: LeaseHolder?,
        reason: String,
    ) {
        val oldHolder = leaseHolder
        setLease(newHolder)
        if (oldHolder is LeaseHolder.Remote) {
            cancelJob(reason)
            emit(RemoteEffect.DropQueuedInput(oldHolder.sessionId))
            releaseAllPriority()
        }
    }

    private fun setLease(holder: LeaseHolder?) {
        leaseEpoch = (leaseEpoch + 1) % RemoteLimits.LEASE_EPOCH_MODULUS
        leaseHolder = holder
        held.clear()
        lastInputMs = nowMs
        emit(RemoteEffect.LeaseChanged(holder, leaseEpoch))
        for (session in sessions.values) {
            if (session.auth == SessionAuth.APPROVED) session.leaseDirty = true
            // Paste plans are bound to the epoch they were prepared under.
            session.plans.clear()
        }
    }

    private fun cancelTakeover(requesterError: Outcome.Err?) {
        val pendingTakeover = takeover ?: return
        takeover = null
        if (requesterError !=
            null
        ) {
            sessions[pendingTakeover.requesterId]?.let { completeDeferred(it, pendingTakeover.requestId, requesterError) }
        }
        sessions[pendingTakeover.holderId]?.let { send(it, TakeoverCancelledPush(pendingTakeover.requesterId)) }
    }

    private fun onLocalCaptureStarted() {
        if (localCapture) return
        localCapture = true
        cancelTakeover(err(ErrorCode.LOCAL_PRIORITY, "the phone took control"))
        loseLease(LeaseHolder.Local, "the phone took control")
    }

    private fun onLocalCaptureStopped() {
        if (!localCapture) return
        localCapture = false
        check(leaseHolder == LeaseHolder.Local)
        setLease(null)
    }

    // ---- Sessions.

    private fun closeSession(
        session: Session,
        reason: CloseReason,
    ) {
        if (sessions[session.id] !== session) return
        emit(RemoteEffect.Close(session.id, reason))
        endSession(session)
    }

    private fun endSession(session: Session) {
        if (sessions[session.id] !== session) return
        takeover?.let { if (it.requesterId == session.id) cancelTakeover(requesterError = null) }
        sessions.remove(session.id)
        if (leaseHolder == LeaseHolder.Remote(session.id)) releaseHolder("session ended")
    }

    // ---- Auth.

    private fun validPrincipalFor(identity: ConnIdentity): Principal? =
        when (identity) {
            is ConnIdentity.Node -> approvalsByNode[identity.nodeId]?.takeIf { it.isValidAt(wallMs) }?.let { Principal.Node(it.nodeId) }
            is ConnIdentity.Token ->
                pairing.tokens.firstOrNull { it.sha256 == identity.tokenHash && it.isValidAt(wallMs) }?.let { Principal.Token(it.sha256) }
            is ConnIdentity.Pairing -> null
        }

    private fun isPrincipalValid(principal: Principal): Boolean =
        when (principal) {
            is Principal.Node -> approvalsByNode[principal.nodeId]?.isValidAt(wallMs) == true
            is Principal.Token -> pairing.tokens.any { it.sha256 == principal.sha256 && it.isValidAt(wallMs) }
        }

    private fun allowRw(session: Session): Boolean =
        when (val principal = session.principal) {
            is Principal.Node -> approvalsByNode[principal.nodeId]?.allowRw == true
            is Principal.Token -> pairing.tokens.firstOrNull { it.sha256 == principal.sha256 }?.allowRw == true
            null -> false
        }

    private fun unauthorizedSessionCount(): Int = sessions.values.count { it.auth != SessionAuth.APPROVED }

    private fun pendingSessionsOf(nodeId: String): List<Session> =
        sessions.values.filter { it.auth == SessionAuth.PENDING && (it.conn.identity as? ConnIdentity.Node)?.nodeId == nodeId }

    private fun sessionsOf(principal: Principal): List<Session> = sessions.values.filter { it.principal == principal }

    private fun onApprove(
        nodeId: String,
        allowRw: Boolean,
    ) {
        val pendingApproval = pending[nodeId]
        val existing = approvalsByNode[nodeId]
        val displayName = pendingApproval?.displayName ?: existing?.displayName ?: return
        val user = pendingApproval?.user ?: existing?.user ?: return
        val record = ApprovalRecord(nodeId, displayName, user, wallMs, wallMs + RemoteLimits.APPROVAL_LIFETIME_MS, allowRw)
        approvalsByNode.remove(nodeId)
        if (approvalsByNode.size >= RemoteLimits.APPROVAL_COUNT_MAX) {
            val oldest = approvalsByNode.values.minBy { it.approvedAtMs }
            removePrincipalAndClose(Principal.Node(oldest.nodeId), CloseReason.REVOKED)
        }
        approvalsByNode[nodeId] = record
        emit(RemoteEffect.SaveApprovals(approvalsByNode.values.toList()))
        if (pending.remove(nodeId) != null) emit(RemoteEffect.PendingChanged(pending.values.toList()))
        val principal = Principal.Node(nodeId)
        for (session in pendingSessionsOf(nodeId)) {
            session.auth = SessionAuth.APPROVED
            session.principal = principal
            emit(RemoteEffect.Authorize(session.id))
        }
        for (session in sessionsOf(principal)) announceAuth(session)
    }

    private fun announceAuth(session: Session) {
        if (!session.helloDone) return
        send(session, AuthPush(AuthStatus.APPROVED, allowRw(session)))
        session.stateDirty = true
        session.leaseDirty = true
        session.downloadsDirty = true
    }

    private fun onDeny(nodeId: String) {
        if (pending.remove(nodeId) != null) emit(RemoteEffect.PendingChanged(pending.values.toList()))
        for (session in pendingSessionsOf(nodeId)) closeSession(session, CloseReason.DENIED)
    }

    private fun onRevoke(principal: Principal) {
        if (principal is Principal.Node) onDeny(principal.nodeId)
        removePrincipalAndClose(principal, CloseReason.REVOKED)
    }

    /** Removes the record, tells Go, and closes every session of [principal], all in this step. */
    private fun removePrincipalAndClose(
        principal: Principal,
        reason: CloseReason,
    ) {
        when (principal) {
            is Principal.Node ->
                if (approvalsByNode.remove(principal.nodeId) !=
                    null
                ) {
                    emit(RemoteEffect.SaveApprovals(approvalsByNode.values.toList()))
                }
            is Principal.Token -> {
                val remaining = pairing.tokens.filterNot { it.sha256 == principal.sha256 }
                if (remaining.size != pairing.tokens.size) {
                    pairing = pairing.copy(tokens = remaining)
                    emit(RemoteEffect.SavePairing(pairing))
                }
            }
        }
        emit(RemoteEffect.Revoke(principal))
        for (session in sessionsOf(principal)) closeSession(session, reason)
    }

    private fun onSetAllowRw(
        principal: Principal,
        allowRw: Boolean,
    ) {
        when (principal) {
            is Principal.Node -> {
                val record = approvalsByNode[principal.nodeId] ?: return
                approvalsByNode[principal.nodeId] = record.copy(allowRw = allowRw)
                emit(RemoteEffect.SaveApprovals(approvalsByNode.values.toList()))
            }
            is Principal.Token -> {
                if (pairing.tokens.none { it.sha256 == principal.sha256 }) return
                pairing =
                    pairing.copy(tokens = pairing.tokens.map { if (it.sha256 == principal.sha256) it.copy(allowRw = allowRw) else it })
                emit(RemoteEffect.SavePairing(pairing))
            }
        }
        for (session in sessionsOf(principal)) announceAuth(session)
    }

    // ---- Pairing.

    private fun onPairingEnable() {
        if (pairing.lockedOut) return
        val code = random.nextInt(RemoteLimits.PAIRING_CODE_SPACE).toString().padStart(RemoteLimits.PAIRING_CODE_DIGIT_COUNT, '0')
        pairingCode = PairingCode(code, nowMs + RemoteLimits.PAIRING_CODE_LIFETIME_MS)
    }

    /** A `pair` session carries exactly one `pair` command (no id, no hello); its reply is Go's verdict, and Go closes it. */
    private fun onPairCommand(
        session: Session,
        type: String,
        message: JsonObject,
    ) {
        val request =
            if (type == "pair" && !session.pairAttempted) {
                runCatching { decode(RequestBody.serializer(), JsonObject(message - "id")) as? PairRequest }.getOrNull()
            } else {
                null
            }
        session.pairAttempted = true
        val outcome =
            if (request ==
                null
            ) {
                err(ErrorCode.BAD_REQUEST, "a pair exchange takes one pair command")
            } else {
                onPair(session, request)
            }
        // Go reads the first message as the verdict and then closes the exchange itself.
        emit(RemoteEffect.Send(session.id, encodeReply(null, outcome)))
    }

    private fun onPair(
        session: Session,
        request: PairRequest,
    ): Outcome {
        val tokenHash = (session.conn.identity as ConnIdentity.Pairing).tokenHash
        if (request.tokenHash != tokenHash) return err(ErrorCode.BAD_REQUEST, "tokenHash doesn't match the exchange")
        if (pairing.lockedOut) return err(ErrorCode.PAIRING_LOCKED, "too many attempts; reset on the phone")
        val code = pairingCode ?: return err(ErrorCode.PAIRING_DISABLED, "pairing is off; enable it on the phone")
        val recentAttempts = pairing.attemptAtMs.filter { it > wallMs - RemoteLimits.PAIRING_ATTEMPT_WINDOW_MS }
        if (recentAttempts.size >= RemoteLimits.PAIRING_ATTEMPT_COUNT_MAX) {
            pairing = pairing.copy(attemptAtMs = recentAttempts, lockedOut = true)
            pairingCode = null
            emit(RemoteEffect.SavePairing(pairing))
            return err(ErrorCode.PAIRING_LOCKED, "too many attempts; reset on the phone")
        }
        pairing = pairing.copy(attemptAtMs = recentAttempts + wallMs)
        if (nowMs >= code.expiresAtMs) {
            pairingCode = null
            emit(RemoteEffect.SavePairing(pairing))
            return err(ErrorCode.PAIRING_DISABLED, "the code expired")
        }
        if (!MessageDigest.isEqual(request.code.toByteArray(Charsets.UTF_8), code.code.toByteArray(Charsets.UTF_8))) {
            emit(RemoteEffect.SavePairing(pairing))
            return err(ErrorCode.BAD_CODE, "wrong code")
        }
        pairingCode = null
        val record =
            TokenRecord(
                sha256 = tokenHash,
                label = sanitizeLabel(session.conn.userAgent).ifEmpty { session.displayName },
                createdAtMs = wallMs,
                expiresAtMs = wallMs + RemoteLimits.TOKEN_LIFETIME_MS,
            )
        val withoutSame = pairing.tokens.filterNot { it.sha256 == tokenHash }
        if (withoutSame.size >= RemoteLimits.TOKEN_COUNT_MAX) {
            pairing = pairing.copy(tokens = withoutSame)
            removePrincipalAndClose(Principal.Token(withoutSame.minBy { it.createdAtMs }.sha256), CloseReason.REVOKED)
        }
        pairing = pairing.copy(tokens = pairing.tokens.filterNot { it.sha256 == tokenHash } + record)
        emit(RemoteEffect.SavePairing(pairing))
        return Outcome.Ok(null)
    }

    // ---- Device.

    private fun onSlotsApply(
        session: Session,
        requestId: JsonPrimitive,
        request: SlotsApplyRequest,
    ): Outcome {
        admitDevice(request.ifRev)?.let { return it }
        if (request.slots.size >
            GadgetLimits.LUN_COUNT_MAX
        ) {
            return err(ErrorCode.BAD_REQUEST, "at most ${GadgetLimits.LUN_COUNT_MAX} slots")
        }
        val sorted = request.slots.sortedBy { it.lun }
        if (sorted.map { it.lun } != sorted.indices.toList()) return err(ErrorCode.BAD_REQUEST, "luns must be 0 until the slot count")
        // Images in the library, plus whatever is exposed now (it may be outside the library).
        val pathsById = device.slots.associate { RemoteImage.idFor(it.path) to it.path } + device.images.associate { it.imageId to it.path }
        val specs =
            sorted.map { slot ->
                val path = pathsById[slot.imageId] ?: return err(ErrorCode.NOT_FOUND, "unknown imageId")
                val image = device.images.firstOrNull { it.path == path }
                val mode = slot.mode?.mode ?: image?.defaultMode ?: RemoteImage.defaultModeFor(path, sizeBytes = null)
                if (mode == LunMode.DISK_RW && !allowRw(session)) return err(ErrorCode.RW_NOT_ALLOWED, "writable disks are not allowed")
                // The phone refuses it too (the slot fails); saying so up front names the alternative.
                if (mode == LunMode.CDROM && image != null) {
                    CdromLimits.problem(image.displayName, image.sizeBytes)?.let { problem ->
                        val code = if (image.sizeBytes > CdromLimits.SIZE_BYTES_MAX) ErrorCode.CDROM_TOO_LARGE else ErrorCode.BAD_REQUEST
                        return err(code, problem)
                    }
                }
                SlotSpec(slot.lun, path, mode, auto = slot.mode == null)
            }
        return startDeviceCall(session, requestId, DeviceOp.Apply(Desired(specs, device.hid)))
    }

    private fun startDeviceCall(
        session: Session,
        requestId: JsonPrimitive,
        op: DeviceOp,
    ): Outcome {
        check(deviceCall == null)
        val opId = nextOpId++
        deviceCall =
            DeviceCall(
                opId,
                session.id,
                requestId,
                timeoutMs = if (op is DeviceOp.SetUsbTethering) RemoteLimits.TETHER_OP_TIMEOUT_MS else RemoteLimits.DEVICE_OP_TIMEOUT_MS,
            )
        emit(RemoteEffect.Device(opId, op))
        return Outcome.Deferred
    }

    private fun onDeviceResult(
        opId: Long,
        outcome: DeviceOutcome,
    ) {
        val call = deviceCall?.takeIf { it.opId == opId } ?: return
        deviceCall = null
        val session = sessions[call.sessionId] ?: return
        val reply =
            when (outcome) {
                is DeviceOutcome.Applied -> ok(DeviceOpResult.serializer(), DeviceOpResult())
                is DeviceOutcome.Partial ->
                    ok(
                        DeviceOpResult.serializer(),
                        DeviceOpResult(outcome.slotFailures.mapKeys { it.key.toString() }),
                    )
                is DeviceOutcome.Failed -> err(ErrorCode.DEVICE, outcome.reason.take(ERROR_MESSAGE_LENGTH_MAX))
                is DeviceOutcome.NeedsAttention -> err(ErrorCode.DEVICE_ATTENTION, outcome.reason.take(ERROR_MESSAGE_LENGTH_MAX))
                is DeviceOutcome.TimedOut -> err(ErrorCode.TIMEOUT, "the device did not answer in ${call.timeoutMs} ms")
                is DeviceOutcome.Busy -> err(ErrorCode.BUSY, "an earlier device operation is still running")
                is DeviceOutcome.Tethering ->
                    ok(
                        TetherSetResult.serializer(),
                        TetherSetResult(TetherView(outcome.active), outcome.warning?.take(ERROR_MESSAGE_LENGTH_MAX)),
                    )
                is DeviceOutcome.DisksExposed -> err(ErrorCode.DISKS_EXPOSED, outcome.reason.take(ERROR_MESSAGE_LENGTH_MAX))
            }
        completeDeferred(session, call.requestId, reply)
    }

    private fun onDeviceChanged(snapshot: DeviceSnapshot) {
        val bounded = snapshot.copy(images = snapshot.images.take(RemoteLimits.IMAGE_COUNT_MAX))
        if (bounded == device) return
        device = bounded
        stateRev++
        markStateDirty()
        if (!device.hid.keyboard) {
            cancelJob("the keyboard was turned off")
            held.modifierBits = 0
            held.keys.clear()
        }
        if (!device.hid.mouse) held.buttonBits = 0
        if (!device.hid.keyboard && !device.hid.mouse) barrier = null
    }

    // ---- Catalog downloads. They aren't held input: after admission nothing here depends on the lease.

    private fun onCatalogChanged(snapshot: CatalogSnapshot) {
        val bounded = boundedCatalog(snapshot)
        if (bounded == catalog) return
        catalog = bounded
        catalogEntries.clear()
        for (distro in bounded.distros) {
            for (release in distro.releases) {
                catalogEntries[CatalogEntryIds.idFor(release.fileName)] =
                    CatalogEntry(release, distro.distro.name)
            }
        }
        catalogRev++
        for (session in sessions.values) if (session.auth == SessionAuth.APPROVED) session.catalogDirty = true
    }

    /** At most [RemoteLimits.CATALOG_ENTRY_COUNT_MAX] releases, one per file name (the first wins, as in the download store). */
    private fun boundedCatalog(snapshot: CatalogSnapshot): CatalogSnapshot {
        val seen = HashSet<String>()
        val distros =
            snapshot.distros.map { distro ->
                val releases =
                    distro.releases.filter { release ->
                        seen.size < RemoteLimits.CATALOG_ENTRY_COUNT_MAX && seen.add(release.fileName)
                    }
                distro.copy(releases = releases)
            }
        return snapshot.copy(distros = distros)
    }

    private fun onDownloadsChanged(changed: List<RemoteDownload>) {
        val bounded = changed.take(RemoteLimits.DOWNLOAD_COUNT_MAX)
        if (bounded == downloads) return
        downloads = bounded
        for (session in sessions.values) if (session.auth == SessionAuth.APPROVED) session.downloadsDirty = true
    }

    /**
     * Lets the app refresh a stale catalog (its own rules decide what's stale), at most once per
     * [RemoteLimits.CATALOG_REFRESH_INTERVAL_MS_MIN] for all sessions together.
     */
    private fun maybeRefreshCatalog() {
        if (catalog.refreshing) return
        val lastMs = catalogRefreshRequestedAtMs
        if (lastMs != null && nowMs - lastMs < RemoteLimits.CATALOG_REFRESH_INTERVAL_MS_MIN) return
        catalogRefreshRequestedAtMs = nowMs
        emit(RemoteEffect.CatalogRefreshIfStale)
    }

    private fun onCatalogList(request: CatalogListRequest): Outcome {
        maybeRefreshCatalog()
        val libraryNames = device.images.mapTo(HashSet()) { it.path.substringAfterLast('/') }
        val downloadsByName = downloads.associateBy { it.fileName }
        var budgetBytes = RemoteLimits.CATALOG_REPLY_SIZE_BYTES_MAX - CATALOG_REPLY_ENVELOPE_BYTES
        var entryCount = 0
        var truncated = false
        val distros = ArrayList<CatalogDistroView>()
        for (distro in catalog.distros) {
            if (truncated) break
            if (request.category != null && distro.distro.category != request.category.category) continue
            if (request.distroId != null && distro.distro.id != request.distroId) continue
            val shell =
                CatalogDistroView(
                    distroId = distro.distro.id,
                    name = distro.distro.name.take(RemoteLimits.CATALOG_TEXT_LENGTH_MAX),
                    category = WireCategory.of(distro.distro.category),
                    fetchedAtMs = distro.fetchedAtWallMs,
                    error = distro.error?.take(RemoteLimits.CATALOG_TEXT_LENGTH_MAX),
                    entries = emptyList(),
                )
            // The separating comma is counted too.
            val shellBytes = jsonSizeBytes(CatalogDistroView.serializer(), shell) + 1
            if (shellBytes > budgetBytes) {
                truncated = true
                break
            }
            budgetBytes -= shellBytes
            val entries = ArrayList<CatalogEntryView>()
            for (release in distro.releases) {
                if (release.arch != request.arch.arch) continue
                if (entryCount >= RemoteLimits.CATALOG_REPLY_ENTRY_COUNT_MAX) {
                    truncated = true
                    break
                }
                val view = entryView(release, libraryNames, downloadsByName)
                val viewBytes = jsonSizeBytes(CatalogEntryView.serializer(), view) + 1
                if (viewBytes > budgetBytes) {
                    truncated = true
                    break
                }
                budgetBytes -= viewBytes
                entryCount++
                entries += view
            }
            distros += shell.copy(entries = entries)
        }
        return ok(
            CatalogResult.serializer(),
            CatalogResult(catalogRev, refreshing = catalog.refreshing, truncated = truncated, distros = distros),
        )
    }

    private fun entryView(
        release: Release,
        libraryNames: Set<String>,
        downloadsByName: Map<String, RemoteDownload>,
    ): CatalogEntryView =
        CatalogEntryView(
            entryId = CatalogEntryIds.idFor(release.fileName),
            version = release.version.take(RemoteLimits.CATALOG_TEXT_LENGTH_MAX),
            variant = release.variant.takeIf { it.isNotBlank() }?.take(RemoteLimits.CATALOG_TEXT_LENGTH_MAX),
            arch = WireArch.of(release.arch),
            fileName = release.fileName,
            sizeBytes = release.sizeBytes,
            supportedUntil = release.supportedUntil?.take(RemoteLimits.CATALOG_TEXT_LENGTH_MAX),
            state =
                when {
                    release.fileName in libraryNames -> WireEntryState.DOWNLOADED
                    else -> downloadsByName[release.fileName]?.let { WireEntryState.of(it.status) } ?: WireEntryState.AVAILABLE
                },
        )

    private fun onDownloadStart(
        session: Session,
        requestId: JsonPrimitive,
        entryId: String,
    ): Outcome {
        if (downloadCall != null) return err(ErrorCode.BUSY, "another download request is running")
        val entry = catalogEntries[entryId] ?: return err(ErrorCode.NOT_FOUND, "no such catalog entry; list the catalog again")
        val fileName = entry.release.fileName
        // A repeat start never fails for capacity: already-downloaded and already-active answer first.
        if (device.images.any { it.path.substringAfterLast('/') == fileName }) {
            return ok(DownloadStartResult.serializer(), DownloadStartResult(entryId, fileName, DownloadStartResultKind.DOWNLOADED))
        }
        if (downloads.any { it.fileName == fileName && it.status.isActive }) {
            return ok(DownloadStartResult.serializer(), DownloadStartResult(entryId, fileName, DownloadStartResultKind.ACTIVE))
        }
        // A cheap early answer; the app enforces the same bound atomically when it enqueues.
        if (downloads.count { it.status.isActive } >= RemoteLimits.DOWNLOAD_ACTIVE_COUNT_MAX) {
            return err(ErrorCode.TOO_MANY_DOWNLOADS, "${RemoteLimits.DOWNLOAD_ACTIVE_COUNT_MAX} downloads are already queued or running")
        }
        return startDownloadCall(session, requestId, entryId, fileName, DownloadOp.Start(entry.release, entry.distroName))
    }

    private fun onDownloadCancel(
        session: Session,
        requestId: JsonPrimitive,
        entryId: String,
    ): Outcome {
        if (downloadCall != null) return err(ErrorCode.BUSY, "another download request is running")
        // The record may not have reached this snapshot yet (a start that just finished), so the catalog names it too.
        val download = downloads.firstOrNull { it.entryId == entryId }
        if (download?.status == RemoteDownloadStatus.COMPLETED) return ok(CancelResult.serializer(), CancelResult(cancelled = false))
        val fileName =
            download?.fileName ?: catalogEntries[entryId]?.release?.fileName
                ?: return ok(CancelResult.serializer(), CancelResult(cancelled = false))
        return startDownloadCall(session, requestId, entryId, fileName, DownloadOp.Cancel(fileName))
    }

    private fun startDownloadCall(
        session: Session,
        requestId: JsonPrimitive,
        entryId: String,
        fileName: String,
        op: DownloadOp,
    ): Outcome {
        check(downloadCall == null)
        val opId = nextOpId++
        downloadCall = DownloadCall(opId, session.id, requestId, entryId, fileName, isStart = op is DownloadOp.Start)
        emit(RemoteEffect.Download(opId, op))
        return Outcome.Deferred
    }

    private fun onDownloadResult(
        opId: Long,
        outcome: DownloadOutcome,
    ) {
        val call = downloadCall?.takeIf { it.opId == opId } ?: return
        downloadCall = null
        val answersStart =
            outcome is DownloadOutcome.Started ||
                outcome is DownloadOutcome.AlreadyActive ||
                outcome is DownloadOutcome.AlreadyDownloaded ||
                outcome is DownloadOutcome.TooMany ||
                outcome is DownloadOutcome.StorageLow
        check(!(call.isStart && outcome is DownloadOutcome.Cancelled)) { "a start answered as a cancel" }
        check(call.isStart || !answersStart) { "a cancel answered as a start: $outcome" }
        // The reply goes nowhere if the session ended; the download carries on either way.
        val session = sessions[call.sessionId] ?: return

        fun started(kind: DownloadStartResultKind) =
            ok(DownloadStartResult.serializer(), DownloadStartResult(call.entryId, call.fileName, kind))
        val reply =
            when (outcome) {
                is DownloadOutcome.Started -> started(DownloadStartResultKind.STARTED)
                is DownloadOutcome.AlreadyActive -> started(DownloadStartResultKind.ACTIVE)
                is DownloadOutcome.AlreadyDownloaded -> started(DownloadStartResultKind.DOWNLOADED)
                is DownloadOutcome.Cancelled -> ok(CancelResult.serializer(), CancelResult(outcome.cancelled))
                is DownloadOutcome.TooMany -> err(ErrorCode.TOO_MANY_DOWNLOADS, outcome.reason.take(ERROR_MESSAGE_LENGTH_MAX))
                is DownloadOutcome.StorageLow -> err(ErrorCode.STORAGE_LOW, outcome.reason.take(ERROR_MESSAGE_LENGTH_MAX))
                is DownloadOutcome.Rejected -> err(ErrorCode.DEVICE, outcome.reason.take(ERROR_MESSAGE_LENGTH_MAX))
                is DownloadOutcome.TimedOut ->
                    err(
                        ErrorCode.TIMEOUT,
                        "the phone did not answer in ${RemoteLimits.DOWNLOAD_OP_TIMEOUT_MS} ms",
                    )
                is DownloadOutcome.Busy -> err(ErrorCode.BUSY, "an earlier download request is still running")
            }
        completeDeferred(session, call.requestId, reply)
    }

    /** Active downloads first, then the rest, as many as fit [RemoteLimits.DOWNLOADS_PUSH_SIZE_BYTES_MAX]. */
    private fun downloadsPush(): DownloadsPush {
        var budgetBytes = RemoteLimits.DOWNLOADS_PUSH_SIZE_BYTES_MAX - DOWNLOADS_PUSH_ENVELOPE_BYTES
        val views = ArrayList<DownloadView>()
        var truncated = false
        for (download in downloads.sortedByDescending { it.status.isActive }) {
            val view =
                DownloadView(
                    entryId = download.entryId,
                    fileName = download.fileName.take(RemoteLimits.CATALOG_TEXT_LENGTH_MAX),
                    title = download.title.take(RemoteLimits.CATALOG_TEXT_LENGTH_MAX),
                    status = WireDownloadStatus.of(download.status),
                    downloadedBytes = download.downloadedBytes,
                    totalBytes = download.totalBytes,
                    verified = download.verified.takeIf { it },
                    error = download.error?.take(RemoteLimits.CATALOG_TEXT_LENGTH_MAX),
                )
            val viewBytes = jsonSizeBytes(DownloadView.serializer(), view) + 1
            if (viewBytes > budgetBytes) {
                truncated = true
                break
            }
            budgetBytes -= viewBytes
            views += view
        }
        return DownloadsPush(views, truncated)
    }

    // ---- HID input.

    private fun onHidFrame(
        session: Session,
        bytes: ByteArray,
    ) {
        if (session.auth != SessionAuth.APPROVED || !session.helloDone) return
        val frame = HidFrameCodec.decode(bytes) ?: return closeSession(session, CloseReason.MALFORMED)
        val isHolder = leaseHolder == LeaseHolder.Remote(session.id) && frame.epoch == leaseEpoch
        if (frame is HidFrame.ReleaseAll) {
            // Coalesced and exempt from the rate limit, for the holder only.
            if (isHolder) clientReleaseAll(session)
            return
        }
        if (!spendFrameBudget(session)) return
        if (!isHolder) {
            session.leaseDirty = true
            return
        }
        if (barrier != null) return
        lastInputMs = nowMs
        when (frame) {
            is HidFrame.KeyDown -> KeyUsage.fromId(frame.usageId)?.let { onKey(session, it, down = true) }
            is HidFrame.KeyUp -> KeyUsage.fromId(frame.usageId)?.let { onKey(session, it, down = false) }
            is HidFrame.MouseMove -> {
                if (!device.hid.mouse || (frame.deltaX == 0 && frame.deltaY == 0)) return
                sendHid(session.id, HidKind.MOUSE, MouseReport.clamped(held.buttonBits, frame.deltaX, frame.deltaY).encode())
            }
            is HidFrame.MouseButtons -> {
                if (!device.hid.mouse || frame.buttonBits == held.buttonBits) return
                held.buttonBits = frame.buttonBits
                sendHid(session.id, HidKind.MOUSE, MouseReport(held.buttonBits).encode())
            }
            is HidFrame.Scroll -> {
                // Not malformed: the client may not have seen the layout change yet.
                if (!device.hid.mouse || device.hid.mouseLayout != MouseLayout.WHEEL || (frame.wheel == 0 && frame.pan == 0)) return
                sendHid(session.id, HidKind.MOUSE, MouseReport.scroll(held.buttonBits, frame.wheel, frame.pan).encode())
            }
            is HidFrame.ReleaseAll -> error("handled above")
        }
    }

    private fun onKey(
        session: Session,
        usage: KeyUsage,
        down: Boolean,
    ) {
        // A running job owns the keyboard; manual keys would interleave with its reports.
        if (!device.hid.keyboard || job != null) return
        val modifier = usage.modifier
        val changed =
            when {
                modifier != null && down ->
                    (held.modifierBits and modifier.bit == 0).also {
                        held.modifierBits =
                            held.modifierBits or modifier.bit
                    }
                modifier != null ->
                    (held.modifierBits and modifier.bit != 0).also {
                        held.modifierBits =
                            held.modifierBits and modifier.bit.inv()
                    }
                down -> held.keys.add(usage)
                else -> held.keys.remove(usage)
            }
        if (changed) sendHid(session.id, HidKind.KEYBOARD, KeyboardReport.pressing(held.modifierBits, held.keys).encode())
    }

    private fun clientReleaseAll(session: Session) {
        if (job != null) return abortInput("released")
        sendReleaseReports(session.id)
    }

    /** Ordinary (queued) all-zero reports for whatever is held; no barrier. */
    private fun sendReleaseReports(sessionId: Long) {
        if (device.hid.keyboard && held.keyboardHeld) sendHid(sessionId, HidKind.KEYBOARD, KeyboardReport.RELEASE_ALL.encode())
        if (device.hid.mouse && held.buttonBits != 0) sendHid(sessionId, HidKind.MOUSE, MouseReport().encode())
        held.clear()
    }

    private fun spendFrameBudget(session: Session): Boolean {
        val elapsedMs = nowMs - session.frameBudgetRefilledAtMs
        session.frameBudgetRefilledAtMs = nowMs
        session.frameBudgetMilli =
            minOf(FRAME_BUDGET_MILLI_MAX, session.frameBudgetMilli + elapsedMs * RemoteLimits.HID_FRAME_RATE_PER_SECOND_MAX)
        if (session.frameBudgetMilli < FRAME_COST_MILLI) return false
        session.frameBudgetMilli -= FRAME_COST_MILLI
        return true
    }

    private fun sendHid(
        sessionId: Long,
        kind: HidKind,
        report: ByteArray,
    ) {
        // Dispatch-time fence: the same step that admitted the input checks it again here.
        check(leaseHolder == LeaseHolder.Remote(sessionId)) { "HID from $sessionId, holder is $leaseHolder" }
        check(barrier == null) { "HID input while the release barrier is up" }
        check(kind == HidKind.KEYBOARD && device.hid.keyboard || kind == HidKind.MOUSE && device.hid.mouse) { "$kind is off" }
        emit(RemoteEffect.HidSend(sessionId, leaseEpoch, kind, report))
    }

    /** Stop the job, drop queued reports and release everything (priority); the lease stays. */
    private fun abortInput(reason: String) {
        cancelJob(reason)
        releaseAllPriority()
    }

    private fun releaseAllPriority() {
        emit(RemoteEffect.HidReleaseAll)
        held.clear()
        raiseBarrier()
    }

    private fun raiseBarrier() {
        if (barrier == null && (device.hid.keyboard || device.hid.mouse)) {
            barrier = Barrier(nowMs, nowMs + RemoteLimits.RELEASE_RETRY_INITIAL_MS, RemoteLimits.RELEASE_RETRY_INITIAL_MS)
        }
    }

    private fun onHidRejected() {
        // The HidSession dropped its queue and owes a release-all; mirror that and stop any job.
        cancelJob("the HID queue overflowed")
        held.clear()
        raiseBarrier()
    }

    private fun onReleaseDelivered() {
        val current = barrier ?: return
        barrier = null
        if (current.pendingReported) markStateDirty()
    }

    // ---- Jobs (paste, boot keys). Their requests are answered when the job ends.

    private fun onBootKeyRun(
        session: Session,
        requestId: JsonPrimitive,
        request: BootKeyRunRequest,
    ): Outcome {
        jobAdmission()?.let { return it }
        val vendor =
            BootVendor.entries.firstOrNull { it.name.lowercase() == request.vendor }
                ?: return err(ErrorCode.BAD_REQUEST, "unknown vendor")
        val preset = BootKeyPresets.forVendor(vendor)
        val keys = if (request.action == BootKeyAction.MENU) preset.bootMenuKeys else preset.setupKeys
        val key = keys.getOrNull(request.keyIndex) ?: return err(ErrorCode.NOT_FOUND, "no such key for ${request.vendor}")
        if (request.durationMs !in 1..HidLimits.BOOT_KEY_HOLD_MS_MAX) {
            return err(ErrorCode.BAD_REQUEST, "durationMs must be 1..${HidLimits.BOOT_KEY_HOLD_MS_MAX}")
        }
        val plan =
            when (key) {
                is BootKey.Tap -> {
                    val intervalMs =
                        maxOf(
                            RemoteLimits.BOOT_KEY_MASH_INTERVAL_MS_MIN,
                            request.durationMs / HidLimits.BOOT_KEY_PRESS_COUNT_MAX,
                        )
                    val pressCount = (request.durationMs / intervalMs).toInt().coerceIn(1, HidLimits.BOOT_KEY_PRESS_COUNT_MAX)
                    BootKeyMasher.repeat(key.usage, pressCount, intervalMs)
                }
                is BootKey.Hold -> BootKeyMasher.hold(key.modifier, request.durationMs)
                is BootKey.Unsupported -> return err(ErrorCode.UNSUPPORTED, key.description)
            }
        startJob(JobKind.BOOTKEY, session, requestId, withHoldResends(plan.reports), plan.durationMs)
        return Outcome.Deferred
    }

    private fun onPastePrepare(
        session: Session,
        text: String,
    ): Outcome {
        if (!utf8SizeWithin(
                text,
                RemoteLimits.PASTE_SIZE_BYTES_MAX,
            )
        ) {
            return err(ErrorCode.TOO_LARGE, "paste is over ${RemoteLimits.PASTE_SIZE_BYTES_MAX} bytes")
        }
        if (session.plans.size >=
            RemoteLimits.PASTE_PLAN_COUNT_MAX
        ) {
            return err(ErrorCode.TOO_MANY_PLANS, "at most ${RemoteLimits.PASTE_PLAN_COUNT_MAX} plans")
        }
        val plan = TextTyper.plan(text)
        val durationMs = plan.reports.size * RemoteLimits.PASTE_REPORT_INTERVAL_MS
        if (durationMs >
            RemoteLimits.PASTE_DURATION_MS_MAX
        ) {
            return err(ErrorCode.TOO_LONG, "typing would take over ${RemoteLimits.PASTE_DURATION_MS_MAX} ms")
        }
        val planId = random.nextBytes(PLAN_ID_SIZE_BYTES).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        session.plans[planId] = PastePlan(text, leaseEpoch, nowMs + RemoteLimits.PASTE_PLAN_LIFETIME_MS)
        return ok(
            PastePrepareResult.serializer(),
            PastePrepareResult(
                planId = planId,
                unsupported =
                    plan.unsupported
                        .take(
                            RemoteLimits.PASTE_UNSUPPORTED_REPORTED_MAX,
                        ).map { UnsupportedCharacterView(it.index, it.codePoint) },
                unsupportedCount = plan.unsupported.size,
                durationMs = durationMs,
            ),
        )
    }

    private fun onPasteRun(
        session: Session,
        requestId: JsonPrimitive,
        planId: String,
    ): Outcome {
        val plan = session.plans[planId] ?: return err(ErrorCode.NOT_FOUND, "no such plan")
        if (nowMs >= plan.expiresAtMs) {
            session.plans.remove(planId)
            return err(ErrorCode.PLAN_EXPIRED, "prepare the paste again")
        }
        if (plan.epoch != leaseEpoch) return err(ErrorCode.STALE_EPOCH, "the plan belongs to an older lease")
        jobAdmission()?.let { return it }
        session.plans.remove(planId)
        val reports =
            TextTyper.plan(plan.text).reports.mapIndexed { index, report ->
                TimedReport(
                    index * RemoteLimits.PASTE_REPORT_INTERVAL_MS,
                    report,
                )
            }
        val durationMs = reports.size * RemoteLimits.PASTE_REPORT_INTERVAL_MS
        if (reports.isEmpty()) return ok(JobResult.serializer(), JobResult(0))
        startJob(JobKind.PASTE, session, requestId, reports, durationMs)
        return Outcome.Deferred
    }

    private fun jobAdmission(): Outcome? {
        job?.let { return err(ErrorCode.BUSY, "a ${it.kind.name.lowercase()} job is running") }
        if (!device.hid.keyboard) return err(ErrorCode.HID_OFF, "the keyboard is off")
        if (barrier != null) return err(ErrorCode.RELEASE_PENDING, "waiting for the host to take a release-all")
        return null
    }

    private fun startJob(
        kind: JobKind,
        session: Session,
        requestId: JsonPrimitive,
        reports: List<TimedReport>,
        durationMs: Long,
    ) {
        check(reports.isNotEmpty() && reports.last().report.isReleaseAll) { "a job must end with release-all" }
        // The job's first report replaces whatever was held.
        held.modifierBits = 0
        held.keys.clear()
        job = Job(kind, session.id, requestId, leaseEpoch, reports, nowMs, durationMs)
    }

    private fun onJobCancel(kind: JobKind): Outcome {
        if (job?.kind != kind) return ok(CancelResult.serializer(), CancelResult(cancelled = false))
        abortInput("cancelled")
        return ok(CancelResult.serializer(), CancelResult(cancelled = true))
    }

    private fun cancelJob(reason: String) {
        val running = job ?: return
        job = null
        sessions[running.sessionId]?.let { completeDeferred(it, running.requestId, err(ErrorCode.CANCELLED, reason)) }
    }

    private fun runJob() {
        val running = job ?: return
        check(leaseHolder == LeaseHolder.Remote(running.sessionId) && leaseEpoch == running.epoch) { "job outlived its lease" }
        if (barrier != null) return
        var sentCount = 0
        while (running.nextIndex < running.reports.size && running.nextAtMs <= nowMs && sentCount < RemoteLimits.JOB_REPORTS_PER_STEP_MAX) {
            sendHid(running.sessionId, HidKind.KEYBOARD, running.reports[running.nextIndex].report.encode())
            running.nextIndex++
            sentCount++
        }
        if (sentCount > 0) lastInputMs = nowMs
        if (running.nextIndex == running.reports.size) {
            job = null
            sessions[running.sessionId]?.let {
                completeDeferred(
                    it,
                    running.requestId,
                    ok(JobResult.serializer(), JobResult(running.durationMs)),
                )
            }
        }
    }

    // ---- Time.

    private fun advance() {
        expireRecords()
        pairingCode?.let { if (nowMs >= it.expiresAtMs) pairingCode = null }
        for (session in sessions.values) session.plans.values.removeIf { nowMs >= it.expiresAtMs }
        takeover?.let { if (nowMs >= it.deadlineMs) cancelTakeover(err(ErrorCode.TAKEOVER_DECLINED, "the holder did not answer")) }
        retryRelease()
        idle()
    }

    private fun expireRecords() {
        val expiredNodes = approvalsByNode.values.filterNot { it.isValidAt(wallMs) }
        for (record in expiredNodes) approvalsByNode.remove(record.nodeId)
        if (expiredNodes.isNotEmpty()) emit(RemoteEffect.SaveApprovals(approvalsByNode.values.toList()))
        val validTokens = pairing.tokens.filter { it.isValidAt(wallMs) }
        if (validTokens.size != pairing.tokens.size) {
            pairing = pairing.copy(tokens = validTokens)
            emit(RemoteEffect.SavePairing(pairing))
        }
        for (session in sessions.values.toList()) {
            val principal = session.principal ?: continue
            if (!isPrincipalValid(principal)) closeSession(session, CloseReason.EXPIRED)
        }
    }

    private fun retryRelease() {
        val current = barrier ?: return
        if (current.pendingReported) return
        if (nowMs - current.owedSinceMs >= RemoteLimits.RELEASE_RETRY_WINDOW_MS) {
            current.pendingReported = true
            markStateDirty()
            return
        }
        if (nowMs >= current.nextRetryMs) {
            emit(RemoteEffect.HidReleaseAll)
            current.retryDelayMs = minOf(current.retryDelayMs * 2, RemoteLimits.RELEASE_RETRY_WINDOW_MS)
            current.nextRetryMs = nowMs + current.retryDelayMs
        }
    }

    private fun idle() {
        val holder = leaseHolder as? LeaseHolder.Remote ?: return
        if (job != null) return
        val idleMs = nowMs - lastInputMs
        if (idleMs >= RemoteLimits.IDLE_LEASE_RELEASE_MS) return releaseHolder("idle")
        if (idleMs >= RemoteLimits.IDLE_KEY_RELEASE_MS && barrier == null && (held.keyboardHeld || held.buttonBits != 0)) {
            sendReleaseReports(holder.sessionId)
        }
    }

    // ---- Replies and pushes.

    private fun encodeReply(
        id: JsonPrimitive?,
        outcome: Outcome,
    ): String =
        when (outcome) {
            is Outcome.Ok -> ProtocolJson.encodeReply(id, error = null, result = outcome.result)
            is Outcome.Err -> ProtocolJson.encodeReply(id, ReplyError(outcome.code, outcome.msg), result = null)
            is Outcome.Deferred -> error("deferred is not a reply")
        }

    private fun finishReply(
        session: Session,
        id: JsonPrimitive,
        outcome: Outcome,
    ) {
        val json = encodeReply(id, outcome)
        session.replies[id.toString()] = json
        trimReplyCache(session)
        emit(RemoteEffect.Send(session.id, json))
    }

    private fun completeDeferred(
        session: Session,
        id: JsonPrimitive,
        outcome: Outcome,
    ) {
        if (sessions[session.id] !== session) return
        finishReply(session, id, outcome)
    }

    /** Evicts the oldest finished replies first, so a deferred request stays deduplicated until it answers. */
    private fun trimReplyCache(session: Session) {
        while (session.replies.size > RemoteLimits.REQUEST_ID_CACHE_SIZE) {
            val oldest =
                session.replies.entries
                    .firstOrNull { it.value != null }
                    ?.key ?: session.replies.keys.first()
            session.replies.remove(oldest)
        }
    }

    private fun send(
        session: Session,
        push: Push,
    ) = emit(RemoteEffect.Send(session.id, ProtocolJson.encodePush(push)))

    private fun markStateDirty() {
        for (session in sessions.values) if (session.auth == SessionAuth.APPROVED) session.stateDirty = true
    }

    private fun flushPushes() {
        for (session in sessions.values) {
            if (session.auth != SessionAuth.APPROVED || !session.helloDone) continue
            if (session.leaseDirty && nowMs - session.lastLeasePushMs >= RemoteLimits.PUSH_INTERVAL_MS_MIN) {
                session.leaseDirty = false
                session.lastLeasePushMs = nowMs
                send(session, LeasePush(leaseViewFor()))
            }
            if (session.catalogDirty && nowMs - session.lastCatalogPushMs >= RemoteLimits.PUSH_INTERVAL_MS_MIN) {
                session.catalogDirty = false
                session.lastCatalogPushMs = nowMs
                send(session, CatalogPush(catalogRev, catalog.refreshing))
            }
            if (session.downloadsDirty && nowMs - session.lastDownloadsPushMs >= RemoteLimits.DOWNLOAD_PUSH_INTERVAL_MS_MIN) {
                session.downloadsDirty = false
                session.lastDownloadsPushMs = nowMs
                send(session, downloadsPush())
            }
            if (session.stateDirty && nowMs - session.lastStatePushMs >= RemoteLimits.PUSH_INTERVAL_MS_MIN) {
                session.stateDirty = false
                session.lastStatePushMs = nowMs
                val view = stateView()
                send(session, StatePush(view.rev, view.slots, view.images, view.hid, view.lease, view.tether))
            }
        }
    }

    private fun leaseViewFor(): LeaseView =
        when (val holder = leaseHolder) {
            null -> LeaseView(epoch = leaseEpoch)
            LeaseHolder.Local -> LeaseView(holderName = "phone", epoch = leaseEpoch, local = true)
            is LeaseHolder.Remote -> LeaseView(holder.sessionId, sessions[holder.sessionId]?.displayName, leaseEpoch)
        }

    private fun stateView(): StateView {
        val imagesByPath = device.images.associateBy { it.path }
        return StateView(
            rev = stateRev,
            slots =
                device.slots.map { slot ->
                    val image = imagesByPath[slot.path]
                    SlotView(
                        lun = slot.lun,
                        imageId = image?.imageId ?: RemoteImage.idFor(slot.path),
                        name = image?.displayName ?: slot.path.substringAfterLast('/'),
                        mode = WireLunMode.of(slot.mode),
                        status = WireSlotStatus.of(slot.intent),
                    )
                },
            images =
                device.images.map {
                    ImageView(
                        it.imageId,
                        it.displayName,
                        it.sizeBytes,
                        WireLunMode.of(it.defaultMode),
                        it.verified.takeIf { verified ->
                            verified
                        },
                    )
                },
            hid =
                HidView(
                    device.hid.keyboard,
                    device.hid.mouse,
                    device.hid.mouse && device.hid.mouseLayout == MouseLayout.WHEEL,
                    if (barrier?.pendingReported ==
                        true
                    ) {
                        HID_STATUS_RELEASE_PENDING
                    } else {
                        null
                    },
                    device.hidLink.wireName.takeIf { device.hid.keyboard || device.hid.mouse },
                    device.hidNotReading
                        .takeIf { device.hidLink == HidLinkStatus.OPEN && it.isNotEmpty() }
                        ?.map { it.wireName }
                        ?.sorted(),
                ),
            lease = leaseViewFor(),
            tether = device.usbTethering?.let(::TetherView),
        )
    }

    // ---- Helpers.

    private fun dispatchOrder(stepEffects: List<RemoteEffect>): List<RemoteEffect> {
        val (hidEffects, otherEffects) = stepEffects.partition { it is RemoteEffect.HidSend || it is RemoteEffect.HidReleaseAll }
        return hidEffects + otherEffects
    }

    private fun emit(effect: RemoteEffect) {
        effects.add(effect)
    }

    private fun err(
        code: ErrorCode,
        msg: String,
    ): Outcome.Err = Outcome.Err(code, msg)

    private fun <T> ok(
        serializer: KSerializer<T>,
        value: T,
    ): Outcome.Ok = Outcome.Ok(ProtocolJson.resultOf(serializer, value))

    private fun <T> jsonSizeBytes(
        serializer: KSerializer<T>,
        value: T,
    ): Int =
        ProtocolJson.json
            .encodeToString(serializer, value)
            .encodeToByteArray()
            .size

    private fun <T> decode(
        serializer: KSerializer<T>,
        element: JsonElement,
    ): T = ProtocolJson.json.decodeFromJsonElement(serializer, element)

    private fun JsonObject.stringField(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun checkInvariants() {
        check(sessions.size <= RemoteLimits.CONNECTION_COUNT_MAX) { "too many sessions: ${sessions.size}" }
        check(unauthorizedSessionCount() <= RemoteLimits.PENDING_CONNECTION_COUNT_MAX) { "too many pending sessions" }
        check(pending.size <= RemoteLimits.PENDING_APPROVAL_COUNT_MAX) { "too many pending approvals" }
        check(approvalsByNode.size <= RemoteLimits.APPROVAL_COUNT_MAX) { "too many approvals" }
        check(pairing.tokens.size <= RemoteLimits.TOKEN_COUNT_MAX) { "too many tokens" }
        check(pairing.attemptAtMs.size <= RemoteLimits.PAIRING_ATTEMPT_COUNT_MAX) { "too many recorded attempts" }
        check(leaseEpoch in 0 until RemoteLimits.LEASE_EPOCH_MODULUS)
        check(localCapture == (leaseHolder == LeaseHolder.Local)) { "local capture $localCapture but holder $leaseHolder" }
        (leaseHolder as? LeaseHolder.Remote)?.let { holder ->
            check(
                sessions[holder.sessionId]?.auth == SessionAuth.APPROVED,
            ) { "lease holder ${holder.sessionId} is not a live approved session" }
        }
        job?.let { check(leaseHolder == LeaseHolder.Remote(it.sessionId) && leaseEpoch == it.epoch) { "job outlived its lease" } }
        takeover?.let {
            check(leaseHolder == LeaseHolder.Remote(it.holderId)) { "takeover of a lease the holder lost" }
            check(it.requesterId in sessions) { "takeover requester is gone" }
        }
        for (session in sessions.values) {
            check(session.replies.size <= RemoteLimits.REQUEST_ID_CACHE_SIZE) { "reply cache over its bound" }
            check(session.plans.size <= RemoteLimits.PASTE_PLAN_COUNT_MAX) { "too many paste plans" }
            check((session.auth == SessionAuth.APPROVED) == (session.principal != null)) { "auth and principal disagree" }
        }
        if (leaseHolder !is LeaseHolder.Remote) check(!held.keyboardHeld && held.buttonBits == 0) { "input held without a remote holder" }
        check(downloads.size <= RemoteLimits.DOWNLOAD_COUNT_MAX) { "too many downloads: ${downloads.size}" }
        check(catalogEntries.size <= RemoteLimits.CATALOG_ENTRY_COUNT_MAX) { "too many catalog entries: ${catalogEntries.size}" }
    }

    private companion object {
        const val ERROR_MESSAGE_LENGTH_MAX = 200

        /**
         * `{"id":…,"ok":true,"catalogRev":…,"refreshing":…,"truncated":…,"distros":[]}` with the
         * longest request id (64 chars, up to 4 UTF-8 bytes each), with room to spare.
         */
        const val CATALOG_REPLY_ENVELOPE_BYTES = 512

        /** `{"t":"downloads","truncated":…,"downloads":[]}`, with room to spare. */
        const val DOWNLOADS_PUSH_ENVELOPE_BYTES = 128
        const val PLAN_ID_SIZE_BYTES = 8
        const val FRAME_COST_MILLI = 1_000L
        const val FRAME_BUDGET_MILLI_MAX = RemoteLimits.HID_FRAME_RATE_PER_SECOND_MAX * FRAME_COST_MILLI

        fun sanitizeLabel(label: String): String =
            label
                .filter { !it.isISOControl() && !it.isSurrogate() }
                .trim()
                .take(RemoteLimits.PAIRING_LABEL_LENGTH_MAX)

        fun utf8SizeWithin(
            text: String,
            limitBytes: Int,
        ): Boolean {
            if (text.length > limitBytes) return false
            return text.toByteArray(Charsets.UTF_8).size <= limitBytes
        }

        /** A held report is re-sent every [RemoteLimits.BOOT_KEY_HOLD_RESEND_INTERVAL_MS] so a host that resets USB while booting still sees it. */
        fun withHoldResends(reports: List<TimedReport>): List<TimedReport> =
            buildList {
                for ((current, next) in reports.zipWithNext()) {
                    add(current)
                    if (current.report.isReleaseAll) continue
                    var atMs = current.atMs + RemoteLimits.BOOT_KEY_HOLD_RESEND_INTERVAL_MS
                    while (atMs < next.atMs) {
                        add(TimedReport(atMs, current.report))
                        atMs += RemoteLimits.BOOT_KEY_HOLD_RESEND_INTERVAL_MS
                    }
                }
                add(reports.last())
            }
    }
}
