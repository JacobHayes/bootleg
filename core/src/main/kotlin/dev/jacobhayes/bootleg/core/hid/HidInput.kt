package dev.jacobhayes.bootleg.core.hid

/** What an on-screen modifier chip shows. */
data class ModifierChipState(
    val sticky: StickyState,
    val held: Boolean,
) {
    val isActive: Boolean get() = held || sticky != StickyState.OFF
}

/**
 * On-screen keyboard state on top of [StickyModifiers]. Held and locked modifiers are sent to the
 * host as soon as they change (so they combine with mouse clicks); a latched modifier travels only
 * with the next key, so tapping Win and then un-latching it never opens the Start menu.
 */
class KeyboardInput {
    private val sticky = StickyModifiers()

    /** Modifier bits the host currently believes are down between key presses. */
    private var hostModifierBits = 0

    fun chipState(modifier: Modifier): ModifierChipState = ModifierChipState(sticky.stickyState(modifier), sticky.isHeld(modifier))

    fun tapModifier(modifier: Modifier): List<KeyboardReport> {
        sticky.tap(modifier)
        return syncIdleModifiers()
    }

    fun holdModifier(modifier: Modifier): List<KeyboardReport> {
        sticky.hold(modifier)
        return syncIdleModifiers()
    }

    fun releaseModifier(modifier: Modifier): List<KeyboardReport> {
        sticky.releaseHold(modifier)
        return syncIdleModifiers()
    }

    /** Taps a key with the active modifiers; latched ones are consumed. */
    fun tapKey(key: KeyUsage): List<KeyboardReport> = typeStroke(KeyStroke(key, shift = false))

    /** Types one character's stroke; its Shift (if any) is added to the active modifiers for the press only. */
    fun typeStroke(stroke: KeyStroke): List<KeyboardReport> {
        val tap = sticky.onKey(stroke.usage)
        val shiftBits = if (stroke.shift) Modifier.LEFT_SHIFT.bit else 0
        val press = tap.press.copy(modifierBits = tap.press.modifierBits or shiftBits)
        hostModifierBits = tap.release.modifierBits
        check(hostModifierBits == idleModifierBits()) { "release must return to the idle modifiers" }
        return listOf(press, tap.release)
    }

    /** A fixed shortcut such as Ctrl+Alt+Del: [modifiers] are pressed with [key] and released afterwards. */
    fun chord(
        modifiers: Set<Modifier>,
        key: KeyUsage,
    ): List<KeyboardReport> {
        require(modifiers.isNotEmpty()) { "a chord needs at least one modifier" }
        val idle = idleModifierBits()
        val chordBits = idle or Modifier.bitsOf(modifiers)
        hostModifierBits = idle
        return listOf(
            KeyboardReport(chordBits, emptyList()),
            KeyboardReport(chordBits, listOf(key)),
            KeyboardReport(chordBits, emptyList()),
            KeyboardReport(idle, emptyList()),
        )
    }

    /** After a release-all reached (or was owed to) the host: forget every modifier. */
    fun reset() {
        sticky.reset()
        hostModifierBits = 0
    }

    private fun idleModifierBits(): Int {
        var bits = 0
        for (modifier in Modifier.entries) {
            if (sticky.isHeld(modifier) || sticky.stickyState(modifier) == StickyState.LOCKED) bits = bits or modifier.bit
        }
        return bits
    }

    private fun syncIdleModifiers(): List<KeyboardReport> {
        val idle = idleModifierBits()
        if (idle == hostModifierBits) return emptyList()
        hostModifierBits = idle
        return listOf(KeyboardReport(idle, emptyList()))
    }
}

/** Mouse button state plus touchpad actions to mouse reports. */
class MouseInput {
    var buttonBits: Int = 0
        private set

    fun apply(action: TouchpadAction): List<MouseReport> =
        when (action) {
            is TouchpadAction.Move -> MouseReport.split(buttonBits, action.deltaX, action.deltaY)
            is TouchpadAction.Click -> click(action.button)
            is TouchpadAction.Press -> press(action.button)
            is TouchpadAction.Release -> release(action.button)
            is TouchpadAction.Scroll -> scroll(action.wheel, action.pan)
        }

    /** A scroll report keeps the held buttons; scrolling has no state of its own to release. */
    fun scroll(
        wheel: Int,
        pan: Int,
    ): List<MouseReport> = if (wheel == 0 && pan == 0) emptyList() else listOf(MouseReport.scroll(buttonBits, wheel, pan))

    fun click(button: MouseButton): List<MouseReport> = listOf(MouseReport(buttonBits or button.bit), MouseReport(buttonBits))

    fun press(button: MouseButton): List<MouseReport> {
        buttonBits = buttonBits or button.bit
        return listOf(MouseReport(buttonBits))
    }

    fun release(button: MouseButton): List<MouseReport> {
        if (buttonBits and button.bit == 0) return emptyList()
        buttonBits = buttonBits and button.bit.inv()
        return listOf(MouseReport(buttonBits))
    }

    fun reset() {
        buttonBits = 0
    }
}

/**
 * The only input the debug provider may send (L4 tests): the Mac is the host and must never get
 * a real keystroke. F24, a lone Shift tap and a cancelling mouse move do nothing there; the
 * cancelling scroll (one detent up, one down) is sent only once the check has parked the Mac's
 * pointer over an inert spot.
 */
enum class HidTestSequence(
    val wireName: String,
) {
    F24("f24"),
    SHIFT("shift"),
    MOUSE("mouse"),
    SCROLL("scroll"),
    ;

    val reports: List<Pair<HidKind, ByteArray>>
        get() =
            when (this) {
                F24 ->
                    listOf(
                        HidKind.KEYBOARD to KeyboardReport(0, listOf(KeyUsage.F24)).encode(),
                        HidKind.KEYBOARD to KeyboardReport.RELEASE_ALL.encode(),
                    )
                SHIFT ->
                    listOf(
                        HidKind.KEYBOARD to KeyboardReport(Modifier.LEFT_SHIFT.bit, emptyList()).encode(),
                        HidKind.KEYBOARD to KeyboardReport.RELEASE_ALL.encode(),
                    )
                MOUSE ->
                    listOf(
                        HidKind.MOUSE to MouseReport(0, TEST_MOVE_COUNTS, TEST_MOVE_COUNTS).encode(),
                        HidKind.MOUSE to MouseReport(0, -TEST_MOVE_COUNTS, -TEST_MOVE_COUNTS).encode(),
                    )
                SCROLL ->
                    listOf(
                        HidKind.MOUSE to MouseReport.scroll(0, wheel = 1, pan = 0).encode(),
                        HidKind.MOUSE to MouseReport.scroll(0, wheel = -1, pan = 0).encode(),
                    )
            }

    companion object {
        const val TEST_MOVE_COUNTS = 10

        fun fromWireName(name: String): HidTestSequence? = entries.firstOrNull { it.wireName == name }
    }
}
