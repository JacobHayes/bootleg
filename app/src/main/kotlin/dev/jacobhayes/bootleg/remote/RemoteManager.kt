package dev.jacobhayes.bootleg.remote

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.SystemClock
import android.util.Log
import bootlegremote.Bootlegremote
import dev.jacobhayes.bootleg.Prefs
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.remote.AuthStatus
import dev.jacobhayes.bootleg.core.remote.CatalogSnapshot
import dev.jacobhayes.bootleg.core.remote.DownloadOutcome
import dev.jacobhayes.bootleg.core.remote.DownloadPort
import dev.jacobhayes.bootleg.core.remote.LeaseHolder
import dev.jacobhayes.bootleg.core.remote.Principal
import dev.jacobhayes.bootleg.core.remote.RemoteActor
import dev.jacobhayes.bootleg.core.remote.RemoteClock
import dev.jacobhayes.bootleg.core.remote.RemoteDownload
import dev.jacobhayes.bootleg.core.remote.RemoteStatus
import dev.jacobhayes.bootleg.core.remote.SecureRandomSource
import dev.jacobhayes.bootleg.hid.HidController
import dev.jacobhayes.bootleg.service.BootlegBackendImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address

sealed interface HotspotState {
    data object Off : HotspotState

    data object Starting : HotspotState

    data class On(
        val info: HotspotInfo,
    ) : HotspotState

    data class Failed(
        val reason: String,
    ) : HotspotState
}

/** Everything the Remote screen shows. */
data class RemoteUiState(
    /** The user's switch (persisted). */
    val enabled: Boolean = false,
    /** The node and controller are up in this process. */
    val running: Boolean = false,
    val hostname: String = RemoteManager.HOSTNAME,
    val tailnet: TailnetStatus = TailnetStatus(),
    val status: RemoteStatus = RemoteStatus(),
    val hotspot: HotspotState = HotspotState.Off,
    val localNetworkGranted: Boolean = true,
    val authKeyPath: String = "",
    val keepAwake: Boolean = false,
    val error: String? = null,
) {
    val pendingCount: Int get() = status.pending.size
}

/** What the Remote screen can ask for. */
interface RemoteActions {
    fun setEnabled(enabled: Boolean)

    fun approve(
        nodeId: String,
        allowRw: Boolean,
    )

    fun deny(nodeId: String)

    fun revoke(principal: Principal)

    fun setAllowRw(
        principal: Principal,
        allowRw: Boolean,
    )

    fun startHotspot()

    fun stopHotspot()

    fun enablePairing()

    fun disablePairing()

    fun resetPairingLockout()

    /** Re-reads runtime permission state (the screen calls this on resume). */
    fun refreshPermissions()
}

/** No catalog behind the remote yet: an empty catalog, no downloads, and every start or cancel refused. */
private object NoRemoteDownloads : DownloadPort {
    override val catalog: Flow<CatalogSnapshot> = flowOf(CatalogSnapshot())
    override val downloads: Flow<List<RemoteDownload>> = flowOf(emptyList())

    override suspend fun start(
        release: Release,
        distroName: String,
    ): DownloadOutcome = DownloadOutcome.Rejected("catalog downloads are not available")

    override suspend fun cancel(fileName: String): DownloadOutcome = DownloadOutcome.Rejected("catalog downloads are not available")

    override fun refreshCatalogIfStale() = Unit
}

