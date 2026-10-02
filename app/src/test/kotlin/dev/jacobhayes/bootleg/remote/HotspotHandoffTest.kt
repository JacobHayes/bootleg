package dev.jacobhayes.bootleg.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HotspotHandoffTest {
    /** Android's side: hands over a reservation (or fails) whenever the test says so. */
    private class FakeAndroid {
        var deliver: ((String) -> Unit)? = null
        var fail: ((Throwable) -> Unit)? = null
        val closed = mutableListOf<String>()

        suspend fun await(): String =
            awaitHandoff(close = { closed += it }) { deliver, fail ->
                this.deliver = deliver
                this.fail = fail
            }
    }

    @Test
    fun `a delivered reservation is returned and not closed`() =
        runTest {
            val android = FakeAndroid()
            val waiting = async { android.await() }
            runCurrent()
            android.deliver!!("reservation-1")
            assertEquals("reservation-1", waiting.await())
            assertEquals(emptyList<String>(), android.closed)
        }

    @Test
    fun `a reservation arriving after the start was cancelled is closed`() =
        runTest {
            val android = FakeAndroid()
            val waiting = async { android.await() }
            runCurrent()
            waiting.cancel()
            runCurrent()
            android.deliver!!("late")
            assertEquals(listOf("late"), android.closed)
        }

    @Test
    fun `a cancellation racing the handover (resumed, not yet dispatched) closes the reservation`() =
        runTest {
            val android = FakeAndroid()
            val result = CompletableDeferred<String>()
            val waiting = async { result.complete(android.await()) }
            runCurrent()
            // Android answers, and before the waiting coroutine gets to run it is cancelled.
            android.deliver!!("raced")
            waiting.cancel()
            runCurrent()
            assertTrue(waiting.isCancelled)
            assertTrue(!result.isCompleted, "the cancelled start must not receive the reservation")
            assertEquals(listOf("raced"), android.closed)
        }

    @Test
    fun `a failure after cancellation is ignored`() =
        runTest {
            val android = FakeAndroid()
            val waiting = async { android.await() }
            runCurrent()
            waiting.cancel()
            runCurrent()
            android.fail!!(IllegalStateException("no channel"))
            assertEquals(emptyList<String>(), android.closed)
        }
}
