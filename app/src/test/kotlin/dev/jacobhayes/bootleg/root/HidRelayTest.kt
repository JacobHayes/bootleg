package dev.jacobhayes.bootleg.root

import android.os.IBinder
import dev.jacobhayes.bootleg.core.gadget.DefaultGadgetController
import dev.jacobhayes.bootleg.core.gadget.MouseLayout
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.HidLimits
import dev.jacobhayes.bootleg.core.hid.MouseButton
import dev.jacobhayes.bootleg.core.hid.MouseReport
import dev.jacobhayes.bootleg.hid.HidWriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import kotlin.concurrent.thread

class HidRelayTest {
    private class FakeEndpoint(
        val kind: HidKind,
        override val reportLengthBytes: Int,
    ) : HidRelay.RelayEndpoint {
        val written = mutableListOf<List<Byte>>()
        var closed = false
        var binding = HidRelay.Binding.CURRENT

        override fun binding() = binding

        override fun writeWithin(report: ByteArray): HidWriteResult {
            check(!closed) { "write after close" }
            check(report.size == reportLengthBytes) { "wrote ${report.size} bytes to a $reportLengthBytes-byte function" }
            written += report.toList()
            return HidWriteResult.WRITTEN
        }

        override fun readOutputReport(): ByteArray? = null

        override fun close() {
            closed = true
        }

        val releasedAndClosed: Boolean get() = closed && written.lastOrNull() == kind.releaseAllReport.copyOf(reportLengthBytes).toList()
    }

    private class FakeDeathLink : HidRelay.ClientDeathLink {
        val dead = mutableSetOf<IBinder>()
        val linked = mutableMapOf<IBinder, IBinder.DeathRecipient>()

        override fun link(
            client: IBinder,
            recipient: IBinder.DeathRecipient,
        ) {
            check(client !in dead) { "client is dead" }
            linked[client] = recipient
        }

        override fun unlink(
            client: IBinder,
            recipient: IBinder.DeathRecipient,
        ) {
            check(linked[client] === recipient) { "unlinking a recipient that was never linked" }
            linked.remove(client)
        }
    }

    /** Records notices as strings, and checks each is sent without the relay lock held. */
    private class FakeNotifier : HidRelay.ClientNotifier {
        val sent = mutableListOf<String>()

        /** The relay sending the notices. */
        lateinit var sender: HidRelay

        override fun closed(
            client: IBinder,
            reason: String,
            reopenFollows: Boolean,
            restartRequired: Boolean,
        ) {
            assertRelayUnlocked()
            sent += "closed($client, $reason, reopenFollows=$reopenFollows, restartRequired=$restartRequired)"
        }

        override fun reopenable(client: IBinder) {
            assertRelayUnlocked()
            sent += "reopenable($client)"
        }

        private fun assertRelayUnlocked() {
            // Another thread must get the relay lock while a notice is being sent.
            val other = thread { sender.quarantinedCount }
            other.join(LOCK_WAIT_MS)
            assertFalse(other.isAlive, "notice sent while holding the relay lock")
        }
    }

    private val opened = mutableListOf<FakeEndpoint>()
    private val deathLink = FakeDeathLink()

    /** `report_length` of the mouse function the next open finds: 5 (wheel) or 3 (compatible). */
    private var mouseReportLengthBytes = MouseLayout.WHEEL.reportLengthBytes

    /** Opens the fake endpoint for [kind] with its function's report length. */
    private fun fakeOpen(kind: HidKind): FakeEndpoint =
        FakeEndpoint(kind, if (kind == HidKind.MOUSE) mouseReportLengthBytes else kind.reportSizeBytes).also { opened += it }

    /** The fake clock and gadget the relay polls while settling. */
    private var clockMs = 0L
    private var gadgetBound = true
    private val watches = mutableListOf<() -> Boolean>()

    /** A relay whose watch the test runs by hand ([poll]), on the fake clock and gadget. */
    private fun watchedRelay(): HidRelay =
        HidRelay(
            ::fakeOpen,
            deathLink,
            warn = {},
            notifier = notifier,
            gadgetBound = { gadgetBound },
            nowMs = { clockMs },
        ) { watches += it }.also { notifier.sender = it }

    /** Runs the watch every [HidRelay.WATCH_INTERVAL_MS] for [durationMs]; false once it stopped. */
    private fun poll(durationMs: Long): Boolean {
        var watching = true
        repeat((durationMs / HidRelay.WATCH_INTERVAL_MS).toInt()) {
            clockMs += HidRelay.WATCH_INTERVAL_MS
            watching = watches.last()()
        }
        return watching
    }

    private val notifier = FakeNotifier()
    private val relay =
        HidRelay(::fakeOpen, deathLink, warn = {}, notifier = notifier).also {
            notifier.sender =
                it
        }

