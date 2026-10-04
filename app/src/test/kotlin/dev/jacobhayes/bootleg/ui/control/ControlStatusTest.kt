package dev.jacobhayes.bootleg.ui.control

import dev.jacobhayes.bootleg.RootStatus
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.hid.HidLinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ControlStatusTest {
    private fun gadget(vararg functions: String) =
        GadgetState(
            udc = "11110000.dwc3",
            functions = functions.toSet(),
            configLinks = functions.associateWith { it },
            luns = emptyMap(),
        )

    private val both = gadget(GadgetNames.HID_KEYBOARD_FUNCTION, GadgetNames.HID_MOUSE_FUNCTION)

    private fun status(
        link: HidLinkState,
        gadget: GadgetState = both,
    ) = statusOf(enabled = true, busy = false, root = RootStatus.Granted, gadget = gadget, link = link)

    @Test
    fun `every endpoint read is connected`() {
        assertEquals(ControlStatus("Connected · the computer is reading input", StatusTone.GOOD), status(HidLinkState.Open()))
    }

    @Test
    fun `a mouse the firmware ignores says the keyboard still works`() {
        assertEquals(
            ControlStatus("Keyboard connected · the computer isn't reading the mouse (BIOS or boot menu?)", StatusTone.PROBLEM),
            status(HidLinkState.Open(setOf(HidKind.MOUSE))),
        )
    }

    @Test
    fun `nothing read, or the only linked endpoint not read, says the computer isn't reading`() {
        val notReading = ControlStatus("Computer not reading input (asleep, off or unplugged?)", StatusTone.PROBLEM)
        assertEquals(notReading, status(HidLinkState.Open(setOf(HidKind.KEYBOARD, HidKind.MOUSE))))
        assertEquals(notReading, status(HidLinkState.Open(setOf(HidKind.MOUSE)), gadget(GadgetNames.HID_MOUSE_FUNCTION)))
    }
}
