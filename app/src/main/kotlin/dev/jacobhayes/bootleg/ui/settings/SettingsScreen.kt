package dev.jacobhayes.bootleg.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.BuildConfig
import dev.jacobhayes.bootleg.Prefs
import dev.jacobhayes.bootleg.core.gadget.MouseLayout

private const val REPOSITORY_URL = "https://github.com/JacobHayes/bootleg"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    onMouseLayoutChanged: () -> Unit = {},
) {
    val wifiOnly by prefs.wifiOnlyDownloads.collectAsState()
    val restore by prefs.restoreAfterReboot.collectAsState()
    val mouseLayout by prefs.mouseLayout.collectAsState()
    val context = LocalContext.current

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
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
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Keyboard & mouse")
            SwitchRow(
                icon = Icons.Outlined.Mouse,
                title = "Compatible mouse (no wheel)",
                body =
                    "Use a plain 3-button mouse without scrolling, for computers whose firmware " +
                        "mishandles the scroll wheel. Changing it while the mouse is on reconnects USB.",
                checked = mouseLayout == MouseLayout.BOOT,
                onChange = { compatible ->
                    prefs.setMouseLayout(if (compatible) MouseLayout.BOOT else MouseLayout.WHEEL)
                    onMouseLayoutChanged()
                },
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
