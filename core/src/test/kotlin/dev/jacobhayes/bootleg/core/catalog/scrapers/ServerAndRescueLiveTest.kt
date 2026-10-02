package dev.jacobhayes.bootleg.core.catalog.scrapers

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.CatalogLimits
import dev.jacobhayes.bootleg.core.catalog.JdkHttp
import dev.jacobhayes.bootleg.core.catalog.LiveTransients
import dev.jacobhayes.bootleg.core.catalog.MemoizingHttp
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.RetryingHttp
import dev.jacobhayes.bootleg.core.catalog.RetryingScraper
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.catalog.liveName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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

/**
 * L3: scrape every server/rescue distro live, HEAD every image, and resolve every sums file.
 * Transient upstream failures are retried once and tolerated per [LiveTransients].
 */
@Tag("live")
class ServerAndRescueLiveTest {
    private val plainHttp = MemoizingHttp(JdkHttp())
    private val http = RetryingHttp(plainHttp)

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
            "memtest86plus" to setOf(Arch.X86_64),
            "shredos" to setOf(Arch.X86_64),
            "netboot-xyz" to setOf(Arch.X86_64, Arch.ARM64),
        )

    private val scraperNameByDistro =
        serverAndRescueScrapers.flatMap { scraper -> scraper.distros.map { it.id to scraper.liveName } }.toMap()

    private fun isTolerated(distroId: String): Boolean = LiveTransients.isRecorded(scraperNameByDistro.getValue(distroId))

    private fun recordTransient(
        release: Release,
        reason: String,
    ) = LiveTransients.record(scraperNameByDistro.getValue(release.distroId), reason)

    @Test
    fun `every distro scrapes, every image is reachable, every sums file lists its image`() =
        runBlocking<Unit> {
            val outcomes =
                serverAndRescueScrapers
                    .map { scraper ->
                        async(Dispatchers.IO) { scraper.liveName to runCatching { RetryingScraper(scraper).releases(plainHttp) } }
                    }.awaitAll()
            val hardFailures =
                outcomes
                    .filter { (name, outcome) -> outcome.isFailure && !LiveTransients.isRecorded(name) }
                    .map { (name, outcome) -> "$name: ${outcome.exceptionOrNull()}" }
            assertEquals(emptyList<String>(), hardFailures, "scrapers that failed")
            val releases = outcomes.flatMap { (_, outcome) -> outcome.getOrDefault(emptyList()) }

            assertEquals(expectedArches.keys, serverAndRescueScrapers.flatMap { it.distros }.map { it.id }.toSet())
            for ((distroId, arches) in expectedArches) {
                if (isTolerated(distroId)) continue
                assertEquals(arches, releases.filter { it.distroId == distroId }.map { it.arch }.toSet(), "arches for $distroId")
            }

            val permits = Semaphore(PROBE_CONCURRENCY_MAX)
            val unreachable =
                releases
                    .map { release -> async(Dispatchers.IO) { permits.withPermit { release.takeUnless { isReachable(it) } } } }
                    .awaitAll()
                    .filterNotNull()
                    .filterNot { isTolerated(it.distroId) }
                    .map { it.url }
            assertEquals(emptyList<String>(), unreachable, "image URLs that failed HEAD and ranged GET")

            val sumsByUrl =
                releases
                    .filter { it.sumsUrl != null }
                    .groupBy { checkNotNull(it.sumsUrl) }
                    .map { (url, sharing) -> async(Dispatchers.IO) { permits.withPermit { url to fetchSums(url, sharing) } } }
                    .awaitAll()
                    .toMap()
            for (release in releases) {
                val sums = sumsByUrl[release.sumsUrl] ?: continue
                if (release.archiveEntry == null) {
                    assertEquals(release.sha256, sums[release.fileName], "sums for ${release.url}")
                } else {
                    // Archive sums list the zip, sometimes under a version directory.
                    val archiveName = release.url.substringAfterLast('/')
                    val listed = sums.filterKeys { it.substringAfterLast('/') == archiveName }.values.toList()
                    assertEquals(listOf(release.archiveSha256), listed, "sums for ${release.url}")
                }
            }
            assertTrue(
                releases.all { it.sha256 != null || it.archiveSha256 != null },
                "every server/rescue release resolves its sha256 (or its archive's) up front",
            )
            LiveTransients.assertWithinBudget()
        }

    /** Null (with the sharing scrapers recorded) when the sums file stays unavailable for a transient reason. */
    private suspend fun fetchSums(
        url: String,
        sharing: List<Release>,
    ): Map<String, String>? {
        val response =
            try {
                http.get(url)
            } catch (exception: Exception) {
                if (!LiveTransients.isTransient(exception)) throw exception
                sharing.forEach { recordTransient(it, "GET $url: ${exception.message}") }
                return null
            }
        if (LiveTransients.isTransientStatus(response.status)) {
            sharing.forEach { recordTransient(it, "GET $url: HTTP ${response.status}") }
            return null
        }
        check(response.status == 200) { "GET $url: HTTP ${response.status}" }
        return ScraperSupport.parseSha256Sums(response.body)
    }

    /** False when the image is missing, or (recording its scraper) when it stays unreachable for a transient reason. */
    private suspend fun isReachable(release: Release): Boolean {
        var transientReason: String? = null
        for (attempt in 1..LiveTransients.ATTEMPT_COUNT_MAX) {
            if (attempt > 1) delay(LiveTransients.RETRY_DELAY_MS)
            val head = runCatching { plainHttp.head(release.url).status }.getOrNull()
            if (head != null && head in 200..399) return true
            val ranged = runCatching { rangedGetStatus(release.url) }
            val status = ranged.getOrNull()
            val failure = ranged.exceptionOrNull()
            transientReason =
                when {
                    status == 200 || status == 206 -> return true
                    status != null && LiveTransients.isTransientStatus(status) -> "HTTP $status"
                    failure != null && LiveTransients.isTransient(failure) -> failure.message ?: failure.javaClass.simpleName
                    else -> return false
                }
        }
        recordTransient(release, "${release.url}: $transientReason")
        return false
    }

    /**
     * Some mirrors reject HEAD; a one-byte ranged GET proves the object exists. A server that ignores
     * Range answers 200 with the whole image, so the body is never read: the stream is closed at once.
     */
    private suspend fun rangedGetStatus(url: String): Int =
        withContext(Dispatchers.IO) {
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
            response.statusCode()
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
