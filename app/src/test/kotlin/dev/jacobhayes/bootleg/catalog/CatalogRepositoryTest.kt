package dev.jacobhayes.bootleg.catalog

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.HttpResponse
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class CatalogRepositoryTest {
    @TempDir
    lateinit var dir: File

    private val noHttp =
        object : Http {
            override suspend fun get(url: String): HttpResponse = error("no network in tests")

            override suspend fun head(url: String): HttpResponse = error("no network in tests")
        }

    private class FlakyScraper(
        val id: String,
        var fail: Boolean = false,
    ) : Scraper {
        val distro = Distro(id, id.uppercase(), Category.DESKTOP, "https://$id.example")
        override val distros = listOf(distro)

        override suspend fun releases(http: Http): List<Release> {
            if (fail) throw IOException("HTTP 503")
            return listOf(
                Release(distro.id, "1.0", Arch.X86_64, url = "https://$id.example/$id.iso", sha256 = "0".repeat(64), fileName = "$id.iso"),
            )
        }
    }

    @Test
    fun `scrapers fail independently and keep the last good result`() =
        runTest {
            val good = FlakyScraper("good")
            val flaky = FlakyScraper("flaky")
            var now = 1_000L
            val cache = File(dir, "cache.json")
            val repository = CatalogRepository(listOf(good, flaky), noHttp, cache) { now }
            repository.refresh()
            assertTrue(
                repository.state.value.entries
                    .all { it.error == null && it.releases.size == 1 },
            )

            flaky.fail = true
            now = 5_000L
            repository.refresh()
            val byId =
                repository.state.value.entries
                    .associateBy { it.distro.id }
            assertNull(byId.getValue("good").error)
            assertEquals(5_000L, byId.getValue("good").fetchedAtMs)
            assertEquals("HTTP 503", byId.getValue("flaky").error)
            assertEquals(1_000L, byId.getValue("flaky").fetchedAtMs, "stale entry keeps its original age")
            assertEquals(1, byId.getValue("flaky").releases.size)
            assertFalse(repository.state.value.refreshing)
        }

    @Test
    fun `cache survives a restart and a fresh failure shows stale data`() =
        runTest {
            val cache = File(dir, "cache.json")
            val scraper = FlakyScraper("debian")
            CatalogRepository(listOf(scraper), noHttp, cache) { 42L }.refresh()

            scraper.fail = true
            val restarted = CatalogRepository(listOf(scraper), noHttp, cache) { 100L }
            restarted.loadCache()
            assertEquals(
                42L,
                restarted.state.value.entries
                    .single()
                    .fetchedAtMs,
            )
            restarted.refresh()
            val entry =
                restarted.state.value.entries
                    .single()
            assertEquals("HTTP 503", entry.error)
            assertEquals(1, entry.releases.size)
        }

    @Test
    fun `no cache and failure shows an error with no releases`() =
        runTest {
            val repository = CatalogRepository(listOf(FlakyScraper("x", fail = true)), noHttp, File(dir, "c.json")) { 1L }
            repository.loadCache()
            assertTrue(repository.isStale(maxAgeMs = 10))
            repository.refresh()
            val entry =
                repository.state.value.entries
                    .single()
            assertNull(entry.fetchedAtMs)
            assertTrue(entry.releases.isEmpty())
        }

    @Test
    fun `corrupt cache is discarded`() =
        runTest {
            val cache = File(dir, "cache.json").apply { writeText("{not json") }
            val repository = CatalogRepository(listOf(FlakyScraper("a")), noHttp, cache) { 1L }
            repository.loadCache()
            assertNull(
                repository.state.value.entries
                    .single()
                    .fetchedAtMs,
            )
            assertFalse(cache.exists())
        }

    @Test
    fun `releases failing core validation are dropped, an all-invalid scraper keeps the last good result`() =
        runTest {
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
            val repository = CatalogRepository(listOf(scraper), noHttp, File(dir, "c.json")) { 7L }
            repository.refresh()
            val entry =
                repository.state.value.entries
                    .single()
            assertEquals(listOf("a.iso"), entry.releases.map { it.fileName })
            assertNull(entry.error)

            releases = releases.drop(1)
            repository.refresh()
            val stale =
                repository.state.value.entries
                    .single()
            assertEquals(listOf("a.iso"), stale.releases.map { it.fileName }, "kept from the last good refresh")
            assertTrue(stale.error!!.startsWith("invalid release"))
        }

    @Test
    fun `duplicate distro ids are rejected`() {
        assertThrows<IllegalArgumentException> {
            CatalogRepository(listOf(FlakyScraper("a"), FlakyScraper("a")), noHttp, File(dir, "c.json"))
        }
    }

    @Test
    fun `empty scraper list is valid and never stale`() {
        val repository = CatalogRepository(emptyList(), noHttp, File(dir, "c.json"))
        assertFalse(repository.isStale(0))
    }
}
