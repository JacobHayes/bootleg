package dev.jacobhayes.bootleg.hid

import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.FakeBootlegBackend
import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.MouseLayout
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class HidControllerTest {
    private val main = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(main)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** HID is on; [setHid] waits for [applied]; every open is counted (and never completes). */
    private class GatedBackend(
        val fake: FakeBootlegBackend = FakeBootlegBackend(initialJournal = Journal(Desired(hid = HidSpec(true, true)))),
    ) : BootlegBackend by fake {
        val applying = CompletableDeferred<Unit>()
        val applied = CompletableDeferred<Unit>()
        val opens = AtomicInteger()

        override suspend fun setHid(spec: HidSpec): ApplyResult {
            applying.complete(Unit)
            applied.await()
            return fake.setHid(spec)
        }

        override suspend fun openHid(kind: HidKind): HidEndpoint {
            opens.incrementAndGet()
            awaitCancellation()
        }
    }

    @Test
    fun `no session opens while its own HID apply runs, and one opens after it`() {
        runBlocking<Unit> {
            val backend = GatedBackend()
            val scope = CoroutineScope(main)
            val controller = HidController(backend, scope)
            try {
                val change = scope.launch { controller.setSpec(HidSpec(keyboard = true, mouse = true)) }
                backend.applying.await()
                // Input and the remote port while the functions are being changed must not reopen /dev/hidgN.
                controller.tapKey(KeyUsage.BACKSPACE)
                controller.connect()
                controller.remotePort.send(HidKind.MOUSE, ByteArray(HidKind.MOUSE.reportSizeBytes))
                delay(OPEN_SETTLE_MS)
                assertEquals(0, backend.opens.get(), "opened during the apply")

                backend.applied.complete(Unit)
                change.join()
                withTimeout(OPEN_SETTLE_MS * 20) { while (backend.opens.get() == 0) delay(10) }
            } finally {
                // Stops the session on the controller's own scope, which cancelling ours wouldn't.
                backend.applied.complete(Unit)
                controller.setSpec(HidSpec())
                scope.cancel()
            }
        }
    }

    @Test
    fun `relay notices reach the session from its first open, and a quarantine shows restart required`() {
        runBlocking<Unit> {
            val backend = GatedBackend()
            val scope = CoroutineScope(main)
            val controller = HidController(backend, scope)
            try {
                controller.connect()
                // Right away, while the first open is still in flight.
                val quarantine = HidRelayNotice.Closed("restart the phone", reopenFollows = false, restartRequired = true)
                assertTrue(backend.fake.hidRelayNoticeFlow.tryEmit(quarantine))
                withTimeout(OPEN_SETTLE_MS * 20) {
                    controller.link.first { it == HidLinkState.Failed("restart the phone", restartRequired = true) }
                }
            } finally {
                backend.applied.complete(Unit)
                controller.setSpec(HidSpec())
                scope.cancel()
            }
        }
    }

    @Test
    fun `no session opens while a mouse layout swap runs, and one opens after it`() {
        runBlocking<Unit> {
            val backend = GatedBackend()
            val scope = CoroutineScope(main)
            val controller = HidController(backend, scope) { MouseLayout.BOOT }
            try {
                val swap = scope.launch { controller.applyMouseLayout() }
                backend.applying.await()
                controller.connect()
                controller.remotePort.send(HidKind.MOUSE, ByteArray(HidKind.MOUSE.reportSizeBytes))
                delay(OPEN_SETTLE_MS)
                assertEquals(0, backend.opens.get(), "opened during the swap")

                backend.applied.complete(Unit)
                swap.join()
                withTimeout(OPEN_SETTLE_MS * 20) { while (backend.opens.get() == 0) delay(10) }
            } finally {
                backend.applied.complete(Unit)
                controller.setSpec(HidSpec())
                scope.cancel()
            }
        }
    }

    @Test
    fun `a session the background closed reopens when the app comes back, not left Stopped`() {
        runBlocking<Unit> {
            val backend = GatedBackend()
            val scope = CoroutineScope(main)
            val controller = HidController(backend, scope)
            try {
                controller.connect()
                withTimeout(OPEN_SETTLE_MS * 20) { while (backend.opens.get() == 0) delay(10) }
                controller.onBackground()
                withTimeout(OPEN_SETTLE_MS * 20) { controller.link.first { it == HidLinkState.Stopped } }
                val opensBefore = backend.opens.get()
                // Back right away, while the background's stop may still be running.
                controller.onForeground()
                withTimeout(OPEN_SETTLE_MS * 20) { while (backend.opens.get() == opensBefore) delay(10) }
                withTimeout(OPEN_SETTLE_MS * 20) { controller.link.first { it != HidLinkState.Stopped } }
                // A second foreground with nothing closed opens nothing more.
                val opensAfter = backend.opens.get()
                controller.onForeground()
                delay(OPEN_SETTLE_MS)
                assertEquals(opensAfter, backend.opens.get())
            } finally {
                backend.applied.complete(Unit)
                controller.setSpec(HidSpec())
                scope.cancel()
            }
        }
    }

    @Test
    fun `background then foreground with HID turned off meanwhile opens nothing`() {
        runBlocking<Unit> {
            val backend = GatedBackend()
            val scope = CoroutineScope(main)
            val controller = HidController(backend, scope)
            try {
                controller.connect()
                withTimeout(OPEN_SETTLE_MS * 20) { while (backend.opens.get() == 0) delay(10) }
                controller.onBackground()
                backend.applied.complete(Unit)
                controller.setSpec(HidSpec())
                val opensBefore = backend.opens.get()
                controller.onForeground()
                delay(OPEN_SETTLE_MS)
                assertEquals(opensBefore, backend.opens.get())
                assertEquals(HidLinkState.Stopped, controller.link.value)
            } finally {
                backend.applied.complete(Unit)
                controller.setSpec(HidSpec())
                scope.cancel()
            }
        }
    }

    private companion object {
        const val OPEN_SETTLE_MS = 100L
    }
}
