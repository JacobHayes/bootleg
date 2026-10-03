package dev.jacobhayes.bootleg.core.gadget

import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.img
import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.iso
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DefaultGadgetControllerTest {
    private val udc = FakeConfigFs.PIXEL_UDC
    private val twoDisks =
        Desired(
            listOf(SlotSpec(0, iso(1), LunMode.CDROM), SlotSpec(1, img(1), LunMode.DISK_RW)),
            HidSpec(keyboard = true, mouse = true),
        )

    private fun GadgetRig.assertHealthy(foreignBefore: GadgetState? = null) {
        val state = state()
        assertEquals(emptyList<String>(), GadgetInvariants.violations(state, GadgetTarget.of(journal()), foreignBefore = foreignBefore))
        assertEquals(emptyList<String>(), fs.foreignWrites, "Bootleg wrote foreign paths")
        assertEquals(emptyList<String>(), fs.boundSymlinkRejections, "linked while bound")
        assertEquals(TransitionPhase.IDLE, journal().phase)
        assertSwitchWindowsFit()
    }

    @Test
    fun `apply exports disks and HID next to the foreign functions, arming the dead-man first`() {
        val rig = GadgetRig()
        val before = rig.state()
        val result = rig.controller().apply(twoDisks)
        val applied = assertInstanceOf(ApplyResult.Applied::class.java, result)
        assertTrue(applied.reenumerated)
        rig.assertHealthy(before)
        assertEquals(listOf(udc to GadgetLimits.DEAD_MAN_REBIND_DELAY_S), rig.deadMan.arms)
        assertEquals(listOf(true), rig.deadMan.boundAtArm, "armed before unbinding")
        assertEquals(LunState(iso(1), ro = true, cdrom = true, removable = true, nofua = false, GadgetRig.MARKER), rig.state().luns[0])
        assertEquals(LunState(img(1), ro = false, cdrom = false, removable = true, nofua = false, GadgetRig.MARKER), rig.state().luns[1])
        assertEquals(mapOf(0 to SlotIntent.EXPORTED, 1 to SlotIntent.EXPORTED), rig.journal().slotIntents)
        assertTrue(rig.clock.sleptMs >= DefaultGadgetController.HAL_QUIET_MS, "waited for the HAL to be quiet")
    }

    @Test
    fun `journal records each phase before the step it covers`() {
        val rig = GadgetRig()
        val phasesWithUdc = mutableListOf<Pair<TransitionPhase, String>>()
        val recordingStore =
            object : JournalStore by rig.journalStore {
                override fun save(journal: Journal) {
                    phasesWithUdc += journal.phase to rig.fs.udc
                    rig.journalStore.save(journal)
                }
            }
        val controller =
            DefaultGadgetController(rig.scripted, rig.hal, recordingStore, rig.deadMan, rig.clock, FakeHidDescriptors, rig.hidNodes)
        controller.apply(twoDisks)
        val phases = phasesWithUdc.map { it.first }.distinctConsecutive()
        assertEquals(listOf(TransitionPhase.MUTATING, TransitionPhase.UNBINDING, TransitionPhase.IDLE), phases)
        assertEquals(udc, phasesWithUdc.first { it.first == TransitionPhase.UNBINDING }.second, "UNBINDING saved before unbinding")
        // An fsync inside the unbound window would eat into the time before the HAL rebinds.
        assertTrue(phasesWithUdc.all { it.second.isNotEmpty() }, "journal written while unbound: $phasesWithUdc")
    }

    @Test
    fun `growing an export to three LUNs and HID detaches, rebuilds and relinks, each window well inside the HAL's`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        val before = rig.state()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO)))))
        val windowsBefore = rig.fs.executorWindowsUs.size
        val threeDisks =
            Desired(
                listOf(SlotSpec(0, img(1), LunMode.DISK_RO), SlotSpec(1, img(2), LunMode.DISK_RO), SlotSpec(2, iso(1), LunMode.CDROM)),
                HidSpec(keyboard = true, mouse = true),
            )
        val result = assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(threeDisks))
        assertTrue(result.reenumerated)
        assertEquals(
            listOf(img(1), img(2), iso(1)),
            rig
                .state()
                .luns.values
                .map { it.file },
        )
        assertEquals(2, rig.fs.executorWindowsUs.size - windowsBefore, "detach switch, then relink switch")
        assertTrue(rig.hal.dropCount > 0, "the HAL's post-bind drop happened and was waited out")
        rig.assertHealthy(before)
    }

    @Test
    fun `a HAL rebind inside the switch is undone and the switch carries on`() {
        val rig = GadgetRig()
        var rebound = false
        val interfering =
            ScriptedConfigFs(rig.fs, rig.clock) { call ->
                // The HAL binds between our unbind and our second link (as if its timer fired early).
                if (call == "symlink configs/b.1/hid.bootleg_kbd" && !rebound) {
                    rebound = true
                    rig.fs.asActor(FakeConfigFs.HAL) { rig.fs.write(GadgetPaths.UDC, udc) }
                }
            }
        val controller =
            DefaultGadgetController(interfering, rig.hal, rig.journalStore, rig.deadMan, rig.clock, FakeHidDescriptors, rig.hidNodes)
        val result = assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(twoDisks))
        assertTrue(result.reenumerated)
        assertEquals(listOf("${FakeConfigFs.EXECUTOR} configs/b.1/hid.bootleg_kbd"), rig.fs.boundSymlinkRejections)
        assertEquals(listOf(true, true), rig.deadMan.boundAtArm, "dead-man re-armed before unbinding again")
        assertEquals(listOf(FakeConfigFs.HAL), rig.fs.bindsInExecutorWindow)
        assertEquals(emptyList<String>(), GadgetInvariants.violations(rig.state(), GadgetTarget.of(rig.journal())))
    }

    @Test
    fun `a HAL that rebinds after every unbind is fought a bounded number of times and leaves the gadget bound`() {
        val rig = GadgetRig()
        rig.hal.rebindDelayMs = 0L..0L
        val result = rig.controller().apply(twoDisks)
        assertInstanceOf(ApplyResult.NeedsAttention::class.java, result)
        assertEquals(udc, rig.fs.udc)
        val armCountMax = (1 + GadgetLimits.REPLAN_COUNT_MAX) * (1 + DefaultGadgetController.SWITCH_REBIND_COUNT_MAX + 3)
        assertTrue(rig.deadMan.arms.size <= armCountMax, "${rig.deadMan.arms.size} arms")
        assertEquals(emptyList<String>(), rig.fs.foreignWrites)
    }

    @Test
    fun `reconcile lets the HAL rebind instead of binding against it`() {
        for (rebindDelayMs in listOf(400L, 1_500L)) {
            val rig = GadgetRig()
            val controller = rig.controller()
            controller.apply(twoDisks)
            val windowsBefore = rig.fs.executorWindowsUs.size
            rig.hal.rebindDelayMs = rebindDelayMs..rebindDelayMs
            rig.fs.asActor("other") { rig.fs.write(GadgetPaths.UDC, "") }
            rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
            val result = assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile(), "rebind after ${rebindDelayMs}ms")
            assertFalse(result.reenumerated)
            assertEquals(windowsBefore, rig.fs.executorWindowsUs.size, "the controller didn't bind")
            rig.assertHealthy()
        }
    }

    @Test
    fun `same-mode swap stays in place without re-enumerating`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        controller.apply(twoDisks)
        rig.deadMan.arms.clear()
        val swapped = twoDisks.copy(slots = listOf(SlotSpec(0, iso(2), LunMode.CDROM), twoDisks.slots[1]))
        val result = assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(swapped))
        assertFalse(result.reenumerated)
        assertEquals(emptyList<Pair<String, Int>>(), rig.deadMan.arms)
        assertEquals(iso(2), rig.state().luns[0]?.file)
        rig.assertHealthy()
    }

    @Test
    fun `a slot whose file can't be opened fails alone`() {
        val rig = GadgetRig()
        val desired = twoDisks.copy(slots = listOf(SlotSpec(0, "/data/media/0/bootleg/missing.iso", LunMode.CDROM), twoDisks.slots[1]))
        val result = assertInstanceOf(ApplyResult.Partial::class.java, rig.controller().apply(desired))
        assertEquals(setOf(0), result.slotFailures.keys)
        assertTrue(result.slotFailures.getValue(0).contains("ENOENT"))
        assertEquals(SlotIntent.FAILED, rig.journal().slotIntents[0])
        assertEquals("", rig.state().luns[0]?.file)
        assertEquals(img(1), rig.state().luns[1]?.file)
        rig.assertHealthy()
    }

    @Test
    fun `host eject is recorded and never reinserted until asked`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        controller.apply(twoDisks)
        rig.fs.hostEject(0)
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(SlotIntent.HOST_EJECTED, rig.journal().slotIntents[0])
        assertEquals("", rig.state().luns[0]?.file)

        // Re-applying the same slot (e.g. while adding another) keeps it ejected.
        controller.apply(twoDisks)
        assertEquals(SlotIntent.HOST_EJECTED, rig.journal().slotIntents[0])
        assertEquals("", rig.state().luns[0]?.file)

        assertInstanceOf(ApplyResult.Applied::class.java, controller.reinsert(0))
        assertEquals(SlotIntent.EXPORTED, rig.journal().slotIntents[0])
        assertEquals(iso(1), rig.state().luns[0]?.file)
        rig.assertHealthy()
    }

    @Test
    fun `observe records a host eject without touching the gadget`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        controller.apply(twoDisks)
        assertEquals(SlotIntent.EXPORTED, controller.observe().slotIntents[0])

        rig.fs.hostEject(0)
        val stateBefore = rig.state()
        val observed = controller.observe()
        assertEquals(SlotIntent.HOST_EJECTED, observed.slotIntents[0])
        assertEquals(SlotIntent.EXPORTED, observed.slotIntents[1])
        assertEquals(observed, rig.journal())
        assertEquals(stateBefore, rig.state())

        // Never brings the media back; a later reconcile keeps it ejected too.
        assertEquals(observed, controller.observe())
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(SlotIntent.HOST_EJECTED, rig.journal().slotIntents[0])
        assertEquals("", rig.state().luns[0]?.file)
        rig.assertHealthy()
    }

    @Test
    fun `observe ignores an empty LUN while our own transition is in flight`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        controller.apply(twoDisks)
        rig.journalStore.save(rig.journal().copy(phase = TransitionPhase.MUTATING))
        rig.fs.hostEject(0)
        assertEquals(SlotIntent.EXPORTED, controller.observe().slotIntents[0])
    }

    @Test
    fun `host eject survives a HAL unbind or a removed link before reconcile`() {
        val interruptions =
            mapOf<String, (GadgetRig) -> Unit>(
                "HAL unbind" to { rig -> rig.fs.asActor(FakeConfigFs.HAL) { rig.fs.write(GadgetPaths.UDC, "") } },
                // Unlinking from a bound gadget also unbinds it.
                "link removal" to { rig ->
                    rig.fs.asActor("other") { rig.fs.unlink(GadgetPaths.configLink(GadgetNames.MASS_STORAGE_FUNCTION)) }
                },
            )
        for ((name, interrupt) in interruptions) {
            val rig = GadgetRig()
            val controller = rig.controller()
            controller.apply(twoDisks)
            rig.fs.hostEject(0)
            interrupt(rig)
            assertEquals("", rig.fs.udc, name)
            assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile(), name)
            assertEquals(SlotIntent.HOST_EJECTED, rig.journal().slotIntents[0], name)
            assertEquals(SlotIntent.EXPORTED, rig.journal().slotIntents[1], name)
            assertEquals("", rig.state().luns[0]?.file, name)
            assertEquals(img(1), rig.state().luns[1]?.file, name)
            rig.assertHealthy()
        }
    }

    @Test
    fun `a LUN removed or recreated by someone else is not a host eject`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        controller.apply(twoDisks)
        val massStorage = GadgetNames.MASS_STORAGE_FUNCTION
        rig.fs.asActor("other") {
            rig.fs.unlink(GadgetPaths.configLink(massStorage))
            rig.fs.rmdir(GadgetPaths.lunDir(1))
            rig.fs.rmdir(GadgetPaths.function(massStorage))
        }
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(mapOf(0 to SlotIntent.EXPORTED, 1 to SlotIntent.EXPORTED), rig.journal().slotIntents)
        assertEquals(
            listOf(iso(1), img(1)),
            rig
                .state()
                .luns.values
                .map { it.file },
        )
        rig.assertHealthy()

        // Recreated with kernel defaults: both LUNs are empty, but lun 0 is no longer our CD-ROM, so neither
        // empty LUN (not even the default-looking RW one) is trusted as a host eject.
        rig.fs.asActor("other") {
            rig.fs.unlink(GadgetPaths.configLink(massStorage))
            rig.fs.rmdir(GadgetPaths.lunDir(1))
            rig.fs.rmdir(GadgetPaths.function(massStorage))
            rig.fs.mkdir(GadgetPaths.function(massStorage))
            rig.fs.mkdir(GadgetPaths.lunDir(1))
        }
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(mapOf(0 to SlotIntent.EXPORTED, 1 to SlotIntent.EXPORTED), rig.journal().slotIntents)
        assertEquals(
            listOf(iso(1), img(1)),
            rig
                .state()
                .luns.values
                .map { it.file },
        )
        rig.assertHealthy()
    }

    @Test
    fun `a recreated function whose defaults match every RW slot is not a host eject`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        val allWritable = Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RW), SlotSpec(1, img(2), LunMode.DISK_RW)))
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(allWritable))
        assertEquals(
            listOf(GadgetRig.MARKER, GadgetRig.MARKER),
            rig
                .state()
                .luns.values
                .map { it.inquiryString },
        )
        val massStorage = GadgetNames.MASS_STORAGE_FUNCTION
        // Recreated and relinked by someone else: ro=0, cdrom=0 and the LUN set all match the journal.
        rig.fs.asActor("other") {
            rig.fs.unlink(GadgetPaths.configLink(massStorage))
            rig.fs.rmdir(GadgetPaths.lunDir(1))
            rig.fs.rmdir(GadgetPaths.function(massStorage))
            rig.fs.mkdir(GadgetPaths.function(massStorage))
            rig.fs.mkdir(GadgetPaths.lunDir(1))
            rig.fs.symlink(GadgetPaths.function(massStorage), GadgetPaths.configLink(massStorage))
            rig.fs.write(GadgetPaths.UDC, udc)
        }
        assertEquals(
            listOf("", ""),
            rig
                .state()
                .luns.values
                .map { it.file },
        )
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(mapOf(0 to SlotIntent.EXPORTED, 1 to SlotIntent.EXPORTED), rig.journal().slotIntents)
        assertEquals(
            listOf(img(1), img(2)),
            rig
                .state()
                .luns.values
                .map { it.file },
        )
        rig.assertHealthy()

        // The same function emptied by the host (marker intact) still is one.
        rig.fs.hostEject(1)
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(SlotIntent.HOST_EJECTED, rig.journal().slotIntents[1])
        assertEquals("", rig.state().luns[1]?.file)
    }

    @Test
    fun `a journal from before the LUN marker keeps host ejects and adopts a nonce on the next apply`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(twoDisks))
        // What an older version left behind: no nonce in the journal, no marker on the LUNs.
        rig.journalStore.save(rig.journal().copy(lunInstanceNonce = ""))
        rig.fs.asActor("old") { (0..1).forEach { rig.fs.write(GadgetPaths.lunAttribute(it, "inquiry_string"), "") } }
        rig.fs.hostEject(0)
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(SlotIntent.HOST_EJECTED, rig.journal().slotIntents[0])
        assertEquals("", rig.journal().lunInstanceNonce, "a clean reconcile changes nothing")

        val applied = assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(twoDisks))
        assertFalse(applied.reenumerated, "the marker is written in place")
        assertEquals(GadgetRig.NONCE, rig.journal().lunInstanceNonce)
        assertEquals(
            listOf(GadgetRig.MARKER, GadgetRig.MARKER),
            rig
                .state()
                .luns.values
                .map { it.inquiryString },
        )
        assertEquals(SlotIntent.HOST_EJECTED, rig.journal().slotIntents[0])
        rig.assertHealthy()
    }

    @Test
    fun `ADB lost mid-transition fails the apply, but ADB absent beforehand is fine`() {
        val rig = GadgetRig()
        val adbLink = GadgetPaths.configLink("function0")
        val dropping =
            ScriptedConfigFs(rig.fs) { call ->
                if (call == "mkdir ${GadgetPaths.function(GadgetNames.MASS_STORAGE_FUNCTION)}" && rig.fs.exists(adbLink)) {
                    rig.fs.asActor(FakeConfigFs.HAL) { rig.fs.unlink(adbLink) }
                }
            }
        val controller =
            DefaultGadgetController(dropping, rig.hal, rig.journalStore, rig.deadMan, rig.clock, FakeHidDescriptors, rig.hidNodes)
        val result = assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.apply(twoDisks))
        assertTrue(result.reason.contains("ADB (ffs.adb) is no longer linked"), result.reason)
        assertEquals(udc, rig.fs.udc, "gadget stays bound")
        assertFalse(rig.fs.exists(adbLink), "Bootleg never restores foreign links")
        assertEquals(emptyList<String>(), GadgetInvariants.violations(rig.state(), GadgetTarget.of(rig.journal())))
        assertEquals(TransitionPhase.IDLE, rig.journal().phase, "only foreign state is off, so host ejects stay trusted")

        // With ADB already gone (USB debugging off), the next apply has nothing to lose.
        val swapped = twoDisks.copy(slots = listOf(SlotSpec(0, iso(2), LunMode.CDROM), twoDisks.slots[1]))
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(swapped))
        rig.assertHealthy()
    }

    @Test
    fun `a stable empty sys usb config counts as settled`() {
        for (reportsUsbState in listOf(false, true)) {
            val rig = GadgetRig()
            rig.hal.reportsUsbState = reportsUsbState
            rig.hal.config = ""
            assertInstanceOf(ApplyResult.Applied::class.java, rig.controller().apply(twoDisks), "reportsUsbState=$reportsUsbState")
            assertTrue(rig.clock.sleptMs < DefaultGadgetController.HAL_QUIET_WAIT_MS_MAX, "slept ${rig.clock.sleptMs}ms")
        }
    }

    @Test
    fun `user eject empties the LUN, and a prevented eject asks for force`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        controller.apply(twoDisks)
        rig.fs.preventRemoval += 1
        val prevented = assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.eject(1, force = false))
        assertTrue(prevented.reason.startsWith(DefaultGadgetController.EJECT_PREVENTED_REASON))
        assertEquals(SlotIntent.EXPORTED, rig.journal().slotIntents[1])
        assertEquals(img(1), rig.state().luns[1]?.file)

        assertInstanceOf(ApplyResult.Applied::class.java, controller.eject(1, force = true))
        assertEquals(SlotIntent.USER_EJECTED, rig.journal().slotIntents[1])
        assertEquals("", rig.state().luns[1]?.file)
        // The forced eject cleared the host's prevent-removal, so the slot changes normally again.
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reinsert(1))
        assertEquals(img(1), rig.state().luns[1]?.file)
        assertInstanceOf(ApplyResult.Applied::class.java, controller.eject(1, force = false))
        assertEquals("", rig.state().luns[1]?.file)

        assertInstanceOf(ApplyResult.Applied::class.java, controller.eject(0, force = false))
        assertEquals(SlotIntent.USER_EJECTED, rig.journal().slotIntents[0])
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(
            listOf("", ""),
            rig
                .state()
                .luns.values
                .map { it.file },
        )
        rig.assertHealthy()
    }

    @Test
    fun `reconcile restores drift, but only once per interval`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        controller.apply(twoDisks)
        // Something else removes our link (and the kernel unbinds), then rebinds.
        rig.fs.asActor("other") {
            rig.fs.unlink("configs/b.1/hid.bootleg_mouse")
            rig.fs.write("UDC", udc)
        }
        val startMs = rig.clock.nowMs
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        rig.assertHealthy()
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertTrue(rig.clock.nowMs - startMs >= GadgetLimits.RECONCILE_INTERVAL_MS_MIN, "second reconcile waited for the interval")
    }

    @Test
    fun `reconcile gives up after the attempt cap`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        controller.apply(twoDisks)
        rig.fs.asActor("other") {
            rig.fs.unlink("configs/b.1/hid.bootleg_mouse")
            rig.fs.write("UDC", udc)
        }
        // Every link attempt fails from now on.
        rig.scripted.faultAt = (rig.scripted.mutationCount until rig.scripted.mutationCount + 10_000).toSet()
        val results = (0..GadgetLimits.RECONCILE_ATTEMPT_COUNT_MAX).map { controller.reconcile() }
        results.forEach { assertInstanceOf(ApplyResult.NeedsAttention::class.java, it) }
        assertTrue((results.last() as ApplyResult.NeedsAttention).reason.contains("reconcile attempts"))
        assertEquals(udc, rig.fs.udc, "gadget stays bound")
    }

    @Test
    fun `a HAL that never settles needs attention and nothing is touched`() {
        for (reportsUsbState in listOf(false, true)) {
            val rig = GadgetRig()
            rig.hal.reportsUsbState = reportsUsbState
            rig.hal.busyFor(60_000)
            val result = rig.controller().apply(twoDisks)
            assertInstanceOf(ApplyResult.NeedsAttention::class.java, result)
            assertEquals(0, rig.scripted.mutationCount)
            assertTrue(rig.clock.sleptMs <= DefaultGadgetController.HAL_QUIET_WAIT_MS_MAX + DefaultGadgetController.HAL_POLL_INTERVAL_MS)
        }
    }

    @Test
    fun `apply waits for a switching HAL to settle, with or without sys usb state`() {
        for (reportsUsbState in listOf(false, true)) {
            val rig = GadgetRig()
            rig.hal.reportsUsbState = reportsUsbState
            rig.hal.busyFor(3_000)
            assertInstanceOf(ApplyResult.Applied::class.java, rig.controller().apply(twoDisks))
            assertTrue(
                rig.clock.sleptMs >= 3_000 + DefaultGadgetController.HAL_QUIET_MS - DefaultGadgetController.HAL_POLL_INTERVAL_MS,
                "slept ${rig.clock.sleptMs}ms",
            )
        }
    }

    @Test
    fun `bootlegOff removes only Bootleg functions and leaves the gadget bound`() {
        val rig = GadgetRig()
        val before = rig.state()
        val controller = rig.controller()
        controller.apply(twoDisks)
        assertInstanceOf(ApplyResult.Applied::class.java, controller.bootlegOff())
        assertEquals(before.copy(usbState = "", usbConfig = ""), rig.state().copy(usbState = "", usbConfig = ""))
        assertEquals(Desired(), rig.journal().desired)
        rig.assertHealthy(before)
    }

    @Test
    fun `a gadget that can't bind falls back to Bootleg-off`() {
        val rig = GadgetRig()
        rig.fs.rejectBind = { links -> links.keys.any { it.contains("bootleg_mouse") } }
        val result = assertInstanceOf(ApplyResult.Failed::class.java, rig.controller().apply(twoDisks))
        assertTrue(result.reason.contains("Bootleg-off performed"), result.reason)
        assertEquals(udc, rig.fs.udc)
        assertTrue(rig.state().functions.none(GadgetPaths::isOwnedName))
        assertEquals(emptyList<String>(), rig.fs.foreignWrites)
    }

    @Test
    fun `invalid desired is rejected before anything happens`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        val bad =
            listOf(
                Desired(listOf(SlotSpec(1, img(1), LunMode.DISK_RO))),
                Desired(listOf(SlotSpec(0, "/sdcard/bootleg/x.img", LunMode.DISK_RO))),
                Desired(listOf(SlotSpec(0, "/data/media/0/bootleg/../secret.img", LunMode.DISK_RO))),
                Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RW), SlotSpec(1, img(1), LunMode.DISK_RO))),
                Desired((0..GadgetLimits.LUN_COUNT_MAX).map { SlotSpec(it, img(it), LunMode.DISK_RO) }),
            )
        bad.forEach { assertThrows<IllegalArgumentException> { controller.apply(it) } }
        assertEquals(0, rig.scripted.mutationCount)
    }

    /**
     * Audits each reconfiguration op (mkdir, rmdir, attribute write) at the moment it reaches the kernel
     * model: it must run stably bound with MUTATING journaled, or inside a switch window (UNBINDING
     * journaled) while nothing at all is linked. MUTATING may only be journaled while bound, unless
     * nothing is linked (then nothing can bind).
     */
    private class PhaseAudit(
        private val rig: GadgetRig,
    ) {
        val violations = mutableListOf<String>()
        var boundOpCount = 0
            private set
        var windowOpCount = 0
            private set

        init {
            rig.fs.beforeMutation = { actor, path -> if (actor == FakeConfigFs.EXECUTOR) audit(path) }
        }

        private fun audit(path: String) {
            if (path == GadgetPaths.UDC || path.startsWith(GadgetNames.CONFIG_DIR + "/")) return
            val phase = rig.journal().phase
            val links = GadgetReader(rig.fs).read().configLinks
            val boundForUs = rig.fs.boundSinceUs?.let { rig.clock.nowUs - it }
            when {
                phase == TransitionPhase.MUTATING && boundForUs != null && boundForUs >= DefaultGadgetController.STABLE_BOUND_MS * 1_000 ->
                    boundOpCount++
                phase == TransitionPhase.UNBINDING && rig.fs.udc.isEmpty() && links.isEmpty() -> windowOpCount++
                else -> violations += "$path: phase $phase, UDC bound for ${boundForUs}us, links $links"
            }
        }

        private val store =
            object : JournalStore by rig.journalStore {
                override fun save(journal: Journal) {
                    if (journal.phase == TransitionPhase.MUTATING &&
                        rig.fs.udc.isEmpty() &&
                        GadgetReader(rig.fs).read().configLinks.isNotEmpty()
                    ) {
                        violations += "MUTATING journaled while unbound"
                    }
                    rig.journalStore.save(journal)
                }
            }

        fun controller() = DefaultGadgetController(rig.scripted, rig.hal, store, rig.deadMan, rig.clock, FakeHidDescriptors, rig.hidNodes)
    }

    @Test
    fun `every reconfiguration op runs stably bound with MUTATING journaled`() {
        val rig = GadgetRig()
        val before = rig.state()
        val audit = PhaseAudit(rig)
        val controller = audit.controller()
        val swapped = twoDisks.copy(slots = listOf(SlotSpec(0, iso(2), LunMode.CDROM), twoDisks.slots[1]))
        val threeDisks =
            Desired(
                listOf(SlotSpec(0, img(1), LunMode.DISK_RO), SlotSpec(1, img(2), LunMode.DISK_RO), SlotSpec(2, iso(1), LunMode.CDROM)),
                HidSpec(keyboard = true),
            )
        val steps =
            listOf<Pair<String, () -> ApplyResult>>(
                "export" to { controller.apply(twoDisks) },
                "swap in place" to { controller.apply(swapped) },
                "change LUN count and modes (detach, reconfigure, relink)" to { controller.apply(threeDisks) },
                "Bootleg off" to { controller.bootlegOff() },
            )
        for ((name, step) in steps) assertInstanceOf(ApplyResult.Applied::class.java, step(), name)
        assertEquals(emptyList<String>(), audit.violations)
        assertTrue(audit.boundOpCount > 0)
        assertEquals(0, audit.windowOpCount, "with ADB linked, nothing reconfigures inside a window")
        assertTrue(rig.hal.dropCount > 0, "the HAL's post-bind drops happened and were waited out")
        rig.assertHealthy(before)
    }

    @Test
    fun `with nothing else linked, reconfiguration runs inside the window and the UDC ends bound`() {
        val rig = GadgetRig().withNothingLinked()
        val audit = PhaseAudit(rig)
        val controller = audit.controller()
        val cdrom = Desired(listOf(SlotSpec(0, iso(1), LunMode.CDROM)))
        val disk = Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO)))
        val windowsBefore = rig.fs.executorWindowsUs.size

        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(cdrom), "export from nothing")
        assertEquals(udc, rig.fs.udc)
        val windowOpsAfterExport = audit.windowOpCount
        assertTrue(windowOpsAfterExport > 0, "built inside the window")
        // ro/cdrom change with nothing else linked: one switch detaches, reconfigures and relinks.
        assertInstanceOf(ApplyResult.Applied::class.java, controller.apply(disk), "mode change")
        assertTrue(audit.windowOpCount > windowOpsAfterExport, "reconfigured inside the window")
        // The export started unbound (nothing to unbind); the mode change is a single window, not a detach plus a relink.
        assertEquals(1, rig.fs.executorWindowsUs.size - windowsBefore, "one window, no separate detach")
        val marker = LunMarker.inquiryString(rig.journal().lunInstanceNonce)
        assertEquals(LunState(img(1), ro = true, cdrom = false, removable = true, nofua = false, marker), rig.state().luns[0])
        assertEquals(udc, rig.fs.udc)
        assertEquals(emptyList<String>(), rig.fs.bindsInExecutorWindow)
        // The window holds a few extra attribute ops (≤ 2ms each); still far inside the HAL's 350ms.
        assertTrue((rig.fs.executorWindowsUs.maxOrNull() ?: 0) < GadgetRig.SWITCH_WINDOW_US_MAX + 20_000)

        // Off with nothing else linked: removed inside the window and left unbound, as nothing can bind.
        assertInstanceOf(ApplyResult.Applied::class.java, controller.bootlegOff())
        assertEquals("", rig.fs.udc)
        assertTrue(rig.state().functions.none(GadgetPaths::isOwnedName))
        assertEquals(emptyList<String>(), audit.violations)
        assertEquals(emptyList<String>(), rig.fs.foreignWrites)
        assertEquals(TransitionPhase.IDLE, rig.journal().phase)
    }

    @Test
    fun `a file that can't be opened inside the window fails only its slot`() {
        val rig = GadgetRig().withNothingLinked()
        val audit = PhaseAudit(rig)
        val desired = Desired(listOf(SlotSpec(0, "/data/media/0/bootleg/missing.iso", LunMode.CDROM), SlotSpec(1, img(1), LunMode.DISK_RO)))
        val result = assertInstanceOf(ApplyResult.Partial::class.java, audit.controller().apply(desired))
        assertEquals(setOf(0), result.slotFailures.keys)
        assertEquals(SlotIntent.FAILED, rig.journal().slotIntents[0])
        assertEquals(
            listOf("", img(1)),
            rig
                .state()
                .luns.values
                .map { it.file },
        )
        assertEquals(udc, rig.fs.udc)
        assertEquals(emptyList<String>(), audit.violations)
    }

    @Test
    fun `a UDC that never stays bound long enough is reported and nothing is reconfigured`() {
        val rig = GadgetRig()
        rig.hal.rebindDelayMs = null
        rig.hal.postBindDropDelayMs = null
        // Something keeps dropping the UDC for 150ms every 700ms (long enough for the polls to see): never bound for STABLE_BOUND_MS.
        for (cycle in 0 until FLAP_CYCLE_COUNT) {
            rig.clock.schedule(cycle * FLAP_INTERVAL_MS) { rig.fs.asActor("other") { runCatching { rig.fs.write(GadgetPaths.UDC, "") } } }
            rig.clock.schedule(cycle * FLAP_INTERVAL_MS + FLAP_DOWN_MS) {
                rig.fs.asActor("other") { runCatching { rig.fs.write(GadgetPaths.UDC, udc) } }
            }
        }
        var reconfigurations = 0
        rig.fs.beforeMutation = { actor, path -> if (actor == FakeConfigFs.EXECUTOR && path != GadgetPaths.UDC) reconfigurations++ }
        val controller = rig.controller()
        val startMs = rig.clock.nowMs
        val applied = assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.apply(twoDisks))
        assertTrue(applied.reason.contains("bound"), applied.reason)
        assertTrue(
            rig.clock.nowMs - startMs <= DefaultGadgetController.HAL_QUIET_WAIT_MS_MAX + 2 * DefaultGadgetController.SETTLE_WAIT_MS_MAX,
            "bounded wait",
        )
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.reconcile())
        assertEquals(0, reconfigurations, "nothing reconfigured against a flapping UDC")
        assertTrue(rig.state().functions.none(GadgetPaths::isOwnedName))
    }

    @Test
    fun `a fallback bind is waited out before reconciling`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        controller.apply(twoDisks)
        rig.hal.rebindDelayMs = null
        val dropsBefore = rig.hal.dropCount
        rig.fs.asActor("other") { rig.fs.write(GadgetPaths.UDC, "") }
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        // The HAL doesn't rebind, so the controller binds; the HAL drops that bind and rebinds a bit later.
        assertInstanceOf(ApplyResult.Applied::class.java, controller.reconcile())
        assertEquals(dropsBefore + 1, rig.hal.dropCount)
        assertEquals(udc, rig.fs.udc)
        val boundForUs = rig.clock.nowUs - checkNotNull(rig.fs.boundSinceUs)
        assertTrue(boundForUs >= DefaultGadgetController.STABLE_BOUND_MS * 1_000, "verified only once stable (bound ${boundForUs}us)")
    }

    @Test
    fun `a fallback bind the HAL drops for good is not reported as applied`() {
        val rig = GadgetRig()
        val controller = rig.controller()
        controller.apply(twoDisks)
        rig.hal.rebindDelayMs = null
        rig.hal.postBindDropDelayMs = 100L..100L
        rig.hal.postBindRebindDelayMs = 600_000L..600_000L
        rig.fs.asActor("other") { rig.fs.write(GadgetPaths.UDC, "") }
        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        val result = assertInstanceOf(ApplyResult.NeedsAttention::class.java, controller.reconcile())
        assertTrue(result.reason.contains("did not stay bound"), result.reason)
        assertTrue(rig.hal.dropCount > 0)
    }

    /**
     * USB debugging off and no other gadget functions: nothing is linked, so the UDC can't be bound. An
     * earlier session journaled the UDC name (with nothing bound it can't be read from configfs).
     */
    private fun GadgetRig.withNothingLinked(): GadgetRig {
        fs.asActor("setup") {
            fs.write(GadgetPaths.UDC, "")
            listOf("function0", "hid.keyboard", "hid.touchpad").forEach { fs.unlink(GadgetPaths.configLink(it)) }
        }
        journalStore.save(Journal(udc = udc))
        clock.sleepMs(2_000)
        return this
    }

    private fun GadgetRig.assertSwitchWindowsFit() {
        assertEquals(emptyList<String>(), fs.bindsInExecutorWindow, "someone bound inside our unbound window")
        val longestUs = fs.executorWindowsUs.maxOrNull() ?: 0
        assertTrue(longestUs < GadgetRig.SWITCH_WINDOW_US_MAX, "unbound for ${longestUs}us")
    }

    private fun <T> List<T>.distinctConsecutive(): List<T> = filterIndexed { index, value -> index == 0 || this[index - 1] != value }

    private companion object {
        const val FLAP_CYCLE_COUNT = 40
        const val FLAP_INTERVAL_MS = 700L
        const val FLAP_DOWN_MS = 150L
    }
}
