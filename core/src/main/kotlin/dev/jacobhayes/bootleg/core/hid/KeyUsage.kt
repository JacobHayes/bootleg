package dev.jacobhayes.bootleg.core.hid

/** Keyboard/Keypad page (0x07) usage IDs, from the HID Usage Tables. */
enum class KeyUsage(
    val id: Int,
) {
    ERROR_ROLL_OVER(0x01),
    A(0x04),
    B(0x05),
    C(0x06),
    D(0x07),
    E(0x08),
    F(0x09),
    G(0x0A),
    H(0x0B),
    I(0x0C),
    J(0x0D),
    K(0x0E),
    L(0x0F),
    M(0x10),
    N(0x11),
    O(0x12),
    P(0x13),
    Q(0x14),
    R(0x15),
    S(0x16),
    T(0x17),
    U(0x18),
    V(0x19),
    W(0x1A),
    X(0x1B),
    Y(0x1C),
    Z(0x1D),
    DIGIT_1(0x1E),
    DIGIT_2(0x1F),
    DIGIT_3(0x20),
    DIGIT_4(0x21),
    DIGIT_5(0x22),
    DIGIT_6(0x23),
    DIGIT_7(0x24),
    DIGIT_8(0x25),
    DIGIT_9(0x26),
    DIGIT_0(0x27),
    ENTER(0x28),
    ESCAPE(0x29),

    /** "Delete" on Mac keyboards. */
    BACKSPACE(0x2A),
    TAB(0x2B),
    SPACE(0x2C),
    MINUS(0x2D),
    EQUAL(0x2E),
    BRACKET_LEFT(0x2F),
    BRACKET_RIGHT(0x30),
    BACKSLASH(0x31),
    NON_US_HASH(0x32),
    SEMICOLON(0x33),
    QUOTE(0x34),
    GRAVE(0x35),
    COMMA(0x36),
    PERIOD(0x37),
    SLASH(0x38),
    CAPS_LOCK(0x39),
    F1(0x3A),
    F2(0x3B),
    F3(0x3C),
    F4(0x3D),
    F5(0x3E),
    F6(0x3F),
    F7(0x40),
    F8(0x41),
    F9(0x42),
    F10(0x43),
    F11(0x44),
    F12(0x45),
    PRINT_SCREEN(0x46),
    SCROLL_LOCK(0x47),
    PAUSE(0x48),
    INSERT(0x49),
    HOME(0x4A),
    PAGE_UP(0x4B),

    /** Forward delete ("Del"). */
    DELETE(0x4C),
    END(0x4D),
    PAGE_DOWN(0x4E),
    ARROW_RIGHT(0x4F),
    ARROW_LEFT(0x50),
    ARROW_DOWN(0x51),
    ARROW_UP(0x52),
    NUM_LOCK(0x53),
    KEYPAD_DIVIDE(0x54),
    KEYPAD_MULTIPLY(0x55),
    KEYPAD_SUBTRACT(0x56),
    KEYPAD_ADD(0x57),
    KEYPAD_ENTER(0x58),
    KEYPAD_1(0x59),
    KEYPAD_2(0x5A),
    KEYPAD_3(0x5B),
    KEYPAD_4(0x5C),
    KEYPAD_5(0x5D),
    KEYPAD_6(0x5E),
    KEYPAD_7(0x5F),
    KEYPAD_8(0x60),
    KEYPAD_9(0x61),
    KEYPAD_0(0x62),
    KEYPAD_DECIMAL(0x63),
    NON_US_BACKSLASH(0x64),
    APPLICATION(0x65),
    POWER(0x66),
    KEYPAD_EQUAL(0x67),
    F13(0x68),
    F14(0x69),
    F15(0x6A),
    F16(0x6B),
    F17(0x6C),
    F18(0x6D),
    F19(0x6E),
    F20(0x6F),
    F21(0x70),
    F22(0x71),
    F23(0x72),
    F24(0x73),
    HELP(0x75),
    MUTE(0x7F),
    VOLUME_UP(0x80),
    VOLUME_DOWN(0x81),
    KEYPAD_COMMA(0x85),
    INTERNATIONAL_1(0x87),
    INTERNATIONAL_2(0x88),
    INTERNATIONAL_3(0x89),
    INTERNATIONAL_4(0x8A),
    INTERNATIONAL_5(0x8B),
    LANG_1(0x90),
    LANG_2(0x91),
    LEFT_CONTROL(0xE0),
    LEFT_SHIFT(0xE1),

    /** Option on Mac. */
    LEFT_ALT(0xE2),

    /** Cmd on Mac, Win on PC. */
    LEFT_GUI(0xE3),
    RIGHT_CONTROL(0xE4),
    RIGHT_SHIFT(0xE5),
    RIGHT_ALT(0xE6),
    RIGHT_GUI(0xE7),
    ;

    /** Modifier usages travel as bits in report byte 0, never in the key array. */
    val modifier: Modifier? get() = Modifier.entries.firstOrNull { it.usage == this }

    val isModifier: Boolean get() = modifier != null

    companion object {
        private val byId = entries.associateBy { it.id }

        init {
            check(byId.size == entries.size) { "duplicate usage ids" }
        }

        fun fromId(id: Int): KeyUsage? = byId[id]
    }
}

