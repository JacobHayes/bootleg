package dev.jacobhayes.bootleg.core.catalog

import dev.jacobhayes.bootleg.core.catalog.scrapers.desktop.desktopScrapers
import dev.jacobhayes.bootleg.core.catalog.scrapers.serverAndRescueScrapers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a refresh spends its time, per feed: each scraper runs alone against the live network
 * (requests timed and sized), then again against an in-memory replay of the same responses, which
 * isolates parsing. Writes `build/catalog-timing.tsv` and the URLs to `build/catalog-timing-urls.txt`.
 * Run with `./gradlew :core:test -Ptiming --tests '*CatalogTimingLiveTest*'`.
 */
@Tag("timing")
class CatalogTimingLiveTest {
    private class Exchange(
        val url: String,
        val elapsedMs: Long,
        val bytes: Long,
        val failure: String?,
    )

    /** Times every request; refuses more than [REQUESTS_PER_FEED_MAX] so a runaway scraper can't run on. */
    private class RecordingHttp(
        private val delegate: Http,
    ) : Http {
        val exchanges: MutableList<Exchange> = Collections.synchronizedList(mutableListOf())
        val responses: MutableMap<String, HttpResponse> = Collections.synchronizedMap(mutableMapOf())
        private val requestCount = AtomicInteger()

        override suspend fun get(url: String): HttpResponse = timed(url) { delegate.get(url) }

        override suspend fun head(url: String): HttpResponse = timed(url) { delegate.head(url) }

        private suspend fun timed(
            url: String,
            request: suspend () -> HttpResponse,
        ): HttpResponse {
            if (requestCount.incrementAndGet() > REQUESTS_PER_FEED_MAX) throw IOException("more than $REQUESTS_PER_FEED_MAX requests: $url")
            val startNs = System.nanoTime()
            try {
                val response = request()
                exchanges += Exchange(url, (System.nanoTime() - startNs) / 1_000_000, utf8ByteCount(response.body), null)
                responses[url] = response
                return response
            } catch (error: Exception) {
                exchanges += Exchange(url, (System.nanoTime() - startNs) / 1_000_000, 0, error.toString())
                throw error
            }
        }
    }

    private class ReplayHttp(
        private val responses: Map<String, HttpResponse>,
    ) : Http {
        override suspend fun get(url: String): HttpResponse = responses[url] ?: throw IOException("not recorded: $url")

        override suspend fun head(url: String): HttpResponse = get(url)
    }

    @Test
    fun `time every feed`() {
        val rows = mutableListOf("feed\twall_ms\trequests\tnetwork_sum_ms\tnetwork_max_ms\tbytes\tparse_ms\treleases\tfailure")
        val urls = mutableListOf<String>()
        for (scraper in desktopScrapers + serverAndRescueScrapers) {
            val http = RecordingHttp(JdkHttp())
            val startNs = System.nanoTime()
            // The app's per-feed deadline: a stuck upstream is that feed's failure, not a hung run.
            val outcome = runCatching { runBlocking { withTimeout(CatalogLimits.FEED_TIMEOUT_MS) { scraper.releases(http) } } }
            val wallMs = (System.nanoTime() - startNs) / 1_000_000
            val replay = ReplayHttp(http.responses.toMap())
            val parseMs =
                (1..PARSE_RUNS).minOf {
                    val parseStartNs = System.nanoTime()
                    runCatching { runBlocking { scraper.releases(replay) } }
                    (System.nanoTime() - parseStartNs) / 1_000
                } / 1_000.0
            val exchanges = http.exchanges.toList()
            urls += exchanges.map { it.url }
            val failure = outcome.exceptionOrNull()?.toString() ?: exchanges.firstNotNullOfOrNull { it.failure }
            rows +=
                listOf(
                    scraper.distros.joinToString("+") { it.id },
                    wallMs,
                    exchanges.size,
                    exchanges.sumOf { it.elapsedMs },
                    exchanges.maxOfOrNull { it.elapsedMs } ?: 0,
                    exchanges.sumOf { it.bytes },
                    "%.1f".format(parseMs),
                    outcome.getOrNull()?.size ?: 0,
                    failure.orEmpty().replace('\t', ' ').take(FAILURE_LENGTH_MAX),
                ).joinToString("\t")
            println(rows.last())
        }
        Files.createDirectories(Path.of("build"))
        Files.write(Path.of("build/catalog-timing.tsv"), rows)
        Files.write(Path.of("build/catalog-timing-urls.txt"), urls.distinct())
    }

    @Test
    fun `time the whole catalog as the app fetches it`() {
        val scrapers = desktopScrapers + serverAndRescueScrapers
        val catalog = Catalog(scrapers, JdkHttp())
        val startNs = System.nanoTime()
        val rows =
            runBlocking {
                // Catalog.fetch bounds each feed itself (CatalogLimits.FEED_TIMEOUT_MS once it has a slot); this bounds the run.
                withTimeout(wholeCatalogTimeoutMs(catalog.feeds.size)) {
                    catalog.feeds
                        .map { feed ->
                            async {
                                val result = catalog.fetch(feed.id)
                                val doneMs = (System.nanoTime() - startNs) / 1_000_000
                                "${feed.id}\t$doneMs\t${if (result is FeedResult.Failed) result.message else "ok"}"
                            }
                        }.awaitAll()
                }
            }
        val totalMs = (System.nanoTime() - startNs) / 1_000_000
        val lines = listOf("feed\tdone_after_ms\tresult") + rows.sortedBy { it.split('\t')[1].toLong() } + "all\t$totalMs\t"
        lines.forEach(::println)
        Files.createDirectories(Path.of("build"))
        Files.write(Path.of("build/catalog-timing-all.tsv"), lines)
    }

    private companion object {
        const val PARSE_RUNS = 5
        const val FAILURE_LENGTH_MAX = 160

        /** Well above any scraper's real request count (the most is a few dozen). */
        const val REQUESTS_PER_FEED_MAX = 200

        /** Every feed, FEED_CONCURRENCY_MAX at a time, each within its deadline, plus one deadline of slack. */
        fun wholeCatalogTimeoutMs(feedCount: Int): Long =
            CatalogLimits.FEED_TIMEOUT_MS * ((feedCount + CatalogLimits.FEED_CONCURRENCY_MAX - 1) / CatalogLimits.FEED_CONCURRENCY_MAX + 1)
    }
}
