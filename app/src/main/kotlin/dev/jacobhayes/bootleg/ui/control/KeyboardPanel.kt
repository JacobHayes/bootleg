package dev.jacobhayes.bootleg.ui.control

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.hid.KeyboardLeds
import dev.jacobhayes.bootleg.core.hid.ModifierChipState
import dev.jacobhayes.bootleg.core.hid.StickyState
import dev.jacobhayes.bootleg.hid.HidController
import dev.jacobhayes.bootleg.core.hid.Modifier as KeyModifier

private data class OnScreenKey(
    val label: String,
    val usage: KeyUsage,
    val description: String = label,
)

private val navigationKeys =
    listOf(
        OnScreenKey("Esc", KeyUsage.ESCAPE, "Escape"),
        OnScreenKey("Tab", KeyUsage.TAB),
        OnScreenKey("Enter", KeyUsage.ENTER),
        OnScreenKey("⌫", KeyUsage.BACKSPACE, "Backspace"),
        OnScreenKey("Del", KeyUsage.DELETE, "Delete"),
        OnScreenKey("←", KeyUsage.ARROW_LEFT, "Left arrow"),
        OnScreenKey("↑", KeyUsage.ARROW_UP, "Up arrow"),
        OnScreenKey("↓", KeyUsage.ARROW_DOWN, "Down arrow"),
        OnScreenKey("→", KeyUsage.ARROW_RIGHT, "Right arrow"),
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

/** Modifier chips, special keys, shortcuts and the typing field. */
@Composable
fun KeyboardPanel(
    hid: HidController,
    chips: Map<KeyModifier, ModifierChipState>,
    leds: KeyboardLeds?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((key, label) in chipModifiers) {
                ModifierChip(label, chips[key] ?: ModifierChipState(StickyState.OFF, held = false), hid, key)
            }
            LedIndicator("Caps", leds?.capsLock == true)
            LedIndicator("Num", leds?.numLock == true)
        }
        KeyRow {
            navigationKeys.forEach { KeyButton(it.label, it.description) { hid.tapKey(it.usage) } }
        }
        KeyRow {
            shortcuts.forEach { KeyButton(it.label, it.label, emphasized = true) { hid.chord(it.modifiers, it.key) } }
            functionKeys.forEach { KeyButton(it.label, it.description) { hid.tapKey(it.usage) } }
            MoreFunctionKeys(hid)
        }
        TypingField(hid)
    }
}

@Composable
private fun KeyRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun KeyButton(
    label: String,
    description: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (emphasized) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.height(44.dp).defaultMinSize(minWidth = 48.dp).semantics { contentDescription = description },
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun MoreFunctionKeys(hid: HidController) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        KeyButton("F13–F24", "More function keys") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            extraFunctionKeys.forEach { key ->
                DropdownMenuItem(text = { Text(key.label) }, onClick = { hid.tapKey(key.usage) })
            }
        }
    }
}

/** Tap = one-shot (next key only), tap again = locked, touch and hold = held while pressed. */
@Composable
private fun ModifierChip(
    label: String,
    state: ModifierChipState,
    hid: HidController,
    modifier: KeyModifier,
) {
    val haptics = LocalHapticFeedback.current
    val hold = remember(modifier, hid) { ModifierHold { hid.releaseModifier(modifier) } }
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
            Modifier
                .height(40.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    stateDescription = stateText
                }.pointerInput(modifier) {
                    detectTapGestures(
                        onLongPress = {
                            hold.holding = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            hid.holdModifier(modifier)
                        },
                        onPress = {
                            // finally: a cancelled gesture (leaving the tab, recomposition) must not leave the key held.
                            try {
                                tryAwaitRelease()
                            } finally {
                                hold.release()
                            }
                        },
                        onTap = { hid.tapModifier(modifier) },
                    )
                },
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.sticky == StickyState.LOCKED) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(14.dp).padding(end = 2.dp))
            }
            Text(if (state.sticky == StickyState.LATCHED && !state.held) "$label·" else label, style = MaterialTheme.typography.labelLarge)
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

/** Characters kept in the field; older ones scroll away (they're already on the computer). */
private const val TYPING_FIELD_CHARS_MAX = 120

/**
 * Captures IME input and types each edit on the computer. Password-type input keeps the IME from
 * learning or suggesting (people type passwords here) while the text stays visible.
 */
@Composable
private fun TypingField(hid: HidController) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var unsupported by remember { mutableStateOf<List<Char>>(emptyList()) }
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.text != value.text) {
                val skipped = hid.typeEdit(value.text, next.text)
                if (skipped.isNotEmpty()) unsupported = (unsupported + skipped).distinct().takeLast(UNSUPPORTED_SHOWN_MAX)
            }
            val text = next.text.takeLast(TYPING_FIELD_CHARS_MAX)
            value = TextFieldValue(text, TextRange(text.length))
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Type here to send keys") },
        supportingText =
            if (unsupported.isEmpty()) {
                null
            } else {
                { Text("Not sent (US layout only): ${unsupported.joinToString(" ")}", color = MaterialTheme.colorScheme.error) }
            },
        trailingIcon = {
            if (value.text.isNotEmpty() || unsupported.isNotEmpty()) {
                IconButton(onClick = {
                    // Clears this field only; nothing is deleted on the computer.
                    value = TextFieldValue("")
                    unsupported = emptyList()
                }) { Icon(Icons.Outlined.Clear, contentDescription = "Clear field (doesn't delete on the computer)") }
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

private const val UNSUPPORTED_SHOWN_MAX = 12
