package dev.jacobhayes.bootleg.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineModeTest {
    private val info = HotspotInfo(ssid = "DIRECT-bootleg", passphrase = "secret", qrSecurity = "WPA", interfaceCidr = "192.168.49.1/24")

    /** Android's hotspot: each start waits on [gate] (Android answering), like the real callback. */
    private class FakeReservation : HotspotReservation {
        var gate = CompletableDeferred<Result<HotspotInfo>>()
        var startCount = 0
        var stopCount = 0

        /** Android closes the reservation right after handing it over (before the caller resumes). */
        var closedOnHandover = false
        private var open = false

        override val isOn: Boolean get() = open

        override suspend fun start(onStopped: () -> Unit): HotspotInfo {
            startCount++
            val info = gate.await().getOrThrow()
            open = !closedOnHandover
            return info
        }

        override fun stop() {
            stopCount++
            open = false
        }
    }

    private class Fixture {
        val lifecycle = Mutex()
        val reservation = FakeReservation()
        val listenerStarts = mutableListOf<String>()
        var listenerStops = 0
        var listenerFailure: Exception? = null
        val states = mutableListOf<HotspotState>()
        var current = true
        val offline =
            OfflineMode(
                lifecycle = lifecycle,
                reservation = reservation,
                stopListener = { listenerStops++ },
                onState = { states += it },
            )

        val listenerUp: Boolean get() = listenerStarts.size > listenerStops

        suspend fun start(): Boolean =
            offline.start(
                isCurrent = { current },
                onStopped = {},
                startListener = { cidr ->
                    listenerFailure?.let { throw it }
                    listenerStarts += cidr
                },
            )
    }

    private fun TestScope.startInFlight(fixture: Fixture) =
        async { fixture.start() }.also {
            runCurrent()
            assertEquals(HotspotState.Starting, fixture.states.last())
            assertEquals(1, fixture.reservation.startCount)
        }

    @Test
    fun `start brings up the hotspot, then the listener on its address`() =
        runTest {
            val fixture = Fixture()
            val started = startInFlight(fixture)
            fixture.reservation.gate.complete(Result.success(info))
            assertTrue(started.await())
            assertEquals(listOf("192.168.49.1/24"), fixture.listenerStarts)
            assertEquals(HotspotState.On(info), fixture.states.last())
            assertTrue(fixture.reservation.isOn)

            fixture.offline.stop()
            assertEquals(1, fixture.listenerStops)
            assertFalse(fixture.reservation.isOn)
            assertEquals(HotspotState.Off, fixture.states.last())
        }

    @Test
    fun `a stop while Android starts the hotspot supersedes the start`() =
        runTest {
            val fixture = Fixture()
            val started = startInFlight(fixture)
            fixture.offline.stop()
            assertEquals(HotspotState.Off, fixture.states.last())

            fixture.reservation.gate.complete(Result.success(info))
            assertFalse(started.await())
            assertTrue(fixture.listenerStarts.isEmpty(), "the listener must never start for a superseded start")
            assertFalse(fixture.reservation.isOn, "the late reservation must be closed")
            assertEquals(HotspotState.Off, fixture.states.last())
        }

    @Test
    fun `the node stopping while Android starts the hotspot supersedes the start`() =
        runTest {
            val fixture = Fixture()
            val started = startInFlight(fixture)
            // What stopNode does: under the lifecycle lock, drop the node and stop offline mode.
            fixture.lifecycle.withLock {
                fixture.current = false
                fixture.offline.stopLocked()
            }

            fixture.reservation.gate.complete(Result.success(info))
            assertFalse(started.await())
            assertFalse(fixture.listenerUp)
            assertFalse(fixture.reservation.isOn)
            assertEquals(HotspotState.Off, fixture.states.last())
        }

    @Test
    fun `a stale node start is superseded even without a stop`() =
        runTest {
            val fixture = Fixture()
            val started = startInFlight(fixture)
            fixture.current = false
            fixture.reservation.gate.complete(Result.success(info))
            assertFalse(started.await())
            assertFalse(fixture.listenerUp)
            assertFalse(fixture.reservation.isOn)
        }

    @Test
    fun `a second start while one is in flight is refused`() =
        runTest {
            val fixture = Fixture()
            val first = startInFlight(fixture)
            assertFalse(fixture.start())
            fixture.offline.stop()
            // Still refused: the superseded start hasn't finished with the reservation yet.
            assertFalse(fixture.start())
            assertEquals(1, fixture.reservation.startCount)

            fixture.reservation.gate.complete(Result.success(info))
            assertFalse(first.await())
            fixture.reservation.gate = CompletableDeferred(Result.success(info))
            assertTrue(fixture.start())
            assertEquals(1, fixture.listenerStarts.size)
        }

    @Test
    fun `a failed hotspot start reports Failed and never starts the listener`() =
        runTest {
            val fixture = Fixture()
            val started = startInFlight(fixture)
            fixture.reservation.gate.complete(Result.failure(IllegalStateException("no Wi-Fi channel available")))
            assertFalse(started.await())
            assertEquals(HotspotState.Failed("no Wi-Fi channel available"), fixture.states.last())
            assertFalse(fixture.listenerUp)
            assertFalse(fixture.reservation.isOn)
        }

    @Test
    fun `a hotspot that never answers times out as Failed`() =
        runTest {
            val fixture = Fixture()
            val started = startInFlight(fixture)
            advanceTimeBy(OfflineMode.HOTSPOT_START_TIMEOUT_MS + 1)
            assertFalse(started.await())
            assertTrue(fixture.states.last() is HotspotState.Failed)
            assertFalse(fixture.listenerUp)
        }

    @Test
    fun `a listener failure closes the reservation`() =
        runTest {
            val fixture = Fixture()
            fixture.listenerFailure = IllegalStateException("address in use")
            val started = startInFlight(fixture)
            fixture.reservation.gate.complete(Result.success(info))
            assertFalse(started.await())
            assertFalse(fixture.reservation.isOn)
            assertEquals(HotspotState.Failed("address in use"), fixture.states.last())
        }

    @Test
    fun `start does nothing without a current node`() =
        runTest {
            val fixture = Fixture()
            fixture.current = false
            assertFalse(fixture.start())
            assertEquals(0, fixture.reservation.startCount)
            assertTrue(fixture.states.isEmpty())
        }

    @Test
    fun `Android stopping the hotspot while it starts supersedes the start`() =
        runTest {
            val fixture = Fixture()
            val started = startInFlight(fixture)
            assertTrue(fixture.offline.hotspotLost())
            assertEquals(HotspotState.Failed(OfflineMode.HOTSPOT_LOST_REASON), fixture.states.last())

            fixture.reservation.gate.complete(Result.success(info))
            assertFalse(started.await())
            assertTrue(fixture.listenerStarts.isEmpty(), "the listener must never start on a stopped hotspot")
            assertFalse(fixture.reservation.isOn)
            assertEquals(HotspotState.Failed(OfflineMode.HOTSPOT_LOST_REASON), fixture.states.last())
        }

    @Test
    fun `a reservation closed before the listener starts fails the start`() =
        runTest {
            val fixture = Fixture()
            fixture.reservation.closedOnHandover = true
            val started = startInFlight(fixture)
            fixture.reservation.gate.complete(Result.success(info))
            assertFalse(started.await())
            assertTrue(fixture.listenerStarts.isEmpty())
            assertEquals(HotspotState.Failed(OfflineMode.HOTSPOT_LOST_REASON), fixture.states.last())
        }

    @Test
    fun `Android stopping a running hotspot stops the listener and reports it`() =
        runTest {
            val fixture = Fixture()
            val started = startInFlight(fixture)
            fixture.reservation.gate.complete(Result.success(info))
            assertTrue(started.await())
            assertTrue(fixture.offline.hotspotLost())
            assertFalse(fixture.listenerUp)
            assertFalse(fixture.reservation.isOn)
            assertEquals(HotspotState.Failed(OfflineMode.HOTSPOT_LOST_REASON), fixture.states.last())
        }

    @Test
    fun `a lost callback with offline mode off changes nothing`() =
        runTest {
            val fixture = Fixture()
            assertFalse(fixture.offline.hotspotLost())
            assertTrue(fixture.states.isEmpty())
            assertEquals(0, fixture.reservation.stopCount)
        }
}
