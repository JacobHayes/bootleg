package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.MouseLayout
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.hid.KeyboardReport
import dev.jacobhayes.bootleg.core.hid.Modifier
import dev.jacobhayes.bootleg.core.hid.MouseReport
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.bool
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.num
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private const val DAY_MS = 24L * 60 * 60 * 1000

fun RemoteHarness.down(
    sessionId: String,
    usage: KeyUsage,
    epoch: Int = epoch(),
) = frame(sessionId, HidFrame.KeyDown(epoch, usage))

fun RemoteHarness.up(
    sessionId: String,
    usage: KeyUsage,
    epoch: Int = epoch(),
) = frame(sessionId, HidFrame.KeyUp(epoch, usage))

fun RemoteHarness.closeReasons(sessionId: String): List<CloseReason> = closes.filter { it.sessionId == id(sessionId) }.map { it.reason }

fun slot(
    lun: Int,
    imageId: String,
    mode: String? = null,
): JsonObject =
    buildJsonObject {
        put("lun", lun)
        put("imageId", imageId)
        if (mode != null) put("mode", mode)
    }

class RemoteControllerTest {
    private val h = RemoteHarness()
    private val isoId get() = h.device.images[0].imageId
    private val imgId get() = h.device.images[1].imageId

    @Nested
    inner class Auth {
        @Test
        fun `an unknown tailnet node is pending and gets no state and no control`() {
            h.open("s1", h.tailnet("n1"))
            h.hello("s1")
            assertFalse("s1" in h.authorized)
            val welcome = h.pushes("s1", "welcome").single()
            assertEquals("pending", welcome["auth"]!!.jsonPrimitive.content)
            assertNull(welcome["stateRev"])
            assertNull(welcome["lease"])
            assertEquals("pending", h.request("s1", "state.get")!!.errorCode)
            assertEquals("pending", h.request("s1", "lease.take")!!.errorCode)
            assertTrue(h.pushes("s1", "state").isEmpty())
            assertEquals(listOf("n1"), h.pendingNotified.map { it.nodeId })
        }

        @Test
        fun `approving in the app authorizes live sessions and persists a 180 day record`() {
            h.open("s1", h.tailnet("n1"))
            h.hello("s1")
            h.approve("n1", allowRw = true)
            assertTrue("s1" in h.authorized)
            val auth = h.pushes("s1", "auth").single()
            assertEquals("approved", auth["auth"]!!.jsonPrimitive.content)
            assertTrue(auth["allowRw"]!!.jsonPrimitive.boolean)
            assertEquals(1, h.pushes("s1", "state").size)
            val record = h.approvalStore.records.single()
            assertEquals(h.clock.wall + RemoteLimits.APPROVAL_LIFETIME_MS, record.expiresAtMs)
            assertTrue(h.pendingNotified.isEmpty())
            assertTrue(h.request("s1", "state.get")!!.ok)
        }

        @Test
        fun `approve ignores nodes that never asked`() {
            h.approve("stranger")
            assertTrue(h.approvalStore.records.isEmpty())
        }

        @Test
        fun `at most 4 pending nodes, the oldest is evicted and its sessions closed`() {
            for (index in 1..4) h.open("s$index", h.tailnet("n$index"))
            h.closed("s2", "dead")
            h.open("s5", h.tailnet("n5"))
            assertEquals(listOf(CloseReason.PENDING_EVICTED), h.closeReasons("s1"))
            assertEquals(
                listOf("n2", "n3", "n4", "n5"),
                h.controller
                    .status()
                    .pending
                    .map { it.nodeId },
            )
        }

        @Test
        fun `at most 4 pending connections and 8 in total`() {
            for (index in 1..4) h.open("p$index", h.tailnet("pending$index"))
            h.open("p5", h.tailnet("pending5"))
            assertEquals(listOf(CloseReason.TOO_MANY_CONNECTIONS), h.closeReasons("p5"))
            for (index in 1..4) h.approve("pending$index")
            for (index in 1..4) h.open("a$index", h.tailnet("pending$index"))
            assertEquals(
                8,
                h.controller
                    .status()
                    .sessions.size,
            )
            h.open("a5", h.tailnet("pending1"))
            assertEquals(listOf(CloseReason.TOO_MANY_CONNECTIONS), h.closeReasons("a5"))
        }

        @Test
        fun `approvals expire after 180 days and close their sessions`() {
            h.connect("s1", "n1")
            h.advance(RemoteLimits.APPROVAL_LIFETIME_MS - 1)
            assertTrue(h.closeReasons("s1").isEmpty())
            h.advance(1)
            assertEquals(listOf(CloseReason.EXPIRED), h.closeReasons("s1"))
            assertTrue(h.approvalStore.records.isEmpty())
            h.open("s2", h.tailnet("n1"))
            assertFalse("s2" in h.authorized)
        }

        @Test
        fun `revoke closes every session of the node and releases its lease in one step`() {
            h.connect("s1", "n1")
            h.open("s2", h.tailnet("n1"))
            h.hello("s2")
            h.take("s1")
            h.down("s1", KeyUsage.A)
            assertTrue(h.hidHost.anyHeld)
            val effects = h.handle(RemoteEvent.Revoke(Principal.Node("n1")))
            assertTrue(RemoteEffect.Close(h.id("s1"), CloseReason.REVOKED) in effects)
            assertTrue(RemoteEffect.Close(h.id("s2"), CloseReason.REVOKED) in effects)
            assertTrue(RemoteEffect.Revoke(Principal.Node("n1")) in effects)
            assertTrue(RemoteEffect.HidReleaseAll in effects)
            assertFalse(h.hidHost.anyHeld)
            assertNull(h.controller.leaseView.first)
            assertTrue(h.approvalStore.records.isEmpty())
            assertEquals(listOf("node:n1"), h.revoked)
        }

        @Test
        fun `deny closes pending sessions and forgets the node`() {
            h.open("s1", h.tailnet("n1"))
            h.handle(RemoteEvent.Deny("n1"))
            assertEquals(listOf(CloseReason.DENIED), h.closeReasons("s1"))
            assertTrue(h.pendingNotified.isEmpty())
        }

        @Test
        fun `RW permission is per approval and announced to live sessions`() {
            h.connect("s1", "n1")
            h.take("s1")
            val refused =
                h.request(
                    "s1",
                    "slots.apply",
                    mapOf(
                        "slots" to JsonArray(listOf(slot(0, imgId, "disk_rw"))),
                        "ifRev" to num(rev()),
                    ),
                    h.epoch(),
                )
            assertEquals("rw_not_allowed", refused!!.errorCode)
            h.handle(RemoteEvent.SetAllowRw(Principal.Node("n1"), true))
            assertTrue(
                h
                    .pushes("s1", "auth")
                    .last()["allowRw"]!!
                    .jsonPrimitive.boolean,
            )
            val accepted =
                h.request(
                    "s1",
                    "slots.apply",
                    mapOf(
                        "slots" to JsonArray(listOf(slot(0, imgId, "disk_rw"))),
                        "ifRev" to num(rev()),
                    ),
                    h.epoch(),
                )
            assertNull(accepted)
            assertEquals(
                LunMode.DISK_RW,
                (h.deviceCalls.single().op as DeviceOp.Apply)
                    .desired.slots
                    .single()
                    .mode,
            )
        }
    }

