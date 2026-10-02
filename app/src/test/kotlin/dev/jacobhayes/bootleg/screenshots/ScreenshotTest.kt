package dev.jacobhayes.bootleg.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.jacobhayes.bootleg.FakeBootlegBackend
import dev.jacobhayes.bootleg.Prefs
import dev.jacobhayes.bootleg.catalog.CatalogRepository
import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.HttpResponse
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import dev.jacobhayes.bootleg.download.DownloadEngine
import dev.jacobhayes.bootleg.download.DownloadNotifications
import dev.jacobhayes.bootleg.download.DownloadRecord
import dev.jacobhayes.bootleg.download.DownloadScheduler
import dev.jacobhayes.bootleg.download.DownloadStatus
import dev.jacobhayes.bootleg.download.DownloadStore
import dev.jacobhayes.bootleg.download.Downloads
import dev.jacobhayes.bootleg.download.VerifyState
import dev.jacobhayes.bootleg.hid.HidController
import dev.jacobhayes.bootleg.storage.FileTasks
import dev.jacobhayes.bootleg.storage.StoragePaths
import dev.jacobhayes.bootleg.ui.catalog.CatalogScreen
import dev.jacobhayes.bootleg.ui.control.ControlScreen
import dev.jacobhayes.bootleg.ui.downloads.DownloadsScreen
import dev.jacobhayes.bootleg.ui.drives.DrivesController
import dev.jacobhayes.bootleg.ui.drives.DrivesScreen
import dev.jacobhayes.bootleg.ui.library.BlankImageDialog
import dev.jacobhayes.bootleg.ui.library.LibraryScreen
import dev.jacobhayes.bootleg.ui.settings.SettingsScreen
import dev.jacobhayes.bootleg.ui.theme.BootlegTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.IOException

