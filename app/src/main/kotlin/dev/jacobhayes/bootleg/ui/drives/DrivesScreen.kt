package dev.jacobhayes.bootleg.ui.drives

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.ImageFile
import dev.jacobhayes.bootleg.RootStatus
import dev.jacobhayes.bootleg.core.gadget.GadgetLimits
import dev.jacobhayes.bootleg.core.tether.TetherPolicy
import dev.jacobhayes.bootleg.ui.common.ConfirmDialog
import dev.jacobhayes.bootleg.ui.common.EmptyState
import dev.jacobhayes.bootleg.ui.common.NoticeCard
import dev.jacobhayes.bootleg.ui.common.Tone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrivesScreen(
    backend: BootlegBackend,
    controller: DrivesController,
    onBrowseFile: (onPicked: (String) -> Unit) -> Unit,
) {
    val state by controller.state.collectAsState()
    val root by backend.rootStatus.collectAsState()
    val journal by backend.journal.collectAsState()
    val images by backend.images.collectAsState()
    val gadget by backend.gadgetState.collectAsState()
    val tethering by backend.usbTethering.collectAsState()
    val tetheringBusy by backend.usbTetheringBusy.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var picker by remember { mutableStateOf<SlotPickerTarget?>(null) }
    var confirmEjectAll by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            controller.clearMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Drives") }, actions = { RootChip(root) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            DrivesActionBar(
                state = state,
                rootGranted = root is RootStatus.Granted,
                anyExported = journal.desired.slots.isNotEmpty(),
                onAdd = { picker = SlotPickerTarget.NewSlot },
                onEjectAll = { confirmEjectAll = true },
                onApply = controller::apply,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            RootBanner(root)
            TetherCard(
                tethering = tethering,
                busy = tetheringBusy,
                disksExposed = gadget?.let(TetherPolicy::exposedLuns).orEmpty().isNotEmpty(),
                rootGranted = root is RootStatus.Granted,
                onToggle = controller::setUsbTethering,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            if (state.slots.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Usb,
                    title = "No drives yet",
                    body = "Add a slot to expose an ISO or disk image to the computer this phone is plugged into.",
                    action = { Button(onClick = { picker = SlotPickerTarget.NewSlot }) { Text("Add slot") } },
                )
            } else {
                SlotList(state, journal, controller, onChangeImage = { picker = SlotPickerTarget.Replace(it) })
            }
        }
    }

    picker?.let { target ->
        ImagePickerSheet(
            images = images,
            onPick = { path -> onImagePicked(controller, target, path) },
            onBrowse = { onBrowseFile { path -> onImagePicked(controller, target, path) } },
            onDismiss = { picker = null },
        )
    }
    state.forceEjectOffer?.let { lun ->
        ForceEjectDialog(
            lun = lun,
            hostRefused = true,
            onConfirm = { controller.eject(lun, force = true) },
            onDismiss = controller::dismissForceEjectOffer,
        )
    }
    if (confirmEjectAll) {
        ConfirmDialog(
            title = "Eject all drives?",
            body =
                "Every exported image is removed from the host. Eject them on the computer first: macOS keeps " +
                    "an attached disk in use and refuses until then, and writable disks can lose data.",
            confirmLabel = "Eject all",
            onConfirm = controller::ejectAll,
            onDismiss = { confirmEjectAll = false },
        )
    }
}

private sealed interface SlotPickerTarget {
    data object NewSlot : SlotPickerTarget

    data class Replace(
        val index: Int,
    ) : SlotPickerTarget
}

private fun onImagePicked(
    controller: DrivesController,
    target: SlotPickerTarget,
    path: String,
) {
    when (target) {
        SlotPickerTarget.NewSlot -> controller.addSlot(path)
        is SlotPickerTarget.Replace -> controller.replaceImage(target.index, path)
    }
}

