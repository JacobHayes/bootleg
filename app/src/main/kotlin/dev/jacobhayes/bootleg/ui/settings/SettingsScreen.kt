package dev.jacobhayes.bootleg.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.BuildConfig
import dev.jacobhayes.bootleg.Prefs
import dev.jacobhayes.bootleg.RootStatus
import dev.jacobhayes.bootleg.core.gadget.TetherMode
import dev.jacobhayes.bootleg.ui.drives.DrivesController

private const val REPOSITORY_URL = "https://github.com/JacobHayes/bootleg"

/** RNDIS has inbox drivers from Windows 7 on (and Linux) but none on macOS; NCM from Windows 10 2004. */
private fun tetherModeBody(mode: TetherMode): String =
    when (mode) {
        TetherMode.RNDIS -> "Windows 7 and later, and Linux. Not macOS."
        TetherMode.NCM -> "macOS, Linux, and Windows 10 (2004) and later."
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    backend: BootlegBackend,
    drives: DrivesController,
) {
    val wifiOnly by prefs.wifiOnlyDownloads.collectAsState()
    val restore by prefs.restoreAfterReboot.collectAsState()
    val reconnect by prefs.reconnectAfterDiskChange.collectAsState()
    val tetherMode by backend.usbTetherMode.collectAsState()
    val tetheringBusy by backend.usbTetheringBusy.collectAsState()
    val root by backend.rootStatus.collectAsState()
    // A mode change while tethering is on switches over; its result (e.g. refused) shows here.
    val drivesMessage =
        drives.state
            .collectAsState()
            .value.message
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(drivesMessage) {
        drivesMessage?.let {
            snackbar.showSnackbar(it)
            drives.clearMessage()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Downloads")
            SwitchRow(
                icon = Icons.Outlined.Wifi,
                title = "Wi-Fi only",
                body = "Wait for Wi-Fi before downloading images. Applies to downloads started after changing this.",
                checked = wifiOnly,
                onChange = prefs::setWifiOnlyDownloads,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Drives")
            SwitchRow(
                icon = Icons.Outlined.RestartAlt,
                title = "Restore drives after reboot",
                body = "Expose the last set of drives again when the phone starts. Drives ejected by the computer stay ejected.",
                checked = restore,
                onChange = prefs::setRestoreAfterReboot,
            )
            SwitchRow(
                icon = Icons.Outlined.Usb,
                title = "Reconnect USB after a disk change",
                body =
                    "For computers that don't notice a disk added or swapped (the drive stays empty until a rescan). " +
                        "Reconnecting drops every drive and the keyboard and mouse for a moment, and can interrupt a " +
                        "computer booting from a drive. Skipped while a writable disk is exposed.",
                checked = reconnect,
                onChange = prefs::setReconnectAfterDiskChange,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("USB tethering mode")
            TetherMode.entries.forEach { mode ->
                val enabled = root is RootStatus.Granted && !tetheringBusy
                ListItem(
                    headlineContent = { Text(mode.name) },
                    supportingContent = { Text(tetherModeBody(mode)) },
                    leadingContent = { RadioButton(selected = mode == tetherMode, onClick = null, enabled = enabled) },
                    modifier =
                        Modifier
                            .selectable(mode == tetherMode, enabled = enabled, role = Role.RadioButton) { drives.setUsbTetherMode(mode) }
                            .testTag("usb-tethering-mode-${mode.wireName}"),
                )
            }
            Text(
                "Switching the mode re-plugs USB once, the next time tethering turns on, so eject every disk first.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("About")
            ListItem(
                headlineContent = { Text("Bootleg ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Boot computers from images on this phone. MIT licensed.") },
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
            )
            ListItem(
                headlineContent = { Text("Source code") },
                supportingContent = { Text(REPOSITORY_URL.removePrefix("https://")) },
                leadingContent = { Icon(Icons.Outlined.Code, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)))
                    },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
