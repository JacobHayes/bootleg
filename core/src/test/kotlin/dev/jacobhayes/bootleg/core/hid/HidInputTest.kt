package dev.jacobhayes.bootleg.core.hid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class HidInputTest {
    private val ctrl = Modifier.LEFT_CONTROL
    private val shift = Modifier.LEFT_SHIFT
    private val gui = Modifier.LEFT_GUI

    @Test
    fun `latched chip sends nothing until the next key, then clears`() {
        val keyboard = KeyboardInput()
        assertEquals(emptyList<KeyboardReport>(), keyboard.tapModifier(gui))
        assertEquals(ModifierChipState(StickyState.LATCHED, held = false), keyboard.chipState(gui))
        assertEquals(
            listOf(KeyboardReport(gui.bit, listOf(KeyUsage.SPACE)), KeyboardReport.RELEASE_ALL),
            keyboard.tapKey(KeyUsage.SPACE),
        )
        assertEquals(StickyState.OFF, keyboard.chipState(gui).sticky)
    }

    @Test
    fun `double tap locks and the host sees it until unlocked`() {
        val keyboard = KeyboardInput()
        keyboard.tapModifier(ctrl)
        assertEquals(listOf(KeyboardReport(ctrl.bit)), keyboard.tapModifier(ctrl))
        assertEquals(StickyState.LOCKED, keyboard.chipState(ctrl).sticky)
        repeat(2) {
            assertEquals(
                listOf(KeyboardReport(ctrl.bit, listOf(KeyUsage.C)), KeyboardReport(ctrl.bit)),
                keyboard.tapKey(KeyUsage.C),
            )
        }
        assertEquals(listOf(KeyboardReport.RELEASE_ALL), keyboard.tapModifier(ctrl))
        assertFalse(keyboard.chipState(ctrl).isActive)
    }

    @Test
    fun `long press holds while pressed and combines with other keys`() {
        val keyboard = KeyboardInput()
        assertEquals(listOf(KeyboardReport(shift.bit)), keyboard.holdModifier(shift))
        assertEquals(ModifierChipState(StickyState.OFF, held = true), keyboard.chipState(shift))
        assertEquals(
            listOf(KeyboardReport(shift.bit, listOf(KeyUsage.ARROW_DOWN)), KeyboardReport(shift.bit)),
            keyboard.tapKey(KeyUsage.ARROW_DOWN),
        )
        assertEquals(listOf(KeyboardReport.RELEASE_ALL), keyboard.releaseModifier(shift))
        assertEquals(emptyList<KeyboardReport>(), keyboard.releaseModifier(shift))
    }

    @Test
    fun `typed strokes add shift only to the press`() {
        val keyboard = KeyboardInput()
        val stroke = checkNotNull(UsLayout.strokeFor('A'))
        assertEquals(listOf(KeyboardReport(shift.bit, listOf(KeyUsage.A)), KeyboardReport.RELEASE_ALL), keyboard.typeStroke(stroke))
        keyboard.tapModifier(ctrl)
        assertEquals(
            listOf(KeyboardReport(ctrl.bit, listOf(KeyUsage.C)), KeyboardReport.RELEASE_ALL),
            keyboard.typeStroke(checkNotNull(UsLayout.strokeFor('c'))),
        )
    }

    @Test
    fun `chords press modifiers first and return to the idle modifiers`() {
        val keyboard = KeyboardInput()
        val ctrlAlt = Modifier.bitsOf(listOf(ctrl, Modifier.LEFT_ALT))
        assertEquals(
            listOf(
                KeyboardReport(ctrlAlt),
                KeyboardReport(ctrlAlt, listOf(KeyUsage.DELETE)),
                KeyboardReport(ctrlAlt),
                KeyboardReport.RELEASE_ALL,
            ),
            keyboard.chord(setOf(ctrl, Modifier.LEFT_ALT), KeyUsage.DELETE),
        )
        keyboard.holdModifier(shift)
        assertEquals(KeyboardReport(shift.bit), keyboard.chord(setOf(Modifier.LEFT_ALT), KeyUsage.TAB).last())
    }

    @Test
    fun `reset forgets latched, locked and held modifiers`() {
        val keyboard = KeyboardInput()
        keyboard.tapModifier(ctrl)
        keyboard.tapModifier(ctrl)
        keyboard.holdModifier(shift)
        keyboard.reset()
        assertTrue(Modifier.entries.none { keyboard.chipState(it).isActive })
        // The host was released by the session; nothing more is owed.
        assertEquals(emptyList<KeyboardReport>(), keyboard.releaseModifier(shift))
    }

    @Test
    fun `test sequences are only harmless input`() {
        val harmlessKeys = setOf(KeyUsage.F20, KeyUsage.F21, KeyUsage.F22, KeyUsage.F23, KeyUsage.F24)
        for (sequence in HidTestSequence.entries) {
            var dx = 0
            var dy = 0
            var wheel = 0
            for ((kind, bytes) in sequence.reports) {
                when (kind) {
                    HidKind.KEYBOARD -> {
                        val report = KeyboardReport.decode(bytes)
                        assertTrue(report.keys.all { it in harmlessKeys }, "$sequence sends ${report.keys}")
                        assertTrue(report.modifierBits == 0 || report.modifierBits == Modifier.LEFT_SHIFT.bit, "$sequence sends modifiers")
                        assertTrue(report.modifierBits == 0 || report.keys.isEmpty(), "$sequence combines Shift with a key")
                    }
                    HidKind.MOUSE -> {
                        val report = MouseReport.decode(bytes)
                        assertEquals(0, report.buttonBits, "$sequence clicks")
                        assertEquals(0, report.pan, "$sequence pans")
                        assertTrue(abs(report.wheel) <= 1, "$sequence scrolls more than one detent")
                        dx += report.deltaX
                        dy += report.deltaY
                        wheel += report.wheel
                    }
                }
            }
            assertEquals(0 to 0, dx to dy, "$sequence mouse moves must cancel out")
            assertEquals(0, wheel, "$sequence scrolling must cancel out")
            val last = sequence.reports.last()
            assertTrue(last.second.all { it == 0.toByte() } || last.first == HidKind.MOUSE, "$sequence must end released")
        }
        assertEquals(HidTestSequence.F24, HidTestSequence.fromWireName("f24"))
        assertEquals(null, HidTestSequence.fromWireName("hello"))
    }
}
