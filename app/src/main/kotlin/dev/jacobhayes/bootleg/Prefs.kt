package dev.jacobhayes.bootleg

import android.content.Context
import android.content.SharedPreferences
import dev.jacobhayes.bootleg.core.gadget.MouseLayout
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
    private val remoteEnabledFlow = MutableStateFlow(preferences.getBoolean(KEY_REMOTE_ENABLED, false))
    private val mouseLayoutFlow = MutableStateFlow(mouseLayoutOf(preferences))
    private val usbTetherChoiceFlow = MutableStateFlow(preferences.getBoolean(KEY_USB_TETHER_CHOICE, false))

    val wifiOnlyDownloads: StateFlow<Boolean> = wifiOnlyFlow.asStateFlow()
    val restoreAfterReboot: StateFlow<Boolean> = restoreFlow.asStateFlow()

    /** Remote control is on (off by default; never turned on at boot). */
    val remoteEnabled: StateFlow<Boolean> = remoteEnabledFlow.asStateFlow()

    /** The mouse layout HID uses: the scroll wheel unless "Compatible mouse (no wheel)" is on. */
    val mouseLayout: StateFlow<MouseLayout> = mouseLayoutFlow.asStateFlow()

    fun setMouseLayout(layout: MouseLayout) {
        preferences.edit().putBoolean(KEY_COMPATIBLE_MOUSE, layout == MouseLayout.BOOT).apply()
        mouseLayoutFlow.value = layout
    }

    /** USB tethering as last chosen through Bootleg (see `TetherChoice`); restore after reboot turns it back on. */
    val usbTetherChoice: StateFlow<Boolean> = usbTetherChoiceFlow.asStateFlow()

    fun setUsbTetherChoice(on: Boolean) {
        if (usbTetherChoiceFlow.value == on) return
        preferences.edit().putBoolean(KEY_USB_TETHER_CHOICE, on).apply()
        usbTetherChoiceFlow.value = on
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        wifiOnlyFlow.value = enabled
    }

    fun setRestoreAfterReboot(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RESTORE_AFTER_REBOOT, enabled).apply()
        restoreFlow.value = enabled
    }

    private val reconnectAfterDiskChangeFlow = MutableStateFlow(preferences.getBoolean(KEY_RECONNECT_AFTER_DISK_CHANGE, false))

    /** Re-plug USB after a disk goes in or changes in place, for hosts that don't look for new media. */
    val reconnectAfterDiskChange: StateFlow<Boolean> = reconnectAfterDiskChangeFlow.asStateFlow()

    fun setReconnectAfterDiskChange(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RECONNECT_AFTER_DISK_CHANGE, enabled).apply()
        reconnectAfterDiskChangeFlow.value = enabled
    }

    fun setRemoteEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_REMOTE_ENABLED, enabled).apply()
        remoteEnabledFlow.value = enabled
    }

    /** The Control pane last selected (its enum name), so Control reopens on it after a restart; null until one is picked. */
    var controlPane: String?
        get() = preferences.getString(KEY_CONTROL_PANE, null)
        set(value) = preferences.edit().putString(KEY_CONTROL_PANE, value).apply()

    var notificationPermissionAsked: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
        set(value) = preferences.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, value).apply()

    companion object {
        const val FILE_NAME = "bootleg"
        const val KEY_RESTORE_AFTER_REBOOT = "restore_after_reboot"
        const val KEY_WIFI_ONLY = "wifi_only_downloads"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        const val KEY_REMOTE_ENABLED = "remote_enabled"
        const val KEY_COMPATIBLE_MOUSE = "compatible_mouse"
        const val KEY_USB_TETHER_CHOICE = "usb_tether_choice"
        const val KEY_CONTROL_PANE = "control_pane"
        const val KEY_RECONNECT_AFTER_DISK_CHANGE = "reconnect_after_disk_change"

        /** Read by the boot path too, which has no [Prefs] instance. */
        fun mouseLayoutOf(preferences: SharedPreferences): MouseLayout =
            if (preferences.getBoolean(KEY_COMPATIBLE_MOUSE, false)) MouseLayout.BOOT else MouseLayout.WHEEL
    }
}
