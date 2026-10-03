package dev.jacobhayes.bootleg.hid

import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.hid.KeyboardLeds
import dev.jacobhayes.bootleg.core.hid.KeyboardReport
import dev.jacobhayes.bootleg.core.hid.MouseReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class HidSessionTest {
    /** Scripted endpoint: [results] decides each write's outcome; written reports are recorded. */
    private class FakeEndpoint(
        val kind: HidKind,
        var results: () -> HidWriteResult = { HidWriteResult.WRITTEN },
    ) : HidEndpoint {
        // Written by the session's thread, read by the test's.
        val written: MutableList<List<Byte>> = Collections.synchronizedList(mutableListOf())
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

    private val gadgetChange = HidRelayNotice.Closed("paused for a gadget change", reopenFollows = true, restartRequired = false)

    @Test
    fun `a gadget change shows reconnecting at once, refuses input, and reopens when it ends`() =
        runTest {
            val opener = FakeOpener()
            val session = session(opener)
            session.start()
            runCurrent()
            val first = opener.latest(HidKind.KEYBOARD)
            val opensBefore = opener.opened.size

            session.onRelayNotice(gadgetChange)
            // No input needed and no loop turn: the link is no longer reported open.
            assertEquals(HidLinkState.Reconnecting(1, gadgetChange.reason), session.state.value)
            assertEquals(HidOffer.UNAVAILABLE, session.offer(HidKind.KEYBOARD, keyboardPress))
            runCurrent()
            assertTrue(first.closed)
            // The gadget change is still running: nothing reopens, however long it takes (within the bound).
            advanceTimeBy(HidSessionTuning().reopenWaitMaxMs - 1)
            runCurrent()
            assertEquals(opensBefore, opener.opened.size)
            assertEquals(HidOffer.UNAVAILABLE, session.offer(HidKind.KEYBOARD, keyboardPress))

            session.onRelayNotice(HidRelayNotice.Reopenable)
            runCurrent()
            assertEquals(HidLinkState.Open(hostListening = true), session.state.value)
            val second = opener.latest(HidKind.KEYBOARD)
            assertTrue(second !== first)
            // Release-all first; nothing offered during the gap was kept for later.
            assertEquals(listOf(keyboardRelease.toList()), second.written)
            assertTrue(session.send(HidKind.KEYBOARD, keyboardPress))
            runCurrent()
            assertEquals(listOf(keyboardRelease, keyboardPress).map { it.toList() }, second.written)
        }

    @Test
    fun `a held key is released after the reopen, never pressed again`() =
        runTest {
            val opener = FakeOpener()
            val session = session(opener)
            session.start()
            runCurrent()
            assertTrue(session.send(HidKind.KEYBOARD, keyboardPress))
            runCurrent()
            val epoch = session.releaseEpoch.value

            session.onRelayNotice(gadgetChange)
            assertTrue(session.releaseEpoch.value > epoch, "input state (held keys, sticky modifiers) is forgotten")
            assertTrue(session.releaseOwed)
            session.onRelayNotice(HidRelayNotice.Reopenable)
            runCurrent()
            for (kind in bothKinds) {
                val reports = opener.latest(kind).reports()
                assertEquals(1, reports.size, "$kind")
                assertTrue(isReleaseAll(reports.single()))
            }
            assertFalse(session.releaseOwed)
        }

    @Test
    fun `the wait for a gadget change to end is bounded`() =
        runTest {
            val opener = FakeOpener()
            val tuning = HidSessionTuning(reopenWaitMaxMs = 1_000)
            val session = session(opener, tuning)
            session.start()
            runCurrent()
            val opensBefore = opener.opened.size
            session.onRelayNotice(gadgetChange)
            advanceTimeBy(tuning.reopenWaitMaxMs + 1)
            runCurrent()
            assertTrue(opener.opened.size > opensBefore)
            assertEquals(HidLinkState.Open(hostListening = true), session.state.value)
        }

    @Test
    fun `a close with nothing to wait for (root restarted) reopens right away, then backs off until the node is back`() =
        runTest {
            val opener = FakeOpener()
            val tuning = HidSessionTuning()
            val session = session(opener, tuning)
            session.start()
            runCurrent()
            opener.failNext = 1
            session.onRelayNotice(HidRelayNotice.Closed("root service restarted", reopenFollows = false, restartRequired = false))
            runCurrent()
            assertEquals(HidLinkState.Reconnecting(1, "open failed: no node yet"), session.state.value)
            advanceTimeBy(tuning.reopenBackoffMs(1) + 1)
            runCurrent()
            assertEquals(HidLinkState.Open(hostListening = true), session.state.value)
            assertTrue(isReleaseAll(opener.latest(HidKind.MOUSE).reports().single()))
        }

    @Test
    fun `the end of a gadget change cuts a reopen backoff short`() =
        runTest {
            val opener = FakeOpener()
            val session = session(opener, HidSessionTuning(reopenBackoffInitialMs = 5_000, reopenBackoffMaxMs = 5_000))
            opener.failNext = 1
            session.start()
            runCurrent()
            assertTrue(session.state.value is HidLinkState.Reconnecting)
            session.onRelayNotice(HidRelayNotice.Reopenable)
            runCurrent()
            assertEquals(HidLinkState.Open(hostListening = true), session.state.value)
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `a quarantine is terminal - restart required, and input never retries`() =
        runTest {
            val opener = FakeOpener()
            val session = session(opener)
            session.start()
            runCurrent()
            val opensBefore = opener.opened.size
            val quarantine = HidRelayNotice.Closed("restart the phone", reopenFollows = false, restartRequired = true)

            session.onRelayNotice(quarantine)
            assertEquals(HidLinkState.Failed("restart the phone", restartRequired = true), session.state.value)
            runCurrent()
            assertFalse(session.isRunning)
            assertTrue(opener.opened.all { it.closed })
            // A milder notice afterwards doesn't make it retry either.
            session.onRelayNotice(HidRelayNotice.Reopenable)
            session.onRelayNotice(gadgetChange)
            assertFalse(session.send(HidKind.KEYBOARD, keyboardPress))
            session.start()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(opensBefore, opener.opened.size)
            assertEquals(HidLinkState.Failed("restart the phone", restartRequired = true), session.state.value)
        }

    @Test
    fun `a quarantine notice for a refused open ends reconnecting`() =
        runTest {
            val opener = FakeOpener()
            opener.failNext = 100
            val session = session(opener)
            session.start()
            runCurrent()
            assertTrue(session.state.value is HidLinkState.Reconnecting)
            session.onRelayNotice(HidRelayNotice.Closed("restart the phone", reopenFollows = false, restartRequired = true))
            runCurrent()
            assertEquals(HidLinkState.Failed("restart the phone", restartRequired = true), session.state.value)
            assertFalse(session.isRunning)
        }

    @Test
    fun `a notice while stopped is ignored`() =
        runTest {
            val opener = FakeOpener()
            val session = session(opener)
            session.onRelayNotice(gadgetChange)
            assertEquals(HidLinkState.Stopped, session.state.value)
            session.start()
            runCurrent()
            assertEquals(HidLinkState.Open(hostListening = true), session.state.value)
        }

    @Test
    fun `relay notices racing the loop never leave the link stuck reconnecting`() {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            val opener = FakeOpener()
            val session = HidSession(opener, scope, bothKinds, { System.nanoTime() / 1_000_000 })

            fun awaitOpen(round: Int) {
                val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (session.state.value != HidLinkState.Open(hostListening = true) || session.releaseOwed) {
                    check(System.nanoTime() < deadlineNs) { "round $round: link stuck at ${session.state.value}" }
                    Thread.sleep(1)
                }
            }
            session.start()
            awaitOpen(-1)
            val notice = HidRelayNotice.Closed("root service restarted", reopenFollows = false, restartRequired = false)
            repeat(ROUND_COUNT) { round ->
                // From another thread, like a binder callback, while the loop reopens right away.
                thread { repeat(round % 3 + 1) { session.onRelayNotice(notice) } }.join()
                awaitOpen(round)
                assertEquals(HidOffer.ACCEPTED, session.offer(HidKind.MOUSE, mouseMove), "round $round")
            }
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun `releaseAll racing many senders never lets input reach the host after the release`() {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            val opener = FakeOpener()
            val session = HidSession(opener, scope, setOf(HidKind.MOUSE), { System.nanoTime() / 1_000_000 })

            fun awaitReleased() {
                val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (session.state.value != HidLinkState.Open(hostListening = true) || session.releaseOwed) {
                    check(System.nanoTime() < deadlineNs) { "release-all never went out: ${session.state.value}" }
                    Thread.sleep(1)
                }
            }
            session.start()
            awaitReleased()
            val mouse = opener.latest(HidKind.MOUSE)
            repeat(ROUND_COUNT) { round ->
                // Hold the writer so whatever the race leaves queued is still there when the release goes out.
                val writerHeld = CountDownLatch(1)
                val resumeWriter = CountDownLatch(1)
                executor.execute {
                    writerHeld.countDown()
                    resumeWriter.await()
                }
                assertTrue(writerHeld.await(5, TimeUnit.SECONDS))
                val writtenBefore = mouse.written.size
                val go = CountDownLatch(1)
                val acceptedAfterRelease = AtomicInteger()
                val releaseCalled = CountDownLatch(1)
                val senders =
                    (0 until SENDER_COUNT).map {
                        thread {
                            go.await()
                            repeat(OFFERS_PER_SENDER) {
                                val releaseDone = releaseCalled.count == 0L
                                if (session.offer(HidKind.MOUSE, mouseMove) == HidOffer.ACCEPTED &&
                                    releaseDone
                                ) {
                                    acceptedAfterRelease.incrementAndGet()
                                }
                            }
                        }
                    }
                go.countDown()
                session.releaseAll()
                releaseCalled.countDown()
                senders.forEach { it.join() }
                assertEquals(0, acceptedAfterRelease.get(), "round $round: input admitted while the release was owed")
                resumeWriter.countDown()
                awaitReleased()
                val written =
                    mouse.written
                        .toList()
                        .drop(writtenBefore)
                        .map { it.toByteArray() }
                val releaseIndex = written.indexOfLast(::isReleaseAll)
                assertTrue(releaseIndex >= 0, "round $round: no release-all written")
                assertEquals(0, written.size - releaseIndex - 1, "round $round: reports written after the release")
            }
            // Once the release is through, input flows again and lands after it.
            assertEquals(HidOffer.ACCEPTED, session.offer(HidKind.MOUSE, mouseMove))
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!mouse.written
                    .last()
                    .toByteArray()
                    .contentEquals(mouseMove)
            ) {
                check(System.nanoTime() < deadlineNs) { "input after the release was never written" }
                Thread.sleep(1)
            }
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val ROUND_COUNT = 50
        const val SENDER_COUNT = 4
        const val OFFERS_PER_SENDER = 40
    }
}
