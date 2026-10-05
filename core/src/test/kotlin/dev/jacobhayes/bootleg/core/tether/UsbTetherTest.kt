package dev.jacobhayes.bootleg.core.tether

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetRig
import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.img
import dev.jacobhayes.bootleg.core.gadget.LinkOrder
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.RndisState
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import dev.jacobhayes.bootleg.core.gadget.TetherMode
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Bootleg's USB tethering over its own network function, against the fake kernel, HAL and Android. */
class UsbTetherTest {
    /**
     * Android's tethering by name as measured and as AOSP has it: one configured virtual interface, a
     * stop by request ends every virtual request (other apps' too) and untethers the configured one, a
     * stop matching no request fails with [TetherErrors.UNKNOWN_REQUEST]. An interface is tethered
     * [TETHER_DELAY_MS] after its start, and only while it exists.
     */
    private inner class FakeAndroid : TetherPort {
        val tethered = mutableMapOf<String, Int>()
        val requests = mutableSetOf<String>()
        var configured: String? = null
        val calls = mutableListOf<String>()

        /** The next start isn't answered in time; Android tethers anyway, [LATE_START_MS] later. */
        var answerLate = false

        override fun start(
            interfaceName: String,
            timeoutMs: Long,
        ): TetherPort.StartOutcome {
            calls += "start $interfaceName"
            if (!exists(interfaceName)) return TetherPort.StartOutcome.Failed(UNKNOWN_INTERFACE)
            requests += interfaceName
            configured = interfaceName
            if (answerLate) {
                answerLate = false
                rig.clock.sleepMs(timeoutMs)
                rig.clock.schedule(LATE_START_MS) { tethered[interfaceName] = TetherPolicy.TETHERING_VIRTUAL }
                return TetherPort.StartOutcome.TimedOut
            }
            rig.clock.schedule(TETHER_DELAY_MS) { tethered[interfaceName] = TetherPolicy.TETHERING_VIRTUAL }
            return TetherPort.StartOutcome.Started
        }

        override fun stop(
            interfaceName: String,
            timeoutMs: Long,
        ): Int? {
            calls += "stop $interfaceName"
            if (interfaceName !in requests) return TetherErrors.UNKNOWN_REQUEST
            stopAll()
            return null
        }

        override fun tethered(): Map<String, Int> {
            // Android untethers an interface that went away (its function or daemon removed).
            tethered.keys.removeAll { it.startsWith("bootleg_") && !exists(it) }
            return tethered.toMap()
        }

        /** Set when a Bootleg interface is untethered (what root's callback sees). */
        var dropped = false

        override fun takeBootlegDropped(): Boolean = dropped.also { dropped = false }

        fun foreignStart(name: String) {
            requests += name
            configured = name
            tethered[name] = TetherPolicy.TETHERING_VIRTUAL
        }

        fun foreignStop() = stopAll()

        private fun stopAll() {
            requests.clear()
            configured?.let { name ->
                rig.clock.schedule(TETHER_DELAY_MS) {
                    tethered.remove(name)
                    if (TetherPolicy.isBootlegInterface(name)) dropped = true
                }
            }
            configured = null
        }

        private fun exists(name: String): Boolean {
            val state = rig.state()
            return when {
                name == TetherPolicy.RNDIS_INTERFACE -> state.rndis == RndisState.READY
                name.startsWith("bootleg_ncm") -> state.ncmInterface == name
                else -> true
            }
        }
    }

    /** The RNDIS daemon's medium; [events] in call order with Android's. */
    private inner class FakeMedium : RndisMedium {
        var connected = false
        var undrained = false

        override fun connected(): Boolean = connected && rig.state().rndis == RndisState.READY

        override fun set(connected: Boolean) {
            android.calls += "media $connected"
            check(!connected || TetherPolicy.RNDIS_INTERFACE in android.tethered) { "the medium connected before Android tethered the TAP" }
            if (!connected && undrained) throw IOException("a frame to the host is still in flight")
            this.connected = connected
        }
    }

    private class MemoryIntent : TetherIntentStore {
        var on = false

        override fun load(): Boolean = on

        override fun save(on: Boolean) {
            this.on = on
        }
    }

