package dev.jacobhayes.bootleg.core.hid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KeyUsageTest {
    @Test
    fun `spot-check usage ids against the HID usage tables`() {
        val expected =
            mapOf(
                KeyUsage.ERROR_ROLL_OVER to 0x01,
                KeyUsage.A to 0x04,
                KeyUsage.Z to 0x1D,
                KeyUsage.DIGIT_1 to 0x1E,
                KeyUsage.DIGIT_0 to 0x27,
                KeyUsage.ENTER to 0x28,
                KeyUsage.ESCAPE to 0x29,
                KeyUsage.BACKSPACE to 0x2A,
                KeyUsage.SPACE to 0x2C,
                KeyUsage.SLASH to 0x38,
                KeyUsage.F1 to 0x3A,
                KeyUsage.F12 to 0x45,
                KeyUsage.PRINT_SCREEN to 0x46,
                KeyUsage.DELETE to 0x4C,
                KeyUsage.ARROW_UP to 0x52,
                KeyUsage.KEYPAD_ENTER to 0x58,
                KeyUsage.KEYPAD_0 to 0x62,
                KeyUsage.APPLICATION to 0x65,
                KeyUsage.F13 to 0x68,
                KeyUsage.F24 to 0x73,
                KeyUsage.LEFT_CONTROL to 0xE0,
                KeyUsage.RIGHT_GUI to 0xE7,
            )
        for ((usage, id) in expected) assertEquals(id, usage.id, usage.name)
    }

    @Test
    fun `contiguous ranges have no gaps`() {
        assertEquals((0x04..0x1D).toList(), ('A'..'Z').map { KeyUsage.valueOf(it.toString()).id })
        assertEquals((0x3A..0x45).toList(), (1..12).map { KeyUsage.valueOf("F$it").id })
        assertEquals((0x68..0x73).toList(), (13..24).map { KeyUsage.valueOf("F$it").id })
        assertEquals((0x59..0x62).toList(), (1..9).map { KeyUsage.valueOf("KEYPAD_$it").id } + KeyUsage.KEYPAD_0.id)
    }

    @Test
    fun `modifiers map to bits in usage order and back`() {
        for (modifier in Modifier.entries) {
            assertEquals(1 shl (modifier.usage.id - 0xE0), modifier.bit)
            assertEquals(modifier, modifier.usage.modifier)
        }
        assertEquals(KeyUsage.entries.filter { it.id >= 0xE0 }.toSet(), KeyUsage.entries.filter { it.isModifier }.toSet())
        assertEquals(0x09, Modifier.bitsOf(listOf(Modifier.LEFT_CONTROL, Modifier.LEFT_GUI)))
        assertEquals(setOf(Modifier.LEFT_CONTROL, Modifier.LEFT_GUI), Modifier.fromBits(0x09))
        assertThrows<IllegalArgumentException> { Modifier.fromBits(0x100) }
    }

    @Test
    fun `fromId is the inverse of id`() {
        for (usage in KeyUsage.entries) assertEquals(usage, KeyUsage.fromId(usage.id))
        assertNull(KeyUsage.fromId(0x00))
        assertNull(KeyUsage.fromId(0x300))
    }

    @Test
    fun `browser codes map to usages`() {
        val expected =
            mapOf(
                "KeyA" to KeyUsage.A,
                "Digit1" to KeyUsage.DIGIT_1,
                "ShiftLeft" to KeyUsage.LEFT_SHIFT,
                "MetaLeft" to KeyUsage.LEFT_GUI,
                "AltRight" to KeyUsage.RIGHT_ALT,
                "F24" to KeyUsage.F24,
                "ArrowUp" to KeyUsage.ARROW_UP,
                "NumpadEnter" to KeyUsage.KEYPAD_ENTER,
                "Numpad7" to KeyUsage.KEYPAD_7,
                "Backquote" to KeyUsage.GRAVE,
                "Delete" to KeyUsage.DELETE,
                "Backspace" to KeyUsage.BACKSPACE,
                "IntlBackslash" to KeyUsage.NON_US_BACKSLASH,
                "OSLeft" to KeyUsage.LEFT_GUI,
                "ContextMenu" to KeyUsage.APPLICATION,
            )
        for ((code, usage) in expected) assertEquals(usage, BrowserKeyCodes.usageFor(code), code)
    }

    @Test
    fun `unknown and oversized browser codes map to nothing`() {
        assertNull(BrowserKeyCodes.usageFor(""))
        assertNull(BrowserKeyCodes.usageFor("keya"))
        assertNull(BrowserKeyCodes.usageFor("F25"))
        assertNull(BrowserKeyCodes.usageFor("Key".repeat(100)))
    }

    @Test
    fun `every usage except ErrorRollOver is reachable from some browser code`() {
        val reachable = BrowserKeyCodes.codes.mapNotNull { BrowserKeyCodes.usageFor(it) }.toSet()
        // NON_US_HASH shares the "Backslash" code on ISO keyboards.
        val unreachable = KeyUsage.entries.toSet() - reachable
        assertEquals(setOf(KeyUsage.ERROR_ROLL_OVER, KeyUsage.NON_US_HASH), unreachable)
        assertTrue(BrowserKeyCodes.codes.all { it.length <= BrowserKeyCodes.CODE_LENGTH_MAX })
    }
}
