package dev.jacobhayes.bootleg.ui.control

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.hid.KeyboardLeds
import dev.jacobhayes.bootleg.core.hid.ModifierChipState
import dev.jacobhayes.bootleg.core.hid.StickyState
import dev.jacobhayes.bootleg.core.hid.TypingFieldState
import dev.jacobhayes.bootleg.hid.HidController
import dev.jacobhayes.bootleg.core.hid.Modifier as KeyModifier

/** Material's minimum touch target; every key, chip and selector is at least this tall. */
internal val KEY_HEIGHT = 48.dp
internal val KEY_GAP = 6.dp

internal data class OnScreenKey(
    val label: String,
    val usage: KeyUsage,
    val description: String = label,
)

internal val escapeKey = OnScreenKey("Esc", KeyUsage.ESCAPE, "Escape")
internal val tabKey = OnScreenKey("Tab", KeyUsage.TAB)
internal val enterKey = OnScreenKey("Enter", KeyUsage.ENTER)
internal val backspaceKey = OnScreenKey("⌫", KeyUsage.BACKSPACE, "Backspace")
internal val deleteKey = OnScreenKey("Del", KeyUsage.DELETE, "Delete")
internal val leftKey = OnScreenKey("←", KeyUsage.ARROW_LEFT, "Left arrow")
internal val upKey = OnScreenKey("↑", KeyUsage.ARROW_UP, "Up arrow")
internal val downKey = OnScreenKey("↓", KeyUsage.ARROW_DOWN, "Down arrow")
internal val rightKey = OnScreenKey("→", KeyUsage.ARROW_RIGHT, "Right arrow")

internal val editingKeys =
    listOf(
        tabKey,
        backspaceKey,
        OnScreenKey("Home", KeyUsage.HOME),
        OnScreenKey("End", KeyUsage.END),
        OnScreenKey("PgUp", KeyUsage.PAGE_UP, "Page up"),
        OnScreenKey("PgDn", KeyUsage.PAGE_DOWN, "Page down"),
    )

private val functionKeys = (1..12).map { OnScreenKey("F$it", KeyUsage.valueOf("F$it")) }
private val extraFunctionKeys = (13..24).map { OnScreenKey("F$it", KeyUsage.valueOf("F$it")) }

private data class Shortcut(
    val label: String,
    val modifiers: Set<KeyModifier>,
    val key: KeyUsage,
)

private val shortcuts =
    listOf(
        Shortcut("Ctrl+Alt+Del", setOf(KeyModifier.LEFT_CONTROL, KeyModifier.LEFT_ALT), KeyUsage.DELETE),
        Shortcut("Cmd+Space", setOf(KeyModifier.LEFT_GUI), KeyUsage.SPACE),
        Shortcut("Alt+Tab", setOf(KeyModifier.LEFT_ALT), KeyUsage.TAB),
    )

private val chipModifiers =
    listOf(
        KeyModifier.LEFT_CONTROL to "Ctrl",
        KeyModifier.LEFT_ALT to "Alt/Opt",
        KeyModifier.LEFT_GUI to "Cmd/Win",
        KeyModifier.LEFT_SHIFT to "Shift",
    )

@Composable
internal fun KeyButton(
    key: OnScreenKey,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(KEY_HEIGHT).defaultMinSize(minWidth = KEY_HEIGHT).semantics { contentDescription = key.description },
    ) {
        Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(key.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Keys of equal width across the row. */
@Composable
internal fun KeyRow(
    keys: List<OnScreenKey>,
    hid: HidController,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        keys.forEach { key -> KeyButton(key, Modifier.weight(1f)) { hid.tapKey(key.usage) } }
    }
}

/**
 * Esc and Del, the arrows as an inverted T, and a tall Enter: the keys boot menus and firmware
 * setup need, kept low on the screen and never scrolled away.
 */
@Composable
internal fun NavigationCluster(
    hid: HidController,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            KeyButton(escapeKey, Modifier.fillMaxWidth()) { hid.tapKey(escapeKey.usage) }
            KeyButton(deleteKey, Modifier.fillMaxWidth()) { hid.tapKey(deleteKey.usage) }
        }
        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                Box(Modifier.weight(1f))
                KeyButton(upKey, Modifier.weight(1f)) { hid.tapKey(upKey.usage) }
                Box(Modifier.weight(1f))
            }
            KeyRow(listOf(leftKey, downKey, rightKey), hid)
        }
        KeyButton(enterKey, Modifier.weight(1f).height(KEY_HEIGHT * 2 + KEY_GAP)) { hid.tapKey(enterKey.usage) }
    }
}

/** F1–F12 or F13–F24, every key of the bank on screen at once. */
@Composable
internal fun FunctionKeyPad(
    hid: HidController,
    upperBank: Boolean,
    onUpperBankChange: (Boolean) -> Unit,
    keysPerRow: Int,
) {
    require(12 % keysPerRow == 0) { "a bank of 12 keys doesn't split into rows of $keysPerRow" }
    Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !upperBank,
                onClick = { onUpperBankChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.height(KEY_HEIGHT).semantics { contentDescription = "Function keys F1 to F12" },
            ) { Text("F1–F12") }
            SegmentedButton(
                selected = upperBank,
                onClick = { onUpperBankChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.height(KEY_HEIGHT).semantics { contentDescription = "More function keys" },
            ) { Text("F13–F24") }
        }
        val keys = if (upperBank) extraFunctionKeys else functionKeys
        keys.chunked(keysPerRow).forEach { row -> KeyRow(row, hid) }
    }
}

/**
 * Key combinations (Ctrl+Alt+Del can reboot the computer), folded away by default and sent only
 * on touch and hold, so a stray tap or a scroll that starts on one sends nothing.
 */
