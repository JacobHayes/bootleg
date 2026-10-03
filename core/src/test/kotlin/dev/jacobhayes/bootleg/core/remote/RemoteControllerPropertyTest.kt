package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.bool
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.num
import dev.jacobhayes.bootleg.core.remote.RemoteHarness.Companion.str
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Random interleavings of Go, app and clock events across many sessions. The harness checks after
 * every effect and step that no effect carries a stale epoch, no non-holder input reaches HID, no
 * keys stay held once the lease moves without a release-all, no pending/revoked/expired identity
 * is authorized, and no bound is exceeded. The controller's own invariants run on every step too.
 */
class RemoteControllerPropertyTest {
    private val usages =
        listOf(
            KeyUsage.A,
            KeyUsage.B,
            KeyUsage.F12,
            KeyUsage.ENTER,
            KeyUsage.LEFT_SHIFT,
            KeyUsage.LEFT_CONTROL,
            KeyUsage.C,
            KeyUsage.D,
            KeyUsage.E,
            KeyUsage.F,
            KeyUsage.G,
        )

    @Test
    fun `random interleavings keep every safety property`() =
        runBlocking<Unit> {
            checkAll(ITERATIONS, Arb.long()) { seed ->
                try {
                    runScenario(seed)
                } catch (failure: Throwable) {
                    throw AssertionError("seed $seed: ${failure.message}", failure)
                }
            }
        }

    private fun runScenario(seed: Long) {
        val random = Random(seed)
        val clock = FakeRemoteClock()
        val approvals =
            InMemoryApprovalStore(
                listOf(
                    ApprovalRecord("n0", "zero", "u", clock.wall, clock.wall + RemoteLimits.APPROVAL_LIFETIME_MS, allowRw = true),
                    ApprovalRecord("n1", "one", "u", clock.wall, clock.wall + RemoteLimits.APPROVAL_LIFETIME_MS),
                    ApprovalRecord("n2", "soon", "u", clock.wall, clock.wall + 20_000),
                ),
            )
        val h = RemoteHarness(clock, approvals, seed = seed, autoDeliverRelease = random.nextBoolean())
        h.handle(RemoteEvent.CatalogChanged(catalog(clock.wall)))
        val sessionIds = (0 until SESSION_ID_COUNT).map { "s$it" }
        val planIds = ArrayList<JsonElement>()
        val tokenHashes = ArrayList<String>()
        val usedIds = ArrayList<String>()
        try {
            steps(h, random, sessionIds, planIds, tokenHashes, usedIds)
        } catch (failure: Throwable) {
            throw AssertionError("${failure.message}\nrecent events:\n${h.recentLog()}", failure)
        }
        windDown(h, sessionIds)
    }

