package dev.jacobhayes.bootleg.ui.drives

import dev.jacobhayes.bootleg.FakeBootlegBackend
import dev.jacobhayes.bootleg.RootStatus
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetLimits
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class DrivesControllerTest {
    private val iso = "/data/media/0/bootleg/a.iso"
    private val img = "/data/media/0/bootleg/b.img"

    @Test
    fun `desired uses dense luns, auto modes and keeps hid`() {
        val journal = Journal(desired = Desired(hid = HidSpec(keyboard = true)))
        val desired = DrivesController.desiredFor(listOf(DraftSlot(iso), DraftSlot(img), DraftSlot(img, LunMode.CDROM)), journal)
        assertEquals(
            listOf(SlotSpec(0, iso, LunMode.CDROM), SlotSpec(1, img, LunMode.DISK_RO), SlotSpec(2, img, LunMode.CDROM)),
            desired.slots,
        )
        assertTrue(desired.hid.keyboard)
    }

    @Test
    fun `a writable image can't appear in two slots`() {
        assertThrows<IllegalArgumentException> {
            DrivesController.desiredFor(listOf(DraftSlot(img, LunMode.DISK_RW), DraftSlot(img)), Journal())
        }
    }

    @Test
    fun `more than eight slots is rejected`() {
        val slots = List(GadgetLimits.LUN_COUNT_MAX + 1) { DraftSlot("/data/media/0/bootleg/$it.iso") }
        assertThrows<IllegalArgumentException> { DrivesController.desiredFor(slots, Journal()) }
    }

    @Test
    fun `journal slots round trip through the draft with auto detection`() {
        val journal = Journal(desired = Desired(listOf(SlotSpec(0, iso, LunMode.CDROM), SlotSpec(1, img, LunMode.DISK_RW))))
        val slots = DrivesController.slotsOf(journal)
        assertEquals(listOf(DraftSlot(iso, null), DraftSlot(img, LunMode.DISK_RW)), slots)
        assertEquals(journal.desired, DrivesController.desiredFor(slots, journal))
    }

    @Test
    fun `edit then apply exports through the backend`() =
        runTest(UnconfinedTestDispatcher()) {
            val backend = FakeBootlegBackend()
            val controller = DrivesController(backend, backgroundScope)
            assertTrue(controller.addSlot(iso))
            assertTrue(controller.state.value.dirty)
            controller.apply()
            assertFalse(controller.state.value.dirty)
            assertEquals(SlotIntent.EXPORTED, backend.journal.value.slotIntents[0])
            assertEquals(listOf(SlotSpec(0, iso, LunMode.CDROM)), backend.journal.value.desired.slots)

            controller.eject(0, force = false)
            assertEquals(SlotIntent.USER_EJECTED, backend.journal.value.slotIntents[0])
        }

    @Test
    fun `host refusing an eject offers force eject, and reinsert re-exports`() =
        runTest(UnconfinedTestDispatcher()) {
            val backend = FakeBootlegBackend()
            val controller = DrivesController(backend, backgroundScope)
            controller.addSlot(iso)
            controller.apply()
            backend.ejectPreventedByHost = true
            controller.eject(0, force = false)
            assertEquals(0, controller.state.value.forceEjectOffer)
            assertEquals(SlotIntent.EXPORTED, backend.journal.value.slotIntents[0])

            controller.eject(0, force = true)
            assertNull(controller.state.value.forceEjectOffer)
            assertEquals(SlotIntent.USER_EJECTED, backend.journal.value.slotIntents[0])

            controller.reinsert(0)
            assertEquals(SlotIntent.EXPORTED, backend.journal.value.slotIntents[0])
        }

    @Test
    fun `removing an ejected slot and adding the same image back exports it again`() =
        runTest(UnconfinedTestDispatcher()) {
            val backend = FakeBootlegBackend()
            val controller = DrivesController(backend, backgroundScope)
            controller.addSlot(iso)
            controller.apply()
            controller.eject(0, force = false)
            assertEquals(SlotIntent.USER_EJECTED, backend.journal.value.slotIntents[0])

            controller.removeSlot(0)
            controller.addSlot(iso)
            assertTrue(controller.state.value.dirty, "a re-added image is a change to apply")
            controller.apply()

            assertEquals(listOf(SlotSpec(0, iso, LunMode.CDROM)), backend.journal.value.desired.slots)
            assertEquals(SlotIntent.EXPORTED, backend.journal.value.slotIntents[0])
            assertFalse(controller.state.value.dirty)
        }

    @Test
    fun `picking the same image again re-inserts a host-ejected slot, other ejected slots stay`() =
        runTest(UnconfinedTestDispatcher()) {
            val backend = FakeBootlegBackend()
            val controller = DrivesController(backend, backgroundScope)
            controller.addSlot(iso)
            controller.addSlot(img)
            controller.apply()
            controller.eject(0, force = false)
            controller.eject(1, force = false)

            controller.replaceImage(0, iso)
            controller.apply()

            assertEquals(SlotIntent.EXPORTED, backend.journal.value.slotIntents[0])
            assertEquals(SlotIntent.USER_EJECTED, backend.journal.value.slotIntents[1], "an untouched ejected slot stays ejected")
        }

    @Test
    fun `slot limit is enforced`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = DrivesController(FakeBootlegBackend(), backgroundScope)
            repeat(GadgetLimits.LUN_COUNT_MAX) { assertTrue(controller.addSlot("/data/media/0/bootleg/$it.iso")) }
            assertFalse(controller.addSlot(iso))
            assertFalse(controller.expose(iso))
            assertEquals("All 8 slots are in use", controller.state.value.message)
        }

    @Test
    fun `apply without root reports failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val backend = FakeBootlegBackend(rootStatus = RootStatus.Unavailable("su denied"))
            val controller = DrivesController(backend, backgroundScope)
            controller.addSlot(iso)
            controller.apply()
            assertEquals("Failed: Root unavailable: su denied", controller.state.value.message)
            assertNull(backend.journal.value.slotIntents[0])
        }

    @Test
    fun `status reflects pending, exported and host ejected`() {
        val journal =
            Journal(
                desired = Desired(listOf(SlotSpec(0, iso, LunMode.CDROM), SlotSpec(1, img, LunMode.DISK_RO))),
                slotIntents = mapOf(0 to SlotIntent.EXPORTED, 1 to SlotIntent.HOST_EJECTED),
            )
        val applied = DrivesController.slotsOf(journal)
        assertEquals(SlotStatusKind.EXPORTED, slotStatus(0, applied[0], applied, journal).kind)
        assertEquals("Host ejected", slotStatus(1, applied[1], applied, journal).label)
        assertEquals(SlotStatusKind.PENDING, slotStatus(1, DraftSlot(iso), applied, journal).kind)
        assertEquals(SlotStatusKind.PENDING, slotStatus(2, DraftSlot(iso), applied, journal).kind)
    }
}
