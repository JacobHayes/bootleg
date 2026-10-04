package dev.jacobhayes.bootleg.core.hid

import dev.jacobhayes.bootleg.core.gadget.HidDescriptorProvider

/**
 * Boot-protocol report descriptors (HID 1.11 appendix B.1 / B.2), no report IDs.
 *
 * The wheel mouse is B.2 with Wheel and AC Pan bytes appended after X and Y. B.2 allows a boot
 * device to send more than the 3 boot bytes; a boot-protocol host reads bytes 0..2 only, so the same
 * report serves BIOS and report-protocol hosts and nothing depends on SET_PROTOCOL.
 *
 * Deviation from B.1: the keyboard key array covers usages 0x00..0xFF (logical max 255) rather
 * than 0x00..0x65, so report-protocol hosts accept F13–F24, Intl/Lang and media usages. BIOS hosts
 * use the fixed boot format and ignore the descriptor, so this costs nothing there.
 */
object HidDescriptors : HidDescriptorProvider {
    private const val KEY_ARRAY_USAGE_MAX = 0xFF

    override fun keyboard(): ByteArray =
        descriptor {
            usagePage(USAGE_PAGE_GENERIC_DESKTOP)
            usage(USAGE_KEYBOARD)
            collection(COLLECTION_APPLICATION) {
                // Byte 0: eight modifier bits (LeftControl..RightGUI).
                usagePage(USAGE_PAGE_KEYBOARD)
                usageMinimum(0xE0)
                usageMaximum(0xE7)
                logicalMinimum(0)
                logicalMaximum(1)
                reportSize(1)
                reportCount(8)
                input(DATA_VARIABLE_ABSOLUTE)
                // Byte 1: reserved.
                reportCount(1)
                reportSize(8)
                input(CONSTANT)
                // Output: five LEDs (Num, Caps, Scroll, Compose, Kana) plus three padding bits.
                reportCount(5)
                reportSize(1)
                usagePage(USAGE_PAGE_LEDS)
                usageMinimum(1)
                usageMaximum(5)
                output(DATA_VARIABLE_ABSOLUTE)
                reportCount(1)
                reportSize(3)
                output(CONSTANT)
                // Bytes 2..7: six-key array.
                reportCount(HidLimits.KEYBOARD_REPORT_KEY_COUNT_MAX)
                reportSize(8)
                logicalMinimum(0)
                logicalMaximum(KEY_ARRAY_USAGE_MAX)
                usagePage(USAGE_PAGE_KEYBOARD)
                usageMinimum(0)
                usageMaximum(KEY_ARRAY_USAGE_MAX)
                input(DATA_ARRAY_ABSOLUTE)
            }
        }

    override fun mouse(): ByteArray =
        descriptor {
            usagePage(USAGE_PAGE_GENERIC_DESKTOP)
            usage(USAGE_MOUSE)
            collection(COLLECTION_APPLICATION) {
                usage(USAGE_POINTER)
                collection(COLLECTION_PHYSICAL) {
                    // Byte 0: three buttons plus five padding bits.
                    usagePage(USAGE_PAGE_BUTTON)
                    usageMinimum(1)
                    usageMaximum(3)
                    logicalMinimum(0)
                    logicalMaximum(1)
                    reportCount(3)
                    reportSize(1)
                    input(DATA_VARIABLE_ABSOLUTE)
                    reportCount(1)
                    reportSize(5)
                    input(CONSTANT)
                    // Bytes 1..2: relative X, Y.
                    usagePage(USAGE_PAGE_GENERIC_DESKTOP)
                    usage(USAGE_X)
                    usage(USAGE_Y)
                    logicalMinimum(-HidLimits.MOUSE_DELTA_MAX)
                    logicalMaximum(HidLimits.MOUSE_DELTA_MAX)
                    reportSize(8)
                    reportCount(2)
                    input(DATA_VARIABLE_RELATIVE)
                    // Byte 3: vertical wheel, detents (+ = away from the user, scroll up).
                    usage(USAGE_WHEEL)
                    logicalMinimum(-HidLimits.MOUSE_DELTA_MAX)
                    logicalMaximum(HidLimits.MOUSE_DELTA_MAX)
                    reportSize(8)
                    reportCount(1)
                    input(DATA_VARIABLE_RELATIVE)
                    // Byte 4: horizontal scroll (+ = right).
                    usagePage(USAGE_PAGE_CONSUMER)
                    usage(USAGE_AC_PAN)
                    logicalMinimum(-HidLimits.MOUSE_DELTA_MAX)
                    logicalMaximum(HidLimits.MOUSE_DELTA_MAX)
                    reportSize(8)
                    reportCount(1)
                    input(DATA_VARIABLE_RELATIVE)
                }
            }
        }

