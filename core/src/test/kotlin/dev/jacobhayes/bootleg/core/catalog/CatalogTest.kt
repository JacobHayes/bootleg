package dev.jacobhayes.bootleg.core.catalog

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class CatalogTest {
    private val hash = "ab".repeat(32)

    private fun release(
        distroId: String,
        version: String = "1",
        url: String = "https://example.org/$distroId-$version.iso",
        sha256: String? = hash,
        sumsUrl: String? = null,
        fileName: String = "$distroId-$version.iso",
    ) = Release(
        distroId = distroId,
        version = version,
        arch = Arch.X86_64,
        url = url,
        sha256 = sha256,
        sumsUrl = sumsUrl,
        fileName = fileName,
    )

    private class FakeScraper(
        id: String,
        private val fetch: suspend (Http) -> List<Release>,
    ) : Scraper {
        override val distros = listOf(Distro(id, id, Category.DESKTOP, "https://example.org"))

        override suspend fun releases(http: Http) = fetch(http)
    }

    /** Serves fixed bodies and tracks how many requests are in flight at once. */
    private class FakeHttp(
        private val bodies: Map<String, String> = emptyMap(),
        private val delayMs: Long = 0,
    ) : Http {
        val inFlight = AtomicInteger()
        val inFlightMax = AtomicInteger()

        override suspend fun get(url: String): HttpResponse {
            inFlightMax.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                delay(delayMs)
                val body = bodies[url] ?: return HttpResponse(404, "", url = url)
                return HttpResponse(200, body, url = url)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun head(url: String) = HttpResponse(200, "", url = url)
    }

    @Test
    fun `one failing or hanging scraper does not affect the others`() =
        runBlocking<Unit> {
            val scrapers =
                listOf(
                    FakeScraper("good") { listOf(release("good")) },
                    FakeScraper("broken") { throw IllegalStateException("unexpected format") },
                    FakeScraper("slow") { awaitCancellation() },
                    FakeScraper("empty") { emptyList() },
                )
            val result = Catalog(scrapers, FakeHttp(), scraperTimeoutMs = 200).fetchAll()
            assertEquals(listOf("good"), result.releases.map { it.distroId })
            assertEquals(listOf("good", "broken", "slow", "empty"), result.distros.map { it.id })
            assertEquals(
                listOf(ScraperError(listOf("broken"), "unexpected format"), ScraperError(listOf("slow"), "timed out after 200ms")),
                result.errors,
            )
        }

    @Test
    fun `requests across all scrapers are capped and a slow request fails only its scraper`() =
        runBlocking<Unit> {
            val bodies = (0 until 20).associate { "https://example.org/$it" to "ok" }
            val http = FakeHttp(bodies, delayMs = 20)
            val scrapers =
                (0 until 5).map { scraperIndex ->
                    FakeScraper("s$scraperIndex") { scraperHttp ->
                        ScraperSupport.fanOut((0 until 4).toList()) { scraperHttp.getText("https://example.org/${scraperIndex * 4 + it}") }
                        listOf(release("s$scraperIndex"))
                    }
                }
            val result = Catalog(scrapers, http).fetchAll()
            assertEquals(5, result.releases.size)
            assertEquals(CatalogLimits.FETCH_CONCURRENCY_MAX, http.inFlightMax.get())

            val timedOut =
                Catalog(
                    listOf(
                        FakeScraper("x") {
                            it.get("https://example.org/0")
                            emptyList()
                        },
                    ),
                    FakeHttp(bodies, 500),
                    requestTimeoutMs = 50,
                ).fetchAll()
            assertEquals(listOf(ScraperError(listOf("x"), "request timed out after 50ms: https://example.org/0")), timedOut.errors)
        }

    @Test
    fun `the response cap counts bytes, not characters`() =
        runBlocking<Unit> {
            // Two UTF-8 bytes per char: within the cap in UTF-16 units, over it in bytes.
            val limitChars = (CatalogLimits.RESPONSE_BYTES_MAX / 2).toInt()
            val atLimit = "é".repeat(limitChars)
            val overLimit = atLimit + "é"
            assertEquals(CatalogLimits.RESPONSE_BYTES_MAX, utf8ByteCount(atLimit))
            assertEquals(4L, utf8ByteCount("\uD83D\uDE00"))
            val http = FakeHttp(mapOf("https://example.org/ok" to atLimit, "https://example.org/big" to overLimit))
            val scrapers =
                listOf("ok", "big").map { name ->
                    FakeScraper(name) {
                        it.getText("https://example.org/$name")
                        listOf(release(name))
                    }
                }
            val result = Catalog(scrapers, http).fetchAll()
            assertEquals(listOf("ok"), result.releases.map { it.distroId })
            assertEquals(listOf(ScraperError(listOf("big"), "response too large: https://example.org/big")), result.errors)
        }

    @Test
    fun `invalid releases are dropped and reported`() =
        runBlocking<Unit> {
            val bad =
                listOf(
                    release("d", "1", url = "http://example.org/a.iso"),
                    release("d", "2", fileName = "../evil.iso"),
                    release("d", "3", fileName = "dir/evil.iso"),
                    release("d", "4", sha256 = "XYZ"),
                    release("d", "5", sha256 = hash.uppercase()),
                    release("d", "6", sha256 = null, sumsUrl = null),
                    release("other", "7"),
                    release("d", "8", sha256 = null, sumsUrl = "ftp://example.org/SUMS"),
                    release("d", " "),
                )
            val good = release("d", "9", sha256 = null, sumsUrl = "https://example.org/SHA256SUMS")
            val result = Catalog(listOf(FakeScraper("d") { bad + good }), FakeHttp()).fetchAll()
            assertEquals(listOf(good), result.releases)
            assertEquals(bad.size, result.errors.size)
            assertTrue(result.errors.all { it.distroIds == listOf("d") && it.message.startsWith("invalid release") })
        }

    @Test
    fun `duplicate URLs and keys are dropped`() =
        runBlocking<Unit> {
            val first = release("d", "1")
            val sameUrl = first.copy(version = "2")
            val sameKey = first.copy(url = "https://example.org/other.iso")
            val result = Catalog(listOf(FakeScraper("d") { listOf(first, sameUrl, sameKey) }), FakeHttp()).fetchAll()
            assertEquals(listOf(first), result.releases)
        }

    @Test
    fun `duplicate distro ids across scrapers are rejected`() {
        assertThrows<IllegalArgumentException> {
            Catalog(
                listOf(FakeScraper("a") { emptyList() }, FakeScraper("a") { emptyList() }),
                FakeHttp(),
            )
        }
    }

    @Test
    fun `file name safety`() {
        assertTrue(Catalog.isSafeFileName("ubuntu-26.04-desktop-amd64.iso"))
        listOf("", ".hidden", "a/b", "a\\b", "..", "a..b", "a\u0000b", "x".repeat(256)).forEach {
            assertTrue(!Catalog.isSafeFileName(it), "should reject '$it'")
        }
    }

    @Test
    fun `resolveSha256 prefers the up-front hash, else looks the file up in the sums`() =
        runBlocking<Unit> {
            val sumsUrl = "https://example.org/SHA256SUMS"
            val http = FakeHttp(mapOf(sumsUrl to "$hash *d-1.iso\n${"cd".repeat(32)}  other.iso\n"))
            assertEquals(hash, resolveSha256(release("d", sha256 = hash), http))
            assertEquals(hash, resolveSha256(release("d", sha256 = null, sumsUrl = sumsUrl), http))
            assertNull(resolveSha256(release("d", sha256 = null, sumsUrl = null), http))
            assertThrows<IllegalStateException> { resolveSha256(release("d", "2", sha256 = null, sumsUrl = sumsUrl), http) }
            assertThrows<java.io.IOException> { resolveSha256(release("d", sha256 = null, sumsUrl = "https://example.org/missing"), http) }
        }

    @Test
    fun `resolveSha256 reads the SHA-256 section of sectioned sums files`() =
        runBlocking<Unit> {
            // Recorded GParted and Clonezilla CHECKSUMS.TXT: MD5 through BLAKE3 sections, where BLAKE3 is also 64 hex.
            val fixtures = FixtureHttp(Path.of("src/test/resources/fixtures/catalog/server-rescue"), record = false)
            val cases =
                mapOf(
                    "https://gparted.org/gparted-live/stable/CHECKSUMS.TXT" to
                        ("gparted-live-1.8.1-6-amd64.iso" to "d789c38779f0d6f7026c12f44c2c52a04f66e28a1aea7d51f3045ad1bbf28411"),
                    "https://clonezilla.org/downloads/stable/data/CHECKSUMS.TXT" to
                        ("clonezilla-live-3.3.3-37-amd64.iso" to "3079458d926a37d3533e5d5caeb61b6e49c2dc69e2c97e0332ef37986bb3414f"),
                )
            for ((sumsUrl, expected) in cases) {
                val (fileName, sha256) = expected
                val release = release("d", sha256 = null, sumsUrl = sumsUrl, url = "https://example.org/$fileName", fileName = fileName)
                assertEquals(sha256, resolveSha256(release, fixtures))
            }
        }
}
