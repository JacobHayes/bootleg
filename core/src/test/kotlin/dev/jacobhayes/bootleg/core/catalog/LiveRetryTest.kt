package dev.jacobhayes.bootleg.core.catalog

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.SocketException
import java.net.http.HttpTimeoutException

/** The L3 retry helpers themselves, offline. */
class LiveRetryTest {
    private class FlakyScraper(
        val id: String,
        private val failures: List<() -> Nothing>,
    ) : Scraper {
        var calls = 0

        override val distros = listOf(Distro(id, id, Category.RESCUE, "https://example.com"))

        override suspend fun releases(http: Http): List<Release> {
            val failure = failures.getOrNull(calls++)
            failure?.invoke()
            return emptyList()
        }
    }

    private val noHttp =
        object : Http {
            override suspend fun get(url: String): HttpResponse = error("unused")

            override suspend fun head(url: String): HttpResponse = error("unused")
        }

    @Test
    fun `transient errors are recognized by type, status and message`() {
        assertTrue(LiveTransients.isTransient(HttpTimeoutException("slow")))
        assertTrue(LiveTransients.isTransient(IOException("wrapped", SocketException("Connection reset"))))
        assertTrue(LiveTransients.isTransient(IOException("GET https://x/y.meta4 returned HTTP 425")))
        assertTrue(LiveTransients.isTransient(IOException("request timed out after 20000ms: https://x")))
        assertFalse(LiveTransients.isTransient(IllegalArgumentException("unrecognized sums line: x")))
        assertFalse(LiveTransients.isTransient(IOException("GET https://x returned HTTP 404")))
        assertTrue(LiveTransients.isTransientStatus(503) && LiveTransients.isTransientStatus(425) && LiveTransients.isTransientStatus(429))
        assertFalse(LiveTransients.isTransientStatus(404) || LiveTransients.isTransientStatus(200))
    }

    @Test
    fun `a transient failure is retried once`() =
        runBlocking<Unit> {
            val scraper = FlakyScraper("retry-once", listOf { throw IOException("returned HTTP 503") })
            assertEquals(emptyList<Release>(), RetryingScraper(scraper).releases(noHttp))
            assertEquals(2, scraper.calls)
            assertFalse(LiveTransients.isRecorded(scraper.liveName))
        }

    @Test
    fun `a parse error fails at once`() =
        runBlocking<Unit> {
            val scraper = FlakyScraper("parse-error", listOf { throw IllegalStateException("no version found") })
            assertThrows<IllegalStateException> { RetryingScraper(scraper).releases(noHttp) }
            assertEquals(1, scraper.calls)
            assertFalse(LiveTransients.isRecorded(scraper.liveName))
        }

    @Test
    fun `a persistent transient failure is recorded`() =
        runBlocking<Unit> {
            val failure = { throw IOException("returned HTTP 425") }
            val scraper = FlakyScraper("persistent-flake", listOf(failure, failure))
            assertThrows<IOException> { RetryingScraper(scraper).releases(noHttp) }
            assertEquals(2, scraper.calls)
            assertTrue(LiveTransients.isRecorded(scraper.liveName))
        }
}
