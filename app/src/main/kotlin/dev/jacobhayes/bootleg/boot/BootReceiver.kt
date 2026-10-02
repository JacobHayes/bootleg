package dev.jacobhayes.bootleg.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.jacobhayes.bootleg.BootlegApp
import dev.jacobhayes.bootleg.service.BootlegService

/**
 * Hands boot handling to [BootlegService]: it restores the last exported set when the user opted
 * in, and otherwise clears the stale journal so a later reconcile doesn't re-export media nobody
 * asked for. The work can't happen here: the root binder arrives by broadcast, which Android
 * won't deliver to this process while this receiver is still running.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val restore =
            context
                .getSharedPreferences(BootlegApp.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(BootlegApp.PREFERENCE_RESTORE_AFTER_REBOOT, false)
        BootlegService.start(context, if (restore) BootlegService.ACTION_RESTORE else BootlegService.ACTION_FORGET)
    }
}
