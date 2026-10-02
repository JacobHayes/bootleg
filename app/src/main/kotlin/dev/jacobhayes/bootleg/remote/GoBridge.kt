package dev.jacobhayes.bootleg.remote

import android.util.Log
import bootlegremote.Bootlegremote
import bootlegremote.Callbacks
import dev.jacobhayes.bootleg.core.remote.CloseReason
import dev.jacobhayes.bootleg.core.remote.Principal
import dev.jacobhayes.bootleg.core.remote.RemoteActor
import dev.jacobhayes.bootleg.core.remote.TransportPort
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.net.SocketException

/** The embedded node's status JSON (`bootlegremote.Status`). */
@Serializable
data class TailnetStatus(
    val backendState: String = STATE_STOPPED,
    val tailscaleIPs: List<String> = emptyList(),
    val loginURL: String = "",
    val dnsName: String = "",
    val magicDNSSuffix: String = "",
    val health: List<String> = emptyList(),
    val error: String = "",
) {
    val isRunning: Boolean get() = backendState == STATE_RUNNING
    val needsLogin: Boolean get() = backendState == STATE_NEEDS_LOGIN || loginURL.isNotEmpty()

    /** The MagicDNS name without the trailing dot, or "". */
    val fqdn: String get() = dnsName.removeSuffix(".")

    companion object {
        const val STATE_STOPPED = "Stopped"
        const val STATE_RUNNING = "Running"
        const val STATE_NEEDS_LOGIN = "NeedsLogin"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(statusJson: String): TailnetStatus =
            runCatching { json.decodeFromString(serializer(), statusJson) }
                .getOrElse { TailnetStatus(backendState = "Unknown", error = "unreadable status") }
    }
}

/** Kotlin → Go: the [TransportPort] over gomobile's static functions. Every call only enqueues in Go. */
object GoTransport : TransportPort {
    override fun authorized(sessionId: Long) {
        Bootlegremote.authorized(sessionId)
    }

    override fun send(
        sessionId: Long,
        json: String,
    ): Boolean = Bootlegremote.send(sessionId, json)

    override fun close(
        sessionId: Long,
        reason: CloseReason,
    ) {
        Bootlegremote.close(sessionId, reason.wireName)
    }

    override fun revoke(principal: Principal) {
        Bootlegremote.revoke(principal.kind, principal.id)
    }
}

/**
 * Go → Kotlin: forwards session callbacks to the actor (which only enqueues) and status to
 * [onStatus]. Runs on Go threads; nothing here blocks.
 */
class GoCallbacks(
    private val actor: RemoteActor,
    private val onStatus: (TailnetStatus) -> Unit,
) : Callbacks {
    override fun onStatus(statusJSON: String) = onStatus(TailnetStatus.parse(statusJSON))

    override fun interfaces(): String = NetworkInterfaces.json()

    override fun open(
        sessionID: Long,
        connJSON: String,
    ) = actor.open(sessionID, connJSON)

    override fun command(
        sessionID: Long,
        messageJSON: String,
    ) = actor.command(sessionID, messageJSON)

    override fun hid(
        sessionID: Long,
        frame: ByteArray,
    ): Int = if (actor.hid(sessionID, frame)) HID_QUEUED else HID_QUEUE_FULL

    override fun closed(
        sessionID: Long,
        reason: String,
    ) = actor.closed(sessionID, reason)

    private companion object {
        const val HID_QUEUED = 0
        const val HID_QUEUE_FULL = 1
    }
}

/**
 * Android denies app UIDs NETLINK_ROUTE, so Go can't list interfaces itself (tailscale#17311).
 * Produces tailscale-android's `getInterfacesAsJson` shape for `netmon.RegisterInterfaceGetter`.
 */
object NetworkInterfaces {
    private const val TAG = "BootlegRemote"

    fun json(): String {
        val result = JSONArray()
        val interfaces =
            try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            } catch (e: SocketException) {
                Log.w(TAG, "listing interfaces failed: ${e.message}")
                emptyList()
            }
        for (networkInterface in interfaces) {
            try {
                val addresses = JSONArray()
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val host = interfaceAddress.address?.hostAddress ?: continue
                    addresses.put(JSONObject().put("ip", host).put("prefixLen", interfaceAddress.networkPrefixLength.toInt()))
                }
                result.put(
                    JSONObject()
                        .put("name", networkInterface.name)
                        .put("index", networkInterface.index)
                        .put("mtu", networkInterface.mtu)
                        .put("up", networkInterface.isUp)
                        .put("broadcast", networkInterface.supportsMulticast())
                        .put("loopback", networkInterface.isLoopback)
                        .put("pointToPoint", networkInterface.isPointToPoint)
                        .put("multicast", networkInterface.supportsMulticast())
                        .put("addrs", addresses),
                )
            } catch (e: SocketException) {
                // An interface can vanish mid-enumeration; skip it.
                Log.w(TAG, "skipping interface ${networkInterface.name}: ${e.message}")
            }
        }
        return result.toString()
    }
}
