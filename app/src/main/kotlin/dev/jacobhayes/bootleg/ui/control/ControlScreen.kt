package dev.jacobhayes.bootleg.ui.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.RootStatus
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.MouseLayout
import dev.jacobhayes.bootleg.core.hid.MouseButton
import dev.jacobhayes.bootleg.hid.HidController
import dev.jacobhayes.bootleg.hid.HidLinkState
import dev.jacobhayes.bootleg.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(
    backend: BootlegBackend,
    hid: HidController,
) {
    val spec by hid.spec.collectAsState()
    val busy by hid.busy.collectAsState()
    val link by hid.link.collectAsState()
    val chips by hid.chips.collectAsState()
    val leds by hid.leds.collectAsState()
    val job by hid.job.collectAsState()
    val message by hid.message.collectAsState()
    val gadget by backend.gadgetState.collectAsState()
    val root by backend.rootStatus.collectAsState()
    val enabled = spec.keyboard || spec.mouse
    var sheet by rememberSaveable { mutableStateOf(ControlSheet.NONE) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            hid.dismissMessage()
        }
    }
    // Open the devices while the screen is up so the status line is live.
    LaunchedEffect(enabled, busy) { if (enabled && !busy) hid.connect() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Control") },
                actions = {
                    if (enabled) {
                        IconButton(onClick = { sheet = ControlSheet.BOOT_KEYS }) {
                            Icon(Icons.Outlined.PowerSettingsNew, contentDescription = "Boot keys")
                        }
                        IconButton(onClick = { sheet = ControlSheet.PASTE }) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste as typing")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EnableRow(
                enabled = enabled,
                busy = busy,
                status = statusText(enabled, busy, root, gadget, link),
                onToggle = hid::setEnabled,
                canToggle = root !is RootStatus.Unavailable,
            )
            if (!enabled) {
                EmptyState(
                    icon = Icons.Outlined.Keyboard,
                    title = "Use this phone as a keyboard and mouse",
                    body =
                        "Adds a USB keyboard and mouse next to your drives. They're standard boot devices, so they also work " +
                            "in BIOS menus and boot pickers. Turning this on briefly reconnects USB.",
                )
                return@Column
            }
            job?.let { JobProgress(it, onCancel = hid::cancelJob) }
            Touchpad(
                onActions = hid::touchpad,
                scrolling = spec.mouseLayout == MouseLayout.WHEEL,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { hid.click(MouseButton.LEFT) }, modifier = Modifier.weight(1f)) { Text("Left click") }
                OutlinedButton(onClick = { hid.click(MouseButton.RIGHT) }, modifier = Modifier.weight(1f)) { Text("Right click") }
            }
            KeyboardPanel(hid, chips, leds, Modifier.padding(bottom = 8.dp))
        }
    }

    when (sheet) {
        ControlSheet.BOOT_KEYS -> BootKeySheet(hid, job, onDismiss = { sheet = ControlSheet.NONE })
        ControlSheet.PASTE -> PasteSheet(hid, job, onDismiss = { sheet = ControlSheet.NONE })
        ControlSheet.NONE -> Unit
    }
}

private enum class ControlSheet { NONE, BOOT_KEYS, PASTE }

@Composable
private fun EnableRow(
    enabled: Boolean,
    busy: Boolean,
    status: String,
    onToggle: (Boolean) -> Unit,
    canToggle: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Keyboard & mouse", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onToggle, enabled = canToggle && !busy)
    }
}

private fun statusText(
    enabled: Boolean,
    busy: Boolean,
    root: RootStatus,
    gadget: GadgetState?,
    link: HidLinkState,
): String {
    if (root is RootStatus.Unavailable) return "Needs root: ${root.reason}"
    if (busy) return "Reconnecting USB…"
    if (!enabled) return "Off"
    val exposed =
        gadget != null &&
            gadget.udc.isNotEmpty() &&
            gadget.configLinks.values.any { it == GadgetNames.HID_KEYBOARD_FUNCTION || MouseLayout.ofFunction(it) != null }
    if (!exposed) return "Not exposed on USB yet"
    return when (link) {
        HidLinkState.Stopped, HidLinkState.Opening -> "Exposed on USB · connecting…"
        is HidLinkState.Open ->
            if (link.hostListening) "Connected · the computer is reading input" else "The computer isn't reading input (asleep or off?)"
        is HidLinkState.Reconnecting -> "Reconnecting (attempt ${link.attempt}): ${link.reason}"
        is HidLinkState.Failed ->
            if (link.restartRequired) "Not connected: ${link.reason}." else "Not connected: ${link.reason}. Touch the pad to retry."
    }
}
