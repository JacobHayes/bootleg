package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.hid.HidKind
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom

/** Two clocks: elapsed (monotonic, for timers) and wall (Unix ms, for records that outlive the process). */
interface RemoteClock {
    /** Monotonic ms, e.g. `SystemClock.elapsedRealtime()`. */
    fun elapsedMs(): Long

    /** Wall-clock Unix ms, e.g. `System.currentTimeMillis()`. */
    fun wallMs(): Long
}

/** JVM clocks; on Android prefer `SystemClock.elapsedRealtime()` (it keeps counting in deep sleep). */
object SystemRemoteClock : RemoteClock {
    override fun elapsedMs(): Long = System.nanoTime() / NANOS_PER_MILLI

    override fun wallMs(): Long = System.currentTimeMillis()

    private const val NANOS_PER_MILLI = 1_000_000L
}

interface RandomSource {
    fun nextBytes(sizeBytes: Int): ByteArray

    /** Uniform in 0 until [bound]. */
    fun nextInt(bound: Int): Int
}

class SecureRandomSource(
    private val random: SecureRandom = SecureRandom(),
) : RandomSource {
    override fun nextBytes(sizeBytes: Int): ByteArray = ByteArray(sizeBytes).also { random.nextBytes(it) }

    override fun nextInt(bound: Int): Int = random.nextInt(bound)
}

/** Kotlin → Go (the gomobile bridge). Every call must only enqueue and return promptly. */
interface TransportPort {
    /** Go may now forward everything from this session, not just its first `hello`. */
    fun authorized(sessionId: Long)

    /** Queues one JSON text message; false if the session is gone, closing, or a slow consumer (Go then closes it). */
    fun send(
        sessionId: Long,
        json: String,
    ): Boolean

    /** Flushes queued messages, then closes with [CloseReason.wireName] (Go maps it to a WebSocket code); idempotent. */
    fun close(
        sessionId: Long,
        reason: CloseReason,
    )

    /** Closes every live connection of [Principal.kind] `node`/`token` with [Principal.id]. */
    fun revoke(principal: Principal)
}

/**
 * The gadget, with `BootlegBackend` semantics. Calls may block for seconds; the actor runs them on
 * a separate bounded executor with a [RemoteLimits.DEVICE_OP_TIMEOUT_MS] deadline.
 */
interface DevicePort {
    suspend fun apply(desired: Desired): ApplyResult

    suspend fun eject(
        lun: Int,
        force: Boolean,
    ): ApplyResult

    suspend fun reinsert(lun: Int): ApplyResult

    suspend fun setHid(spec: HidSpec): ApplyResult

    /** The current device state and every change to it. */
    val snapshots: Flow<DeviceSnapshot>
}

enum class HidSendResult {
    ACCEPTED,

    /** The bounded report queue overflowed; the session dropped it and owes a release-all. */
    QUEUE_FULL,

    /** An owed release-all hasn't reached the host yet; input is refused until it does. */
    RELEASE_PENDING,

    /** Stopped, reconnecting or the host isn't listening. */
    UNAVAILABLE,
}

/**
 * The app's `HidSession`. Both calls are nonblocking (they enqueue).
 *
 * The actor polls [releaseOwed] while its barrier is up (the app may also call
 * `RemoteActor.hidReleaseDelivered()`).
 */
interface HidPort {
    /** A release-all is owed and hasn't reached the host yet (HidSession's barrier). */
    val releaseOwed: Boolean

    fun send(
        kind: HidKind,
        report: ByteArray,
    ): HidSendResult

    /** Drops queued reports and owes the host a release-all (priority). */
    fun releaseAll()
}

interface ApprovalStore {
    fun load(): List<ApprovalRecord>

    /** Writes atomically (temp file + rename). */
    fun save(records: List<ApprovalRecord>)
}

interface PairingStore {
    fun load(): PairingRecord

    /** Writes atomically (temp file + rename). */
    fun save(record: PairingRecord)
}

/** Tells the user that nodes are waiting; a notification only opens the app, it can't approve. */
fun interface Notifier {
    fun pendingChanged(pending: List<PendingApproval>)
}
