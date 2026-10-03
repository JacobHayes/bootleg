package dev.jacobhayes.bootleg.root

import android.os.IBinder
import android.util.Log
import dev.jacobhayes.bootleg.core.gadget.DefaultGadgetController
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.HidLimits
import dev.jacobhayes.bootleg.hid.FdHidEndpoint
import dev.jacobhayes.bootleg.hid.HidWriteResult

/**
 * Root side of HID input. The `/dev/hidgN` nodes carry the generic `device` SELinux label, which
 * apps may not read or write (an fd passed over binder is checked too), so root keeps the fds and
 * the app sends each report over AIDL. Every write is nonblocking with the 50ms deadline, so a host
 * that stops polling never holds a binder thread longer than that. If the client process dies,
 * root releases every key and button itself.
 *
 * An fd still open when its function is bound again panics the kernel once closed (see
 * [dev.jacobhayes.bootleg.core.gadget.HidNodeGuard]). So the gadget controller closes everything
 * first ([closeAllForGadgetChange]) and opens are refused until [endGadgetChange]; and since someone
 * else may rebind behind Bootleg, every call first checks the binding the fd was opened under: an
 * fd whose function was only unbound is closed at once (safe before a rebind), and one that was
 * rebound is quarantined, never closed.
 *
 * Whenever the relay drops the client's fds on its own (not on the client's write or close), it
 * tells the client ([ClientNotifier.closed]), and once opens are safe again it says so
 * ([ClientNotifier.reopenable]): when the gadget change that closed them ends, or, after a close it
 * didn't initiate (someone else unbound or rebound the function), once the UDC and the lost functions'
 * nodes have stayed bound for [DefaultGadgetController.STABLE_BOUND_MS] (at most
 * [HidLimits.RELAY_SETTLE_WAIT_MS_MAX]). Until then opens are refused: the HAL unbinds and rebinds
 * more than once after a mode switch, and an fd opened in between would be held across its next rebind.
 */
