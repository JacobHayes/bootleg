package dev.jacobhayes.bootleg.core.tether

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.FakeConfigFs
import dev.jacobhayes.bootleg.core.gadget.FakeHidNodes
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.GadgetPaths
import dev.jacobhayes.bootleg.core.gadget.GadgetRig
import dev.jacobhayes.bootleg.core.gadget.HidNodeGuard
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.LinkOrder
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import dev.jacobhayes.bootleg.core.gadget.TetherMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tethering against the fake kernel with the HID session open: the HAL's re-plug (unbind, NCM relink,
 * rebind by someone other than Bootleg) must never meet an open hidg fd, which the fake kernel would
 * poison (closing it later panics the real one).
 */
class TetherHidSimulationTest {
    /** Android plus the HAL on [rig]: a start or stop re-plugs USB with the measured timings. */
    private class RigAndroid(
        private val rig: GadgetRig,
    ) : TetherPort {
        var tethered = false
            private set

        override fun start(timeoutMs: Long): TetherPort.StartOutcome {
            rig.clock.schedule(UP_DELAY_MS) { tethered = true }
            halSwitch(linked = true)
            return TetherPort.StartOutcome.Started
        }

        override fun stop() {
            rig.clock.schedule(DOWN_DELAY_MS) { tethered = false }
            halSwitch(linked = false)
        }

        override fun usbTethered(): Boolean = tethered

        private fun halSwitch(linked: Boolean) {
            rig.clock.schedule(SWITCH_DELAY_MS) {
                rig.fs.asActor(FakeConfigFs.HAL) {
                    if (rig.fs.udc.isNotEmpty()) rig.fs.write(GadgetPaths.UDC, "")
                    val link = GadgetPaths.configLink(NCM_LINK)
                    if (linked) rig.fs.symlink(GadgetPaths.function(NCM_FUNCTION), link) else rig.fs.unlink(link)
                }
            }
            rig.clock.schedule(SWITCH_DELAY_MS + UNBOUND_MS) {
                rig.fs.asActor(FakeConfigFs.HAL) { if (rig.fs.udc.isEmpty()) rig.fs.write(GadgetPaths.UDC, rig.fs.udcName) }
            }
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

    private object NoGuard : HidNodeGuard {
        override fun closeAll() = Unit

        override fun holders(functions: Collection<String>): List<String> = emptyList()
    }

    private val hid = HidSpec(keyboard = true, mouse = true)

    private fun GadgetRig.hidOnAndOpen() {
        assertInstanceOf(ApplyResult.Applied::class.java, controller().apply(Desired(hid = hid)))
        hidNodes.reopen()
        assertEquals(2, hidNodes.relayOpenCount())
    }

    private fun GadgetRig.tether(guard: HidNodeGuard = hidNodes) =
        UsbTetherController(RigAndroid(this), controller(), MemoryTransitions(), clock, guard)

    @Test
    fun `tethering on and off with the HID session open never leaves an fd across the HAL's re-plug`() {
        for (seed in 0L until SEED_COUNT) {
            val rig = GadgetRig(seed)
            rig.hidOnAndOpen()
            val tether = rig.tether()
            for (on in listOf(true, false)) {
                val context = "seed $seed, on=$on"
                val result = tether.set(on)
                assertTrue(result is TetherResult.Changed && result.active == on && result.switchSettled, "$context: $result")
                assertEquals(emptyList<String>(), rig.fs.hidFdHazards, context)
                assertEquals(emptySet<String>(), rig.hidNodes.quarantined, context)
                assertTrue(FakeHidNodes.RELAY_FUNCTIONS.none { rig.fs.isPoisoned(it, FakeHidNodes.RELAY) }, context)
                assertTrue(rig.hidNodes.fenced, "$context: no reopen until the call is over")
                assertEquals(on, NCM_FUNCTION in rig.state().configLinks.values, context)
                val ours = listOf(GadgetNames.HID_KEYBOARD_FUNCTION, GadgetNames.HID_MOUSE_FUNCTION)
                assertTrue(
                    rig
                        .state()
                        .configLinks.values
                        .containsAll(ours),
                    "$context: our links survive the re-plug",
                )
                // The root service lifts the fence once the call has returned; the session reopens.
                rig.hidNodes.reopen()
                assertEquals(2, rig.hidNodes.relayOpenCount(), context)
            }
        }
    }

    @Test
    fun `a boot restore sets the prefix up before tethering starts, then exports the journal's disk`() {
        val rig = GadgetRig()
        val disk = SlotSpec(0, GadgetRig.img(1), LunMode.DISK_RO)
        rig.journalStore.save(Journal(desired = Desired(listOf(disk), prefix = true), udc = FakeConfigFs.PIXEL_UDC))
        val result = rig.tether().set(on = true)
        assertTrue(result is TetherResult.Changed && result.active, "$result")
        val links = rig.state().configLinks
        assertEquals(LinkOrder.prefix(TetherMode.RNDIS), links.keys.take(3), "the prefix went first, ahead of the HAL's links")
        assertTrue(NCM_FUNCTION in links.values)
        assertEquals(
            GadgetRig.img(1),
            rig
                .state()
                .luns
                .getValue(0)
                .file,
            "the tethering call's reconcile exported the disk",
        )
    }

    @Test
    fun `without the guard the HAL's re-plug poisons the open session`() {
        val rig = GadgetRig()
        rig.hidOnAndOpen()
        rig.tether(guard = NoGuard).set(on = true)
        assertTrue(FakeHidNodes.RELAY_FUNCTIONS.any { rig.fs.isPoisoned(it, FakeHidNodes.RELAY) }, "the simulation must see the hazard")
    }

    @Test
    fun `a holder other than the relay refuses tethering before the HAL touches anything`() {
        val rig = GadgetRig()
        rig.hidOnAndOpen()
        rig.fs.openHidFd(FakeHidNodes.RELAY_FUNCTIONS.first(), "pid 4242 (old root daemon)")
        val result = rig.tether().set(on = true)
        assertTrue(result is TetherResult.Failed && "pid 4242" in result.reason, "$result")
        assertTrue(NCM_FUNCTION !in rig.state().configLinks.values)
        assertEquals(emptyList<String>(), rig.fs.hidFdHazards)
    }

    private companion object {
        const val SEED_COUNT = 20L
        const val NCM_FUNCTION = "ncm.gs4"
        const val NCM_LINK = "function1"

        // Measured on the Pixel 6 (ARCHITECTURE.md, USB tethering).
        const val UP_DELAY_MS = 1_200L
        const val DOWN_DELAY_MS = 1_100L
        const val SWITCH_DELAY_MS = 1_300L
        const val UNBOUND_MS = 600L
    }
}