    private const val USAGE_PAGE_GENERIC_DESKTOP = 0x01
    private const val USAGE_PAGE_KEYBOARD = 0x07
    private const val USAGE_PAGE_LEDS = 0x08
    private const val USAGE_PAGE_BUTTON = 0x09
    private const val USAGE_PAGE_CONSUMER = 0x0C
    private const val USAGE_WHEEL = 0x38
    private const val USAGE_AC_PAN = 0x0238
    private const val USAGE_POINTER = 0x01
    private const val USAGE_MOUSE = 0x02
    private const val USAGE_KEYBOARD = 0x06
    private const val USAGE_X = 0x30
    private const val USAGE_Y = 0x31
    private const val COLLECTION_PHYSICAL = 0x00
    private const val COLLECTION_APPLICATION = 0x01
    private const val DATA_ARRAY_ABSOLUTE = 0x00
    private const val CONSTANT = 0x01
    private const val DATA_VARIABLE_ABSOLUTE = 0x02
    private const val DATA_VARIABLE_RELATIVE = 0x06

    private fun descriptor(build: DescriptorWriter.() -> Unit): ByteArray = DescriptorWriter().apply(build).toByteArray()

    /** Emits short items (HID 1.11 §6.2.2.2), choosing the smallest data size that holds the value. */
    private class DescriptorWriter {
        private val bytes = mutableListOf<Byte>()
        private var collectionDepth = 0

        fun usagePage(page: Int) = unsignedItem(0x04, page)

        fun usage(usage: Int) = unsignedItem(0x08, usage)

        fun usageMinimum(usage: Int) = unsignedItem(0x18, usage)

        fun usageMaximum(usage: Int) = unsignedItem(0x28, usage)

        fun logicalMinimum(value: Int) = signedItem(0x14, value)

        fun logicalMaximum(value: Int) = signedItem(0x24, value)

        fun reportSize(bits: Int) = unsignedItem(0x74, bits)

        fun reportCount(count: Int) = unsignedItem(0x94, count)

        fun input(flags: Int) = unsignedItem(0x80, flags)

        fun output(flags: Int) = unsignedItem(0x90, flags)

        fun collection(
            type: Int,
            body: DescriptorWriter.() -> Unit,
        ) {
            unsignedItem(0xA0, type)
            collectionDepth++
            body()
            collectionDepth--
            bytes += 0xC0.toByte() // End Collection
        }

        fun toByteArray(): ByteArray {
            check(collectionDepth == 0) { "unbalanced collections" }
            return bytes.toByteArray()
        }

        private fun unsignedItem(
            prefix: Int,
            value: Int,
        ) {
            require(value in 0..0xFFFF) { "unsigned item value out of range: $value" }
            if (value <= 0xFF) emit(prefix or 1, value, 1) else emit(prefix or 2, value, 2)
        }

        private fun signedItem(
            prefix: Int,
            value: Int,
        ) {
            require(value in Short.MIN_VALUE..Short.MAX_VALUE) { "signed item value out of range: $value" }
            if (value in Byte.MIN_VALUE..Byte.MAX_VALUE) emit(prefix or 1, value, 1) else emit(prefix or 2, value, 2)
        }

        private fun emit(
            prefixWithSize: Int,
            value: Int,
            sizeBytes: Int,
        ) {
            bytes += prefixWithSize.toByte()
            for (i in 0 until sizeBytes) bytes += (value ushr (8 * i)).toByte()
        }
    }
}
