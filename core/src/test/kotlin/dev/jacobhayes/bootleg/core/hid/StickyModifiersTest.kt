package dev.jacobhayes.bootleg.core.hid

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StickyModifiersTest {
    private val ctrl = Modifier.LEFT_CONTROL
    private val alt = Modifier.LEFT_ALT
    private val shift = Modifier.LEFT_SHIFT

    @Test
    fun `tap cycles off, latched, locked, off for every modifier`() {
        for (modifier in Modifier.entries) {
            val sticky = StickyModifiers()
            assertEquals(StickyState.OFF, sticky.stickyState(modifier))
            assertEquals(StickyState.LATCHED, sticky.tap(modifier))
            assertEquals(modifier.bit, sticky.currentModifierByte())
            assertEquals(StickyState.LOCKED, sticky.tap(modifier))
            assertEquals(modifier.bit, sticky.currentModifierByte())
            assertEquals(StickyState.OFF, sticky.tap(modifier))
            assertEquals(0, sticky.currentModifierByte())
        }
    }

    @Test
    fun `latched applies to exactly the next key`() {
        val sticky = StickyModifiers()
        sticky.tap(shift)
        val first = sticky.onKey(KeyUsage.A)
        assertEquals(KeyboardReport(shift.bit, listOf(KeyUsage.A)), first.press)
        assertEquals(KeyboardReport.RELEASE_ALL, first.release)
        assertEquals(StickyState.OFF, sticky.stickyState(shift))
        assertEquals(KeyboardReport(0, listOf(KeyUsage.B)), sticky.onKey(KeyUsage.B).press)
    }

    @Test
    fun `locked applies to every key until tapped again`() {
        val sticky = StickyModifiers()
        sticky.tap(ctrl)
        sticky.tap(ctrl)
        repeat(3) {
            val tap = sticky.onKey(KeyUsage.C)
            assertEquals(KeyboardReport(ctrl.bit, listOf(KeyUsage.C)), tap.press)
            assertEquals(KeyboardReport(ctrl.bit, emptyList()), tap.release)
        }
        sticky.tap(ctrl)
        assertEquals(KeyboardReport(0, listOf(KeyUsage.C)), sticky.onKey(KeyUsage.C).press)
    }

    @Test
    fun `ctrl alt delete via two latches is one chord, then everything clears`() {
        val sticky = StickyModifiers()
        sticky.tap(ctrl)
        sticky.tap(alt)
        val tap = sticky.onKey(KeyUsage.DELETE)
        assertEquals(KeyboardReport(ctrl.bit or alt.bit, listOf(KeyUsage.DELETE)), tap.press)
        assertEquals(KeyboardReport.RELEASE_ALL, tap.release)
        assertEquals(0, sticky.currentModifierByte())
    }

    @Test
    fun `locked plus latched - only the latch clears`() {
        val sticky = StickyModifiers()
        sticky.tap(Modifier.LEFT_GUI)
        sticky.tap(Modifier.LEFT_GUI)
        sticky.tap(shift)
        val tap = sticky.onKey(KeyUsage.Z)
        assertEquals(Modifier.LEFT_GUI.bit or shift.bit, tap.press.modifierBits)
        assertEquals(Modifier.LEFT_GUI.bit, tap.release.modifierBits)
        assertEquals(StickyState.LOCKED, sticky.stickyState(Modifier.LEFT_GUI))
        assertEquals(StickyState.OFF, sticky.stickyState(shift))
    }

    @Test
    fun `held modifiers apply while held and survive key taps`() {
        val sticky = StickyModifiers()
        sticky.hold(alt)
        assertTrue(sticky.isHeld(alt))
        assertEquals(alt.bit, sticky.onKey(KeyUsage.TAB).press.modifierBits)
        assertEquals(alt.bit, sticky.onKey(KeyUsage.TAB).release.modifierBits)
        sticky.releaseHold(alt)
        assertFalse(sticky.isHeld(alt))
        assertEquals(0, sticky.currentModifierByte())
    }

    @Test
    fun `held and latched on the same modifier - the latch clears, the hold remains`() {
        val sticky = StickyModifiers()
        sticky.tap(ctrl)
        sticky.hold(ctrl)
        assertEquals(ctrl.bit, sticky.onKey(KeyUsage.A).release.modifierBits)
        assertEquals(StickyState.OFF, sticky.stickyState(ctrl))
        sticky.releaseHold(ctrl)
        assertEquals(0, sticky.currentModifierByte())
    }

    @Test
    fun `left and right modifiers are independent`() {
        val sticky = StickyModifiers()
        sticky.tap(Modifier.LEFT_SHIFT)
        sticky.hold(Modifier.RIGHT_SHIFT)
        assertEquals(0x22, sticky.currentModifierByte())
    }

    @Test
    fun `reset clears sticky and held state`() {
        val sticky = StickyModifiers()
        Modifier.entries.forEach { sticky.tap(it) }
        sticky.tap(ctrl)
        sticky.hold(alt)
        sticky.reset()
        assertEquals(0, sticky.currentModifierByte())
        assertTrue(Modifier.entries.all { sticky.stickyState(it) == StickyState.OFF && !sticky.isHeld(it) })
    }

    @Test
    fun `modifier and rollover usages are rejected by onKey`() {
        val sticky = StickyModifiers()
        assertThrows<IllegalArgumentException> { sticky.onKey(KeyUsage.LEFT_SHIFT) }
        assertThrows<IllegalArgumentException> { sticky.onKey(KeyUsage.ERROR_ROLL_OVER) }
    }

    private sealed interface Action {
        data class Tap(
            val modifier: Modifier,
        ) : Action

        data class Hold(
            val modifier: Modifier,
        ) : Action

        data class ReleaseHold(
            val modifier: Modifier,
        ) : Action

        data object Key : Action
    }

    /** Model-based check: the byte equals held ∪ non-off, and key taps clear exactly the latches. */
    @Test
    fun `random action sequences match a reference model`() =
        runBlocking<Unit> {
            val modifier = Arb.element(Modifier.entries)
            val action =
                Arb.choice(
                    modifier.map { Action.Tap(it) },
                    modifier.map { Action.Hold(it) },
                    modifier.map { Action.ReleaseHold(it) },
                    Arb.constant(Action.Key),
                )
            checkAll(Arb.list(action, 0..40)) { actions ->
                val sticky = StickyModifiers()
                val modelStates = Modifier.entries.associateWith { StickyState.OFF }.toMutableMap()
                val modelHeld = mutableSetOf<Modifier>()

                fun modelByte() = Modifier.bitsOf(modelHeld + modelStates.filterValues { it != StickyState.OFF }.keys)
                for (step in actions) {
                    when (step) {
                        is Action.Tap -> {
                            modelStates[step.modifier] = StickyState.entries[(modelStates.getValue(step.modifier).ordinal + 1) % 3]
                            sticky.tap(step.modifier)
                        }
                        is Action.Hold -> {
                            modelHeld += step.modifier
                            sticky.hold(step.modifier)
                        }
                        is Action.ReleaseHold -> {
                            modelHeld -= step.modifier
                            sticky.releaseHold(step.modifier)
                        }
                        Action.Key -> {
                            val pressByte = modelByte()
                            modelStates.replaceAll { _, state -> if (state == StickyState.LATCHED) StickyState.OFF else state }
                            val tap = sticky.onKey(KeyUsage.F20)
                            assertEquals(KeyboardReport(pressByte, listOf(KeyUsage.F20)), tap.press)
                            assertEquals(KeyboardReport(modelByte(), emptyList()), tap.release)
                        }
                    }
                    assertEquals(modelByte(), sticky.currentModifierByte())
                }
            }
        }
}
