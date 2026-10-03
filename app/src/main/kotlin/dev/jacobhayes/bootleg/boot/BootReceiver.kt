package dev.jacobhayes.bootleg.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jacobhayes.bootleg.BootlegApp
import dev.jacobhayes.bootleg.service.BootlegService

/**
 * Hands boot handling to [BootlegService]: it restores the last exported set when the user opted
 * in, and otherwise clears the stale journal so a later reconcile doesn't re-export media nobody
 * asked for. The work can't happen here: the root binder arrives by broadcast, which Android
 * won't deliver to this process while this receiver is still running.
 *
 * Android 15+ also sends BOOT_COMPLETED when the app leaves the force-stopped state; the drives are
 * still exported then, so [BootGeneration] filters that out and only a real reboot is handled.
 *
 * The images live on credential-encrypted storage (`/data/media/0`), readable only after the user
 * unlocks. BOOT_COMPLETED reaches components that aren't direct-boot aware only after that unlock,
 * so restore never runs against locked storage; `BootManifestTest` keeps the app that way.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val currentBootCount = BootGeneration.currentBootCount(context)
        val journalBootCount = BootGeneration.journalBootCount(context)
        if (!BootGeneration.isNewBoot(currentBootCount, journalBootCount)) {
            Log.i(TAG, "BOOT_COMPLETED within boot $currentBootCount (the app left the force-stopped state): not a reboot")
            return
        }
        BootGeneration.markJournalCurrent(context)
        val restore =
            context
                .getSharedPreferences(BootlegApp.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(BootlegApp.PREFERENCE_RESTORE_AFTER_REBOOT, false)
        BootlegService.start(context, if (restore) BootlegService.ACTION_RESTORE else BootlegService.ACTION_FORGET)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
