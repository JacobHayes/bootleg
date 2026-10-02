package dev.jacobhayes.bootleg.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.download.DownloadNotifications
import dev.jacobhayes.bootleg.download.DownloadRecord
import dev.jacobhayes.bootleg.download.DownloadStatus
import dev.jacobhayes.bootleg.download.Downloads
import dev.jacobhayes.bootleg.download.VerifyState
import dev.jacobhayes.bootleg.ui.common.ConfirmDialog
import dev.jacobhayes.bootleg.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: Downloads,
    wifiOnly: Boolean,
    onBack: (() -> Unit)? = null,
) {
    val records by downloads.records.collectAsState()
    var pendingCancel by remember { mutableStateOf<DownloadRecord?>(null) }
    val sorted = records.values.sortedWith(compareByDescending<DownloadRecord> { it.isActive }.thenByDescending { it.createdAtMs })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Library") }
                    }
                },
            )
        },
    ) { padding ->
        if (sorted.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Downloading,
                title = "No downloads",
                body =
                    "Images you download from the Catalog show up here while they transfer and verify." +
                        if (wifiOnly) " Downloads wait for Wi-Fi." else "",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sorted, key = { it.fileName }) { record ->
                DownloadCard(
                    record = record,
                    onPause = { downloads.pause(record.fileName) },
                    onResume = { downloads.resume(record.fileName) },
                    onCancel = { pendingCancel = record },
                    onDismiss = { downloads.dismiss(record.fileName) },
                )
            }
        }
    }
    pendingCancel?.let { record ->
        ConfirmDialog(
            title = "Cancel download?",
            body = "The partly downloaded ${record.fileName} is deleted.",
            confirmLabel = "Cancel download",
            destructive = true,
            onConfirm = { downloads.cancel(record.fileName) },
            onDismiss = { pendingCancel = null },
        )
    }
}

@Composable
private fun DownloadCard(
    record: DownloadRecord,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        record.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DownloadActions(record, onPause, onResume, onCancel, onDismiss)
            }
            if (record.isActive || record.status == DownloadStatus.PAUSED) {
                Spacer(Modifier.padding(4.dp))
                DownloadProgress(record, Modifier.padding(end = 12.dp))
            }
            Spacer(Modifier.padding(4.dp))
            StatusLine(record)
        }
    }
}

@Composable
private fun DownloadActions(
    record: DownloadRecord,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row {
        when (record.status) {
            DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.VERIFYING -> {
                IconButton(onClick = onPause) { Icon(Icons.Outlined.Pause, contentDescription = "Pause") }
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
            }

            DownloadStatus.PAUSED -> {
                IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, contentDescription = "Resume") }
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
            }

            DownloadStatus.FAILED -> {
                IconButton(onClick = onResume) { Icon(Icons.Outlined.Replay, contentDescription = "Retry") }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
            }

            DownloadStatus.COMPLETED -> {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Remove from list") }
            }
        }
    }
}

@Composable
private fun DownloadProgress(
    record: DownloadRecord,
    modifier: Modifier = Modifier,
) {
    val fraction = record.progressFraction
    when {
        record.status == DownloadStatus.VERIFYING || record.status == DownloadStatus.QUEUED ->
            LinearProgressIndicator(modifier.fillMaxWidth())

        fraction != null -> LinearProgressIndicator(progress = { fraction }, modifier = modifier.fillMaxWidth())

        else -> LinearProgressIndicator(modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusLine(record: DownloadRecord) {
    val colors = MaterialTheme.colorScheme
    val (icon, text, color) =
        when (record.status) {
            DownloadStatus.QUEUED -> Triple(null, record.error ?: "Waiting to start", colors.onSurfaceVariant)
            DownloadStatus.RUNNING -> Triple(null, DownloadNotifications.progressText(record), colors.onSurfaceVariant)
            DownloadStatus.PAUSED -> Triple(null, "Paused · ${DownloadNotifications.progressText(record)}", colors.onSurfaceVariant)
            DownloadStatus.VERIFYING -> Triple(null, "Verifying SHA-256…", colors.onSurfaceVariant)
            DownloadStatus.FAILED ->
                Triple(
                    Icons.Outlined.ErrorOutline,
                    if (record.verify == VerifyState.MISMATCH) "Checksum mismatch — the file was deleted" else record.error ?: "Failed",
                    colors.error,
                )
            DownloadStatus.COMPLETED ->
                when (record.verify) {
                    VerifyState.VERIFIED -> Triple(Icons.Outlined.Verified, "Downloaded · SHA-256 verified", colors.primary)
                    else -> Triple(Icons.Outlined.GppMaybe, "Downloaded · no published checksum to verify", colors.tertiary)
                }
        }
    StatusText(icon, text, color)
}

@Composable
private fun StatusText(
    icon: ImageVector?,
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
