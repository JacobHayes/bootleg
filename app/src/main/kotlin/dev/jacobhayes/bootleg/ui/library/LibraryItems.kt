package dev.jacobhayes.bootleg.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.ImageFile
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.LunModes
import dev.jacobhayes.bootleg.download.formatBytes
import dev.jacobhayes.bootleg.storage.FileTasks
import dev.jacobhayes.bootleg.storage.PickedFile
import dev.jacobhayes.bootleg.storage.StoragePaths

@Composable
fun ImageCard(
    image: ImageFile,
    verifying: Boolean,
    canVerify: Boolean,
    onExpose: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    // Name and details get the full width; the action sits on its own row so long file names don't wrap mid-word.
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (LunModes.auto(image.path, image.sizeBytes) == LunMode.CDROM) Icons.Outlined.Album else Icons.Outlined.SdStorage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(image.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        sizeLine(image),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ImageOverflowMenu(verifying, canVerify, onVerify, onDelete)
            }
            Row(Modifier.padding(start = 40.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { if (image.verifiedSha256 != null) VerifiedBadge() }
                FilledTonalButton(onClick = onExpose) {
                    Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Expose")
                }
            }
        }
    }
}

private fun sizeLine(image: ImageFile): String {
    val base = "${StoragePaths.kindLabel(image.path)} · ${formatBytes(image.sizeBytes)}"
    // Sparse images: show what they actually occupy so the user knows how much they can still grow.
    val sparse = image.allocatedBytes < image.sizeBytes * SPARSE_THRESHOLD
    return if (sparse) "$base · ${formatBytes(image.allocatedBytes)} used" else base
}

private const val SPARSE_THRESHOLD = 0.95

@Composable
private fun VerifiedBadge() {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Verified, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Text("SHA-256 verified", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ImageOverflowMenu(
    verifying: Boolean,
    canVerify: Boolean,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (canVerify) "Verify checksum" else "No checksum known") },
                enabled = canVerify && !verifying,
                onClick = {
                    open = false
                    onVerify()
                },
            )
            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                open = false
                onDelete()
            })
        }
    }
}

@Composable
fun TaskCard(
    task: FileTasks.Progress,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") }
            }
            val total = task.totalBytes
            Box(Modifier.padding(end = 12.dp)) {
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (task.doneBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            Text(
                if (total != null) "${formatBytes(task.doneBytes)} of ${formatBytes(total)}" else formatBytes(task.doneBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** After ACTION_OPEN_DOCUMENT: use a real internal-storage path in place, or import a copy. */
@Composable
fun PickedFileDialog(
    file: PickedFile,
    onUseInPlace: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sizeText = file.sizeBytes?.let { " (${formatBytes(it)})" }.orEmpty()
    val inPlace = file.mediaPath
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.displayName) },
        text = {
            Text(
                if (inPlace != null) {
                    "This file is on internal storage, so Bootleg can expose it where it is. " +
                        "You can also import a copy$sizeText into the library."
                } else {
                    "This file comes from a provider Bootleg can't read directly. " +
                        "Import a copy$sizeText into the library to use it."
                },
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (inPlace != null) {
                    TextButton(onClick = {
                        onImport()
                        onDismiss()
                    }) { Text("Import copy") }
                    TextButton(onClick = {
                        onUseInPlace(inPlace)
                        onDismiss()
                    }) { Text("Expose in place") }
                } else {
                    TextButton(onClick = {
                        onImport()
                        onDismiss()
                    }) { Text("Import") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
