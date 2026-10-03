package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.hid.KeyboardReport
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.bool
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.num
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.str
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** End-to-end stories through the controller, each checked against the harness's safety properties. */
class RemoteScenarioTest {
    private val h = RemoteHarness()

    private fun rev(name: String): Long =
        h
            .request(name, "state.get")!!
            .getValue("state")
            .jsonObject
            .long("rev")

    private fun paste(
        name: String,
        text: String,
        id: String,
    ) {
        val epoch = h.epoch()
        val planId = h.request(name, "paste.prepare", mapOf("text" to str(text)), epoch)!!.getValue("planId")
        check(h.request(name, "paste.run", mapOf("planId" to planId), epoch, id = id) == null) { "paste.run answers when typing ends" }
    }

    @Test
    fun `revoke during paste stops typing and releases in the same step`() {
        h.connect("s1", "n1")
        h.take("s1")
        paste("s1", "the quick brown fox jumps over the lazy dog", id = "run")
        h.runFor(100)
        val typedBefore = h.hidHost.typedText
        assertTrue(typedBefore.isNotEmpty())
        val effects = h.handle(RemoteEvent.Revoke(Principal.Node("n1")))
        assertTrue(RemoteEffect.HidReleaseAll in effects && RemoteEffect.Close(h.id("s1"), CloseReason.REVOKED) in effects)
        h.runFor(2_000)
        assertEquals(typedBefore, h.hidHost.typedText)
        assertFalse(h.hidHost.anyHeld)
        assertNull(h.controller.status().runningJob)
        h.open("s2", h.tailnet("n1"))
        assertFalse("s2" in h.authorized)
    }

