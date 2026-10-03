package dev.jacobhayes.bootleg.boot

import android.content.Context
import android.provider.Settings
import dev.jacobhayes.bootleg.BootlegApp

/**
 * Tells a real reboot from the BOOT_COMPLETED that Android 15+ also sends when an app leaves the
 * force-stopped state (force-stop, then the user opens it again). Only a real reboot may restore or
 * forget the journal: after a force-stop the kernel still exports the drives.
 *
 * The device's boot count ([Settings.Global.BOOT_COUNT]) is stored whenever the journal is known to
 * belong to the current boot: when the boot receiver handles a boot, and after every gadget change
 * the user (or a remote client) makes. A BOOT_COMPLETED whose boot count matches is not a reboot.
 */
object BootGeneration {
    private const val KEY_JOURNAL_BOOT_COUNT = "journal_boot_count"

    /** True unless both counts are known and equal; an unknown count keeps the old behaviour (act on every boot). */
    fun isNewBoot(
        currentBootCount: Int?,
        journalBootCount: Int?,
    ): Boolean = currentBootCount == null || journalBootCount == null || currentBootCount != journalBootCount

    /** The device's boot count, or null if the platform doesn't report it. */
    fun currentBootCount(context: Context): Int? =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }

    fun journalBootCount(context: Context): Int? = preferences(context).getInt(KEY_JOURNAL_BOOT_COUNT, -1).takeIf { it >= 0 }

    /** Records that the journal belongs to the current boot. */
    fun markJournalCurrent(context: Context) {
        val current = currentBootCount(context) ?: return
        if (journalBootCount(context) == current) return
        preferences(context).edit().putInt(KEY_JOURNAL_BOOT_COUNT, current).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(BootlegApp.PREFERENCES_NAME, Context.MODE_PRIVATE)
}
