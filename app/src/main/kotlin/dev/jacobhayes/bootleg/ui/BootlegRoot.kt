package dev.jacobhayes.bootleg.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.jacobhayes.bootleg.BootlegApp
import dev.jacobhayes.bootleg.ui.catalog.CatalogScreen
import dev.jacobhayes.bootleg.ui.control.ControlScreen
import dev.jacobhayes.bootleg.ui.downloads.DownloadsScreen
import dev.jacobhayes.bootleg.ui.drives.DrivesScreen
import dev.jacobhayes.bootleg.ui.library.LibraryScreen
import dev.jacobhayes.bootleg.ui.settings.SettingsScreen

enum class Tab(
    val label: String,
    val icon: ImageVector,
) {
    DRIVES("Drives", Icons.Outlined.Usb),
    CONTROL("Control", Icons.Outlined.Keyboard),
    LIBRARY("Library", Icons.Outlined.Inventory2),
    CATALOG("Catalog", Icons.Outlined.CloudDownload),
    DOWNLOADS("Downloads", Icons.Outlined.Downloading),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun BootlegRoot(
    app: BootlegApp,
    selectedTab: Tab,
    onSelectTab: (Tab) -> Unit,
) {
    val records by app.downloads.records.collectAsState()
    val activeDownloads = records.values.count { it.isActive }
    val browse = rememberBrowseForImage(app)

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { onSelectTab(tab) },
                        icon = { TabIcon(tab, if (tab == Tab.DOWNLOADS) activeDownloads else 0) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            when (selectedTab) {
                Tab.DRIVES -> DrivesScreen(app.backend, app.drives, browse)
                Tab.CONTROL -> ControlScreen(app.backend, app.hid)
                Tab.LIBRARY ->
                    LibraryScreen(app.backend, app.drives, app.fileTasks, app.downloads, onExposed = { onSelectTab(Tab.DRIVES) })
                Tab.CATALOG -> CatalogScreen(app.catalog, app.downloads, onShowDownloads = { onSelectTab(Tab.DOWNLOADS) })
                Tab.DOWNLOADS ->
                    DownloadsScreen(
                        app.downloads,
                        app.prefs.wifiOnlyDownloads
                            .collectAsState()
                            .value,
                    )
                Tab.SETTINGS -> SettingsScreen(app.prefs)
            }
        }
    }
}

@Composable
private fun TabIcon(
    tab: Tab,
    badgeCount: Int,
) {
    if (badgeCount > 0) {
        BadgedBox(badge = { Badge { Text("$badgeCount") } }) { Icon(tab.icon, contentDescription = null) }
    } else {
        Icon(tab.icon, contentDescription = null)
    }
}

/** ACTION_OPEN_DOCUMENT for a drive slot: only files with a real internal-storage path can be used in place. */
@Composable
private fun rememberBrowseForImage(app: BootlegApp): ((String) -> Unit) -> Unit {
    val pending = remember { mutableStateOf<((String) -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val onPicked = pending.value
            pending.value = null
            if (uri == null || onPicked == null) return@rememberLauncherForActivityResult
            val picked = app.fileTasks.describe(uri)
            val path = picked.mediaPath
            if (path != null) {
                onPicked(path)
            } else {
                app.drives.showMessage("${picked.displayName} isn't on internal storage. Import it in the Library first.")
            }
        }
    return { onPicked ->
        pending.value = onPicked
        launcher.launch(arrayOf("*/*"))
    }
}
