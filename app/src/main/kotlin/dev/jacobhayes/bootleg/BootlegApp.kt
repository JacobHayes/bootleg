package dev.jacobhayes.bootleg

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.StatFs
import dev.jacobhayes.bootleg.catalog.CatalogRepository
import dev.jacobhayes.bootleg.catalog.OkHttpHttp
import dev.jacobhayes.bootleg.catalog.catalogScrapers
import dev.jacobhayes.bootleg.download.BackgroundJobScheduling
import dev.jacobhayes.bootleg.download.DownloadEngine
import dev.jacobhayes.bootleg.download.DownloadNotifications
import dev.jacobhayes.bootleg.download.DownloadScheduler
import dev.jacobhayes.bootleg.download.DownloadStore
import dev.jacobhayes.bootleg.download.Downloads
import dev.jacobhayes.bootleg.hid.HidController
import dev.jacobhayes.bootleg.remote.KeepAwake
import dev.jacobhayes.bootleg.remote.RemoteDownloads
import dev.jacobhayes.bootleg.remote.RemoteManager
import dev.jacobhayes.bootleg.service.BootlegBackendImpl
import dev.jacobhayes.bootleg.storage.FileTasks
import dev.jacobhayes.bootleg.storage.StoragePaths
import dev.jacobhayes.bootleg.ui.drives.DrivesController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Process-wide singletons (manual DI). */
class BootlegApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // A process that died while a remote session kept the phone awake left settings changed.
        if (KeepAwake.hasLeftover(this)) appScope.launch { runCatching { remote.keepAwake.restoreLeftover() } }
    }

    val systemBackend: BootlegBackendImpl by lazy { BootlegBackendImpl(this, appScope, prefs) }

    /** The one place the UI gets the system side from. */
    val backend: BootlegBackend get() = systemBackend

    val prefs by lazy { Prefs(this) }
    private val httpClient by lazy { OkHttpHttp.baseClient() }
    private val catalogHttp by lazy { OkHttpHttp(httpClient) }
    private val storeDir = File(StoragePaths.STORE_FUSE_DIR)

    val catalog by lazy {
        CatalogRepository(
            catalogScrapers(),
            catalogHttp,
            File(filesDir, "catalog-cache.json"),
            appScope,
            networkAvailable = ::hasUsableNetwork,
        ).also { repository ->
            // Keeps "offline" current and catches up once the network is back (or unblocked for us).
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        appScope.launch { repository.onNetworkChanged() }
                    }

                    override fun onLost(network: Network) {
                        defaultNetworkBlocked = false
                        appScope.launch { repository.onNetworkChanged() }
                    }

                    override fun onBlockedStatusChanged(
                        network: Network,
                        blocked: Boolean,
                    ) {
                        defaultNetworkBlocked = blocked
                        appScope.launch { repository.onNetworkChanged() }
                    }
                },
            )
        }
    }
    val drives by lazy { DrivesController(backend, appScope) }
    val hid by lazy {
        HidController(backend, appScope).also {
            registerActivityLifecycleCallbacks(it.backgroundWatcher)
        }
    }
    val remote by lazy {
        RemoteManager(this, systemBackend, hid, prefs, appScope) {
            RemoteDownloads(catalog, downloads, storeDir, freeBytes = { dir -> StatFs(dir.path).availableBytes })
        }
    }
    val fileTasks by lazy { FileTasks(contentResolver, appScope, storeDir) { backend.refreshImages() } }

    val downloads by lazy {
        val store = DownloadStore(File(filesDir, "downloads.json"))
        val notifications = DownloadNotifications(this).also { it.ensureChannel() }
        val engine =
            DownloadEngine(
                store = store,
                storeDir = storeDir,
                baseClient = httpClient,
                sumsHttp = catalogHttp,
                freeBytes = { dir -> StatFs(dir.path).availableBytes },
                onImageAdded = { backend.refreshImages() },
            )
        Downloads(
            store,
            engine,
            notifications,
            DownloadScheduler(this),
            wifiOnly = { prefs.wifiOnlyDownloads.value },
            backgroundScheduling =
                BackgroundJobScheduling({ fileName, token -> systemBackend.sendScheduleDownloadBroadcast(fileName, token) }),
        ).also { downloads ->
            // The library changed (a delete, a refresh): completed records of images that are gone go too.
            // drop(1) skips the empty placeholder before the first listing.
            appScope.launch {
                backend.images.drop(1).collect { images ->
                    val names = images.map { it.path.substringAfterLast('/') }.toSet()
                    withContext(Dispatchers.IO) { downloads.forgetDeletedImages(names) }
                }
            }
        }
    }

    /** The default network callback's last blocked status for this app (Doze, background limits, data saver). */
    @Volatile private var defaultNetworkBlocked = false

    /**
     * A default network with internet that isn't blocked for this app. activeNetwork is null while
     * networking is blocked; the callback's blocked status covers a block it reports first.
     */
    private fun hasUsableNetwork(): Boolean {
        if (defaultNetworkBlocked) return false
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    companion object {
        const val PREFERENCES_NAME = "bootleg"
        const val PREFERENCE_RESTORE_AFTER_REBOOT = "restore_after_reboot"

        fun from(context: Context): BootlegApp = context.applicationContext as BootlegApp
    }
}