    @Test
    fun `takeover during boot-key mashing stops the mash and hands over a clean keyboard`() {
        h.connect("s1")
        h.connect("s2")
        val oldEpoch = h.take("s1")
        h.request(
            "s1",
            "bootkey.run",
            mapOf("vendor" to str("dell"), "action" to str("menu"), "durationMs" to num(5_000)),
            oldEpoch,
            id = "boot",
        )
        h.runFor(1_010, stepMs = 10)
        h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("s1"))), oldEpoch, id = "t1")
        h.runFor(500, stepMs = 10)
        assertTrue(h.request("s1", "lease.takeover_reply", mapOf("allow" to bool(true)), oldEpoch)!!.ok)
        val newEpoch =
            h
                .replyFor("s2", "t1")!!
                .getValue("epoch")
                .jsonPrimitive.int
        assertEquals(h.holder("s2") to newEpoch, h.controller.leaseView)
        assertFalse(h.hidHost.anyHeld)
        assertEquals("cancelled", h.replyFor("s1", "boot")!!.errorCode)
        val sentAtTransfer = h.hidHost.sent.size
        h.runFor(5_000, stepMs = 50)
        assertEquals(sentAtTransfer, h.hidHost.sent.size)
        h.down("s1", KeyUsage.F12, oldEpoch)
        h.down("s2", KeyUsage.ENTER, newEpoch)
        assertEquals(KeyboardReport(0, listOf(KeyUsage.ENTER)), h.hidHost.keyboard)
    }

    @Test
    fun `stale-epoch frames after re-acquiring the lease are dropped`() {
        h.connect("s1")
        val first = h.take("s1")
        h.request("s1", "lease.release", epoch = first)
        val second = h.take("s1")
        assertTrue(second != first)
        h.down("s1", KeyUsage.A, first)
        h.frame("s1", HidFrame.MouseMove(first, 5, 5))
        assertTrue(h.hidHost.sent.isEmpty())
        h.down("s1", KeyUsage.A, second)
        assertEquals(1, h.hidHost.sent.size)
        assertEquals(
            second,
            h.hidHost.sent
                .single()
                .epoch,
        )
    }

    @Test
    fun `a device result arriving after its session ended is dropped, and the next op may run`() {
        h.connect("s1")
        h.connect("s2")
        val epoch = h.take("s1")
        val apply = mapOf("slots" to JsonArray(listOf(slot(0, h.device.images[0].imageId))), "ifRev" to num(rev("s1")))
        h.request("s1", "slots.apply", apply, epoch, id = "a1")
        h.closed("s1")
        val sentBefore = h.sent.size
        h.handle(RemoteEvent.DeviceResult(h.deviceCalls.single().opId, DeviceOutcome.Applied))
        assertEquals(sentBefore, h.sent.size)
        h.setDevice(h.device.copy(slots = listOf(RemoteSlot(0, h.device.images[0].path, LunMode.CDROM, SlotIntent.EXPORTED))))
        val epoch2 = h.take("s2")
        assertNull(h.request("s2", "slot.eject", mapOf("lun" to num(0)), epoch2))
        assertEquals(2, h.deviceCalls.size)
    }

    @Test
    fun `a half-open connection that Go declares dead mid-input is released and its late frames ignored`() {
        h.connect("s1")
        val epoch = h.take("s1")
        h.down("s1", KeyUsage.LEFT_SHIFT)
        h.down("s1", KeyUsage.A)
        h.frame("s1", HidFrame.MouseButtons(epoch, 1))
        h.closed("s1")
        assertFalse(h.hidHost.anyHeld)
        val sent = h.hidHost.sent.size
        h.down("s1", KeyUsage.B, epoch)
        h.command("s1", """{"id":9,"t":"lease.take"}""")
        h.closed("s1")
        assertEquals(sent, h.hidHost.sent.size)
        assertNull(h.controller.leaseView.first)
    }

    @Test
    fun `random bytes are either an exact frame or close the session`() =
        runBlocking<Unit> {
            checkAll(500, Arb.byteArray(Arb.int(0..6), Arb.byte())) { bytes ->
                val decoded = HidFrameCodec.decode(bytes)
                if (decoded != null) assertArrayEquals(canonicalFrame(bytes), HidFrameCodec.encode(decoded))
            }
            var index = 0
            checkAll(100, Arb.byteArray(Arb.int(0..6), Arb.byte())) { bytes ->
                val name = "f${index++}"
                h.connect(name)
                h.take(name)
                h.handle(RemoteEvent.Hid(h.id(name), bytes))
                val closed = h.closeReasons(name)
                assertEquals(if (HidFrameCodec.decode(bytes) == null) listOf(CloseReason.MALFORMED) else emptyList(), closed)
                if (closed.isEmpty()) h.closed(name, "test")
            }
        }

    @Test
    fun `a slow consumer is disconnected and loses the lease`() {
        h.connect("s1")
        h.connect("s2")
        h.take("s1")
        h.down("s1", KeyUsage.A)
        h.slowSessions += "s1"
        h.setDevice(h.device.copy(images = h.device.images.take(1)))
        h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
        assertEquals(listOf(CloseReason.SLOW_CONSUMER), h.closeReasons("s1"))
        assertNull(h.controller.leaseView.first)
        assertFalse(h.hidHost.anyHeld)
    }

    @Test
    fun `a restart keeps approvals and tokens, drops sessions, pending nodes and codes`() {
        h.connect("s1", "n1", allowRw = true)
        h.take("s1")
        h.open("pending", h.tailnet("stranger"))
        h.handle(RemoteEvent.PairingEnable)
        val tokenHash =
            h
                .pair(
                    "p1",
                    h.controller
                        .status()
                        .pairing.code!!,
                ).second
        h.handle(RemoteEvent.PairingEnable)
        val code =
            h.controller
                .status()
                .pairing.code!!
        h.restart()
        val status = h.controller.status()
        assertTrue(status.sessions.isEmpty() && status.pending.isEmpty())
        assertNull(status.leaseHolder)
        assertNull(status.pairing.code)
        h.connect("s1b", "n1")
        assertTrue("s1b" in h.authorized)
        val welcome = h.pushes("s1b", "welcome").single()
        assertEquals("true", welcome.getValue("allowRw").jsonPrimitive.content)
        h.open("t1", ConnIdentity.Token(tokenHash))
        assertTrue("t1" in h.authorized)
        assertEquals("pairing_disabled", h.pair("p2", code).first.errorCode)
    }

    @Test
    fun `pairing attempts survive a restart and lock out on the 11th`() {
        h.handle(RemoteEvent.PairingEnable)
        val firstCode =
            h.controller
                .status()
                .pairing.code!!
        val wrong = if (firstCode == "000000") "000001" else "000000"
        repeat(9) { assertEquals("bad_code", h.pair("w$it", wrong).first.errorCode) }
        h.restart()
        h.handle(RemoteEvent.PairingEnable)
        val code =
            h.controller
                .status()
                .pairing.code!!
        assertEquals("bad_code", h.pair("x1", if (code == "000000") "000001" else "000000").first.errorCode)
        assertEquals("pairing_locked", h.pair("x2", code).first.errorCode)
        h.restart()
        assertTrue(
            h.controller
                .status()
                .pairing.lockedOut,
        )
    }

    @Test
    fun `duplicate request ids never re-run a mutation and are scoped per session`() {
        h.connect("s1")
        h.connect("s2")
        val epoch = h.take("s1")
        val apply = mapOf("slots" to JsonArray(listOf(slot(0, h.device.images[0].imageId))), "ifRev" to num(rev("s1")))
        h.request("s1", "slots.apply", apply, epoch, id = "same")
        h.request("s1", "slots.apply", apply, epoch, id = "same")
        h.handle(RemoteEvent.DeviceResult(h.deviceCalls.single().opId, DeviceOutcome.Applied))
        val reply = h.request("s1", "slots.apply", apply, epoch, id = "same")!!
        assertTrue(reply.ok)
        assertEquals(1, h.deviceCalls.size)
        assertEquals("lease_required", h.request("s2", "slots.apply", apply, epoch, id = "same")!!.errorCode)
    }

    @Test
    fun `numeric and string request ids are echoed exactly`() {
        h.connect("s1")
        h.command("s1", """{"id":7,"t":"state.get"}""")
        h.command("s1", """{"id":"7","t":"lease.take"}""")
        val replies =
            h.sent
                .filter { it.first == "s1" }
                .map { RemoteHarness.parse(it.second) }
                .filter { "ok" in it }
        assertEquals(listOf("7", "\"7\""), replies.map { it.getValue("id").toString() })
        assertTrue(replies.all { it.ok })
    }

    /** [bytes] as the decoder normalizes it: motion and scroll are symmetric, so a -128 byte reads as -127. */
    private fun canonicalFrame(bytes: ByteArray): ByteArray {
        val op = HidFrameCodec.opOf(bytes)
        if (op != HidFrameCodec.OP_MOUSE_MOVE && op != HidFrameCodec.OP_SCROLL) return bytes
        return bytes.copyOf().also { frame ->
            for (index in DELTA_BYTE_INDICES) if (frame[index] == Byte.MIN_VALUE) frame[index] = (Byte.MIN_VALUE + 1).toByte()
        }
    }

    private companion object {
        /** The two signed bytes after the 3-byte header of a move or scroll frame. */
        val DELTA_BYTE_INDICES = 3..4
    }
}
