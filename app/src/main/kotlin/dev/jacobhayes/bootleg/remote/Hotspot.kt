package dev.jacobhayes.bootleg.remote

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

/** The phone-hosted offline network (LocalOnlyHotspot) as the UI shows it. */
data class HotspotInfo(
    val ssid: String,
    val passphrase: String,
    /** `WPA` or `SAE` for the Wi-Fi QR code; `nopass` for an open network. */
    val qrSecurity: String,
    /** The phone's address on the hotspot with its prefix, e.g. `192.168.49.1/24`. */
    val interfaceCidr: String,
) {
    val address: String get() = interfaceCidr.substringBefore('/')
    val url: String get() = "http://$address:$HOTSPOT_PORT"

    /** The standard Wi-Fi QR payload (`WIFI:T:...;S:...;P:...;;`) with its special characters escaped. */
    val wifiQrPayload: String
        get() = "WIFI:T:$qrSecurity;S:${escape(ssid)};" + (if (qrSecurity == "nopass") "" else "P:${escape(passphrase)};") + ";"

    companion object {
        /** Go serves the hotspot on this port (bootlegremote.hotspotPort). */
        const val HOTSPOT_PORT = 8080

        private fun escape(text: String): String = text.replace(Regex("""([\\;,:"])"""), """\\$1""")
    }
}

/**
 * Suspends until [start]'s callback delivers a resource (or fails). The handoff is atomic with
 * cancellation: a delivered value is either returned to the caller or passed to [close], even when
 * the caller is cancelled before Android answers or between the answer and the caller resuming.
 */
internal suspend fun <T : Any> awaitHandoff(
    close: (T) -> Unit,
    start: (deliver: (T) -> Unit, fail: (Throwable) -> Unit) -> Unit,
): T =
    suspendCancellableCoroutine { continuation ->
        start(
            { value -> continuation.resume(value) { _, undelivered, _ -> close(undelivered) } },
            { failure -> if (continuation.isActive) continuation.resumeWithException(failure) },
        )
    }

/**
 * Starts and stops Android's LocalOnlyHotspot. Needs NEARBY_WIFI_DEVICES (checked by the caller).
 * Callbacks arrive on the main looper.
 */
class Hotspot(
    private val context: Context,
) : HotspotReservation {
    /**
     * The open reservation. Set by Android's onStarted (main looper) before the start resumes, so a
     * start cancelled in between still finds and closes it; cleared by whoever closes it.
     */
    private val reservation = AtomicReference<WifiManager.LocalOnlyHotspotReservation?>(null)

    override val isOn: Boolean get() = reservation.get() != null

    /** Starts the hotspot; returns its details, or throws with a reason. [onStopped] runs if the system stops it later. */
    @SuppressLint("MissingPermission") // The UI requests NEARBY_WIFI_DEVICES before starting.
    override suspend fun start(onStopped: () -> Unit): HotspotInfo {
        check(reservation.get() == null) { "hotspot already started" }
        val wifiManager = context.getSystemService(WifiManager::class.java)
        val defaultInterface = defaultInterfaceName()
        val notifyStopped = onStopped
        val started =
            awaitHandoff(close = ::close) { deliver, fail ->
                wifiManager.startLocalOnlyHotspot(
                    object : WifiManager.LocalOnlyHotspotCallback() {
                        private var own: WifiManager.LocalOnlyHotspotReservation? = null

                        override fun onStarted(started: WifiManager.LocalOnlyHotspotReservation) {
                            own = started
                            if (!reservation.compareAndSet(null, started)) {
                                started.close()
                                fail(IllegalStateException("another hotspot reservation is open"))
                                return
                            }
                            deliver(started)
                        }

                        override fun onStopped() {
                            // Only this start's reservation: a late callback from an older one must not end a newer one.
                            val mine = own ?: return
                            if (reservation.compareAndSet(mine, null)) notifyStopped()
                        }

                        override fun onFailed(reason: Int) = fail(IllegalStateException(failureText(reason)))
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
        try {
            val configuration = started.softApConfiguration
            val cidr = findHotspotCidr(defaultInterface) ?: error("the hotspot started but its address didn't appear")
            return HotspotInfo(
                ssid = configuration.wifiSsid?.toString()?.removeSurrounding("\"") ?: "",
                passphrase = configuration.passphrase.orEmpty(),
                qrSecurity =
                    when (configuration.securityType) {
                        SoftApConfiguration.SECURITY_TYPE_OPEN -> "nopass"
                        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "SAE"
                        else -> "WPA"
                    },
                interfaceCidr = cidr,
            )
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    override fun stop() {
        reservation.getAndSet(null)?.close()
    }

    /** Closes [value] and forgets it, if it is still the open reservation. */
    private fun close(value: WifiManager.LocalOnlyHotspotReservation) {
        reservation.compareAndSet(value, null)
        value.close()
    }

    private fun defaultInterfaceName(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.getLinkProperties(connectivity.activeNetwork)?.interfaceName
    }

    /** The hotspot interface isn't a Network apps can query; find the new private IPv4 Wi-Fi interface. */
    private suspend fun findHotspotCidr(defaultInterface: String?): String? {
        repeat(ADDRESS_POLL_COUNT_MAX) {
            val candidate =
                NetworkInterface
                    .getNetworkInterfaces()
                    ?.toList()
                    .orEmpty()
                    .filter { it.isUp && !it.isLoopback && it.name != defaultInterface && HOTSPOT_INTERFACE_NAME.matches(it.name) }
                    .firstNotNullOfOrNull { networkInterface ->
                        networkInterface.interfaceAddresses.firstOrNull { (it.address as? Inet4Address)?.isSiteLocalAddress == true }
                    }
            if (candidate != null) return "${candidate.address.hostAddress}/${candidate.networkPrefixLength}"
            delay(ADDRESS_POLL_INTERVAL_MS)
        }
        return null
    }

    private fun failureText(reason: Int): String =
        when (reason) {
            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no Wi-Fi channel available"
            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "tethering is disallowed on this phone"
            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Wi-Fi is in an incompatible mode (is the regular hotspot on?)"
            else -> "the hotspot failed to start (error $reason)"
        }

    private companion object {
        val HOTSPOT_INTERFACE_NAME = Regex("""(wlan|swlan|ap|softap)\d+""")
        const val ADDRESS_POLL_COUNT_MAX = 50
        const val ADDRESS_POLL_INTERVAL_MS = 100L
    }
}