/**
 * Owns remote control in the app process: the embedded Tailscale node (Go), the [RemoteActor]
 * (Kotlin, all authority) and the ports between them, keep-awake and the offline hotspot.
 * [RemoteService] keeps the process in the foreground while it runs. UI calls come on the main
 * thread; [startNode]/[stopNode] are serialized.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteManager(
    private val context: Context,
    private val backend: BootlegBackendImpl,
    private val hid: HidController,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) : RemoteActions {
    private class Node(
        val actor: RemoteActor,
        val callbacks: GoCallbacks,
        val actorScope: CoroutineScope,
        val watchers: Job,
        val networkCallback: ConnectivityManager.NetworkCallback?,
    )

    private val directory = File(context.noBackupFilesDir, "remote")
    private val stateDirectory = File(context.noBackupFilesDir, "tailscale")
    private val authKeyFile = File(context.filesDir, AUTH_KEY_FILE_NAME)
    private val notifications = RemoteNotifications(context).also { it.ensureChannel() }
    private val lifecycle = Mutex()
    private var node: Node? = null
    private var localCapture = false
    private var serviceText: Pair<String, String>? = null

    val keepAwake = KeepAwake(context, directory) { stayOn, lowLatency, wakeScreen -> backend.setKeepAwake(stayOn, lowLatency, wakeScreen) }

    private val stateFlow =
        MutableStateFlow(
            RemoteUiState(enabled = prefs.remoteEnabled.value, authKeyPath = authKeyFile.path, localNetworkGranted = localNetworkGranted()),
        )
    val state: StateFlow<RemoteUiState> = stateFlow.asStateFlow()

    // Shares the node's lifecycle lock so a hotspot start can't outlive (or race) the node's stop.
    private val offline =
        OfflineMode(
            lifecycle = lifecycle,
            reservation = Hotspot(context),
            stopListener = { withContext(Dispatchers.IO) { Bootlegremote.stopHotspot() } },
            onState = { hotspot -> stateFlow.update { it.copy(hotspot = hotspot) } },
        )

    val isEnabled: Boolean get() = prefs.remoteEnabled.value

    override fun setEnabled(enabled: Boolean) {
        prefs.setRemoteEnabled(enabled)
        stateFlow.update { it.copy(enabled = enabled, error = null) }
        if (enabled) {
            if (!RemoteService.start(context)) {
                // Only a visible user action may start the foreground service; a background caller
                // (the debug hook) still gets a working node while the process lives.
                scope.launch { startNode() }
            }
        } else {
            RemoteService.stop(context)
            scope.launch { stopNode() }
        }
    }

    override fun approve(
        nodeId: String,
        allowRw: Boolean,
    ) {
        node?.actor?.approve(nodeId, allowRw)
    }

    override fun deny(nodeId: String) {
        node?.actor?.deny(nodeId)
    }

    override fun revoke(principal: Principal) {
        node?.actor?.revoke(principal)
    }

    override fun setAllowRw(
        principal: Principal,
        allowRw: Boolean,
    ) {
        node?.actor?.setAllowRw(principal, allowRw)
    }

    override fun enablePairing() {
        node?.actor?.enablePairing()
    }

    override fun disablePairing() {
        node?.actor?.disablePairing()
    }

    override fun resetPairingLockout() {
        node?.actor?.resetPairingLockout()
    }

    override fun refreshPermissions() {
        stateFlow.update { it.copy(localNetworkGranted = localNetworkGranted()) }
    }

    /** The phone's Control tab is (not) in the foreground: it preempts any remote lease. */
    fun setLocalCapture(active: Boolean) {
        if (active == localCapture) return
        localCapture = active
        val actor = node?.actor ?: return
        if (active) actor.localCaptureStarted() else actor.localCaptureStopped()
    }

    override fun startHotspot() {
        scope.launch {
            val running = node ?: return@launch
            offline.start(
                isCurrent = { node === running },
                onStopped = { scope.launch { onHotspotLost() } },
                startListener = { cidr -> withContext(Dispatchers.IO) { Bootlegremote.startHotspot(cidr, running.callbacks) } },
            )
        }
    }

    override fun stopHotspot() {
        scope.launch { stopHotspotNow() }
    }

    private suspend fun stopHotspotNow() {
        offline.stop()
        node?.actor?.hotspotStopped()
    }

    /** Android stopped the hotspot, whether offline mode was on or still starting. */
    private suspend fun onHotspotLost() {
        if (offline.hotspotLost()) node?.actor?.hotspotStopped()
    }

    /** Brings the node and controller up (idempotent). Called by [RemoteService] on the main thread. */
    suspend fun startNode() =
        lifecycle.withLock {
            if (node != null) return@withLock
            hid.keepSessionInBackground = true
            val actorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
            val actor =
                withContext(Dispatchers.IO) {
                    RemoteActor.create(
                        clock = AndroidRemoteClock,
                        random = SecureRandomSource(),
                        transport = GoTransport,
                        device = RemoteDevice(backend, hid),
                        hid = hid.remotePort,
                        approvalStore = FileApprovalStore(directory),
                        pairingStore = FilePairingStore(directory),
                        notifier = { pending -> notifications.showPending(pending) },
                        downloads = NoRemoteDownloads,
                        scope = actorScope,
                        deviceDispatcher = Dispatchers.IO.limitedParallelism(1),
                        downloadDispatcher = Dispatchers.IO.limitedParallelism(1),
                        onFatal = { failure -> scope.launch { onFatal(failure) } },
                    )
                }
            actor.start()
            if (localCapture) actor.localCaptureStarted()
            val callbacks = GoCallbacks(actor) { status -> scope.launch { onTailnetStatus(status) } }
            val authKey =
                authKeyFile
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.trim()
                    .orEmpty()
            val started =
                withContext(Dispatchers.IO) {
                    runCatching {
                        stateDirectory.mkdirs()
                        Bootlegremote.setVerboseLogging(false)
                        Bootlegremote.start(stateDirectory.path, HOSTNAME, authKey, callbacks)
                    }
                }
            if (started.isFailure) {
                val reason = started.exceptionOrNull()?.message ?: "the node failed to start"
                Log.w(TAG, "start failed: $reason")
                actor.shutdown()
                actorScope.cancel()
                hid.keepSessionInBackground = false
                stateFlow.update { it.copy(running = false, error = reason, tailnet = TailnetStatus.parse(Bootlegremote.status())) }
                return@withLock
            }
            val watchers = scope.launch { watch(actor) }
            node = Node(actor, callbacks, actorScope, watchers, registerNetworkCallback())
            stateFlow.update { it.copy(running = true, error = null, localNetworkGranted = localNetworkGranted()) }
            // Status callbacks during start() arrive while node is still null and are dropped, so a
            // Running reached during start would never delete the auth key: read it explicitly.
            onTailnetStatus(TailnetStatus.parse(withContext(Dispatchers.IO) { Bootlegremote.status() }))
            scope.launch { runCatching { backend.refreshImages() } }
        }

    /** Ends every session, stops the node and restores keep-awake (idempotent). */
    suspend fun stopNode() =
        lifecycle.withLock {
            val running = node ?: return@withLock
            node = null
            running.actor.shutdown()
            // Let the controller close its sessions (and release HID) before the listener goes away.
            withTimeoutOrNull(SHUTDOWN_DRAIN_TIMEOUT_MS) { running.actor.status.first { it.sessions.isEmpty() } }
            offline.stopLocked()
            withContext(Dispatchers.IO) { Bootlegremote.stop() }
            running.networkCallback?.let { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
            running.watchers.cancel()
            running.actorScope.cancel()
            keepAwake.setActive(false)
            hid.keepSessionInBackground = false
            notifications.cancelPending()
            serviceText = null
            stateFlow.update {
                it.copy(running = false, keepAwake = false, tailnet = TailnetStatus(), status = RemoteStatus(), hotspot = HotspotState.Off)
            }
        }

    private suspend fun onFatal(failure: Throwable) {
        Log.e(TAG, "remote controller failed closed", failure)
        prefs.setRemoteEnabled(false)
        RemoteService.stop(context)
        stopNode()
        stateFlow.update { it.copy(enabled = false, error = "Remote stopped after an internal error: ${failure.message}") }
    }

    private fun onTailnetStatus(status: TailnetStatus) {
        if (node == null && status.backendState != TailnetStatus.STATE_STOPPED) return
        stateFlow.update { it.copy(tailnet = status) }
        // The key only registers the node; afterwards its identity lives in the state directory.
        if (status.isRunning && authKeyFile.delete()) Log.i(TAG, "registered; deleted the auth key file")
        updateServiceNotification()
    }

    private suspend fun watch(actor: RemoteActor): Nothing =
        coroutineScope {
            launch {
                actor.status.collect { status ->
                    stateFlow.update { it.copy(status = status) }
                    updateServiceNotification()
                }
            }
            launch {
                actor.status
                    .map { status -> status.sessions.any { it.auth == AuthStatus.APPROVED } }
                    .distinctUntilChanged()
                    .collect { live ->
                        keepAwake.setActive(live)
                        stateFlow.update { it.copy(keepAwake = live) }
                    }
            }
            // Remote input needs the HID devices open even while the app is in the background.
            hid.spec.collect { spec -> if (spec.keyboard || spec.mouse) hid.connect() }
        }

    private fun updateServiceNotification() {
        if (node == null) return
        val current = state.value
        val connected = current.status.sessions.count { it.auth == AuthStatus.APPROVED }
        val title = if (connected > 0) "Remote control: $connected connected" else "Remote control on"
        val text =
            buildList {
                val tailnet = current.tailnet
                when {
                    tailnet.isRunning -> add(tailnet.fqdn.ifEmpty { current.hostname })
                    tailnet.needsLogin -> add("Tailscale sign-in needed")
                    else -> add("Tailscale ${tailnet.backendState.lowercase()}")
                }
                if (current.status.leaseHolder == LeaseHolder.Local) add("phone has control")
                if (current.pendingCount > 0) add("${current.pendingCount} waiting for approval")
            }.joinToString(" · ")
        if (serviceText == title to text) return
        serviceText = title to text
        notifications.updateService(title, text)
    }

    private fun registerNetworkCallback(): ConnectivityManager.NetworkCallback? {
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
                    Bootlegremote.networkChanged(linkProperties.interfaceName.orEmpty(), gateway.orEmpty())
                }

                override fun onLost(network: Network) = Bootlegremote.networkChanged("", "")
            }
        return runCatching {
            context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
            callback
        }.onFailure { Log.w(TAG, "network callback unavailable", it) }.getOrNull()
    }

    private fun localNetworkGranted(): Boolean =
        context.checkSelfPermission(PERMISSION_ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private object AndroidRemoteClock : RemoteClock {
        override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

        override fun wallMs(): Long = System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "BootlegRemote"

        /** The tailnet name the node registers as. */
        const val HOSTNAME = "bootleg"

        /** Dev/test only: a Tailscale auth key placed here registers the node, then is deleted. */
        const val AUTH_KEY_FILE_NAME = "ts-authkey"

        /** Android 17: without it every LAN `sendto` fails, so traffic falls back to DERP relays. */
        const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        private const val SHUTDOWN_DRAIN_TIMEOUT_MS = 2_000L
    }
}
