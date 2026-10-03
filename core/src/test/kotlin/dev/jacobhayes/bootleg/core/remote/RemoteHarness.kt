package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.KeyboardReport
import dev.jacobhayes.bootleg.core.hid.MouseReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Random

class FakeRemoteClock(
    var elapsed: Long = 10_000,
    var wall: Long = 1_800_000_000_000,
) : RemoteClock {
    override fun elapsedMs(): Long = elapsed

    override fun wallMs(): Long = wall

    fun advance(durationMs: Long) {
        require(durationMs >= 0)
        elapsed += durationMs
        wall += durationMs
    }
}

class SeededRandom(
    seed: Long,
) : RandomSource {
    private val random = Random(seed)

    override fun nextBytes(sizeBytes: Int): ByteArray = ByteArray(sizeBytes).also { random.nextBytes(it) }

    override fun nextInt(bound: Int): Int = random.nextInt(bound)
}

class InMemoryApprovalStore(
    var records: List<ApprovalRecord> = emptyList(),
) : ApprovalStore {
    override fun load(): List<ApprovalRecord> = records

    override fun save(records: List<ApprovalRecord>) {
        this.records = records
    }
}

class InMemoryPairingStore(
    var record: PairingRecord = PairingRecord(),
) : PairingStore {
    override fun load(): PairingRecord = record

    override fun save(record: PairingRecord) {
        this.record = record
    }
}

/** Host-side view of what the HID port delivered. */
class FakeHidHost {
    var keyboard = KeyboardReport.RELEASE_ALL
    var mouseButtons = 0
    val sent = ArrayList<RemoteEffect.HidSend>()
    var releaseAllCount = 0
    var releaseOwed = false

    val anyHeld: Boolean get() = !keyboard.isReleaseAll || mouseButtons != 0

    fun send(effect: RemoteEffect.HidSend) {
        sent += effect
        when (effect.kind) {
            HidKind.KEYBOARD -> keyboard = KeyboardReport.decode(effect.report)
            HidKind.MOUSE -> mouseButtons = MouseReport.decode(effect.report).buttonBits
        }
    }

    fun releaseAll() {
        releaseAllCount++
        keyboard = KeyboardReport.RELEASE_ALL
        mouseButtons = 0
        releaseOwed = true
    }

    val typedText: String
        get() =
            dev.jacobhayes.bootleg.core.hid.TextTyper.typedText(
                sent.filter { it.kind == HidKind.KEYBOARD }.map { KeyboardReport.decode(it.report) }.filter { it.keys.size <= 1 },
            )
}

/**
 * Drives a [RemoteController] synchronously, applying effects to fakes the way [RemoteActor] does,
 * and (with [checking]) asserts the safety properties after every effect and step.
 *
 * Tests name sessions with strings; the harness maps each name to a Go-style numeric session id.
 */
