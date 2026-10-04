package dev.jacobhayes.bootleg.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LockPerson
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.RootStatus
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.KeyboardLeds
import dev.jacobhayes.bootleg.hid.HidController
import dev.jacobhayes.bootleg.hid.HidLinkState
import dev.jacobhayes.bootleg.ui.common.EmptyState

/**
 * Pointer, Keys and Type panes under a shared header (switch, status, Boot keys, Paste) above a
 * shared modifier row and pane selector. The root owns the IME inset (see BootlegRoot), so this
 * screen only applies and consumes its own Scaffold padding.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(
    backend: BootlegBackend,
    hid: HidController,
    initialPane: ControlPane = ControlPane.KEYS,
    focusTypingOnStart: Boolean = false,
    onPaneSelected: (ControlPane) -> Unit = {},
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
    var pane by rememberSaveable { mutableStateOf(initialPane) }
    var upperBank by rememberSaveable { mutableStateOf(false) }
    var shortcutsExpanded by rememberSaveable { mutableStateOf(false) }
    // Not saved: coming back to a restored Type pane never opens the IME by itself.
    var typeOpenRequests by remember { mutableIntStateOf(if (focusTypingOnStart) 1 else 0) }
    val typing = remember { TypingUi() }
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp > configuration.screenHeightDp

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            hid.dismissMessage()
        }
    }
    // Open the devices while the screen is up so the status line is live.
    LaunchedEffect(enabled, busy) { if (enabled && !busy) hid.connect() }
    // Back hides the IME but leaves the field focused; drop focus so tapping the field opens it again.
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) focusManager.clearFocus()
        imeWasVisible = imeVisible
    }

    fun hideKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    fun selectPane(next: ControlPane) {
        if (next == ControlPane.TYPE) typeOpenRequests++ else hideKeyboard()
        pane = next
        onPaneSelected(next)
    }

    Scaffold(
        topBar = {
            // A landscape IME leaves too little height for the header too.
            if (!(wide && imeVisible)) {
                TopAppBar(
                    title = { Text("Control") },
                    actions = {
                        if (enabled) {
                            TextButton(onClick = { sheet = ControlSheet.BOOT_KEYS }) { Text("Boot keys") }
                            TextButton(onClick = { sheet = ControlSheet.PASTE }) { Text("Paste") }
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = hid::setEnabled,
                            enabled = root !is RootStatus.Unavailable && !busy,
                            modifier = Modifier.padding(start = 4.dp, end = 12.dp).semantics { contentDescription = "Keyboard & mouse" },
                        )
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!(wide && imeVisible)) StatusLine(statusOf(enabled, busy, root, gadget, link), leds, showLeds = enabled)
            if (!enabled) {
                OffState(root)
                return@Column
            }
            job?.let { JobProgress(it, onCancel = hid::cancelJob) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val paneModifier = Modifier.fillMaxSize()
                when (pane) {
                    ControlPane.POINTER -> PointerPane(hid, wide, paneModifier)
                    ControlPane.KEYS ->
                        KeysPane(
                            hid,
                            wide,
                            upperBank,
                            onUpperBankChange = { upperBank = it },
                            shortcutsExpanded,
                            onShortcutsExpandedChange = { shortcutsExpanded = it },
                            paneModifier,
                        )
                    ControlPane.TYPE -> {
                        TypePane(hid, typing, imeVisible, onHideKeyboard = ::hideKeyboard, paneModifier)
                        LaunchedEffect(typeOpenRequests) {
                            if (typeOpenRequests > 0) {
                                typing.focusRequester.requestFocus()
                                keyboard?.show()
                            }
                        }
                    }
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ModifierRow(hid, chips, Modifier.weight(1f))
                    PaneSelector(pane, ::selectPane, Modifier.weight(1f))
                }
            } else {
                ModifierRow(hid, chips)
                PaneSelector(pane, ::selectPane)
            }
        }
    }

    when (sheet) {
        ControlSheet.BOOT_KEYS -> BootKeySheet(hid, job, onDismiss = { sheet = ControlSheet.NONE })
        ControlSheet.PASTE -> PasteSheet(hid, job, onDismiss = { sheet = ControlSheet.NONE })
        ControlSheet.NONE -> Unit
    }
}

private enum class ControlSheet { NONE, BOOT_KEYS, PASTE }

/** The typing field's local history; kept while switching panes so returning to Type shows it. */
@Stable
internal class TypingUi {
    var value by mutableStateOf(TextFieldValue(""))
    var unsupported by mutableStateOf<List<String>>(emptyList())
    val focusRequester = FocusRequester()