/**
 * Golden screenshots of the main screens against fakes, light and dark, so UI changes can be
 * reviewed without unlocking the phone. `mise run screenshots:record` re-records them; `mise run check` verifies.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-hdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun drives() {
        val backend = FakeBootlegBackend(initialImages = FakeBootlegBackend.demoImages, initialJournal = drivesJournal)
        val controller = DrivesController(backend, MainScope())
        capture("drives") { DrivesScreen(backend, controller, onBrowseFile = {}) }
    }

    @Test
    fun drivesEmpty() {
        val backend = FakeBootlegBackend()
        capture("drives_empty") { DrivesScreen(backend, DrivesController(backend, MainScope()), onBrowseFile = {}) }
    }

    @Test
    fun library() {
        ShadowEnvironment.addExternalDir(temp.newFolder("sdcard").path)
        val backend = FakeBootlegBackend(initialImages = FakeBootlegBackend.demoImages, initialJournal = drivesJournal)
        val fileTasks = FileTasks(context.contentResolver, MainScope(), temp.newFolder("store")) {}
        capture("library") {
            LibraryScreen(backend, DrivesController(backend, MainScope()), fileTasks, newDownloads(), onExposed = {})
        }
    }

    @Test
    fun catalog() {
        val catalog = CatalogRepository(listOf(SampleScraper, FailingScraper), NoHttp, temp.newFile("catalog.json"))
        runBlocking { catalog.refresh() }
        capture("catalog") { CatalogScreen(catalog, newDownloads(), onShowDownloads = {}) }
    }

    @Test
    fun downloadsScreen() {
        val downloads = newDownloads()
        sampleDownloads.forEach(downloads.store::put)
        capture("downloads") { DownloadsScreen(downloads, wifiOnly = true) }
    }

    @Test
    fun controlOff() {
        val backend = FakeBootlegBackend()
        capture("control_off") { ControlScreen(backend, HidController(backend, MainScope())) }
    }

    @Test
    fun control() {
        val backend = FakeBootlegBackend(initialJournal = Journal(desired = Desired(hid = HidSpec(keyboard = true, mouse = true))))
        capture("control") { ControlScreen(backend, HidController(backend, MainScope())) }
    }

    @Test
    fun settings() {
        capture("settings") { SettingsScreen(Prefs(context)) }
    }

    @Test
    fun blankImageDialog() {
        capture("blank_image_dialog") {
            BlankImageDialog(
                freeBytes = { 42_000_000_000 },
                existingNames = setOf("scratch-fat32.img"),
                onCreate = {},
                onDismiss = {},
            )
        }
    }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            captureRoboImage("src/test/screenshots/${name}_${if (dark) "dark" else "light"}.png") {
                BootlegTheme(darkTheme = dark) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
    }

    private fun newDownloads(): Downloads {
        val store = DownloadStore(temp.newFile())
        val engine =
            DownloadEngine(
                store = store,
                storeDir = temp.newFolder(),
                baseClient = OkHttpClient(),
                sumsHttp = NoHttp,
                freeBytes = { Long.MAX_VALUE },
                onImageAdded = {},
            )
        return Downloads(store, engine, DownloadNotifications(context), DownloadScheduler(context), wifiOnly = { true })
    }

    private companion object {
        val store = StoragePaths.STORE_MEDIA_DIR
        val drivesJournal =
            Journal(
                desired =
                    Desired(
                        listOf(
                            SlotSpec(0, "$store/ubuntu-26.04-desktop-amd64.iso", LunMode.CDROM),
                            SlotSpec(1, "$store/debian-13.2.0-amd64-netinst.iso", LunMode.CDROM),
                            SlotSpec(2, "$store/scratch-fat32.img", LunMode.DISK_RW),
                            SlotSpec(3, "$store/memtest86plus-7.20.iso", LunMode.DISK_RO),
                        ),
                    ),
                slotIntents =
                    mapOf(
                        0 to SlotIntent.EXPORTED,
                        1 to SlotIntent.HOST_EJECTED,
                        2 to SlotIntent.EXPORTED,
                        3 to SlotIntent.FAILED,
                    ),
                slotFailures = mapOf(3 to "lun.3/file: No such file or directory"),
                udc = "11110000.dwc3",
            )

        val sampleDownloads =
            listOf(
                DownloadRecord(
                    fileName = "ubuntu-26.04-desktop-amd64.iso",
                    title = "Ubuntu 26.04 LTS",
                    url = "https://releases.ubuntu.com/26.04/ubuntu-26.04-desktop-amd64.iso",
                    totalBytes = 6_270_000_000,
                    downloadedBytes = 2_400_000_000,
                    status = DownloadStatus.RUNNING,
                    createdAtMs = 4,
                ),
                DownloadRecord(
                    fileName = "Fedora-Workstation-Live-43-1.6.x86_64.iso",
                    title = "Fedora 43 Workstation",
                    url = "https://download.fedoraproject.org/x.iso",
                    totalBytes = 2_400_000_000,
                    downloadedBytes = 800_000_000,
                    status = DownloadStatus.PAUSED,
                    createdAtMs = 3,
                ),
                DownloadRecord(
                    fileName = "debian-13.2.0-amd64-netinst.iso",
                    title = "Debian 13.2 netinst",
                    url = "https://cdimage.debian.org/x.iso",
                    totalBytes = 754_974_720,
                    downloadedBytes = 754_974_720,
                    status = DownloadStatus.COMPLETED,
                    verify = VerifyState.VERIFIED,
                    createdAtMs = 2,
                ),
                DownloadRecord(
                    fileName = "archlinux-2026.10.01-x86_64.iso",
                    title = "Arch Linux 2026.10.01",
                    url = "https://geo.mirror.pkgbuild.com/x.iso",
                    status = DownloadStatus.FAILED,
                    verify = VerifyState.MISMATCH,
                    error = "Checksum mismatch",
                    createdAtMs = 1,
                ),
            )
    }

    private object NoHttp : Http {
        override suspend fun get(url: String): HttpResponse = throw IOException("offline")

        override suspend fun head(url: String): HttpResponse = throw IOException("offline")
    }

    private object SampleScraper : Scraper {
        private val ubuntu = Distro("ubuntu", "Ubuntu", Category.DESKTOP, "https://ubuntu.com")
        private val fedora = Distro("fedora", "Fedora Workstation", Category.DESKTOP, "https://fedoraproject.org")
        override val distros = listOf(ubuntu, fedora)

        override suspend fun releases(http: Http): List<Release> =
            listOf(
                release(ubuntu, "26.04 LTS", Arch.X86_64, "", "ubuntu-26.04-desktop-amd64.iso", 6_270_000_000, "2031-05-31"),
                release(ubuntu, "25.10", Arch.X86_64, "", "ubuntu-25.10-desktop-amd64.iso", 5_900_000_000, "2026-07-31"),
                release(ubuntu, "26.04 LTS", Arch.ARM64, "", "ubuntu-26.04-desktop-arm64.iso", 6_100_000_000, "2031-05-31"),
                release(fedora, "43", Arch.X86_64, "Workstation", "Fedora-Workstation-Live-43-1.6.x86_64.iso", 2_400_000_000, null),
            )

        private fun release(
            distro: Distro,
            version: String,
            arch: Arch,
            variant: String,
            fileName: String,
            sizeBytes: Long,
            supportedUntil: String?,
        ) = Release(
            distroId = distro.id,
            version = version,
            arch = arch,
            variant = variant,
            url = "https://example.org/$fileName",
            sha256 = "0".repeat(64),
            sizeBytes = sizeBytes,
            supportedUntil = supportedUntil,
            fileName = fileName,
        )
    }

    private object FailingScraper : Scraper {
        override val distros = listOf(Distro("manjaro", "Manjaro", Category.DESKTOP, "https://manjaro.org"))

        override suspend fun releases(http: Http): List<Release> = throw IOException("Release index returned HTTP 503")
    }
}
