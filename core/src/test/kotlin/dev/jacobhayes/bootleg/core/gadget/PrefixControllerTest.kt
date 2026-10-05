package dev.jacobhayes.bootleg.core.gadget

import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.img
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Bootleg's fixed prefix (RNDIS, keyboard, mouse) through the controller, against the HAL and FunctionFS fakes. */
class PrefixControllerTest {
    private val udc = FakeConfigFs.PIXEL_UDC
    private val disk = Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO)))
    private val prefixThenAdb = LinkOrder.prefix(TetherMode.DEFAULT) + "function0"

    private fun GadgetRig.links() = state().configLinks.keys.toList()

    private fun ApplyResult.reason(): String = (this as? ApplyResult.NeedsAttention)?.reason ?: (this as ApplyResult.Failed).reason

    @Test
    fun `first use links the prefix ahead of ADB through a USB mode round trip the host never sees`() {
        for (network in TetherMode.entries) {
            for ((startMode, through) in listOf(0L to UsbFunctions.MTP, UsbFunctions.MTP to UsbFunctions.PTP)) {
                for (halDelayMs in listOf(0L, 400L)) {
                    val rig = GadgetRig()
                    if (startMode != 0L) rig.hal.setFunctions("mtp,adb")
                    rig.clock.sleepMs(HAL_SETTLE_MS)
                    rig.usbFunctions.halDelayMs = halDelayMs
                    assertInstanceOf(ApplyResult.Applied::class.java, rig.controller().apply(Desired(prefix = true, network = network)))
                    assertEquals(LinkOrder.prefix(network), rig.links().take(3))
                    assertEquals(listOf(through, startMode), rig.usbFunctions.sets, "through another mode and back")
                    assertNull(rig.journal().usbFunctionsToRestore)
                    // In NCM mode the held RNDIS function is a gate, removed once the HAL has relinked.
                    assertEquals(listOf("start", if (network == TetherMode.RNDIS) "release" else "stop"), rig.rndisDaemon.events)
                    // The HAL tried to bind after each of its switches; the held function refused both.
                    assertEquals(emptyList<String>(), rig.fs.bindsInExecutorWindow)
                    assertTrue(rig.deadMan.disarmCount > 0, "the watchdog is disarmed once the transition is over")
                }
            }
        }
    }

    @Test
    fun `a mode change re-establishes the prefix, is refused before anything changes while a disk has media, and apply never makes one`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(disk.copy(network = TetherMode.RNDIS)))
        val sets = rig.usbFunctions.sets.size
        val refused = assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.establishPrefix(TetherMode.NCM))
        assertTrue(refused.reason.contains(DefaultGadgetController.PREFIX_DISKS_EXPOSED_REASON), refused.reason)
        assertEquals(refused.reason, controller.establishmentRefusal(TetherMode.NCM))
        assertEquals(TetherMode.RNDIS, rig.journal().desired.network, "the old network function is still wanted")
        assertEquals(sets, rig.usbFunctions.sets.size, "nothing re-plugged")
        assertEquals(LinkOrder.prefix(TetherMode.RNDIS), rig.links().take(3))
        controller.eject(0, force = false)
        assertInstanceOf(ApplyResult.Applied::class.java, controller.establishPrefix(TetherMode.NCM))
        assertEquals(LinkOrder.prefix(TetherMode.NCM) + "function0", rig.links())
        assertFalse(GadgetNames.RNDIS_FUNCTION in rig.state().functions, "the old network function is gone")
        assertEquals(TetherMode.NCM, rig.journal().desired.network)
        assertNull(controller.establishPrefix(TetherMode.NCM), "already in place")
        // Disks and HID keep the network function they find.
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(disk.copy(network = TetherMode.RNDIS)))
        assertEquals(LinkOrder.prefix(TetherMode.NCM) + "function0" + GadgetNames.MASS_STORAGE_FUNCTION, rig.links())
    }

    @Test
    fun `establishment is refused up front with disks exposed, Android's tethering on, or another app's function ahead`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(disk))
        // Something unlinks our mouse (the kernel unbinds), then rebinds: the prefix is no longer whole.
        rig.fs.asActor("other") {
            rig.fs.unlink(GadgetPaths.configLink(GadgetNames.HID_MOUSE_FUNCTION))
            rig.fs.write(GadgetPaths.UDC, udc)
        }
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertTrue(controller.reconcile().reason().contains(DefaultGadgetController.PREFIX_DISKS_EXPOSED_REASON))
        assertEquals(
            img(1),
            rig
                .state()
                .luns
                .getValue(0)
                .file,
            "the disk keeps working meanwhile",
        )

        val tethering = GadgetRig()
        tethering.usbFunctions.extra = UsbFunctions.TETHERING
        assertTrue(
            tethering
                .controller()
                .apply(Desired(prefix = true))
                .reason()
                .contains(DefaultGadgetController.PREFIX_TETHERING_REASON),
        )

        val otherApp = GadgetRig(otherAppHid = true)
        repeat(2) {
            val reason = otherApp.controller().apply(Desired(prefix = true)).reason()
            assertTrue(reason.contains(DefaultGadgetController.PREFIX_FOREIGN_AHEAD_REASON) && reason.contains("hid.keyboard"), reason)
        }
        for (refused in listOf(tethering, otherApp)) {
            assertEquals(emptyList<Long>(), refused.usbFunctions.sets)
            assertEquals(0, refused.deadMan.arms.size, "never unbound")
        }
    }

    @Test
    fun `with the NCM function linked no bind fails after it, since binds wait for FunctionFS, and the dead-man unlinks ours first`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(Desired(prefix = true, network = TetherMode.NCM)))
        // adbd reopens its function around our re-plug: the bind waits for it (a failed one would throw NcmBindHazard).
        rig.fs.unreadyFunctionFs += "ffs.adb"
        rig.clock.schedule(ADB_REOPEN_MS) { rig.fs.unreadyFunctionFs -= "ffs.adb" }
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(disk))
        // It never comes back: nothing binds, and once it does, reconcile finishes.
        rig.fs.unreadyFunctionFs += "ffs.adb"
        assertFalse(controller.apply(disk.copy(slots = disk.slots.map { it.copy(mode = LunMode.CDROM) })) is ApplyResult.Applied)
        rig.fs.unreadyFunctionFs -= "ffs.adb"
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertFalse(rig.controller().reconcile() is ApplyResult.Failed)
        // A root process died with the gadget unbound: the dead-man never tries a bind with f_ncm linked.
        rig.hal.rebindDelayMs = null
        rig.fs.write(GadgetPaths.UDC, "")
        rig.deadMan.arm(udc, GadgetLimits.DEAD_MAN_REBIND_DELAY_S)
        rig.deadMan.fire()
        assertEquals(udc, rig.fs.udc, "ADB is back")
        assertEquals(listOf("function0"), rig.links())
    }

    @Test
    fun `a failed establishment gives Android its mode back, brings ADB back, and waits for the next apply`() {
        val rig = GadgetRig()
        rig.usbFunctions.halDelayMs = null
        val controller = rig.controller()
        assertFalse(controller.apply(Desired(prefix = true)) is ApplyResult.Applied)
        assertEquals(udc, rig.fs.udc, "the gadget is bound again")
        assertEquals(listOf("function0"), rig.links(), "ours are gone, ADB is back")
        assertEquals(listOf(UsbFunctions.MTP, 0L), rig.usbFunctions.sets, "the original mode was set back")
        assertTrue(rig.journal().desired.wantsPrefix, "the prefix is still wanted")
        val sets = rig.usbFunctions.sets.size
        repeat(2) {
            rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
            assertTrue(controller.reconcile().reason().contains(DefaultGadgetController.PREFIX_FAILED_REASON))
        }
        assertEquals(sets, rig.usbFunctions.sets.size, "reconcile doesn't re-plug USB again")
        rig.usbFunctions.halDelayMs = 0
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(Desired(prefix = true)))
        assertEquals(LinkOrder.prefix(TetherMode.DEFAULT), rig.links().take(3))
    }

    @Test
    fun `a root process that dies in the middle of the round trip leaves it for the next one, which restores the mode first`() {
        val rig = GadgetRig()
        val usb = rig.usbFunctions
        val dying =
            object : UsbFunctions by usb {
                override fun set(functions: Long) {
                    if (usb.sets.isNotEmpty()) throw IllegalStateException("the root process died")
                    usb.set(functions)
                }
            }
        val first =
            DefaultGadgetController(
                rig.scripted,
                rig.hal,
                rig.journalStore,
                rig.deadMan,
                rig.clock,
                FakeHidDescriptors,
                rig.hidNodes,
                newLunInstanceNonce = { GadgetRig.NONCE },
                rndisDaemon = rig.rndisDaemon,
                usbFunctions = dying,
            )
        runCatching { first.apply(Desired(prefix = true)) }
        assertEquals(UsbFunctions.MTP, usb.current(), "left in MTP")
        assertEquals(0L, rig.journal().usbFunctionsToRestore)
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        val result = rig.controller().reconcile()
        assertInstanceOf(ApplyResult.Applied::class.java, result, "$result")
        assertEquals(0L, usb.current(), "back in the original mode before the release")
        assertNull(rig.journal().usbFunctionsToRestore)
        assertEquals(prefixThenAdb, rig.links())
        assertTrue(LinkOrder.prefixServed(rig.state(), TetherMode.DEFAULT))
    }

    @Test
    fun `a dead daemon is restarted in place once nothing has media, at most once per interval`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(disk.copy(network = TetherMode.RNDIS)))
        rig.rndisDaemon.die()
        assertEquals(udc, rig.fs.udc, "with no_disconnect the gadget stays bound and the disk keeps working")
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertEquals(DefaultGadgetController.RNDIS_STOPPED_REASON, controller.reconcile().reason())
        controller.eject(0, force = false)
        repeat(2) {
            rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
            assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
            assertEquals(RndisState.READY, rig.state().rndis)
            assertEquals(LinkOrder.prefix(TetherMode.RNDIS), rig.links().take(3), "restarted in place: the prefix kept its place")
            // It dies again right away: left dead and reported until the interval has passed.
            rig.rndisDaemon.die()
            rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
            assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.reconcile())
            rig.clock.sleepMs(DefaultGadgetController.RNDIS_REPAIR_INTERVAL_MS_MIN)
        }
        assertEquals(3, rig.rndisDaemon.events.count { it == "start" })
    }

    @Test
    fun `the dead-man brings ADB back without any function of ours, and the next reconcile sets the prefix up`() {
        val rig = GadgetRig()
        rig.hal.rebindDelayMs = null
        // A root process that died mid-establishment: the prefix linked, the daemon held, unbound.
        rig.fs.mkdir(GadgetPaths.function(GadgetNames.RNDIS_FUNCTION))
        rig.rndisDaemon.start()
        rig.fs.write(GadgetPaths.UDC, "")
        rig.fs.symlink(GadgetPaths.function(GadgetNames.RNDIS_FUNCTION), GadgetPaths.configLink(GadgetNames.RNDIS_FUNCTION))
        assertTrue(runCatching { rig.fs.write(GadgetPaths.UDC, udc) }.exceptionOrNull() is IOException, "a held function can't bind")
        rig.deadMan.arm(udc, GadgetLimits.DEAD_MAN_REBIND_DELAY_S)
        rig.deadMan.fire()
        assertEquals(udc, rig.fs.udc, "ADB is back")
        assertEquals(listOf("function0"), rig.links())
        rig.journalStore.save(Journal(desired = Desired(prefix = true), udc = udc))
        rig.hal.rebindDelayMs = FakeHal.REBIND_DELAY_MS
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertInstanceOf(ApplyResult.Applied::class.java, rig.controller().reconcile())
        assertEquals(prefixThenAdb, rig.links())
    }

    @Test
    fun `HID and disks never move the prefix, turning everything off keeps it idle, and Bootleg-off forgets it`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(disk.copy(hid = HidSpec(keyboard = true, mouse = true))))
        assertEquals(prefixThenAdb + GadgetNames.MASS_STORAGE_FUNCTION, rig.links())
        assertFalse(assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(disk)).reenumerated, "HID off is idle")
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(Desired()))
        assertTrue(rig.journal().desired.prefix)
        assertEquals(prefixThenAdb, rig.links())
        assertInstanceOf(ApplyResult.Applied::class.java, controller.bootlegOff())
        assertFalse(rig.journal().desired.wantsPrefix)
        assertTrue(rig.state().functions.none(GadgetPaths::isOwnedName))
        assertEquals(listOf("function0"), rig.links())
    }

    private companion object {
        const val HAL_SETTLE_MS = 2_000L
        const val ADB_REOPEN_MS = 500L
    }
}
