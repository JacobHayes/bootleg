package dev.jacobhayes.bootleg.core.hid

import dev.jacobhayes.bootleg.core.gadget.MouseLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * One 8-byte boot keyboard input report: modifier bits, reserved, six key slots.
 *
 * Either holds up to six distinct non-modifier keys, or is a rollover report (every slot
 * ErrorRollOver), which tells the host "too many keys" without dropping any silently.
 */
data class KeyboardReport(
    val modifierBits: Int = 0,
    val keys: List<KeyUsage> = emptyList(),
) {
    init {
        require(modifierBits in 0..Modifier.ALL_BITS) { "modifier bits out of range: $modifierBits" }
        require(keys.size <= HidLimits.KEYBOARD_REPORT_KEY_COUNT_MAX) { "at most 6 keys per report, got ${keys.size}" }
        require(keys.none { it.isModifier }) { "modifier usages belong in modifierBits, not keys: $keys" }
        if (KeyUsage.ERROR_ROLL_OVER in keys) {
            require(isRollover) { "ErrorRollOver must fill all six slots: $keys" }
        } else {
            require(keys.toSet().size == keys.size) { "duplicate keys: $keys" }
        }
    }

    val isRollover: Boolean
        get() = keys.size == HidLimits.KEYBOARD_REPORT_KEY_COUNT_MAX && keys.all { it == KeyUsage.ERROR_ROLL_OVER }

    val isReleaseAll: Boolean get() = modifierBits == 0 && keys.isEmpty()

    fun encode(): ByteArray {
        val report = ByteArray(HidLimits.KEYBOARD_REPORT_SIZE_BYTES)
        report[0] = modifierBits.toByte()
        keys.forEachIndexed { slot, key -> report[2 + slot] = key.id.toByte() }
        return report
    }

    companion object {
        val RELEASE_ALL = KeyboardReport()

        /** Builds a report for the keys currently down; more than six becomes a rollover report. */
        fun pressing(
            modifierBits: Int,
            keys: Collection<KeyUsage>,
        ): KeyboardReport {
            val distinctKeys = keys.toSet()
            require(KeyUsage.ERROR_ROLL_OVER !in distinctKeys) { "ErrorRollOver is not a pressable key" }
            return if (distinctKeys.size > HidLimits.KEYBOARD_REPORT_KEY_COUNT_MAX) {
                rollover(modifierBits)
            } else {
                KeyboardReport(modifierBits, distinctKeys.toList())
            }
        }

        fun rollover(modifierBits: Int): KeyboardReport =
            KeyboardReport(modifierBits, List(HidLimits.KEYBOARD_REPORT_KEY_COUNT_MAX) { KeyUsage.ERROR_ROLL_OVER })

        /** Inverse of [encode]; rejects malformed reports (non-zero reserved byte, gaps, unknown usages). */
        fun decode(report: ByteArray): KeyboardReport {
            require(report.size == HidLimits.KEYBOARD_REPORT_SIZE_BYTES) { "keyboard report must be 8 bytes, got ${report.size}" }
            require(report[1] == 0.toByte()) { "reserved byte must be zero" }
            val ids = (2 until HidLimits.KEYBOARD_REPORT_SIZE_BYTES).map { report[it].toInt() and 0xFF }
            val keyCount = ids.indexOfFirst { it == 0 }.let { if (it < 0) ids.size else it }
            require(ids.drop(keyCount).all { it == 0 }) { "key slots must be packed: $ids" }
            val keys = ids.take(keyCount).map { requireNotNull(KeyUsage.fromId(it)) { "unknown usage id: $it" } }
            return KeyboardReport(report[0].toInt() and 0xFF, keys)
        }
    }
}

/** Keyboard LED output report (host → device), one byte. */
data class KeyboardLeds(
    val numLock: Boolean,
    val capsLock: Boolean,
    val scrollLock: Boolean,
    val compose: Boolean,
    val kana: Boolean,
) {
    companion object {
        fun decode(report: ByteArray): KeyboardLeds {
            require(report.size == 1) { "LED report must be 1 byte, got ${report.size}" }
            val bits = report[0].toInt()
            return KeyboardLeds(
                numLock = bits and 0x01 != 0,
                capsLock = bits and 0x02 != 0,
                scrollLock = bits and 0x04 != 0,
                compose = bits and 0x08 != 0,
                kana = bits and 0x10 != 0,
            )
        }
    }
}

enum class MouseButton(
    val bit: Int,
) {
    LEFT(0x01),
    RIGHT(0x02),
    MIDDLE(0x04),
    ;

    companion object {
        const val ALL_BITS = 0x07

        fun bitsOf(buttons: Iterable<MouseButton>): Int = buttons.fold(0) { bits, button -> bits or button.bit }
    }
}

/**
 * One mouse input report: button bits, relative X, relative Y (screen-down is +Y), then the
 * wheel (+ = scroll up) and AC Pan (+ = scroll right) in detents. [encode] gives the 5-byte wheel
 * layout; its first 3 bytes are the boot report ([encodeFor] with [MouseLayout.BOOT]).
 */
