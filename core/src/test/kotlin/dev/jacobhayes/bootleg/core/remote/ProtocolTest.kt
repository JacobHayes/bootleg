package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.hid.KeyUsage
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class ProtocolTest {
    private val pressable = KeyUsage.entries.filter { it != KeyUsage.ERROR_ROLL_OVER }

    @Test
    fun `frames round-trip with a big-endian epoch and exact lengths`() =
        runBlocking<Unit> {
            checkAll(
                300,
                Arb.int(0 until RemoteLimits.LEASE_EPOCH_MODULUS),
                Arb.element(pressable),
                Arb.int(HidFrameCodec.MOUSE_DELTA_RANGE),
            ) { epoch, usage, delta ->
                val frames =
                    listOf(
                        HidFrame.KeyDown(epoch, usage),
                        HidFrame.KeyUp(epoch, usage),
                        HidFrame.MouseMove(epoch, delta, -delta),
                        HidFrame.MouseButtons(epoch, (delta and 0x07)),
                        HidFrame.Scroll(epoch, delta, -delta),
                        HidFrame.ReleaseAll(epoch),
                    )
                for (frame in frames) {
                    val bytes = HidFrameCodec.encode(frame)
                    assertEquals(epoch shr 8, bytes[0].toInt() and 0xFF)
                    assertEquals(epoch and 0xFF, bytes[1].toInt() and 0xFF)
                    assertEquals(HidFrameCodec.frameSizeBytesByOp[bytes[2].toInt()], bytes.size)
                    assertEquals(frame, HidFrameCodec.decode(bytes))
                    assertNull(HidFrameCodec.decode(bytes + 0))
                    assertNull(HidFrameCodec.decode(bytes.copyOf(bytes.size - 1)))
                }
            }
        }

    @Test
    fun `frame examples from PROTOCOL md`() {
        assertEquals(HidFrame.KeyDown(0x0102, KeyUsage.A), HidFrameCodec.decode(byteArrayOf(1, 2, 1, 4)))
        assertEquals(HidFrame.MouseMove(7, -1, 127), HidFrameCodec.decode(byteArrayOf(0, 7, 3, -1, 127)))
        assertEquals(HidFrame.ReleaseAll(65535), HidFrameCodec.decode(byteArrayOf(-1, -1, 5)))
        assertArrayEquals(byteArrayOf(0, 9, 4, 3), HidFrameCodec.encode(HidFrame.MouseButtons(9, 3)))
        assertThrows<IllegalArgumentException> { HidFrameCodec.encode(HidFrame.ReleaseAll(65536)) }
    }

    @Test
    fun `mouse delta -128 is clamped to -127 on decode and never encoded`() {
        assertEquals(HidFrame.MouseMove(7, -127, -127), HidFrameCodec.decode(byteArrayOf(0, 7, 3, -128, -128)))
        assertEquals(HidFrame.MouseMove(7, -127, 127), HidFrameCodec.decode(byteArrayOf(0, 7, 3, -128, 127)))
        assertThrows<IllegalArgumentException> { HidFrameCodec.encode(HidFrame.MouseMove(7, -128, 0)) }
        assertThrows<IllegalArgumentException> { HidFrameCodec.encode(HidFrame.MouseMove(7, 0, -128)) }
        assertThrows<IllegalArgumentException> { HidFrameCodec.encode(HidFrame.MouseMove(7, 128, 0)) }
    }

    @Test
    fun `scroll frames carry signed wheel and pan, clamping -128`() {
        assertEquals(HidFrame.Scroll(7, -3, 2), HidFrameCodec.decode(byteArrayOf(0, 7, 6, -3, 2)))
        assertEquals(HidFrame.Scroll(7, -127, 127), HidFrameCodec.decode(byteArrayOf(0, 7, 6, -128, 127)))
        assertArrayEquals(byteArrayOf(0, 9, 6, 1, -1), HidFrameCodec.encode(HidFrame.Scroll(9, 1, -1)))
        assertNull(HidFrameCodec.decode(byteArrayOf(0, 7, 6, 1)))
        assertThrows<IllegalArgumentException> { HidFrameCodec.encode(HidFrame.Scroll(7, -128, 0)) }
        assertThrows<IllegalArgumentException> { HidFrameCodec.encode(HidFrame.Scroll(7, 0, 128)) }
    }

    @Test
    fun `connJson is bounded in UTF-8 bytes, not chars`() {
        val base = ConnInfo.encode(ConnInfo("tailnet", "100.64.0.2:1", ConnIdentity.Node("nABC")))
        val limit = RemoteLimits.CONN_JSON_SIZE_BYTES_MAX

        // Pads Go's JSON with an ignored field to exactly sizeBytes, ending the pad in a 2-byte character.
        fun padded(sizeBytes: Int): String {
            val prefix = """{"pad":""""
            val suffix = "\"," + base.removePrefix("{")
            val asciiCount = sizeBytes - prefix.length - suffix.length - 2
            return prefix + "x".repeat(asciiCount) + "é" + suffix
        }
        val atLimit = padded(limit)
        assertEquals(limit, atLimit.encodeToByteArray().size)
        assertNotNull(ConnInfo.parse(atLimit))
        val overLimit = padded(limit + 1)
        // The 2-byte character straddles the limit: the chars fit, the bytes don't.
        assertTrue(overLimit.length <= limit)
        assertEquals(limit + 1, overLimit.encodeToByteArray().size)
        assertNull(ConnInfo.parse(overLimit))
    }

    @Test
    fun `request types`() {
        assertEquals(
            setOf(
                "hello",
                "pair",
                "state.get",
                "lease.take",
                "lease.takeover",
                "lease.takeover_reply",
                "lease.release",
                "slots.apply",
                "slot.eject",
                "slot.reinsert",
                "hid.set",
                "tether.set",
                "bootkey.run",
                "bootkey.cancel",
                "paste.prepare",
                "paste.run",
                "paste.cancel",
                "catalog.list",
                "download.start",
                "download.cancel",
            ),
            ProtocolJson.requestTypes,
        )
    }

    @Test
    fun `wire shapes match the Go side`() {
        assertEquals("""{"id":1,"ok":true}""", ProtocolJson.encodeReply(JsonPrimitive(1), null, null))
        assertEquals(
            """{"id":"2","ok":false,"error":{"code":"lease_held","msg":"held"}}""",
            ProtocolJson.encodeReply(JsonPrimitive("2"), ReplyError(ErrorCode.LEASE_HELD, "held"), null),
        )
        assertEquals(
            """{"id":3,"ok":true,"planId":"p","unsupported":[],"unsupportedCount":0,"durationMs":8}""",
            ProtocolJson.encodeReply(
                JsonPrimitive(3),
                null,
                ProtocolJson.resultOf(PastePrepareResult.serializer(), PastePrepareResult("p", emptyList(), 0, 8)),
            ),
        )
        assertEquals(
            """{"ok":false,"error":{"code":"bad_code","msg":"x"}}""",
            ProtocolJson.encodeReply(null, ReplyError(ErrorCode.BAD_CODE, "x"), null),
        )
        assertEquals("""{"t":"lease","lease":{"epoch":3,"local":false}}""", ProtocolJson.encodePush(LeasePush(LeaseView(epoch = 3))))
        assertEquals(
            """{"t":"lease","lease":{"holder":12,"holderName":"mac","epoch":4,"local":false}}""",
            ProtocolJson.encodePush(LeasePush(LeaseView(12, "mac", 4))),
        )
        assertEquals(
            """{"t":"welcome","v":1,"sessionId":5,"auth":"pending","allowRw":false}""",
            ProtocolJson.encodePush(WelcomePush(1, 5, AuthStatus.PENDING)),
        )
        val decoded =
            ProtocolJson.json.decodeFromJsonElement(
                RequestBody.serializer(),
                JsonObject(mapOf("t" to JsonPrimitive("slot.eject"), "lun" to JsonPrimitive(2))),
            )
        assertEquals(SlotEjectRequest(2), decoded)
        assertThrows<Exception> { ProtocolJson.json.decodeFromString(RequestBody.serializer(), """{"t":"state.get","x":1}""") }
        assertThrows<IllegalArgumentException> { ProtocolJson.encodeReply(null, ReplyError(ErrorCode.BUSY, "x"), JsonObject(emptyMap())) }
        assertThrows<IllegalArgumentException> {
            ProtocolJson.encodeReply(null, null, JsonObject(mapOf("ok" to JsonPrimitive(false))))
        }
    }

    @Test
    fun `connection info parses Go's connJson, bounds it and checks transport against identity`() {
        val node =
            ConnInfo(
                "tailnet",
                "100.64.0.2:51000",
                ConnIdentity.Node("nABC", "alice@example.com", listOf("tag:x"), "mac"),
                browser = true,
                userAgent = "Firefox",
            )
        assertEquals(node, ConnInfo.parse(ConnInfo.encode(node)))
        val goJson =
            """{"transport":"hotspot","remoteAddr":"192.168.49.2:5000","identity":{"kind":"token","tokenHash":"${sha256Hex("t")}"},""" +
                """"browser":false,"userAgent":"bootleg/1","future":1}"""
        assertEquals(ConnIdentity.Token(sha256Hex("t")), ConnInfo.parse(goJson)?.identity)
        val pairJson = """{"transport":"hotspot","remoteAddr":"x","identity":{"kind":"pair","tokenHash":"${sha256Hex("t")}"}}"""
        assertEquals(ConnIdentity.Pairing(sha256Hex("t")), ConnInfo.parse(pairJson)?.identity)
        val longNode = """{"transport":"tailnet","identity":{"kind":"node","nodeId":"${"x".repeat(
            RemoteLimits.IDENTITY_TEXT_LENGTH_MAX + 1,
        )}"}}"""
        assertNull(ConnInfo.parse(longNode))
        assertNull(ConnInfo.parse("""{"transport":"tailnet","identity":{"kind":"pair","tokenHash":"${sha256Hex("t")}"}}"""))
        assertNull(ConnInfo.parse("""{"transport":"hotspot","identity":{"kind":"token","tokenHash":"${"A".repeat(64)}"}}"""))
        assertEquals("node" to "nABC", Principal.Node("nABC").let { it.kind to it.id })
        assertEquals("token", Principal.Token(sha256Hex("t")).kind)
    }

    @Test
    fun `wire names agree with Go's remote protocol package`() {
        val go = File("../remote/protocol/protocol.go").readText() + File("../remote/protocol/frames.go").readText()

        fun goStrings(prefix: String): Set<String> = Regex("""\b$prefix\w*\s*=\s*"([^"]+)"""").findAll(go).map { it.groupValues[1] }.toSet()

        val goTypes = goStrings("Type")
        assertTrue(ProtocolJson.requestTypes.containsAll(goTypes - ProtocolJson.pushTypes)) {
            "Go request types ${goTypes - ProtocolJson.pushTypes}"
        }
        assertTrue(ProtocolJson.pushTypes.containsAll(goTypes - ProtocolJson.requestTypes)) {
            "Go push types ${goTypes - ProtocolJson.requestTypes}"
        }
        assertEquals(
            goStrings("Mode"),
            WireLunMode.entries
                .map {
                    ProtocolJson.json.encodeToString(WireLunMode.serializer(), it).trim('"')
                }.toSet(),
        )
        assertEquals(
            goStrings("BootAction"),
            BootKeyAction.entries
                .map {
                    ProtocolJson.json.encodeToString(BootKeyAction.serializer(), it).trim('"')
                }.toSet(),
        )
        assertEquals(
            goStrings("Auth"),
            AuthStatus.entries.map { ProtocolJson.json.encodeToString(AuthStatus.serializer(), it).trim('"') }.toSet(),
        )
        // Kotlin's error codes that Go also names must be spelled the same.
        val goErrors = goStrings("Error")
        for (code in listOf(
            ErrorCode.BAD_REQUEST,
            ErrorCode.PENDING,
            ErrorCode.LEASE_REQUIRED,
            ErrorCode.LEASE_HELD,
            ErrorCode.STALE_EPOCH,
        )) {
            assertTrue(code.wireName in goErrors) { "Go doesn't know ${code.wireName}" }
        }
        for (code in listOf(ErrorCode.LOCAL_PRIORITY, ErrorCode.REV_CONFLICT, ErrorCode.NOT_FOUND, ErrorCode.DEVICE, ErrorCode.TIMEOUT)) {
            assertTrue(code.wireName in goErrors) { "Go doesn't know ${code.wireName}" }
        }
        // Go's CloseCodeFor maps the reasons it names to specific codes and everything else to 4000.
        val goReasons = goStrings("Reason")
        for (reason in CloseReason.entries) {
            val namedByGo = reason.wireName in goReasons || "\"${reason.wireName}\"" in go
            assertEquals(namedByGo, reason.webSocketCode != 4000) { reason.name }
            assertTrue(Regex("""= ${reason.webSocketCode}\b""").containsMatchIn(go)) { "Go has no close code ${reason.webSocketCode}" }
        }
        assertTrue("KeyUsageMin = 0x%02X".format(HidFrameCodec.KEY_USAGE_ID_MIN) in go)
        assertTrue("KeyUsageMax = 0x%02X".format(HidFrameCodec.KEY_USAGE_ID_MAX) in go)
        // Go rejects a connection whose identity breaks these before Kotlin's Open sees it.
        assertTrue(Regex("""IdentityTagCountMax\s*=\s*${RemoteLimits.IDENTITY_TAG_COUNT_MAX}\b""").containsMatchIn(go))
        assertTrue(Regex("""IdentityTextLengthMax\s*=\s*${RemoteLimits.IDENTITY_TEXT_LENGTH_MAX}\b""").containsMatchIn(go))
        assertEquals(16 * 1024, RemoteLimits.CONN_JSON_SIZE_BYTES_MAX)
        assertTrue(Regex("""ConnJSONBytesMax\s*=\s*16 << 10\b""").containsMatchIn(go))
    }

    @Test
    fun `PROTOCOL md names every request, push, error code and close reason`() {
        val doc = File("../remote/PROTOCOL.md").readText()
        for (type in ProtocolJson.requestTypes + ProtocolJson.pushTypes) {
            assertTrue(
                "`$type`" in doc,
            ) { "PROTOCOL.md doesn't mention `$type`" }
        }
        for (code in ErrorCode.entries) {
            assertTrue(
                "| `${code.wireName}` |" in doc,
            ) { "PROTOCOL.md doesn't document error ${code.wireName}" }
        }
        for (reason in CloseReason.entries) {
            assertTrue(
                "| `${reason.wireName}` | ${reason.webSocketCode} |" in doc,
            ) { "PROTOCOL.md doesn't document close ${reason.wireName}" }
        }
        for ((op, sizeBytes) in HidFrameCodec.frameSizeBytesByOp) {
            assertTrue("| `%02x` | $sizeBytes |".format(op) in doc) { "PROTOCOL.md frame table is missing op $op" }
        }
    }
}