    /** A binder token with identity only; the relay never calls it directly (the death link and notifier do). */
    private fun token(name: String? = null): IBinder =
        Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> name ?: "token@${System.identityHashCode(proxy)}"
                else -> error("unexpected binder call ${method.name}")
            }
        } as IBinder

    private fun report(kind: HidKind) = ByteArray(kind.reportSizeBytes)

    @Test
    fun `a new client releases and closes every endpoint of the previous one`() {
        val first = token()
        relay.open(HidKind.KEYBOARD, first)
        relay.open(HidKind.MOUSE, first)
        val (keyboard, mouse) = opened.toList()
        val second = token()
        relay.open(HidKind.KEYBOARD, second)

        assertTrue(keyboard.releasedAndClosed, "previous keyboard released and closed")
        assertTrue(mouse.releasedAndClosed, "previous mouse released and closed")
        assertEquals(setOf(second), deathLink.linked.keys, "only the new client is watched")
        // The previous client's mouse is gone; the new client opens its own.
        assertEquals(HidWriteResult.DISCONNECTED, relay.write(HidKind.MOUSE, report(HidKind.MOUSE)))
        assertEquals(HidWriteResult.WRITTEN, relay.write(HidKind.KEYBOARD, report(HidKind.KEYBOARD)))
    }

    @Test
    fun `a dead new client is refused and leaves the current client intact`() {
        val first = token()
        relay.open(HidKind.KEYBOARD, first)
        val keyboard = opened.single()
        val dead = token().also { deathLink.dead += it }

        assertThrows<IllegalStateException> { relay.open(HidKind.KEYBOARD, dead) }

        assertFalse(keyboard.closed, "current client's endpoint kept")
        assertEquals(setOf(first), deathLink.linked.keys, "current client still watched")
        assertEquals(1, opened.size, "nothing opened for the dead client")
        // The current client dying still releases its keys.
        deathLink.linked.getValue(first).binderDied()
        assertTrue(keyboard.releasedAndClosed)
    }

    @Test
    fun `a replaced client's late death notice does not touch the new client`() {
        val first = token()
        relay.open(HidKind.KEYBOARD, first)
        val firstRecipient = deathLink.linked.getValue(first)
        relay.open(HidKind.KEYBOARD, token())
        val current = opened.last()

        firstRecipient.binderDied()

        assertFalse(current.closed, "new client's endpoint kept")
        assertEquals(HidWriteResult.WRITTEN, relay.write(HidKind.KEYBOARD, report(HidKind.KEYBOARD)))
    }

    @Test
    fun `reopening for the same client releases the old endpoint and keeps the death link`() {
        val client = token()
        relay.open(HidKind.KEYBOARD, client)
        val recipient = deathLink.linked.getValue(client)
        relay.open(HidKind.KEYBOARD, client)

        assertTrue(opened[0].releasedAndClosed)
        assertFalse(opened[1].closed)
        assertSame(recipient, deathLink.linked.getValue(client))
    }

    @Test
    fun `the compatible mouse gets the boot bytes of each report, release included`() {
        mouseReportLengthBytes = MouseLayout.BOOT.reportLengthBytes
        relay.open(HidKind.MOUSE, token())
        val mouse = opened.single()
        val scrollWithButton = MouseReport(MouseButton.LEFT.bit, 3, -4, wheel = 2, pan = -1).encode()
        assertEquals(HidWriteResult.WRITTEN, relay.write(HidKind.MOUSE, scrollWithButton))
        assertEquals(listOf<Byte>(1, 3, -4), mouse.written.single())
        // Reports are always the wheel layout's size on the wire from the app.
        assertThrows<IllegalArgumentException> { relay.write(HidKind.MOUSE, ByteArray(3)) }
        relay.close(HidKind.MOUSE)
        assertTrue(mouse.releasedAndClosed)
        assertEquals(listOf<Byte>(0, 0, 0), mouse.written.last())
    }

    @Test
    fun `the wheel mouse gets the whole report`() {
        relay.open(HidKind.MOUSE, token())
        val report = MouseReport(0, wheel = -5, pan = 7).encode()
        relay.write(HidKind.MOUSE, report)
        assertEquals(report.toList(), opened.single().written.single())
    }

    @Test
    fun `closing the last endpoint stops watching the client`() {
        val client = token()
        relay.open(HidKind.KEYBOARD, client)
        relay.close(HidKind.KEYBOARD)

        assertTrue(opened.single().releasedAndClosed)
        assertEquals(emptyMap<IBinder, IBinder.DeathRecipient>(), deathLink.linked)
    }

    @Test
    fun `a gadget change releases and closes everything and refuses opens until it ends`() {
        val client = token()
        relay.open(HidKind.KEYBOARD, client)
        relay.open(HidKind.MOUSE, client)
        relay.closeAllForGadgetChange()

        assertTrue(opened.all { it.releasedAndClosed })
        assertEquals(HidWriteResult.DISCONNECTED, relay.write(HidKind.KEYBOARD, report(HidKind.KEYBOARD)))
        assertThrows<IllegalStateException> { relay.open(HidKind.KEYBOARD, client) }
        assertEquals(2, opened.size, "nothing opened while fenced")
        relay.endGadgetChange()
        relay.open(HidKind.KEYBOARD, client)
        assertEquals(HidWriteResult.WRITTEN, relay.write(HidKind.KEYBOARD, report(HidKind.KEYBOARD)))
    }

    @Test
    fun `an fd whose function was unbound behind us is closed at once, without a report`() {
        relay.open(HidKind.KEYBOARD, token())
        val keyboard = opened.single()
        keyboard.binding = HidRelay.Binding.UNBOUND

        assertEquals(HidWriteResult.DISCONNECTED, relay.write(HidKind.KEYBOARD, report(HidKind.KEYBOARD)))
        assertTrue(keyboard.closed)
        assertEquals(emptyList<List<Byte>>(), keyboard.written)
        assertEquals(0, relay.quarantinedCount)
    }

    @Test
    fun `the watch closes an fd as soon as its function is unbound, before any rebind`() {
        val watched = watchedRelay()
        val client = token()
        watched.open(HidKind.KEYBOARD, client)
        watched.open(HidKind.MOUSE, client)
        assertEquals(1, watches.size, "one watch for the open fds")
        assertTrue(watches.single()(), "still watching while open")

        opened.forEach { it.binding = HidRelay.Binding.UNBOUND }
        gadgetBound = false
        assertTrue(watches.single()(), "still watching while USB settles")
        assertTrue(opened.all { it.closed })
        assertEquals(0, watched.quarantinedCount)
        gadgetBound = true
        assertFalse(poll(DefaultGadgetController.STABLE_BOUND_MS + HidRelay.WATCH_INTERVAL_MS), "nothing left to watch once settled")
        watched.open(HidKind.KEYBOARD, client)
        assertEquals(2, watches.size, "a new watch once something is open again")
    }

    @Test
    fun `after someone else re-enumerates, nothing opens until USB stayed bound for the stable window`() {
        val watched = watchedRelay()
        val client = token("app")
        watched.open(HidKind.KEYBOARD, client)
        // The HAL's mode switch: unbind, bind, then (inside the stable window) unbind and bind again.
        opened.single().binding = HidRelay.Binding.UNBOUND
        gadgetBound = false
        poll(HidRelay.WATCH_INTERVAL_MS)
        assertTrue(opened.single().closed)
        poll(400)
        gadgetBound = true
        poll(600)
        assertThrows<IllegalStateException> { watched.open(HidKind.KEYBOARD, client) }
        gadgetBound = false
        poll(400)
        gadgetBound = true
        poll(DefaultGadgetController.STABLE_BOUND_MS - HidRelay.WATCH_INTERVAL_MS)
        val refused = assertThrows<IllegalStateException> { watched.open(HidKind.KEYBOARD, client) }
        assertEquals(HidRelay.SETTLING_MESSAGE, refused.message)
        assertEquals(1, opened.size, "nothing was opened across the second rebind")
        assertFalse("reopenable(app)" in notifier.sent)

        poll(2 * HidRelay.WATCH_INTERVAL_MS)
        assertEquals("reopenable(app)", notifier.sent.last())
        watched.open(HidKind.KEYBOARD, client)
        assertEquals(2, opened.size)
    }

    @Test
    fun `a settle that never sees USB stable gives up at the cap without announcing a reopen`() {
        val watched = watchedRelay()
        val client = token("app")
        watched.open(HidKind.MOUSE, client)
        opened.single().binding = HidRelay.Binding.UNBOUND
        gadgetBound = false
        assertFalse(poll(HidLimits.RELAY_SETTLE_WAIT_MS_MAX + HidRelay.WATCH_INTERVAL_MS), "the watch stops at the cap")
        assertFalse("reopenable(app)" in notifier.sent)
        watched.open(HidKind.MOUSE, client)
    }

    @Test
    fun `reopening over an fd rebound behind us quarantines it and opens nothing`() {
        val client = token()
        relay.open(HidKind.KEYBOARD, client)
        opened.single().binding = HidRelay.Binding.REBOUND
        assertThrows<IllegalStateException> { relay.open(HidKind.KEYBOARD, client) }
        assertEquals(1, opened.size)
        assertFalse(opened.single().closed)
        assertEquals(1, relay.quarantinedCount)
    }

    @Test
    fun `an fd rebound behind us is never closed, and HID stays refused`() {
        val client = token()
        relay.open(HidKind.KEYBOARD, client)
        relay.open(HidKind.MOUSE, client)
        val (keyboard, mouse) = opened.toList()
        keyboard.binding = HidRelay.Binding.REBOUND

        assertEquals(ByteArray(0).toList(), relay.readOutput(HidKind.KEYBOARD).toList())
        relay.closeAllForGadgetChange()
        deathLink.linked[client]?.binderDied()

        assertFalse(keyboard.closed, "closing it would panic the kernel")
        assertEquals(emptyList<List<Byte>>(), keyboard.written)
        assertTrue(mouse.releasedAndClosed)
        assertEquals(1, relay.quarantinedCount)
        relay.endGadgetChange()
        val refused = assertThrows<IllegalStateException> { relay.open(HidKind.MOUSE, client) }
        assertEquals(HidRelay.QUARANTINED_MESSAGE, refused.message)
    }

    @Test
    fun `a gadget change tells the client its fds are gone, and when opens work again`() {
        val client = token("app")
        relay.open(HidKind.KEYBOARD, client)
        relay.open(HidKind.MOUSE, client)
        relay.closeAllForGadgetChange()
        // Every UDC write closes again; nothing is left, so nothing more is said.
        relay.closeAllForGadgetChange()
        assertEquals(listOf("closed(app, ${HidRelay.GADGET_CHANGING_MESSAGE}, reopenFollows=true, restartRequired=false)"), notifier.sent)

        relay.endGadgetChange()
        assertEquals("reopenable(app)", notifier.sent.last())
        assertEquals(2, notifier.sent.size)
        // The next controller call that changes nothing says nothing.
        relay.endGadgetChange()
        assertEquals(2, notifier.sent.size)
    }

    @Test
    fun `a client refused an open during a gadget change is told when it ends`() {
        relay.closeAllForGadgetChange()
        val client = token("app")
        assertThrows<IllegalStateException> { relay.open(HidKind.KEYBOARD, client) }
        assertEquals(emptyList<String>(), notifier.sent)
        relay.endGadgetChange()
        assertEquals(listOf("reopenable(app)"), notifier.sent)
    }

    @Test
    fun `a gadget change with nothing open says nothing`() {
        relay.closeAllForGadgetChange()
        relay.endGadgetChange()
        assertEquals(emptyList<String>(), notifier.sent)
    }

    @Test
    fun `the client is told when someone else unbinds its function, by the watch or a write`() {
        val watched = watchedRelay()
        val client = token("app")
        watched.open(HidKind.KEYBOARD, client)
        watched.open(HidKind.MOUSE, client)
        val closed = "closed(app, ${HidRelay.REENUMERATED_MESSAGE}, reopenFollows=true, restartRequired=false)"
        opened[0].binding = HidRelay.Binding.UNBOUND
        assertEquals(HidWriteResult.DISCONNECTED, watched.write(HidKind.KEYBOARD, report(HidKind.KEYBOARD)))
        assertEquals(listOf(closed), notifier.sent)

        opened[1].binding = HidRelay.Binding.UNBOUND
        watches.single()()
        assertEquals(listOf(closed, closed), notifier.sent)
    }

    @Test
    fun `a quarantine tells the client a restart is required, and so does every refused open`() {
        val watched = watchedRelay()
        val client = token("app")
        watched.open(HidKind.KEYBOARD, client)
        opened.single().binding = HidRelay.Binding.REBOUND
        watches.single()()
        val restart = "closed(app, ${HidRelay.QUARANTINED_MESSAGE}, reopenFollows=false, restartRequired=true)"
        assertEquals(listOf(restart), notifier.sent)

        assertThrows<IllegalStateException> { watched.open(HidKind.KEYBOARD, client) }
        assertEquals(listOf(restart, restart), notifier.sent)
        // Opens stay refused after a gadget change too, so no reopen notice.
        watched.closeAllForGadgetChange()
        watched.endGadgetChange()
        assertEquals(listOf(restart, restart), notifier.sent)
    }

    @Test
    fun `a gadget change that finds an fd rebound behind us says a restart is required`() {
        val client = token("app")
        relay.open(HidKind.KEYBOARD, client)
        opened.single().binding = HidRelay.Binding.REBOUND
        relay.closeAllForGadgetChange()
        relay.endGadgetChange()
        assertEquals(listOf("closed(app, ${HidRelay.QUARANTINED_MESSAGE}, reopenFollows=false, restartRequired=true)"), notifier.sent)
    }

    private companion object {
        const val LOCK_WAIT_MS = 2_000L
    }
}
