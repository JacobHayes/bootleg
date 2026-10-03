package dev.jacobhayes.bootleg.catalog

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Feed
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.HttpResponse
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class CatalogRepositoryTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() = scope.cancel()

    private val noHttp =
        object : Http {
            override suspend fun get(url: String): HttpResponse = error("no network in tests")

            override suspend fun head(url: String): HttpResponse = error("no network in tests")
        }

    /** Answers, fails, or hangs until [gate] completes, as told; counts its calls. */
    private class FakeScraper(
        val id: String,
        var fail: Boolean = false,
        var gate: CompletableDeferred<Unit>? = null,
        val name: String = id.uppercase(),
        val category: Category = Category.DESKTOP,
    ) : Scraper {
        val distro = Distro(id, name, category, "https://$id.example")
        override val distros = listOf(distro)
        val calls = AtomicInteger()

        override suspend fun releases(http: Http): List<Release> {
            calls.incrementAndGet()
            gate?.await()
            if (fail) throw IOException("HTTP 503")
            return listOf(
                Release(distro.id, "1.0", Arch.X86_64, url = "https://$id.example/$id.iso", sha256 = "0".repeat(64), fileName = "$id.iso"),
            )
        }
    }

    private fun repository(
        scrapers: List<Scraper>,
        cache: File = File(dir, "cache.json"),
        network: () -> Boolean = { true },
        now: () -> Long = { 1_000L },
    ) = CatalogRepository(scrapers, noHttp, cache, scope, network, Dispatchers.Default, now)

    private fun CatalogRepository.entry(id: String) = state.value.entries.single { it.distro.id == id }

    @Test
    fun `feeds fail independently and a failure keeps the last good result`() =
        runBlocking<Unit> {
            val good = FakeScraper("good")
            val flaky = FakeScraper("flaky")
            var now = 1_000L
            val repository = repository(listOf(good, flaky), now = { now })
            repository.refreshAll().join()
            assertTrue(
                repository.state.value.entries
                    .all { it.error == null && it.releases.size == 1 },
            )

            flaky.fail = true
            now = 5_000L
            repository.refreshAll().join()
            assertNull(repository.entry("good").error)
            assertEquals(5_000L, repository.entry("good").fetchedAtMs)
            val stale = repository.entry("flaky")
            assertEquals("HTTP 503", stale.error)
            assertEquals(5_000L, stale.failedAtMs)
            assertEquals(1_000L, stale.fetchedAtMs, "stale entry keeps its original age")
            assertEquals(1, stale.releases.size)
            assertFalse(repository.state.value.refreshing)
        }

    @Test
    fun `a hung feed stays loading while the others land, and only it is affected`() =
        runBlocking<Unit> {
            val hung = FakeScraper("hung", gate = CompletableDeferred())
            val others = (1..3).map { FakeScraper("f$it") }
            val repository = repository(listOf(hung) + others)
            val all = repository.refreshAll()
            withTimeout(5_000) {
                repository.state.first { state -> state.entries.filter { it.distro.id != "hung" }.all { it.fetchedAtMs != null } }
            }
            val state = repository.state.value
            assertTrue(repository.entry("hung").loading)
            assertTrue(state.refreshing)
            assertTrue(state.entries.all { it.error == null })
            hung.gate!!.complete(Unit)
            all.join()
            assertEquals(1, repository.entry("hung").releases.size)
            assertFalse(repository.state.value.refreshing)
        }

    @Test
    fun `cancelling a fetch leaves the entry as it was, never failed`() =
        runBlocking<Unit> {
            val scraper = FakeScraper("a")
            val repository = repository(listOf(scraper))
            repository.refresh("a").join()
            val before = repository.entry("a")

            scraper.gate = CompletableDeferred()
            val job = repository.start(Feed("a", scraper.distros))
            withTimeout(5_000) { repository.state.first { it.entries.single().loading } }
            job.cancelAndJoin()
            val after = repository.entry("a")
            assertNull(after.error)
            assertFalse(after.loading)
            assertEquals(before.releases, after.releases)
            assertEquals(before.fetchedAtMs, after.fetchedAtMs)
        }

    @Test
    fun `cancelling a caller's wait does not cancel the shared fetch`() =
        runBlocking<Unit> {
            val scraper = FakeScraper("a", gate = CompletableDeferred())
            val repository = repository(listOf(scraper))
            val waiter = repository.refresh("a")
            withTimeout(5_000) { repository.state.first { it.entries.single().loading } }
            waiter.cancelAndJoin()
            scraper.gate!!.complete(Unit)
            withTimeout(5_000) { repository.state.first { it.entries.single().fetchedAtMs != null } }
        }

    @Test
    fun `a fetch started while a cancelled one cleans up runs after it, and its loading state sticks`() =
        runBlocking<Unit> {
            val scraper = FakeScraper("a", gate = CompletableDeferred())
            val repository = repository(listOf(scraper))
            val feed = Feed("a", scraper.distros)
            val first = repository.start(feed)
            withTimeout(5_000) { repository.state.first { it.entries.single().loading } }
            first.cancel()
            val second = repository.start(feed)
            first.join()
            withTimeout(5_000) { repository.state.first { it.entries.single().loading } }
            scraper.gate!!.complete(Unit)
            second.join()
            assertEquals(2, scraper.calls.get())
            assertNotNull(repository.entry("a").fetchedAtMs)
            assertFalse(repository.entry("a").loading)
        }

    @Test
    fun `one host's DNS failure is that feed's error, not offline`() =
        runBlocking<Unit> {
            val broken =
                object : Scraper {
                    override val distros = listOf(Distro("broken", "Broken", Category.DESKTOP, "https://broken.example"))

                    override suspend fun releases(http: Http): List<Release> = throw java.net.UnknownHostException("Unable to resolve host")
                }
            val repository = repository(listOf(broken, FakeScraper("good")))
            repository.refreshAll().join()
            assertFalse(repository.state.value.offline)
            assertEquals("Unable to resolve host", repository.entry("broken").error)
            assertNull(repository.entry("good").error)
        }

    @Test
    fun `the network coming back waits for fetches still failing, then retries them`() =
        runBlocking<Unit> {
            var online = true
            val scraper = FakeScraper("a", fail = true, gate = CompletableDeferred())
            val repository = repository(listOf(scraper), network = { online })
            repository.refresh("a")
            withTimeout(5_000) { repository.state.first { it.entries.single().loading } }
            online = false
            repository.onNetworkChanged()
            assertTrue(repository.state.value.offline)
            online = true
            repository.onNetworkChanged()
            assertFalse(repository.state.value.offline)
            // The in-flight attempt now fails (with the network back it counts as a failure); the
            // catch-up retries it anyway, recent failure or not.
            scraper.fail = false
            scraper.gate!!.complete(Unit)
            withTimeout(5_000) { repository.state.first { it.entries.single().fetchedAtMs != null } }
            assertTrue(scraper.calls.get() >= 1)
        }

    @Test
    fun `opening with nothing due still shows offline`() =
        runBlocking<Unit> {
            var online = true
            val scraper = FakeScraper("a")
            val repository = repository(listOf(scraper), network = { online })
            repository.refreshAll().join()
            online = false
            repository.refreshStale().join()
            assertTrue(repository.state.value.offline)
            assertEquals(1, scraper.calls.get())
        }

    @Test
    fun `a cache that can't be written costs nothing but the cache`() =
        runBlocking<Unit> {
            val unwritable = File(File(dir, "missing-dir"), "cache.json")
            val repository = repository(listOf(FakeScraper("a")), unwritable)
            repository.refreshAll().join()
            assertEquals(1, repository.entry("a").releases.size)
            assertFalse(unwritable.exists())
        }

    @Test
    fun `leaving the screen does not cancel a refresh it started`() =
        runBlocking<Unit> {
            val scraper = FakeScraper("a", gate = CompletableDeferred())
            val repository = repository(listOf(scraper))
            val screen = CoroutineScope(Dispatchers.Default)
            screen.launch { repository.refreshStale() }
            withTimeout(5_000) { repository.state.first { it.entries.single().loading } }
            screen.cancel()
            scraper.gate!!.complete(Unit)
            withTimeout(5_000) { repository.state.first { it.entries.single().fetchedAtMs != null } }
            assertNull(repository.entry("a").error)
        }

    @Test
    fun `with no usable network nothing is fetched and nothing is marked failed`() =
        runBlocking<Unit> {
            val scrapers = (1..3).map { FakeScraper("f$it", fail = true) }
            val repository = repository(scrapers, network = { false })
            repository.refreshAll().join()
            assertTrue(repository.state.value.offline)
            assertTrue(
                repository.state.value.entries
                    .all { it.error == null && !it.loading },
            )
            assertTrue(scrapers.all { it.calls.get() == 0 })
        }

    @Test
    fun `failures while the network drops mid-refresh are offline, not failed`() =
        runBlocking<Unit> {
            var online = true
            val scraper = FakeScraper("a", fail = true, gate = CompletableDeferred())
            val repository = repository(listOf(scraper), network = { online })
            val job = repository.refresh("a")
            withTimeout(5_000) { repository.state.first { it.entries.single().loading } }
            online = false
            scraper.gate!!.complete(Unit)
            job.join()
            assertTrue(repository.state.value.offline)
            assertNull(repository.entry("a").error)

            online = true
            scraper.fail = false
            repository.refresh("a").join()
            assertFalse(repository.state.value.offline)
        }

    @Test
    fun `a feed is fetched once at a time`() =
        runBlocking<Unit> {
            val scraper = FakeScraper("a", gate = CompletableDeferred())
            val repository = repository(listOf(scraper))
            val first = repository.refresh("a")
            withTimeout(5_000) { repository.state.first { it.entries.single().loading } }
            val second = repository.refresh("a")
            val all = repository.refreshAll()
            val stale = repository.refreshStale(maxAgeMs = 0)
            scraper.gate!!.complete(Unit)
            listOf(first, second, all, stale).joinAll()
            assertEquals(1, scraper.calls.get())
        }

    @Test
    fun `the cache shows at once after a restart, and a fresh failure keeps it`() =
        runBlocking<Unit> {
            val cache = File(dir, "cache.json")
            val scraper = FakeScraper("debian")
            repository(listOf(scraper), cache, now = { 42L }).refreshAll().join()

            scraper.fail = true
            val restarted = repository(listOf(scraper), cache, now = { 100L })
            restarted.awaitCache()
            assertTrue(restarted.state.value.cacheLoaded)
            assertEquals(42L, restarted.entry("debian").fetchedAtMs)
            assertEquals(1, restarted.entry("debian").releases.size)
            restarted.refreshAll().join()
            val entry = restarted.entry("debian")
            assertEquals("HTTP 503", entry.error)
            assertEquals(1, entry.releases.size)
        }

    @Test
    fun `cached distros no scraper declares any more are dropped`() =
        runBlocking<Unit> {
            val cache = File(dir, "cache.json")
            repository(listOf(FakeScraper("kept"), FakeScraper("removed")), cache).refreshAll().join()
            val trimmed = repository(listOf(FakeScraper("kept")), cache)
            trimmed.awaitCache()
            assertEquals(
                listOf("kept"),
                trimmed.state.value.entries
                    .map { it.distro.id },
            )
        }

    @Test
    fun `refreshStale fetches only stale feeds that did not just fail`() =
        runBlocking<Unit> {
            val fresh = FakeScraper("fresh")
            val old = FakeScraper("old")
            val failing = FakeScraper("failing", fail = true)
            var now = 0L
            val repository = repository(listOf(fresh, old, failing), now = { now })
            repository.refresh("old").join()
            repository.refresh("failing").join()
            now = CatalogRepository.MAX_AGE_MS + 1
            repository.refresh("fresh").join()
            now += 1
            repository.refreshStale().join()
            assertEquals(1, fresh.calls.get(), "fresh feed not refetched")
            assertEquals(2, old.calls.get(), "stale feed refetched")
            assertEquals(2, failing.calls.get(), "failed long ago: retried")
            repository.refreshStale().join()
            assertEquals(2, failing.calls.get(), "just failed: not retried on open")
            repository.refresh("failing").join()
            assertEquals(3, failing.calls.get(), "the card's Retry always fetches")
        }

    @Test
    fun `feedsToRefresh covers never fetched, stale, loading and recently failed feeds`() {
        val distro = { id: String -> Distro(id, id, Category.DESKTOP, "https://$id.example") }
        val feeds = listOf("never", "stale", "fresh", "loading", "failed", "freshFailedLongAgo").map { Feed(it, listOf(distro(it))) }
        val now = 10 * CatalogRepository.MAX_AGE_MS
        val entries =
            listOf(
                DistroEntry(distro("never"), emptyList(), null),
                DistroEntry(distro("stale"), emptyList(), now - CatalogRepository.MAX_AGE_MS - 1),
                DistroEntry(distro("fresh"), emptyList(), now - 1),
                DistroEntry(distro("loading"), emptyList(), null, loading = true),
                DistroEntry(distro("failed"), emptyList(), null, error = "x", failedAtMs = now - 1),
                // Fresh releases, but a later manual refresh failed: retried once the interval has passed.
                DistroEntry(
                    distro("freshFailedLongAgo"),
                    emptyList(),
                    now - 1,
                    error = "x",
                    failedAtMs =
                        now - CatalogRepository.FAILED_RETRY_INTERVAL_MS,
                ),
            )
        assertEquals(
            listOf("never", "stale", "freshFailedLongAgo"),
            CatalogRepository.feedsToRefresh(entries, feeds, now, CatalogRepository.MAX_AGE_MS).map { it.id },
        )
        assertThrows<IllegalArgumentException> { DistroEntry(distro("x"), emptyList(), null, error = "x") }
    }

    @Test
    fun `corrupt cache is discarded`() =
        runBlocking<Unit> {
            val cache = File(dir, "cache.json").apply { writeText("{not json") }
            val repository = repository(listOf(FakeScraper("a")), cache)
            repository.awaitCache()
            assertNull(repository.entry("a").fetchedAtMs)
            assertFalse(cache.exists())
        }

    @Test
    fun `releases failing core validation are dropped, an all-invalid feed keeps the last good result`() =
        runBlocking<Unit> {
            val distro = Distro("mixed", "Mixed", Category.DESKTOP, "https://mixed.example")
            var releases =
                listOf(
                    Release(distro.id, "1", Arch.X86_64, url = "https://mixed.example/a.iso", sha256 = "0".repeat(64), fileName = "a.iso"),
                    Release(distro.id, "2", Arch.X86_64, url = "http://mixed.example/b.iso", sha256 = "0".repeat(64), fileName = "b.iso"),
                    Release(
                        distro.id,
                        "3",
                        Arch.X86_64,
                        url = "https://mixed.example/c.iso",
                        sha256 = "0".repeat(64),
                        fileName = "../c.iso",
                    ),
                )
            val scraper =
                object : Scraper {
                    override val distros = listOf(distro)

                    override suspend fun releases(http: Http) = releases
                }
            val repository = repository(listOf(scraper))
            repository.refreshAll().join()
            assertEquals(listOf("a.iso"), repository.entry("mixed").releases.map { it.fileName })
            assertNull(repository.entry("mixed").error)

            releases = releases.drop(1)
            repository.refreshAll().join()
            val stale = repository.entry("mixed")
            assertEquals(listOf("a.iso"), stale.releases.map { it.fileName }, "kept from the last good refresh")
            assertTrue(stale.error!!.startsWith("invalid release"))
        }

    @Test
    fun `entries are sorted by category, then name case-insensitively`() {
        val repository =
            repository(
                listOf(
                    FakeScraper("z", name = "zorin"),
                    FakeScraper("s", name = "Alpine", category = Category.SERVER),
                    FakeScraper("a", name = "Arch"),
                    FakeScraper("b", name = "bodhi"),
                ),
            )
        assertEquals(
            listOf("Arch", "bodhi", "zorin", "Alpine"),
            repository.state.value.entries
                .map { it.distro.name },
        )
    }

    @Test
    fun `duplicate distro ids are rejected`() {
        assertThrows<IllegalArgumentException> { repository(listOf(FakeScraper("a"), FakeScraper("a"))) }
    }

    @Test
    fun `an unknown distro cannot be refreshed`() {
        assertThrows<IllegalArgumentException> { repository(listOf(FakeScraper("a"))).refresh("missing") }
    }
}
