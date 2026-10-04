package dev.jacobhayes.bootleg.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.BlankImageFormat
import dev.jacobhayes.bootleg.download.formatBytes
import dev.jacobhayes.bootleg.storage.StoragePaths

data class BlankImageRequest(
    val name: String,
    val sizeBytes: Long,
    val format: BlankImageFormat,
    val preallocate: Boolean,
)

/** Pure validation for the blank image form: blocking errors and non-blocking warnings. */
object BlankImageRules {
    const val GB = 1_000_000_000L
    val presetSizesGb = listOf(1L, 4L, 8L, 16L, 32L, 64L)
    const val FAT32_SIZE_BYTES_MIN = 33L * 1024 * 1024
    const val MBR_SIZE_BYTES_MAX = 2L * 1024 * 1024 * 1024 * 1024
    const val SIZE_BYTES_MIN = 1024L * 1024

    data class Verdict(
        val error: String?,
        val warning: String?,
    )

    /** Adds `.img` when the user typed no extension. */
    fun normalizedName(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.contains('.')) trimmed else "$trimmed.img"
    }

    fun check(
        request: BlankImageRequest,
        existingNames: Set<String>,
        freeBytes: Long?,
    ): Verdict {
        val error =
            when {
                !StoragePaths.isValidImageName(request.name) -> "Enter a file name (no slashes)"
                request.name in existingNames -> "${request.name} already exists"
                request.sizeBytes < SIZE_BYTES_MIN -> "Size must be at least 1 MB"
                request.format != BlankImageFormat.RAW && request.sizeBytes > MBR_SIZE_BYTES_MAX ->
                    "Formatted images are limited to 2 TiB (MBR)"
                request.format == BlankImageFormat.FAT32 && request.sizeBytes < FAT32_SIZE_BYTES_MIN -> "FAT32 needs at least 33 MiB"
                request.preallocate && freeBytes != null && freeBytes < request.sizeBytes ->
                    "Not enough space to preallocate: ${formatBytes(freeBytes)} free"
                else -> null
            }
        val warning =
            if (error == null && !request.preallocate && freeBytes != null && freeBytes < request.sizeBytes) {
                "Only ${formatBytes(freeBytes)} free. The image starts small but can fill the phone as the computer writes to it."
            } else {
                null
            }
        return Verdict(error, warning)
    }
}

/** Opens [BlankImageDialog]: a blank image is the phone's "flash drive" for the computer. */
@Composable
fun NewFlashDriveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("New flash drive")
    }
}

/** Free space in the image store, or null if it can't be read. */
fun storeFreeBytesOrNull(): Long? = runCatching { android.os.StatFs(StoragePaths.FUSE_ROOT).availableBytes }.getOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlankImageDialog(
    freeBytes: () -> Long?,
    existingNames: Set<String>,
    onCreate: (BlankImageRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("blank.img") }
    var presetGb by rememberSaveable { mutableStateOf<Long?>(8L) }
    var customGb by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf(BlankImageFormat.FAT32) }
    var preallocate by rememberSaveable { mutableStateOf(false) }
    val free = remember { freeBytes() }

    val sizeBytes = presetGb?.let { it * BlankImageRules.GB } ?: customGb.toDoubleOrNull()?.let { (it * BlankImageRules.GB).toLong() } ?: 0L
    val request = BlankImageRequest(BlankImageRules.normalizedName(name), sizeBytes, format, preallocate)
    val verdict = BlankImageRules.check(request, existingNames, free)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create blank image") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Size", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlankImageRules.presetSizesGb.forEach { gb ->
                        FilterChip(selected = presetGb == gb, onClick = { presetGb = gb }, label = { Text("$gb GB") })
                    }
                    FilterChip(selected = presetGb == null, onClick = { presetGb = null }, label = { Text("Custom") })
                }
                if (presetGb == null) {
                    OutlinedTextField(
                        value = customGb,
                        onValueChange = { customGb = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Size in GB") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Format", style = MaterialTheme.typography.labelLarge)
                FormatSelector(format) { format = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Preallocate", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Reserve the full size now instead of growing as data is written",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = preallocate, onCheckedChange = { preallocate = it })
                }
                free?.let {
                    Text(
                        "${formatBytes(it)} free on this phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                (verdict.error ?: verdict.warning)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (verdict.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = verdict.error == null, onClick = {
                onCreate(request)
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FormatSelector(
    selected: BlankImageFormat,
    onSelect: (BlankImageFormat) -> Unit,
) {
    val formats = BlankImageFormat.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        formats.forEachIndexed { index, format ->
            SegmentedButton(
                selected = selected == format,
                onClick = { onSelect(format) },
                shape = SegmentedButtonDefaults.itemShape(index, formats.size),
                icon = {},
                label = { Text(formatLabel(format), maxLines = 1) },
            )
        }
    }
}

fun formatLabel(format: BlankImageFormat): String =
    when (format) {
        BlankImageFormat.RAW -> "Raw"
        BlankImageFormat.FAT32 -> "FAT32"
        BlankImageFormat.EXFAT -> "exFAT"
        BlankImageFormat.EXT4 -> "ext4"
    }
