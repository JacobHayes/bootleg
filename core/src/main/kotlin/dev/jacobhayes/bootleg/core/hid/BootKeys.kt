package dev.jacobhayes.bootleg.core.hid

/** How to trigger a firmware menu at power-on. */
sealed interface BootKey {
    /** Tap repeatedly while the machine posts. */
    data class Tap(
        val usage: KeyUsage,
    ) : BootKey {
        init {
            require(!usage.isModifier && usage != KeyUsage.ERROR_ROLL_OVER) { "boot keys are ordinary keys: $usage" }
        }
    }

    /** Hold from power-on until the menu appears. */
    data class Hold(
        val modifier: Modifier,
    ) : BootKey

    /** Needs something a USB keyboard can't send (e.g. a hardware button). */
    data class Unsupported(
        val description: String,
    ) : BootKey
}

enum class BootVendor { GENERIC, DELL, LENOVO, HP, ASUS, ACER, MSI, GIGABYTE, INTEL_NUC, APPLE_MAC, MICROSOFT_SURFACE }

data class BootKeyPreset(
    val vendor: BootVendor,
    val displayName: String,
    /** One-time boot device menu, most likely first. */
    val bootMenuKeys: List<BootKey>,
    /** Firmware setup (BIOS/UEFI), most likely first. */
    val setupKeys: List<BootKey>,
    val note: String? = null,
) {
    init {
        require(bootMenuKeys.isNotEmpty() || setupKeys.isNotEmpty()) { "preset $vendor has no keys" }
    }
}

object BootKeyPresets {
    private fun taps(vararg usages: KeyUsage): List<BootKey> = usages.map { BootKey.Tap(it) }

    val all: List<BootKeyPreset> =
        listOf(
            BootKeyPreset(
                BootVendor.GENERIC,
                "Other / unknown",
                taps(KeyUsage.ESCAPE, KeyUsage.F12, KeyUsage.F11, KeyUsage.F10),
                taps(KeyUsage.F2, KeyUsage.DELETE),
            ),
            BootKeyPreset(BootVendor.DELL, "Dell", taps(KeyUsage.F12), taps(KeyUsage.F2)),
            BootKeyPreset(
                BootVendor.LENOVO,
                "Lenovo",
                taps(KeyUsage.F12, KeyUsage.ENTER),
                taps(KeyUsage.F1, KeyUsage.F2),
                note = "Enter opens the ThinkPad startup interrupt menu; IdeaPads use F2 for setup.",
            ),
            BootKeyPreset(
                BootVendor.HP,
                "HP",
                taps(KeyUsage.F9, KeyUsage.ESCAPE),
                taps(KeyUsage.F10),
                note = "Esc opens the startup menu.",
            ),
            BootKeyPreset(BootVendor.ASUS, "ASUS", taps(KeyUsage.F8), taps(KeyUsage.DELETE, KeyUsage.F2)),
            BootKeyPreset(BootVendor.ACER, "Acer", taps(KeyUsage.F12), taps(KeyUsage.F2)),
            BootKeyPreset(BootVendor.MSI, "MSI", taps(KeyUsage.F11), taps(KeyUsage.DELETE)),
            BootKeyPreset(BootVendor.GIGABYTE, "Gigabyte", taps(KeyUsage.F12), taps(KeyUsage.DELETE)),
            BootKeyPreset(BootVendor.INTEL_NUC, "Intel NUC", taps(KeyUsage.F10), taps(KeyUsage.F2)),
            BootKeyPreset(
                BootVendor.APPLE_MAC,
                "Apple Mac (Intel)",
                listOf(BootKey.Hold(Modifier.LEFT_ALT)),
                emptyList(),
                note = "Hold Option from power-on until the startup disk picker appears.",
            ),
            BootKeyPreset(
                BootVendor.MICROSOFT_SURFACE,
                "Microsoft Surface",
                listOf(BootKey.Unsupported("hold the volume-down button while powering on")),
                listOf(BootKey.Unsupported("hold the volume-up button while powering on")),
                note = "Surface firmware menus need hardware buttons.",
            ),
        )

    init {
        check(all.map { it.vendor }.toSet() == BootVendor.entries.toSet()) { "every vendor needs exactly one preset" }
        check(all.size == BootVendor.entries.size)
    }

    fun forVendor(vendor: BootVendor): BootKeyPreset = all.single { it.vendor == vendor }
}

data class TimedReport(
    /** Offset from the start of the plan. */
    val atMs: Long,
    val report: KeyboardReport,
)

/** A pure schedule of reports; the app sends each at its offset and always ends with release-all. */
data class BootKeyPlan(
    val reports: List<TimedReport>,
) {
    init {
        require(reports.isNotEmpty()) { "empty plan" }
        require(reports.size <= HidLimits.BOOT_KEY_PLAN_REPORT_COUNT_MAX) {
            "plan has ${reports.size} reports; limit is ${HidLimits.BOOT_KEY_PLAN_REPORT_COUNT_MAX}"
        }
        require(reports.all { it.atMs in 0..HidLimits.BOOT_KEY_PLAN_DURATION_MS_MAX }) {
            "report offsets must be 0..${HidLimits.BOOT_KEY_PLAN_DURATION_MS_MAX} ms"
        }
        require(reports.zipWithNext().all { (first, second) -> first.atMs <= second.atMs }) { "reports out of order" }
        require(reports.last().report.isReleaseAll) { "a plan must end with release-all" }
    }

    val durationMs: Long get() = reports.last().atMs
}

object BootKeyMasher {
    /** Taps [key] [pressCount] times, one tap every [intervalMs]. */
    fun repeat(
        key: KeyUsage,
        pressCount: Int,
        intervalMs: Long,
    ): BootKeyPlan {
        require(!key.isModifier && key != KeyUsage.ERROR_ROLL_OVER) { "mash an ordinary key, not $key" }
        require(pressCount in 1..HidLimits.BOOT_KEY_PRESS_COUNT_MAX) {
            "press count must be 1..${HidLimits.BOOT_KEY_PRESS_COUNT_MAX}: $pressCount"
        }
        require(intervalMs in HidLimits.BOOT_KEY_INTERVAL_MS_MIN..HidLimits.BOOT_KEY_INTERVAL_MS_MAX) {
            "interval must be ${HidLimits.BOOT_KEY_INTERVAL_MS_MIN}..${HidLimits.BOOT_KEY_INTERVAL_MS_MAX} ms: $intervalMs"
        }
        val press = KeyboardReport(0, listOf(key))
        val reports =
            (0 until pressCount).flatMap { pressIndex ->
                val pressAtMs = pressIndex * intervalMs
                listOf(
                    TimedReport(pressAtMs, press),
                    TimedReport(pressAtMs + HidLimits.BOOT_KEY_PRESS_DURATION_MS, KeyboardReport.RELEASE_ALL),
                )
            }
        return BootKeyPlan(reports)
    }

    /** Holds [modifier] for [durationMs], then releases. */
    fun hold(
        modifier: Modifier,
        durationMs: Long,
    ): BootKeyPlan {
        require(durationMs in 1..HidLimits.BOOT_KEY_HOLD_MS_MAX) { "hold must be 1..${HidLimits.BOOT_KEY_HOLD_MS_MAX} ms: $durationMs" }
        return BootKeyPlan(
            listOf(
                TimedReport(0, KeyboardReport(modifier.bit, emptyList())),
                TimedReport(durationMs, KeyboardReport.RELEASE_ALL),
            ),
        )
    }
}
