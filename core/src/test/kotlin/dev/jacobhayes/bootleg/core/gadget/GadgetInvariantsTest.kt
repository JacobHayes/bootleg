package dev.jacobhayes.bootleg.core.gadget

import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.img
import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.iso
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GadgetInvariantsTest {
    private val roots = GadgetInvariants.ALLOWED_ROOTS_DEFAULT
    private val lun = LunState(img(1), ro = false, cdrom = false, removable = true, nofua = false)
    private val prefix = LinkOrder.prefix(TetherMode.RNDIS).associateWith { it }
    private val good =
        GadgetState(
            udc = FakeConfigFs.PIXEL_UDC,
            functions = setOf("ffs.adb", GadgetNames.MASS_STORAGE_FUNCTION) + LinkOrder.prefix(TetherMode.RNDIS),
            configLinks = prefix + mapOf("function0" to "ffs.adb", GadgetNames.MASS_STORAGE_FUNCTION to GadgetNames.MASS_STORAGE_FUNCTION),
            luns = mapOf(0 to lun),
            rndis = RndisState.READY,
        )
    private val target = GadgetTarget(listOf(LunTarget(img(1), LunMode.DISK_RW)), prefix = true)

    private fun violations(state: GadgetState) = GadgetInvariants.violations(state, target, roots, foreignBefore = good)

    @Test
    fun `a healthy gadget has no violations`() {
        assertEquals(emptyList<String>(), violations(good))
    }

    @Test
    fun `each broken invariant is reported`() {
        val cases =
            mapOf(
                "UDC is not bound" to good.copy(udc = ""),
                "link mass_storage.bootleg missing" to good.copy(configLinks = prefix + mapOf("function0" to "ffs.adb")),
                "the prefix isn't first" to good.copy(configLinks = mapOf("function0" to "ffs.adb") + prefix),
                "links out of order" to
                    good.copy(configLinks = mapOf(GadgetNames.MASS_STORAGE_FUNCTION to GadgetNames.MASS_STORAGE_FUNCTION) + prefix),
                "the RNDIS function is HELD" to good.copy(rndis = RndisState.HELD),
                "cdrom without ro" to good.copy(luns = mapOf(0 to lun.copy(cdrom = true))),
                "not dense" to good.copy(luns = mapOf(1 to lun)),
                "exceeds" to good.copy(luns = (0..GadgetLimits.LUN_COUNT_MAX).associateWith { lun.copy(ro = true, file = img(it)) }),
                "writable file exported more than once" to good.copy(luns = mapOf(0 to lun, 1 to lun.copy(ro = true))),
                "outside the allowed roots" to good.copy(luns = mapOf(0 to lun.copy(file = "/data/local/tmp/x.img"))),
                "not canonical" to good.copy(luns = mapOf(0 to lun.copy(file = "/data/media/0/bootleg/../x.img"))),
                "ADB (ffs.adb) is no longer linked (was function0)" to good.copy(configLinks = good.configLinks - "function0"),
                "foreign functions removed: [ffs.adb]" to good.copy(functions = good.functions - "ffs.adb"),
                "unwanted function" to good.copy(functions = good.functions + "hid.bootleg_mouse"),
                "file '' != " to good.copy(luns = mapOf(0 to lun.copy(file = ""))),
                "lun 0: removable=0" to good.copy(luns = mapOf(0 to lun.copy(removable = false))),
                "lun 0: nofua=1" to good.copy(luns = mapOf(0 to lun.copy(nofua = true))),
            )
        for ((expected, state) in cases) {
            val found = violations(state)
            assertTrue(found.any { expected in it }, "expected '$expected' in $found")
        }
    }

    @Test
    fun `the HAL renumbering or adding foreign links is fine, and ADB may be absent to begin with`() {
        val renumbered = good.copy(configLinks = good.configLinks + mapOf("function0" to "ffs.mtp", "function1" to "ffs.adb"))
        assertEquals(emptyList<String>(), violations(renumbered.copy(functions = renumbered.functions + "ffs.mtp")))
        val withoutAdb = good.copy(configLinks = good.configLinks - "function0")
        assertEquals(emptyList<String>(), GadgetInvariants.violations(withoutAdb, target, roots, foreignBefore = withoutAdb))
    }

    @Test
    fun `path rules`() {
        assertNull(GadgetInvariants.pathProblem(iso(1), roots))
        listOf(
            "",
            "relative.iso",
            "/data/media/0/bootleg/",
            "/data/media/0/bootleg//x.iso",
            "/data/media/0/bootleg/./x.iso",
            "/sdcard/bootleg/x.iso",
        ).forEach { assertNotNull(GadgetInvariants.pathProblem(it, roots), it) }
    }

    @Test
    fun `a live LUN must carry the target's marker, unless the kernel has no inquiry_string`() {
        val marked = target.copy(lunMarker = GadgetRig.MARKER)
        val withMarker = good.copy(luns = mapOf(0 to lun.copy(inquiryString = GadgetRig.MARKER)))
        assertEquals(emptyList<String>(), GadgetInvariants.violations(withMarker, marked, roots))
        val recreated = good.copy(luns = mapOf(0 to lun.copy(inquiryString = "")))
        assertTrue(GadgetInvariants.violations(recreated, marked, roots).any { "is not our marker" in it })
        val unsupported = good.copy(luns = mapOf(0 to lun.copy(inquiryString = null)))
        assertEquals(emptyList<String>(), GadgetInvariants.violations(unsupported, marked, roots))
    }

    @Test
    fun `duplicate writable exports are found by any identity, so hard links count as one file`() {
        val slots = listOf(SlotSpec(0, img(1), LunMode.DISK_RW), SlotSpec(1, img(2), LunMode.DISK_RO))
        // Different paths pass the path check...
        GadgetInvariants.requireValid(Desired(slots))
        // ...but the same inode does not, whichever copy is writable.
        val sameInode = mapOf(img(1) to (66L to 1234L), img(2) to (66L to 1234L))
        val error =
            assertThrows<IllegalArgumentException> {
                GadgetInvariants.requireNoDuplicateWritable(
                    slots,
                ) { sameInode.getValue(it.backingPath) }
            }
        assertTrue("LUNs [0, 1]" in error.message.orEmpty(), error.message)
        // Read-only copies of one inode are fine.
        val readOnly = slots.map { it.copy(mode = LunMode.DISK_RO) }
        GadgetInvariants.requireNoDuplicateWritable(readOnly) { sameInode.getValue(it.backingPath) }
        // Same device, different inode: different files.
        val distinct = mapOf(img(1) to (66L to 1L), img(2) to (66L to 2L))
        GadgetInvariants.requireNoDuplicateWritable(slots) { distinct.getValue(it.backingPath) }
    }
}
