package dev.jacobhayes.bootleg.core.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import kotlin.time.TimeSource

/** One scraper's failure. The other scrapers' results are unaffected. */
data class ScraperError(
    val distroIds: List<String>,
    val message: String,
)

data class CatalogResult(
    val distros: List<Distro>,
    val releases: List<Release>,
    val errors: List<ScraperError>,
)

/** One scraper and the distros it fills. Fetched, cached, failed and retried as a unit. */
data class Feed(
    /** The id of its first distro ("ubuntu" for Ubuntu + Ubuntu Server). */
    val id: String,
    val distros: List<Distro>,
)

sealed interface FeedResult {
    /** Valid releases (possibly none); [rejected] says why any others were dropped. */
    data class Fetched(
        val releases: List<Release>,
        val rejected: List<String>,
    ) : FeedResult

    data class Failed(
        val message: String,
    ) : FeedResult
}

/**
 * Fetches feeds (one per scraper) independently: at most [CatalogLimits.FEED_CONCURRENCY_MAX] at
 * once, each with its own request cap ([CatalogLimits.REQUESTS_PER_FEED_MAX]), per-request timeout
 * and overall deadline, which starts only once the feed has a slot. A feed's failure, timeout or
 * size problem is its own [FeedResult.Failed] and never touches another feed. Cancelling the caller
 * cancels the fetch and is never reported as a failure.
 */
class Catalog(
    scrapers: List<Scraper>,
    private val http: Http,
    private val feedTimeoutMs: Long = CatalogLimits.FEED_TIMEOUT_MS,
    private val requestTimeoutMs: Long = CatalogLimits.REQUEST_TIMEOUT_MS,
) {
    private val feedPermits = Semaphore(CatalogLimits.FEED_CONCURRENCY_MAX)

    val feeds: List<Feed>

    /** Every distro, in display order (see [sortedForDisplay]). */
    val distros: List<Distro>

    private val scrapersByFeedId: Map<String, Scraper>
    private val feedsByDistroId: Map<String, Feed>

    init {
        require(feedTimeoutMs > 0 && requestTimeoutMs > 0) { "timeouts must be positive" }
        require(scrapers.size <= SCRAPERS_MAX) { "too many scrapers: ${scrapers.size}" }
        require(scrapers.all { it.distros.isNotEmpty() }) { "every scraper must declare a distro" }
        val ids = scrapers.flatMap { scraper -> scraper.distros.map { it.id } }
        require(ids.size == ids.toSet().size) { "duplicate distro ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}" }
        feeds = scrapers.map { Feed(it.distros.first().id, it.distros) }
        scrapersByFeedId = feeds.zip(scrapers).associate { (feed, scraper) -> feed.id to scraper }
        feedsByDistroId = feeds.flatMap { feed -> feed.distros.map { it.id to feed } }.toMap()
        distros = sortedForDisplay(ids.map { id -> feedsByDistroId.getValue(id).distros.first { it.id == id } })
    }

    /** The feed that fills [distroId], or null if no scraper declares it. */
    fun feedOf(distroId: String): Feed? = feedsByDistroId[distroId]

    /** Fetches one feed. Waits for a feed slot first; the deadline covers only the fetch itself. */
    suspend fun fetch(feedId: String): FeedResult {
        val scraper = requireNotNull(scrapersByFeedId[feedId]) { "unknown feed $feedId" }
        return feedPermits.withPermit { fetchNow(scraper) }
    }

    /** Every feed concurrently (still [CatalogLimits.FEED_CONCURRENCY_MAX] at a time), merged. */
    suspend fun fetchAll(): CatalogResult {
        val results = coroutineScope { feeds.map { feed -> async { feed to fetch(feed.id) } }.awaitAll() }
        val releases = mutableListOf<Release>()
        val errors = mutableListOf<ScraperError>()
        for ((feed, result) in results) {
            val distroIds = feed.distros.map { it.id }
            when (result) {
                is FeedResult.Fetched -> {
                    releases += result.releases
                    errors += result.rejected.map { ScraperError(distroIds, it) }
                }
                is FeedResult.Failed -> errors += ScraperError(distroIds, result.message)
            }
        }
        return CatalogResult(feeds.flatMap { it.distros }, releases, errors)
    }

    private suspend fun fetchNow(scraper: Scraper): FeedResult {
        val distroIds = scraper.distros.map { it.id }.toSet()
        val scraped =
            try {
                withDeadline(feedTimeoutMs, { "timed out after ${formatSeconds(feedTimeoutMs)}" }) {
                    scraper.releases(LimitedHttp(http, requestTimeoutMs))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                // withDeadline lets an error through only while the caller is active: the feed's own failure.
                return FeedResult.Failed(describe(exception))
            }
        if (scraped.size > RELEASES_PER_SCRAPER_MAX) return FeedResult.Failed("too many releases: ${scraped.size}")
        val rejected = mutableListOf<String>()
        val seenUrls = HashSet<String>()
        val seenKeys = HashSet<List<Any>>()
        val valid =
            scraped.filter { release ->
                val problem = validate(release, distroIds)
                if (problem != null) rejected += "invalid release ${release.url}: $problem"
                val key = listOf(release.distroId, release.version, release.arch, release.variant)
                problem == null && seenUrls.add(release.url) and seenKeys.add(key)
            }
        // Releases but none usable means the upstream format changed under us: a failure, not "no releases".
        if (valid.isEmpty() && rejected.isNotEmpty()) return FeedResult.Failed(rejected.first().take(ERROR_LENGTH_MAX))
        return FeedResult.Fetched(valid, rejected)
    }

    companion object {
        const val SCRAPERS_MAX = 64
        const val RELEASES_PER_SCRAPER_MAX = 200
        const val FILE_NAME_LENGTH_MAX = 255
        const val ERROR_LENGTH_MAX = 200

        /** Returns why [release] is unsafe or malformed, or null if it is valid. */
        fun validate(
            release: Release,
            distroIds: Set<String>,
        ): String? =
            when {
                release.distroId !in distroIds -> "distro ${release.distroId} not declared by its scraper"
                release.version.isBlank() -> "blank version"
                !isHttpsUrl(release.url) -> "image URL must be https"
                release.sumsUrl != null && !isHttpsUrl(release.sumsUrl) -> "sums URL must be https"
                release.sha256 != null && !ScraperSupport.isSha256(release.sha256) -> "sha256 must be 64 lowercase hex chars"
                release.sha256 == null && release.sumsUrl == null -> "needs a sha256 or a sums URL"
                !isSafeFileName(release.fileName) -> "unsafe file name ${release.fileName}"
                release.sizeBytes != null && release.sizeBytes <= 0 -> "non-positive size"
                release.supportedUntil != null && runCatching { LocalDate.parse(release.supportedUntil) }.isFailure ->
                    "supportedUntil is not an ISO date"
                else -> null
            }

        private fun isHttpsUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null
        }

        /** Category order (Desktop, Server, Rescue), then name case-insensitively, then id. */
        fun sortedForDisplay(distros: List<Distro>): List<Distro> =
            distros.sortedWith(compareBy<Distro>({ it.category.ordinal }, { it.name.lowercase() }, { it.id }))

        fun isSafeFileName(name: String): Boolean =
            name.isNotEmpty() &&
                name.length <= FILE_NAME_LENGTH_MAX &&
                !name.startsWith(".") &&
                !name.contains("..") &&
                name.none { it == '/' || it == '\\' || it.isISOControl() }
    }
}

