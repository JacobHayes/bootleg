package dev.jacobhayes.bootleg.core.hid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BootKeyPresetsTest {
    private fun taps(vararg usages: KeyUsage) = usages.map { BootKey.Tap(it) }

    @Test
    fun `vendor presets match the SPEC table`() {
        fun check(
            vendor: BootVendor,
            bootMenu: List<BootKey>,
            setup: List<BootKey>,
        ) {
            val preset = BootKeyPresets.forVendor(vendor)
            assertEquals(bootMenu, preset.bootMenuKeys, "$vendor boot menu")
            assertEquals(setup, preset.setupKeys, "$vendor setup")
        }
        check(BootVendor.DELL, taps(KeyUsage.F12), taps(KeyUsage.F2))
        check(BootVendor.LENOVO, taps(KeyUsage.F12, KeyUsage.ENTER), taps(KeyUsage.F1, KeyUsage.F2))
        check(BootVendor.HP, taps(KeyUsage.F9, KeyUsage.ESCAPE), taps(KeyUsage.F10))
        check(BootVendor.ASUS, taps(KeyUsage.F8), taps(KeyUsage.DELETE, KeyUsage.F2))
        check(BootVendor.ACER, taps(KeyUsage.F12), taps(KeyUsage.F2))
        check(BootVendor.MSI, taps(KeyUsage.F11), taps(KeyUsage.DELETE))
        check(BootVendor.GIGABYTE, taps(KeyUsage.F12), taps(KeyUsage.DELETE))
        check(BootVendor.INTEL_NUC, taps(KeyUsage.F10), taps(KeyUsage.F2))
        check(BootVendor.APPLE_MAC, listOf(BootKey.Hold(Modifier.LEFT_ALT)), emptyList())
        check(BootVendor.GENERIC, taps(KeyUsage.ESCAPE, KeyUsage.F12, KeyUsage.F11, KeyUsage.F10), taps(KeyUsage.F2, KeyUsage.DELETE))
    }

    @Test
    fun `surface is marked unsupported`() {
        val surface = BootKeyPresets.forVendor(BootVendor.MICROSOFT_SURFACE)
        assertTrue((surface.bootMenuKeys + surface.setupKeys).all { it is BootKey.Unsupported })
    }

    @Test
    fun `every vendor has exactly one preset`() {
        assertEquals(BootVendor.entries, BootKeyPresets.all.map { it.vendor })
    }

    @Test
    fun `boot keys reject modifiers as taps`() {
        assertThrows<IllegalArgumentException> { BootKey.Tap(KeyUsage.LEFT_ALT) }
    }
}

class BootKeyMasherTest {
    @Test
    fun `repeat produces press and release pairs at the interval`() {
        val plan = BootKeyMasher.repeat(KeyUsage.F12, pressCount = 3, intervalMs = 100)
        val press = KeyboardReport(0, listOf(KeyUsage.F12))
        val release = KeyboardReport.RELEASE_ALL
        val pressDurationMs = HidLimits.BOOT_KEY_PRESS_DURATION_MS
        assertEquals(
            listOf(
                TimedReport(0, press),
                TimedReport(pressDurationMs, release),
                TimedReport(100, press),
                TimedReport(100 + pressDurationMs, release),
                TimedReport(200, press),
                TimedReport(200 + pressDurationMs, release),
            ),
            plan.reports,
        )
        assertEquals(200 + pressDurationMs, plan.durationMs)
    }

    @Test
    fun `repeat at the limits`() {
        val plan = BootKeyMasher.repeat(KeyUsage.DELETE, HidLimits.BOOT_KEY_PRESS_COUNT_MAX, HidLimits.BOOT_KEY_INTERVAL_MS_MIN)
        assertEquals(2 * HidLimits.BOOT_KEY_PRESS_COUNT_MAX, plan.reports.size)
        // The key is always released before the next press.
        assertTrue(plan.reports.zipWithNext().all { (first, second) -> first.report.isReleaseAll != second.report.isReleaseAll })
        assertTrue(plan.reports.zipWithNext().all { (first, second) -> first.atMs < second.atMs })
    }

