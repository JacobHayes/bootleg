package dev.jacobhayes.bootleg.download

import dev.jacobhayes.bootleg.download.ResumePlanner.Action
import dev.jacobhayes.bootleg.download.ResumePlanner.ContentRange
import dev.jacobhayes.bootleg.download.ResumePlanner.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResumePlannerTest {
    @Test
    fun `no partial sends no range headers`() {
        assertEquals(emptyMap<String, String>(), ResumePlanner.requestHeaders(0, Validator("\"abc\"", "Thu, 01 Oct 2026 11:06:09 GMT")))
    }

    @Test
    fun `partial with strong etag uses it for If-Range`() {
        val headers = ResumePlanner.requestHeaders(1000, Validator("\"abc\"", "Thu, 01 Oct 2026 11:06:09 GMT"))
        assertEquals(mapOf("Range" to "bytes=1000-", "If-Range" to "\"abc\""), headers)
    }

    @Test
    fun `weak etag falls back to Last-Modified`() {
        val headers = ResumePlanner.requestHeaders(5, Validator("W/\"abc\"", "Thu, 01 Oct 2026 11:06:09 GMT"))
        assertEquals("Thu, 01 Oct 2026 11:06:09 GMT", headers["If-Range"])
    }

    @Test
    fun `no validator still sends Range without If-Range`() {
        assertEquals(mapOf("Range" to "bytes=5-"), ResumePlanner.requestHeaders(5, null))
        assertEquals(mapOf("Range" to "bytes=5-"), ResumePlanner.requestHeaders(5, Validator("W/\"x\"", null)))
    }

    @Test
    fun `negative partial size is rejected`() {
        assertThrows<IllegalArgumentException> { ResumePlanner.requestHeaders(-1, null) }
        assertThrows<IllegalArgumentException> { ResumePlanner.decide(-1, 200, null, null) }
    }

    @Test
    fun `206 at the requested offset appends`() {
        assertEquals(Action.Append(1000, 5000), ResumePlanner.decide(1000, 206, "bytes 1000-4999/5000", 4000))
        assertTrue(ResumePlanner.decide(1000, 206, "bytes 1000-4999/*", 4000) is Action.RetryFromZero, "unknown total")
        assertEquals(Action.Append(1000, 5000), ResumePlanner.decide(1000, 206, "bytes 1000-4999/*", 4000, expectedTotalBytes = 5000))
        assertTrue(ResumePlanner.decide(1000, 206, "bytes 1000-4999/6000", 5000, expectedTotalBytes = 5000) is Action.RetryFromZero)
        assertTrue(ResumePlanner.decide(1000, 206, "bytes 1000-5999/*", 5000, expectedTotalBytes = 5000) is Action.RetryFromZero)
    }

    @Test
    fun `206 at another offset retries from zero`() {
        assertTrue(ResumePlanner.decide(1000, 206, "bytes 0-4999/5000", 5000) is Action.RetryFromZero)
        assertTrue(ResumePlanner.decide(1000, 206, null, 5000) is Action.RetryFromZero)
        assertTrue(ResumePlanner.decide(1000, 206, "garbage", 5000) is Action.RetryFromZero)
    }

    @Test
    fun `200 restarts with the content length`() {
        assertEquals(Action.Restart(5000), ResumePlanner.decide(1000, 200, null, 5000))
        assertEquals(Action.Restart(null), ResumePlanner.decide(0, 200, null, null))
    }

    @Test
    fun `416 re-verifies only when the server's size matches the partial`() {
        assertEquals(Action.Reverify, ResumePlanner.decide(5000, 416, "bytes */5000", null))
        assertTrue(ResumePlanner.decide(5000, 416, null, null) is Action.RetryFromZero)
        assertTrue(ResumePlanner.decide(5000, 416, "garbage", null) is Action.RetryFromZero)
    }

    @Test
    fun `416 with a different total retries from zero`() {
        assertTrue(ResumePlanner.decide(6000, 416, "bytes */5000", null) is Action.RetryFromZero)
    }

    @Test
    fun `416 without a range request fails`() {
        val action = ResumePlanner.decide(0, 416, "bytes */5000", null)
        assertTrue(action is Action.Fail && !action.retryable)
    }

    @Test
    fun `server errors are retryable and client errors are not`() {
        listOf(408, 429, 500, 502, 503).forEach { status ->
            val action = ResumePlanner.decide(0, status, null, null)
            assertTrue(action is Action.Fail && action.retryable, "HTTP $status should be retryable")
        }
        listOf(403, 404, 410).forEach { status ->
            val action = ResumePlanner.decide(0, status, null, null)
            assertTrue(action is Action.Fail && !action.retryable, "HTTP $status should not be retryable")
        }
    }

    @Test
    fun `content range parsing`() {
        assertEquals(ContentRange(0, 99, 1000), ContentRange.parse("bytes 0-99/1000"))
        assertEquals(ContentRange(null, null, 1000), ContentRange.parse("bytes */1000"))
        assertEquals(ContentRange(5, 9, null), ContentRange.parse("BYTES 5-9/*"))
        assertNull(ContentRange.parse("bytes 9-5/1000"))
        assertNull(ContentRange.parse("bytes */*"))
        assertNull(ContentRange.parse("items 0-1/2"))
        assertNull(ContentRange.parse(null))
    }

    @Test
    fun `every status maps to exactly one action without throwing`() {
        for (status in 100..599) {
            for (offset in listOf(0L, 1L, 5000L)) {
                ResumePlanner.decide(offset, status, "bytes $offset-5999/6000", 6000 - offset)
            }
        }
    }
}