class RemoteHarness(
    val clock: FakeRemoteClock = FakeRemoteClock(),
    val approvalStore: InMemoryApprovalStore = InMemoryApprovalStore(),
    val pairingStore: InMemoryPairingStore = InMemoryPairingStore(),
    seed: Long = 1,
    var autoDeliverRelease: Boolean = true,
    val checking: Boolean = true,
) {
    val random = SeededRandom(seed)

    /** The last few events and their effects, for failure messages. */
    private val recent = ArrayDeque<String>()

    private val idsByName = HashMap<String, Long>()
    private val namesById = HashMap<Long, String>()

    var controller = RemoteController(clock, random, approvalStore.load(), pairingStore.load())
        private set

    val hidHost = FakeHidHost()

    /** (session name, JSON) of every message Go accepted. */
    val sent = ArrayList<Pair<String, String>>()
    val closes = ArrayList<RemoteEffect.Close>()
    val revoked = ArrayList<String>()
    val deviceCalls = ArrayList<RemoteEffect.Device>()
    val downloadCalls = ArrayList<RemoteEffect.Download>()
    private val answeredDownloadOps = HashSet<Long>()
    var catalogRefreshes = 0
    val effectLog = ArrayList<RemoteEffect>()
    var pendingNotified: List<PendingApproval> = emptyList()

    /** Session names Go considers authorized (Authorize seen, not closed yet). */
    val authorized = HashSet<String>()
    val identities = HashMap<String, ConnIdentity>()
    val live = LinkedHashSet<String>()

    /** Session names whose Send calls fail (a full Go outbound queue). */
    val slowSessions = HashSet<String>()

    private var trackedHolder: LeaseHolder? = null
    private var trackedEpoch = 0
    private var heldEpoch = -1
    private val statePushAtMs = HashMap<String, Long>()
    private var nextRequestId = 1L

    var device =
        DeviceSnapshot(
            hid = HidSpec(keyboard = true, mouse = true),
            images =
                listOf(
                    image("/data/media/0/bootleg/debian.iso", 700_000_000),
                    image("/data/media/0/bootleg/scratch.img", 64_000_000),
                ),
        )
        private set

    init {
        trackedHolder = controller.leaseView.first
        trackedEpoch = controller.leaseView.second
        handle(RemoteEvent.DeviceChanged(device))
    }

    /** Go's numeric session id for a test's session name. */
    fun id(name: String): Long = idsByName.getOrPut(name) { (idsByName.size + 1).toLong().also { namesById[it] = name } }

    fun name(id: Long): String = namesById[id] ?: "#$id"

    fun holder(name: String): LeaseHolder = LeaseHolder.Remote(id(name))

    /** Simulates a process restart: new controller from the stores; Go's sessions are gone. */
    fun restart() {
        controller = RemoteController(clock, random, approvalStore.load(), pairingStore.load())
        // The old process's download calls die with it; the new controller numbers its ops from 1 again.
        downloadCalls.clear()
        answeredDownloadOps.clear()
        trackedHolder = controller.leaseView.first
        trackedEpoch = controller.leaseView.second
        authorized.clear()
        live.clear()
        hidHost.keyboard = KeyboardReport.RELEASE_ALL
        hidHost.mouseButtons = 0
        handle(RemoteEvent.DeviceChanged(device))
    }

    fun recentLog(): String = recent.joinToString("\n")

    fun handle(event: RemoteEvent): List<RemoteEffect> {
        recent.addLast("@${clock.elapsed} $event")
        if (recent.size > RECENT_LOG_SIZE) recent.removeFirst()
        // Go never reuses a live session id; the controller ignores a duplicate Open.
        if (event is RemoteEvent.Open && live.add(name(event.sessionId))) {
            ConnInfo.parse(event.connJson)?.let { identities[name(event.sessionId)] = it.identity }
            statePushAtMs.remove(name(event.sessionId))
        }
        if (event is RemoteEvent.Closed) {
            live -= name(event.sessionId)
            authorized -= name(event.sessionId)
        }
        val effects = controller.handle(event)
        if (event is RemoteEvent.DownloadResult) answeredDownloadOps += event.opId
        recent.addLast("    -> $effects")
        val followUps = ArrayList<RemoteEvent>()
        for (effect in effects) apply(effect, followUps)
        if (checking) checkStep(effects)
        for (followUp in followUps) handle(followUp)
        if (autoDeliverRelease && controller.releaseBarrierActive) {
            hidHost.releaseOwed = false
            handle(RemoteEvent.HidReleaseDelivered)
        }
        return effects
    }

    private fun apply(
        effect: RemoteEffect,
        followUps: MutableList<RemoteEvent>,
    ) {
        effectLog += effect
        when (effect) {
            is RemoteEffect.Authorize -> {
                val name = name(effect.sessionId)
                if (checking) {
                    val identity = checkNotNull(identities[name]) { "authorized unknown session $name" }
                    check(identityValid(identity)) { "authorized $name without a valid record: $identity" }
                }
                authorized += name
            }
            is RemoteEffect.Send -> {
                val name = name(effect.sessionId)
                if (name in slowSessions) {
                    followUps += RemoteEvent.SendFailed(effect.sessionId)
                } else {
                    sent += name to effect.json
                    if (checking && effect.json.contains("\"t\":\"state\"")) {
                        val previous = statePushAtMs[name]
                        check(previous == null || clock.elapsed - previous >= RemoteLimits.PUSH_INTERVAL_MS_MIN) {
                            "state pushes to $name faster than 10/s"
                        }
                        statePushAtMs[name] = clock.elapsed
                    }
                }
            }
            is RemoteEffect.Close -> {
                closes += effect
                authorized -= name(effect.sessionId)
                live -= name(effect.sessionId)
            }
            is RemoteEffect.Revoke -> revoked += "${effect.principal.kind}:${effect.principal.id}"
            is RemoteEffect.DropQueuedInput -> Unit
            is RemoteEffect.HidSend -> {
                if (checking) {
                    // Against the controller's lease as the effect is dispatched, not the LeaseChanged
                    // effects seen so far: a report emitted before a lease change in the same step is stale.
                    val name = name(effect.sessionId)
                    val (holder, epoch) = controller.leaseView
                    check(holder == LeaseHolder.Remote(effect.sessionId)) { "HID from non-holder $name (holder $holder)" }
                    check(effect.epoch == epoch) { "HID with stale epoch ${effect.epoch} (current $epoch)" }
                    check(name in authorized) { "HID for unauthorized $name" }
                }
                hidHost.send(effect)
                if (hidHost.anyHeld) heldEpoch = effect.epoch
            }
            is RemoteEffect.HidReleaseAll -> hidHost.releaseAll()
            is RemoteEffect.Device -> deviceCalls += effect
            is RemoteEffect.Download -> {
                if (checking) {
                    // Admitted and dispatched in this step: the requester holds the lease right now.
                    val holder = controller.leaseView.first
                    check(
                        holder is LeaseHolder.Remote && name(holder.sessionId) in authorized,
                    ) { "download op without a remote holder ($holder)" }
                    check(downloadCalls.none { it.opId !in answeredDownloadOps }) { "two download ops in flight" }
                }
                downloadCalls += effect
            }
            is RemoteEffect.CatalogRefreshIfStale -> catalogRefreshes++
            is RemoteEffect.SaveApprovals -> approvalStore.save(effect.records)
            is RemoteEffect.SavePairing -> pairingStore.save(effect.record)
            is RemoteEffect.PendingChanged -> pendingNotified = effect.pending
            is RemoteEffect.LeaseChanged -> {
                // HID effects are dispatched first in a step, so anything the old holder held is released by now.
                if (checking) {
                    check(!hidHost.anyHeld || heldEpoch == effect.epoch) {
                        "keys held across a lease change (held at $heldEpoch, new epoch ${effect.epoch}, holder ${effect.holder})"
                    }
                }
                trackedHolder = effect.holder
                trackedEpoch = effect.epoch
            }
        }
    }

    private fun identityValid(identity: ConnIdentity): Boolean =
        when (identity) {
            is ConnIdentity.Node -> approvalStore.records.any { it.nodeId == identity.nodeId && it.isValidAt(clock.wall) }
            is ConnIdentity.Token -> pairingStore.record.tokens.any { it.sha256 == identity.tokenHash && it.isValidAt(clock.wall) }
            is ConnIdentity.Pairing -> false
        }

    private fun checkStep(effects: List<RemoteEffect>) {
        val status = controller.status()
        check(status.sessions.size <= RemoteLimits.CONNECTION_COUNT_MAX)
        check(status.sessions.count { it.auth != AuthStatus.APPROVED } <= RemoteLimits.PENDING_CONNECTION_COUNT_MAX)
        check(status.pending.size <= RemoteLimits.PENDING_APPROVAL_COUNT_MAX)
        check(status.tokens.size <= RemoteLimits.TOKEN_COUNT_MAX)
        check(status.approvals.size <= RemoteLimits.APPROVAL_COUNT_MAX)
        check(effects.count { it is RemoteEffect.HidSend } <= RemoteLimits.JOB_REPORTS_PER_STEP_MAX + 2) { "too many reports in one step" }
        check(
            status.leaseHolder == trackedHolder && status.leaseEpoch == trackedEpoch,
        ) { "LeaseChanged effects disagree with the controller" }
        for (name in authorized) {
            check(identityValid(identities.getValue(name))) { "$name stays authorized without a valid record" }
        }
        if (hidHost.anyHeld) {
            val (holder, epoch) = controller.leaseView
            check(holder is LeaseHolder.Remote && heldEpoch == epoch) {
                "keys held on the host after the lease moved (held at $heldEpoch, epoch $epoch, holder $holder)"
            }
        }
    }

    // ---- Convenience.

    fun open(
        name: String,
        identity: ConnIdentity,
        userAgent: String = "",
    ) = handle(
        RemoteEvent.Open(
            id(name),
            ConnInfo.encode(
                ConnInfo(
                    transport = if (identity is ConnIdentity.Node) ConnInfo.TRANSPORT_TAILNET else ConnInfo.TRANSPORT_HOTSPOT,
                    remoteAddr = "192.168.49.${id(name) % 250}:5000",
                    identity = identity,
                    userAgent = userAgent,
                ),
            ),
        ),
    )

    fun tailnet(
        nodeId: String,
        name: String = "node-$nodeId",
    ) = ConnIdentity.Node(nodeId, user = "user@example.com", name = name)

    fun closed(
        name: String,
        reason: String = "dead",
    ) = handle(RemoteEvent.Closed(id(name), reason))

    fun command(
        name: String,
        json: String,
    ) = handle(RemoteEvent.Command(id(name), json))

    fun hello(name: String) = command(name, """{"id":0,"t":"hello","v":1}""")

    fun approve(
        nodeId: String,
        allowRw: Boolean = false,
    ) = handle(RemoteEvent.Approve(nodeId, allowRw))

    /** Open, approve in the app, hello. */
    fun connect(
        name: String,
        nodeId: String = "node-$name",
        allowRw: Boolean = false,
    ) {
        open(name, tailnet(nodeId))
        if (name !in authorized) approve(nodeId, allowRw)
        hello(name)
    }

    /** A hotspot `/pair` exchange as Go runs it; returns Go's verdict and the token hash Go minted. */
    fun pair(
        name: String,
        code: String,
        userAgent: String = "bootleg-cli",
    ): Pair<JsonObject, String> {
        val tokenHash = sha256Hex("token-$name-${clock.elapsed}")
        open(name, ConnIdentity.Pairing(tokenHash), userAgent)
        val before = sent.size
        command(name, """{"t":"pair","code":"$code","tokenHash":"$tokenHash"}""")
        val verdict = sent.drop(before).firstOrNull { it.first == name }?.let { parse(it.second) } ?: JsonObject(emptyMap())
        if (name in live) closed(name, "pair_done")
        return verdict to tokenHash
    }

    fun request(
        name: String,
        type: String,
        body: Map<String, JsonElement> = emptyMap(),
        epoch: Int? = null,
        id: JsonPrimitive = JsonPrimitive(nextRequestId++),
    ): JsonObject? {
        val message =
            buildJsonObject {
                put("id", id)
                put("t", type)
                if (epoch != null) put("epoch", epoch)
                for ((key, value) in body) put(key, value)
            }
        val before = sent.size
        command(name, message.toString())
        return sent
            .drop(before)
            .filter { it.first == name }
            .map { parse(it.second) }
            .lastOrNull { it["id"] == id }
    }

    fun request(
        name: String,
        type: String,
        body: Map<String, JsonElement> = emptyMap(),
        epoch: Int? = null,
        id: String,
    ): JsonObject? = request(name, type, body, epoch, JsonPrimitive(id))

    fun replyFor(
        name: String,
        id: String,
    ): JsonObject? = sent.filter { it.first == name }.map { parse(it.second) }.lastOrNull { it["id"] == JsonPrimitive(id) }

    fun pushes(
        name: String,
        type: String,
    ): List<JsonObject> = sent.filter { it.first == name }.map { parse(it.second) }.filter { it["t"]?.jsonPrimitive?.content == type }

    /** The lease object of the last `lease` push to [name]. */
    fun lastLease(name: String): JsonObject = pushes(name, "lease").last().getValue("lease").jsonObject

    fun epoch(): Int = controller.leaseView.second

    fun take(name: String): Int {
        val reply = checkNotNull(request(name, "lease.take"))
        check(reply.ok) { "lease.take failed: $reply" }
        return reply.getValue("epoch").jsonPrimitive.int
    }

    fun frame(
        name: String,
        frame: HidFrame,
    ) = handle(RemoteEvent.Hid(id(name), HidFrameCodec.encode(frame)))

    fun setDevice(snapshot: DeviceSnapshot) {
        device = snapshot
        // A removed HID function no longer holds anything on the host.
        if (!snapshot.hid.keyboard) hidHost.keyboard = KeyboardReport.RELEASE_ALL
        if (!snapshot.hid.mouse) hidHost.mouseButtons = 0
        handle(RemoteEvent.DeviceChanged(snapshot))
    }

    fun advance(durationMs: Long) {
        clock.advance(durationMs)
        handle(RemoteEvent.Tick)
    }

    /** Advances in small steps (as the actor would wake), so jobs and pushes run on time. */
    fun runFor(
        durationMs: Long,
        stepMs: Long = 8,
    ) {
        var remainingMs = durationMs
        while (remainingMs > 0) {
            val stepDurationMs = minOf(stepMs, remainingMs)
            advance(stepDurationMs)
            remainingMs -= stepDurationMs
        }
    }

    companion object {
        private const val RECENT_LOG_SIZE = 80
        private val json = Json

        fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

        fun image(
            path: String,
            sizeBytes: Long,
        ) = RemoteImage(RemoteImage.idFor(path), path.substringAfterLast('/'), sizeBytes, path, RemoteImage.defaultModeFor(path))

        fun str(value: String): JsonElement = JsonPrimitive(value)

        fun num(value: Number): JsonElement = JsonPrimitive(value)

        fun bool(value: Boolean): JsonElement = JsonPrimitive(value)
    }
}

val JsonObject.ok: Boolean get() = getValue("ok").jsonPrimitive.boolean
val JsonObject.errorCode: String? get() = (this["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content

fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
