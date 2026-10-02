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
 */
class HidRelay internal constructor(
    private val openEndpoint: (HidKind) -> RelayEndpoint,
    private val deathLink: ClientDeathLink,
    private val warn: (String) -> Unit,
) {
    constructor() : this(
        { kind -> FdRelayEndpoint(FdHidEndpoint(kind, HidNodes.open(kind))) },
        BinderDeathLink,
        { message -> Log.w(TAG, message) },
    )

    /** An open `/dev/hidgN` as the relay uses it; a fake in JVM tests. */
    interface RelayEndpoint {
        /** Nonblocking writes within the 50ms report deadline. */
        fun writeWithin(report: ByteArray): HidWriteResult

        fun readOutputReport(): ByteArray?

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

    /**
     * (Re)opens [kind] for [clientToken]. A new client replaces the previous one: the new token is linked
     * first (a dead one throws and leaves the previous client and its endpoints untouched), then every
     * endpoint the previous client held is released and closed, so its keys can't stay pressed.
     */
    fun open(
        kind: HidKind,
        clientToken: IBinder,
    ) = synchronized(lock) {
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
        endpoints[kind] = openEndpoint(kind)
    }

    fun write(
        kind: HidKind,
        report: ByteArray,
    ): HidWriteResult {
        require(report.size == kind.reportSizeBytes) { "$kind report must be ${kind.reportSizeBytes} bytes, got ${report.size}" }
        return synchronized(lock) { endpoints[kind]?.writeWithin(report) ?: HidWriteResult.DISCONNECTED }
    }

    fun readOutput(kind: HidKind): ByteArray = synchronized(lock) { endpoints[kind]?.readOutputReport() ?: ByteArray(0) }

    /** Releases [kind] (best effort, within the deadline) and closes it. */
    fun close(kind: HidKind) =
        synchronized(lock) {
            endpoints.remove(kind)?.let { releaseAndClose(kind, it) }
            if (endpoints.isEmpty()) detachClient()
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
        runCatching { endpoint.writeWithin(kind.releaseAllReport) }
        runCatching { endpoint.close() }
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
    ) : RelayEndpoint {
        override fun writeWithin(report: ByteArray) = endpoint.writeWithin(report)

        override fun readOutputReport() = endpoint.readOutputReport()

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

    private companion object {
        const val TAG = "BootlegHidRelay"
    }
}