@Composable
private fun SlotList(
    state: DrivesUiState,
    journal: dev.jacobhayes.bootleg.core.gadget.Journal,
    controller: DrivesController,
    onChangeImage: (Int) -> Unit,
) {
    val applied = DrivesController.slotsOf(journal)
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.dirty) {
            item {
                NoticeCard(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Changes not applied",
                    body = "Tap Apply to send these drives to the host.",
                    tone = Tone.INFO,
                    action = { TextButton(onClick = controller::discardDraft) { Text("Discard") } },
                )
            }
        }
        val statuses = state.slots.mapIndexed { index, slot -> slotStatus(index, slot, applied, journal) }
        itemsIndexed(state.slots) { index, slot ->
            SlotCard(
                index = index,
                slot = slot,
                status = statuses[index],
                busy = state.busy,
                onModeChange = { controller.setMode(index, it) },
                onEject = { controller.eject(index, force = false) },
                onForceEject = { controller.eject(index, force = true) },
                onReinsert = { controller.reinsert(index) },
                onChangeImage = { onChangeImage(index) },
                onRemove = { controller.removeSlot(index) },
            )
        }
        if (statuses.any { it.kind == SlotStatusKind.EXPORTED }) {
            item {
                Text(
                    EJECT_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** macOS holds an attached disk (prevent-removal) until it ejects it itself, so the phone's Eject is refused before that. */
private const val EJECT_HINT =
    "Eject drives on the computer first. macOS keeps an attached disk in use until you eject it there, " +
        "so Eject here is refused until then (Force eject still works)."

/**
 * Android's USB tethering next to the drives: it shares the USB connection with them, and switching
 * it re-plugs USB, so it can't change while a disk is exposed.
 */
@Composable
private fun TetherCard(
    tethering: Boolean?,
    busy: Boolean,
    disksExposed: Boolean,
    rootGranted: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status =
        when {
            busy -> "Reconnecting USB…"
            tethering == null -> "Checking…"
            disksExposed -> "Eject every disk to turn it ${if (tethering) "off" else "on"}: switching re-plugs USB"
            tethering -> "On · the computer shares this phone's internet"
            else -> "Off · switching re-plugs USB (keyboard and mouse drop for a second)"
        }
    OutlinedCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lan, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("USB tethering", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = tethering == true,
                onCheckedChange = onToggle,
                enabled = rootGranted && !busy && tethering != null && !disksExposed,
                modifier = Modifier.testTag("usb-tethering-switch"),
            )
        }
    }
}

@Composable
private fun RootChip(root: RootStatus) {
    val (icon, label) =
        when (root) {
            RootStatus.Granted -> Icons.Outlined.Check to "Root"
            RootStatus.Unknown -> Icons.Outlined.Lock to "Checking…"
            is RootStatus.Unavailable -> Icons.Outlined.ErrorOutline to "No root"
        }
    val color = if (root is RootStatus.Unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun RootBanner(root: RootStatus) {
    if (root !is RootStatus.Unavailable) return
    NoticeCard(
        icon = Icons.Outlined.ErrorOutline,
        title = "Root access is required",
        body = "Bootleg needs root (Magisk) to expose drives over USB. Grant it in Magisk, then reopen the app.\n${root.reason}",
        tone = Tone.ERROR,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun DrivesActionBar(
    state: DrivesUiState,
    rootGranted: Boolean,
    anyExported: Boolean,
    onAdd: () -> Unit,
    onEjectAll: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onAdd, enabled = state.slots.size < GadgetLimits.LUN_COUNT_MAX && !state.busy) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add slot")
            }
            TextButton(onClick = onEjectAll, enabled = anyExported && rootGranted && !state.busy) {
                Icon(Icons.Outlined.Eject, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Eject all")
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onApply, enabled = state.dirty && rootGranted && !state.busy) {
                Text(if (state.busy) "Working…" else "Apply")
            }
        }
    }
}

/** Library images plus "browse the phone"; used for new slots and image swaps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagePickerSheet(
    images: List<ImageFile>,
    onPick: (String) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    ImagePickerSheetContent(images, onPick = {
        onPick(it)
        onDismiss()
    }, onBrowse = {
        onBrowse()
        onDismiss()
    }, onDismiss = onDismiss)
}
