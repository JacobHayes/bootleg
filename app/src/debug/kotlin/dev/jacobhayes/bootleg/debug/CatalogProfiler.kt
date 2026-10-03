package dev.jacobhayes.bootleg.debug

import dev.jacobhayes.bootleg.catalog.OkHttpHttp
import dev.jacobhayes.bootleg.core.catalog.CatalogLimits
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.HttpResponse
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Debug-only: where a catalog refresh spends its time on the phone. Runs each scraper alone over a
 * fresh OkHttp client (no warm connections) with per-call DNS/connect/TLS/first-byte/transfer
 * timings, then against an in-memory replay of the same responses to time parsing alone.
 */
object CatalogProfiler {
    private const val PARSE_RUNS = 3

    /** All feeds' live fetches together, well inside the debug provider's 150 s command deadline. */
    const val PROFILE_BUDGET_MS = 120_000L

    private class CallTiming(
        val url: String,
    ) {
        val startNs = System.nanoTime()
        var dnsMs = 0L
        var connectMs = 0L
        var tlsMs = 0L
        var firstByteMs = 0L
        var totalMs = 0L
        var bytes = 0L
        var failure: String? = null
        var reusedConnection = true
        private var dnsStartNs = 0L
        private var connectStartNs = 0L
        private var tlsStartNs = 0L

        fun listener() =
            object : EventListener() {
                override fun dnsStart(
                    call: Call,
                    domainName: String,
                ) {
                    dnsStartNs = System.nanoTime()
                }

                override fun dnsEnd(
                    call: Call,
                    domainName: String,
                    inetAddressList: List<InetAddress>,
                ) {
                    dnsMs += elapsedMs(dnsStartNs)
                }

                override fun connectStart(
                    call: Call,
                    inetSocketAddress: InetSocketAddress,
                    proxy: Proxy,
                ) {
                    reusedConnection = false
                    connectStartNs = System.nanoTime()
                }

                override fun secureConnectStart(call: Call) {
                    tlsStartNs = System.nanoTime()
                }

                override fun secureConnectEnd(
                    call: Call,
                    handshake: okhttp3.Handshake?,
                ) {
                    tlsMs += elapsedMs(tlsStartNs)
                }

                override fun connectEnd(
                    call: Call,
                    inetSocketAddress: InetSocketAddress,
                    proxy: Proxy,
                    protocol: Protocol?,
                ) {
                    connectMs += elapsedMs(connectStartNs)
                }

                override fun responseHeadersStart(call: Call) {
                    if (firstByteMs == 0L) firstByteMs = elapsedMs(startNs)
                }

                override fun responseBodyEnd(
                    call: Call,
                    byteCount: Long,
                ) {
                    bytes += byteCount
                }

                override fun callEnd(call: Call) {
                    totalMs = elapsedMs(startNs)
                }

                override fun callFailed(
                    call: Call,
                    ioe: IOException,
                ) {
                    totalMs = elapsedMs(startNs)
                    failure = ioe.toString()
                }
            }
    }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    /**
     * A scraper's failure as a result; cancellation of the caller is rethrown, never recorded. A feed
     * that ran past its deadline ([TimeoutCancellationException]) is the feed's own failure.
     */
    private suspend fun <T> catchingFailure(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (timeout: TimeoutCancellationException) {
            // The feed's own deadline only while the caller is still running; otherwise the caller was cancelled.
            currentCoroutineContext().ensureActive()
            Result.failure(timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    private class Replay(
        private val responses: Map<String, HttpResponse>,
    ) : Http {
        override suspend fun get(url: String) = responses[url] ?: throw IOException("not recorded: $url")

        override suspend fun head(url: String) = get(url)
    }

    private class Recording(
        private val delegate: Http,
    ) : Http {
        val responses = ConcurrentHashMap<String, HttpResponse>()

        override suspend fun get(url: String) = delegate.get(url).also { responses[url] = it }

        override suspend fun head(url: String) = delegate.head(url)
    }

    suspend fun profile(
        scrapers: List<Scraper>,
        budgetMs: Long = PROFILE_BUDGET_MS,
    ): JsonObject {
        require(budgetMs > 0) { "budgetMs must be positive, got $budgetMs" }
        val rows = ConcurrentLinkedQueue<JsonObject>()
        val deadlineNs = System.nanoTime() + budgetMs * 1_000_000
        for (scraper in scrapers) {
            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) {
                rows +=
                    buildJsonObject {
                        put("feed", scraper.distros.joinToString("+") { it.id })
                        put("failure", "skipped: the ${budgetMs}ms profile budget is spent")
                    }
                continue
            }
            val calls = ConcurrentLinkedQueue<CallTiming>()
            val client =
                OkHttpHttp
                    .baseClient()
                    .newBuilder()
                    .eventListenerFactory { call -> CallTiming(call.request().url.toString()).also(calls::add).listener() }
                    .build()
            val outcome: Result<List<Release>>
            val wallMs: Long
            val parseUs: Long
            try {
                val http = Recording(OkHttpHttp(client))
                val startNs = System.nanoTime()
                // The app's own per-feed deadline (or what's left of the budget), so one stuck upstream can't hold the call.
                val feedTimeoutMs = minOf(CatalogLimits.FEED_TIMEOUT_MS, remainingMs)
                outcome = catchingFailure { withTimeout(feedTimeoutMs) { scraper.releases(http) } }
                wallMs = elapsedMs(startNs)
                val replay = Replay(http.responses.toMap())
                // Replays stop once the budget is spent (at least one runs, so every fetched feed has a parse time).
                var bestUs = Long.MAX_VALUE
                for (run in 1..PARSE_RUNS) {
                    if (run > 1 && System.nanoTime() >= deadlineNs) break
                    val parseStartNs = System.nanoTime()
                    catchingFailure { scraper.releases(replay) }
                    bestUs = minOf(bestUs, (System.nanoTime() - parseStartNs) / 1_000)
                }
                parseUs = bestUs
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
            rows +=
                buildJsonObject {
                    put("feed", scraper.distros.joinToString("+") { it.id })
                    put("wallMs", wallMs)
                    put("parseMs", parseUs / 1_000.0)
                    put("releases", outcome.getOrNull()?.size ?: 0)
                    put("failure", outcome.exceptionOrNull()?.toString())
                    putJsonArray("calls") {
                        calls.forEach { timing ->
                            addJsonObject {
                                put("url", timing.url)
                                put("dnsMs", timing.dnsMs)
                                put("connectMs", timing.connectMs)
                                put("tlsMs", timing.tlsMs)
                                put("firstByteMs", timing.firstByteMs)
                                put("totalMs", timing.totalMs)
                                put("bytes", timing.bytes)
                                put("reused", timing.reusedConnection)
                                timing.failure?.let { put("failure", it) }
                            }
                        }
                    }
                }
        }
        return buildJsonObject { putJsonArray("feeds") { rows.forEach { add(it) } } }
    }
}
