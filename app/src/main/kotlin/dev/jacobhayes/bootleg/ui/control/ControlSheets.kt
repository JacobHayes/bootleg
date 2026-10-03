package dev.jacobhayes.bootleg.ui.control

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.core.hid.BootKey
import dev.jacobhayes.bootleg.core.hid.BootKeyPresets
import dev.jacobhayes.bootleg.core.hid.BootVendor
import dev.jacobhayes.bootleg.core.hid.HidLimits
import dev.jacobhayes.bootleg.core.hid.TextTyper
import dev.jacobhayes.bootleg.hid.BootKeyActions
import dev.jacobhayes.bootleg.hid.HidController
import dev.jacobhayes.bootleg.hid.HidJobProgress

/** Progress of the running paste or boot-key action, with Cancel. */
@Composable
fun JobProgress(
    job: HidJobProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                job.error?.let { "${job.label}: stopped, $it" } ?: job.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (job.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(if (job.error != null) "Dismiss" else "Cancel") }
        }
        LinearProgressIndicator(progress = { job.fraction }, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BootKeySheet(
    hid: HidController,
    job: HidJobProgress?,
    onDismiss: () -> Unit,
) {
    var vendorName by rememberSaveable { mutableStateOf(BootVendor.GENERIC.name) }
    var durationMs by rememberSaveable { mutableLongStateOf(BootKeyActions.DEFAULT_DURATION_MS) }
    val preset = BootKeyPresets.forVendor(BootVendor.valueOf(vendorName))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Boot keys", style = MaterialTheme.typography.titleLarge)
            Text(
                "Start an action, then power on or restart the computer. Bootleg keeps pressing while it starts up.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootKeyPresets.all.forEach { candidate ->
                    FilterChip(
                        selected = candidate.vendor == preset.vendor,
                        onClick = { vendorName = candidate.vendor.name },
                        label = { Text(candidate.displayName) },
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                BootKeyActions.durationsMs.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == durationMs,
                        onClick = { durationMs = option },
                        shape = SegmentedButtonDefaults.itemShape(index, BootKeyActions.durationsMs.size),
                    ) { Text("${option / 1000} s") }
                }
            }
            BootKeyGroup("Boot menu", preset.bootMenuKeys, durationMs, hid)
            BootKeyGroup("Firmware setup", preset.setupKeys, durationMs, hid)
            preset.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (job != null) JobProgress(job, onCancel = hid::cancelJob)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BootKeyGroup(
    title: String,
    keys: List<BootKey>,
    durationMs: Long,
    hid: HidController,
) {
    if (keys.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEachIndexed { index, key ->
            when (key) {
                is BootKey.Unsupported ->
                    Text(
                        BootKeyActions.label(key),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else -> {
                    val label = BootKeyActions.label(key)
                    // The most likely key gets the prominent button.
                    if (index == 0) {
                        Button(onClick = { hid.runBootKey(key, durationMs) }) { Text(label) }
                    } else {
                        FilledTonalButton(onClick = { hid.runBootKey(key, durationMs) }) { Text(label) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteSheet(
    hid: HidController,
    job: HidJobProgress?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Not saveable: up to 64 KiB would overflow the saved-state bundle.
    var text by remember { mutableStateOf("") }
    val sizeBytes = remember(text) { text.toByteArray(Charsets.UTF_8).size }
    val tooLong = sizeBytes > HidLimits.PASTE_SIZE_BYTES_MAX
    val plan = remember(text, tooLong) { if (tooLong) null else TextTyper.plan(text) }
    val unsupported =
        remember(plan) {
            plan
                ?.unsupported
                ?.map { String(Character.toChars(it.codePoint)) }
                ?.distinct()
                .orEmpty()
        }
    val keyCount = (plan?.reports?.size ?: 0) / 2
    val seconds = keyCount * 2 * HidController.TYPING_REPORT_INTERVAL_MS / 1000

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Paste as typing", style = MaterialTheme.typography.titleLarge)
            Text(
                "Types the text on the computer one key at a time. The computer must use a US keyboard layout.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                placeholder = { Text("Text to type") },
                isError = tooLong || unsupported.isNotEmpty(),
                supportingText = {
                    Text(
                        when {
                            tooLong -> "Too long: limit is ${HidLimits.PASTE_SIZE_BYTES_MAX / 1024} KiB"
                            unsupported.isNotEmpty() -> "Will be skipped: ${unsupported.take(UNSUPPORTED_LISTED_MAX).joinToString(" ")}"
                            keyCount > 0 -> "$keyCount keys, about ${maxOf(seconds, 1)} s"
                            else -> ""
                        },
                    )
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val clip = clipboard?.primaryClip
                    if (clip != null && clip.itemCount > 0) text = clip.getItemAt(0).coerceToText(context).toString()
                }) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Paste from clipboard")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { hid.typeText(text) }, enabled = !tooLong && keyCount > 0 && job == null) { Text("Type it") }
            }
            if (job != null) JobProgress(job, onCancel = hid::cancelJob)
            Spacer(Modifier.height(16.dp))
        }
    }
}

private const val UNSUPPORTED_LISTED_MAX = 20
