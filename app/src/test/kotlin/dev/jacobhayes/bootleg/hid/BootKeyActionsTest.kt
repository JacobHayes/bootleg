package dev.jacobhayes.bootleg.hid

import dev.jacobhayes.bootleg.core.hid.BootKey
import dev.jacobhayes.bootleg.core.hid.BootKeyPresets
import dev.jacobhayes.bootleg.core.hid.HidLimits
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.hid.KeyboardReport
import dev.jacobhayes.bootleg.core.hid.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BootKeyActionsTest {
    @Test
    fun `mash F12 for 20 s taps every 100 ms and ends released`() {
        val plan = checkNotNull(BootKeyActions.plan(BootKey.Tap(KeyUsage.F12), 20_000))
        val presses = plan.reports.filter { !it.report.isReleaseAll }
        assertEquals(200, presses.size)
        assertEquals(100L, presses[1].atMs - presses[0].atMs)
        assertTrue(
            plan.reports
                .last()
                .report.isReleaseAll,
        )
        assertTrue(plan.durationMs <= 20_000)
    }

    @Test
    fun `every preset key and duration makes a valid plan or is explicitly unsupported`() {
        for (preset in BootKeyPresets.all) {
            for (key in preset.bootMenuKeys + preset.setupKeys) {
                for (durationMs in BootKeyActions.durationsMs) {
                    val plan = BootKeyActions.plan(key, durationMs)
                    if (key is BootKey.Unsupported) {
                        assertNull(plan)
                    } else {
                        checkNotNull(plan)
                        assertTrue(plan.reports.count { !it.report.isReleaseAll } <= HidLimits.BOOT_KEY_PRESS_COUNT_MAX)
                    }
                }
            }
        }
    }

    @Test
    fun `holding Option re-sends the hold so a host USB reset still sees it`() {
        val plan = checkNotNull(BootKeyActions.plan(BootKey.Hold(Modifier.LEFT_ALT), 1_000))
        val reports = BootKeyActions.withHoldResends(plan)
        val held = KeyboardReport(Modifier.LEFT_ALT.bit)
        assertEquals(listOf(0L, 200L, 400L, 600L, 800L), reports.filter { it.report == held }.map { it.atMs })
        assertEquals(1_000L, reports.last().atMs)
        assertTrue(reports.last().report.isReleaseAll)
        assertTrue(reports.zipWithNext().all { (a, b) -> a.atMs <= b.atMs })
    }

    @Test
    fun `mashing has no resends (taps are already short)`() {
        val plan = checkNotNull(BootKeyActions.plan(BootKey.Tap(KeyUsage.DELETE), 10_000))
        assertEquals(plan.reports, BootKeyActions.withHoldResends(plan))
    }

    @Test
    fun `labels name the key`() {
        assertEquals("Mash F12", BootKeyActions.label(BootKey.Tap(KeyUsage.F12)))
        assertEquals("Mash Esc", BootKeyActions.label(BootKey.Tap(KeyUsage.ESCAPE)))
        assertEquals("Hold Option", BootKeyActions.label(BootKey.Hold(Modifier.LEFT_ALT)))
    }
}
