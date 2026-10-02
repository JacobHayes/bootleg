package dev.jacobhayes.bootleg.root

import android.os.IBinder
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.hid.HidWriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

class HidRelayTest {
    private class FakeEndpoint(
        val kind: HidKind,
    ) : HidRelay.RelayEndpoint {
        val written = mutableListOf<List<Byte>>()
        var closed = false

        override fun writeWithin(report: ByteArray): HidWriteResult {
            check(!closed) { "write after close" }
            written += report.toList()
            return HidWriteResult.WRITTEN
        }

        override fun readOutputReport(): ByteArray? = null

        override fun close() {
            closed = true
        }

        val releasedAndClosed: Boolean get() = closed && written.lastOrNull() == kind.releaseAllReport.toList()
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

    private val opened = mutableListOf<FakeEndpoint>()
    private val deathLink = FakeDeathLink()
    private val relay = HidRelay({ kind -> FakeEndpoint(kind).also { opened += it } }, deathLink, warn = {})

    /** A binder token with identity only; the relay never calls it directly (the death link does). */
    private fun token(): IBinder =
        Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "token@${System.identityHashCode(proxy)}"
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
    fun `closing the last endpoint stops watching the client`() {
        val client = token()
        relay.open(HidKind.KEYBOARD, client)
        relay.close(HidKind.KEYBOARD)

        assertTrue(opened.single().releasedAndClosed)
        assertEquals(emptyMap<IBinder, IBinder.DeathRecipient>(), deathLink.linked)
    }
}
