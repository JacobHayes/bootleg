package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RemoteActorTest {
    /** Throws on the [armAt]-th port call (0-based) after [arm], once. */
    private class PortFault {
        private val calls = AtomicInteger()

        @Volatile private var armAt = -1

        fun arm(position: Int) {
            calls.set(0)
            armAt = position
        }

        fun hit(port: String) {
            if (armAt < 0) return
            if (calls.getAndIncrement() == armAt) {
                armAt = -1
                throw IllegalStateException("injected $port failure")
            }
        }
    }

    private class FakeTransport(
        private val fault: PortFault,
    ) : TransportPort {
        val authorized = CopyOnWriteArrayList<Long>()
        val sent = CopyOnWriteArrayList<Pair<Long, String>>()
        val closed = CopyOnWriteArrayList<Pair<Long, CloseReason>>()

        @Volatile var accepting = true

        override fun authorized(sessionId: Long) {
            fault.hit("authorized")
            authorized += sessionId
        }

        override fun send(
            sessionId: Long,
            json: String,
        ): Boolean {
            fault.hit("send")
            if (!accepting) return false
            sent += sessionId to json
            return true
        }

        override fun close(
            sessionId: Long,
            reason: CloseReason,
        ) {
            fault.hit("close")
            closed += sessionId to reason
        }

        override fun revoke(principal: Principal) = fault.hit("revoke")
    }

    private class FakeDevice : DevicePort {
        val hang = CompletableDeferred<ApplyResult>()
        val calls = ConcurrentLinkedQueue<String>()
        val running = AtomicInteger()
        val runningMax = AtomicInteger()
        override val snapshots = MutableStateFlow(DeviceSnapshot(hid = HidSpec(keyboard = true, mouse = true)))

        private suspend fun run(
            call: String,
            body: suspend () -> ApplyResult,
        ): ApplyResult {
            runningMax.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try {
                calls += call
                return body()
            } finally {
                running.decrementAndGet()
            }
        }

        override suspend fun apply(desired: Desired): ApplyResult = run("apply") { hang.await() }

        override suspend fun eject(
            lun: Int,
            force: Boolean,
        ): ApplyResult = run("eject $lun") { hang.await() }

        override suspend fun reinsert(lun: Int): ApplyResult = run("reinsert $lun") { hang.await() }

        override suspend fun setHid(spec: HidSpec): ApplyResult =
            run("setHid $spec") { ApplyResult.Applied(GadgetState("udc", emptySet(), emptyMap(), emptyMap()), reenumerated = true) }
    }

    private class FakeHid(
        private val fault: PortFault,
    ) : HidPort {
        val reports = CopyOnWriteArrayList<Pair<HidKind, ByteArray>>()
        val releases = AtomicInteger()

        /** The host holds nothing: the last call that got through was a release-all. */
        @Volatile var released = true

        @Volatile override var releaseOwed = false

        override fun send(
            kind: HidKind,
            report: ByteArray,
        ): HidSendResult {
            fault.hit("hid.send")
            reports += kind to report
            released = false
            return HidSendResult.ACCEPTED
        }

        override fun releaseAll() {
            fault.hit("hid.releaseAll")
            releases.incrementAndGet()
            released = true
            releaseOwed = true
        }
    }

    private class Rig(
        deviceDispatcher: CoroutineDispatcher,
    ) {
        val fault = PortFault()
        val fatal = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        val transport = FakeTransport(fault)
        val device = FakeDevice()
        val hid = FakeHid(fault)
        val approvals = InMemoryApprovalStore()
        val pairing = InMemoryPairingStore()
        val notified = CopyOnWriteArrayList<List<PendingApproval>>()

        val actor =
            RemoteActor(
                RemoteController(SystemRemoteClock, SecureRandomSource(), approvals.load(), pairing.load()),
                SystemRemoteClock,
                transport,
                device,
                hid,
                object : ApprovalStore {
                    override fun load() = approvals.load()

                    override fun save(records: List<ApprovalRecord>) {
                        fault.hit("approvals.save")
                        approvals.save(records)
                    }
                },
                pairing,
                {
                    fault.hit("notifier")
                    notified += it
                },
                scope,
                deviceDispatcher,
                onFatal = { fatal.set(it) },
                deviceTimeoutMs = 100,
            )

        fun connect(sessionId: Long) {
            actor.open(
                sessionId,
                ConnInfo.encode(ConnInfo("tailnet", "100.64.0.$sessionId:1", ConnIdentity.Node("node-$sessionId", name = "s$sessionId"))),
            )
            awaitUntil("pending") { notified.lastOrNull()?.any { it.nodeId == "node-$sessionId" } == true }
            actor.approve("node-$sessionId", allowRw = false)
            awaitUntil("authorized") { sessionId in transport.authorized }
            actor.command(sessionId, """{"id":0,"t":"hello","v":1}""")
            awaitUntil("welcome") { transport.sent.any { it.first == sessionId && it.second.contains("\"t\":\"welcome\"") } }
        }

        fun connectAndTake(sessionId: Long): Int {
            connect(sessionId)
            actor.command(sessionId, """{"id":"take","t":"lease.take"}""")
            awaitUntil("lease") { actor.status.value.leaseHolder == LeaseHolder.Remote(sessionId) }
            return actor.status.value.leaseEpoch
        }

        fun sentTo(
            sessionId: Long,
            text: String,
        ): Boolean = transport.sent.any { it.first == sessionId && it.second.contains(text) }
    }

    // A parallel dispatcher: the actor alone must keep device calls to one at a time.
    private val rig = Rig(Dispatchers.IO)
    private val actor = rig.actor
    private val transport = rig.transport
    private val device = rig.device
    private val hid = rig.hid

    @AfterEach
    fun tearDown() = rig.scope.cancel()

    @Test
    fun `bridge calls flow through the controller to the ports`() {
        actor.start()
        val epoch = rig.connectAndTake(1L)
        assertTrue(transport.sent.any { it.second.contains("\"t\":\"welcome\"") })
        assertTrue(actor.hid(1L, HidFrameCodec.encode(HidFrame.KeyDown(epoch, KeyUsage.A))))
        awaitUntil("report") { hid.reports.isNotEmpty() }
        assertEquals(HidKind.KEYBOARD, hid.reports.single().first)
        assertEquals(
            "node-1",
            rig.approvals.records
                .single()
                .nodeId,
        )
    }

    @Test
    fun `device calls run off the actor, time out without blocking it, and never overlap`() {
        actor.start()
        val epoch = rig.connectAndTake(1L)
        actor.command(1L, """{"id":"h","t":"hid.set","epoch":$epoch,"keyboard":true,"mouse":false}""")
        awaitUntil("hid.set reply") { rig.sentTo(1L, "\"id\":\"h\",\"ok\":true") }
        assertEquals(listOf("setHid ${HidSpec(keyboard = true, mouse = false)}"), device.calls.toList())
        device.snapshots.value = device.snapshots.value.copy(slots = listOf(RemoteSlot(0, "/x.img", LunMode.DISK_RO, SlotIntent.EXPORTED)))
        awaitUntil("snapshot") { transport.sent.any { it.second.contains("x.img") } }
        // This call hangs: the actor keeps answering, then the call times out.
        actor.command(1L, """{"id":"r","t":"slot.reinsert","epoch":$epoch,"lun":0}""")
        actor.command(1L, """{"id":"g","t":"state.get"}""")
        awaitUntil("actor still answers") { rig.sentTo(1L, "\"id\":\"g\"") }
        awaitUntil("timeout reply") { rig.sentTo(1L, "\"id\":\"r\"") && rig.sentTo(1L, "\"timeout\"") }
        // The timed-out call still runs on the phone, so the next one is refused rather than started beside it.
        actor.command(1L, """{"id":"h2","t":"hid.set","epoch":$epoch,"keyboard":true,"mouse":true}""")
        awaitUntil("busy reply") { transport.sent.any { it.second.contains("\"id\":\"h2\"") && it.second.contains("\"busy\"") } }
        assertEquals(2, device.calls.size)
        device.hang.complete(ApplyResult.Applied(GadgetState("udc", emptySet(), emptyMap(), emptyMap()), reenumerated = false))
        // The lane frees once the hung call returns; until then each retry is refused, never started.
        var retry = 0
        awaitUntil("next op runs") {
            actor.command(1L, """{"id":"retry$retry","t":"hid.set","epoch":$epoch,"keyboard":true,"mouse":true}""")
            retry++
            Thread.sleep(10)
            transport.sent.any { it.second.contains(""""id":"retry""") && it.second.contains("\"ok\":true") }
        }
        assertEquals(1, device.runningMax.get())
    }

    @Test
    fun `inbound queues are bounded per session, release-all and close always get through`() {
        // Queue everything before the actor runs, so the bounds are hit deterministically.
        actor.open(1L, ConnInfo.encode(ConnInfo("tailnet", "100.64.0.1:1", ConnIdentity.Node("node-1"))))
        actor.approve("node-1", allowRw = false)
        actor.command(1L, """{"id":0,"t":"hello","v":1}""")
        repeat(RemoteLimits.INBOUND_QUEUE_LENGTH_MAX - 1) { actor.command(1L, """{"id":"$it","t":"state.get"}""") }
        assertFalse(actor.hid(1L, HidFrameCodec.encode(HidFrame.KeyDown(0, KeyUsage.A))))
        assertTrue(actor.hid(1L, HidFrameCodec.encode(HidFrame.ReleaseAll(0))))
        actor.command(1L, """{"id":"over","t":"state.get"}""")
        repeat(RemoteLimits.PRIORITY_QUEUE_LENGTH_MAX) { actor.approve("n$it", allowRw = false) }
        assertFalse(actor.approve("one-more", allowRw = false))
        actor.closed(2L, "dead")
        actor.closed(2L, "dead")
        actor.start()
        awaitUntil("the overflowing session is closed") { transport.closed.contains(1L to CloseReason.RATE_LIMITED) }
    }

    @Test
    fun `a slow consumer is closed`() {
        actor.start()
        rig.connectAndTake(1L)
        transport.accepting = false
        actor.command(1L, """{"id":"g","t":"state.get"}""")
        awaitUntil("closed") { transport.closed.contains(1L to CloseReason.SLOW_CONSUMER) }
        awaitUntil("lease freed") { actor.status.value.leaseHolder == null }
    }

    @Test
    fun `input waits for the HID session's owed release, polled by the actor`() {
        actor.start()
        rig.connectAndTake(1L)
        actor.closed(1L, "dead")
        awaitUntil("released") { hid.releases.get() == 1 }
        val epoch = rig.connectAndTake(2L)
        actor.hid(2L, HidFrameCodec.encode(HidFrame.KeyDown(epoch, KeyUsage.B)))
        Thread.sleep(50)
        assertTrue(hid.reports.isEmpty())
        hid.releaseOwed = false
        var usage = 0
        val keys = listOf(KeyUsage.C, KeyUsage.D, KeyUsage.E, KeyUsage.F, KeyUsage.G)
        awaitUntil("input flows once the barrier clears") {
            actor.hid(2L, HidFrameCodec.encode(HidFrame.KeyDown(epoch, keys[usage++ % keys.size])))
            Thread.sleep(5)
            hid.reports.isNotEmpty()
        }
    }

    private enum class Ending { REVOKE, TAKEOVER, CLOSE }

    @Test
    fun `a port failing anywhere while a holder's session ends still releases keys and closes every session`() {
        rig.scope.cancel()
        val cases = Ending.entries.flatMap { ending -> (0 until FAULT_POSITION_COUNT).map { ending to it } }
        // Each case has its own actor and ports; running them side by side keeps the test fast.
        val failures = ConcurrentLinkedQueue<String>()
        val fatalCount = AtomicInteger()
        cases.parallelStream().forEach { (ending, position) ->
            try {
                if (endWithFault(ending, position)) fatalCount.incrementAndGet()
            } catch (failure: Throwable) {
                failures += "$ending, port call $position failing: $failure"
            }
        }
        assertTrue(failures.isEmpty()) { failures.joinToString("\n") }
        assertTrue(fatalCount.get() >= Ending.entries.size) { "faults never hit the ending step" }
    }

    /** True if the injected fault failed the actor closed. */
    private fun endWithFault(
        ending: Ending,
        position: Int,
    ): Boolean {
        val rig = Rig(Dispatchers.IO.limitedParallelism(1))
        try {
            rig.actor.start()
            val epoch = rig.connectAndTake(1L)
            rig.connect(2L)
            rig.actor.hid(1L, HidFrameCodec.encode(HidFrame.KeyDown(epoch, KeyUsage.A)))
            awaitUntil("key held") { !rig.hid.released }
            if (ending == Ending.TAKEOVER) {
                rig.actor.command(2L, """{"id":"t","t":"lease.takeover","epoch":$epoch,"holderSession":1}""")
                awaitUntil("prompt") { rig.sentTo(1L, "lease.takeover_request") }
            }
            // Let coalesced pushes go out, so the armed step is the one that ends the holder's session.
            Thread.sleep(PUSH_SETTLE_MS)
            rig.fault.arm(position)
            when (ending) {
                Ending.REVOKE -> rig.actor.revoke(Principal.Node("node-1"))
                Ending.TAKEOVER -> rig.actor.command(1L, """{"id":"a","t":"lease.takeover_reply","epoch":$epoch,"allow":true}""")
                Ending.CLOSE -> rig.actor.closed(1L, "dead")
            }
            awaitUntil("the session ends or the actor fails closed") {
                rig.fatal.get() != null || rig.actor.status.value.leaseHolder != LeaseHolder.Remote(1L)
            }
            Thread.sleep(PUSH_SETTLE_MS)
            assertTrue(rig.hid.released) { "keys still held on the host" }
            if (rig.fatal.get() != null) {
                assertTrue(rig.transport.closed.contains(2L to CloseReason.SHUTDOWN)) { "session 2 left open: ${rig.transport.closed}" }
                if (ending != Ending.CLOSE) {
                    assertTrue(rig.transport.closed.any { it.first == 1L }) { "session 1 left open: ${rig.transport.closed}" }
                }
                rig.actor.open(3L, ConnInfo.encode(ConnInfo("tailnet", "100.64.0.3:1", ConnIdentity.Node("node-3"))))
                assertTrue(rig.transport.closed.contains(3L to CloseReason.SHUTDOWN)) { "a failed actor accepted a session" }
            } else {
                assertTrue(position > 0) { "the first port call of the step failed without failing closed" }
                when (ending) {
                    Ending.REVOKE -> assertTrue(rig.transport.closed.contains(1L to CloseReason.REVOKED))
                    Ending.TAKEOVER -> assertEquals(LeaseHolder.Remote(2L), rig.actor.status.value.leaseHolder)
                    Ending.CLOSE -> assertNull(rig.actor.status.value.leaseHolder)
                }
            }
            return rig.fatal.get() != null
        } finally {
            rig.scope.cancel()
        }
    }

    private companion object {
        const val AWAIT_MS = 2_000L
        const val PUSH_SETTLE_MS = RemoteLimits.PUSH_INTERVAL_MS_MIN + 50

        /** More than the port calls in any of the ending steps, so later positions hit later steps too. */
        const val FAULT_POSITION_COUNT = 8

        fun awaitUntil(
            what: String,
            condition: () -> Boolean,
        ) = runBlocking {
            withTimeout(AWAIT_MS) {
                while (!condition()) delay(2)
            }
            check(condition()) { what }
        }
    }
}