class HidRelay internal constructor(
    private val openEndpoint: (HidKind) -> RelayEndpoint,
    private val deathLink: ClientDeathLink,
    private val warn: (String) -> Unit,
    private val notifier: ClientNotifier = ClientNotifier.NONE,
    /** Whether the UDC is bound and every one of these functions has its device node (polled while settling). */
    private val gadgetBound: (Set<HidKind>) -> Boolean = { true },
    private val nowMs: () -> Long = { 0L },
    private val startWatch: (watch: () -> Boolean) -> Unit = {},
) {
    constructor() : this(
        { kind ->
            val node = HidNodes.open(kind)
            FdRelayEndpoint(FdHidEndpoint(kind, node.descriptor), node.sysfsPath, node.sysfsInode)
        },
        BinderDeathLink,
        { message -> Log.w(TAG, message) },
        BinderClientNotifier,
        HidNodes::gadgetBound,
        android.os.SystemClock::elapsedRealtime,
        ::watchOnThread,
    )

    /** Whether the bind an fd was opened under is still the function's current one. */
    enum class Binding {
        /** Same bind: safe to use and to close. */
        CURRENT,

        /** Unbound since, not (yet) rebound: no longer usable, safe to close now. */
        UNBOUND,

        /** Bound again since: closing this fd would panic the kernel. */
        REBOUND,
    }

    /** An open `/dev/hidgN` as the relay uses it; a fake in JVM tests. */
    interface RelayEndpoint {
        /** Nonblocking writes within the 50ms report deadline. */
        fun writeWithin(report: ByteArray): HidWriteResult

        fun readOutputReport(): ByteArray?

        fun binding(): Binding

        fun close()
    }

    /** Binder death notifications; [link] throws if the client is already dead. */
    interface ClientDeathLink {
        fun link(
            client: IBinder,
            recipient: IBinder.DeathRecipient,
        )

        fun unlink(
            client: IBinder,
            recipient: IBinder.DeathRecipient,
        )
    }

    /** One-way notices to the client ([IHidRelayClient]); never blocks and never throws. */
    interface ClientNotifier {
        fun closed(
            client: IBinder,
            reason: String,
            reopenFollows: Boolean,
            restartRequired: Boolean,
        )

        fun reopenable(client: IBinder)

        companion object {
            val NONE =
                object : ClientNotifier {
                    override fun closed(
                        client: IBinder,
                        reason: String,
                        reopenFollows: Boolean,
                        restartRequired: Boolean,
                    ) = Unit

                    override fun reopenable(client: IBinder) = Unit
                }
        }
    }

    private val lock = Any()

    /** Taken around collecting and sending notices, so they reach the client in the order they were raised. */
    private val noticeLock = Any()
    private val endpoints = HashMap<HidKind, RelayEndpoint>()
    private var client: IBinder? = null
    private var clientDeath: IBinder.DeathRecipient? = null

    /** True while the gadget controller is changing the gadget: no fd may be opened. */
    private var fenced = false

    /** The client to tell when the current gadget change ends: it lost its fds to it, or was refused an open. */
    private var reopenWaiter: IBinder? = null

    /** Functions lost to someone else's unbind or rebind: opens wait until they are stably bound again. */
    private val settlingKinds = mutableSetOf<HidKind>()
    private var settleStartMs = 0L

    /** Since when the gadget has been seen bound while settling; null while it isn't. */
    private var boundSinceMs: Long? = null

    /** Raised under [lock], sent after it is released (see [locked]). */
    private val notices = mutableListOf<(ClientNotifier) -> Unit>()

    /** True while a watch is polling [checkBindings]. */
    private var watching = false

    /** Fds that crossed a foreign rebind. Never closed (closing panics); kept referenced so nothing finalizes them. */
    private val quarantined = mutableListOf<RelayEndpoint>()

    /**
     * (Re)opens [kind] for [clientToken]. A new client replaces the previous one: the new token is linked
     * first (a dead one throws and leaves the previous client and its endpoints untouched), then every
     * endpoint the previous client held is released and closed, so its keys can't stay pressed.
     */
    fun open(
        kind: HidKind,
        clientToken: IBinder,
    ) = locked {
        refuseIfQuarantined(clientToken)
        if (fenced || settlingKinds.isNotEmpty()) {
            reopenWaiter = clientToken
            error(if (fenced) GADGET_CHANGING_MESSAGE else SETTLING_MESSAGE)
        }
        if (client !== clientToken) {
            // One recipient per client, so a late death notice from a replaced client is ignored.
            val recipient = IBinder.DeathRecipient { onClientDied(clientToken) }
            deathLink.link(clientToken, recipient)
            detachClient()
            releaseAndCloseAll()
            client = clientToken
            clientDeath = recipient
        }
        endpoints.remove(kind)?.let { releaseAndClose(kind, it) }
        // Releasing the replaced endpoints may have found one rebound behind us.
        refuseIfQuarantined(clientToken)
        endpoints[kind] = openEndpoint(kind)
        ensureWatching()
    }

    /**
     * Polled every [WATCH_INTERVAL_MS] while anything is open or settling: an fd whose function someone
     * else unbound is closed right away, before the HAL's rebind (350ms or more after an unbind) can poison
     * it, and a settle ends once the gadget has stayed bound long enough. False once there is neither,
     * which stops the watch.
     */
    fun checkBindings(): Boolean =
        locked {
            HidKind.entries.forEach(::current)
            advanceSettle()
            watching = endpoints.isNotEmpty() || settlingKinds.isNotEmpty()
            watching
        }

    fun write(
        kind: HidKind,
        report: ByteArray,
    ): HidWriteResult {
        require(report.size == kind.reportSizeBytes) { "$kind report must be ${kind.reportSizeBytes} bytes, got ${report.size}" }
        // The notice can't race the caller's reopen: opens are refused until the settle ends.
        return locked { current(kind)?.writeWithin(report) ?: HidWriteResult.DISCONNECTED }
    }

    fun readOutput(kind: HidKind): ByteArray = locked { current(kind)?.readOutputReport() ?: ByteArray(0) }

    /** Releases [kind] (best effort, within the deadline) and closes it. */
    fun close(kind: HidKind) =
        locked {
            endpoints.remove(kind)?.let { releaseAndClose(kind, it) }
            if (endpoints.isEmpty()) detachClient()
        }

    /** Before the gadget controller rebinds or removes anything: release and close all, and refuse opens until [endGadgetChange]. */
    fun closeAllForGadgetChange() =
        locked {
            fenced = true
            val holder = client
            val hadEndpoints = endpoints.isNotEmpty()
            releaseAndCloseAll()
            if (holder != null) {
                reopenWaiter = holder
                if (hadEndpoints) {
                    val restartRequired = quarantined.isNotEmpty()
                    val reason = if (restartRequired) QUARANTINED_MESSAGE else GADGET_CHANGING_MESSAGE
                    notice { it.closed(holder, reason, reopenFollows = !restartRequired, restartRequired = restartRequired) }
                }
            }
        }

    fun endGadgetChange() =
        locked {
            fenced = false
            // While settling, the settle's end tells the waiter instead.
            if (settlingKinds.isNotEmpty()) return@locked
            val waiter = reopenWaiter
            reopenWaiter = null
            if (waiter != null && quarantined.isEmpty()) notice { it.reopenable(waiter) }
        }

    val quarantinedCount: Int get() = synchronized(lock) { quarantined.size }

    /** Caller holds [lock]. Ends the settle once the gadget has been bound for the stable-bound window, or gives up at the cap. */
    private fun advanceSettle() {
        if (settlingKinds.isEmpty()) return
        val now = nowMs()
        val bound = runCatching { gadgetBound(settlingKinds.toSet()) }.getOrDefault(false)
        if (!bound) {
            boundSinceMs = null
        } else if (boundSinceMs == null) {
            boundSinceMs = now
        }
        val stable = bound && now - checkNotNull(boundSinceMs) >= DefaultGadgetController.STABLE_BOUND_MS
        val expired = now - settleStartMs >= HidLimits.RELAY_SETTLE_WAIT_MS_MAX
        if (!stable && !expired) return
        if (expired && !stable) warn("USB did not stay bound within ${HidLimits.RELAY_SETTLE_WAIT_MS_MAX}ms; allowing HID opens again")
        settlingKinds.clear()
        boundSinceMs = null
        // Only a stable gadget is announced; at the cap the client's own wait runs out at the same time.
        val waiter = reopenWaiter
        if (stable && waiter != null && !fenced && quarantined.isEmpty()) {
            reopenWaiter = null
            notice { it.reopenable(waiter) }
        }
    }

    /** Caller holds [lock]. */
    private fun ensureWatching() {
        if (watching) return
        watching = true
        startWatch(::checkBindings)
    }

    /** Runs [block] under [lock], then sends the notices it raised (even when it throws), outside [lock]. */
    private fun <T> locked(block: () -> T): T =
        try {
            synchronized(lock, block)
        } finally {
            synchronized(noticeLock) {
                val pending = synchronized(lock) { notices.toList().also { notices.clear() } }
                pending.forEach { send -> runCatching { send(notifier) } }
            }
        }

    /** Caller holds [lock]. */
    private fun notice(send: (ClientNotifier) -> Unit) {
        check(notices.size < NOTICE_COUNT_MAX) { "more than $NOTICE_COUNT_MAX HID notices raised in one call" }
        notices += send
    }

    /** Caller holds [lock]: tells [clientToken] to stop trying (it would only be refused until a restart), then throws. */
    private fun refuseIfQuarantined(clientToken: IBinder) {
        if (quarantined.isEmpty()) return
        notice { it.closed(clientToken, QUARANTINED_MESSAGE, reopenFollows = false, restartRequired = true) }
        error(QUARANTINED_MESSAGE)
    }

    /** [kind]'s endpoint if it is still on its bind; one that isn't is closed or quarantined and dropped. */
    private fun current(kind: HidKind): RelayEndpoint? {
        val endpoint = endpoints[kind] ?: return null
        if (endpoint.binding() == Binding.CURRENT) return endpoint
        val holder = client
        endpoints.remove(kind)
        releaseAndClose(kind, endpoint)
        // Someone else re-enumerated: no open until the gadget has settled (see the class comment).
        if (settlingKinds.isEmpty()) {
            settleStartMs = nowMs()
            boundSinceMs = null
        }
        settlingKinds += kind
        if (holder != null) reopenWaiter = holder
        ensureWatching()
        if (holder != null) {
            val restartRequired = quarantined.isNotEmpty()
            val reason = if (restartRequired) QUARANTINED_MESSAGE else REENUMERATED_MESSAGE
            notice { it.closed(holder, reason, reopenFollows = !restartRequired, restartRequired = restartRequired) }
        }
        if (endpoints.isEmpty()) detachClient()
        return null
    }

    private fun onClientDied(token: IBinder) {
        locked {
            if (reopenWaiter === token) reopenWaiter = null
            if (client !== token) return@locked
            warn("HID client died; releasing all keys and buttons")
            releaseAndCloseAll()
            client = null
            clientDeath = null
        }
    }

    private fun releaseAndCloseAll() {
        endpoints.forEach { (kind, endpoint) -> releaseAndClose(kind, endpoint) }
        endpoints.clear()
    }

    private fun releaseAndClose(
        kind: HidKind,
        endpoint: RelayEndpoint,
    ) {
        when (endpoint.binding()) {
            Binding.CURRENT -> {
                runCatching { endpoint.writeWithin(kind.releaseAllReport) }
                runCatching { endpoint.close() }
            }
            // Unbound and not rebound yet: the last close releases the cdev cleanly.
            Binding.UNBOUND -> runCatching { endpoint.close() }
            Binding.REBOUND -> {
                // Opens stop once one is quarantined, so there is at most one per kind.
                check(quarantined.size < QUARANTINED_COUNT_MAX) { "more than $QUARANTINED_COUNT_MAX quarantined hidg fds" }
                quarantined += endpoint
                warn("$kind hidg fd was open across a rebind by someone else; keeping it open, since closing it would panic the kernel")
            }
        }
    }

    private fun detachClient() {
        val previous = client
        val recipient = clientDeath
        if (previous != null && recipient != null) runCatching { deathLink.unlink(previous, recipient) }
        client = null
        clientDeath = null
    }

    private class FdRelayEndpoint(
        private val endpoint: FdHidEndpoint,
        private val sysfsPath: String,
        private val sysfsInode: Long,
    ) : RelayEndpoint {
        override fun writeWithin(report: ByteArray) = endpoint.writeWithin(report)

        override fun readOutputReport() = endpoint.readOutputReport()

        // Anything unexpected counts as rebound: a leaked fd is recoverable by a reboot, a bad close is not.
        override fun binding(): Binding =
            when (runCatching { HidNodes.sysfsInode(sysfsPath) }.getOrElse { -1L }) {
                sysfsInode -> Binding.CURRENT
                null -> Binding.UNBOUND
                else -> Binding.REBOUND
            }

        override fun close() = endpoint.close()
    }

    private object BinderDeathLink : ClientDeathLink {
        override fun link(
            client: IBinder,
            recipient: IBinder.DeathRecipient,
        ) = client.linkToDeath(recipient, 0)

        override fun unlink(
            client: IBinder,
            recipient: IBinder.DeathRecipient,
        ) {
            client.unlinkToDeath(recipient, 0)
        }
    }

    /** Oneway calls: a busy or dead app never holds root up, and a dead one only makes them fail (ignored). */
    private object BinderClientNotifier : ClientNotifier {
        override fun closed(
            client: IBinder,
            reason: String,
            reopenFollows: Boolean,
            restartRequired: Boolean,
        ) {
            runCatching { IHidRelayClient.Stub.asInterface(client).onClosed(reason, reopenFollows, restartRequired) }
                .onFailure { Log.w(TAG, "HID client notice failed", it) }
        }

        override fun reopenable(client: IBinder) {
            runCatching { IHidRelayClient.Stub.asInterface(client).onReopenable() }
                .onFailure { Log.w(TAG, "HID client notice failed", it) }
        }
    }

    companion object {
        private const val TAG = "BootlegHidRelay"

        /** Well under the HAL's rebind delay after an unbind (350ms or more, measured). */
        const val WATCH_INTERVAL_MS = 50L

        private fun watchOnThread(watch: () -> Boolean) {
            val thread = Thread({ while (watch()) Thread.sleep(WATCH_INTERVAL_MS) }, "BootlegHidRelayWatch")
            thread.isDaemon = true
            thread.start()
        }

        val QUARANTINED_COUNT_MAX = HidKind.entries.size

        /** One per kind from a watch pass, plus a refusal and a reopen notice. */
        val NOTICE_COUNT_MAX = HidKind.entries.size + 2
        const val QUARANTINED_MESSAGE =
            "a keyboard/mouse device was re-enumerated by someone else while open; restart the phone to use keyboard and mouse again"
        const val GADGET_CHANGING_MESSAGE = "keyboard and mouse are paused while the USB gadget changes"
        const val REENUMERATED_MESSAGE = "USB was re-enumerated"
        const val SETTLING_MESSAGE = "keyboard and mouse wait for USB to settle after a re-enumeration"
    }
}