/**
 * Looks up [release]'s hash: the up-front [Release.sha256] if set, otherwise its entry in the sums
 * file. Returns null only when the release has neither. Throws if the sums file can't be fetched,
 * can't be parsed, or doesn't list the image.
 */
suspend fun resolveSha256(
    release: Release,
    http: Http,
): String? {
    release.sha256?.let { return it }
    val sumsUrl = release.sumsUrl ?: return null
    val sums = ScraperSupport.parseSha256Sums(http.getText(sumsUrl))
    val hash =
        sums[release.fileName]
            ?: sums[ScraperSupport.fileNameOf(release.url)]
            ?: throw IllegalStateException("${release.fileName} is not listed in $sumsUrl")
    check(ScraperSupport.isSha256(hash)) { "bad hash for ${release.fileName} in $sumsUrl" }
    return hash
}

/** One feed's view of the network: caps its in-flight requests, times out each one, and bounds response size. */
private class LimitedHttp(
    private val delegate: Http,
    private val requestTimeoutMs: Long,
) : Http {
    private val permits = Semaphore(CatalogLimits.REQUESTS_PER_FEED_MAX)

    override suspend fun get(url: String): HttpResponse = limited(url) { delegate.get(url) }

    override suspend fun head(url: String): HttpResponse = limited(url) { delegate.head(url) }

    private suspend fun limited(
        url: String,
        request: suspend () -> HttpResponse,
    ): HttpResponse {
        val response =
            permits.withPermit {
                withDeadline(requestTimeoutMs, { "request timed out after ${formatSeconds(requestTimeoutMs)}: $url" }) { request() }
            }
        // The delegate enforces the byte limit while reading (see [Http]); this re-checks its result.
        check(utf8ByteCount(response.body) <= CatalogLimits.RESPONSE_BYTES_MAX) { "response too large: $url" }
        return response
    }
}

/**
 * Runs [block] with a deadline of [timeoutMs]:
 * - the caller cancelled: rethrows its [CancellationException], whatever [block] ended with (a
 *   cancelled OkHttp call ends with IOException("Canceled"), which is not a failure);
 * - the deadline passed: throws [IOException] with [timeoutMessage], whatever [block] ended with;
 * - otherwise [block]'s own error, a stray [CancellationException] from inside it becoming an
 *   [IOException], so callers can never mistake it for their own cancellation.
 */
internal suspend fun <T : Any> withDeadline(
    timeoutMs: Long,
    timeoutMessage: () -> String,
    block: suspend () -> T,
): T {
    require(timeoutMs > 0) { "timeout must be positive" }
    val started = TimeSource.Monotonic.markNow()
    val result =
        try {
            withTimeoutOrNull(timeoutMs) { block() }
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            if (started.elapsedNow().inWholeMilliseconds >= timeoutMs) throw IOException(timeoutMessage(), exception)
            if (exception is CancellationException) throw IOException("cancelled inside the fetch: ${exception.message}", exception)
            throw exception
        }
    currentCoroutineContext().ensureActive()
    return result ?: throw IOException(timeoutMessage())
}

private fun formatSeconds(milliseconds: Long): String = if (milliseconds % 1_000 == 0L) "${milliseconds / 1_000}s" else "${milliseconds}ms"

/** A short, user-facing description of [exception]. */
internal fun describe(exception: Exception): String =
    (exception.message?.takeIf { it.isNotBlank() } ?: exception::class.simpleName ?: "unknown error").take(Catalog.ERROR_LENGTH_MAX)

/** UTF-8 encoded size of [text], without allocating the encoding. Unpaired surrogates count as 3 bytes (an upper bound). */
internal fun utf8ByteCount(text: String): Long {
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val char = text[index]
        bytes +=
            when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }
                else -> 3
            }
        index++
    }
    return bytes
}
