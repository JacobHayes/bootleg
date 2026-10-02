package dev.jacobhayes.bootleg.core.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.URI
import java.time.LocalDate

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

/**
 * Runs every scraper concurrently. All scrapers share one [Http] wrapper that caps in-flight
 * requests at [CatalogLimits.FETCH_CONCURRENCY_MAX] and times out each request, so scrapers may fan
 * out freely. Each scraper also has an overall deadline, and its failure is recorded, not thrown.
 */
class Catalog(
    private val scrapers: List<Scraper>,
    http: Http,
    private val scraperTimeoutMs: Long = SCRAPER_TIMEOUT_MS,
    requestTimeoutMs: Long = CatalogLimits.REQUEST_TIMEOUT_MS,
) {
    private val http: Http = LimitedHttp(http, requestTimeoutMs)

    init {
        require(scraperTimeoutMs > 0 && requestTimeoutMs > 0) { "timeouts must be positive" }
        require(scrapers.size <= SCRAPERS_MAX) { "too many scrapers: ${scrapers.size}" }
        val ids = scrapers.flatMap { scraper -> scraper.distros.map { it.id } }
        require(ids.size == ids.toSet().size) { "duplicate distro ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}" }
    }

    suspend fun fetchAll(): CatalogResult {
        val outcomes = coroutineScope { scrapers.map { scraper -> async { fetchOne(scraper) } }.awaitAll() }
        val seenUrls = HashSet<String>()
        val seenKeys = HashSet<List<Any>>()
        val releases =
            outcomes.flatMap { it.releases }.filter { release ->
                val key = listOf(release.distroId, release.version, release.arch, release.variant)
                seenUrls.add(release.url) and seenKeys.add(key)
            }
        return CatalogResult(
            distros = scrapers.flatMap { it.distros },
            releases = releases,
            errors = outcomes.flatMap { it.errors },
        )
    }

    private class Outcome(
        val releases: List<Release>,
        val errors: List<ScraperError>,
    )

    private suspend fun fetchOne(scraper: Scraper): Outcome {
        val distroIds = scraper.distros.map { it.id }
        val scraped =
            try {
                withTimeout(scraperTimeoutMs) { scraper.releases(http) }
            } catch (timeout: TimeoutCancellationException) {
                return Outcome(emptyList(), listOf(ScraperError(distroIds, "timed out after ${scraperTimeoutMs}ms")))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                val message = exception.message ?: exception::class.simpleName ?: "unknown error"
                return Outcome(emptyList(), listOf(ScraperError(distroIds, message)))
            }
        if (scraped.size > RELEASES_PER_SCRAPER_MAX) {
            return Outcome(emptyList(), listOf(ScraperError(distroIds, "too many releases: ${scraped.size}")))
        }
        val errors = mutableListOf<ScraperError>()
        val valid =
            scraped.filter { release ->
                val problem = validate(release, distroIds.toSet())
                if (problem != null) errors += ScraperError(distroIds, "invalid release ${release.url}: $problem")
                problem == null
            }
        return Outcome(valid, errors)
    }

    companion object {
        const val SCRAPER_TIMEOUT_MS = 60_000L
        const val SCRAPERS_MAX = 64
        const val RELEASES_PER_SCRAPER_MAX = 200
        const val FILE_NAME_LENGTH_MAX = 255

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

/** Caps concurrent requests across all scrapers, times out each one, and bounds response size. */
private class LimitedHttp(
    private val delegate: Http,
    private val requestTimeoutMs: Long,
) : Http {
    private val permits = Semaphore(CatalogLimits.FETCH_CONCURRENCY_MAX)

    override suspend fun get(url: String): HttpResponse = limited(url) { delegate.get(url) }

    override suspend fun head(url: String): HttpResponse = limited(url) { delegate.head(url) }

    private suspend fun limited(
        url: String,
        request: suspend () -> HttpResponse,
    ): HttpResponse {
        val response =
            permits.withPermit {
                try {
                    withTimeout(requestTimeoutMs) { request() }
                } catch (timeout: TimeoutCancellationException) {
                    // An outer deadline (the scraper's) propagates as-is; this request's own timeout becomes
                    // a plain failure of this scraper.
                    currentCoroutineContext().ensureActive()
                    throw IOException("request timed out after ${requestTimeoutMs}ms: $url", timeout)
                }
            }
        // The delegate enforces the byte limit while reading (see [Http]); this re-checks its result.
        check(utf8ByteCount(response.body) <= CatalogLimits.RESPONSE_BYTES_MAX) { "response too large: $url" }
        return response
    }
}

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
