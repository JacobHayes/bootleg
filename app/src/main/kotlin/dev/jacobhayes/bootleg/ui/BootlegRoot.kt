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
import androidx.compose.material.icons.outlined.SettingsRemote
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.jacobhayes.bootleg.BootlegApp
import dev.jacobhayes.bootleg.ui.catalog.CatalogScreen
import dev.jacobhayes.bootleg.ui.control.ControlScreen
import dev.jacobhayes.bootleg.ui.downloads.DownloadsScreen
import dev.jacobhayes.bootleg.ui.drives.DrivesScreen
import dev.jacobhayes.bootleg.ui.library.LibraryScreen
import dev.jacobhayes.bootleg.ui.remote.RemoteRoute
import dev.jacobhayes.bootleg.ui.settings.SettingsScreen

/** Screens. [navParent] is the bottom-bar item a screen lives under (Downloads opens from Library and Catalog). */
enum class Tab(
    val label: String,
    val icon: ImageVector,
    val navParent: Tab? = null,
) {
    DRIVES("Drives", Icons.Outlined.Usb),
    CONTROL("Control", Icons.Outlined.Keyboard),
    LIBRARY("Library", Icons.Outlined.Inventory2),
    CATALOG("Catalog", Icons.Outlined.CloudDownload),
    REMOTE("Remote", Icons.Outlined.SettingsRemote),
    SETTINGS("Settings", Icons.Outlined.Settings),
    DOWNLOADS("Downloads", Icons.Outlined.Downloading, navParent = LIBRARY),
    ;

    val inNavBar: Boolean get() = navParent == null
}

@Composable
fun BootlegRoot(
    app: BootlegApp,
    selectedTab: Tab,
    onSelectTab: (Tab) -> Unit,
) {
    val records by app.downloads.records.collectAsState()
    val activeDownloads = records.values.count { it.isActive }
    val remoteState by app.remote.state.collectAsState()
    val browse = rememberBrowseForImage(app)
    // Each tab keeps its rememberSaveable state (Catalog category, expanded cards, scroll) while another is shown.
    val tabStates = rememberSaveableStateHolder()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.filter { it.inNavBar }.forEach { tab ->
                    val badgeCount =
                        when (tab) {
                            Tab.LIBRARY -> activeDownloads
                            Tab.REMOTE -> remoteState.pendingCount
                            else -> 0
                        }
                    NavigationBarItem(
                        selected = tab == (selectedTab.navParent ?: selectedTab),
                        onClick = { onSelectTab(tab) },
                        icon = { TabIcon(tab, badgeCount) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            tabStates.SaveableStateProvider(selectedTab.name) { TabContent(app, selectedTab, onSelectTab, browse) }
        }
    }
}

@Composable
private fun TabContent(
    app: BootlegApp,
    selectedTab: Tab,
    onSelectTab: (Tab) -> Unit,
    browse: ((String) -> Unit) -> Unit,
) {
    when (selectedTab) {
        Tab.DRIVES -> DrivesScreen(app.backend, app.drives, browse)
        Tab.CONTROL -> {
            // The Control tab in the foreground preempts any remote controller.
            LifecycleResumeEffect(Unit) {
                app.remote.setLocalCapture(true)
                onPauseOrDispose { app.remote.setLocalCapture(false) }
            }
            ControlScreen(app.backend, app.hid)
        }
        Tab.LIBRARY ->
            LibraryScreen(
                app.backend,
                app.drives,
                app.fileTasks,
                app.downloads,
                onExposed = { onSelectTab(Tab.DRIVES) },
                onShowDownloads = { onSelectTab(Tab.DOWNLOADS) },
            )
        Tab.CATALOG -> CatalogScreen(app.catalog, app.downloads, onShowDownloads = { onSelectTab(Tab.DOWNLOADS) })
        Tab.DOWNLOADS ->
            DownloadsScreen(
                app.downloads,
                app.prefs.wifiOnlyDownloads
                    .collectAsState()
                    .value,
                onBack = { onSelectTab(Tab.LIBRARY) },
            )
        Tab.REMOTE -> RemoteRoute(app.remote)
        Tab.SETTINGS -> SettingsScreen(app.prefs)
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
