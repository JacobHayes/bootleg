package dev.jacobhayes.bootleg.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.ImageFile
import dev.jacobhayes.bootleg.download.Downloads
import dev.jacobhayes.bootleg.storage.FileTasks
import dev.jacobhayes.bootleg.storage.PickedFile
import dev.jacobhayes.bootleg.storage.StoragePaths
import dev.jacobhayes.bootleg.ui.common.ConfirmDialog
import dev.jacobhayes.bootleg.ui.common.EmptyState
import dev.jacobhayes.bootleg.ui.common.NoticeCard
import dev.jacobhayes.bootleg.ui.common.Tone
import dev.jacobhayes.bootleg.ui.drives.DrivesController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    backend: BootlegBackend,
    drives: DrivesController,
    fileTasks: FileTasks,
    downloads: Downloads,
    onExposed: () -> Unit,
    onShowDownloads: () -> Unit,
) {
    val images by backend.images.collectAsState()
    val tasks by fileTasks.progress.collectAsState()
    val outcome by fileTasks.lastOutcome.collectAsState()
    val records by downloads.records.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var showBlankDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ImageFile?>(null) }
    var storageGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    LifecycleResumeEffect(Unit) {
        storageGranted = Environment.isExternalStorageManager()
        onPauseOrDispose {}
    }
    LaunchedEffect(outcome) {
        val current = outcome ?: return@LaunchedEffect
        val text =
            when (current) {
                is FileTasks.Outcome.Success -> current.message
                is FileTasks.Outcome.Failure -> current.message
            }
        fileTasks.clearOutcome()
        snackbar.showSnackbar(text)
    }

    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) picked = fileTasks.describe(uri)
        }
    val showMessage: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    val activeDownloads = records.values.count { it.isActive }
                    IconButton(onClick = onShowDownloads) {
                        if (activeDownloads > 0) {
                            BadgedBox(badge = { Badge { Text("$activeDownloads") } }) {
                                Icon(Icons.Outlined.Downloading, contentDescription = "Downloads")
                            }
                        } else {
                            Icon(Icons.Outlined.Downloading, contentDescription = "Downloads")
                        }
                    }
                    IconButton(onClick = { showBlankDialog = true }) {
                        Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = "Create blank image")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openDocument.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                text = { Text("Add file") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    try {
                        backend.refreshImages()
                    } finally {
                        refreshing = false
                    }
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!storageGranted) item { StorageHint() }
                items(tasks.entries.toList(), key = { "task:" + it.key }) { (key, task) ->
                    TaskCard(task, onCancel = { fileTasks.cancel(key) })
                }
                if (images.isEmpty() && tasks.isEmpty()) {
                    item { LibraryEmpty(onBlank = { showBlankDialog = true }) }
                } else {
                    item(key = "new-flash-drive") { NewFlashDriveButton(onClick = { showBlankDialog = true }) }
                }
                items(images, key = { it.path }) { image ->
                    val expectedSha256 = image.verifiedSha256 ?: records[image.displayName]?.expectedSha256
                    ImageCard(
                        image = image,
                        verifying = tasks.containsKey(fusePathOf(image.path)),
                        canVerify = expectedSha256 != null,
                        onExpose = {
                            if (drives.expose(image.path)) onExposed()
                        },
                        onVerify = {
                            expectedSha256?.let { fileTasks.verify(fusePathOf(image.path), image.sizeBytes, it) }
                        },
                        onDelete = { pendingDelete = image },
                    )
                }
            }
        }
    }

    picked?.let { file ->
        PickedFileDialog(
            file = file,
            onUseInPlace = { path ->
                if (drives.expose(path)) onExposed() else showMessage("All drive slots are in use")
            },
            onImport = { fileTasks.import(file) },
            onDismiss = { picked = null },
        )
    }
    if (showBlankDialog) {
        BlankImageDialog(
            freeBytes = ::storeFreeBytesOrNull,
            existingNames = images.map { it.displayName }.toSet(),
            onCreate = { request ->
                scope.launch {
                    val result = backend.createBlankImage(request.name, request.sizeBytes, request.format, request.preallocate)
                    showMessage(result.fold({ "Created ${it.displayName}" }, { "Couldn't create image: ${it.message}" }))
                }
            },
            onDismiss = { showBlankDialog = false },
        )
    }
    pendingDelete?.let { image ->
        ConfirmDialog(
            title = "Delete ${image.displayName}?",
            body = "The file is removed from this phone. This can't be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                scope.launch {
                    val result = backend.deleteImage(image.path)
                    showMessage(result.fold({ "Deleted ${image.displayName}" }, { "Couldn't delete: ${it.message}" }))
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** The app reads images through FUSE; the backend reports kernel (/data/media/0) paths. */
private fun fusePathOf(path: String): String = if (path.startsWith(StoragePaths.MEDIA_ROOT + "/")) StoragePaths.toFusePath(path) else path

@Composable
private fun StorageHint() {
    val context = LocalContext.current
    NoticeCard(
        icon = Icons.Outlined.FolderOpen,
        title = "Storage access not granted yet",
        body =
            "Bootleg normally grants itself “All files access” through root. Until then it can't read " +
                "/sdcard/${StoragePaths.STORE_DIR_NAME} or save downloads there.",
        tone = Tone.WARNING,
        action = {
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Open settings") }
        },
    )
}

@Composable
private fun LibraryEmpty(onBlank: () -> Unit) {
    Column {
        EmptyState(
            icon = Icons.Outlined.Inventory2,
            title = "No images yet",
            body = "Download one from the Catalog, add a file from this phone, or create a blank disk image.",
            modifier = Modifier.padding(top = 48.dp),
            action = { NewFlashDriveButton(onClick = onBlank) },
        )
    }
}
