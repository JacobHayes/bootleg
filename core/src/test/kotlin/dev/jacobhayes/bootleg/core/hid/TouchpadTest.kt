package dev.jacobhayes.bootleg.core.hid

import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class TouchpadTest {
    private val tuning = TouchpadTuning()

    private fun moves(actions: List<TouchpadAction>) = actions.filterIsInstance<TouchpadAction.Move>()

    @Test
    fun `tuning rejects non-finite and out-of-range values`() {
        val invalid =
            listOf<() -> TouchpadTuning>(
                { TouchpadTuning(tapSlopDp = Float.POSITIVE_INFINITY) },
                { TouchpadTuning(tapSlopDp = Float.NaN) },
                { TouchpadTuning(tapSlopDp = TouchpadTuning.TAP_SLOP_DP_MAX + 1f) },
                { TouchpadTuning(countsPerDp = Float.POSITIVE_INFINITY) },
                { TouchpadTuning(countsPerDp = Float.NaN) },
                { TouchpadTuning(countsPerDp = TouchpadTuning.COUNTS_PER_DP_MAX * 2) },
                { TouchpadTuning(accelerationGainMax = Float.POSITIVE_INFINITY) },
                { TouchpadTuning(accelerationGainMax = Float.NaN) },
                { TouchpadTuning(accelerationGainMax = TouchpadTuning.ACCELERATION_GAIN_MAX + 1f) },
                { TouchpadTuning(fastSpeedDpPerMs = Float.POSITIVE_INFINITY) },
                { TouchpadTuning(fastSpeedDpPerMs = Float.NaN) },
                { TouchpadTuning(fastSpeedDpPerMs = TouchpadTuning.FAST_SPEED_DP_PER_MS_MAX + 1f) },
                { TouchpadTuning(tapTimeoutMs = Long.MAX_VALUE, holdToDragMs = Long.MAX_VALUE) },
                { TouchpadTuning(holdToDragMs = TouchpadTuning.HOLD_TO_DRAG_MS_MAX + 1) },
                { TouchpadTuning(tapTimeoutMs = 0) },
                { TouchpadTuning(scrollDpPerDetent = 0f) },
                { TouchpadTuning(scrollDpPerDetent = Float.NaN) },
                { TouchpadTuning(scrollDpPerDetent = TouchpadTuning.SCROLL_DP_PER_DETENT_MAX + 1f) },
            )
        invalid.forEachIndexed { index, build -> assertThrows<IllegalArgumentException>("case $index") { build() } }
        // The maxima themselves are accepted.
        TouchpadTuning(
            tapSlopDp = TouchpadTuning.TAP_SLOP_DP_MAX,
            tapTimeoutMs = TouchpadTuning.TAP_TIMEOUT_MS_MAX,
            holdToDragMs = TouchpadTuning.HOLD_TO_DRAG_MS_MAX,
            countsPerDp = TouchpadTuning.COUNTS_PER_DP_MAX,
            accelerationGainMax = TouchpadTuning.ACCELERATION_GAIN_MAX,
            fastSpeedDpPerMs = TouchpadTuning.FAST_SPEED_DP_PER_MS_MAX,
            scrollDpPerDetent = TouchpadTuning.SCROLL_DP_PER_DETENT_MAX,
        )
    }

    @Test
    fun `quick still tap clicks left`() {
        val pad = TouchpadGestures(tuning)
        pad.down(1, 100f, 100f, 0)
        assertEquals(emptyList<TouchpadAction>(), pad.move(1, 103f, 102f, 50))
        assertEquals(listOf(TouchpadAction.Click(MouseButton.LEFT)), pad.up(1, 120))
    }

    @Test
    fun `slow lift is not a click`() {
        val pad = TouchpadGestures(tuning)
        pad.down(1, 0f, 0f, 0)
        assertEquals(emptyList<TouchpadAction>(), pad.up(1, tuning.tapTimeoutMs + 1))
    }

    @Test
    fun `two-finger tap clicks right`() {
        val pad = TouchpadGestures(tuning)
        pad.down(1, 100f, 100f, 0)
        pad.down(2, 160f, 100f, 20)
        assertEquals(emptyList<TouchpadAction>(), pad.up(2, 100))
        assertEquals(listOf(TouchpadAction.Click(MouseButton.RIGHT)), pad.up(1, 110))
    }

    private fun scrolls(actions: List<TouchpadAction>) = actions.filterIsInstance<TouchpadAction.Scroll>()

    @Test
    fun `two fingers dragged down scroll down by their centroid, without moving or clicking`() {
        val pad = TouchpadGestures(tuning)
        pad.down(1, 100f, 100f, 0)
        pad.down(2, 160f, 100f, 10)
        val actions =
            (1..6).flatMap { step ->
                pad.move(1, 100f, 100f + step * 10f, step * 16L) +
                    pad.move(2, 160f, 100f + step * 10f, step * 16L)
            }
        assertEquals(emptyList<TouchpadAction.Move>(), moves(actions))
        val scrolled = scrolls(actions)
        assertTrue(scrolled.isNotEmpty())
        // Centroid moved 60dp down; the part inside the tap slop doesn't scroll. Down is negative wheel.
        val wheel = scrolled.sumOf { it.wheel }
        assertTrue(wheel < 0 && wheel >= -60 / tuning.scrollDpPerDetent.toInt(), "wheel $wheel")
        assertTrue(scrolled.all { it.pan == 0 })
        assertEquals(emptyList<TouchpadAction>(), pad.up(1, 120) + pad.up(2, 130))
    }

    @Test
    fun `two fingers dragged right pan right, and small wobbles never scroll`() {
        val pad = TouchpadGestures(tuning)
        pad.down(1, 0f, 0f, 0)
        pad.down(2, 0f, 50f, 0)
        assertEquals(emptyList<TouchpadAction>(), pad.move(1, 3f, 2f, 20) + pad.move(2, 3f, 52f, 20))
        val actions =
            (1..10).flatMap { step ->
                pad.move(1, step * 20f, 0f, 20L + step * 16) + pad.move(2, step * 20f, 50f, 20L + step * 16)
            }
        val scrolled = scrolls(actions)
        assertTrue(scrolled.sumOf { it.pan } > 0)
        assertTrue(scrolled.all { it.wheel == 0 })
        // A two-finger gesture that scrolled is not a right click.
        assertEquals(emptyList<TouchpadAction>(), pad.up(1, 300) + pad.up(2, 300))
    }

    @Test
    fun `a second finger during a hold-drag moves the pointer instead of scrolling`() {
        val pad = TouchpadGestures(tuning)
        pad.down(1, 50f, 50f, 0)
        assertEquals(listOf(TouchpadAction.Press(MouseButton.LEFT)), pad.tick(tuning.holdToDragMs))
        pad.down(2, 100f, 50f, tuning.holdToDragMs + 10)
        val actions = pad.move(1, 50f, 90f, tuning.holdToDragMs + 30) + pad.move(2, 100f, 90f, tuning.holdToDragMs + 30)
        assertEquals(emptyList<TouchpadAction.Scroll>(), scrolls(actions))
        assertTrue(moves(actions).isNotEmpty())
    }

    @Test
    fun `scroll keeps sub-detent remainders and clamps each report (property)`() =
        runBlocking<Unit> {
            checkAll(Arb.list(Arb.float(-30f, 30f).filter { it.isFinite() }, 1..50)) { deltas ->
                val accumulator = ScrollAccumulator(tuning.scrollDpPerDetent)
                val total = deltas.sumOf { accumulator.apply(0f, it)?.wheel?.toDouble() ?: 0.0 }
                // Detents sent equal the travel rounded toward zero, within one detent of float error.
                val expected = -deltas.sumOf { it.toDouble() } / tuning.scrollDpPerDetent
                assertTrue(abs(total - expected) < 1.0 + 1e-3, "sent $total for $expected")
            }
            val accumulator = ScrollAccumulator(1f)
            assertEquals(
                TouchpadAction.Scroll(-HidLimits.MOUSE_SCROLL_MAX, HidLimits.MOUSE_SCROLL_MAX),
                accumulator.apply(10_000f, 10_000f),
            )
            // The excess was dropped, not carried.
            assertEquals(null, accumulator.apply(0f, 0f))
        }

    @Test
    fun `drag beyond slop moves without clicking`() {
        val pad = TouchpadGestures(tuning)
        pad.down(1, 0f, 0f, 0)
        val actions = (1..10).flatMap { step -> pad.move(1, step * 5f, 0f, step * 16L) }
        val moved = moves(actions)
        assertTrue(moved.isNotEmpty())
        assertTrue(moved.all { it.deltaX > 0 && it.deltaY == 0 })
        // The movement inside the slop isn't sent: the pointer doesn't jump when a tap wobbles.
        assertTrue(moved.sumOf { it.deltaX } < 50 * tuning.accelerationGainMax)
        assertEquals(emptyList<TouchpadAction>(), pad.up(1, 200))
    }

    @Test
    fun `touch and hold presses left until lift, and moves carry the button`() {
        val pad = TouchpadGestures(tuning)
        pad.down(1, 50f, 50f, 0)
        assertEquals(emptyList<TouchpadAction>(), pad.tick(tuning.holdToDragMs - 1))
        assertEquals(listOf(TouchpadAction.Press(MouseButton.LEFT)), pad.tick(tuning.holdToDragMs))
        assertTrue(pad.isDragging)
        assertEquals(emptyList<TouchpadAction>(), pad.tick(tuning.holdToDragMs + 100))
        val moved = moves(pad.move(1, 60f, 50f, tuning.holdToDragMs + 116))
        assertEquals(1, moved.size)
        assertEquals(listOf(TouchpadAction.Release(MouseButton.LEFT)), pad.up(1, tuning.holdToDragMs + 300))
    }

    @Test
    fun `hold after moving or with two fingers does not drag`() {
        val moving = TouchpadGestures(tuning)
        moving.down(1, 0f, 0f, 0)
        moving.move(1, 40f, 0f, 100)
        assertEquals(emptyList<TouchpadAction>(), moving.tick(tuning.holdToDragMs * 2))
        val twoFingers = TouchpadGestures(tuning)
        twoFingers.down(1, 0f, 0f, 0)
        twoFingers.down(2, 40f, 0f, 10)
        assertEquals(emptyList<TouchpadAction>(), twoFingers.tick(tuning.holdToDragMs * 2))
    }

    @Test
    fun `cancel releases a held button and nothing else`() {
        val pad = TouchpadGestures(tuning)
        assertEquals(emptyList<TouchpadAction>(), pad.cancel())
        pad.down(1, 0f, 0f, 0)
        pad.tick(tuning.holdToDragMs)
        assertEquals(listOf(TouchpadAction.Release(MouseButton.LEFT)), pad.cancel())
        assertEquals(emptyList<TouchpadAction>(), pad.up(1, 1_000))
    }

    @Test
    fun `acceleration ramps from 1 to the max gain`() {
        val acceleration = PointerAcceleration(tuning)
        assertEquals(1f, acceleration.gainFor(0f))
        assertEquals(tuning.accelerationGainMax, acceleration.gainFor(tuning.fastSpeedDpPerMs))
        assertEquals(tuning.accelerationGainMax, acceleration.gainFor(100f))
        assertTrue(acceleration.gainFor(tuning.fastSpeedDpPerMs / 2) in 1f..tuning.accelerationGainMax)
    }

    @Test
    fun `slow motion keeps sub-count remainders instead of losing them`() {
        val acceleration = PointerAcceleration(tuning)
        // 0.25 dp per 16 ms is very slow: gain ~1, 0.25 counts per event.
        val total = (1..40).sumOf { acceleration.apply(0.25f, -0.25f, 16).first }
        assertTrue(total in 9..10, "expected ~10 counts, got $total")
    }

    @Test
    fun `accelerated counts keep direction and are bounded by the max gain (property)`() =
        runBlocking<Unit> {
            checkAll(Arb.list(Arb.float(-200f, 200f).filter { it.isFinite() }, 1..50), Arb.long(1L, 50L)) { deltas, elapsedMs ->
                val acceleration = PointerAcceleration(tuning)
                var sum = 0L
                for (delta in deltas) {
                    val (countsX, _) = acceleration.apply(delta, 0f, elapsedMs)
                    assertTrue(countsX == 0 || (countsX > 0) == (delta > 0))
                    sum += countsX
                }
                assertTrue(abs(sum) <= abs(deltas.sumOf { abs(it.toDouble()) }) * tuning.accelerationGainMax + 1)
            }
        }

    @Test
    fun `mouse input turns touchpad actions into split boot reports`() {
        val mouse = MouseInput()
        val reports = mouse.apply(TouchpadAction.Move(300, -10))
        assertEquals(300, reports.sumOf { it.deltaX })
        assertEquals(-10, reports.sumOf { it.deltaY })
        assertTrue(reports.all { it.buttonBits == 0 })
        assertEquals(listOf(MouseReport(1), MouseReport(0)), mouse.apply(TouchpadAction.Click(MouseButton.LEFT)))
        assertEquals(listOf(MouseReport(1)), mouse.apply(TouchpadAction.Press(MouseButton.LEFT)))
        assertTrue(mouse.apply(TouchpadAction.Move(5, 5)).all { it.buttonBits == 1 })
        assertEquals(listOf(MouseReport(3), MouseReport(1)), mouse.apply(TouchpadAction.Click(MouseButton.RIGHT)))
        assertEquals(listOf(MouseReport(0)), mouse.apply(TouchpadAction.Release(MouseButton.LEFT)))
        assertEquals(emptyList<MouseReport>(), mouse.apply(TouchpadAction.Release(MouseButton.LEFT)))
        mouse.apply(TouchpadAction.Press(MouseButton.LEFT))
        assertEquals(listOf(MouseReport(1, wheel = -2, pan = 1)), mouse.apply(TouchpadAction.Scroll(-2, 1)))
        assertEquals(1, mouse.buttonBits)
    }
}
