package dev.jacobhayes.bootleg.core.hid

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DropHoldTest {
    private val holdMs = 3_000L
    private val ranks = mapOf("open" to 2, "partial" to 1, "reconnecting" to 0, "stopped" to null)

    private fun hold() = DropHold<String>(holdMs) { ranks.getValue(it) }

    @Test
    fun `a host resetting while it boots shows connected throughout, a lasting drop shows after the hold`() {
        val hold = hold()
        assertEquals("reconnecting", hold.observe("reconnecting", 0))
        assertEquals("open", hold.observe("open", 100))
        // Reset, re-enumeration and the firmware handing off to the OS: each drop is shorter than the hold.
        assertEquals("open", hold.observe("reconnecting", 1_000))
        assertEquals("open", hold.observe("partial", 2_000))
        assertEquals("open", hold.observe("open", 3_500))
        assertNull(hold.dropShowsAtMs)
        // The hold runs from the first value below the shown one, through partial recoveries.
        assertEquals("open", hold.observe("reconnecting", 5_000))
        assertEquals("open", hold.observe("partial", 6_000))
        assertEquals(8_000L, hold.dropShowsAtMs)
        assertEquals("open", hold.shownAt(7_999))
        assertEquals("partial", hold.shownAt(8_000))
        // A deliberate stop shows at once, and so does whatever follows it.
        assertEquals("open", hold.observe("open", 9_000))
        assertEquals("stopped", hold.observe("stopped", 9_001))
        assertEquals("reconnecting", hold.observe("reconnecting", 9_002))
    }

    @Test
    fun `never shows worse than the latest value, and the latest shows once it has lasted the hold`() =
        runBlocking<Unit> {
            val steps = Arb.list(Arb.pair(Arb.element(ranks.keys.toList()), Arb.long(0L..2 * holdMs)), 1..40)
            checkAll(steps) { observations ->
                val hold = hold()
                var nowMs = 0L
                for ((value, gapMs) in observations) {
                    nowMs += gapMs
                    val shown = hold.observe(value, nowMs)
                    val shownRank = ranks.getValue(shown)
                    val valueRank = ranks.getValue(value)
                    if (shownRank == null || valueRank == null) {
                        assertEquals(value, shown)
                    } else {
                        assertTrue(shownRank >= valueRank) { "$shown shown over $value" }
                    }
                }
                assertEquals(observations.last().first, hold.shownAt(nowMs + holdMs))
            }
        }
}
