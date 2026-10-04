package dev.jacobhayes.bootleg.core.tether

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.FakeClock
import dev.jacobhayes.bootleg.core.gadget.GadgetController
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.HidNodeGuard
import dev.jacobhayes.bootleg.core.gadget.LunState
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsbTetherTest {
    private val clock = FakeClock()

    /**
     * Android's tethering plus the HAL as measured: a start tethers `ncm0` [upDelayMs] later, and the
     * HAL's switch (UDC unbound for [unboundMs], NCM link appearing) lands [switchDelayMs] after the
     * request; a stop mirrors it. Null [upDelayMs]: nothing ever comes up.
     */
    private inner class FakeAndroid(
        var tethered: Boolean = false,
        val outcomes: ArrayDeque<TetherPort.StartOutcome> = ArrayDeque(),
        val upDelayMs: Long? = 1_200,
        val downDelayMs: Long = 1_100,
        val switchDelayMs: Long = 1_300,
        val unboundMs: Long = 600,
    ) : TetherPort {
        val calls = mutableListOf<String>()
        var ncmLinked = tethered
        var udcBound = true

        override fun start(timeoutMs: Long): TetherPort.StartOutcome {
            calls += "start"
            val outcome = outcomes.removeFirstOrNull() ?: TetherPort.StartOutcome.Started
            when (outcome) {
                TetherPort.StartOutcome.Started ->
                    upDelayMs?.let {
                        clock.schedule(it) { tethered = true }
                        halSwitch(linked = true)
                    }
                TetherPort.StartOutcome.TimedOut -> clock.sleepMs(timeoutMs)
                is TetherPort.StartOutcome.Failed -> Unit
            }
            return outcome
        }

        override fun stop() {
            calls += "stop"
            if (!tethered && !ncmLinked) return
            clock.schedule(downDelayMs) { tethered = false }
            halSwitch(linked = false)
        }

        private fun halSwitch(linked: Boolean) {
            clock.schedule(switchDelayMs) {
                udcBound = false
                ncmLinked = linked
            }
            clock.schedule(switchDelayMs + unboundMs) { udcBound = true }
        }

        override fun usbTethered(): Boolean = tethered
    }

    private inner class FakeGadget(
        private val android: FakeAndroid,
        var luns: Map<Int, String> = emptyMap(),
    ) : GadgetController {
        var reconcileCount = 0
        val reconcileAtMs = mutableListOf<Long>()

        override fun read(): GadgetState = gadget(luns, android.ncmLinked, android.udcBound)

        override fun apply(desired: Desired): ApplyResult = error("not used")

        override fun eject(
            lun: Int,
            force: Boolean,
        ): ApplyResult = error("not used")

        override fun reconcile(): ApplyResult {
            reconcileCount++
            reconcileAtMs += clock.nowMs
            return ApplyResult.Applied(read(), reenumerated = false)
        }

        override fun bootlegOff(): ApplyResult = error("not used")
    }

    private class MemoryTransitions : TetherTransitionStore {
        var marker: TetherTransition? = null
        val saved = mutableListOf<TetherTransition>()

        override fun load(): TetherTransition? = marker

        override fun save(transition: TetherTransition) {
            marker = transition
            saved += transition
        }

        override fun clear() {
            marker = null
        }
    }

    private val transitions = MemoryTransitions()

    /** The relay as the tether controller sees it: when it closed (as a count of Android calls by then), and who holds a node. */
    private class RecordingGuard(
        private val android: FakeAndroid,
    ) : HidNodeGuard {
        val closedAtCallCount = mutableListOf<Int>()
        var heldBy = emptyList<String>()

        override fun closeAll() {
            closedAtCallCount += android.calls.size
        }

        override fun holders(functions: Collection<String>): List<String> = heldBy
    }

    private fun controller(
        android: FakeAndroid,
        gadget: FakeGadget = FakeGadget(android),
        guard: RecordingGuard = RecordingGuard(android),
    ) = UsbTetherController(android, gadget, transitions, clock, guard)

    @Test
    fun `turning it on waits for the HAL to settle, then reconciles once`() {
        val android = FakeAndroid()
        val gadget = FakeGadget(android)
        val startMs = clock.nowMs
        val result = controller(android, gadget).set(on = true)
        assertTrue(result is TetherResult.Changed && result.active && result.switchSettled, "$result")
        assertEquals(listOf("start"), android.calls)
        assertEquals(1, gadget.reconcileCount)
        // Never before the HAL rebound and held the UDC for the stable window.
        val reboundAtMs = startMs + 1_300 + 600
        assertTrue(gadget.reconcileAtMs.single() >= reboundAtMs + TetherLimits.SWITCH_STABLE_BOUND_MS, "${gadget.reconcileAtMs}")
        assertNull(transitions.marker)
        assertEquals(listOf(TetherTransition(true, startMs)), transitions.saved)
    }

    @Test
    fun `HID is closed before Android is asked, in both directions`() {
        for (on in listOf(true, false)) {
            val android = FakeAndroid(tethered = !on)
            val guard = RecordingGuard(android)
            assertTrue(controller(android, guard = guard).set(on) is TetherResult.Changed)
            assertEquals(0, guard.closedAtCallCount.first(), "closed before the first request")
        }
    }

    @Test
    fun `a held hid node refuses the change before Android is asked`() {
        val android = FakeAndroid()
        val gadget = FakeGadget(android)
        val guard = RecordingGuard(android).apply { heldBy = listOf("pid 4242 (hid.bootleg_kbd)") }
        val result = controller(android, gadget, guard).set(on = true)
        assertTrue(result is TetherResult.Failed && "pid 4242" in result.reason, "$result")
        assertTrue(android.calls.isEmpty())
        assertEquals(0, gadget.reconcileCount)
        assertTrue(transitions.saved.isEmpty())
    }

    @Test
    fun `nothing is closed when nothing is re-plugged`() {
        for (luns in listOf(emptyMap(), mapOf(0 to "/data/media/0/bootleg/x.iso"))) {
            val android = FakeAndroid(tethered = luns.isEmpty())
            val guard = RecordingGuard(android)
            controller(android, FakeGadget(android, luns), guard).set(on = true)
            assertTrue(guard.closedAtCallCount.isEmpty(), "$luns")
        }
    }

    @Test
    fun `turning it off waits for the interface and the switch`() {
        val android = FakeAndroid(tethered = true)
        val gadget = FakeGadget(android)
        val result = controller(android, gadget).set(on = false)
        assertTrue(result is TetherResult.Changed && !result.active && result.switchSettled, "$result")
        assertEquals(listOf("stop"), android.calls)
        assertFalse(android.ncmLinked)
        assertEquals(1, gadget.reconcileCount)
        assertNull(transitions.marker)
    }

    @Test
    fun `asking for the current state does nothing, even with disks exposed`() {
        for (on in listOf(true, false)) {
            val android = FakeAndroid(tethered = on)
            val gadget = FakeGadget(android, mapOf(0 to "/data/media/0/bootleg/x.iso"))
            assertEquals(TetherResult.Unchanged(on), controller(android, gadget).set(on))
            assertTrue(android.calls.isEmpty())
            assertEquals(0, gadget.reconcileCount)
            assertTrue(transitions.saved.isEmpty())
        }
    }

    @Test
    fun `an exposed disk refuses both directions without touching Android`() {
        for (on in listOf(true, false)) {
            val android = FakeAndroid(tethered = !on)
            val gadget = FakeGadget(android, mapOf(0 to "", 1 to "/data/media/0/bootleg/x.iso"))
            val result = controller(android, gadget).set(on)
            assertEquals(TetherResult.Refused(!on, listOf(1), TetherPolicy.DISKS_EXPOSED_REASON), result)
            assertTrue(android.calls.isEmpty())
            assertEquals(0, gadget.reconcileCount)
            assertTrue(transitions.saved.isEmpty())
        }
    }

    @Test
    fun `ejected LUNs do not count as exposed`() {
        val android = FakeAndroid()
        val result = controller(android, FakeGadget(android, mapOf(0 to "", 1 to ""))).set(on = true)
        assertTrue(result is TetherResult.Changed, "$result")
    }

    @Test
    fun `a refusal from Android is reported, not retried and leaves nothing to stop`() {
        val android = FakeAndroid(outcomes = ArrayDeque(listOf(TetherPort.StartOutcome.Failed(11))))
        val gadget = FakeGadget(android)
        val result = controller(android, gadget).set(on = true)
        assertTrue(result is TetherResult.Failed && "entitlement" in result.reason, "$result")
        assertEquals(listOf("start"), android.calls)
        assertEquals(0, gadget.reconcileCount)
        assertNull(transitions.marker)
    }

    @Test
    fun `a transient failure is retried once`() {
        val android = FakeAndroid(outcomes = ArrayDeque(listOf(TetherPort.StartOutcome.Failed(TetherErrors.SERVICE_UNAVAILABLE))))
        val result = controller(android).set(on = true)
        assertTrue(result is TetherResult.Changed && result.active, "$result")
        assertEquals(listOf("start", "start"), android.calls)
    }

    @Test
    fun `transient failures stop after the attempt limit`() {
        val failure = TetherPort.StartOutcome.Failed(TetherErrors.INTERNAL_ERROR)
        val android = FakeAndroid(outcomes = ArrayDeque(List(5) { failure }))
        val result = controller(android).set(on = true)
        assertTrue(result is TetherResult.Failed, "$result")
        assertEquals(List(TetherLimits.START_ATTEMPT_COUNT_MAX) { "start" }, android.calls)
    }

    @Test
    fun `no answer from Android withdraws the request and waits out its unverified marker before returning`() {
        val android = FakeAndroid(outcomes = ArrayDeque(listOf(TetherPort.StartOutcome.TimedOut)))
        val gadget = FakeGadget(android)
        val result = controller(android, gadget).set(on = true)
        assertTrue(result is TetherResult.Failed, "$result")
        assertEquals(listOf("start", "stop"), android.calls)
        assertEquals(0, gadget.reconcileCount)
        val marker = transitions.saved.last()
        assertTrue(!marker.verified && !marker.requestedOn, "$marker")
        assertEquals(marker.startedAtMs + TetherLimits.TRANSITION_GATE_MS_MAX, clock.nowMs, "the start may still be queued: a full window")
        assertNull(transitions.marker)
    }

    @Test
    fun `an interface that never comes up is stopped again and the marker cleared once that settled`() {
        val android = FakeAndroid(upDelayMs = null)
        val gadget = FakeGadget(android)
        val startMs = clock.nowMs
        val result = controller(android, gadget).set(on = true)
        assertTrue(result is TetherResult.Failed && !result.active, "$result")
        assertEquals(listOf("start", "stop"), android.calls)
        assertEquals(0, gadget.reconcileCount)
        assertNull(transitions.marker)
        assertEquals(listOf(true, false), transitions.saved.map { it.requestedOn })
        assertTrue(clock.nowMs - startMs <= TetherLimits.INTERFACE_WAIT_MS_MAX * 2 + TetherLimits.SWITCH_WAIT_MS_MAX)
    }

    @Test
    fun `a host slower than the interface wait (Windows 7 configures ~3-4s late) fails cleanly and leaves no marker`() {
        // Android tethers ncm0 only once the host configured the device; a slow host misses the 5s wait.
        val android = FakeAndroid(upDelayMs = TetherLimits.INTERFACE_WAIT_MS_MAX + 500)
        val gadget = FakeGadget(android)
        val guard = RecordingGuard(android)
        val startMs = clock.nowMs
        val result = controller(android, gadget, guard).set(on = true)
        assertTrue(result is TetherResult.Failed, "$result")
        assertEquals(listOf("start", "stop"), android.calls)
        assertEquals(0, guard.closedAtCallCount.first(), "HID closed before Android was asked")
        assertNull(transitions.marker, "nothing left to hold HID opens back")
        assertTrue(clock.nowMs - startMs <= TetherLimits.INTERFACE_WAIT_MS_MAX * 2 + TetherLimits.TRANSITION_GATE_MS_MAX)
        // Whatever Android does with the late start, the withdrawal's stop has the last word.
        clock.sleepMs(60_000)
        assertFalse(android.tethered)
    }

    @Test
    fun `a UDC that never stays bound (a host that keeps resetting) still returns within the window, marker cleared`() {
        val android = FakeAndroid()
        val gadget = FakeGadget(android)
        // Drops every 700ms for two minutes: the switch never counts as settled.
        val flapUntilMs = clock.nowMs + 120_000

        fun flap(atMs: Long) {
            clock.schedule(atMs) {
                android.udcBound = false
                clock.schedule(300) { android.udcBound = true }
                if (clock.nowMs < flapUntilMs) flap(700)
            }
        }
        flap(2_000)
        val startMs = clock.nowMs
        val result = controller(android, gadget).set(on = true)
        assertTrue(result is TetherResult.Changed && !result.switchSettled, "$result")
        assertNull(transitions.marker)
        assertTrue(
            clock.nowMs - startMs <=
                TetherLimits.INTERFACE_WAIT_MS_MAX + TetherLimits.SWITCH_WAIT_MS_MAX + TetherLimits.TRANSITION_GATE_MS_MAX,
            "${clock.nowMs - startMs}ms",
        )
    }

    @Test
    fun `a stop that never lands fails once its marker's window has passed`() {
        val android = FakeAndroid(tethered = true, downDelayMs = 60_000, switchDelayMs = 60_000)
        val startMs = clock.nowMs
        val result = controller(android).set(on = false)
        assertTrue(result is TetherResult.Failed && result.active, "$result")
        assertTrue(
            clock.nowMs - startMs <= TetherLimits.TRANSITION_GATE_MS_MAX + TetherLimits.POLL_INTERVAL_MS,
            "${clock.nowMs - startMs}ms",
        )
        assertEquals(listOf(false), transitions.saved.map { it.requestedOn })
        assertNull(transitions.marker)
    }

    @Test
    fun `a HAL that never finishes its switch still reconciles, and says so`() {
        val android = FakeAndroid(switchDelayMs = 60_000)
        val gadget = FakeGadget(android)
        val result = controller(android, gadget).set(on = true)
        assertTrue(result is TetherResult.Changed && result.active && !result.switchSettled, "$result")
        assertEquals(1, gadget.reconcileCount)
        // The HAL may still re-plug: set returns only after a full, unverified window.
        val marker = transitions.saved.last()
        assertTrue(!marker.verified && marker.requestedOn, "$marker")
        assertTrue(clock.nowMs >= marker.startedAtMs + TetherLimits.TRANSITION_GATE_MS_MAX)
        assertNull(transitions.marker)
    }

    @Test
    fun `a marker is pending only inside its window`() {
        val marker = TetherTransition(requestedOn = true, startedAtMs = 10_000)
        assertFalse(marker.isPendingAt(9_999), "from an earlier boot")
        assertTrue(marker.isPendingAt(10_000))
        assertTrue(marker.isPendingAt(10_000 + TetherLimits.TRANSITION_GATE_MS_MAX - 1))
        assertFalse(marker.isPendingAt(10_000 + TetherLimits.TRANSITION_GATE_MS_MAX))
    }

    @Test
    fun `the gate closes HID only for a marker still inside its window`() {
        val android = FakeAndroid(tethered = true)
        for ((startedAtMs, closes) in listOf(clock.nowMs to true, clock.nowMs + 50_000 to false, clock.nowMs - 50_000 to false)) {
            val guard = RecordingGuard(android)
            transitions.marker = TetherTransition(requestedOn = true, startedAtMs = startedAtMs)
            controller(android, guard = guard).awaitPendingTransition()
            assertEquals(closes, guard.closedAtCallCount.isNotEmpty(), "marker at $startedAtMs")
        }
    }

    @Test
    fun `the gate waits the full window when a verified marker's change never shows`() {
        val reportedWithoutLink = FakeAndroid(tethered = true).apply { ncmLinked = false }
        for (android in listOf(FakeAndroid(tethered = false), reportedWithoutLink)) {
            // Asked for on: either Android never reports it, or it does but the HAL never links NCM.
            transitions.marker = TetherTransition(requestedOn = true, startedAtMs = clock.nowMs)
            val startMs = clock.nowMs
            controller(android).awaitPendingTransition()
            assertEquals(TetherLimits.TRANSITION_GATE_MS_MAX, clock.nowMs - startMs)
            assertNull(transitions.marker)
        }
    }

    @Test
    fun `the gate waits out a verified marker only until the change has settled`() {
        val android = FakeAndroid()
        transitions.marker = TetherTransition(requestedOn = true, startedAtMs = clock.nowMs)
        // A dead root process had asked Android to start: the switch is still to come.
        clock.schedule(1_200) { android.tethered = true }
        clock.schedule(1_300) {
            android.udcBound = false
            android.ncmLinked = true
        }
        clock.schedule(1_900) { android.udcBound = true }
        val startMs = clock.nowMs
        controller(android).awaitPendingTransition()
        val waitedMs = clock.nowMs - startMs
        assertTrue(waitedMs in 2_900..3_100, "waited ${waitedMs}ms")
        assertNull(transitions.marker)
    }

    @Test
    fun `the gate always waits out an unverified marker's full window`() {
        val android = FakeAndroid()
        transitions.marker = TetherTransition(requestedOn = false, startedAtMs = clock.nowMs - 4_000, verified = false)
        val startMs = clock.nowMs
        controller(android).awaitPendingTransition()
        assertEquals(TetherLimits.TRANSITION_GATE_MS_MAX - 4_000, clock.nowMs - startMs)
        assertNull(transitions.marker)
    }

    @Test
    fun `an old or future marker costs at most one check`() {
        val android = FakeAndroid(tethered = true)
        for (startedAtMs in listOf(clock.nowMs + 50_000, clock.nowMs - 50_000)) {
            transitions.marker = TetherTransition(requestedOn = false, startedAtMs = startedAtMs)
            val startMs = clock.nowMs
            controller(android).awaitPendingTransition()
            assertEquals(startMs, clock.nowMs)
            assertNull(transitions.marker)
        }
    }

    @Test
    fun `set waits out a pending marker before deciding`() {
        val android = FakeAndroid(tethered = false)
        transitions.marker = TetherTransition(requestedOn = true, startedAtMs = clock.nowMs, verified = false)
        val gadget = FakeGadget(android, mapOf(0 to "/data/media/0/bootleg/x.iso"))
        val startMs = clock.nowMs
        val result = controller(android, gadget).set(on = true)
        assertTrue(result is TetherResult.Refused, "$result")
        assertEquals(TetherLimits.TRANSITION_GATE_MS_MAX, clock.nowMs - startMs)
    }

    @Test
    fun `the decision never proceeds while media is loaded`() =
        runBlocking<Unit> {
            checkAll(Arb.boolean(), Arb.boolean(), Arb.list(Arb.boolean(), 0..8)) { requestOn, active, loaded ->
                val state = gadget(loaded.mapIndexed { lun, has -> lun to (if (has) "/data/media/0/bootleg/$lun.img" else "") }.toMap())
                val decision = TetherPolicy.decide(requestOn, active, state)
                when {
                    requestOn == active -> assertEquals(TetherDecision.AlreadyDone, decision)
                    loaded.any { it } ->
                        assertEquals(TetherDecision.RefuseDisksExposed(loaded.indices.filter { loaded[it] }), decision)
                    else -> assertEquals(TetherDecision.Proceed, decision)
                }
            }
        }

    @Test
    fun `usb tether interfaces and functions are recognised by name`() {
        for (name in listOf("ncm0", "rndis0", "usb0", "ncm12")) assertTrue(TetherPolicy.isUsbTetherInterface(name), name)
        for (name in listOf("wlan0", "ap_br_wlan0", "bt-pan", "ncm", "xncm0", "rmnet0", "")) {
            assertFalse(TetherPolicy.isUsbTetherInterface(name), name)
        }
        assertTrue(TetherPolicy.usbTethered(listOf("wlan1", "ncm0")))
        assertFalse(TetherPolicy.usbTethered(listOf("wlan1")))
        assertTrue(TetherPolicy.tetherFunctionLinked(gadget(emptyMap(), ncmLinked = true)))
        assertFalse(TetherPolicy.tetherFunctionLinked(gadget(emptyMap(), ncmLinked = false)))
    }

    @Test
    fun `only an external stop clears the remembered choice`() =
        runBlocking<Unit> {
            checkAll(
                Arb.boolean(),
                Arb.int(0..2),
                Arb.boolean(),
                Arb.boolean(),
                Arb.boolean(),
            ) { choice, was, isActive, inFlight, shutdown ->
                val wasActive = listOf(null, false, true)[was]
                val after = TetherChoice.afterObservation(choice, wasActive, isActive, inFlight, shutdown)
                val externalStop = wasActive == true && !isActive && !inFlight && !shutdown
                assertEquals(if (externalStop) false else choice, after)
                // Observing Android never turns the choice on: only a request through Bootleg does.
                if (!choice) assertFalse(after)
            }
        }

    @Test
    fun `a request sets the choice only when it succeeded`() {
        val applied = ApplyResult.Applied(gadget(emptyMap()), reenumerated = true)
        assertEquals(true, TetherChoice.afterRequest(TetherResult.Changed(true, applied, switchSettled = true)))
        assertEquals(false, TetherChoice.afterRequest(TetherResult.Unchanged(false)))
        assertNull(TetherChoice.afterRequest(TetherResult.Refused(true, listOf(0), "x")))
        assertNull(TetherChoice.afterRequest(TetherResult.Failed(false, "x")))
    }

    @Test
    fun `a re-enumeration's ncm0 drop never clears the choice, a real stop does`() {
        // Bootleg's own re-plug: ncm0 is gone for a few seconds and root's callback may say off,
        // but the HAL's ncm function stays linked.
        assertFalse(
            TetherChoice.externalStopConfirmed(
                stillOff = true,
                rootTethered = false,
                tetherFunctionLinked = true,
                requestInFlight = false,
                shuttingDown = false,
            ),
        )
        // ncm0 came back within the confirm window: a broadcast said on again.
        assertFalse(TetherChoice.externalStopConfirmed(false, false, false, false, false))
        // Android stopped it (unplug, mode change): off everywhere and the function unlinked.
        assertTrue(TetherChoice.externalStopConfirmed(true, false, false, false, false))
        // Any other signal keeps the choice.
        assertFalse(TetherChoice.externalStopConfirmed(true, true, false, false, false))
        assertFalse(TetherChoice.externalStopConfirmed(true, false, false, true, false))
        assertFalse(TetherChoice.externalStopConfirmed(true, false, false, false, true))
        assertTrue(TetherLimits.EXTERNAL_STOP_CONFIRM_MS > WINDOWS_7_NCM_RETURN_MS, "the wait outlasts ncm0's return")
    }

    @Test
    fun `restore needs both the opt-in and the choice`() {
        assertTrue(TetherChoice.restoreOnBoot(restoreAfterReboot = true, choice = true))
        assertFalse(TetherChoice.restoreOnBoot(restoreAfterReboot = true, choice = false))
        assertFalse(TetherChoice.restoreOnBoot(restoreAfterReboot = false, choice = true))
    }

    @Test
    fun `error codes have names`() {
        assertEquals("carrier entitlement check failed (11)", TetherErrors.describe(11))
        assertEquals("error (99)", TetherErrors.describe(99))
    }

    private companion object {
        /** Measured: ncm0 is back ~4s after a Bootleg re-enumeration with a Windows 7 host. */
        const val WINDOWS_7_NCM_RETURN_MS = 4_000L

        fun gadget(
            files: Map<Int, String>,
            ncmLinked: Boolean = false,
            udcBound: Boolean = true,
        ): GadgetState =
            GadgetState(
                udc = if (udcBound) "11110000.dwc3" else "",
                functions = setOf("ffs.adb", "ncm.gs9"),
                configLinks = mapOf("function0" to "ffs.adb") + (if (ncmLinked) mapOf("function1" to "ncm.gs9") else emptyMap()),
                luns = files.mapValues { (_, file) -> LunState(file, ro = true, cdrom = false, removable = true, nofua = false) },
            )
    }
}
