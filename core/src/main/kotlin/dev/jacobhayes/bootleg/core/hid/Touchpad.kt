package dev.jacobhayes.bootleg.core.hid

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Touchpad feel. Distances are in dp so the pad behaves the same at any screen density. */
data class TouchpadTuning(
    /** Movement below this still counts as a tap. */
    val tapSlopDp: Float = 8f,
    /** A touch lifted within this time (and within the slop) is a click. */
    val tapTimeoutMs: Long = 250,
    /** Touch and hold this long without moving to press the left button for a drag. */
    val holdToDragMs: Long = 450,
    /** Mouse counts per dp at slow speeds. */
    val countsPerDp: Float = 1.0f,
    /** Gain at and above [fastSpeedDpPerMs]; it ramps linearly from 1 at rest. */
    val accelerationGainMax: Float = 3.0f,
    val fastSpeedDpPerMs: Float = 1.5f,
    /** Two-finger travel per scroll detent (one wheel unit; hosts scroll about 3 lines per detent). */
    val scrollDpPerDetent: Float = 20f,
) {
    init {
        // Finite and capped: an infinite or huge value would overflow mouse counts or make taps/drags impossible.
        require(tapSlopDp.isFinite() && tapSlopDp > 0f && tapSlopDp <= TAP_SLOP_DP_MAX) { "bad tap slop: $this" }
        require(
            tapTimeoutMs in 1..TAP_TIMEOUT_MS_MAX && holdToDragMs in (tapTimeoutMs + 1)..HOLD_TO_DRAG_MS_MAX,
        ) { "bad tap/hold timing: $this" }
        require(countsPerDp.isFinite() && countsPerDp > 0f && countsPerDp <= COUNTS_PER_DP_MAX) { "bad counts per dp: $this" }
        require(accelerationGainMax.isFinite() && accelerationGainMax >= 1f && accelerationGainMax <= ACCELERATION_GAIN_MAX) {
            "bad acceleration gain: $this"
        }
        require(
            fastSpeedDpPerMs.isFinite() && fastSpeedDpPerMs > 0f && fastSpeedDpPerMs <= FAST_SPEED_DP_PER_MS_MAX,
        ) { "bad fast speed: $this" }
        require(
            scrollDpPerDetent.isFinite() && scrollDpPerDetent >= SCROLL_DP_PER_DETENT_MIN && scrollDpPerDetent <= SCROLL_DP_PER_DETENT_MAX,
        ) { "bad scroll distance: $this" }
    }

    companion object {
        const val TAP_SLOP_DP_MAX = 100f
        const val TAP_TIMEOUT_MS_MAX = 2_000L
        const val HOLD_TO_DRAG_MS_MAX = 5_000L
        const val COUNTS_PER_DP_MAX = 20f
        const val ACCELERATION_GAIN_MAX = 20f
        const val FAST_SPEED_DP_PER_MS_MAX = 100f
        const val SCROLL_DP_PER_DETENT_MIN = 1f
        const val SCROLL_DP_PER_DETENT_MAX = 500f
    }
}

sealed interface TouchpadAction {
    data class Move(
        val deltaX: Int,
        val deltaY: Int,
    ) : TouchpadAction

    data class Click(
        val button: MouseButton,
    ) : TouchpadAction

    data class Press(
        val button: MouseButton,
    ) : TouchpadAction

    data class Release(
        val button: MouseButton,
    ) : TouchpadAction

    /** Whole detents: [wheel] > 0 scrolls up, [pan] > 0 scrolls right. Never both zero. */
    data class Scroll(
        val wheel: Int,
        val pan: Int,
    ) : TouchpadAction {
        init {
            require(wheel != 0 || pan != 0) { "empty scroll" }
        }
    }
}

/**
 * Two-finger travel to scroll detents, carrying the fractional remainder between events. Like a
 * traditional wheel: fingers moving down scroll down (negative wheel), right scrolls right; the host's
 * natural-scrolling setting applies on top.
 */
class ScrollAccumulator(
    private val dpPerDetent: Float,
) {
    private var remainderWheel = 0f
    private var remainderPan = 0f

    init {
        require(dpPerDetent.isFinite() && dpPerDetent > 0f) { "bad dp per detent: $dpPerDetent" }
    }

    /** Whole detents for a finger movement; the excess beyond one report's range is dropped, not carried. */
    fun apply(
        deltaXDp: Float,
        deltaYDp: Float,
    ): TouchpadAction.Scroll? {
        require(deltaXDp.isFinite() && deltaYDp.isFinite()) { "non-finite delta" }
        val exactWheel = -deltaYDp / dpPerDetent + remainderWheel
        val exactPan = deltaXDp / dpPerDetent + remainderPan
        val wheel = exactWheel.toInt()
        val pan = exactPan.toInt()
        remainderWheel = exactWheel - wheel
        remainderPan = exactPan - pan
        check(abs(remainderWheel) < 1f && abs(remainderPan) < 1f)
        val limit = HidLimits.MOUSE_SCROLL_MAX
        return if (wheel == 0 && pan == 0) null else TouchpadAction.Scroll(wheel.coerceIn(-limit, limit), pan.coerceIn(-limit, limit))
    }

    fun reset() {
        remainderWheel = 0f
        remainderPan = 0f
    }
}

