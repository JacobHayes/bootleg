package dev.jacobhayes.bootleg.hid

import dev.jacobhayes.bootleg.core.hid.BootKey
import dev.jacobhayes.bootleg.core.hid.BootKeyMasher
import dev.jacobhayes.bootleg.core.hid.BootKeyPlan
import dev.jacobhayes.bootleg.core.hid.HidLimits
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.hid.Modifier
import dev.jacobhayes.bootleg.core.hid.TimedReport

/** Turns a vendor's boot key into a cancelable timed plan ("Mash F12 for 20 s", "Hold Option for 20 s"). */
object BootKeyActions {
    val durationsMs: List<Long> = listOf(10_000L, 20_000L, 30_000L)
    const val DEFAULT_DURATION_MS = 20_000L

    /** Fast enough for short POST windows, slow enough that firmware doesn't see a held key. */
    const val MASH_INTERVAL_MS_MIN = 100L

    /** While a key or modifier is held between reports, its report is re-sent this often (survives a host USB reset). */
    const val HOLD_RESEND_INTERVAL_MS = 200L

    fun plan(
        key: BootKey,
        durationMs: Long,
    ): BootKeyPlan? {
        require(durationMs in 1..HidLimits.BOOT_KEY_HOLD_MS_MAX) { "duration must be 1..${HidLimits.BOOT_KEY_HOLD_MS_MAX} ms: $durationMs" }
        return when (key) {
            is BootKey.Tap -> {
                val intervalMs = maxOf(MASH_INTERVAL_MS_MIN, durationMs / HidLimits.BOOT_KEY_PRESS_COUNT_MAX)
                val pressCount = (durationMs / intervalMs).toInt().coerceIn(1, HidLimits.BOOT_KEY_PRESS_COUNT_MAX)
                BootKeyMasher.repeat(key.usage, pressCount, intervalMs)
            }
            is BootKey.Hold -> BootKeyMasher.hold(key.modifier, durationMs)
            is BootKey.Unsupported -> null
        }
    }

    fun label(key: BootKey): String =
        when (key) {
            is BootKey.Tap -> "Mash ${keyName(key.usage)}"
            is BootKey.Hold -> "Hold ${modifierName(key.modifier)}"
            is BootKey.Unsupported -> key.description.replaceFirstChar { it.uppercase() }
        }

    /**
     * Expands a plan so a held report is repeated every [HOLD_RESEND_INTERVAL_MS] until the next
     * scheduled report: a host that resets its USB stack while the key is held (e.g. a Mac
     * restarting with Option down) still sees it held afterwards.
     */
    fun withHoldResends(plan: BootKeyPlan): List<TimedReport> =
        buildList {
            for ((current, next) in plan.reports.zipWithNext()) {
                add(current)
                if (current.report.isReleaseAll) continue
                var atMs = current.atMs + HOLD_RESEND_INTERVAL_MS
                while (atMs < next.atMs) {
                    add(TimedReport(atMs, current.report))
                    atMs += HOLD_RESEND_INTERVAL_MS
                }
            }
            add(plan.reports.last())
        }

    fun keyName(usage: KeyUsage): String =
        when (usage) {
            KeyUsage.ESCAPE -> "Esc"
            KeyUsage.DELETE -> "Del"
            KeyUsage.ENTER -> "Enter"
            else -> usage.name.lowercase().replaceFirstChar { it.uppercase() }
        }

    private fun modifierName(modifier: Modifier): String =
        when (modifier) {
            Modifier.LEFT_ALT, Modifier.RIGHT_ALT -> "Option"
            Modifier.LEFT_SHIFT, Modifier.RIGHT_SHIFT -> "Shift"
            Modifier.LEFT_CONTROL, Modifier.RIGHT_CONTROL -> "Ctrl"
            Modifier.LEFT_GUI, Modifier.RIGHT_GUI -> "Cmd"
        }
}
