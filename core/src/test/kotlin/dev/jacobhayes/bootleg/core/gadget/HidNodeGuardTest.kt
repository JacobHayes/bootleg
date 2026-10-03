package dev.jacobhayes.bootleg.core.gadget

import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.img
import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.iso
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** No hidg fd is ever open across a UDC write of ours or the removal of a hid function (see [HidNodeGuard]). */
class HidNodeGuardTest {
    private val udc = FakeConfigFs.PIXEL_UDC
    private val keyboard = GadgetNames.HID_KEYBOARD_FUNCTION
    private val mouse = GadgetNames.HID_WHEEL_MOUSE_FUNCTION
    private val hid = HidSpec(keyboard = true, mouse = true)
    private val disk = SlotSpec(0, img(1), LunMode.DISK_RO)

    /** HID on with the session open, as after the app's HidController turned it on. */
    private fun GadgetRig.hidOnAndOpen(desired: Desired = Desired(listOf(disk), hid)): DefaultGadgetController {
        val controller = controller()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(desired))
        hidNodes.reopen()
        assertEquals(2, hidNodes.relayOpenCount())
        return controller
    }

    private fun GadgetRig.assertNoHazards() = assertEquals(emptyList<String>(), fs.hidFdHazards)

    @Test
    fun `the fake kernel treats our rebind, unlink or rmdir under an open hidg fd as a hazard`() {
        val rig = GadgetRig()
        rig.hidOnAndOpen()
        assertThrows<HidFdHazard> { rig.fs.write(GadgetPaths.UDC, "") }
        rig.fs.asActor(FakeConfigFs.HAL) { rig.fs.write(GadgetPaths.UDC, "") }
        assertThrows<HidFdHazard> { rig.fs.unlink(GadgetPaths.configLink(keyboard)) }
        assertEquals(2, rig.fs.hidFdHazards.size)
        // Closed while unbound, before any rebind: closing, unlinking and removing it is fine.
        assertTrue(rig.fs.closeHidFd(keyboard, FakeHidNodes.RELAY))
        rig.fs.unlink(GadgetPaths.configLink(keyboard))
        rig.fs.rmdir(GadgetPaths.function(keyboard))
        assertEquals(2, rig.fs.hidFdHazards.size)
        assertEquals("ENOENT", assertThrows<ConfigFsException> { rig.fs.openHidFd(mouse, "late") }.errno, "no node while unbound")
        assertThrows<HidFdHazard> { rig.fs.write(GadgetPaths.UDC, udc) }
    }

    @Test
    fun `someone else rebinding under an open fd poisons it, and closing it even by exiting is the panic`() {
        val rig = GadgetRig()
        rig.hidOnAndOpen()
        rig.fs.asActor(FakeConfigFs.HAL) { rig.fs.write(GadgetPaths.UDC, "") }
        rig.fs.asActor(FakeConfigFs.HAL) { rig.fs.write(GadgetPaths.UDC, udc) }
        assertTrue(rig.fs.isPoisoned(keyboard, FakeHidNodes.RELAY))
        assertThrows<HidFdHazard> { rig.fs.closeHidFd(keyboard, FakeHidNodes.RELAY) }
        assertThrows<HidFdHazard> { rig.fs.exitHidHolder(FakeHidNodes.RELAY) }
    }

    @Test
    fun `after the HAL relinks and rebinds under an open session, nothing closes it and every hid change is refused`() {
        val rig = GadgetRig()
        val controller = rig.hidOnAndOpen()
        // Like tethering: the HAL rewrites functionN and rebinds while the relay holds both fds.
        rig.hal.setFunctions("ptp,adb")
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        val reconciled = controller.reconcile()
        rig.assertNoHazards()
        // HID off (or any re-enumeration) would have to rebind or remove them: refused, named.
        val off = assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.apply(Desired(listOf(disk))))
        assertTrue(off.reason.contains(FakeHidNodes.RELAY), off.reason)
        assertTrue(keyboard in rig.state().configLinks.values)
        assertEquals(setOf(keyboard, mouse), rig.hidNodes.quarantined, "kept open, never closed")
        rig.hidNodes.reopen()
        assertEquals(2, rig.hidNodes.relayOpenCount(), "no new fds, only the quarantined ones")
        rig.assertNoHazards()
        assertTrue(reconciled is ApplyResult.Applied || reconciled is ApplyResult.NeedsAttention, "$reconciled")
    }

    @Test
    fun `reconcile after the HAL unbinds under an open session closes it before anything binds again`() {
        val rig = GadgetRig()
        val controller = rig.hidOnAndOpen()
        rig.hal.rebindDelayMs = null
        rig.fs.asActor(FakeConfigFs.HAL) { rig.fs.write(GadgetPaths.UDC, "") }
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(udc, rig.fs.udc)
        assertEquals(0, rig.hidNodes.relayOpenCount())
        assertEquals(emptySet<String>(), rig.hidNodes.quarantined)
        rig.assertNoHazards()
        rig.hidNodes.reopen()
        assertEquals(2, rig.hidNodes.relayOpenCount())
    }

    @Test
    fun `interleaved HID on, HID off, mouse layout swaps and HAL relinks never close an fd across a rebind`() {
        for (seed in 0L until INTERLEAVING_SEED_COUNT) {
            val random = kotlin.random.Random(seed)
            val rig = GadgetRig(seed)
            val controller = rig.controller()
            repeat(INTERLEAVING_STEP_COUNT) { step ->
                val context = "seed $seed step $step"
                when (random.nextInt(5)) {
                    0 -> controller.apply(Desired(listOf(disk), hid))
                    1 -> controller.apply(Desired(listOf(disk)))
                    4 -> controller.apply(Desired(listOf(disk), hid.copy(mouseLayout = MouseLayout.BOOT)))
                    2 -> rig.hal.setFunctions(listOf("adb", "ptp,adb", "mtp,adb").random(random))
                    else -> {
                        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
                        controller.reconcile()
                    }
                }
                // The app's session (re)opens whenever it may.
                rig.hidNodes.reopen()
                assertEquals(emptyList<String>(), rig.fs.hidFdHazards, context)
            }
        }
    }

    @Test
    fun `turning HID off releases and closes the relay while bound, then removes the functions`() {
        val rig = GadgetRig()
        val controller = rig.hidOnAndOpen()
        val result = assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(Desired(listOf(disk))))
        assertTrue(result.reenumerated)
        rig.assertNoHazards()
        assertEquals(0, rig.hidNodes.relayOpenCount())
        assertEquals(listOf(true, true), rig.hidNodes.releasesWhileBound, "release-all reached the host before the unbind")
        assertTrue(rig.state().functions.none(HidFunctions::isOwnedHid))
        assertTrue(rig.hidNodes.fenced, "no reopen until the call is over")
    }

    @Test
    fun `a re-enumerating disk change with HID on closes the relay before the unbind`() {
        val rig = GadgetRig()
        val controller = rig.hidOnAndOpen()
        val twoDisks = Desired(listOf(disk, SlotSpec(1, iso(1), LunMode.CDROM)), hid)
        assertTrue(assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(twoDisks)).reenumerated)
        rig.assertNoHazards()
        rig.hidNodes.reopen()
        assertEquals(2, rig.hidNodes.relayOpenCount(), "the session reopens once the call is over")
    }

    @Test
    fun `swapping the mouse layout with the session open closes the relay first and removes the old function`() {
        val rig = GadgetRig()
        val controller = rig.hidOnAndOpen()
        for (layout in listOf(MouseLayout.BOOT, MouseLayout.WHEEL)) {
            val swapped = Desired(listOf(disk), hid.copy(mouseLayout = layout))
            assertTrue(assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(swapped)).reenumerated, "$layout")
            rig.assertNoHazards()
            assertEquals(0, rig.hidNodes.relayOpenCount(), "$layout: closed for the swap")
            val linked = rig.state().configLinks.values
            assertTrue(layout.functionName in linked, "$layout linked")
            MouseLayout.entries.filter { it != layout }.forEach { assertFalse(it.functionName in linked, "$it unlinked") }
            rig.hidNodes.reopen()
            assertEquals(2, rig.hidNodes.relayOpenCount(), "$layout: the session reopens on the new function")
        }
    }

    @Test
    fun `an in-place media swap leaves the relay open`() {
        val rig = GadgetRig()
        val controller = rig.hidOnAndOpen(Desired(listOf(SlotSpec(0, iso(1), LunMode.CDROM)), hid))
        val closesBefore = rig.hidNodes.closeAllCount
        val swapped = Desired(listOf(SlotSpec(0, iso(2), LunMode.CDROM)), hid)
        assertFalse(assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(swapped)).reenumerated)
        assertEquals(closesBefore, rig.hidNodes.closeAllCount)
        assertEquals(2, rig.hidNodes.relayOpenCount())
    }

    @Test
    fun `a stale process holding a hid node refuses the change before any op, and reconcile finishes it once it exits`() {
        val rig = GadgetRig()
        val controller = rig.hidOnAndOpen()
        rig.fs.openHidFd(keyboard, "pid 4242 (old root daemon)")
        val mutationsBefore = rig.scripted.mutationCount
        val armsBefore = rig.deadMan.arms.size
        val refused = assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.apply(Desired(listOf(disk))))
        assertTrue(refused.reason.contains("pid 4242 (old root daemon)"), refused.reason)
        assertTrue(refused.reason.contains(DefaultGadgetController.HID_NODE_HELD_REASON), refused.reason)
        assertEquals(mutationsBefore, rig.scripted.mutationCount, "nothing changed")
        assertEquals(armsBefore, rig.deadMan.arms.size, "never unbound")
        assertEquals(1, rig.hidNodes.holderScanCount - 1, "refused at the first plan, without replanning")
        rig.assertNoHazards()

        rig.fs.exitHidHolder("pid 4242 (old root daemon)")
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertInstanceOf(ApplyResult.Applied::class.java, rig.controller().reconcile())
        assertTrue(rig.state().functions.none(HidFunctions::isOwnedHid))
        rig.assertNoHazards()
    }

    @Test
    fun `another app's open hid node blocks re-enumerating, since a rebind would poison its fd too`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        rig.fs.openHidFd("hid.keyboard", "pid 777 (usb hid client)")
        val refused = assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.apply(Desired(listOf(disk))))
        assertTrue(refused.reason.contains("pid 777"), refused.reason)
        assertEquals(udc, rig.fs.udc)
        rig.assertNoHazards()
    }

    @Test
    fun `a holder that shows up after the first scan stops the rmdir itself`() {
        val rig = GadgetRig()
        rig.hidOnAndOpen()
        var scans = 0
        val lateHolder =
            object : HidNodeGuard by rig.hidNodes {
                override fun holders(functions: Collection<String>): List<String> {
                    scans++
                    // Clean for the plan's scan, held at the rmdir's own.
                    return if (scans > 1 && keyboard in functions) listOf("pid 99 (late opener)") else rig.hidNodes.holders(functions)
                }
            }
        val controller =
            DefaultGadgetController(rig.scripted, rig.hal, rig.journalStore, rig.deadMan, rig.clock, FakeHidDescriptors, lateHolder)
        val refused = assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.apply(Desired(listOf(disk))))
        assertTrue(refused.reason.contains("pid 99 (late opener)"), refused.reason)
        assertTrue(keyboard in rig.state().functions, "the held function was not removed")
        assertEquals(udc, rig.fs.udc)
        rig.assertNoHazards()
    }

    @Test
    fun `an unbound UDC is not bound over another process's open hidg fd`() {
        val rig = GadgetRig()
        val controller = rig.hidOnAndOpen()
        rig.hidNodes.closeAll()
        rig.fs.openHidFd(keyboard, "pid 31 (script)")
        rig.hal.rebindDelayMs = null
        rig.fs.asActor(FakeConfigFs.HAL) { rig.fs.write(GadgetPaths.UDC, "") }
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        val refused = assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.reconcile())
        assertTrue(refused.reason.contains("pid 31 (script)"), refused.reason)
        assertEquals("", rig.fs.udc)
        rig.assertNoHazards()

        // Closing it before any rebind is safe; then the controller binds.
        rig.fs.exitHidHolder("pid 31 (script)")
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(udc, rig.fs.udc)
        rig.assertNoHazards()
    }

    @Test
    fun `Bootleg off with the session open closes it before tearing down`() {
        val rig = GadgetRig()
        val controller = rig.hidOnAndOpen()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.bootlegOff())
        assertTrue(rig.state().functions.none(GadgetPaths::isOwnedName))
        assertEquals(0, rig.hidNodes.relayOpenCount())
        rig.assertNoHazards()
    }

    private companion object {
        const val INTERLEAVING_SEED_COUNT = 40L
        const val INTERLEAVING_STEP_COUNT = 12
    }
}
