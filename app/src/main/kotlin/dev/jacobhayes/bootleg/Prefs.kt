package dev.jacobhayes.bootleg

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App preferences in SharedPreferences "bootleg" (shared with the system side, e.g. the boot receiver). */
class Prefs(
    context: Context,
) {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val wifiOnlyFlow = MutableStateFlow(preferences.getBoolean(KEY_WIFI_ONLY, false))
    private val restoreFlow = MutableStateFlow(preferences.getBoolean(KEY_RESTORE_AFTER_REBOOT, false))

    val wifiOnlyDownloads: StateFlow<Boolean> = wifiOnlyFlow.asStateFlow()
    val restoreAfterReboot: StateFlow<Boolean> = restoreFlow.asStateFlow()

    fun setWifiOnlyDownloads(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        wifiOnlyFlow.value = enabled
    }

    fun setRestoreAfterReboot(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RESTORE_AFTER_REBOOT, enabled).apply()
        restoreFlow.value = enabled
    }

    var notificationPermissionAsked: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
        set(value) = preferences.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, value).apply()

    companion object {
        const val FILE_NAME = "bootleg"
        const val KEY_RESTORE_AFTER_REBOOT = "restore_after_reboot"
        const val KEY_WIFI_ONLY = "wifi_only_downloads"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
    }
}
