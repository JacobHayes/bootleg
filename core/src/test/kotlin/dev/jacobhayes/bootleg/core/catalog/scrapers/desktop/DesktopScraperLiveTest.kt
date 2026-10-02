package dev.jacobhayes.bootleg.core.catalog.scrapers.desktop

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Catalog
import dev.jacobhayes.bootleg.core.catalog.CatalogLimits
import dev.jacobhayes.bootleg.core.catalog.CatalogResult
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.JdkHttp
import dev.jacobhayes.bootleg.core.catalog.LiveTransients
import dev.jacobhayes.bootleg.core.catalog.MemoizingHttp
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.RetryingHttp
import dev.jacobhayes.bootleg.core.catalog.RetryingScraper
import dev.jacobhayes.bootleg.core.catalog.liveName
import dev.jacobhayes.bootleg.core.catalog.resolveSha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/**
 * L3: scrapes every desktop distro live, then checks each image URL answers (HEAD, falling back to a
 * one-byte ranged GET) and each sums file lists the image with the same hash. No image is downloaded.
 */
@Tag("live")
class DesktopScraperLiveTest {
    private data class Checked(
        val release: Release,
        val problems: List<String>,
    )

    private val requiredArches =
        mapOf(
            "ubuntu" to setOf(Arch.X86_64, Arch.ARM64),
            "ubuntu-server" to setOf(Arch.X86_64, Arch.ARM64),
            "debian-netinst" to setOf(Arch.X86_64, Arch.ARM64),
            "fedora" to setOf(Arch.X86_64, Arch.ARM64),
            "opensuse-leap" to setOf(Arch.X86_64, Arch.ARM64),
            "opensuse-tumbleweed" to setOf(Arch.X86_64, Arch.ARM64),
            "nixos" to setOf(Arch.X86_64, Arch.ARM64),
        )

    @TestFactory
    fun `every desktop distro has reachable images with resolvable hashes`(): List<DynamicTest> {
        val http = MemoizingHttp(JdkHttp())
        val (result, checked) =
            runBlocking {
                val result = Catalog(desktopScrapers.map { RetryingScraper(it) }, http).fetchAll()
                result to check(result.releases, RetryingHttp(http))
            }
        val byDistro = checked.groupBy { it.release.distroId }
        return result.distros.map { distro ->
            DynamicTest.dynamicTest(distro.id) {
                if (LiveTransients.isRecorded(scraperNameByDistro.getValue(distro.id))) return@dynamicTest
                assertEquals(emptyList<Any>(), result.errors.filter { distro.id in it.distroIds })
                val releases = byDistro[distro.id].orEmpty()
                assertTrue(releases.isNotEmpty(), "no releases for ${distro.id}")
                val arches = releases.map { it.release.arch }.toSet()
                val missing = requiredArches[distro.id].orEmpty().ifEmpty { setOf(Arch.X86_64) } - arches
                assertTrue(missing.isEmpty(), "${distro.id} has no $missing release")
                assertEquals(emptyList<String>(), releases.flatMap { it.problems })
            }
        } +
            DynamicTest.dynamicTest("catalog has no orphan errors") { assertNoOrphans(result) } +
            DynamicTest.dynamicTest("transient failures stay within budget") { LiveTransients.assertWithinBudget() }
    }

    private val scraperNameByDistro =
        desktopScrapers.flatMap { scraper -> scraper.distros.map { it.id to scraper.liveName } }.toMap()

    private fun assertNoOrphans(result: CatalogResult) {
        val ids = result.distros.map { it.id }.toSet()
        assertTrue(result.errors.all { error -> error.distroIds.any { it in ids } })
    }

    private suspend fun check(
        releases: List<Release>,
        http: Http,
    ): List<Checked> {
        val permits = Semaphore(PROBE_CONCURRENCY_MAX)
        return coroutineScope {
            releases
                .map { release ->
                    async {
                        permits.withPermit {
                            val problems = mutableListOf<String>()
                            val scraperName = scraperNameByDistro.getValue(release.distroId)
                            val probed = probeWithRetry(release.url)
                            val status = probed.getOrNull()
                            val probeFailure = probed.exceptionOrNull()
                            when {
                                status == 200 || status == 206 -> Unit
                                status != null && LiveTransients.isTransientStatus(status) ->
                                    LiveTransients.record(scraperName, "${release.url} answered $status")
                                probeFailure != null && LiveTransients.isTransient(probeFailure) ->
                                    LiveTransients.record(scraperName, "${release.url}: ${probeFailure.message}")
                                else -> problems += "${release.url} answered ${status ?: probeFailure}"
                            }
                            if (release.sumsUrl != null) {
                                val fromSums = runCatching { resolveSha256(release.copy(sha256 = null), http) }
                                val sumsFailure = fromSums.exceptionOrNull()
                                when {
                                    sumsFailure != null && LiveTransients.isTransient(sumsFailure) ->
                                        LiveTransients.record(scraperName, "sums: ${sumsFailure.message}")
                                    sumsFailure != null -> problems += "sums: ${sumsFailure.message}"
                                    release.sha256 != null && fromSums.getOrNull() != release.sha256 ->
                                        problems +=
                                            "${release.fileName}: sums disagree"
                                }
                            }
                            Checked(release, problems)
                        }
                    }
                }.awaitAll()
        }
    }

    /** [probe], tried once more after a transient status or network error. */
    private suspend fun probeWithRetry(url: String): Result<Int> {
        var outcome = runCatching { probe(url) }
        for (attempt in 2..LiveTransients.ATTEMPT_COUNT_MAX) {
            val status = outcome.getOrNull()
            val failure = outcome.exceptionOrNull()
            val transient =
                (status != null && LiveTransients.isTransientStatus(status)) || (failure != null && LiveTransients.isTransient(failure))
            if (!transient) break
            delay(LiveTransients.RETRY_DELAY_MS)
            outcome = runCatching { probe(url) }
        }
        return outcome
    }

    /** HEAD, then a one-byte ranged GET for hosts that reject HEAD. The body is never read. */
    private suspend fun probe(url: String): Int =
        withContext(Dispatchers.IO) {
            withTimeout(CatalogLimits.REQUEST_TIMEOUT_MS) {
                val head = client.send(request(url).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), BodyHandlers.discarding())
                if (head.statusCode() == 200) return@withTimeout 200
                val ranged = client.send(request(url).header("Range", "bytes=0-0").GET().build(), BodyHandlers.ofInputStream())
                ranged.body().close()
                ranged.statusCode()
            }
        }

    private fun request(url: String) =
        HttpRequest
            .newBuilder(URI(url))
            .timeout(Duration.ofMillis(CatalogLimits.REQUEST_TIMEOUT_MS))
            .header("User-Agent", "bootleg-catalog-test/0.1")

    companion object {
        private const val PROBE_CONCURRENCY_MAX = 16
        private val client: HttpClient =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(CatalogLimits.REQUEST_TIMEOUT_MS))
                .build()
    }
}
