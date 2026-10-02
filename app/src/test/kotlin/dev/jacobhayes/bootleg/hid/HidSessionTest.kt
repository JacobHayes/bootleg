package dev.jacobhayes.bootleg.hid

import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.hid.KeyboardLeds
import dev.jacobhayes.bootleg.core.hid.KeyboardReport
import dev.jacobhayes.bootleg.core.hid.MouseReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class HidSessionTest {
    /** Scripted endpoint: [results] decides each write's outcome; written reports are recorded. */
    private class FakeEndpoint(
        val kind: HidKind,
        var results: () -> HidWriteResult = { HidWriteResult.WRITTEN },
    ) : HidEndpoint {
        val written = mutableListOf<List<Byte>>()
        val outputReports = ArrayDeque<ByteArray>()
        var closed = false

        override fun write(report: ByteArray): HidWriteResult {
            check(!closed) { "write after close" }
            val result = results()
            if (result == HidWriteResult.WRITTEN) written += report.toList()
            return result
        }

        override fun readOutputReport(): ByteArray? = outputReports.removeFirstOrNull()

        override fun close() {
            closed = true
        }
    }

    private class FakeOpener : HidEndpointOpener {
        val opened = mutableListOf<FakeEndpoint>()
        var failNext = 0
        var nextResults: () -> HidWriteResult = { HidWriteResult.WRITTEN }

        override suspend fun open(kind: HidKind): HidEndpoint {
            if (failNext > 0) {
                failNext--
                error("no node yet")
            }
            return FakeEndpoint(kind, nextResults).also { opened += it }
        }

        fun latest(kind: HidKind): FakeEndpoint = opened.last { it.kind == kind }
    }

    private val keyboardPress = KeyboardReport(0, listOf(KeyUsage.F24)).encode()
    private val keyboardRelease = KeyboardReport.RELEASE_ALL.encode()
    private val mouseMove = MouseReport(0, 10, 10).encode()
    private val bothKinds = setOf(HidKind.KEYBOARD, HidKind.MOUSE)

    private fun TestScope.session(
        opener: FakeOpener,
        tuning: HidSessionTuning = HidSessionTuning(),
    ) = HidSession(opener, backgroundScope, bothKinds, { testScheduler.currentTime }, tuning)

    private fun FakeEndpoint.reports() = written.map { it.toByteArray() }

    private fun isReleaseAll(report: ByteArray) = report.all { it == 0.toByte() }

    @Test
    fun `start opens both endpoints and releases everything first`() =
        runTest {
            val opener = FakeOpener()
            val session = session(opener)
            session.start()
            runCurrent()
            assertEquals(HidLinkState.Open(hostListening = true), session.state.value)
            for (kind in bothKinds) {
                val reports = opener.latest(kind).reports()
                assertEquals(1, reports.size)
                assertTrue(isReleaseAll(reports.single()))
            }
            assertEquals(1, session.stats.value.releaseAllCount)
        }

    @Test
    fun `reports are written in order per kind`() =
        runTest {
            val opener = FakeOpener()
            val session = session(opener)
            session.start()
            runCurrent()
            assertTrue(session.send(HidKind.KEYBOARD, keyboardPress))
            assertTrue(session.send(HidKind.MOUSE, mouseMove))
            assertTrue(session.send(HidKind.KEYBOARD, keyboardRelease))
            runCurrent()
            assertEquals(
                listOf(keyboardRelease, keyboardPress, keyboardRelease).map { it.toList() },
                opener.latest(HidKind.KEYBOARD).written,
            )
            assertEquals(mouseMove.toList(), opener.latest(HidKind.MOUSE).written.last())
            assertTrue(session.awaitIdle(10))
        }

    @Test
    fun `wrong-size reports and unknown kinds are rejected`() =
        runTest {
            val session = HidSession(FakeOpener(), backgroundScope, setOf(HidKind.MOUSE), { testScheduler.currentTime })
            assertThrows<IllegalArgumentException> { session.send(HidKind.MOUSE, ByteArray(8)) }
            assertThrows<IllegalArgumentException> { session.send(HidKind.KEYBOARD, keyboardPress) }
        }

    @Test
    fun `sending before the link is open is dropped, not buffered`() =
        runTest {
            val session = session(FakeOpener())
            assertFalse(session.send(HidKind.KEYBOARD, keyboardPress))
            assertEquals(1, session.stats.value.reportsDropped)
        }

    @Test
    fun `overflow drops the queue and releases everything`() =
        runTest {
            val opener = FakeOpener()
            val session = session(opener, HidSessionTuning(queueLengthMax = 4))
            session.start()
            runCurrent()
            val epoch = session.releaseEpoch.value
            // Nothing runs between these sends, so the fifth overflows the queue of four.
            repeat(4) { assertTrue(session.send(HidKind.KEYBOARD, keyboardPress)) }
            assertFalse(session.send(HidKind.KEYBOARD, keyboardPress))
            assertTrue(session.releaseEpoch.value > epoch)
            runCurrent()
            val keyboard = opener.latest(HidKind.KEYBOARD).reports()
            assertTrue(keyboard.none { it.contentEquals(keyboardPress) }, "queued presses must not be replayed")
            assertTrue(isReleaseAll(keyboard.last()))
            assertTrue(isReleaseAll(opener.latest(HidKind.MOUSE).reports().last()))
            assertEquals(5, session.stats.value.reportsDropped)
        }

    @Test
    fun `a write that keeps blocking past the deadline drops input and owes a release`() =
        runTest {
            val opener = FakeOpener()
            val tuning = HidSessionTuning()
            val session = session(opener, tuning)
            session.start()
            runCurrent()
            val keyboard = opener.latest(HidKind.KEYBOARD)
            keyboard.results = { HidWriteResult.WOULD_BLOCK }
            assertTrue(session.send(HidKind.KEYBOARD, keyboardPress))
            assertTrue(session.send(HidKind.KEYBOARD, keyboardRelease))
            advanceTimeBy(tuning.writeDeadlineMs + 1)
            runCurrent()
            assertEquals(HidLinkState.Open(hostListening = false), session.state.value)
            // Input while the host isn't listening is refused rather than delivered late.
            assertFalse(session.send(HidKind.KEYBOARD, keyboardPress))
            // The host wakes up: the owed release-all goes out before anything else.
            keyboard.results = { HidWriteResult.WRITTEN }
            advanceTimeBy(tuning.stallRetryIntervalMs + tuning.writeDeadlineMs)
            runCurrent()
            assertEquals(HidLinkState.Open(hostListening = true), session.state.value)
            val written = keyboard.reports()
            assertTrue(isReleaseAll(written.last()))
            assertTrue(written.none { it.contentEquals(keyboardPress) })
            assertTrue(session.send(HidKind.KEYBOARD, keyboardPress))
        }

    @Test
    fun `blocking for too long reopens the endpoints`() =
        runTest {
            val opener = FakeOpener()
            val tuning = HidSessionTuning()
            val session = session(opener, tuning)
            session.start()
            runCurrent()
            val first = opener.latest(HidKind.KEYBOARD)
            first.results = { HidWriteResult.WOULD_BLOCK }
            session.send(HidKind.KEYBOARD, keyboardPress)
            advanceTimeBy(tuning.stallReopenMs + tuning.writeDeadlineMs + tuning.stallRetryIntervalMs * 2)
            runCurrent()
            assertTrue(first.closed)
            assertTrue(session.state.value is HidLinkState.Reconnecting, "${session.state.value}")
            advanceTimeBy(tuning.reopenBackoffMs(1) + 1)
            runCurrent()
            val second = opener.latest(HidKind.KEYBOARD)
            assertTrue(second !== first && !second.closed)
            assertEquals(HidLinkState.Open(hostListening = true), session.state.value)
            assertTrue(isReleaseAll(second.reports().first()))
        }

    @Test
    fun `disconnect (ENXIO, ESHUTDOWN) closes, backs off, reopens and releases`() =
        runTest {
            val opener = FakeOpener()
            val tuning = HidSessionTuning()
            val session = session(opener, tuning)
            session.start()
            runCurrent()
            val first = opener.latest(HidKind.MOUSE)
            first.results = { HidWriteResult.DISCONNECTED }
            val epoch = session.releaseEpoch.value
            session.send(HidKind.MOUSE, mouseMove)
            runCurrent()
            assertTrue(first.closed)
            assertTrue(opener.latest(HidKind.KEYBOARD).closed)
            assertEquals(HidLinkState.Reconnecting(1, "HID device disconnected"), session.state.value)
            assertTrue(session.releaseEpoch.value > epoch)
            // Not before the backoff.
            advanceTimeBy(tuning.reopenBackoffMs(1) - 1)
            runCurrent()
            assertTrue(opener.latest(HidKind.MOUSE) === first)
            advanceTimeBy(2)
            runCurrent()
            val second = opener.latest(HidKind.MOUSE)
            assertTrue(second !== first)
            assertTrue(isReleaseAll(second.reports().single()))
            assertTrue(isReleaseAll(opener.latest(HidKind.KEYBOARD).reports().single()))
        }

    @Test
    fun `open failures back off, give up after the limit, and input retries`() =
        runTest {
            val opener = FakeOpener()
            val tuning = HidSessionTuning(reopenAttemptCountMax = 3, reopenBackoffInitialMs = 100, reopenBackoffMaxMs = 400)
            opener.failNext = 100
            val session = session(opener, tuning)
            session.start()
            runCurrent()
            assertEquals(HidLinkState.Reconnecting(1, "open failed: no node yet"), session.state.value)
            advanceTimeBy(100 + 200 + 1)
            runCurrent()
            assertTrue(session.state.value is HidLinkState.Failed, "${session.state.value}")
            assertFalse(session.isRunning)
            opener.failNext = 0
            assertFalse(session.send(HidKind.KEYBOARD, keyboardPress))
            runCurrent()
            assertEquals(HidLinkState.Open(hostListening = true), session.state.value)
        }

    @Test
    fun `backoff doubles and is capped`() {
        val tuning = HidSessionTuning(reopenBackoffInitialMs = 250, reopenBackoffMaxMs = 5_000)
        assertEquals(listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (1..7).map(tuning::reopenBackoffMs))
        assertEquals(5_000L, tuning.reopenBackoffMs(1_000))
        assertThrows<IllegalArgumentException> { tuning.reopenBackoffMs(0) }
    }

    @Test
    fun `stop releases everything and closes`() =
        runTest {
            val opener = FakeOpener()
            val session = session(opener)
            session.start()
            runCurrent()
            session.send(HidKind.KEYBOARD, keyboardPress)
            runCurrent()
            session.stop()
            assertEquals(HidLinkState.Stopped, session.state.value)
            for (kind in bothKinds) {
                val endpoint = opener.latest(kind)
                assertTrue(endpoint.closed)
                assertTrue(isReleaseAll(endpoint.reports().last()))
            }
            assertFalse(session.send(HidKind.KEYBOARD, keyboardPress))
        }

    @Test
    fun `releaseAll (app backgrounded) drops queued input and zeroes both devices`() =
        runTest {
            val opener = FakeOpener()
            val session = session(opener)
            session.start()
            runCurrent()
            session.send(HidKind.KEYBOARD, keyboardPress)
            session.releaseAll()
            runCurrent()
            val keyboard = opener.latest(HidKind.KEYBOARD).reports()
            assertTrue(keyboard.none { it.contentEquals(keyboardPress) })
            assertTrue(isReleaseAll(keyboard.last()))
            assertTrue(isReleaseAll(opener.latest(HidKind.MOUSE).reports().last()))
            assertEquals(2, session.stats.value.releaseAllCount)
        }

    @Test
    fun `keyboard LED output reports are decoded`() =
        runTest {
            val opener = FakeOpener()
            val tuning = HidSessionTuning()
            val session = session(opener, tuning)
            session.start()
            runCurrent()
            opener.latest(HidKind.KEYBOARD).outputReports += byteArrayOf(0x03)
            advanceTimeBy(tuning.ledPollIntervalMs + 1)
            runCurrent()
            assertEquals(
                KeyboardLeds(numLock = true, capsLock = true, scrollLock = false, compose = false, kana = false),
                session.leds.value,
            )
        }
}
