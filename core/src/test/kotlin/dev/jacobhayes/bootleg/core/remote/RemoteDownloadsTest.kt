package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.num
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Remote catalog downloads: who may do what, fencing, dedup, bounds, and that nothing depends on the lease after admission. */
class RemoteDownloadsTest {
    private val h = RemoteHarness()

    private val rescue = Distro("netboot", "netboot.xyz", Category.RESCUE, "https://netboot.xyz")
    private val desktop = Distro("debian", "Debian", Category.DESKTOP, "https://debian.org")
    private val netboot = release("netboot", "netboot.xyz.iso", Arch.X86_64, sizeBytes = 2_000_000)
    private val netbootArm = release("netboot", "netboot.xyz-arm64.iso", Arch.ARM64)
    private val debian = release("debian", "debian-13-amd64.iso", Arch.X86_64, sizeBytes = 700_000_000)
    private val netbootId = CatalogEntryIds.idFor(netboot.fileName)
    private val debianId = CatalogEntryIds.idFor(debian.fileName)

    private fun release(
        distroId: String,
        fileName: String,
        arch: Arch,
        version: String = "1.0",
        sizeBytes: Long? = null,
    ) = Release(
        distroId,
        version,
        arch,
        url = "https://example.org/$fileName",
        sha256 = "a".repeat(64),
        sizeBytes = sizeBytes,
        fileName = fileName,
    )

    private fun setCatalog(
        refreshing: Boolean = false,
        distros: List<CatalogDistroSnapshot> =
            listOf(
                CatalogDistroSnapshot(desktop, listOf(debian), fetchedAtWallMs = h.clock.wall, error = null),
                CatalogDistroSnapshot(rescue, listOf(netboot, netbootArm), fetchedAtWallMs = h.clock.wall, error = null),
            ),
    ) = h.handle(RemoteEvent.CatalogChanged(CatalogSnapshot(distros, refreshing)))

    private fun download(
        fileName: String,
        status: RemoteDownloadStatus,
        downloadedBytes: Long = 0,
        verified: Boolean = false,
    ) = RemoteDownload(fileName, "title $fileName", status, downloadedBytes, 2_000_000, verified, error = null)

    private fun setDownloads(vararg downloads: RemoteDownload) = h.handle(RemoteEvent.DownloadsChanged(downloads.toList()))

    private fun start(
        name: String,
        entryId: String,
        epoch: Int? = h.epoch(),
        id: String = "start-$entryId",
    ) = h.request(name, "download.start", mapOf("entryId" to str(entryId)), epoch, id = id)

    private fun cancel(
        name: String,
        entryId: String,
        epoch: Int? = h.epoch(),
        id: String = "cancel-$entryId",
    ) = h.request(name, "download.cancel", mapOf("entryId" to str(entryId)), epoch, id = id)

    private fun list(
        name: String,
        body: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ): JsonObject = checkNotNull(h.request(name, "catalog.list", body))

    private fun entries(reply: JsonObject): List<JsonObject> =
        reply.getValue("distros").jsonArray.flatMap { distro ->
            distro.jsonObject
                .getValue("entries")
                .jsonArray
                .map { it.jsonObject }
        }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun finish(outcome: DownloadOutcome) = h.handle(RemoteEvent.DownloadResult(h.downloadCalls.last().opId, outcome))

