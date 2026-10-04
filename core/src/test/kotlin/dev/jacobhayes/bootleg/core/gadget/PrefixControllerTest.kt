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
    private val prefixThenAdb = LinkOrder.PREFIX + "function0"

    private fun GadgetRig.links() = state().configLinks.keys.toList()

    private fun ApplyResult.reason(): String = (this as? ApplyResult.NeedsAttention)?.reason ?: (this as ApplyResult.Failed).reason

    @Test
    fun `first use links the prefix ahead of ADB through a USB mode round trip the host never sees`() {
        for ((startMode, through) in listOf(0L to UsbFunctions.MTP, UsbFunctions.MTP to UsbFunctions.PTP)) {
            for (halDelayMs in listOf(0L, 400L)) {
                val rig = GadgetRig()
                if (startMode != 0L) rig.hal.setFunctions("mtp,adb")
                rig.clock.sleepMs(HAL_SETTLE_MS)
                rig.usbFunctions.halDelayMs = halDelayMs
                assertInstanceOf(ApplyResult.Applied::class.java, rig.controller().apply(Desired(prefix = true)))
                assertEquals(LinkOrder.PREFIX, rig.links().take(3))
                assertEquals(listOf(through, startMode), rig.usbFunctions.sets, "through another mode and back")
                assertNull(rig.journal().usbFunctionsToRestore)
                assertEquals(listOf("start", "release"), rig.rndisDaemon.events)
                // The HAL tried to bind after each of its switches; the held function refused both.
                assertEquals(emptyList<String>(), rig.fs.bindsInExecutorWindow)
                assertTrue(rig.deadMan.disarmCount > 0, "the watchdog is disarmed once the transition is over")
            }
        }
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
        assertEquals(LinkOrder.PREFIX, rig.links().take(3))
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
        assertEquals(RndisState.READY, rig.state().rndis)
    }

    @Test
    fun `a dead daemon is restarted in place once nothing has media, at most once per interval`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(disk))
        rig.rndisDaemon.die()
        assertEquals(udc, rig.fs.udc, "with no_disconnect the gadget stays bound and the disk keeps working")
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertEquals(DefaultGadgetController.RNDIS_STOPPED_REASON, controller.reconcile().reason())
        controller.eject(0, force = false)
        repeat(2) {
            rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
            assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
            assertEquals(RndisState.READY, rig.state().rndis)
            assertEquals(LinkOrder.PREFIX, rig.links().take(3), "restarted in place: the prefix kept its place")
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
    }
}