/** Modifier bits of keyboard report byte 0, in usage order 0xE0..0xE7. */
enum class Modifier(
    val usage: KeyUsage,
) {
    LEFT_CONTROL(KeyUsage.LEFT_CONTROL),
    LEFT_SHIFT(KeyUsage.LEFT_SHIFT),
    LEFT_ALT(KeyUsage.LEFT_ALT),
    LEFT_GUI(KeyUsage.LEFT_GUI),
    RIGHT_CONTROL(KeyUsage.RIGHT_CONTROL),
    RIGHT_SHIFT(KeyUsage.RIGHT_SHIFT),
    RIGHT_ALT(KeyUsage.RIGHT_ALT),
    RIGHT_GUI(KeyUsage.RIGHT_GUI),
    ;

    val bit: Int get() = 1 shl ordinal

    companion object {
        const val ALL_BITS = 0xFF

        fun bitsOf(modifiers: Iterable<Modifier>): Int = modifiers.fold(0) { bits, modifier -> bits or modifier.bit }

        fun fromBits(bits: Int): Set<Modifier> {
            require(bits in 0..ALL_BITS) { "modifier bits out of range: $bits" }
            return entries.filterTo(mutableSetOf()) { bits and it.bit != 0 }
        }
    }
}

/** Maps browser `KeyboardEvent.code` values (UI Events KeyboardEvent code spec) to usages, for the web UI. */
object BrowserKeyCodes {
    private val usageByCode: Map<String, KeyUsage> =
        buildMap {
            for (letter in 'A'..'Z') put("Key$letter", KeyUsage.valueOf(letter.toString()))
            for (digit in 0..9) put("Digit$digit", KeyUsage.valueOf("DIGIT_$digit"))
            for (number in 1..24) put("F$number", KeyUsage.valueOf("F$number"))
            for (digit in 0..9) put("Numpad$digit", KeyUsage.valueOf("KEYPAD_$digit"))
            put("Enter", KeyUsage.ENTER)
            put("Escape", KeyUsage.ESCAPE)
            put("Backspace", KeyUsage.BACKSPACE)
            put("Tab", KeyUsage.TAB)
            put("Space", KeyUsage.SPACE)
            put("Minus", KeyUsage.MINUS)
            put("Equal", KeyUsage.EQUAL)
            put("BracketLeft", KeyUsage.BRACKET_LEFT)
            put("BracketRight", KeyUsage.BRACKET_RIGHT)
            put("Backslash", KeyUsage.BACKSLASH)
            put("Semicolon", KeyUsage.SEMICOLON)
            put("Quote", KeyUsage.QUOTE)
            put("Backquote", KeyUsage.GRAVE)
            put("Comma", KeyUsage.COMMA)
            put("Period", KeyUsage.PERIOD)
            put("Slash", KeyUsage.SLASH)
            put("IntlBackslash", KeyUsage.NON_US_BACKSLASH)
            put("IntlRo", KeyUsage.INTERNATIONAL_1)
            put("KanaMode", KeyUsage.INTERNATIONAL_2)
            put("IntlYen", KeyUsage.INTERNATIONAL_3)
            put("Convert", KeyUsage.INTERNATIONAL_4)
            put("NonConvert", KeyUsage.INTERNATIONAL_5)
            put("Lang1", KeyUsage.LANG_1)
            put("Lang2", KeyUsage.LANG_2)
            put("CapsLock", KeyUsage.CAPS_LOCK)
            put("PrintScreen", KeyUsage.PRINT_SCREEN)
            put("ScrollLock", KeyUsage.SCROLL_LOCK)
            put("Pause", KeyUsage.PAUSE)
            put("Insert", KeyUsage.INSERT)
            put("Home", KeyUsage.HOME)
            put("PageUp", KeyUsage.PAGE_UP)
            put("Delete", KeyUsage.DELETE)
            put("End", KeyUsage.END)
            put("PageDown", KeyUsage.PAGE_DOWN)
            put("ArrowRight", KeyUsage.ARROW_RIGHT)
            put("ArrowLeft", KeyUsage.ARROW_LEFT)
            put("ArrowDown", KeyUsage.ARROW_DOWN)
            put("ArrowUp", KeyUsage.ARROW_UP)
            put("NumLock", KeyUsage.NUM_LOCK)
            put("NumpadDivide", KeyUsage.KEYPAD_DIVIDE)
            put("NumpadMultiply", KeyUsage.KEYPAD_MULTIPLY)
            put("NumpadSubtract", KeyUsage.KEYPAD_SUBTRACT)
            put("NumpadAdd", KeyUsage.KEYPAD_ADD)
            put("NumpadEnter", KeyUsage.KEYPAD_ENTER)
            put("NumpadDecimal", KeyUsage.KEYPAD_DECIMAL)
            put("NumpadEqual", KeyUsage.KEYPAD_EQUAL)
            put("NumpadComma", KeyUsage.KEYPAD_COMMA)
            put("ContextMenu", KeyUsage.APPLICATION)
            put("Power", KeyUsage.POWER)
            put("Help", KeyUsage.HELP)
            put("AudioVolumeMute", KeyUsage.MUTE)
            put("AudioVolumeUp", KeyUsage.VOLUME_UP)
            put("AudioVolumeDown", KeyUsage.VOLUME_DOWN)
            put("ControlLeft", KeyUsage.LEFT_CONTROL)
            put("ShiftLeft", KeyUsage.LEFT_SHIFT)
            put("AltLeft", KeyUsage.LEFT_ALT)
            put("MetaLeft", KeyUsage.LEFT_GUI)
            put("ControlRight", KeyUsage.RIGHT_CONTROL)
            put("ShiftRight", KeyUsage.RIGHT_SHIFT)
            put("AltRight", KeyUsage.RIGHT_ALT)
            put("MetaRight", KeyUsage.RIGHT_GUI)
            // Legacy names still sent by older Firefox.
            put("OSLeft", KeyUsage.LEFT_GUI)
            put("OSRight", KeyUsage.RIGHT_GUI)
        }

    /** Codes are short identifiers; anything longer is rejected before the map lookup. */
    const val CODE_LENGTH_MAX = 32

    fun usageFor(code: String): KeyUsage? = if (code.length > CODE_LENGTH_MAX) null else usageByCode[code]

    val codes: Set<String> get() = usageByCode.keys
}