@Composable
internal fun Shortcuts(
    hid: HidController,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var hint by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.height(KEY_HEIGHT).semantics { stateDescription = if (expanded) "expanded" else "collapsed" },
        ) {
            Text(if (expanded) "Shortcuts · touch and hold to send" else "Shortcuts")
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
        }
        if (!expanded) return@Column
        if (hint) {
            Text(
                "Touch and hold a shortcut to send it.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            shortcuts.forEach { shortcut ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(KEY_HEIGHT)
                            .semantics {
                                role = Role.Button
                                contentDescription = shortcut.label
                                onLongClick(label = "Send ${shortcut.label}") {
                                    hid.chord(shortcut.modifiers, shortcut.key)
                                    true
                                }
                            }.pointerInput(shortcut) {
                                detectTapGestures(
                                    onLongPress = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        hint = false
                                        hid.chord(shortcut.modifiers, shortcut.key)
                                    },
                                    onTap = { hint = true },
                                )
                            },
                ) {
                    Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        Text(shortcut.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Ctrl, Alt/Opt, Cmd/Win and Shift: shared by every pane, so a latched or locked modifier survives switching. */
@Composable
internal fun ModifierRow(
    hid: HidController,
    chips: Map<KeyModifier, ModifierChipState>,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        for ((key, label) in chipModifiers) {
            ModifierChip(label, chips[key] ?: ModifierChipState(StickyState.OFF, held = false), hid, key, Modifier.weight(1f))
        }
    }
}

/** Tap = one-shot (next key only), tap again = locked, touch and hold = held while pressed. */
@Composable
private fun ModifierChip(
    label: String,
    state: ModifierChipState,
    hid: HidController,
    key: KeyModifier,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val hold = remember(key, hid) { ModifierHold { hid.releaseModifier(key) } }
    DisposableEffect(hold) { onDispose { hold.release() } }
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        when {
            state.held -> colors.tertiary to colors.onTertiary
            state.sticky == StickyState.LOCKED -> colors.primary to colors.onPrimary
            state.sticky == StickyState.LATCHED -> colors.secondaryContainer to colors.onSecondaryContainer
            else -> colors.surfaceContainerHighest to colors.onSurface
        }
    val stateText =
        when {
            state.held -> "held"
            state.sticky == StickyState.LOCKED -> "locked"
            state.sticky == StickyState.LATCHED -> "next key only"
            else -> "off"
        }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
        contentColor = content,
        modifier =
            modifier
                .height(KEY_HEIGHT)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    stateDescription = stateText
                    // The pointer handler below has no semantics of its own; TalkBack activates through this.
                    onClick(label = "Change") {
                        hid.tapModifier(key)
                        true
                    }
                }.pointerInput(key) {
                    detectTapGestures(
                        onLongPress = {
                            hold.holding = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            hid.holdModifier(key)
                        },
                        onPress = {
                            // finally: a cancelled gesture (leaving the tab, recomposition) must not leave the key held.
                            try {
                                tryAwaitRelease()
                            } finally {
                                hold.release()
                            }
                        },
                        onTap = { hid.tapModifier(key) },
                    )
                },
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (state.sticky == StickyState.LOCKED) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(14.dp).padding(end = 2.dp))
            }
            Text(
                if (state.sticky == StickyState.LATCHED && !state.held) "$label·" else label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A modifier held by a long press; [release] sends the release once, however the hold ends. */
private class ModifierHold(
    private val releaseModifier: () -> Unit,
) {
    var holding = false

    fun release() {
        if (!holding) return
        holding = false
        releaseModifier()
    }
}

@Composable
internal fun LedIndicators(leds: KeyboardLeds?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LedIndicator("Caps", leds?.capsLock == true)
        LedIndicator("Num", leds?.numLock == true)
    }
}

@Composable
private fun LedIndicator(
    label: String,
    on: Boolean,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.semantics { stateDescription = if (on) "on" else "off" },
    )
}

/**
 * Captures IME input and types each edit on the computer. The IME's selection and composing region
 * are kept (see `TypingField`). Password-type input keeps the IME from learning or suggesting
 * (people type passwords here) while the text stays visible.
 */
@Composable
internal fun TypingField(
    hid: HidController,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onUnsupported: (List<String>) -> Unit,
    clearable: Boolean,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            val typed = hid.typeFieldUpdate(value.toFieldState(), next.toFieldState())
            if (typed.unsupported.isNotEmpty()) onUnsupported(typed.unsupported)
            onValueChange(typed.field.toTextFieldValue())
        },
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
        singleLine = true,
        placeholder = { Text("Type here to send keys") },
        trailingIcon = {
            if (clearable) {
                // Clears this field only; nothing is deleted on the computer.
                IconButton(
                    onClick = onClear,
                ) { Icon(Icons.Outlined.Clear, contentDescription = "Clear field (doesn't delete on the computer)") }
            }
        },
        visualTransformation = VisualTransformation.None,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Send,
            ),
        keyboardActions = KeyboardActions(onSend = { hid.tapKey(KeyUsage.ENTER) }),
    )
}

private fun TextFieldValue.toFieldState(): TypingFieldState =
    TypingFieldState(
        text = text,
        selectionStart = selection.start,
        selectionEnd = selection.end,
        compositionStart = composition?.start,
        compositionEnd = composition?.end,
    )

private fun TypingFieldState.toTextFieldValue(): TextFieldValue {
    val composition = compositionStart?.let { start -> TextRange(start, checkNotNull(compositionEnd)) }
    return TextFieldValue(text, TextRange(selectionStart, selectionEnd), composition)
}
