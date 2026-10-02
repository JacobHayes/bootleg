package dev.jacobhayes.bootleg.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.catalog.CatalogRepository
import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.download.Downloads
import dev.jacobhayes.bootleg.ui.common.EmptyState
import kotlinx.coroutines.launch

private const val CATALOG_MAX_AGE_MS = 6L * 60 * 60 * 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    catalog: CatalogRepository,
    downloads: Downloads,
    onShowDownloads: () -> Unit,
) {
    val state by catalog.state.collectAsState()
    val records by downloads.records.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var category by rememberSaveable { mutableStateOf(Category.DESKTOP) }
    var arch by rememberSaveable { mutableStateOf(Arch.X86_64) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        if (state.entries.isEmpty()) catalog.loadCache()
        if (catalog.isStale(CATALOG_MAX_AGE_MS)) catalog.refresh()
    }

    val onDownload: (Release, String) -> Unit = { release, distroName ->
        val message =
            when (val result = downloads.enqueue(release, distroName)) {
                Downloads.EnqueueResult.Started -> "Downloading ${release.fileName}"
                Downloads.EnqueueResult.AlreadyActive -> "Already downloading"
                Downloads.EnqueueResult.AlreadyDownloaded -> "Already in your library"
                is Downloads.EnqueueResult.Rejected -> result.reason
            }
        scope.launch {
            val action = snackbar.showSnackbar(message, actionLabel = "View")
            if (action == androidx.compose.material3.SnackbarResult.ActionPerformed) onShowDownloads()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catalog") },
                actions = {
                    IconButton(onClick = { scope.launch { catalog.refresh() } }, enabled = !state.refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            CategoryTabs(category) { category = it }
            ArchFilter(arch) { arch = it }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { scope.launch { catalog.refresh() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                val entries = state.entries.filter { it.distro.category == category }
                when {
                    catalog.distros.isEmpty() ->
                        EmptyState(
                            icon = Icons.Outlined.CloudDownload,
                            title = "No catalog sources",
                            body = "This build has no distro scrapers yet. Add images from the Library instead.",
                        )

                    entries.isEmpty() && state.entries.isEmpty() ->
                        EmptyState(
                            icon = Icons.Outlined.CloudDownload,
                            title = "Loading catalog…",
                            body = "Fetching the latest releases from each distro.",
                        )

                    else ->
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(entries, key = { it.distro.id }) { entry ->
                                DistroCard(
                                    entry = entry,
                                    arch = arch,
                                    expanded = entry.distro.id in expanded,
                                    records = records,
                                    onToggle = {
                                        expanded =
                                            if (entry.distro.id in expanded) expanded - entry.distro.id else expanded + entry.distro.id
                                    },
                                    onDownload = { onDownload(it, entry.distro.name) },
                                )
                            }
                        }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryTabs(
    selected: Category,
    onSelect: (Category) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = Category.entries.indexOf(selected)) {
        Category.entries.forEach { category ->
            Tab(selected = category == selected, onClick = { onSelect(category) }, text = { Text(categoryLabel(category)) })
        }
    }
}

@Composable
private fun ArchFilter(
    selected: Arch,
    onSelect: (Arch) -> Unit,
) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Arch.entries.forEach { arch ->
            FilterChip(selected = arch == selected, onClick = { onSelect(arch) }, label = { Text(archLabel(arch)) })
        }
    }
}

fun categoryLabel(category: Category): String =
    when (category) {
        Category.DESKTOP -> "Desktop"
        Category.SERVER -> "Server"
        Category.RESCUE -> "Rescue"
    }

fun archLabel(arch: Arch): String =
    when (arch) {
        Arch.X86_64 -> "x86_64"
        Arch.ARM64 -> "arm64"
    }
