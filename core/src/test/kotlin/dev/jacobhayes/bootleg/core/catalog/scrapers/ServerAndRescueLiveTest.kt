package dev.jacobhayes.bootleg.core.catalog.scrapers

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.CatalogLimits
import dev.jacobhayes.bootleg.core.catalog.JdkHttp
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/** L3: scrape every server/rescue distro live, HEAD every image, and resolve every sums file. */
@Tag("live")
class ServerAndRescueLiveTest {
    private val http = JdkHttp()

    private val expectedArches =
        mapOf(
            "alpine" to setOf(Arch.X86_64, Arch.ARM64),
            "rocky" to setOf(Arch.X86_64, Arch.ARM64),
            "almalinux" to setOf(Arch.X86_64, Arch.ARM64),
            "proxmox-ve" to setOf(Arch.X86_64, Arch.ARM64),
            "truenas" to setOf(Arch.X86_64),
            "systemrescue" to setOf(Arch.X86_64),
            "gparted-live" to setOf(Arch.X86_64),
            "clonezilla" to setOf(Arch.X86_64),
            "shredos" to setOf(Arch.X86_64),
            "netboot-xyz" to setOf(Arch.X86_64, Arch.ARM64),
        )

    @Test
    fun `every distro scrapes, every image is reachable, every sums file lists its image`() =
        runBlocking<Unit> {
            val releases =
                serverAndRescueScrapers
                    .map { scraper -> async(Dispatchers.IO) { scraper.releases(http) } }
                    .awaitAll()
                    .flatten()

            assertEquals(expectedArches.keys, serverAndRescueScrapers.flatMap { it.distros }.map { it.id }.toSet())
            for ((distroId, arches) in expectedArches) {
                assertEquals(arches, releases.filter { it.distroId == distroId }.map { it.arch }.toSet(), "arches for $distroId")
            }

            val permits = Semaphore(PROBE_CONCURRENCY_MAX)
            val unreachable =
                releases
                    .map { release -> async(Dispatchers.IO) { permits.withPermit { release.url.takeUnless { isReachable(it) } } } }
                    .awaitAll()
                    .filterNotNull()
            assertEquals(emptyList<String>(), unreachable, "image URLs that failed HEAD and ranged GET")

            val sumsByUrl =
                releases
                    .mapNotNull { it.sumsUrl }
                    .distinct()
                    .map { url -> async(Dispatchers.IO) { permits.withPermit { url to fetchSums(url) } } }
                    .awaitAll()
                    .toMap()
            for (release in releases) {
                val sums = sumsByUrl[release.sumsUrl] ?: continue
                assertEquals(release.sha256, sums[release.fileName], "sums for ${release.url}")
            }
            assertTrue(releases.all { it.sha256 != null }, "every server/rescue release resolves its sha256 up front")
        }

    private suspend fun fetchSums(url: String): Map<String, String> {
        val response = http.get(url)
        check(response.status == 200) { "GET $url: HTTP ${response.status}" }
        return ScraperSupport.parseSha256Sums(response.body)
    }

    private suspend fun isReachable(url: String): Boolean {
        val head = runCatching { http.head(url).status }.getOrNull()
        if (head != null && head in 200..399) return true
        // Some mirrors reject HEAD; a one-byte ranged GET proves the object exists. A server that ignores
        // Range answers 200 with the whole image, so the body is never read: the stream is closed at once.
        return withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    HttpRequest
                        .newBuilder(URI(url))
                        .timeout(Duration.ofMillis(CatalogLimits.REQUEST_TIMEOUT_MS))
                        .header("Range", "bytes=0-0")
                        .header("User-Agent", "bootleg-catalog-test/0.1")
                        .GET()
                        .build()
                val response = rangeClient.send(request, BodyHandlers.ofInputStream())
                response.body().close()
                response.statusCode() == 200 || response.statusCode() == 206
            }.getOrDefault(false)
        }
    }

    private companion object {
        const val PROBE_CONCURRENCY_MAX = 16
        val rangeClient: HttpClient =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(CatalogLimits.REQUEST_TIMEOUT_MS))
                .build()
    }
}
