package dev.jacobhayes.bootleg.core.hid

enum class StickyState { OFF, LATCHED, LOCKED }

/** The press and the release for one non-modifier key tap. */
data class KeyTap(
    val press: KeyboardReport,
    val release: KeyboardReport,
)

/**
 * On-screen modifier keys. Tapping cycles OFF → LATCHED (one-shot: applies to the next
 * non-modifier key, then clears) → LOCKED (until tapped again) → OFF. Independently, a modifier can
 * be physically held (touch-and-hold) and is active while held, whatever its sticky state.
 */
class StickyModifiers {
    private val stickyStates = Array(Modifier.entries.size) { StickyState.OFF }
    private var heldBits = 0

    fun stickyState(modifier: Modifier): StickyState = stickyStates[modifier.ordinal]

    fun isHeld(modifier: Modifier): Boolean = heldBits and modifier.bit != 0

    /** Returns the new sticky state. */
    fun tap(modifier: Modifier): StickyState {
        val next =
            when (stickyStates[modifier.ordinal]) {
                StickyState.OFF -> StickyState.LATCHED
                StickyState.LATCHED -> StickyState.LOCKED
                StickyState.LOCKED -> StickyState.OFF
            }
        stickyStates[modifier.ordinal] = next
        return next
    }

    fun hold(modifier: Modifier) {
        heldBits = heldBits or modifier.bit
    }

    fun releaseHold(modifier: Modifier) {
        heldBits = heldBits and modifier.bit.inv()
    }

    /** Modifier byte the host should currently see. */
    fun currentModifierByte(): Int {
        var bits = heldBits
        for (modifier in Modifier.entries) {
            if (stickyStates[modifier.ordinal] != StickyState.OFF) bits = bits or modifier.bit
        }
        check(bits in 0..Modifier.ALL_BITS)
        return bits
    }

    /** Taps [key] with the active modifiers, then clears latched (one-shot) modifiers. */
    fun onKey(key: KeyUsage): KeyTap {
        require(!key.isModifier) { "modifier keys go through tap/hold, not onKey: $key" }
        require(key != KeyUsage.ERROR_ROLL_OVER) { "ErrorRollOver is not a pressable key" }
        val press = KeyboardReport(currentModifierByte(), listOf(key))
        for (modifier in Modifier.entries) {
            if (stickyStates[modifier.ordinal] == StickyState.LATCHED) stickyStates[modifier.ordinal] = StickyState.OFF
        }
        val release = KeyboardReport(currentModifierByte(), emptyList())
        check(release.modifierBits and press.modifierBits.inv() == 0) { "a key tap must never add modifiers on release" }
        return KeyTap(press, release)
    }

    /** Disconnect, controller change, or queue overflow: forget everything. */
    fun reset() {
        stickyStates.fill(StickyState.OFF)
        heldBits = 0
    }
}
