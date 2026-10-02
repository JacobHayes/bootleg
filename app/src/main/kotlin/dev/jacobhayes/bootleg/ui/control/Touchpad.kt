package dev.jacobhayes.bootleg.ui.control

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.core.hid.TouchpadAction
import dev.jacobhayes.bootleg.core.hid.TouchpadGestures
import dev.jacobhayes.bootleg.core.hid.TouchpadTuning
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Relative touchpad: drag moves, tap clicks, two-finger tap right-clicks, touch-and-hold drags. */
@Composable
fun Touchpad(
    onActions: (List<TouchpadAction>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tuning = remember { TouchpadTuning() }
    val gestures = remember { TouchpadGestures(tuning) }
    val currentOnActions by rememberUpdatedState(onActions)
    var dragging by remember { mutableStateOf(false) }

    fun emit(actions: List<TouchpadAction>) {
        if (actions.isNotEmpty()) currentOnActions(actions)
        dragging = gestures.isDragging
    }

    // Never leave the button held if the pad leaves the screen mid-drag.
    DisposableEffect(gestures) { onDispose { emit(gestures.cancel()) } }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            modifier
                .semantics { contentDescription = "Touchpad" }
                .pointerInput(gestures) {
                    coroutineScope {
                        var holdTimer: Job? = null
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    val id = change.id.value
                                    val xDp = change.position.x / density
                                    val yDp = change.position.y / density
                                    val actions =
                                        when {
                                            change.changedToDown() -> gestures.down(id, xDp, yDp, change.uptimeMillis)
                                            change.changedToUp() -> gestures.up(id, change.uptimeMillis)
                                            change.positionChanged() -> gestures.move(id, xDp, yDp, change.uptimeMillis)
                                            else -> emptyList()
                                        }
                                    change.consume()
                                    emit(actions)
                                }
                                val pressedCount = event.changes.count { it.pressed }
                                if (event.changes.any { it.changedToDown() } && pressedCount == 1) {
                                    holdTimer?.cancel()
                                    holdTimer =
                                        this@coroutineScope.launch {
                                            delay(tuning.holdToDragMs)
                                            emit(gestures.tick(SystemClock.uptimeMillis()))
                                        }
                                } else if (pressedCount != 1) {
                                    holdTimer?.cancel()
                                }
                            }
                        }
                    }
                },
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (dragging) "Holding the left button. Lift to drop." else "Drag to move · Tap to click",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Two-finger tap to right-click · Touch and hold to drag\nNo scrolling yet: use PgUp/PgDn or the arrows",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