    @Nested
    inner class Handshake {
        @Test
        fun `welcome carries session, auth, state rev and lease`() {
            h.connect("s1")
            val welcome = h.pushes("s1", "welcome").single()
            assertEquals(1, welcome["v"]!!.jsonPrimitive.int)
            assertEquals(h.id("s1"), welcome["sessionId"]!!.jsonPrimitive.long)
            assertEquals("approved", welcome["auth"]!!.jsonPrimitive.content)
            assertNotNull(welcome["stateRev"])
            assertEquals(h.epoch(), welcome["lease"]!!.jsonObject["epoch"]!!.jsonPrimitive.int)
            assertEquals(1, h.pushes("s1", "state").size)
        }

        @Test
        fun `a version mismatch closes with 4400`() {
            h.open("s1", h.tailnet("n1"))
            h.command("s1", """{"t":"hello","v":2}""")
            assertEquals(listOf(CloseReason.VERSION), h.closeReasons("s1"))
            assertEquals(4400, CloseReason.VERSION.webSocketCode)
        }

        @Test
        fun `requests before hello get hello_required`() {
            h.open("s1", h.tailnet("n1"))
            h.approve("n1")
            assertEquals("hello_required", h.request("s1", "state.get")!!.errorCode)
        }

        @Test
        fun `malformed messages close the session, bad bodies get bad_request`() {
            h.connect("s1")
            assertEquals("unknown_type", h.request("s1", "disk.format")!!.errorCode)
            assertEquals("bad_request", h.request("s1", "slot.eject", mapOf("lun" to str("zero")))!!.errorCode)
            assertEquals("bad_request", h.request("s1", "state.get", mapOf("extra" to num(1)))!!.errorCode)
            assertEquals("bad_request", h.request("s1", "lease.release", epoch = 70_000)!!.errorCode)
            h.command("s1", """{"t":"state.get"}""")
            assertEquals(listOf(CloseReason.MALFORMED), h.closeReasons("s1"))
            h.connect("s2")
            h.command("s2", "not json")
            assertEquals(listOf(CloseReason.MALFORMED), h.closeReasons("s2"))
            h.connect("s3")
            h.hello("s3")
            assertEquals(listOf(CloseReason.MALFORMED), h.closeReasons("s3"))
        }

        @Test
        fun `a malformed connection identity is refused`() {
            val malformed =
                listOf(
                    """{"transport":"tailnet","identity":{"kind":"node","nodeId":""}}""",
                    """{"transport":"carrier-pigeon","identity":{"kind":"node","nodeId":"n"}}""",
                    """{"transport":"hotspot","identity":{"kind":"node","nodeId":"n"}}""",
                    """{"transport":"tailnet","identity":{"kind":"token","tokenHash":"${"a".repeat(64)}"}}""",
                    """{"transport":"hotspot","identity":{"kind":"token","tokenHash":"XYZ"}}""",
                    """{"transport":"hotspot","identity":{"kind":"wizard"}}""",
                    "[]",
                )
            for ((index, connJson) in malformed.withIndex()) {
                h.handle(RemoteEvent.Open(h.id("bad$index"), connJson))
                assertEquals(listOf(CloseReason.MALFORMED), h.closeReasons("bad$index")) { connJson }
            }
            assertTrue(
                h.controller
                    .status()
                    .sessions
                    .isEmpty(),
            )
        }
    }

    @Nested
    inner class Lease {
        @Test
        fun `take only when free`() {
            h.connect("s1")
            h.connect("s2")
            val epoch = h.take("s1")
            assertEquals(epoch, h.take("s1"))
            assertEquals("lease_held", h.request("s2", "lease.take")!!.errorCode)
            assertEquals(h.holder("s1"), h.controller.leaseView.first)
            assertEquals(h.id("s1"), h.lastLease("s2")["holder"]!!.jsonPrimitive.long)
        }

        @Test
        fun `mutations need the lease and the current epoch`() {
            h.connect("s1")
            h.connect("s2")
            assertEquals(
                "lease_required",
                h.request("s1", "hid.set", mapOf("keyboard" to bool(true), "mouse" to bool(true)), 0)!!.errorCode,
            )
            val epoch = h.take("s1")
            assertEquals(
                "lease_required",
                h.request("s2", "hid.set", mapOf("keyboard" to bool(true), "mouse" to bool(true)), epoch)!!.errorCode,
            )
            assertEquals("stale_epoch", h.request("s1", "hid.set", mapOf("keyboard" to bool(true), "mouse" to bool(true)))!!.errorCode)
            assertEquals(
                "stale_epoch",
                h.request("s1", "hid.set", mapOf("keyboard" to bool(true), "mouse" to bool(true)), epoch - 1)!!.errorCode,
            )
            assertNull(h.request("s1", "hid.set", mapOf("keyboard" to bool(true), "mouse" to bool(false)), epoch))
            assertEquals(DeviceOp.SetHid(HidSpec(keyboard = true, mouse = false)), h.deviceCalls.single().op)
        }

        @Test
        fun `hid set keeps the phone's mouse layout`() {
            h.setDevice(h.device.copy(hid = HidSpec(keyboard = true, mouse = true, mouseLayout = MouseLayout.BOOT)))
            h.connect("s1")
            val epoch = h.take("s1")
            assertNull(h.request("s1", "hid.set", mapOf("keyboard" to bool(false), "mouse" to bool(true)), epoch))
            assertEquals(
                DeviceOp.SetHid(HidSpec(keyboard = false, mouse = true, mouseLayout = MouseLayout.BOOT)),
                h.deviceCalls.single().op,
            )
        }

        @Test
        fun `tether set is a lease-holder mutation the phone decides`() {
            h.connect("s1")
            h.connect("s2")
            assertEquals("lease_required", h.request("s1", "tether.set", mapOf("on" to bool(true)), 0)!!.errorCode)
            val epoch = h.take("s1")
            assertEquals("lease_required", h.request("s2", "tether.set", mapOf("on" to bool(true)), epoch)!!.errorCode)
            // Exported slots don't refuse it here: root checks the live LUNs, which can be ahead of this snapshot.
            h.setDevice(h.device.copy(slots = listOf(RemoteSlot(0, h.device.images[0].path, LunMode.CDROM, SlotIntent.EXPORTED))))
            assertNull(h.request("s1", "tether.set", mapOf("on" to bool(true)), epoch, id = "t1"))
            assertEquals(DeviceOp.SetUsbTethering(on = true), h.deviceCalls.single().op)
            assertEquals("busy", h.request("s1", "tether.set", mapOf("on" to bool(false)), epoch)!!.errorCode)
            h.handle(RemoteEvent.DeviceResult(h.deviceCalls.single().opId, DeviceOutcome.DisksExposed("eject first")))
            assertEquals("disks_exposed", h.replyFor("s1", "t1")!!.errorCode)
        }

        @Test
        fun `tether results carry the state, a warning and the longer deadline`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.request("s1", "tether.set", mapOf("on" to bool(true)), epoch, id = "t1")
            h.handle(
                RemoteEvent.DeviceResult(
                    h.deviceCalls.last().opId,
                    DeviceOutcome.Tethering(active = true, warning = "drives need attention"),
                ),
            )
            val reply = h.replyFor("s1", "t1")!!
            assertTrue(reply.ok, "$reply")
            assertEquals(
                true,
                reply
                    .getValue("tether")
                    .jsonObject
                    .getValue("on")
                    .jsonPrimitive.boolean,
            )
            assertEquals("drives need attention", reply.getValue("warning").jsonPrimitive.content)
            h.request("s1", "tether.set", mapOf("on" to bool(false)), epoch, id = "t2")
            h.handle(RemoteEvent.DeviceResult(h.deviceCalls.last().opId, DeviceOutcome.TimedOut))
            val timedOut = h.replyFor("s1", "t2")!!
            assertEquals("timeout", timedOut.errorCode)
            assertTrue("${RemoteLimits.TETHER_OP_TIMEOUT_MS}" in timedOut.toString(), "$timedOut")
        }

