package dev.jacobhayes.bootleg.root

import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.LunState
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AdoptableDesiredTest {
    private val ms = GadgetNames.MASS_STORAGE_FUNCTION
    private val kbd = GadgetNames.HID_KEYBOARD_FUNCTION
    private val mouse = GadgetNames.HID_WHEEL_MOUSE_FUNCTION
    private val empty = LunState(file = "", ro = true, cdrom = false, removable = true, nofua = false)

    private fun state(
        links: List<String>,
        luns: Map<Int, LunState>,
    ) = GadgetState("udc", links.toSet(), links.associateWith { it }, luns)

    @Test
    fun `HID with the empty placeholder LUN adopts as HID without disks`() {
        assertEquals(
            Desired(hid = HidSpec(keyboard = true, mouse = true)),
            adoptableDesired(state(listOf(ms, kbd, mouse), mapOf(0 to empty))),
        )
    }

    @Test
    fun `an empty LUN without HID can't be adopted`() {
        assertNull(adoptableDesired(state(listOf(ms), mapOf(0 to empty))))
    }

    @Test
    fun `exported LUNs adopt as slots`() {
        val file = "/data/media/0/bootleg/a.img"
        assertEquals(
            Desired(listOf(SlotSpec(0, file, LunMode.DISK_RO)), HidSpec(keyboard = true)),
            adoptableDesired(state(listOf(ms, kbd), mapOf(0 to empty.copy(file = file)))),
        )
    }
}
