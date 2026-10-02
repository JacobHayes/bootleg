package dev.jacobhayes.bootleg.core.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ConcurrentHashMap

/**
 * L3 tolerance for upstream flakiness (timeouts, resets, 5xx, 425, 429). Scrapers and requests are
 * retried once; a scraper that still fails transiently is recorded here and its checks are skipped,
 * but at most [TRANSIENT_SCRAPERS_MAX] per run (both live test classes share this JVM-wide record).
 * Parse, format and validation errors are never transient.
 */
object LiveTransients {
    const val TRANSIENT_SCRAPERS_MAX = 1
    const val ATTEMPT_COUNT_MAX = 2
    const val RETRY_DELAY_MS = 1_000L
    private const val CAUSE_DEPTH_MAX = 8

    private val TRANSIENT_MESSAGE =
        Regex("""HTTP (5\d\d|425|429)\b|timed out|connection reset|GOAWAY|received no bytes|rate limit""", RegexOption.IGNORE_CASE)

    private val failures = ConcurrentHashMap<String, String>()

    fun isTransientStatus(status: Int): Boolean = status in 500..599 || status == 425 || status == 429

    fun isTransient(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(CAUSE_DEPTH_MAX).any { cause ->
            cause is TimeoutCancellationException ||
                cause is HttpTimeoutException ||
                cause is ConnectException ||
                cause is SocketException ||
                cause is SocketTimeoutException ||
                cause is EOFException ||
                cause is ClosedChannelException ||
                cause.message?.let(TRANSIENT_MESSAGE::containsMatchIn) == true
        }

    /** Records that [scraperName] failed transiently even after its retry. */
    fun record(
        scraperName: String,
        reason: String,
    ) {
        if (failures.putIfAbsent(scraperName, reason) == null) {
            println("WARN L3: $scraperName failed transiently after a retry, its checks are skipped: $reason")
        }
    }

    fun isRecorded(scraperName: String): Boolean = failures.containsKey(scraperName)

    /** Fails once more than [TRANSIENT_SCRAPERS_MAX] scrapers have failed transiently in this run. */
    fun assertWithinBudget() {
        val snapshot = failures.toSortedMap()
        check(snapshot.size <= TRANSIENT_SCRAPERS_MAX) {
            "${snapshot.size} scrapers failed transiently (at most $TRANSIENT_SCRAPERS_MAX allowed): $snapshot"
        }
    }
}

/** The name [LiveTransients] records a scraper (and its distros' checks) under. */
val Scraper.liveName: String get() = distros.joinToString("+") { it.id }

/**
 * Retries [delegate] once when an attempt fails transiently (by exception, or because a request in
 * it saw a transient status or network error). Each attempt has its own deadline, and all attempts
 * together stay within [budgetMs] so a hung upstream can't stretch L3 much past its usual time.
 */
class RetryingScraper(
    private val delegate: Scraper,
    private val attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS,
    private val budgetMs: Long = BUDGET_MS,
) : Scraper {
    init {
        require(attemptTimeoutMs in 1..budgetMs) { "attempt timeout must be positive and within the budget" }
    }

    override val distros: List<Distro> get() = delegate.distros

    override suspend fun releases(http: Http): List<Release> {
        val startedNs = System.nanoTime()
        var lastFailure: Throwable? = null
        for (attempt in 1..LiveTransients.ATTEMPT_COUNT_MAX) {
            if (attempt > 1) {
                delay(LiveTransients.RETRY_DELAY_MS)
                if (budgetMs - elapsedMs(startedNs) < RETRY_ATTEMPT_MIN_MS) break
            }
            val timeoutMs = minOf(attemptTimeoutMs, budgetMs - elapsedMs(startedNs))
            val tracking = TrackingHttp(http)
            try {
                return withTimeout(timeoutMs) { delegate.releases(tracking) }
            } catch (timeout: TimeoutCancellationException) {
                // An outer deadline propagates as-is; only this attempt's own deadline is retried.
                currentCoroutineContext().ensureActive()
                lastFailure = IOException("attempt timed out after ${timeoutMs}ms", timeout)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                if (!LiveTransients.isTransient(exception) && tracking.transientSeen == null) throw exception
                lastFailure = exception
            }
        }
        val reason = lastFailure?.message ?: lastFailure?.javaClass?.simpleName
        LiveTransients.record(liveName, reason.orEmpty())
        throw IOException("transient failure, retried once: $reason", lastFailure)
    }

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000

    /** Remembers whether any request saw a transient status or network error. */
    private class TrackingHttp(
        private val delegate: Http,
    ) : Http {
        @Volatile var transientSeen: String? = null

        override suspend fun get(url: String): HttpResponse = track(url) { delegate.get(url) }

        override suspend fun head(url: String): HttpResponse = track(url) { delegate.head(url) }

        private suspend fun track(
            url: String,
            request: suspend () -> HttpResponse,
        ): HttpResponse {
            val response =
                try {
                    request()
                } catch (exception: Exception) {
                    if (exception !is CancellationException &&
                        LiveTransients.isTransient(exception)
                    ) {
                        transientSeen = "$url: ${exception.message}"
                    }
                    throw exception
                }
            if (LiveTransients.isTransientStatus(response.status)) transientSeen = "$url: HTTP ${response.status}"
            return response
        }
    }

    companion object {
        /** Matches [CatalogLimits.REQUEST_TIMEOUT_MS]: one hung request uses up an attempt. */
        const val ATTEMPT_TIMEOUT_MS = 20_000L

        /** Both attempts together; keeps L3 near its ~45s budget even when an upstream hangs. */
        const val BUDGET_MS = 32_000L

        /** A retry with less time than this left is not worth starting. */
        const val RETRY_ATTEMPT_MIN_MS = 5_000L
    }
}

/** Retries a request once on a transient status or network error, then returns or throws what the retry got. */
class RetryingHttp(
    private val delegate: Http,
) : Http {
    override suspend fun get(url: String): HttpResponse = retrying { delegate.get(url) }

    override suspend fun head(url: String): HttpResponse = retrying { delegate.head(url) }

    private suspend fun retrying(request: suspend () -> HttpResponse): HttpResponse {
        for (attempt in 1 until LiveTransients.ATTEMPT_COUNT_MAX) {
            val response =
                try {
                    request()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    if (!LiveTransients.isTransient(exception)) throw exception
                    null
                }
            if (response != null && !LiveTransients.isTransientStatus(response.status)) return response
            delay(LiveTransients.RETRY_DELAY_MS)
        }
        return request()
    }
}

/**
 * Fetches each URL once even when many releases (and the scraper itself) share it, which also keeps
 * L3 from hammering upstreams that throttle repeats. Transient failures aren't kept, so a retry refetches.
 */
class MemoizingHttp(
    private val delegate: Http,
) : Http {
    private val responses = ConcurrentHashMap<String, CompletableDeferred<HttpResponse>>()

    override suspend fun get(url: String): HttpResponse {
        val fresh = CompletableDeferred<HttpResponse>()
        val existing = responses.putIfAbsent(url, fresh)
        if (existing != null) return existing.await()
        val outcome = runCatching { delegate.get(url) }
        val failure = outcome.exceptionOrNull()
        val keep =
            if (failure == null) {
                !LiveTransients.isTransientStatus(outcome.getOrThrow().status)
            } else {
                failure !is CancellationException && !LiveTransients.isTransient(failure)
            }
        if (!keep) responses.remove(url, fresh)
        outcome.fold({ fresh.complete(it) }, { fresh.completeExceptionally(it) })
        return fresh.await()
    }

    override suspend fun head(url: String): HttpResponse = delegate.head(url)
}
