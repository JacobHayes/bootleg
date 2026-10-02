package dev.jacobhayes.bootleg.core.hid

/** Hard limits for HID input (TigerStyle: every resource is bounded). */
object HidLimits {
    /** Reports waiting to be written to /dev/hidgN; overflow releases all keys and drops the queue. */
    const val REPORT_QUEUE_LENGTH_MAX = 256

    /** Per-report write deadline; a host that stops polling must not block the writer. */
    const val REPORT_WRITE_DEADLINE_MS = 50L

    /** Paste-as-typing input, measured in UTF-8 bytes. */
    const val PASTE_SIZE_BYTES_MAX = 64 * 1024

    const val KEYBOARD_REPORT_SIZE_BYTES = 8
    const val KEYBOARD_REPORT_KEY_COUNT_MAX = 6
    const val MOUSE_REPORT_SIZE_BYTES = 3

    /** Boot mouse X/Y are int8; -128 is excluded so motion is symmetric (HID B.2 logical min is -127). */
    const val MOUSE_DELTA_MAX = 127

    /** A single gesture may be split into at most this many reports; larger deltas are clamped. */
    const val MOUSE_SPLIT_REPORT_COUNT_MAX = 64

    const val BOOT_KEY_PRESS_COUNT_MAX = 200
    const val BOOT_KEY_INTERVAL_MS_MIN = 50L
    const val BOOT_KEY_INTERVAL_MS_MAX = 10_000L
    const val BOOT_KEY_HOLD_MS_MAX = 30_000L

    /** How long each mashed key stays down; well under [BOOT_KEY_INTERVAL_MS_MIN]. */
    const val BOOT_KEY_PRESS_DURATION_MS = 20L

    /** A boot-key plan never holds more than a press and a release per allowed press. */
    const val BOOT_KEY_PLAN_REPORT_COUNT_MAX = 2 * BOOT_KEY_PRESS_COUNT_MAX

    /** The longest mash ([BOOT_KEY_PRESS_COUNT_MAX] taps at [BOOT_KEY_INTERVAL_MS_MAX]) also covers the longest hold. */
    const val BOOT_KEY_PLAN_DURATION_MS_MAX = BOOT_KEY_PRESS_COUNT_MAX * BOOT_KEY_INTERVAL_MS_MAX
}