    fun clear() {
        value = TextFieldValue("")
        unsupported = emptyList()
    }
}

internal enum class StatusTone { NEUTRAL, GOOD, PROBLEM }

internal data class ControlStatus(
    val text: String,
    val tone: StatusTone,
)

@Composable
private fun StatusLine(
    status: ControlStatus,
    leds: KeyboardLeds?,
    showLeds: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val dot =
        when (status.tone) {
            StatusTone.GOOD -> colors.primary
            StatusTone.PROBLEM -> colors.error
            StatusTone.NEUTRAL -> colors.outline
        }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            status.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (status.tone == StatusTone.PROBLEM) colors.error else colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (showLeds) {
            Spacer(Modifier.width(8.dp))
            LedIndicators(leds)
        }
    }
}

@Composable
private fun OffState(root: RootStatus) {
    if (root is RootStatus.Unavailable) {
        EmptyState(
            icon = Icons.Outlined.LockPerson,
            title = "Root is needed",
            body = "Bootleg adds the keyboard and mouse to the phone's USB connection, which needs root (Magisk). ${root.reason}",
        )
    } else {
        EmptyState(
            icon = Icons.Outlined.Keyboard,
            title = "Keyboard and mouse are off",
            body =
                "Turn on the switch to use this phone as a keyboard, mouse and touchpad for the computer it's plugged " +
                    "into. They also work in BIOS menus and boot pickers.",
        )
    }
}

internal fun statusOf(
    enabled: Boolean,
    busy: Boolean,
    root: RootStatus,
    gadget: GadgetState?,
    link: HidLinkState,
): ControlStatus {
    if (root is RootStatus.Unavailable) return ControlStatus("Needs root: ${root.reason}", StatusTone.PROBLEM)
    if (busy) return ControlStatus("Reconnecting USB…", StatusTone.NEUTRAL)
    if (!enabled) return ControlStatus("USB keyboard and mouse, including BIOS.", StatusTone.NEUTRAL)
    val linked =
        if (gadget == null || gadget.udc.isEmpty()) {
            emptySet()
        } else {
            buildSet {
                if (GadgetNames.HID_KEYBOARD_FUNCTION in gadget.configLinks.values) add(HidKind.KEYBOARD)
                if (GadgetNames.HID_MOUSE_FUNCTION in gadget.configLinks.values) add(HidKind.MOUSE)
            }
        }
    if (linked.isEmpty()) return ControlStatus("Not on USB yet", StatusTone.NEUTRAL)
    return when (link) {
        HidLinkState.Stopped, HidLinkState.Opening -> ControlStatus("On USB · connecting…", StatusTone.NEUTRAL)
        is HidLinkState.Open -> openStatus(reading = linked - link.notReading, notReading = link.notReading)
        is HidLinkState.Reconnecting -> ControlStatus("Reconnecting (attempt ${link.attempt}): ${link.reason}", StatusTone.PROBLEM)
        is HidLinkState.Failed ->
            if (link.restartRequired) {
                ControlStatus("Not connected: ${link.reason}.", StatusTone.PROBLEM)
            } else {
                ControlStatus("Not connected: ${link.reason}. Any key or the pad retries.", StatusTone.PROBLEM)
            }
    }
}

/** Firmware and boot menus often poll only the keyboard, so one endpoint can be read while the other isn't. */
private fun openStatus(
    reading: Set<HidKind>,
    notReading: Set<HidKind>,
): ControlStatus =
    when {
        notReading.isEmpty() -> ControlStatus("Connected · the computer is reading input", StatusTone.GOOD)
        reading.isEmpty() -> ControlStatus("Computer not reading input (asleep, off or unplugged?)", StatusTone.PROBLEM)
        else -> {
            val connected = reading.joinToString(" and ") { it.wireName }.replaceFirstChar { it.uppercase() }
            val ignored = notReading.joinToString(" and ") { it.wireName }
            ControlStatus("$connected connected · the computer isn't reading the $ignored (BIOS or boot menu?)", StatusTone.PROBLEM)
        }
    }