    @Test
    fun `repeat rejects out of range arguments`() {
        assertThrows<IllegalArgumentException> { BootKeyMasher.repeat(KeyUsage.F12, 0, 100) }
        assertThrows<IllegalArgumentException> { BootKeyMasher.repeat(KeyUsage.F12, HidLimits.BOOT_KEY_PRESS_COUNT_MAX + 1, 100) }
        assertThrows<IllegalArgumentException> { BootKeyMasher.repeat(KeyUsage.F12, 10, HidLimits.BOOT_KEY_INTERVAL_MS_MIN - 1) }
        assertThrows<IllegalArgumentException> { BootKeyMasher.repeat(KeyUsage.F12, 10, HidLimits.BOOT_KEY_INTERVAL_MS_MAX + 1) }
        assertThrows<IllegalArgumentException> { BootKeyMasher.repeat(KeyUsage.LEFT_SHIFT, 10, 100) }
    }

    @Test
    fun `hold presses the modifier then releases after the duration`() {
        val plan = BootKeyMasher.hold(Modifier.LEFT_ALT, durationMs = 5_000)
        assertEquals(
            listOf(TimedReport(0, KeyboardReport(Modifier.LEFT_ALT.bit, emptyList())), TimedReport(5_000, KeyboardReport.RELEASE_ALL)),
            plan.reports,
        )
        BootKeyMasher.hold(Modifier.LEFT_ALT, HidLimits.BOOT_KEY_HOLD_MS_MAX)
        assertThrows<IllegalArgumentException> { BootKeyMasher.hold(Modifier.LEFT_ALT, HidLimits.BOOT_KEY_HOLD_MS_MAX + 1) }
        assertThrows<IllegalArgumentException> { BootKeyMasher.hold(Modifier.LEFT_ALT, 0) }
    }

    @Test
    fun `press duration fits inside the minimum interval`() {
        assertTrue(HidLimits.BOOT_KEY_PRESS_DURATION_MS < HidLimits.BOOT_KEY_INTERVAL_MS_MIN)
    }

    @Test
    fun `plans must end released and be ordered`() {
        assertThrows<IllegalArgumentException> { BootKeyPlan(emptyList()) }
        assertThrows<IllegalArgumentException> { BootKeyPlan(listOf(TimedReport(0, KeyboardReport(0, listOf(KeyUsage.A))))) }
        assertThrows<IllegalArgumentException> {
            BootKeyPlan(listOf(TimedReport(10, KeyboardReport(0, listOf(KeyUsage.A))), TimedReport(0, KeyboardReport.RELEASE_ALL)))
        }
    }

    @Test
    fun `plans reject negative offsets, too many reports, and too long a schedule`() {
        val press = KeyboardReport(0, listOf(KeyUsage.A))
        val release = KeyboardReport.RELEASE_ALL
        assertThrows<IllegalArgumentException> { BootKeyPlan(listOf(TimedReport(-1, press), TimedReport(0, release))) }
        assertThrows<IllegalArgumentException> { BootKeyPlan(listOf(TimedReport(-10, release))) }
        assertThrows<IllegalArgumentException> {
            BootKeyPlan(listOf(TimedReport(0, press), TimedReport(HidLimits.BOOT_KEY_PLAN_DURATION_MS_MAX + 1, release)))
        }
        BootKeyPlan(listOf(TimedReport(0, press), TimedReport(HidLimits.BOOT_KEY_PLAN_DURATION_MS_MAX, release)))
        val tooMany = List(HidLimits.BOOT_KEY_PLAN_REPORT_COUNT_MAX + 1) { TimedReport(it.toLong(), release) }
        assertThrows<IllegalArgumentException> { BootKeyPlan(tooMany) }
        BootKeyPlan(tooMany.drop(1))
    }

    @Test
    fun `the largest masher plans fit the plan limits`() {
        val mash = BootKeyMasher.repeat(KeyUsage.F12, HidLimits.BOOT_KEY_PRESS_COUNT_MAX, HidLimits.BOOT_KEY_INTERVAL_MS_MAX)
        assertEquals(HidLimits.BOOT_KEY_PLAN_REPORT_COUNT_MAX, mash.reports.size)
        assertTrue(mash.durationMs <= HidLimits.BOOT_KEY_PLAN_DURATION_MS_MAX)
        assertTrue(
            BootKeyMasher.hold(Modifier.LEFT_ALT, HidLimits.BOOT_KEY_HOLD_MS_MAX).durationMs <= HidLimits.BOOT_KEY_PLAN_DURATION_MS_MAX,
        )
    }
}
