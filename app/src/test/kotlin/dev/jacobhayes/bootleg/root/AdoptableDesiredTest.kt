package dev.jacobhayes.bootleg.root

import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.LinkOrder
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.LunState
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AdoptableDesiredTest {
    private val ms = GadgetNames.MASS_STORAGE_FUNCTION
    private val empty = LunState(file = "", ro = true, cdrom = false, removable = true, nofua = false)

    private fun state(
        links: List<String>,
        luns: Map<Int, LunState>,
    ) = GadgetState("udc", links.toSet(), links.associateWith { it }, luns)

    @Test
    fun `a linked prefix adopts as the idle prefix, HID off`() {
        assertEquals(Desired(prefix = true), adoptableDesired(state(LinkOrder.PREFIX, emptyMap())))
    }

    @Test
    fun `nothing of Bootleg's, or an empty LUN, can't be adopted`() {
        assertNull(adoptableDesired(state(emptyList(), emptyMap())))
        assertNull(adoptableDesired(state(LinkOrder.PREFIX + ms, mapOf(0 to empty))))
    }

    @Test
    fun `exported LUNs adopt as slots behind the prefix`() {
        val file = "/data/media/0/bootleg/a.img"
        assertEquals(
            Desired(listOf(SlotSpec(0, file, LunMode.DISK_RO)), prefix = true),
            adoptableDesired(state(LinkOrder.PREFIX + ms, mapOf(0 to empty.copy(file = file)))),
        )
    }
}