    private val rig = GadgetRig()
    private val gadget = rig.controller()
    private val android = FakeAndroid()
    private val medium = FakeMedium()
    private val intent = MemoryIntent()
    private val tether = UsbTetherController(android, gadget, rig.clock, medium, intent)
    private val disk = Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO)))

    /** Our own re-enumerations so far (each opens an unbound window of the executor's). */
    private fun windows() = rig.fs.executorWindowsUs.size + rig.fs.unbindableWindowsUs.size

    @Test
    fun `NCM is the default, set up by the first on, and on or off never re-plugs USB, disks exposed or not`() {
        val on = assertInstanceOf(TetherResult.Changed::class.java, tether.set(true, TetherMode.DEFAULT))
        assertTrue(on.active)
        assertInstanceOf(ApplyResult.Applied::class.java, on.setup)
        assertEquals(
            LinkOrder.prefix(TetherMode.NCM),
            rig
                .state()
                .configLinks.keys
                .take(3),
        )
        assertEquals(mapOf("bootleg_ncm0" to TetherPolicy.TETHERING_VIRTUAL), android.tethered)
        assertTrue(intent.on)
        assertInstanceOf(ApplyResult.Applied::class.java, gadget.apply(disk))
        val windows = windows()
        val switches = rig.hal.switchCount
        repeat(3) {
            assertFalse(assertInstanceOf(TetherResult.Changed::class.java, tether.set(false, TetherMode.NCM)).active)
            assertInstanceOf(TetherResult.Unchanged::class.java, tether.set(false, TetherMode.NCM))
            assertTrue(assertInstanceOf(TetherResult.Changed::class.java, tether.set(true, TetherMode.NCM)).active)
            assertInstanceOf(TetherResult.Unchanged::class.java, tether.set(true, TetherMode.NCM))
        }
        assertEquals(windows, windows(), "no re-enumeration")
        assertEquals(switches, rig.hal.switchCount)
        assertEquals(
            img(1),
            rig
                .state()
                .luns
                .getValue(0)
                .file,
            "the disk stayed",
        )
    }

    @Test
    fun `a mode change is refused with a disk exposed, then stops the old interface before setting the prefix up again`() {
        tether.set(true, TetherMode.NCM)
        assertInstanceOf(ApplyResult.Applied::class.java, gadget.apply(disk))
        val refused = assertInstanceOf(TetherResult.Refused::class.java, tether.set(true, TetherMode.RNDIS))
        assertEquals(listOf(0), refused.exposedLuns)
        assertEquals(setOf("bootleg_ncm0"), android.tethered.keys, "a refused switch leaves the old mode on")
        assertEquals(TetherMode.NCM, rig.journal().desired.network)
        gadget.eject(0, force = false)
        android.calls.clear()
        assertTrue(assertInstanceOf(TetherResult.Changed::class.java, tether.set(true, TetherMode.RNDIS)).active)
        assertEquals(listOf("stop bootleg_ncm0", "start bootleg_rndis", "media true"), android.calls)
        assertEquals(
            LinkOrder.prefix(TetherMode.RNDIS),
            rig
                .state()
                .configLinks.keys
                .take(3),
        )
        assertEquals(setOf(TetherPolicy.RNDIS_INTERFACE), android.tethered.keys)
        // Off is the medium first (shown drained), then Android; a medium that can't drain fails it.
        medium.undrained = true
        assertTrue(assertInstanceOf(TetherResult.Failed::class.java, tether.set(false, TetherMode.RNDIS)).reason.contains("in flight"))
        assertTrue(TetherPolicy.RNDIS_INTERFACE in android.tethered, "Android keeps tethering until the medium is off")
        medium.undrained = false
        android.calls.clear()
        assertInstanceOf(TetherResult.Changed::class.java, tether.set(false, TetherMode.RNDIS))
        assertEquals(listOf("media false", "stop bootleg_rndis"), android.calls)
        assertTrue(android.tethered.isEmpty())
    }

    @Test
    fun `another app's virtual tethering is never cut, and a request it erased is stopped through a fresh one`() {
        android.foreignStart(AVF)
        assertTrue(assertInstanceOf(TetherResult.Failed::class.java, tether.set(true, TetherMode.NCM)).reason.contains(AVF))
        assertFalse(android.calls.any { it.startsWith("start") }, "${android.calls}")
        android.foreignStop()
        rig.clock.sleepMs(TETHER_DELAY_MS)
        assertInstanceOf(TetherResult.Changed::class.java, tether.set(true, TetherMode.NCM))
        // The other app starts after us (its interface is now Android's configured one): off is refused.
        android.foreignStart(AVF)
        assertInstanceOf(TetherResult.Failed::class.java, tether.set(false, TetherMode.NCM))
        assertTrue(AVF in android.tethered && "bootleg_ncm0" in android.tethered)
        // Its own stop erased our request too; ours stays tethered with no request left.
        android.foreignStop()
        rig.clock.sleepMs(TETHER_DELAY_MS)
        android.calls.clear()
        assertInstanceOf(TetherResult.Changed::class.java, tether.set(false, TetherMode.NCM))
        assertEquals(listOf("stop bootleg_ncm0", "start bootleg_ncm0", "stop bootleg_ncm0"), android.calls)
        assertTrue(android.tethered.isEmpty())
    }

    @Test
    fun `a start Android finishes after its answer timed out is stopped while the choice is off, which an external stop sets`() {
        tether.set(true, TetherMode.NCM)
        tether.set(false, TetherMode.NCM)
        android.answerLate = true
        assertInstanceOf(TetherResult.Failed::class.java, tether.set(true, TetherMode.NCM))
        rig.clock.sleepMs(LATE_START_MS)
        assertTrue("bootleg_ncm0" in android.tethered, "Android tethered it anyway")
        assertNull(tether.converge())
        assertTrue(android.tethered.isEmpty(), "the choice is off, so it was stopped")
        tether.set(true, TetherMode.NCM)
        android.calls.clear()
        assertNull(tether.converge())
        assertEquals(emptyList<String>(), android.calls, "nothing to do while the choice is on")
        // A stop outside Bootleg (another app's ended ours) turns the choice off, so a later late start is
        // stopped too, even when the late start lands before root gets to look.
        android.foreignStop()
        rig.clock.sleepMs(TETHER_DELAY_MS)
        android.foreignStart("bootleg_ncm0")
        android.requests.clear()
        android.configured = null
        // A request refused in between doesn't wipe out the drop.
        assertInstanceOf(ApplyResult.Applied::class.java, gadget.apply(disk))
        assertInstanceOf(TetherResult.Refused::class.java, tether.set(true, TetherMode.RNDIS))
        assertNull(tether.converge())
        assertFalse(intent.on)
        assertTrue(android.tethered.isEmpty(), "the late start was stopped")
    }

    @Test
    fun `without the API to tether by name tethering is unsupported`() {
        val unsupported = UsbTetherController(null, gadget, rig.clock, medium, intent)
        assertEquals(UsbTetherController.UNSUPPORTED_REASON, (unsupported.set(true, TetherMode.NCM) as TetherResult.Failed).reason)
        assertNull(unsupported.converge())
        assertFalse(unsupported.usbTethered())
    }

    @Test
    fun `only Bootleg's interfaces count as its tethering`() {
        for (name in listOf("bootleg_rndis", "bootleg_ncm0", "bootleg_ncm12")) assertTrue(TetherPolicy.isBootlegInterface(name), name)
        for (name in listOf("ncm0", "rndis0", "usb0", "bootleg_ncm", "bootleg_ncm%d", "avf_tap_fixed", "wlan0", "")) {
            assertFalse(TetherPolicy.isBootlegInterface(name), name)
        }
        assertEquals(listOf(AVF), TetherPolicy.foreignVirtual(mapOf(AVF to 7, "bootleg_ncm0" to 7, "ncm0" to 5)))
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
    fun `a request sets the choice only when it succeeded, and restore needs both the opt-in and the choice`() {
        assertEquals(true, TetherChoice.afterRequest(TetherResult.Changed(true)))
        assertEquals(false, TetherChoice.afterRequest(TetherResult.Unchanged(false)))
        assertNull(TetherChoice.afterRequest(TetherResult.Refused(true, listOf(0), "x")))
        assertNull(TetherChoice.afterRequest(TetherResult.Failed(false, "x")))
        assertTrue(TetherChoice.restoreOnBoot(restoreAfterReboot = true, choice = true))
        assertFalse(TetherChoice.restoreOnBoot(restoreAfterReboot = true, choice = false))
        assertFalse(TetherChoice.restoreOnBoot(restoreAfterReboot = false, choice = true))
    }

    private companion object {
        const val AVF = "avf_tap_fixed"
        const val TETHER_DELAY_MS = 100L
        const val LATE_START_MS = 500L
        const val UNKNOWN_INTERFACE = 1
    }
}
