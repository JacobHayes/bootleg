package dev.jacobhayes.bootleg.ui.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.core.hid.MouseButton
import dev.jacobhayes.bootleg.hid.HidController

/** The Control screen's panes, switched with buttons (a swipe on the touchpad is mouse input). */
enum class ControlPane(
    val label: String,
    val icon: ImageVector,
) {
    POINTER("Pointer", Icons.Outlined.Mouse),
    KEYS("Keys", Icons.Outlined.Keyboard),
    TYPE("Type", Icons.Outlined.EditNote),
}

/** Wider than tall: panes lay out side by side. */
internal val PANE_SIDE_WIDTH_MAX = 340.dp

@Composable
internal fun PaneSelector(
    selected: ControlPane,
    onSelect: (ControlPane) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        ControlPane.entries.forEachIndexed { index, pane ->
            SegmentedButton(
                selected = pane == selected,
                onClick = { onSelect(pane) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ControlPane.entries.size),
                icon = { Icon(pane.icon, contentDescription = null, modifier = Modifier.height(18.dp)) },
                modifier = Modifier.height(KEY_HEIGHT),
            ) { Text(pane.label) }
        }
    }
}

@Composable
internal fun PointerPane(
    hid: HidController,
    wide: Boolean,
    /** The wheel mouse is linked: the touchpad's hint offers two-finger scrolling. */
    scrolling: Boolean,
    modifier: Modifier = Modifier,
) {
    val buttons: @Composable (Modifier) -> Unit = { buttonModifier ->
        Row(buttonModifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { hid.click(MouseButton.LEFT) },
                modifier = Modifier.weight(1f).height(KEY_HEIGHT),
            ) { Text("Left click") }
            OutlinedButton(
                onClick = { hid.click(MouseButton.RIGHT) },
                modifier = Modifier.weight(1f).height(KEY_HEIGHT),
            ) { Text("Right click") }
        }
    }
    if (wide) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Touchpad(onActions = hid::touchpad, scrolling = scrolling, modifier = Modifier.weight(1f).fillMaxHeight())
            Column(
                Modifier.widthIn(max = PANE_SIDE_WIDTH_MAX).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(KEY_GAP, Alignment.Bottom),
            ) {
                buttons(Modifier)
                KeyRow(listOf(escapeKey, leftKey, upKey, downKey, rightKey, enterKey), hid)
            }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Touchpad(onActions = hid::touchpad, scrolling = scrolling, modifier = Modifier.weight(1f).fillMaxWidth())
            buttons(Modifier)
            KeyRow(listOf(escapeKey, leftKey, upKey, downKey, rightKey, enterKey), hid)
        }
    }
}

@Composable
internal fun KeysPane(
    hid: HidController,
    wide: Boolean,
    upperBank: Boolean,
    onUpperBankChange: (Boolean) -> Unit,
    shortcutsExpanded: Boolean,
    onShortcutsExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The inventory scrolls under large fonts or a short window; the navigation cluster never does.
    val inventory: @Composable (Modifier) -> Unit = { inventoryModifier ->
        // Bottom-aligned: spare height goes above the keys, which stay near the thumb.
        Column(
            inventoryModifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(KEY_GAP, Alignment.Bottom),
        ) {
            FunctionKeyPad(hid, upperBank, onUpperBankChange, keysPerRow = if (wide) 6 else 4)
            KeyRow(editingKeys, hid)
            Shortcuts(hid, shortcutsExpanded, onShortcutsExpandedChange)
        }
    }
    if (wide) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            inventory(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.widthIn(max = PANE_SIDE_WIDTH_MAX).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                NavigationCluster(hid)
            }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            inventory(Modifier.weight(1f).fillMaxWidth())
            NavigationCluster(hid)
        }
    }
}

/** Distinct unsupported characters listed under the field; older ones drop off. */
internal const val UNSUPPORTED_SHOWN_MAX = 12

/**
 * The typing field sits at the bottom, directly above the shared modifier row and the IME; the
 * explanation and the unsupported-character list scroll above it.
 */
@Composable
internal fun TypePane(
    hid: HidController,
    typing: TypingUi,
    imeVisible: Boolean,
    onHideKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            Text(
                "Each key is sent to the computer as you type, for a US keyboard layout. The field below is only a local " +
                    "history: clearing it deletes nothing on the computer. Send on your keyboard presses Enter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (typing.unsupported.isNotEmpty()) {
                Text(
                    "Not sent (US layout only): ${typing.unsupported.joinToString(" ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TypingField(
            hid = hid,
            value = typing.value,
            onValueChange = { typing.value = it },
            onUnsupported = { typing.unsupported = (typing.unsupported + it).distinct().takeLast(UNSUPPORTED_SHOWN_MAX) },
            clearable = typing.value.text.isNotEmpty() || typing.unsupported.isNotEmpty(),
            onClear = typing::clear,
            focusRequester = typing.focusRequester,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            listOf(escapeKey, tabKey, backspaceKey, enterKey).forEach { key ->
                KeyButton(key, Modifier.weight(1f)) { hid.tapKey(key.usage) }
            }
            if (imeVisible) {
                TextButton(
                    onClick = onHideKeyboard,
                    modifier = Modifier.height(KEY_HEIGHT).semantics { contentDescription = "Hide keyboard" },
                ) { Icon(Icons.Outlined.KeyboardHide, contentDescription = null) }
            }
        }
    }
}
