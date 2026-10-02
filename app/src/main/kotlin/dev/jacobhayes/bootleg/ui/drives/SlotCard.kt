package dev.jacobhayes.bootleg.ui.drives

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.download.formatBytes
import dev.jacobhayes.bootleg.storage.StoragePaths
import dev.jacobhayes.bootleg.ui.common.ConfirmDialog

enum class SlotStatusKind { EXPORTED, EJECTED, FAILED, PENDING }

data class SlotStatus(
    val kind: SlotStatusKind,
    val label: String,
)

fun slotStatus(
    index: Int,
    slot: DraftSlot,
    applied: List<DraftSlot>,
    journal: Journal,
): SlotStatus {
    if (applied.getOrNull(index) != slot) return SlotStatus(SlotStatusKind.PENDING, "Not applied")
    return when (journal.slotIntents[index]) {
        SlotIntent.EXPORTED -> SlotStatus(SlotStatusKind.EXPORTED, "Exported")
        SlotIntent.HOST_EJECTED -> SlotStatus(SlotStatusKind.EJECTED, "Host ejected")
        SlotIntent.USER_EJECTED -> SlotStatus(SlotStatusKind.EJECTED, "Ejected")
        SlotIntent.FAILED -> SlotStatus(SlotStatusKind.FAILED, "Failed: ${journal.slotFailures[index] ?: "unknown reason"}")
        null -> SlotStatus(SlotStatusKind.PENDING, "Not applied")
    }
}

fun modeLabel(mode: LunMode): String =
    when (mode) {
        LunMode.CDROM -> "CD-ROM"
        LunMode.DISK_RO -> "Disk (read-only)"
        LunMode.DISK_RW -> "Disk (read/write)"
    }

@Composable
fun SlotCard(
    index: Int,
    slot: DraftSlot,
    status: SlotStatus,
    busy: Boolean,
    onModeChange: (LunMode?) -> Unit,
    onEject: () -> Unit,
    onForceEject: () -> Unit,
    onReinsert: () -> Unit,
    onChangeImage: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmForce by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (slot.mode == LunMode.CDROM) Icons.Outlined.Album else Icons.Outlined.SdStorage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Slot ${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(slot.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // In the header, not the action row: there a chip next to the mode and button is squeezed to "…".
                    StatusText(status)
                }
                SlotOverflowMenu(
                    canEject = status.kind == SlotStatusKind.EXPORTED && !busy,
                    onChangeImage = onChangeImage,
                    onForceEject = { confirmForce = true },
                    onRemove = onRemove,
                )
            }
            Spacer(Modifier.padding(4.dp))
            Row(
                Modifier.padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeChip(slot, onModeChange)
                Spacer(Modifier.weight(1f))
                if (status.kind == SlotStatusKind.EXPORTED) {
                    FilledTonalButton(onClick = onEject, enabled = !busy) {
                        Icon(Icons.Outlined.Eject, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Eject")
                    }
                }
                if (status.kind == SlotStatusKind.EJECTED || status.kind == SlotStatusKind.FAILED) {
                    FilledTonalButton(onClick = onReinsert, enabled = !busy) {
                        Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Re-insert")
                    }
                }
            }
        }
    }
    if (confirmForce) ForceEjectDialog(index, onConfirm = onForceEject, onDismiss = { confirmForce = false })
}

@Composable
fun ForceEjectDialog(
    lun: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    hostRefused: Boolean = false,
) {
    val lead = if (hostRefused) "The computer is still using slot ${lun + 1}, so it refused the eject. " else ""
    ConfirmDialog(
        title = "Force eject slot ${lun + 1}?",
        body =
            lead + "Eject the disk on the computer first if you can. Force ejecting can corrupt data the " +
                "computer hasn't written yet, and macOS may show a stale volume until the drive reconnects.",
        confirmLabel = "Force eject",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SlotOverflowMenu(
    canEject: Boolean,
    onChangeImage: () -> Unit,
    onForceEject: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Change image") }, onClick = {
                open = false
                onChangeImage()
            })
            DropdownMenuItem(text = { Text("Force eject") }, enabled = canEject, onClick = {
                open = false
                onForceEject()
            })
            DropdownMenuItem(text = { Text("Remove slot") }, onClick = {
                open = false
                onRemove()
            })
        }
    }
}

@Composable
private fun ModeChip(
    slot: DraftSlot,
    onModeChange: (LunMode?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirmWritable by remember { mutableStateOf(false) }
    val label = if (slot.modeOverride == null) "Auto · ${modeLabel(slot.mode)}" else modeLabel(slot.mode)
    Box {
        AssistChip(onClick = { open = true }, label = { Text(label, maxLines = 1) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Auto (${modeLabel(StoragePaths.autoMode(slot.path))})") }, onClick = {
                open = false
                onModeChange(null)
            })
            LunMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(modeLabel(mode)) }, onClick = {
                    open = false
                    if (mode == LunMode.DISK_RW) confirmWritable = true else onModeChange(mode)
                })
            }
        }
    }
    if (confirmWritable) {
        ConfirmDialog(
            title = "Make this disk writable?",
            body =
                "The computer will be able to change ${slot.displayName}. A writable disk can only be used in one " +
                    "slot, and the image can be damaged if you eject it before the computer is done writing.",
            confirmLabel = "Make writable",
            onConfirm = { onModeChange(LunMode.DISK_RW) },
            onDismiss = { confirmWritable = false },
        )
    }
}

@Composable
private fun StatusText(status: SlotStatus) {
    val colors = MaterialTheme.colorScheme
    val color =
        when (status.kind) {
            SlotStatusKind.EXPORTED -> colors.primary
            SlotStatusKind.EJECTED -> colors.onSurfaceVariant
            SlotStatusKind.FAILED -> colors.error
            SlotStatusKind.PENDING -> colors.tertiary
        }
    Text(status.label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePickerSheetContent(
    images: List<ImageFile>,
    onPick: (String) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Choose an image",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn {
            items(images, key = { it.path }) { image ->
                ListItem(
                    headlineContent = { Text(image.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${StoragePaths.kindLabel(image.path)} · ${formatBytes(image.sizeBytes)}") },
                    leadingContent = {
                        Icon(
                            if (StoragePaths.autoMode(image.path) == LunMode.CDROM) Icons.Outlined.Album else Icons.Outlined.SdStorage,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { onPick(image.path) },
                )
            }
            item {
                if (images.isNotEmpty()) HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Other file on this phone…") },
                    supportingContent = { Text("Use any file on internal storage in place") },
                    leadingContent = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onBrowse).padding(bottom = 24.dp),
                )
            }
        }
    }
}
