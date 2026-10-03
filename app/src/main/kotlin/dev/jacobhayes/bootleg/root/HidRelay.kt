package dev.jacobhayes.bootleg.root

import android.os.IBinder
import android.util.Log
import dev.jacobhayes.bootleg.core.hid.HidKind
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
 */
class HidRelay internal constructor(
    private val openEndpoint: (HidKind) -> RelayEndpoint,
    private val deathLink: ClientDeathLink,
    private val warn: (String) -> Unit,
    private val startWatch: (watch: () -> Boolean) -> Unit = {},
) {
    constructor() : this(
        { kind ->
            val node = HidNodes.open(kind)
            FdRelayEndpoint(FdHidEndpoint(kind, node.descriptor), node.sysfsPath, node.sysfsInode)
        },
        BinderDeathLink,
        { message -> Log.w(TAG, message) },
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

    private val lock = Any()
    private val endpoints = HashMap<HidKind, RelayEndpoint>()
    private var client: IBinder? = null
    private var clientDeath: IBinder.DeathRecipient? = null

    /** True while the gadget controller is changing the gadget: no fd may be opened. */
    private var fenced = false

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
    ) = synchronized(lock) {
        check(!fenced) { "keyboard and mouse are paused while the USB gadget changes" }
        check(quarantined.isEmpty()) { QUARANTINED_MESSAGE }
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
        check(quarantined.isEmpty()) { QUARANTINED_MESSAGE }
        endpoints[kind] = openEndpoint(kind)
        if (!watching) {
            watching = true
            startWatch(::checkBindings)
        }
    }

    /**
     * Polled every [WATCH_INTERVAL_MS] while anything is open: an fd whose function someone else unbound
     * is closed right away, before the HAL's rebind (350ms or more after an unbind) can poison it. False
     * once nothing is open, which stops the watch.
     */
    fun checkBindings(): Boolean =
        synchronized(lock) {
            HidKind.entries.forEach(::current)
            watching = endpoints.isNotEmpty()
            watching
        }

    fun write(
        kind: HidKind,
        report: ByteArray,
    ): HidWriteResult {
        require(report.size == kind.reportSizeBytes) { "$kind report must be ${kind.reportSizeBytes} bytes, got ${report.size}" }
        return synchronized(lock) { current(kind)?.writeWithin(report) ?: HidWriteResult.DISCONNECTED }
    }

    fun readOutput(kind: HidKind): ByteArray = synchronized(lock) { current(kind)?.readOutputReport() ?: ByteArray(0) }

    /** Releases [kind] (best effort, within the deadline) and closes it. */
    fun close(kind: HidKind) =
        synchronized(lock) {
            endpoints.remove(kind)?.let { releaseAndClose(kind, it) }
            if (endpoints.isEmpty()) detachClient()
        }

    /** Before the gadget controller rebinds or removes anything: release and close all, and refuse opens until [endGadgetChange]. */
    fun closeAllForGadgetChange() =
        synchronized(lock) {
            fenced = true
            releaseAndCloseAll()
        }

    fun endGadgetChange() =
        synchronized(lock) {
            fenced = false
        }

    val quarantinedCount: Int get() = synchronized(lock) { quarantined.size }

    /** [kind]'s endpoint if it is still on its bind; one that isn't is closed or quarantined and dropped. */
    private fun current(kind: HidKind): RelayEndpoint? {
        val endpoint = endpoints[kind] ?: return null
        if (endpoint.binding() == Binding.CURRENT) return endpoint
        endpoints.remove(kind)
        releaseAndClose(kind, endpoint)
        if (endpoints.isEmpty()) detachClient()
        return null
    }

    private fun onClientDied(token: IBinder) {
        synchronized(lock) {
            if (client !== token) return
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
        const val QUARANTINED_MESSAGE =
            "a keyboard/mouse device was re-enumerated by someone else while open; restart the phone to use keyboard and mouse again"
    }
}
