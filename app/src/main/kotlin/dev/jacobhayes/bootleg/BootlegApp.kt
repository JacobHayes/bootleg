package dev.jacobhayes.bootleg

import android.app.Application
import android.content.Context
import android.os.StatFs
import dev.jacobhayes.bootleg.catalog.CatalogRepository
import dev.jacobhayes.bootleg.catalog.OkHttpHttp
import dev.jacobhayes.bootleg.catalog.catalogScrapers
import dev.jacobhayes.bootleg.download.DownloadEngine
import dev.jacobhayes.bootleg.download.DownloadNotifications
import dev.jacobhayes.bootleg.download.DownloadScheduler
import dev.jacobhayes.bootleg.download.DownloadStore
import dev.jacobhayes.bootleg.download.Downloads
import dev.jacobhayes.bootleg.hid.HidController
import dev.jacobhayes.bootleg.remote.KeepAwake
import dev.jacobhayes.bootleg.remote.RemoteManager
import dev.jacobhayes.bootleg.service.BootlegBackendImpl
import dev.jacobhayes.bootleg.storage.FileTasks
import dev.jacobhayes.bootleg.storage.StoragePaths
import dev.jacobhayes.bootleg.ui.drives.DrivesController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Process-wide singletons (manual DI). */
class BootlegApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // A process that died while a remote session kept the phone awake left settings changed.
        if (KeepAwake.hasLeftover(this)) appScope.launch { runCatching { remote.keepAwake.restoreLeftover() } }
    }

    val systemBackend: BootlegBackendImpl by lazy { BootlegBackendImpl(this, appScope) }

    /** The one place the UI gets the system side from. */
    val backend: BootlegBackend get() = systemBackend

    val prefs by lazy { Prefs(this) }
    private val httpClient by lazy { OkHttpHttp.baseClient() }
    private val catalogHttp by lazy { OkHttpHttp(httpClient) }
    private val storeDir = File(StoragePaths.STORE_FUSE_DIR)

    val catalog by lazy { CatalogRepository(catalogScrapers(), catalogHttp, File(filesDir, "catalog-cache.json")) }
    val drives by lazy { DrivesController(backend, appScope) }
    val hid by lazy { HidController(backend, appScope).also { registerActivityLifecycleCallbacks(it.backgroundWatcher) } }
    val remote by lazy { RemoteManager(this, systemBackend, hid, prefs, appScope) }
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
        Downloads(store, engine, notifications, DownloadScheduler(this), wifiOnly = { prefs.wifiOnlyDownloads.value })
    }

    companion object {
        const val PREFERENCES_NAME = "bootleg"
        const val PREFERENCE_RESTORE_AFTER_REBOOT = "restore_after_reboot"

        fun from(context: Context): BootlegApp = context.applicationContext as BootlegApp
    }
}