data class MouseReport(
    val buttonBits: Int = 0,
    val deltaX: Int = 0,
    val deltaY: Int = 0,
    val wheel: Int = 0,
    val pan: Int = 0,
) {
    init {
        require(buttonBits in 0..MouseButton.ALL_BITS) { "button bits out of range: $buttonBits" }
        require(deltaX in -HidLimits.MOUSE_DELTA_MAX..HidLimits.MOUSE_DELTA_MAX) { "deltaX out of range: $deltaX" }
        require(deltaY in -HidLimits.MOUSE_DELTA_MAX..HidLimits.MOUSE_DELTA_MAX) { "deltaY out of range: $deltaY" }
        require(wheel in -HidLimits.MOUSE_SCROLL_MAX..HidLimits.MOUSE_SCROLL_MAX) { "wheel out of range: $wheel" }
        require(pan in -HidLimits.MOUSE_SCROLL_MAX..HidLimits.MOUSE_SCROLL_MAX) { "pan out of range: $pan" }
    }

    val isScroll: Boolean get() = wheel != 0 || pan != 0

    fun encode(): ByteArray = byteArrayOf(buttonBits.toByte(), deltaX.toByte(), deltaY.toByte(), wheel.toByte(), pan.toByte())

    fun encodeFor(layout: MouseLayout): ByteArray = forLayout(encode(), layout)

    companion object {
        fun clamped(
            buttonBits: Int,
            deltaX: Int,
            deltaY: Int,
        ): MouseReport = MouseReport(buttonBits, clampDelta(deltaX), clampDelta(deltaY))

        /** A scroll-only report holding [buttonBits]; each axis is clamped to int8 detents. */
        fun scroll(
            buttonBits: Int,
            wheel: Int,
            pan: Int,
        ): MouseReport = MouseReport(buttonBits, wheel = clampScroll(wheel), pan = clampScroll(pan))

        /**
         * The bytes a function with [layout] takes from a wheel-layout [report]: its first
         * `reportLengthBytes`. The layouts share bytes 0..2 (HID 1.11 B.2), so this is exactly the
         * boot report for [MouseLayout.BOOT].
         */
        fun forLayout(
            report: ByteArray,
            layout: MouseLayout,
        ): ByteArray {
            require(report.size == HidLimits.MOUSE_REPORT_SIZE_BYTES) {
                "mouse report must be ${HidLimits.MOUSE_REPORT_SIZE_BYTES} bytes, got ${report.size}"
            }
            check(layout.reportLengthBytes in HidLimits.MOUSE_BOOT_REPORT_SIZE_BYTES..HidLimits.MOUSE_REPORT_SIZE_BYTES)
            return report.copyOf(layout.reportLengthBytes)
        }

        /** Inverse of [encode]; a 3-byte boot report decodes with no scroll. */
        fun decode(report: ByteArray): MouseReport {
            require(report.size == HidLimits.MOUSE_REPORT_SIZE_BYTES || report.size == HidLimits.MOUSE_BOOT_REPORT_SIZE_BYTES) {
                "mouse report must be ${HidLimits.MOUSE_REPORT_SIZE_BYTES} or ${HidLimits.MOUSE_BOOT_REPORT_SIZE_BYTES} bytes, got ${report.size}"
            }
            val wheel = if (report.size > 3) report[3].toInt() else 0
            val pan = if (report.size > 4) report[4].toInt() else 0
            return MouseReport(report[0].toInt() and 0xFF, report[1].toInt(), report[2].toInt(), wheel, pan)
        }

        /**
         * Splits a large motion into reports that each fit int8, preserving direction and the exact
         * total. Motion beyond [HidLimits.MOUSE_SPLIT_REPORT_COUNT_MAX] reports is clamped per axis.
         */
        fun split(
            buttonBits: Int,
            deltaX: Int,
            deltaY: Int,
        ): List<MouseReport> {
            val totalMax = HidLimits.MOUSE_DELTA_MAX * HidLimits.MOUSE_SPLIT_REPORT_COUNT_MAX
            val totalX = deltaX.coerceIn(-totalMax, totalMax)
            val totalY = deltaY.coerceIn(-totalMax, totalMax)
            val reportCount = max(1, max(stepsFor(totalX), stepsFor(totalY)))
            check(reportCount <= HidLimits.MOUSE_SPLIT_REPORT_COUNT_MAX)
            val reports =
                (0 until reportCount).map { step ->
                    MouseReport(
                        buttonBits,
                        portion(totalX, step, reportCount),
                        portion(totalY, step, reportCount),
                    )
                }
            check(reports.sumOf { it.deltaX } == totalX && reports.sumOf { it.deltaY } == totalY)
            return reports
        }

        private fun clampDelta(delta: Int): Int = delta.coerceIn(-HidLimits.MOUSE_DELTA_MAX, HidLimits.MOUSE_DELTA_MAX)

        private fun clampScroll(detents: Int): Int = detents.coerceIn(-HidLimits.MOUSE_SCROLL_MAX, HidLimits.MOUSE_SCROLL_MAX)

        private fun stepsFor(delta: Int): Int = (abs(delta) + HidLimits.MOUSE_DELTA_MAX - 1) / HidLimits.MOUSE_DELTA_MAX

        /** Even split by cumulative rounding, so every step is within one unit of total/count. */
        private fun portion(
            total: Int,
            step: Int,
            count: Int,
        ): Int = total * (step + 1) / count - total * step / count
    }
}