        @Test
        fun `state shows tethering only once Android reported it`() {
            h.connect("s1")
            val unknown = h.request("s1", "state.get")!!.getValue("state").jsonObject
            assertNull(unknown["tether"])
            h.setDevice(h.device.copy(usbTethering = true))
            val known = h.request("s1", "state.get")!!.getValue("state").jsonObject
            assertEquals(
                true,
                known
                    .getValue("tether")
                    .jsonObject
                    .getValue("on")
                    .jsonPrimitive.boolean,
            )
        }

        @Test
        fun `release frees the lease and bumps the epoch`() {
            h.connect("s1")
            val epoch = h.take("s1")
            val reply = h.request("s1", "lease.release", epoch = epoch)!!
            assertTrue(reply.ok)
            assertEquals(epoch + 1, reply["epoch"]!!.jsonPrimitive.int)
            assertNull(h.controller.leaseView.first)
        }

        @Test
        fun `takeover prompts the holder and moves the lease only on consent`() {
            h.connect("s1")
            h.connect("s2")
            val epoch = h.take("s1")
            assertNull(h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("s1"))), epoch, id = "t1"))
            val prompt = h.pushes("s1", "lease.takeover_request").single()
            assertEquals(h.id("s2"), prompt["requester"]!!.jsonPrimitive.long)
            assertEquals(h.holder("s1"), h.controller.leaseView.first)
            h.runFor(RemoteLimits.TAKEOVER_PROMPT_TIMEOUT_MS - 1_000, stepMs = 1_000)
            assertEquals(h.holder("s1"), h.controller.leaseView.first)
            assertTrue(h.request("s1", "lease.takeover_reply", mapOf("allow" to bool(true)), epoch)!!.ok)
            assertEquals(h.holder("s2"), h.controller.leaseView.first)
            val granted = h.replyFor("s2", "t1")!!
            assertTrue(granted.ok)
            assertEquals(h.epoch(), granted["epoch"]!!.jsonPrimitive.int)
        }

        @Test
        fun `a declined or unanswered takeover leaves the lease where it is`() {
            h.connect("s1")
            h.connect("s2")
            val epoch = h.take("s1")
            h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("s1"))), epoch, id = "t1")
            h.request("s1", "lease.takeover_reply", mapOf("allow" to bool(false)), epoch)
            assertEquals("takeover_declined", h.replyFor("s2", "t1")!!.errorCode)
            h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("s1"))), epoch, id = "t2")
            h.advance(RemoteLimits.TAKEOVER_PROMPT_TIMEOUT_MS)
            assertEquals("takeover_declined", h.replyFor("s2", "t2")!!.errorCode)
            assertEquals(1, h.pushes("s1", "lease.takeover_cancelled").size)
            assertEquals(h.holder("s1") to epoch, h.controller.leaseView)
        }

        @Test
        fun `takeover moves the lease when the holder dies`() {
            h.connect("s1")
            h.connect("s2")
            val epoch = h.take("s1")
            h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("s1"))), epoch, id = "t1")
            h.closed("s1", "dead")
            assertEquals(h.holder("s2"), h.controller.leaseView.first)
            assertTrue(h.replyFor("s2", "t1")!!.ok)
        }

        @Test
        fun `takeover naming the wrong holder or epoch is refused`() {
            h.connect("s1")
            h.connect("s2")
            h.connect("s3")
            val epoch = h.take("s1")
            val wrongEpoch = h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("s1"))), epoch - 1)!!
            assertEquals("lease_changed", wrongEpoch.errorCode)
            val wrongHolder = h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("s3"))), epoch)!!
            assertEquals("lease_changed", wrongHolder.errorCode)
            h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("s1"))), epoch)
            assertEquals("busy", h.request("s3", "lease.takeover", mapOf("holderSession" to num(h.id("s1"))), epoch)!!.errorCode)
            assertEquals("lease_required", h.request("s3", "lease.takeover_reply", mapOf("allow" to bool(true)), epoch)!!.errorCode)
        }

        @Test
        fun `a requester that leaves cancels its takeover`() {
            h.connect("s1")
            h.connect("s2")
            val epoch = h.take("s1")
            h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("s1"))), epoch)
            h.closed("s2", "client_closed")
            assertEquals(1, h.pushes("s1", "lease.takeover_cancelled").size)
            assertEquals("not_found", h.request("s1", "lease.takeover_reply", mapOf("allow" to bool(true)), epoch)!!.errorCode)
            assertEquals(h.holder("s1") to epoch, h.controller.leaseView)
        }

        @Test
        fun `local capture preempts the remote lease and blocks take and takeover`() {
            h.connect("s1")
            h.connect("s2")
            val epoch = h.take("s1")
            h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("s1"))), epoch, id = "t1")
            h.down("s1", KeyUsage.A)
            h.handle(RemoteEvent.LocalCaptureStarted)
            assertEquals(LeaseHolder.Local, h.controller.leaseView.first)
            assertFalse(h.hidHost.anyHeld)
            assertEquals("local_priority", h.replyFor("s2", "t1")!!.errorCode)
            assertEquals("local_priority", h.request("s2", "lease.take")!!.errorCode)
            val local = h.epoch()
            assertEquals(
                "local_priority",
                h.request("s2", "lease.takeover", mapOf("holderSession" to num(h.id("local"))), local)!!.errorCode,
            )
            h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
            assertTrue(h.lastLease("s2")["local"]!!.jsonPrimitive.boolean)
            assertNull(h.lastLease("s2")["holder"])
            h.handle(RemoteEvent.LocalCaptureStopped)
            assertNull(h.controller.leaseView.first)
            h.take("s2")
        }

        @Test
        fun `the epoch is 16 bits and wraps`() {
            repeat(RemoteLimits.LEASE_EPOCH_MODULUS / 2) {
                h.handle(RemoteEvent.LocalCaptureStarted)
                h.handle(RemoteEvent.LocalCaptureStopped)
            }
            assertEquals(0, h.epoch())
            h.connect("s1")
            assertEquals(1, h.take("s1"))
        }
    }

    @Nested
    inner class EndOfSession {
        @Test
        fun `bump epoch, cancel producers, drop queued input and release-all, dispatched release first`() {
            h.connect("s1")
            val epoch = h.take("s1")
            val prepared = h.request("s1", "paste.prepare", mapOf("text" to str("hello world")), epoch)!!
            h.request("s1", "paste.run", mapOf("planId" to prepared["planId"]!!), epoch)
            h.runFor(16)
            val effects = h.closed("s1", "dead")
            val leaseChanged = effects.indexOfFirst { it is RemoteEffect.LeaseChanged }
            val dropped = effects.indexOf(RemoteEffect.DropQueuedInput(h.id("s1")))
            val released = effects.indexOf(RemoteEffect.HidReleaseAll)
            // The epoch bump and job cancel already happened in the controller; the release is dispatched
            // before any port call that could fail.
            assertEquals(0, released) { "$effects" }
            assertTrue(leaseChanged in 0 until dropped) { "$effects" }
            assertTrue(effects.none { it is RemoteEffect.HidSend })
            assertEquals(null to epoch + 1, h.controller.leaseView)
            assertNull(h.controller.status().runningJob)
        }

        @Test
        fun `input is refused until the release barrier clears`() {
            h.autoDeliverRelease = false
            h.connect("s1")
            h.connect("s2")
            h.take("s1")
            h.down("s1", KeyUsage.A)
            h.closed("s1", "dead")
            h.take("s2")
            val sentBefore = h.hidHost.sent.size
            h.down("s2", KeyUsage.B)
            assertEquals(sentBefore, h.hidHost.sent.size)
            h.handle(RemoteEvent.HidReleaseDelivered)
            h.down("s2", KeyUsage.B)
            assertEquals(KeyboardReport(0, listOf(KeyUsage.B)), h.hidHost.keyboard)
        }

        @Test
        fun `release retries back off for 60 s, then the state reports release_pending`() {
            h.autoDeliverRelease = false
            h.connect("s1")
            h.connect("s2")
            h.take("s1")
            h.down("s1", KeyUsage.A)
            h.closed("s1", "dead")
            val releasesBefore = h.hidHost.releaseAllCount
            h.runFor(RemoteLimits.RELEASE_RETRY_WINDOW_MS + 10_000, stepMs = 100)
            // Retries at +1, +3, +7, +15, +31 s; the next (+63 s) is past the window.
            assertEquals(5, h.hidHost.releaseAllCount - releasesBefore)
            assertEquals("release_pending", hidStatus("s2"))
            assertTrue(h.controller.status().releasePending)
            val epoch = h.take("s2")
            assertEquals(
                "release_pending",
                h.request("s2", "bootkey.run", mapOf("vendor" to str("dell"), "action" to str("menu")), epoch)!!.errorCode,
            )
            h.handle(RemoteEvent.HidReleaseDelivered)
            h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
            assertNull(hidStatus("s2"))
        }

        @Test
        fun `turning HID off clears the barrier`() {
            h.autoDeliverRelease = false
            h.connect("s1")
            h.take("s1")
            h.closed("s1", "dead")
            assertTrue(h.controller.releaseBarrierActive)
            h.setDevice(h.device.copy(hid = HidSpec()))
            assertFalse(h.controller.releaseBarrierActive)
        }

        @Test
        fun `the state carries the HID link while HID is on`() {
            h.connect("s1")
            for (status in listOf(HidLinkStatus.OPEN, HidLinkStatus.RECONNECTING, HidLinkStatus.OPEN, HidLinkStatus.RESTART_REQUIRED)) {
                h.setDevice(h.device.copy(hid = HidSpec(keyboard = true, mouse = true), hidLink = status))
                h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
                assertEquals(status.wireName, hidField("s1", "link"))
            }
            h.setDevice(h.device.copy(hid = HidSpec(), hidLink = HidLinkStatus.STOPPED))
            h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
            assertNull(hidField("s1", "link"))
        }

        @Test
        fun `the state lists the endpoints the computer isn't reading while the link is open`() {
            h.connect("s1")
            val on = HidSpec(keyboard = true, mouse = true)

            fun notReading(): List<String>? =
                h
                    .pushes("s1", "state")
                    .last()
                    .getValue("hid")
                    .jsonObject["notReading"]
                    ?.jsonArray
                    ?.map { it.jsonPrimitive.content }

            h.setDevice(h.device.copy(hid = on, hidLink = HidLinkStatus.OPEN, hidNotReading = setOf(HidKind.MOUSE)))
            h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
            assertEquals(listOf("mouse"), notReading())
            h.setDevice(h.device.copy(hidNotReading = setOf(HidKind.MOUSE, HidKind.KEYBOARD)))
            h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
            assertEquals(listOf("keyboard", "mouse"), notReading())
            h.setDevice(h.device.copy(hidNotReading = emptySet()))
            h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
            assertNull(notReading())
            // Only meaningful while the link is open.
            h.setDevice(h.device.copy(hidLink = HidLinkStatus.RECONNECTING, hidNotReading = setOf(HidKind.MOUSE)))
            h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
            assertNull(notReading())
        }
    }

    @Nested
    inner class Idle {
        @Test
        fun `30 s idle releases held keys and keeps the lease, 10 min idle releases the lease`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.down("s1", KeyUsage.LEFT_SHIFT)
            h.frame("s1", HidFrame.MouseButtons(epoch, 1))
            h.advance(RemoteLimits.IDLE_KEY_RELEASE_MS - 1)
            assertTrue(h.hidHost.anyHeld)
            h.advance(1)
            assertFalse(h.hidHost.anyHeld)
            assertEquals(h.holder("s1") to epoch, h.controller.leaseView)
            h.advance(RemoteLimits.IDLE_LEASE_RELEASE_MS - RemoteLimits.IDLE_KEY_RELEASE_MS)
            assertNull(h.controller.leaseView.first)
        }

        @Test
        fun `input resets the idle timer`() {
            h.connect("s1")
            h.take("s1")
            repeat(11) {
                h.advance(60_000)
                h.frame("s1", HidFrame.MouseMove(h.epoch(), 1, -1))
            }
            assertEquals(h.holder("s1"), h.controller.leaseView.first)
        }
    }

    @Nested
    inner class Frames {
        @Test
        fun `a valid usage Bootleg has no key for is ignored, not fatal`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.frame("s1", HidFrame.KeyDown(epoch, 0xA5))
            assertTrue(h.closeReasons("s1").isEmpty())
            assertTrue(h.hidHost.sent.isEmpty())
        }

        @Test
        fun `duplicate key down is idempotent and more than six keys roll over`() {
            h.connect("s1")
            h.take("s1")
            h.down("s1", KeyUsage.A)
            h.down("s1", KeyUsage.A)
            assertEquals(1, h.hidHost.sent.size)
            h.down("s1", KeyUsage.LEFT_CONTROL)
            assertEquals(KeyboardReport(Modifier.LEFT_CONTROL.bit, listOf(KeyUsage.A)), h.hidHost.keyboard)
            for (usage in listOf(KeyUsage.B, KeyUsage.C, KeyUsage.D, KeyUsage.E, KeyUsage.F)) h.down("s1", usage)
            assertEquals(
                KeyboardReport(Modifier.LEFT_CONTROL.bit, listOf(KeyUsage.A, KeyUsage.B, KeyUsage.C, KeyUsage.D, KeyUsage.E, KeyUsage.F)),
                h.hidHost.keyboard,
            )
            h.down("s1", KeyUsage.G)
            assertTrue(h.hidHost.keyboard.isRollover)
            h.up("s1", KeyUsage.G)
            assertFalse(h.hidHost.keyboard.isRollover)
            for (usage in listOf(
                KeyUsage.A,
                KeyUsage.B,
                KeyUsage.C,
                KeyUsage.D,
                KeyUsage.E,
                KeyUsage.F,
                KeyUsage.LEFT_CONTROL,
            )) {
                h.up("s1", usage)
            }
            assertFalse(h.hidHost.anyHeld)
        }

        @Test
        fun `mouse frames move and press buttons`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.frame("s1", HidFrame.MouseButtons(epoch, 0b101))
            h.frame("s1", HidFrame.MouseMove(epoch, -127, 127))
            assertEquals(
                MouseReport(0b101, -127, 127),
                MouseReport.decode(
                    h.hidHost.sent
                        .last()
                        .report,
                ),
            )
            h.frame("s1", HidFrame.MouseMove(epoch, 0, 0))
            assertEquals(2, h.hidHost.sent.size)
        }

        @Test
        fun `scroll frames send a wheel report that keeps the held buttons`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.frame("s1", HidFrame.MouseButtons(epoch, 0b001))
            h.frame("s1", HidFrame.Scroll(epoch, -3, 2))
            assertEquals(
                MouseReport(0b001, wheel = -3, pan = 2),
                MouseReport.decode(
                    h.hidHost.sent
                        .last()
                        .report,
                ),
            )
            h.frame("s1", HidFrame.Scroll(epoch, 0, 0))
            assertEquals(2, h.hidHost.sent.size)
            assertEquals(true, hidBooleanField("s1", "wheel"))
        }

        @Test
        fun `scroll frames are ignored, not malformed, with the compatible mouse or the mouse off`() {
            h.connect("s1")
            val epoch = h.take("s1")
            for (hid in listOf(
                HidSpec(keyboard = true, mouse = true, mouseLayout = MouseLayout.BOOT),
                HidSpec(keyboard = true, mouse = false),
            )) {
                h.setDevice(h.device.copy(hid = hid))
                h.frame("s1", HidFrame.Scroll(epoch, 1, 0))
                assertTrue(h.hidHost.sent.isEmpty())
                assertEquals(false, hidBooleanField("s1", "wheel"))
            }
            // The session is still open: a later frame still works.
            h.down("s1", KeyUsage.A)
            assertEquals(listOf(HidKind.KEYBOARD), h.hidHost.sent.map { it.kind })
        }

        @Test
        fun `frames from non-holders or stale epochs never reach HID and refresh the lease push`() {
            h.connect("s1")
            h.connect("s2")
            val epoch = h.take("s1")
            h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
            val leasePushes = h.pushes("s2", "lease").size
            h.down("s2", KeyUsage.A, epoch)
            h.down("s1", KeyUsage.A, epoch - 1)
            assertTrue(h.hidHost.sent.isEmpty())
            assertEquals(leasePushes + 1, h.pushes("s2", "lease").size)
        }

        @Test
        fun `HID kinds that are off are ignored`() {
            h.setDevice(h.device.copy(hid = HidSpec(keyboard = false, mouse = true)))
            h.connect("s1")
            val epoch = h.take("s1")
            h.down("s1", KeyUsage.A)
            h.frame("s1", HidFrame.MouseMove(epoch, 1, 1))
            assertEquals(listOf(HidKind.MOUSE), h.hidHost.sent.map { it.kind })
        }

        @Test
        fun `200 frames per second, release-all exempt`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.down("s1", KeyUsage.A)
            repeat(RemoteLimits.HID_FRAME_RATE_PER_SECOND_MAX + 50) { h.frame("s1", HidFrame.MouseMove(epoch, 1, 0)) }
            assertEquals(RemoteLimits.HID_FRAME_RATE_PER_SECOND_MAX, h.hidHost.sent.size)
            h.frame("s1", HidFrame.MouseButtons(epoch, 1))
            assertEquals(0, h.hidHost.mouseButtons)
            h.frame("s1", HidFrame.ReleaseAll(epoch))
            assertFalse(h.hidHost.anyHeld)
            assertEquals(RemoteLimits.HID_FRAME_RATE_PER_SECOND_MAX + 1, h.hidHost.sent.size)
            h.advance(100)
            h.frame("s1", HidFrame.MouseMove(epoch, 1, 0))
            assertEquals(RemoteLimits.HID_FRAME_RATE_PER_SECOND_MAX + 2, h.hidHost.sent.size)
        }

        @Test
        fun `release-all is holder-only and coalesced`() {
            h.connect("s1")
            h.connect("s2")
            val epoch = h.take("s1")
            h.down("s1", KeyUsage.A)
            h.frame("s2", HidFrame.ReleaseAll(epoch))
            assertTrue(h.hidHost.anyHeld)
            h.frame("s1", HidFrame.ReleaseAll(epoch))
            h.frame("s1", HidFrame.ReleaseAll(epoch))
            assertFalse(h.hidHost.anyHeld)
            assertEquals(2, h.hidHost.sent.size)
        }

        @Test
        fun `malformed frames close the session`() {
            val malformed =
                listOf(
                    byteArrayOf(0, 1, 1),
                    byteArrayOf(0, 1, 1, 4, 0),
                    byteArrayOf(0, 1, 3, 1),
                    byteArrayOf(0, 1, 5, 0),
                    byteArrayOf(0, 1, 9, 0),
                    byteArrayOf(0, 1, 1, 0),
                    byteArrayOf(0, 1, 1, 1),
                    byteArrayOf(0, 1, 1, 0xE8.toByte()),
                    byteArrayOf(0, 1, 2, 0x03),
                    byteArrayOf(0, 1, 4, 8),
                    byteArrayOf(0, 1),
                    byteArrayOf(),
                )
            for ((index, frame) in malformed.withIndex()) {
                val sessionId = "m$index"
                h.connect(sessionId)
                h.handle(RemoteEvent.Hid(h.id(sessionId), frame))
                assertEquals(listOf(CloseReason.MALFORMED), h.closeReasons(sessionId)) { "frame $index" }
            }
            assertTrue(h.hidHost.sent.isEmpty())
        }
    }

    @Nested
    inner class Requests {
        @Test
        fun `duplicate request ids get the cached reply without re-running`() {
            h.connect("s1")
            val first = h.request("s1", "lease.take", id = "dup")!!
            h.request("s1", "lease.release", epoch = h.epoch())
            val again = h.request("s1", "lease.take", id = "dup")!!
            assertEquals(first, again)
            assertNull(h.controller.leaseView.first)
        }

        @Test
        fun `the reply cache keeps the last 64 ids`() {
            h.connect("s1")
            h.request("s1", "lease.take", id = "old")
            repeat(RemoteLimits.REQUEST_ID_CACHE_SIZE) { h.request("s1", "state.get", id = "q$it") }
            h.request("s1", "lease.release", epoch = h.epoch(), id = "rel")
            val rerun = h.request("s1", "lease.take", id = "old")!!
            assertTrue(rerun.ok)
            assertEquals(h.holder("s1"), h.controller.leaseView.first)
        }

        @Test
        fun `a duplicate of an in-flight request is answered once when it completes`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.request("s1", "slot.eject", mapOf("lun" to num(0)), epoch, id = "e1")
            h.setDevice(h.device.copy(slots = listOf(RemoteSlot(0, h.device.images[0].path, LunMode.CDROM, SlotIntent.EXPORTED))))
            h.request("s1", "slot.reinsert", mapOf("lun" to num(0)), epoch, id = "e2")
            assertNull(h.request("s1", "slot.reinsert", mapOf("lun" to num(0)), epoch, id = "e2"))
            assertEquals(1, h.deviceCalls.size)
            h.handle(RemoteEvent.DeviceResult(h.deviceCalls.single().opId, DeviceOutcome.Applied))
            assertEquals(1, h.sent.count { it.first == "s1" && it.second.contains("\"id\":\"e2\"") })
        }

        @Test
        fun `an ISO too big for a CD-ROM defaults to a read-only disk, and asking for a CD-ROM is refused`() {
            val big = RemoteHarness.image("/data/media/0/bootleg/omarchy.iso", 6_200_000_000L)
            h.setDevice(h.device.copy(images = h.device.images + big))
            h.connect("s1")
            val epoch = h.take("s1")
            assertEquals(LunMode.DISK_RO, big.defaultMode)
            val refused =
                h.request(
                    "s1",
                    "slots.apply",
                    mapOf("slots" to JsonArray(listOf(slot(0, big.imageId, "cdrom"))), "ifRev" to num(rev())),
                    epoch,
                )
            assertEquals("cdrom_too_large", refused!!.errorCode)
            assertTrue(h.deviceCalls.isEmpty())
            assertNull(
                h.request("s1", "slots.apply", mapOf("slots" to JsonArray(listOf(slot(0, big.imageId))), "ifRev" to num(rev())), epoch),
            )
            val spec = (h.deviceCalls.single().op as DeviceOp.Apply).desired.slots.single()
            assertEquals(LunMode.DISK_RO, spec.mode)
            assertTrue(spec.auto)
        }

        @Test
        fun `slots apply resolves image ids, defaults modes and checks ifRev atomically`() {
            h.connect("s1")
            h.connect("s2")
            val epoch = h.take("s1")
            val rev = rev()
            val slots = JsonArray(listOf(slot(1, imgId), slot(0, isoId)))
            assertNull(h.request("s1", "slots.apply", mapOf("slots" to slots, "ifRev" to num(rev)), epoch, id = "a1"))
            val desired = (h.deviceCalls.single().op as DeviceOp.Apply).desired
            assertEquals(listOf(h.device.images[0].path, h.device.images[1].path), desired.slots.map { it.backingPath })
            assertEquals(listOf(LunMode.CDROM, LunMode.DISK_RO), desired.slots.map { it.mode })
            assertEquals(h.device.hid, desired.hid)
            assertEquals("busy", h.request("s1", "slots.apply", mapOf("slots" to slots, "ifRev" to num(rev)), epoch)!!.errorCode)
            h.handle(RemoteEvent.DeviceResult(h.deviceCalls.single().opId, DeviceOutcome.Partial(mapOf(1 to "EBUSY"))))
            assertEquals(
                "EBUSY",
                h
                    .replyFor("s1", "a1")!!
                    ["slotFailures"]!!
                    .jsonObject["1"]!!
                    .jsonPrimitive.content,
            )
            h.setDevice(h.device.copy(slots = listOf(RemoteSlot(0, h.device.images[0].path, LunMode.CDROM, SlotIntent.EXPORTED))))
            assertEquals("rev_conflict", h.request("s1", "slots.apply", mapOf("slots" to slots, "ifRev" to num(rev)), epoch)!!.errorCode)
            val missing = JsonArray(listOf(slot(0, "/data/media/0/bootleg/debian.iso")))
            assertEquals("not_found", h.request("s1", "slots.apply", mapOf("slots" to missing, "ifRev" to num(rev())), epoch)!!.errorCode)
            val gap = JsonArray(listOf(slot(1, isoId)))
            assertEquals("bad_request", h.request("s1", "slots.apply", mapOf("slots" to gap, "ifRev" to num(rev())), epoch)!!.errorCode)
            h.advance(RemoteLimits.PUSH_INTERVAL_MS_MIN)
            val stateSlots = h.pushes("s2", "state").last()["slots"]!!.jsonArray
            assertEquals(
                isoId,
                stateSlots
                    .single()
                    .jsonObject["imageId"]!!
                    .jsonPrimitive.content,
            )
            assertFalse(h.sent.any { it.second.contains("/data/media") }) { "paths must never reach clients" }
        }

        @Test
        fun `device failures and timeouts become error replies`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.request("s1", "hid.set", mapOf("keyboard" to bool(false), "mouse" to bool(false)), epoch, id = "h1")
            h.handle(RemoteEvent.DeviceResult(h.deviceCalls.last().opId, DeviceOutcome.TimedOut))
            assertEquals("timeout", h.replyFor("s1", "h1")!!.errorCode)
            h.request("s1", "hid.set", mapOf("keyboard" to bool(false), "mouse" to bool(false)), epoch, id = "h2")
            h.handle(RemoteEvent.DeviceResult(h.deviceCalls.last().opId, DeviceOutcome.Failed("EIO")))
            assertEquals("device", h.replyFor("s1", "h2")!!.errorCode)
            h.handle(RemoteEvent.DeviceResult(h.deviceCalls.last().opId, DeviceOutcome.Applied))
            assertEquals(1, h.sent.count { it.second.contains("\"id\":\"h2\"") })
        }
    }

    @Nested
    inner class Paste {
        private fun prepare(
            text: String,
            epoch: Int = h.epoch(),
        ): JsonObject = h.request("s1", "paste.prepare", mapOf("text" to str(text)), epoch)!!

        @Test
        fun `prepare then run types the text at 8 ms per report and ends released`() {
            h.connect("s1")
            val epoch = h.take("s1")
            val prepared = prepare("Hi! é")
            assertEquals(1, prepared["unsupportedCount"]!!.jsonPrimitive.int)
            assertEquals(
                8L * 2 * 4,
                prepared["durationMs"]!!
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertNull(h.request("s1", "paste.run", mapOf("planId" to prepared["planId"]!!), epoch, id = "run"))
            h.runFor(48)
            assertNull(h.replyFor("s1", "run"))
            h.runFor(8)
            assertEquals("Hi! ", h.hidHost.typedText)
            assertFalse(h.hidHost.anyHeld)
            val done = h.replyFor("s1", "run")!!
            assertTrue(done.ok)
            assertEquals(64, done["durationMs"]!!.jsonPrimitive.int)
        }

        @Test
        fun `plans are bounded, single use, expire after 2 min and are bound to the epoch`() {
            h.connect("s1")
            val epoch = h.take("s1")
            val plans = (1..RemoteLimits.PASTE_PLAN_COUNT_MAX).map { prepare("p$it")["planId"]!! }
            assertEquals("too_many_plans", prepare("one more").errorCode)
            assertNull(h.request("s1", "paste.run", mapOf("planId" to plans[0]), epoch))
            h.runFor(200)
            assertEquals("not_found", h.request("s1", "paste.run", mapOf("planId" to plans[0]), epoch)!!.errorCode)
            h.advance(RemoteLimits.PASTE_PLAN_LIFETIME_MS)
            assertEquals("not_found", h.request("s1", "paste.run", mapOf("planId" to plans[1]), epoch)!!.errorCode)
            val fresh = prepare("fresh")["planId"]!!
            h.request("s1", "lease.release", epoch = epoch)
            val newEpoch = h.take("s1")
            assertEquals("not_found", h.request("s1", "paste.run", mapOf("planId" to fresh), newEpoch)!!.errorCode)
        }

        @Test
        fun `pastes over 64 KiB or 5 minutes are refused`() {
            h.connect("s1")
            h.take("s1")
            assertEquals("too_large", prepare("é".repeat(RemoteLimits.PASTE_SIZE_BYTES_MAX / 2 + 1)).errorCode)
            val tooLongChars = (RemoteLimits.PASTE_DURATION_MS_MAX / RemoteLimits.PASTE_REPORT_INTERVAL_MS / 2 + 1).toInt()
            assertEquals("too_long", prepare("a".repeat(tooLongChars)).errorCode)
            assertTrue(prepare("a".repeat(tooLongChars - 1)).ok)
        }

        @Test
        fun `cancel stops typing and releases on the priority lane`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.request("s1", "paste.run", mapOf("planId" to prepare("abcdefghij")["planId"]!!), epoch, id = "run")
            h.runFor(24)
            val typed = h.hidHost.sent.size
            val cancel = h.request("s1", "paste.cancel", epoch = epoch)!!
            assertTrue(cancel["cancelled"]!!.jsonPrimitive.boolean)
            h.runFor(500)
            assertEquals(typed, h.hidHost.sent.size)
            assertEquals("cancelled", h.replyFor("s1", "run")!!.errorCode)
            assertTrue(h.hidHost.releaseAllCount > 0)
        }

        @Test
        fun `keyboard frames are ignored while a job types`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.request("s1", "paste.run", mapOf("planId" to prepare("abc")["planId"]!!), epoch)
            h.down("s1", KeyUsage.Z)
            h.runFor(200)
            assertEquals("abc", h.hidHost.typedText)
        }
    }

    @Nested
    inner class BootKeys {
        @Test
        fun `mashes the vendor key for the duration and ends released`() {
            h.connect("s1")
            val epoch = h.take("s1")
            val reply =
                h.request(
                    "s1",
                    "bootkey.run",
                    mapOf(
                        "vendor" to str("dell"),
                        "action" to str("menu"),
                        "durationMs" to num(2_000),
                    ),
                    epoch,
                    id = "boot",
                )
            assertNull(reply)
            h.runFor(3_000, stepMs = 10)
            assertTrue(h.replyFor("s1", "boot")!!.ok)
            val presses =
                h.hidHost.sent
                    .map { KeyboardReport.decode(it.report) }
                    .filter { !it.isReleaseAll }
            assertEquals(20, presses.size)
            assertTrue(presses.all { it.keys == listOf(KeyUsage.F12) })
            assertFalse(h.hidHost.anyHeld)
        }

        @Test
        fun `holds a modifier with re-sends, refuses unsupported keys and unknown vendors`() {
            h.connect("s1")
            val epoch = h.take("s1")
            h.request(
                "s1",
                "bootkey.run",
                mapOf("vendor" to str("apple_mac"), "action" to str("menu"), "durationMs" to num(1_000)),
                epoch,
            )
            h.runFor(500, stepMs = 10)
            assertEquals(3, h.hidHost.sent.size)
            assertEquals(Modifier.LEFT_ALT.bit, h.hidHost.keyboard.modifierBits)
            assertEquals(
                "busy",
                h.request("s1", "bootkey.run", mapOf("vendor" to str("dell"), "action" to str("setup")), epoch)!!.errorCode,
            )
            h.request("s1", "bootkey.cancel", epoch = epoch)
            assertFalse(h.hidHost.anyHeld)
            val surface = h.request("s1", "bootkey.run", mapOf("vendor" to str("microsoft_surface"), "action" to str("setup")), epoch)!!
            assertEquals("unsupported", surface.errorCode)
            assertEquals(
                "bad_request",
                h.request("s1", "bootkey.run", mapOf("vendor" to str("../etc"), "action" to str("setup")), epoch)!!.errorCode,
            )
            assertEquals(
                "not_found",
                h
                    .request(
                        "s1",
                        "bootkey.run",
                        mapOf(
                            "vendor" to str("dell"),
                            "action" to str("setup"),
                            "keyIndex" to num(5),
                        ),
                        epoch,
                    )!!
                    .errorCode,
            )
        }
    }

    @Nested
    inner class Pairing {
        private fun code(): String =
            checkNotNull(
                h.controller
                    .status()
                    .pairing.code,
            )

        @Test
        fun `a 6-digit code pairs once and keeps Go's token hash, which then authorizes`() {
            h.handle(RemoteEvent.PairingEnable)
            val code = code()
            assertTrue(Regex("[0-9]{6}").matches(code))
            val (verdict, tokenHash) = h.pair("p1", code, userAgent = "bootleg-cli/1")
            assertEquals("""{"ok":true}""", verdict.toString())
            assertTrue(h.closeReasons("p1").isEmpty()) { "Go closes the exchange itself" }
            val stored =
                h.pairingStore.record.tokens
                    .single()
            assertEquals(tokenHash, stored.sha256)
            assertEquals("bootleg-cli/1", stored.label)
            assertEquals(h.clock.wall + RemoteLimits.TOKEN_LIFETIME_MS, stored.expiresAtMs)
            assertNull(
                h.controller
                    .status()
                    .pairing.code,
            )
            assertEquals("pairing_disabled", h.pair("p2", code).first.errorCode)
            h.open("t1", ConnIdentity.Token(tokenHash))
            assertTrue("t1" in h.authorized)
            h.open("t2", ConnIdentity.Token(sha256Hex("never paired")))
            assertEquals(listOf(CloseReason.UNAUTHORIZED), h.closeReasons("t2"))
        }

        @Test
        fun `an exchange gets one attempt, its own token hash, and only on a pair session`() {
            h.handle(RemoteEvent.PairingEnable)
            val code = code()
            val hash = sha256Hex("minted")
            h.open("p1", ConnIdentity.Pairing(hash))
            h.command("p1", """{"t":"pair","code":"000000","tokenHash":"$hash"}""")
            h.command("p1", """{"t":"pair","code":"$code","tokenHash":"$hash"}""")
            val verdicts = h.sent.filter { it.first == "p1" }.map { RemoteHarness.parse(it.second) }
            assertEquals(
                listOf("bad_code", "bad_request").takeIf { code != "000000" } ?: verdicts.map { it.errorCode },
                verdicts.map { it.errorCode },
            )
            h.open("p2", ConnIdentity.Pairing(hash))
            h.command("p2", """{"t":"pair","code":"$code","tokenHash":"${sha256Hex("other")}"}""")
            assertEquals("bad_request", RemoteHarness.parse(h.sent.last().second).errorCode)
            h.connect("s1")
            h.command("s1", """{"t":"pair","code":"$code","tokenHash":"$hash"}""")
            assertEquals(listOf(CloseReason.MALFORMED), h.closeReasons("s1"))
            assertTrue(
                h.pairingStore.record.tokens
                    .isEmpty(),
            )
        }

        @Test
        fun `codes expire after 2 minutes and die with pairing or the hotspot`() {
            h.handle(RemoteEvent.PairingEnable)
            val first = code()
            h.advance(RemoteLimits.PAIRING_CODE_LIFETIME_MS)
            assertEquals("pairing_disabled", h.pair("p1", first).first.errorCode)
            h.handle(RemoteEvent.PairingEnable)
            val second = code()
            h.handle(RemoteEvent.PairingDisable)
            assertEquals("pairing_disabled", h.pair("p2", second).first.errorCode)
            h.handle(RemoteEvent.PairingEnable)
            val third = code()
            h.open("p3", ConnIdentity.Pairing(sha256Hex("in flight")))
            h.handle(RemoteEvent.HotspotStopped)
            assertEquals(listOf(CloseReason.LISTENER_STOPPED), h.closeReasons("p3"))
            assertEquals("pairing_disabled", h.pair("p4", third).first.errorCode)
        }

        @Test
        fun `10 attempts an hour lock pairing until reset on the phone, across restarts`() {
            h.handle(RemoteEvent.PairingEnable)
            val right = code()
            val wrong = if (right == "000000") "000001" else "000000"
            repeat(RemoteLimits.PAIRING_ATTEMPT_COUNT_MAX) { assertEquals("bad_code", h.pair("w$it", wrong).first.errorCode) }
            assertEquals("pairing_locked", h.pair("p1", right).first.errorCode)
            assertTrue(h.pairingStore.record.lockedOut)
            h.advance(RemoteLimits.PAIRING_ATTEMPT_WINDOW_MS * 2)
            h.restart()
            h.handle(RemoteEvent.PairingEnable)
            assertNull(
                h.controller
                    .status()
                    .pairing.code,
            )
            assertEquals("pairing_locked", h.pair("p2", "123456").first.errorCode)
            h.handle(RemoteEvent.PairingLockoutReset)
            h.handle(RemoteEvent.PairingEnable)
            assertTrue(h.pair("p3", code()).first.ok)
        }

        @Test
        fun `attempts older than an hour do not count`() {
            h.handle(RemoteEvent.PairingEnable)
            repeat(RemoteLimits.PAIRING_ATTEMPT_COUNT_MAX - 1) { h.pair("a$it", "abc") }
            h.advance(RemoteLimits.PAIRING_ATTEMPT_WINDOW_MS)
            h.handle(RemoteEvent.PairingEnable)
            repeat(RemoteLimits.PAIRING_ATTEMPT_COUNT_MAX - 1) { assertEquals("bad_code", h.pair("b$it", "abc").first.errorCode) }
            assertTrue(h.pair("c", code()).first.ok)
        }

        @Test
        fun `tokens expire after 90 days and are revocable`() {
            h.handle(RemoteEvent.PairingEnable)
            val hash = h.pair("p1", code()).second
            h.open("t1", ConnIdentity.Token(hash))
            h.handle(RemoteEvent.Revoke(Principal.Token(hash)))
            assertEquals(listOf(CloseReason.REVOKED), h.closeReasons("t1"))
            assertEquals(listOf("token:$hash"), h.revoked)
            assertTrue(
                h.pairingStore.record.tokens
                    .isEmpty(),
            )

            h.handle(RemoteEvent.PairingEnable)
            val second = h.pair("p2", code()).second
            h.open("t2", ConnIdentity.Token(second))
            h.advance(RemoteLimits.TOKEN_LIFETIME_MS)
            assertEquals(listOf(CloseReason.EXPIRED), h.closeReasons("t2"))
            assertTrue(
                h.pairingStore.record.tokens
                    .isEmpty(),
            )
        }
    }

    @Test
    fun `state pushes are coalesced to at most 10 per second`() {
        h.connect("s1")
        val before = h.pushes("s1", "state").size
        repeat(50) { index ->
            h.setDevice(h.device.copy(images = h.device.images.take(1) + RemoteHarness.image("/data/media/0/bootleg/x$index.img", 1)))
            h.clock.advance(10)
        }
        h.runFor(200, stepMs = 10)
        val pushes = h.pushes("s1", "state").size - before
        assertTrue(pushes in 4..7) { "$pushes pushes in 0.7 s" }
        assertTrue(
            h
                .pushes("s1", "state")
                .last()["images"]!!
                .jsonArray
                .any { it.jsonObject["name"]!!.jsonPrimitive.content == "x49.img" },
        )
    }

    private fun hidStatus(name: String): String? = hidField(name, "status")

    private fun hidField(
        name: String,
        field: String,
    ): String? =
        h
            .pushes(name, "state")
            .last()
            .getValue("hid")
            .jsonObject[field]
            ?.jsonPrimitive
            ?.content

    private fun hidBooleanField(
        name: String,
        field: String,
    ): Boolean? =
        checkNotNull(h.request(name, "state.get"))["state"]!!
            .jsonObject
            .getValue("hid")
            .jsonObject[field]
            ?.jsonPrimitive
            ?.boolean

    private fun rev(): Long = checkNotNull(h.request("s1", "state.get"))["state"]!!.jsonObject.long("rev")
}
