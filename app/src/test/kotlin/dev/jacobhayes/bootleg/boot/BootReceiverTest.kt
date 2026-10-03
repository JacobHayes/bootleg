package dev.jacobhayes.bootleg.boot

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.jacobhayes.bootleg.service.BootlegService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android 15+ sends BOOT_COMPLETED when an app leaves the force-stopped state. That must not tear
 * down (or restore over) drives the kernel still exports; only a real reboot is handled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BootReceiverTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun setBootCount(count: Int) = Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, count)

    private fun receiveBootCompleted(): Intent? {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        return shadowOf(context).nextStartedService
    }

    @Test
    fun `a real reboot forgets the previous boot's journal`() {
        setBootCount(7)
        BootGeneration.markJournalCurrent(context)
        setBootCount(8)

        val started = receiveBootCompleted()

        assertEquals(BootlegService.ACTION_FORGET, started?.action)
        assertEquals(8, BootGeneration.journalBootCount(context))
    }

    @Test
    fun `leaving the force-stopped state in the same boot leaves the drives alone`() {
        setBootCount(8)
        BootGeneration.markJournalCurrent(context)

        assertNull(receiveBootCompleted())
    }

    @Test
    fun `a second BOOT_COMPLETED after handling a reboot is ignored`() {
        setBootCount(3)
        receiveBootCompleted()

        assertNull(receiveBootCompleted())
    }

    @Test
    fun `the decision is conservative when a count is unknown`() {
        assertTrue(BootGeneration.isNewBoot(currentBootCount = null, journalBootCount = 4))
        assertTrue(BootGeneration.isNewBoot(currentBootCount = 4, journalBootCount = null))
        assertTrue(BootGeneration.isNewBoot(currentBootCount = 5, journalBootCount = 4))
        assertFalse(BootGeneration.isNewBoot(currentBootCount = 4, journalBootCount = 4))
    }
}