    @Test
    fun `listing needs an approved session but not the lease, and filters by arch, category and distro`() {
        setCatalog()
        h.open("pending", h.tailnet("unknown"))
        h.hello("pending")
        assertEquals("pending", h.request("pending", "catalog.list")!!.errorCode)
        h.connect("viewer")
        val all = list("viewer")
        assertTrue(all.ok)
        assertEquals(listOf(debian.fileName, netboot.fileName), entries(all).map { it.str("fileName") })
        assertEquals(listOf("x86_64"), entries(all).map { it.str("arch") }.distinct())
        assertEquals(listOf(netbootArm.fileName), entries(list("viewer", mapOf("arch" to str("arm64")))).map { it.str("fileName") })
        assertEquals(listOf(netboot.fileName), entries(list("viewer", mapOf("category" to str("rescue")))).map { it.str("fileName") })
        assertEquals(listOf(debian.fileName), entries(list("viewer", mapOf("distroId" to str("debian")))).map { it.str("fileName") })
        assertEquals("bad_request", list("viewer", mapOf("arch" to str("sparc"))).errorCode)
        assertEquals("bad_request", list("viewer", mapOf("url" to str("https://evil.example"))).errorCode)
        assertFalse(all.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(netbootId, entries(all)[1].str("entryId"))
    }

    @Test
    fun `entry state comes from the library first, then the download record`() {
        setCatalog()
        h.connect("s1")
        assertEquals(listOf("available", "available"), entries(list("s1")).map { it.str("state") })
        setDownloads(download(netboot.fileName, RemoteDownloadStatus.RUNNING, 1_000))
        assertEquals("running", entries(list("s1"))[1].str("state"))
        val path = "/data/media/0/bootleg/${netboot.fileName}"
        h.setDevice(h.device.copy(images = h.device.images + RemoteHarness.image(path, 2_000_000).copy(verified = true)))
        assertEquals("downloaded", entries(list("s1"))[1].str("state"))
        h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
        val image =
            h.pushes("s1", "state").last().getValue("images").jsonArray.map { it.jsonObject }.single {
                it.str("name") ==
                    netboot.fileName
            }
        assertTrue(image.getValue("verified").jsonPrimitive.boolean)
        assertNull(
            h
                .pushes("s1", "state")
                .last()
                .getValue("images")
                .jsonArray[0]
                .jsonObject["verified"],
        )
    }

    @Test
    fun `a stale-check refresh is asked for at most once per interval, for all sessions together`() {
        setCatalog()
        h.connect("a")
        h.connect("b")
        list("a")
        list("b")
        assertEquals(1, h.catalogRefreshes)
        h.advance(RemoteLimits.CATALOG_REFRESH_INTERVAL_MS_MIN - 1)
        list("a")
        assertEquals(1, h.catalogRefreshes)
        h.advance(1)
        list("b")
        assertEquals(2, h.catalogRefreshes)
        // Not while one runs; the reply says so and a catalog push follows when it ends.
        setCatalog(refreshing = true)
        h.advance(RemoteLimits.CATALOG_REFRESH_INTERVAL_MS_MIN)
        assertTrue(list("a").getValue("refreshing").jsonPrimitive.boolean)
        assertEquals(2, h.catalogRefreshes)
        val pushesBefore = h.pushes("a", "catalog").size
        setCatalog(refreshing = false)
        h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
        val push = h.pushes("a", "catalog").drop(pushesBefore).last()
        assertFalse(push.getValue("refreshing").jsonPrimitive.boolean)
    }

    @Test
    fun `start and cancel need the lease and the current epoch`() {
        setCatalog()
        h.connect("holder")
        h.connect("other")
        val epoch = h.take("holder")
        assertEquals("lease_required", start("other", netbootId, epoch)!!.errorCode)
        assertEquals("stale_epoch", start("holder", netbootId, epoch + 1, id = "s2")!!.errorCode)
        assertEquals("stale_epoch", start("holder", netbootId, epoch = null, id = "s3")!!.errorCode)
        assertEquals("lease_required", cancel("other", netbootId, epoch)!!.errorCode)
        assertTrue(h.downloadCalls.isEmpty())
        assertNull(start("holder", netbootId, epoch, id = "s4"))
        assertEquals(DownloadOp.Start(netboot, "netboot.xyz"), h.downloadCalls.single().op)
    }

    @Test
    fun `a start answers when the phone has enqueued it, maps every outcome, and allows one request at a time`() {
        setCatalog()
        h.connect("s1")
        h.take("s1")
        assertEquals("not_found", start("s1", "0123456789abcdef")!!.errorCode)
        assertNull(start("s1", netbootId, id = "a"))
        assertEquals("busy", start("s1", debianId, id = "b")!!.errorCode)
        assertEquals("busy", cancel("s1", debianId, id = "c")!!.errorCode)
        finish(DownloadOutcome.Started)
        val reply = h.replyFor("s1", "a")!!
        assertTrue(reply.ok)
        assertEquals("started", reply.str("result"))
        assertEquals(netboot.fileName, reply.str("fileName"))
        assertEquals(netbootId, reply.str("entryId"))
        val outcomes =
            mapOf(
                DownloadOutcome.AlreadyActive to "active",
                DownloadOutcome.AlreadyDownloaded to "downloaded",
                DownloadOutcome.TooMany("full") to "too_many_downloads",
                DownloadOutcome.StorageLow("2 GB free") to "storage_low",
                DownloadOutcome.Rejected("refused") to "device",
                DownloadOutcome.TimedOut to "timeout",
                DownloadOutcome.Busy to "busy",
            )
        for ((index, entry) in outcomes.entries.withIndex()) {
            assertNull(start("s1", debianId, id = "o$index"))
            finish(entry.key)
            val answer = h.replyFor("s1", "o$index")!!
            assertEquals(entry.value, if (answer.ok) answer.str("result") else answer.errorCode) { "${entry.key}" }
        }
    }

    @Test
    fun `an already active download answers active without asking the phone, before the capacity check`() {
        setCatalog()
        h.connect("s1")
        h.take("s1")
        setDownloads(
            download(netboot.fileName, RemoteDownloadStatus.RUNNING),
            download("b.iso", RemoteDownloadStatus.QUEUED),
            download("c.iso", RemoteDownloadStatus.VERIFYING),
            download("d.iso", RemoteDownloadStatus.QUEUED),
        )
        assertEquals("active", start("s1", netbootId)!!.str("result"))
        assertEquals("too_many_downloads", start("s1", debianId)!!.errorCode)
        // Already in the library answers downloaded, whatever the capacity.
        val path = "/data/media/0/bootleg/${debian.fileName}"
        h.setDevice(h.device.copy(images = h.device.images + RemoteHarness.image(path, 700_000_000)))
        assertEquals("downloaded", start("s1", debianId, id = "in-library")!!.str("result"))
        h.setDevice(h.device.copy(images = h.device.images.filterNot { it.path == path }))
        assertTrue(h.downloadCalls.isEmpty())
        // Paused and failed downloads don't count; starting one resumes it through the phone's own path.
        setDownloads(
            download("b.iso", RemoteDownloadStatus.PAUSED),
            download("c.iso", RemoteDownloadStatus.FAILED),
            download("d.iso", RemoteDownloadStatus.QUEUED),
            download("e.iso", RemoteDownloadStatus.RUNNING),
            download(debian.fileName, RemoteDownloadStatus.PAUSED),
        )
        assertNull(start("s1", debianId, id = "resume"))
        assertEquals(DownloadOp.Start(debian, "Debian"), h.downloadCalls.single().op)
    }

    @Test
    fun `cancel stops a known download and answers false for nothing to cancel`() {
        setCatalog()
        h.connect("s1")
        h.take("s1")
        assertFalse(cancel("s1", "0123456789abcdef")!!.getValue("cancelled").jsonPrimitive.boolean)
        setDownloads(download(netboot.fileName, RemoteDownloadStatus.COMPLETED, verified = true))
        assertFalse(cancel("s1", netbootId, id = "done")!!.getValue("cancelled").jsonPrimitive.boolean)
        assertTrue(h.downloadCalls.isEmpty())
        // A download the phone started that the catalog no longer lists can still be cancelled.
        setDownloads(download("gone-from-catalog.iso", RemoteDownloadStatus.PAUSED))
        assertNull(cancel("s1", CatalogEntryIds.idFor("gone-from-catalog.iso")))
        assertEquals(DownloadOp.Cancel("gone-from-catalog.iso"), h.downloadCalls.single().op)
        finish(DownloadOutcome.Cancelled(true))
        assertTrue(
            h
                .replyFor("s1", "cancel-${CatalogEntryIds.idFor("gone-from-catalog.iso")}")!!
                .getValue("cancelled")
                .jsonPrimitive.boolean,
        )
        // Not in this snapshot yet (a start that just finished): the catalog names it.
        setDownloads()
        assertNull(cancel("s1", netbootId, id = "fresh"))
        assertEquals(DownloadOp.Cancel(netboot.fileName), h.downloadCalls.last().op)
    }

    @Test
    fun `a repeated request id is answered once, while in flight and after, and never runs twice`() {
        setCatalog()
        h.connect("s1")
        h.take("s1")
        assertNull(start("s1", netbootId, id = "dup"))
        // Many other requests meanwhile must not evict the pending id from the dedup cache.
        repeat(RemoteLimits.REQUEST_ID_CACHE_SIZE + 5) { h.request("s1", "state.get", id = "g$it") }
        assertNull(start("s1", netbootId, id = "dup"))
        finish(DownloadOutcome.Started)
        assertEquals(1, h.downloadCalls.size)
        val replies = h.sent.filter { it.first == "s1" && it.second.contains("\"id\":\"dup\"") }
        assertEquals(1, replies.size)
        val again = start("s1", netbootId, id = "dup")!!
        assertEquals("started", again.str("result"))
        assertEquals(1, h.downloadCalls.size)
    }

    @Test
    fun `revoke, takeover or disconnect during a start leave the download alone and the reply goes nowhere`() {
        setCatalog()
        h.connect("a", nodeId = "na")
        h.connect("b", nodeId = "nb")
        val epoch = h.take("a")
        assertNull(start("a", netbootId, epoch))
        h.handle(RemoteEvent.Revoke(Principal.Node("na")))
        assertTrue(CloseReason.REVOKED in h.closeReasons("a"))
        assertNull(h.controller.leaseView.first)
        val sentBefore = h.sent.size
        finish(DownloadOutcome.Started)
        assertTrue(h.sent.drop(sentBefore).none { it.first == "a" })
        // The next holder starts its own; the running download shows to every approved session.
        val next = h.take("b")
        setDownloads(download(netboot.fileName, RemoteDownloadStatus.RUNNING, 500_000))
        h.advance(RemoteLimits.DOWNLOAD_PUSH_INTERVAL_MS_MIN)
        val view =
            h
                .pushes("b", "downloads")
                .last()
                .getValue("downloads")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(netbootId, view.str("entryId"))
        assertEquals("running", view.str("status"))
        assertEquals(500_000, view.getValue("downloadedBytes").jsonPrimitive.int)
        assertEquals("active", start("b", netbootId, next)!!.str("result"))
        // Losing the lease (takeover consent) while a start is in flight doesn't cancel it either.
        h.connect("c", nodeId = "nc")
        assertNull(start("b", debianId, next))
        h.request("c", "lease.takeover", mapOf("holderSession" to num(h.id("b"))), next)
        h.request("b", "lease.takeover_reply", mapOf("allow" to kotlinx.serialization.json.JsonPrimitive(true)), next)
        assertEquals(LeaseHolder.Remote(h.id("c")), h.controller.leaseView.first)
        finish(DownloadOutcome.Started)
        assertEquals("started", h.replyFor("b", "start-$debianId")!!.str("result"))
        assertTrue(h.downloadCalls.none { it.op is DownloadOp.Cancel })
    }

    @Test
    fun `downloads are pushed after welcome and on change, at most twice a second, and never to pending sessions`() {
        setDownloads(download(netboot.fileName, RemoteDownloadStatus.QUEUED))
        h.connect("s1")
        h.open("p", h.tailnet("pending-node"))
        h.hello("p")
        assertEquals(1, h.pushes("s1", "downloads").size)
        for (bytes in 1L..20L) {
            setDownloads(download(netboot.fileName, RemoteDownloadStatus.RUNNING, bytes * 1000))
            h.advance(50)
        }
        h.advance(RemoteLimits.DOWNLOAD_PUSH_INTERVAL_MS_MIN)
        val pushes = h.pushes("s1", "downloads")
        assertTrue(pushes.size in 2..4) { "${pushes.size} pushes for 1 s of progress" }
        val last =
            pushes
                .last()
                .getValue("downloads")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(20_000, last.getValue("downloadedBytes").jsonPrimitive.int)
        assertTrue(h.pushes("p", "downloads").isEmpty())
        // Approval sends the current downloads too.
        h.approve("pending-node")
        h.advance(RemoteLimits.DOWNLOAD_PUSH_INTERVAL_MS_MIN)
        assertEquals(1, h.pushes("p", "downloads").size)
    }

    @Test
    fun `replies and pushes stay inside one WebSocket message however large the catalog and the downloads`() {
        // Three UTF-8 bytes per char, so char limits alone wouldn't bound the bytes.
        val longText = "\u20ac".repeat(RemoteLimits.CATALOG_TEXT_LENGTH_MAX * 3)
        val distros =
            (0 until 40).map { distroIndex ->
                val distro = Distro("d$distroIndex", longText, Category.DESKTOP, "https://example.org")
                val releases =
                    (0 until 60).map { releaseIndex ->
                        release(distro.id, "d$distroIndex-$releaseIndex-${"\u20ac".repeat(80)}.iso", Arch.X86_64, version = longText)
                            .copy(variant = longText, supportedUntil = longText)
                    }
                CatalogDistroSnapshot(distro, releases, h.clock.wall, error = longText)
            }
        setCatalog(distros = distros)
        h.connect("s1")
        // The longest request id, in multibyte characters, counts against the budget too.
        val longId = "\u20ac".repeat(RemoteLimits.REQUEST_ID_LENGTH_MAX - 2)
        val reply = checkNotNull(h.request("s1", "catalog.list", id = longId))
        val replyText = h.sent.last { it.first == "s1" && it.second.contains("\"catalogRev\"") && it.second.contains(longId) }.second
        assertTrue(replyText.encodeToByteArray().size <= RemoteLimits.CATALOG_REPLY_SIZE_BYTES_MAX) { "${replyText.length} bytes" }
        assertTrue(entries(reply).size <= RemoteLimits.CATALOG_REPLY_ENTRY_COUNT_MAX)
        assertTrue(reply.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(entries(reply).all { it.str("version").length <= RemoteLimits.CATALOG_TEXT_LENGTH_MAX })
        // A narrower query fits whole.
        val one = list("s1", mapOf("distroId" to str("d3")))
        assertTrue(entries(one).size in 1..60)

        val downloads =
            (0 until RemoteLimits.DOWNLOAD_COUNT_MAX + 10).map {
                val status = if (it == RemoteLimits.DOWNLOAD_COUNT_MAX - 1) RemoteDownloadStatus.RUNNING else RemoteDownloadStatus.FAILED
                RemoteDownload("f$it-${"\u20ac".repeat(90)}.iso", longText, status, Long.MAX_VALUE, Long.MAX_VALUE, false, longText)
            }
        h.handle(RemoteEvent.DownloadsChanged(downloads))
        h.advance(RemoteLimits.DOWNLOAD_PUSH_INTERVAL_MS_MIN)
        val push = h.sent.last { it.first == "s1" && it.second.contains("\"t\":\"downloads\"") }.second
        assertTrue(push.encodeToByteArray().size <= RemoteLimits.DOWNLOADS_PUSH_SIZE_BYTES_MAX) { "${push.length} bytes" }
        assertTrue(RemoteLimits.DOWNLOADS_PUSH_SIZE_BYTES_MAX < WEBSOCKET_MESSAGE_BYTES_MAX)
        val pushed = RemoteHarness.parse(push)
        assertTrue(pushed.getValue("truncated").jsonPrimitive.boolean)
        val first =
            pushed
                .getValue("downloads")
                .jsonArray
                .first()
                .jsonObject
        assertEquals("running", first.str("status")) { "the active download goes first" }
    }

    @Test
    fun `duplicate file names keep the first release, so an entry id names exactly one release`() {
        val twin = netboot.copy(distroId = "debian", url = "https://elsewhere.example/netboot.xyz.iso")
        setCatalog(
            distros =
                listOf(
                    CatalogDistroSnapshot(rescue, listOf(netboot), h.clock.wall, null),
                    CatalogDistroSnapshot(desktop, listOf(twin, debian), h.clock.wall, null),
                ),
        )
        h.connect("s1")
        h.take("s1")
        assertEquals(listOf(debian.fileName, netboot.fileName).sorted(), entries(list("s1")).map { it.str("fileName") }.sorted())
        assertNull(start("s1", netbootId))
        assertEquals(DownloadOp.Start(netboot, "netboot.xyz"), h.downloadCalls.single().op)
    }

    private companion object {
        /** Go's protocol.MessageBytesMax. */
        const val WEBSOCKET_MESSAGE_BYTES_MAX = 64 * 1024
    }
}
