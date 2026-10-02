package dev.jacobhayes.bootleg.remotespike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.IBinder
import android.util.Log
import bootlegremote.Bootlegremote
import bootlegremote.Callbacks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/** Foreground service that owns the embedded Tailscale node for the lifetime of the spike. */
class SpikeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val verbose = intent?.getBooleanExtra(EXTRA_VERBOSE, false) ?: false
        worker.execute { startNode(verbose) }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
        worker.execute { Bootlegremote.stop() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun startNode(verbose: Boolean) {
        val stateDirectory = File(noBackupFilesDir, "tailscale").apply { mkdirs() }
        val authKeyFile = File(filesDir, AUTH_KEY_FILE_NAME)
        val authKey = if (authKeyFile.isFile) authKeyFile.readText().trim() else ""
        val callbacks =
            object : Callbacks {
                override fun onStatus(statusJson: String) {
                    lastStatusJson = statusJson
                    Log.i(TAG, "status $statusJson")
                    // The key is only needed to register; once the node is Running its identity
                    // lives in the tsnet state directory.
                    if (JSONObject(statusJson).optString("backendState") == "Running" && authKeyFile.delete()) {
                        Log.i(TAG, "registered; deleted auth key file")
                    }
                }

                override fun interfaces(): String = interfacesJson()

                // The spike authorizes nobody: sessions stay pending and get no control.
                override fun open(
                    sessionID: Long,
                    connJSON: String,
                ) = Unit

                override fun command(
                    sessionID: Long,
                    messageJSON: String,
                ) = Unit

                override fun hid(
                    sessionID: Long,
                    frame: ByteArray,
                ): Int = HID_QUEUE_FULL

                override fun closed(
                    sessionID: Long,
                    reason: String,
                ) = Unit
            }
        try {
            Bootlegremote.setVerboseLogging(verbose)
            Bootlegremote.start(stateDirectory.path, HOSTNAME, authKey, callbacks)
            registerNetworkCallback()
        } catch (exception: Exception) {
            Log.e(TAG, "start failed: ${exception.message}")
            lastStatusJson = Bootlegremote.status()
        }
    }

    private fun registerNetworkCallback() {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    val gateway =
                        linkProperties.routes
                            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                            ?.gateway
                            ?.hostAddress
                    Log.i(TAG, "default network ${linkProperties.interfaceName} gateway $gateway")
                    Bootlegremote.networkChanged(linkProperties.interfaceName ?: "", gateway ?: "")
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "default network lost")
                    Bootlegremote.networkChanged("", "")
                }
            }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote spike", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle("Bootleg remote spike")
            .setContentText("Tailscale node $HOSTNAME")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_STOP = "stop"
        const val EXTRA_VERBOSE = "verbose"
        const val AUTH_KEY_FILE_NAME = "ts-authkey"
        private const val HOSTNAME = "bootleg-pixel6"
        private const val TAG = "BootlegRemoteSpike"
        private const val CHANNEL_ID = "remote-spike"
        private const val NOTIFICATION_ID = 1
        private const val HID_QUEUE_FULL = 1

        @Volatile
        var lastStatusJson: String = "{}"
            private set

        /** Same JSON shape tailscale-android's App.getInterfacesAsJson produces. */
        fun interfacesJson(): String {
            val result = JSONArray()
            for (networkInterface in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                try {
                    val addresses = JSONArray()
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        val host = interfaceAddress.address?.hostAddress ?: continue
                        addresses.put(
                            JSONObject().put("ip", host).put("prefixLen", interfaceAddress.networkPrefixLength.toInt()),
                        )
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
                } catch (exception: java.net.SocketException) {
                    // An interface can vanish mid-enumeration; skip it.
                    Log.w(TAG, "skipping interface ${networkInterface.name}: ${exception.message}")
                }
            }
            return result.toString()
        }
    }
}
