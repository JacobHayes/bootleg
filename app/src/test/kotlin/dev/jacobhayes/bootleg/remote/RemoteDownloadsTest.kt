package dev.jacobhayes.bootleg.remote

import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import dev.jacobhayes.bootleg.catalog.CatalogRepository
import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.HttpResponse
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.remote.DownloadOutcome
import dev.jacobhayes.bootleg.core.remote.RemoteDownloadStatus
import dev.jacobhayes.bootleg.core.remote.RemoteLimits
import dev.jacobhayes.bootleg.download.BackgroundJobScheduling
import dev.jacobhayes.bootleg.download.DownloadEngine
import dev.jacobhayes.bootleg.download.DownloadLimits
import dev.jacobhayes.bootleg.download.DownloadNotifications
import dev.jacobhayes.bootleg.download.DownloadRecord
import dev.jacobhayes.bootleg.download.DownloadScheduler
import dev.jacobhayes.bootleg.download.DownloadStatus
import dev.jacobhayes.bootleg.download.DownloadStore
import dev.jacobhayes.bootleg.download.Downloads
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** The remote's download port over the app's real Downloads: admission, background scheduling, storage, cancel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RemoteDownloadsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val jobScheduler = shadowOf(context.getSystemService(JobScheduler::class.java))
    private val broadcasts = ArrayList<String>()

    /** What root's privileged broadcast does: when it arrives, Android lets the job through. */
    private var broadcastAllowsJob = true
    private var lastToken = ""

    /** Runs inside the fake root broadcast, before the receiver: other things happening meanwhile. */
    private var duringBroadcast: () -> Unit = {}

    private lateinit var storeDir: File
    private lateinit var store: DownloadStore
    private lateinit var downloads: Downloads
    private var freeBytes = Long.MAX_VALUE

    private fun setUp() {
        storeDir = temp.newFolder("store")
        store = DownloadStore(temp.newFile("downloads.json"))
        val engine = DownloadEngine(store, storeDir, OkHttpClient(), NoHttp, freeBytes = { freeBytes }, onImageAdded = {})
        lateinit var created: Downloads
        val background =
            BackgroundJobScheduling({ fileName, token ->
                broadcasts += fileName
                duringBroadcast()
                lastToken = token
                if (broadcastAllowsJob) allowAllJobs()
                created.onBackgroundScheduleBroadcast(fileName, "0".repeat(32))
                created.onBackgroundScheduleBroadcast(fileName, token)
                // Claimed once: a repeat schedules nothing more.
                created.onBackgroundScheduleBroadcast(fileName, token)
            })
        created =
            Downloads(
                store,
                engine,
                DownloadNotifications(context),
                DownloadScheduler(context),
                wifiOnly = { true },
                backgroundScheduling = background,
            )
        downloads = created
    }

    private fun port(): RemoteDownloads {
        val catalog = CatalogRepository(emptyList(), NoHttp, temp.newFile(), MainScope())
        return RemoteDownloads(catalog, downloads, storeDir, freeBytes = { freeBytes })
    }

    private fun release(
        fileName: String,
        sizeBytes: Long? = 1_000_000,
    ) = Release(
        "d",
        "1",
        Arch.X86_64,
        url = "https://example.org/$fileName",
        sha256 = "a".repeat(64),
        sizeBytes = sizeBytes,
        fileName = fileName,
    )

    private fun jobIdFor(fileName: String) = DownloadScheduler.jobId(store[fileName]!!)

    /** Robolectric can only add jobs that fail; this is Android letting jobs through again. */
    private fun allowAllJobs() {
        val field = jobScheduler.javaClass.getDeclaredField("jobsToFail").apply { isAccessible = true }
        (field.get(jobScheduler) as MutableSet<*>).clear()
    }

    @Test
    fun `a start schedules the same user-initiated job the Catalog would`() =
        runBlocking<Unit> {
            setUp()
            assertEquals(DownloadOutcome.Started, port().start(release("a.iso"), "Distro"))
            val record = store["a.iso"]!!
            assertEquals(DownloadStatus.QUEUED, record.status)
            val job = jobScheduler.getPendingJob(DownloadScheduler.jobId(record))!!
            assertTrue(job.isUserInitiated)
            assertTrue(broadcasts.isEmpty())
            assertEquals(DownloadOutcome.AlreadyActive, port().start(release("a.iso"), "Distro"))
            File(storeDir, "b.iso").writeText("done")
            assertEquals(DownloadOutcome.AlreadyDownloaded, port().start(release("b.iso"), "Distro"))
        }

    @Test
    fun `refused in the background, the job is scheduled inside root's broadcast, or the download fails`() =
        runBlocking<Unit> {
            setUp()
            // The store hands out id slot 0 first, so the first record's job id is known up front.
            jobScheduler.failOnJob(DownloadScheduler.jobId(DownloadRecord("x", "x", "u", createdAtMs = 0, idSlot = 0)))
            assertEquals(DownloadOutcome.Started, port().start(release("a.iso"), "Distro"))
            assertEquals(listOf("a.iso"), broadcasts)
            assertTrue(jobScheduler.getPendingJob(jobIdFor("a.iso"))!!.isUserInitiated)

            broadcastAllowsJob = false
            jobScheduler.failOnJob(DownloadScheduler.jobId(DownloadRecord("x", "x", "u", createdAtMs = 0, idSlot = 1)))
            val refused = port().start(release("b.iso"), "Distro")
            assertTrue(refused is DownloadOutcome.Rejected)
            assertEquals(DownloadStatus.FAILED, store["b.iso"]!!.status)
            // A broadcast nobody waits for schedules nothing.
            broadcastAllowsJob = true
            allowAllJobs()
            downloads.onBackgroundScheduleBroadcast("b.iso", lastToken)
            assertEquals(null, jobScheduler.getPendingJob(jobIdFor("b.iso")))
        }

    @Test
    fun `the remote's active bound and the record bound are checked with admission, after already-active`() =
        runBlocking<Unit> {
            setUp()
            repeat(RemoteLimits.DOWNLOAD_ACTIVE_COUNT_MAX) { assertEquals(DownloadOutcome.Started, port().start(release("r$it.iso"), "D")) }
            assertTrue(port().start(release("one-more.iso"), "D") is DownloadOutcome.TooMany)
            assertEquals(DownloadOutcome.AlreadyActive, port().start(release("r0.iso"), "D"))
            // The phone's own starts aren't bound by it.
            assertEquals(Downloads.EnqueueResult.Started, downloads.enqueue(release("phone.iso"), "D"))
            for (index in 0 until RemoteLimits.DOWNLOAD_ACTIVE_COUNT_MAX) downloads.pause("r$index.iso")
            downloads.pause("phone.iso")
            repeat(DownloadLimits.RECORDS_MAX - store.records.value.size) {
                store.put(DownloadRecord("old$it.iso", "old", "u", status = DownloadStatus.COMPLETED, createdAtMs = 0))
            }
            assertTrue(port().start(release("new.iso"), "D") is DownloadOutcome.TooMany)
            // Resuming a paused one needs no new record.
            assertEquals(DownloadOutcome.Started, port().start(release("r1.iso"), "D"))
        }

    @Test
    fun `a late refusal fails only its own attempt, not a restart of the same download`() =
        runBlocking<Unit> {
            setUp()
            broadcastAllowsJob = false
            jobScheduler.failOnJob(DownloadScheduler.jobId(DownloadRecord("x", "x", "u", createdAtMs = 0, idSlot = 0)))
            // While the remote's attempt waits on root, the phone cancels it and starts it again.
            duringBroadcast = {
                downloads.cancel("a.iso")
                allowAllJobs()
                assertEquals(Downloads.EnqueueResult.Started, downloads.enqueue(release("a.iso"), "D"))
            }
            assertTrue(port().start(release("a.iso"), "D") is DownloadOutcome.Rejected)
            assertEquals(DownloadStatus.QUEUED, store["a.iso"]!!.status)
        }

    @Test
    fun `root's broadcast names the receiver and refuses names and tokens that aren't safe`() {
        val token = "0123456789abcdef0123456789abcdef"
        val argv = BackgroundJobScheduling.broadcastArgv("dev.jacobhayes.bootleg", "netboot.xyz.iso", token)
        assertEquals(
            listOf(
                "am",
                "broadcast",
                "--user",
                "0",
                "--allow-background-activity-starts",
                "-n",
                "dev.jacobhayes.bootleg/dev.jacobhayes.bootleg.download.ScheduleDownloadReceiver",
                "--es",
                "file_name",
                "netboot.xyz.iso",
                "--es",
                "token",
                token,
            ),
            argv,
        )
        for ((name, badToken) in listOf("-n" to token, "../x.iso" to token, "a.iso.partial" to token, "a.iso" to "XYZ", "a.iso" to "")) {
            assertTrue("$name / $badToken", runCatching { BackgroundJobScheduling.broadcastArgv("p", name, badToken) }.isFailure)
        }
    }

    @Test
    fun `a queued download whose job is gone becomes paused at start-up, a scheduled one stays queued`() =
        runBlocking<Unit> {
            setUp()
            port().start(release("scheduled.iso"), "D")
            store.put(DownloadRecord("orphan.iso", "orphan", "u", status = DownloadStatus.QUEUED, createdAtMs = 0))
            // A new process: the same store file, the same JobScheduler.
            val reloaded = DownloadStore(temp.root.resolve("downloads.json"))
            val engine = DownloadEngine(reloaded, storeDir, OkHttpClient(), NoHttp, freeBytes = { freeBytes }, onImageAdded = {})
            Downloads(reloaded, engine, DownloadNotifications(context), DownloadScheduler(context), wifiOnly = { true })
            assertEquals(DownloadStatus.PAUSED, reloaded["orphan.iso"]!!.status)
            assertEquals(DownloadStatus.QUEUED, reloaded["scheduled.iso"]!!.status)
        }

    @Test
    fun `storage counts what is left to fetch plus the margin`() =
        runBlocking<Unit> {
            setUp()
            val size = 3_000_000_000L
            freeBytes = size + DownloadLimits.FREE_SPACE_MARGIN_BYTES - 1
            assertTrue(port().start(release("big.iso", size), "D") is DownloadOutcome.StorageLow)
            assertEquals(null, store["big.iso"])
            File(storeDir, "big.iso.partial").writeBytes(ByteArray(10))
            assertEquals(DownloadOutcome.Started, port().start(release("big.iso", size), "D"))
            freeBytes = DownloadLimits.FREE_SPACE_MARGIN_BYTES - 1
            assertTrue(port().start(release("unknown-size.iso", sizeBytes = null), "D") is DownloadOutcome.StorageLow)
        }

    @Test
    fun `cancel removes an unfinished download and its partial, and leaves finished ones`() =
        runBlocking<Unit> {
            setUp()
            val port = port()
            assertEquals(DownloadOutcome.Cancelled(false), port.cancel("nothing.iso"))
            port.start(release("a.iso"), "D")
            File(storeDir, "a.iso.partial").writeText("partial")
            assertEquals(DownloadOutcome.Cancelled(true), port.cancel("a.iso"))
            assertEquals(null, store["a.iso"])
            assertFalse(File(storeDir, "a.iso.partial").exists())
            store.put(DownloadRecord("done.iso", "done", "u", status = DownloadStatus.COMPLETED, createdAtMs = 0))
            assertEquals(DownloadOutcome.Cancelled(false), port.cancel("done.iso"))
            assertEquals(DownloadStatus.COMPLETED, store["done.iso"]!!.status)
            val listed = port.downloads.first()
            assertEquals(RemoteDownloadStatus.COMPLETED, listed.single().status)
        }

    private object NoHttp : Http {
        override suspend fun get(url: String): HttpResponse = throw IOException("offline")

        override suspend fun head(url: String): HttpResponse = throw IOException("offline")
    }
}
