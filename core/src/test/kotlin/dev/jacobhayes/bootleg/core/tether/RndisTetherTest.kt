package dev.jacobhayes.bootleg.core.tether

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.FakeConfigFs
import dev.jacobhayes.bootleg.core.gadget.GadgetPaths
import dev.jacobhayes.bootleg.core.gadget.GadgetRig
import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.img
import dev.jacobhayes.bootleg.core.gadget.LinkOrder
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** RNDIS tethering (Bootleg's TAP and the daemon's medium) and switching to NCM, against the fake kernel and HAL. */
class RndisTetherTest {
    /** Android's NCM tethering: a start or stop relinks the HAL's `ncm` function and re-plugs USB. */
    private class FakeNcm(
        private val rig: GadgetRig,
    ) : TetherPort {
        var tethered = false

        override fun start(timeoutMs: Long): TetherPort.StartOutcome {
            halSwitch(true)
            return TetherPort.StartOutcome.Started
        }

        override fun stop() = halSwitch(false)

        override fun usbTethered(): Boolean = tethered

        private fun halSwitch(on: Boolean) =
            rig.clock.schedule(SWITCH_DELAY_MS) {
                rig.fs.asActor(FakeConfigFs.HAL) {
                    if (rig.fs.udc.isNotEmpty()) rig.fs.write(GadgetPaths.UDC, "")
                    val link = GadgetPaths.configLink("function1")
                    if (on) rig.fs.symlink(GadgetPaths.function("ncm.gs4"), link) else rig.fs.unlink(link)
                    rig.fs.write(GadgetPaths.UDC, rig.fs.udcName)
                }
                tethered = on
            }
    }

    /** Android tethering the TAP, and the daemon's medium; [events] in call order. */
    private class FakeRndis(
        private val rig: GadgetRig,
    ) : RndisTetherPort {
        val events = mutableListOf<String>()
        var tethered = false
        var medium = false
        var refuseWith: Int? = null
        var undrained = false

        override fun start(timeoutMs: Long): TetherPort.StartOutcome {
            events += "start"
            refuseWith?.let { return TetherPort.StartOutcome.Failed(it) }
            rig.clock.schedule(TAP_DELAY_MS) { tethered = true }
            return TetherPort.StartOutcome.Started
        }

        override fun stop() {
            events += "stop"
            rig.clock.schedule(TAP_DELAY_MS) { tethered = false }
        }

        override fun tethered(): Boolean = tethered

        override fun mediaConnected(): Boolean = medium

        override fun setMedia(connected: Boolean) {
            events += "media $connected"
            check(!connected || tethered) { "the medium connected before Android tethered the TAP" }
            if (!connected && undrained) throw IOException("a frame to the host is still in flight")
            medium = connected
        }
    }

    private class MemoryTransitions : TetherTransitionStore {
        private var marker: TetherTransition? = null

        override fun load(): TetherTransition? = marker

        override fun save(transition: TetherTransition) {
            marker = transition
        }

        override fun clear() {
            marker = null
        }
    }

    private val rig = GadgetRig()
    private val ncm = FakeNcm(rig)
    private val rndis = FakeRndis(rig)
    private val tether = UsbTetherController(ncm, rig.controller(), MemoryTransitions(), rig.clock, rig.hidNodes, rndis)

    @Test
    fun `RNDIS on sets the prefix up, tethers the TAP, then connects the medium, and off is the mirror image`() {
        assertTrue(assertInstanceOf(TetherResult.Changed::class.java, tether.set(true, TetherMode.RNDIS)).active)
        assertEquals(
            LinkOrder.PREFIX,
            rig
                .state()
                .configLinks.keys
                .take(3),
        )
        assertTrue(rig.journal().desired.prefix, "tethering is a use of Bootleg")
        assertEquals(listOf("start", "media true"), rndis.events)
        assertInstanceOf(TetherResult.Unchanged::class.java, tether.set(true, TetherMode.RNDIS))
        val switches = rig.hal.switchCount
        assertFalse(assertInstanceOf(TetherResult.Changed::class.java, tether.set(false, TetherMode.RNDIS)).active)
        assertEquals(listOf("start", "media true", "media false", "stop"), rndis.events)
        assertEquals(switches, rig.hal.switchCount, "no USB change")
    }

    @Test
    fun `Android's tethering and the medium converge independently`() {
        tether.set(true, TetherMode.RNDIS)
        // A failed stop (or a root process that died) left the TAP tethered with the medium off.
        rndis.medium = false
        assertInstanceOf(TetherResult.Changed::class.java, tether.set(true, TetherMode.RNDIS))
        assertEquals(listOf("start", "media true", "media true"), rndis.events, "reconnected without a second start")
        // The medium can't be shown drained: off fails and Android keeps tethering until it is.
        rndis.undrained = true
        assertTrue(assertInstanceOf(TetherResult.Failed::class.java, tether.set(false, TetherMode.RNDIS)).reason.contains("in flight"))
        assertTrue(rndis.tethered)
        rndis.undrained = false
        assertInstanceOf(TetherResult.Changed::class.java, tether.set(false, TetherMode.RNDIS))
        assertFalse(rndis.tethered || rndis.medium)
    }

    @Test
    fun `a refused start is taken back down, and without the API RNDIS is unsupported`() {
        rndis.refuseWith = 11
        val failed = assertInstanceOf(TetherResult.Failed::class.java, tether.set(true, TetherMode.RNDIS))
        assertTrue(failed.reason.contains("entitlement"), failed.reason)
        assertFalse(rndis.tethered || rndis.medium)
        val unsupported = UsbTetherController(ncm, rig.controller(), MemoryTransitions(), rig.clock, rig.hidNodes, rndis = null)
        ncm.tethered = true
        assertEquals(UsbTetherController.RNDIS_UNSUPPORTED_REASON, (unsupported.set(true, TetherMode.RNDIS) as TetherResult.Failed).reason)
        assertTrue(ncm.tethered, "NCM stays on")
    }

    @Test
    fun `with a disk exposed RNDIS switches without a re-plug once the prefix is up, NCM never`() {
        val controller = rig.controller()
        val disk = Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO)))
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(disk))
        assertInstanceOf(TetherResult.Changed::class.java, tether.set(true, TetherMode.RNDIS))
        assertEquals(
            img(1),
            rig
                .state()
                .luns
                .getValue(0)
                .file,
            "the disk stayed",
        )
        assertInstanceOf(TetherResult.Refused::class.java, tether.set(true, TetherMode.NCM))
        assertTrue(rndis.tethered, "a refused switch leaves RNDIS on")
    }

    @Test
    fun `switching the mode turns the other transport off first`() {
        tether.set(true, TetherMode.RNDIS)
        assertInstanceOf(TetherResult.Changed::class.java, tether.set(true, TetherMode.NCM))
        assertTrue(ncm.tethered && !rndis.tethered && !rndis.medium)
        assertInstanceOf(TetherResult.Changed::class.java, tether.set(true, TetherMode.RNDIS))
        assertTrue(rndis.tethered && !ncm.tethered)
        assertInstanceOf(TetherResult.Changed::class.java, tether.set(false, TetherMode.NCM))
        assertFalse(rndis.tethered || ncm.tethered)
    }

    private companion object {
        const val SWITCH_DELAY_MS = 1_300L
        const val TAP_DELAY_MS = 100L
    }
}