/** Relative-motion acceleration with sub-count remainders carried between events. */
class PointerAcceleration(
    private val tuning: TouchpadTuning,
) {
    private var remainderX = 0f
    private var remainderY = 0f

    fun gainFor(speedDpPerMs: Float): Float {
        val ramp = min(max(speedDpPerMs, 0f) / tuning.fastSpeedDpPerMs, 1f)
        return 1f + (tuning.accelerationGainMax - 1f) * ramp
    }

    /** Converts a finger movement over [elapsedMs] into whole mouse counts. */
    fun apply(
        deltaXDp: Float,
        deltaYDp: Float,
        elapsedMs: Long,
    ): Pair<Int, Int> {
        require(deltaXDp.isFinite() && deltaYDp.isFinite()) { "non-finite delta" }
        val speed = hypot(deltaXDp, deltaYDp) / max(elapsedMs, 1L)
        val scale = tuning.countsPerDp * gainFor(speed)
        val exactX = deltaXDp * scale + remainderX
        val exactY = deltaYDp * scale + remainderY
        val countsX = exactX.toInt()
        val countsY = exactY.toInt()
        remainderX = exactX - countsX
        remainderY = exactY - countsY
        return countsX to countsY
    }

    fun reset() {
        remainderX = 0f
        remainderY = 0f
    }
}

/**
 * Touchpad gestures: one-finger drag moves the pointer, a tap clicks left, a two-finger tap
 * clicks right, two-finger movement scrolls (by the fingers' centroid, once beyond the tap slop,
 * so it never also right-clicks), and touch-and-hold presses the left button until the finger
 * lifts (drag; a second finger joining a drag doesn't scroll). Feed it pointer events and call
 * [tick] while a finger is down so a hold is noticed without movement.
 */
class TouchpadGestures(
    private val tuning: TouchpadTuning = TouchpadTuning(),
) {
    private class Pointer(
        val startX: Float,
        val startY: Float,
        var lastX: Float,
        var lastY: Float,
        var lastMs: Long,
    )

    private val acceleration = PointerAcceleration(tuning)
    private val scroll = ScrollAccumulator(tuning.scrollDpPerDetent)
    private val pointers = LinkedHashMap<Long, Pointer>()
    private var gestureStartMs = 0L
    private var pointerCountMax = 0
    private var movedBeyondSlop = false
    private var dragging = false

    /** True while touch-and-hold is holding the left button. */
    val isDragging: Boolean get() = dragging

    fun down(
        pointerId: Long,
        xDp: Float,
        yDp: Float,
        timeMs: Long,
    ): List<TouchpadAction> {
        if (pointerId in pointers) return emptyList()
        if (pointers.isEmpty()) {
            gestureStartMs = timeMs
            pointerCountMax = 0
            movedBeyondSlop = false
            acceleration.reset()
            scroll.reset()
        }
        if (pointers.size >= TRACKED_POINTER_COUNT_MAX) return emptyList()
        pointers[pointerId] = Pointer(xDp, yDp, xDp, yDp, timeMs)
        pointerCountMax = max(pointerCountMax, pointers.size)
        return emptyList()
    }

    fun move(
        pointerId: Long,
        xDp: Float,
        yDp: Float,
        timeMs: Long,
    ): List<TouchpadAction> {
        val pointer = pointers[pointerId] ?: return emptyList()
        if (hypot(xDp - pointer.startX, yDp - pointer.startY) > tuning.tapSlopDp) movedBeyondSlop = true
        val isPrimary = pointers.keys.first() == pointerId
        val drives = isPrimary && (dragging || (pointerCountMax == 1 && movedBeyondSlop))
        val scrolls = !dragging && pointers.size == TRACKED_POINTER_COUNT_MAX && movedBeyondSlop
        val actions =
            when {
                drives -> {
                    val (countsX, countsY) = acceleration.apply(xDp - pointer.lastX, yDp - pointer.lastY, timeMs - pointer.lastMs)
                    if (countsX != 0 || countsY != 0) listOf(TouchpadAction.Move(countsX, countsY)) else emptyList()
                }
                // Each finger's move shifts the centroid by half of it.
                scrolls -> listOfNotNull(scroll.apply((xDp - pointer.lastX) / 2, (yDp - pointer.lastY) / 2))
                else -> emptyList()
            }
        pointer.lastX = xDp
        pointer.lastY = yDp
        pointer.lastMs = timeMs
        return actions
    }

    /** Presses the button once a single still finger has been down for [TouchpadTuning.holdToDragMs]. */
    fun tick(timeMs: Long): List<TouchpadAction> {
        if (dragging || pointers.size != 1 || pointerCountMax != 1 || movedBeyondSlop) return emptyList()
        if (timeMs - gestureStartMs < tuning.holdToDragMs) return emptyList()
        dragging = true
        return listOf(TouchpadAction.Press(MouseButton.LEFT))
    }

    fun up(
        pointerId: Long,
        timeMs: Long,
    ): List<TouchpadAction> {
        if (pointers.remove(pointerId) == null || pointers.isNotEmpty()) return emptyList()
        if (dragging) {
            dragging = false
            return listOf(TouchpadAction.Release(MouseButton.LEFT))
        }
        val isTap = !movedBeyondSlop && timeMs - gestureStartMs <= tuning.tapTimeoutMs
        if (!isTap) return emptyList()
        return listOf(TouchpadAction.Click(if (pointerCountMax >= 2) MouseButton.RIGHT else MouseButton.LEFT))
    }

    /** The gesture was interrupted (e.g. the touchpad left the screen): never leave a button held. */
    fun cancel(): List<TouchpadAction> {
        pointers.clear()
        val wasDragging = dragging
        dragging = false
        return if (wasDragging) listOf(TouchpadAction.Release(MouseButton.LEFT)) else emptyList()
    }

    private companion object {
        /** Only the first two fingers matter (right-click, scroll); the rest are ignored. */
        const val TRACKED_POINTER_COUNT_MAX = 2
    }
}