    private fun steps(
        h: RemoteHarness,
        random: Random,
        sessionIds: List<String>,
        planIds: MutableList<JsonElement>,
        tokenHashes: MutableList<String>,
        usedIds: MutableList<String>,
    ) {
        val helloed = HashSet<String>()

        fun hello(sessionId: String) {
            // A second hello is a protocol error; send one now and then to cover that too.
            if (helloed.add(sessionId) || random.nextInt(20) == 0) h.hello(sessionId)
        }

        fun anySession() = sessionIds[random.nextInt(sessionIds.size)]

        // Mostly sessions Go still has open, so requests get past the handshake and auth.
        fun liveSession(): String =
            if (h.live.isNotEmpty() &&
                random.nextInt(5) != 0
            ) {
                h.live.elementAt(random.nextInt(h.live.size))
            } else {
                anySession()
            }

        fun epochGuess(): Int =
            when (random.nextInt(10)) {
                in 0..6 -> h.epoch()
                7 -> (h.epoch() + RemoteLimits.LEASE_EPOCH_MODULUS - 1) % RemoteLimits.LEASE_EPOCH_MODULUS
                8 -> (h.epoch() + 1) % RemoteLimits.LEASE_EPOCH_MODULUS
                else -> random.nextInt(RemoteLimits.LEASE_EPOCH_MODULUS)
            }

        fun holderOrAny(): String =
            (h.controller.leaseView.first as? LeaseHolder.Remote)?.sessionId?.takeIf { random.nextInt(6) != 0 }?.let { h.name(it) }
                ?: liveSession()

        fun request(
            type: String,
            body: Map<String, JsonElement> = emptyMap(),
            epoch: Int? = epochGuess(),
            sessionId: String = holderOrAny(),
        ) = h.request(sessionId, type, body, epoch, id = "q${usedIds.size}").also { usedIds += "q${usedIds.size}" }

        fun entryId(): String =
            if (random.nextInt(8) ==
                0
            ) {
                "0123456789abcdef"
            } else {
                CatalogEntryIds.idFor(DOWNLOAD_FILE_NAMES[random.nextInt(DOWNLOAD_FILE_NAMES.size)])
            }

        repeat(STEP_COUNT) {
            when (random.nextInt(36)) {
                0, 1 -> {
                    val identity =
                        if (random.nextInt(6) == 0) {
                            val known = tokenHashes.isNotEmpty() && random.nextBoolean()
                            ConnIdentity.Token(
                                if (known) tokenHashes[random.nextInt(tokenHashes.size)] else sha256Hex("guess${random.nextInt()}"),
                            )
                        } else {
                            h.tailnet("n${random.nextInt(7)}")
                        }
                    val sessionId = anySession()
                    if (sessionId !in h.live) helloed -= sessionId
                    h.open(sessionId, identity)
                    if (random.nextInt(8) != 0) hello(sessionId)
                    if (identity is ConnIdentity.Node && random.nextInt(10) < 7) h.approve(identity.nodeId, random.nextBoolean())
                }
                2 -> hello(liveSession())
                3 -> if (random.nextBoolean()) h.closed(liveSession(), "dead")
                4 -> request("lease.take", epoch = null, sessionId = liveSession())
                5 -> request("lease.takeover", mapOf("holderSession" to num(h.id(holderOrAny()))), sessionId = liveSession())
                6 -> request("lease.takeover_reply", mapOf("allow" to bool(random.nextBoolean())))
                7 -> if (random.nextBoolean()) request("lease.release") else request("lease.take", epoch = null, sessionId = liveSession())
                8, 9, 10 -> {
                    val usage = usages[random.nextInt(usages.size)]
                    val frame = if (random.nextBoolean()) HidFrame.KeyDown(epochGuess(), usage) else HidFrame.KeyUp(epochGuess(), usage)
                    h.frame(holderOrAny(), frame)
                }
                11 ->
                    h.frame(
                        holderOrAny(),
                        if (random.nextBoolean()) {
                            HidFrame.MouseMove(epochGuess(), random.nextInt(255) - 127, random.nextInt(255) - 127)
                        } else {
                            HidFrame.MouseButtons(epochGuess(), random.nextInt(8))
                        },
                    )
                12 -> h.frame(holderOrAny(), HidFrame.ReleaseAll(epochGuess()))
                13 ->
                    if (random.nextInt(4) ==
                        0
                    ) {
                        h.handle(RemoteEvent.Hid(h.id(anySession()), ByteArray(random.nextInt(6)) { random.nextInt(256).toByte() }))
                    }
                14 ->
                    request("paste.prepare", mapOf("text" to str("ab C\n".repeat(random.nextInt(4) + 1))))?.let { reply ->
                        if (reply.ok) planIds += reply["planId"]!!
                    }
                15 -> if (planIds.isNotEmpty()) request("paste.run", mapOf("planId" to planIds[random.nextInt(planIds.size)]))
                16 -> request(if (random.nextBoolean()) "paste.cancel" else "bootkey.cancel")
                17 ->
                    request(
                        "bootkey.run",
                        mapOf(
                            "vendor" to str(listOf("dell", "apple_mac", "hp", "microsoft_surface")[random.nextInt(4)]),
                            "action" to str("menu"),
                            "durationMs" to num(random.nextInt(3_000) + 1),
                        ),
                    )
                18 ->
                    if (random.nextBoolean()) {
                        val imageId = h.device.images[random.nextInt(h.device.images.size)].imageId
                        val mode = listOf("cdrom", "disk_ro", "disk_rw")[random.nextInt(3)]
                        request(
                            "slots.apply",
                            mapOf("slots" to JsonArray(listOf(slot(0, imageId, mode))), "ifRev" to num(random.nextInt(4))),
                        )
                    } else {
                        request("hid.set", mapOf("keyboard" to bool(random.nextBoolean()), "mouse" to bool(random.nextBoolean())))
                    }
                19 -> {
                    val opId = if (h.deviceCalls.isEmpty()) 1L else h.deviceCalls[random.nextInt(h.deviceCalls.size)].opId
                    val outcome = listOf(DeviceOutcome.Applied, DeviceOutcome.TimedOut, DeviceOutcome.Failed("x"))[random.nextInt(3)]
                    h.handle(RemoteEvent.DeviceResult(opId, outcome))
                }
                20 -> h.setDevice(h.device.copy(hid = HidSpec(random.nextInt(4) != 0, random.nextInt(4) != 0)))
                21 -> h.handle(if (random.nextInt(3) == 0) RemoteEvent.LocalCaptureStarted else RemoteEvent.LocalCaptureStopped)
                22 -> {
                    val nodeId = "n${random.nextInt(7)}"
                    h.handle(
                        when (random.nextInt(5)) {
                            0, 1 -> RemoteEvent.Approve(nodeId, random.nextBoolean())
                            2 -> RemoteEvent.Deny(nodeId)
                            3 ->
                                RemoteEvent.Revoke(
                                    if (tokenHashes.isNotEmpty() &&
                                        random.nextBoolean()
                                    ) {
                                        Principal.Token(tokenHashes[random.nextInt(tokenHashes.size)])
                                    } else {
                                        Principal.Node(nodeId)
                                    },
                                )
                            else -> RemoteEvent.SetAllowRw(Principal.Node(nodeId), random.nextBoolean())
                        },
                    )
                }
                23 -> {
                    if (random.nextBoolean()) h.handle(RemoteEvent.PairingEnable)
                    val code =
                        h.controller
                            .status()
                            .pairing.code
                    val guess = if (code != null && random.nextBoolean()) code else "%06d".format(random.nextInt(1_000_000))
                    // Mostly a fresh exchange; sometimes a pair command forged onto a live session.
                    val (verdict, tokenHash) = h.pair(if (random.nextInt(8) == 0) liveSession() else "pair${usedIds.size}", guess)
                    usedIds += "pair${usedIds.size}"
                    if (verdict["ok"]?.jsonPrimitive?.boolean == true) tokenHashes += tokenHash
                }
                24, 25 ->
                    h.advance(
                        when (random.nextInt(20)) {
                            0 -> RemoteLimits.IDLE_KEY_RELEASE_MS
                            1 -> RemoteLimits.IDLE_LEASE_RELEASE_MS
                            2 -> RemoteLimits.TAKEOVER_PROMPT_TIMEOUT_MS
                            3 -> 3L * 24 * 60 * 60 * 1000
                            else -> random.nextInt(400).toLong()
                        },
                    )
                26 -> h.handle(RemoteEvent.HidReleaseDelivered)
                27 ->
                    h.handle(
                        when (random.nextInt(3)) {
                            0 -> RemoteEvent.HidRejected(HidSendResult.entries[1 + random.nextInt(3)])
                            1 -> RemoteEvent.InputOverflow(h.id(holderOrAny()))
                            else -> RemoteEvent.SendFailed(h.id(anySession()))
                        },
                    )
                // A reused request id: the cached reply, never a second execution.
                28 ->
                    if (usedIds.isNotEmpty()) {
                        h.request(
                            anySession(),
                            "lease.release",
                            epoch = h.epoch(),
                            id = usedIds[random.nextInt(usedIds.size)],
                        )
                    }
                29 ->
                    h.handle(
                        listOf(RemoteEvent.HotspotStopped, RemoteEvent.PairingLockoutReset, RemoteEvent.PairingDisable)[random.nextInt(3)],
                    )
                30 ->
                    when (random.nextInt(8)) {
                        0 -> {
                            h.restart()
                            helloed.clear()
                        }
                        1 -> h.slowSessions.add(liveSession())
                        else -> h.slowSessions.clear()
                    }
                31 -> request("state.get", epoch = null, sessionId = liveSession())
                32 -> request(if (random.nextBoolean()) "download.start" else "download.cancel", mapOf("entryId" to str(entryId())))
                33 -> {
                    // The phone answers some call, maybe a stale one; the outcome fits the call's kind.
                    val call = h.downloadCalls.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] } ?: return@repeat
                    val outcome =
                        if (call.op is DownloadOp.Start) {
                            listOf(
                                DownloadOutcome.Started,
                                DownloadOutcome.AlreadyActive,
                                DownloadOutcome.StorageLow("x"),
                                DownloadOutcome.TimedOut,
                            )[
                                random.nextInt(4),
                            ]
                        } else {
                            listOf(
                                DownloadOutcome.Cancelled(random.nextBoolean()),
                                DownloadOutcome.Busy,
                                DownloadOutcome.Rejected("x"),
                            )[random.nextInt(3)]
                        }
                    h.handle(RemoteEvent.DownloadResult(call.opId, outcome))
                }
                34 ->
                    h.handle(
                        RemoteEvent.DownloadsChanged(
                            DOWNLOAD_FILE_NAMES.filter { random.nextBoolean() }.map {
                                RemoteDownload(
                                    it,
                                    it,
                                    RemoteDownloadStatus.entries[random.nextInt(6)],
                                    random.nextInt(1000).toLong(),
                                    1000,
                                    false,
                                    null,
                                )
                            },
                        ),
                    )
                35 ->
                    if (random.nextBoolean()) {
                        request("catalog.list", epoch = null, sessionId = liveSession())
                    } else {
                        h.handle(RemoteEvent.CatalogChanged(catalog(h.clock.wall).copy(refreshing = random.nextBoolean())))
                    }
            }
        }
    }

    /** Every job finishes or is cancelled, and nothing stays held once all sessions end. */
    private fun windDown(
        h: RemoteHarness,
        sessionIds: List<String>,
    ) {
        h.slowSessions.clear()
        for (sessionId in sessionIds) h.closed(sessionId, "end")
        h.handle(RemoteEvent.LocalCaptureStopped)
        h.runFor(1_000, stepMs = 100)
        check(!h.hidHost.anyHeld) { "keys held after every session ended" }
        check(
            h.controller
                .status()
                .sessions
                .isEmpty(),
        )
        check(h.controller.leaseView.first == null)
    }

    private companion object {
        val DOWNLOAD_FILE_NAMES = listOf("netboot.xyz.iso", "debian.iso", "big.iso")

        fun catalog(wallMs: Long) =
            CatalogSnapshot(
                listOf(
                    CatalogDistroSnapshot(
                        Distro("d", "Distro", Category.RESCUE, "https://example.org"),
                        DOWNLOAD_FILE_NAMES.map {
                            Release(
                                "d",
                                "1",
                                Arch.X86_64,
                                url = "https://example.org/$it",
                                sha256 = "a".repeat(64),
                                fileName = it,
                            )
                        },
                        wallMs,
                        null,
                    ),
                ),
            )

        const val ITERATIONS = 200
        const val STEP_COUNT = 300
        const val SESSION_ID_COUNT = 7
    }
}
