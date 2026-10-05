package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Remote protocol v1: the contract between the web UI / CLI, the Go transport and Kotlin.
 * remote/PROTOCOL.md documents every message and remote/protocol/protocol.go mirrors the wire
 * names; keep all three in sync.
 */

/** Error codes in `{id, ok:false, error:{code, msg}}`. */
@Serializable
enum class ErrorCode {
    @SerialName("bad_request")
    BAD_REQUEST,

    @SerialName("unknown_type")
    UNKNOWN_TYPE,

    @SerialName("hello_required")
    HELLO_REQUIRED,

    @SerialName("pending")
    PENDING,

    @SerialName("lease_required")
    LEASE_REQUIRED,

    @SerialName("stale_epoch")
    STALE_EPOCH,

    @SerialName("lease_held")
    LEASE_HELD,

    @SerialName("local_priority")
    LOCAL_PRIORITY,

    @SerialName("lease_changed")
    LEASE_CHANGED,

    @SerialName("takeover_declined")
    TAKEOVER_DECLINED,

    @SerialName("busy")
    BUSY,

    @SerialName("rev_conflict")
    REV_CONFLICT,

    @SerialName("not_found")
    NOT_FOUND,

    @SerialName("rw_not_allowed")
    RW_NOT_ALLOWED,

    /** The image is bigger than a CD-ROM holds (the kernel would truncate it); the message names the alternative. */
    @SerialName("cdrom_too_large")
    CDROM_TOO_LARGE,

    @SerialName("release_pending")
    RELEASE_PENDING,

    @SerialName("hid_off")
    HID_OFF,

    @SerialName("too_large")
    TOO_LARGE,

    @SerialName("too_long")
    TOO_LONG,

    @SerialName("too_many_plans")
    TOO_MANY_PLANS,

    @SerialName("plan_expired")
    PLAN_EXPIRED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("pairing_disabled")
    PAIRING_DISABLED,

    @SerialName("pairing_locked")
    PAIRING_LOCKED,

    @SerialName("bad_code")
    BAD_CODE,

    @SerialName("device")
    DEVICE,

    @SerialName("device_attention")
    DEVICE_ATTENTION,

    @SerialName("disks_exposed")
    DISKS_EXPOSED,

    @SerialName("timeout")
    TIMEOUT,

    @SerialName("too_many_downloads")
    TOO_MANY_DOWNLOADS,

    @SerialName("storage_low")
    STORAGE_LOW,
    ;

    val wireName: String get() = serializer().descriptor.getElementName(ordinal)
}

/**
 * Why Kotlin closes a session (`close(sessionId, reason)`). [webSocketCode] is what Go's
 * `protocol.CloseCodeFor` maps [wireName] to (4000 for reasons Go doesn't name).
 */
enum class CloseReason(
    val wireName: String,
    val webSocketCode: Int,
) {
    VERSION("version", 4400),
    REVOKED("revoked", 4401),
    EXPIRED("expired", 4401),

    /** A hotspot token Kotlin doesn't know (or no longer knows). */
    UNAUTHORIZED("unauthorized", 4401),
    MALFORMED("malformed", 4402),
    SLOW_CONSUMER("slow_consumer", 4408),

    /** Kotlin's inbound queue for the session overflowed. */
    RATE_LIMITED("rate_limited", 4429),
    LISTENER_STOPPED("listener_stopped", 4503),
    DENIED("denied", 4000),
    PENDING_EVICTED("pending_evicted", 4000),
    TOO_MANY_CONNECTIONS("too_many_connections", 4000),
    SHUTDOWN("shutdown", 4000),
}

@Serializable
enum class AuthStatus {
    @SerialName("approved")
    APPROVED,

    /** Unknown tailnet node, waiting for in-app approval: no state, no control. */
    @SerialName("pending")
    PENDING,
}

@Serializable
enum class WireLunMode(
    val mode: LunMode,
) {
    @SerialName("cdrom")
    CDROM(LunMode.CDROM),

    @SerialName("disk_ro")
    DISK_RO(LunMode.DISK_RO),

    @SerialName("disk_rw")
    DISK_RW(LunMode.DISK_RW),
    ;

    companion object {
        fun of(mode: LunMode): WireLunMode = entries.single { it.mode == mode }
    }
}

@Serializable
enum class WireSlotStatus(
    val intent: SlotIntent,
) {
    @SerialName("exported")
    EXPORTED(SlotIntent.EXPORTED),

    @SerialName("host_ejected")
    HOST_EJECTED(SlotIntent.HOST_EJECTED),

    @SerialName("user_ejected")
    USER_EJECTED(SlotIntent.USER_EJECTED),

    @SerialName("failed")
    FAILED(SlotIntent.FAILED),
    ;

    companion object {
        fun of(intent: SlotIntent): WireSlotStatus = entries.single { it.intent == intent }
    }
}

@Serializable
enum class WireArch(
    val arch: Arch,
) {
    @SerialName("x86_64")
    X86_64(Arch.X86_64),

    @SerialName("arm64")
    ARM64(Arch.ARM64),
    ;

    companion object {
        fun of(arch: Arch): WireArch = entries.single { it.arch == arch }
    }
}

@Serializable
enum class WireCategory(
    val category: Category,
) {
    @SerialName("desktop")
    DESKTOP(Category.DESKTOP),

    @SerialName("server")
    SERVER(Category.SERVER),

    @SerialName("rescue")
    RESCUE(Category.RESCUE),
    ;

    companion object {
        fun of(category: Category): WireCategory = entries.single { it.category == category }
    }
}

@Serializable
enum class WireDownloadStatus(
    val status: RemoteDownloadStatus,
) {
    @SerialName("queued")
    QUEUED(RemoteDownloadStatus.QUEUED),

    @SerialName("running")
    RUNNING(RemoteDownloadStatus.RUNNING),

    @SerialName("paused")
    PAUSED(RemoteDownloadStatus.PAUSED),

    @SerialName("verifying")
    VERIFYING(RemoteDownloadStatus.VERIFYING),

    @SerialName("completed")
    COMPLETED(RemoteDownloadStatus.COMPLETED),

    @SerialName("failed")
    FAILED(RemoteDownloadStatus.FAILED),
    ;

    companion object {
        fun of(status: RemoteDownloadStatus): WireDownloadStatus = entries.single { it.status == status }
    }
}

/** A catalog entry's state: not downloaded, its download's status, or in the library. */
@Serializable
enum class WireEntryState {
    @SerialName("available")
    AVAILABLE,

    @SerialName("queued")
    QUEUED,

    @SerialName("running")
    RUNNING,

    @SerialName("paused")
    PAUSED,

    @SerialName("verifying")
    VERIFYING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("downloaded")
    DOWNLOADED,
    ;

    companion object {
        fun of(status: RemoteDownloadStatus): WireEntryState = valueOf(status.name)
    }
}

/** `download.start`'s `result`. */
@Serializable
enum class DownloadStartResultKind {
    @SerialName("started")
    STARTED,

    /** Already queued or running; nothing new started. */
    @SerialName("active")
    ACTIVE,

    /** Already in the library; nothing started. */
    @SerialName("downloaded")
    DOWNLOADED,
}

@Serializable
enum class BootKeyAction {
    @SerialName("menu")
    MENU,

    @SerialName("setup")
    SETUP,
}

// ---- Requests: {"id": ..., "t": "...", "epoch": n?, ...body}. The envelope keys are stripped before the body is decoded.

@Serializable
sealed interface RequestBody

/** The first message on /ws (`{"id":0,"t":"hello","v":1}`); answered by a `welcome` push. */
@Serializable
@SerialName("hello")
data class HelloRequest(
    val v: Int,
) : RequestBody

/** Go → Kotlin only, on a `pair` session: `{"t":"pair","code":"123456","tokenHash":"..."}`, no id. */
@Serializable
@SerialName("pair")
data class PairRequest(
    val code: String,
    val tokenHash: String,
) : RequestBody

@Serializable
@SerialName("state.get")
data object StateGetRequest : RequestBody

@Serializable
@SerialName("lease.take")
data object LeaseTakeRequest : RequestBody

/** The envelope `epoch` (required here) is the holder's epoch as the requester last saw it. */
@Serializable
@SerialName("lease.takeover")
data class LeaseTakeoverRequest(
    val holderSession: Long,
) : RequestBody

/** The holder's answer to a `lease.takeover_request` push. */
@Serializable
@SerialName("lease.takeover_reply")
data class LeaseTakeoverReplyRequest(
    val allow: Boolean,
) : RequestBody

@Serializable
@SerialName("lease.release")
data object LeaseReleaseRequest : RequestBody

@Serializable
data class SlotRequest(
    val lun: Int,
    val imageId: String,
    /** Absent: the image's default (`.iso` → cdrom, else disk_ro). */
    val mode: WireLunMode? = null,
)

/** The full desired slot list. */
@Serializable
@SerialName("slots.apply")
data class SlotsApplyRequest(
    val slots: List<SlotRequest>,
    val ifRev: Long,
) : RequestBody

@Serializable
@SerialName("slot.eject")
data class SlotEjectRequest(
    val lun: Int,
    val force: Boolean = false,
    val ifRev: Long? = null,
) : RequestBody

@Serializable
@SerialName("slot.reinsert")
data class SlotReinsertRequest(
    val lun: Int,
    val ifRev: Long? = null,
) : RequestBody

@Serializable
@SerialName("hid.set")
data class HidSetRequest(
    val keyboard: Boolean,
    val mouse: Boolean,
    val ifRev: Long? = null,
) : RequestBody

/** USB tethering on or off in the phone's mode, without a re-plug; switching the mode re-plugs, so the phone refuses that while a disk is exposed. */
@Serializable
@SerialName("tether.set")
data class TetherSetRequest(
    val on: Boolean,
    val ifRev: Long? = null,
) : RequestBody

/** Answered when the boot keys finish (or are cancelled). */
@Serializable
@SerialName("bootkey.run")
data class BootKeyRunRequest(
    /** Lower-case `BootVendor` name, e.g. `dell`, `apple_mac`. */
    val vendor: String,
    val action: BootKeyAction,
    /** Index into the preset's keys for [action], most likely first. */
    val keyIndex: Int = 0,
    val durationMs: Long = RemoteLimits.BOOT_KEY_DURATION_MS_DEFAULT,
) : RequestBody

@Serializable
@SerialName("bootkey.cancel")
data object BootKeyCancelRequest : RequestBody

@Serializable
@SerialName("paste.prepare")
data class PastePrepareRequest(
    val text: String,
) : RequestBody

/** Answered when the typing finishes (or is cancelled). */
@Serializable
@SerialName("paste.run")
data class PasteRunRequest(
    val planId: String,
) : RequestBody

@Serializable
@SerialName("paste.cancel")
data object PasteCancelRequest : RequestBody

/** The cached catalog, filtered. Needs an approved session, not the lease. */
@Serializable
@SerialName("catalog.list")
data class CatalogListRequest(
    val arch: WireArch = WireArch.X86_64,
    /** Absent: every category. */
    val category: WireCategory? = null,
    /** Absent: every distro. */
    val distroId: String? = null,
) : RequestBody

/** Answered once the phone has enqueued it (or refused). */
@Serializable
@SerialName("download.start")
data class DownloadStartRequest(
    val entryId: String,
) : RequestBody

/** Answered once the phone has cancelled it. */
@Serializable
@SerialName("download.cancel")
data class DownloadCancelRequest(
    val entryId: String,
) : RequestBody

// ---- Replies: {id, ok, error?: {code, msg}, ...result fields}. No "t".

@Serializable
data class ReplyError(
    val code: ErrorCode,
    val msg: String,
)

// ---- Shared views (reply results and push bodies).

/** `holder` is the holding session's id, absent when free or while the phone's Control tab has priority (`local`). */
@Serializable
data class LeaseView(
    val holder: Long? = null,
    val holderName: String? = null,
    val epoch: Int,
    val local: Boolean = false,
)

@Serializable
data class SlotView(
    val lun: Int,
    /** Always set: images outside the library get an id from their path too. */
    val imageId: String,
    val name: String,
    val mode: WireLunMode,
    val status: WireSlotStatus,
)

@Serializable
data class ImageView(
    val imageId: String,
    val name: String,
    val sizeBytes: Long,
    val defaultMode: WireLunMode,
    /** True (else absent) when the image matched the distro's published SHA-256. */
    val verified: Boolean? = null,
)

@Serializable
data class HidView(
    val keyboard: Boolean,
    val mouse: Boolean,
    /** The mouse is on, so scroll frames work (kept for clients that check it). */
    val wheel: Boolean,
    /** [HID_STATUS_RELEASE_PENDING] once release-all hasn't reached the host for 60 s; input is refused. */
    val status: String? = null,
    /** The phone's HID session ([HidLinkStatus.wireName]) while the keyboard or mouse is on. */
    val link: String? = null,
    /**
     * While [link] is open: the endpoints (`keyboard`, `mouse`) whose reports the computer isn't reading
     * (firmware and boot menus often read only the keyboard); absent when it reads them all.
     */
    val notReading: List<String>? = null,
)

const val HID_STATUS_RELEASE_PENDING = "release_pending"

/** The phone's USB tethering, as Android reports it, and its mode (`TetherMode.wireName`; absent in `tether.set` replies). */
@Serializable
data class TetherView(
    val on: Boolean,
    val mode: String? = null,
)

@Serializable
data class StateView(
    val rev: Long,
    val slots: List<SlotView>,
    val images: List<ImageView>,
    val hid: HidView,
    val lease: LeaseView,
    /** Absent until Android has reported it. */
    val tether: TetherView? = null,
)

@Serializable
data class StateResult(
    val state: StateView,
    val lease: LeaseView,
)

@Serializable
data class LeaseResult(
    val epoch: Int,
    val lease: LeaseView,
)

@Serializable
data class TetherSetResult(
    val tether: TetherView,
    /** Tethering changed, but the gadget then needs attention on the phone. */
    val warning: String? = null,
)

@Serializable
data class DeviceOpResult(
    /** Some slots failed (lun → reason); the rest were applied. */
    val slotFailures: Map<String, String> = emptyMap(),
)

@Serializable
data class UnsupportedCharacterView(
    val index: Int,
    val codePoint: Int,
)

@Serializable
data class PastePrepareResult(
    val planId: String,
    /** The first [RemoteLimits.PASTE_UNSUPPORTED_REPORTED_MAX] characters that can't be typed (skipped). */
    val unsupported: List<UnsupportedCharacterView>,
    val unsupportedCount: Int,
    val durationMs: Long,
)

@Serializable
data class JobResult(
    val durationMs: Long,
)

@Serializable
data class CancelResult(
    val cancelled: Boolean,
)

@Serializable
data class CatalogEntryView(
    val entryId: String,
    val version: String,
    val variant: String? = null,
    val arch: WireArch,
    val fileName: String,
    val sizeBytes: Long? = null,
    val supportedUntil: String? = null,
    val state: WireEntryState,
)

@Serializable
data class CatalogDistroView(
    val distroId: String,
    val name: String,
    val category: WireCategory,
    /** Wall-clock Unix ms of the last good fetch. */
    val fetchedAtMs: Long? = null,
    /** The last refresh's error; [entries] are then the last good ones. */
    val error: String? = null,
    val entries: List<CatalogEntryView>,
)

@Serializable
data class CatalogResult(
    val catalogRev: Long,
    val refreshing: Boolean,
    /** Some entries didn't fit the reply; narrow it with `arch`/`category`. */
    val truncated: Boolean,
    val distros: List<CatalogDistroView>,
)

@Serializable
data class DownloadStartResult(
    val entryId: String,
    val fileName: String,
    val result: DownloadStartResultKind,
)

@Serializable
data class DownloadView(
    val entryId: String,
    val fileName: String,
    val title: String,
    val status: WireDownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val verified: Boolean? = null,
    val error: String? = null,
)

// ---- Pushes: {"t": "...", ...}, no id.

@Serializable
sealed interface Push

@Serializable
@SerialName("welcome")
data class WelcomePush(
    val v: Int,
    val sessionId: Long,
    val auth: AuthStatus,
    val allowRw: Boolean = false,
    /** Absent unless [auth] is approved. */
    val stateRev: Long? = null,
    val lease: LeaseView? = null,
) : Push

/** Sent when the app approves a pending node or changes its RW permission. */
@Serializable
@SerialName("auth")
data class AuthPush(
    val auth: AuthStatus,
    val allowRw: Boolean,
) : Push

@Serializable
@SerialName("state")
data class StatePush(
    val rev: Long,
    val slots: List<SlotView>,
    val images: List<ImageView>,
    val hid: HidView,
    val lease: LeaseView,
    val tether: TetherView? = null,
) : Push

@Serializable
@SerialName("lease")
data class LeasePush(
    val lease: LeaseView,
) : Push

/** To the holder: another session asks for the lease; answer with `lease.takeover_reply`. */
@Serializable
@SerialName("lease.takeover_request")
data class TakeoverRequestPush(
    val requester: Long,
    val requesterName: String,
    val epoch: Int,
    val timeoutMs: Long,
) : Push

@Serializable
@SerialName("lease.takeover_cancelled")
data class TakeoverCancelledPush(
    val requester: Long,
) : Push

/** Every download the phone knows (from the phone or the remote), with progress. */
@Serializable
@SerialName("downloads")
data class DownloadsPush(
    val downloads: List<DownloadView>,
    /** Some (finished or paused) downloads didn't fit the message. */
    val truncated: Boolean = false,
) : Push

/** The cached catalog changed, or a refresh started or ended; `catalog.list` again for the entries. */
@Serializable
@SerialName("catalog")
data class CatalogPush(
    val catalogRev: Long,
    val refreshing: Boolean,
) : Push

// ---- JSON configuration.

object ProtocolJson {
    /** Wire JSON: `t` discriminates requests and pushes; null fields are omitted (absent means null). */
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json =
        Json {
            classDiscriminator = "t"
            explicitNulls = false
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

    /** Request types Kotlin understands, by `t`. */
    val requestTypes: Set<String> = elementNames(RequestBody.serializer())

    val pushTypes: Set<String> = elementNames(Push.serializer())

    fun encodePush(push: Push): String = json.encodeToString(Push.serializer(), push)

    /** `{id?, ok, error?, ...result}`: result fields sit beside id and ok. */
    fun encodeReply(
        id: JsonPrimitive?,
        error: ReplyError?,
        result: JsonObject?,
    ): String {
        require(error == null || result == null) { "an error reply has no result" }
        val reply =
            buildJsonObject {
                if (id != null) put("id", id)
                put("ok", JsonPrimitive(error == null))
                if (error != null) put("error", json.encodeToJsonElement(ReplyError.serializer(), error))
                result?.forEach { (key, value) ->
                    require(key !in REPLY_ENVELOPE_KEYS) { "result field $key collides with the reply envelope" }
                    put(key, value)
                }
            }
        return reply.toString()
    }

    fun <T> resultOf(
        serializer: KSerializer<T>,
        value: T,
    ): JsonObject = json.encodeToJsonElement(serializer, value).jsonObject

    private val REPLY_ENVELOPE_KEYS = setOf("id", "ok", "error", "t")

    private fun elementNames(serializer: KSerializer<*>): Set<String> {
        val subclasses = serializer.descriptor.getElementDescriptor(1)
        return (0 until subclasses.elementsCount).map { subclasses.getElementName(it) }.toSet()
    }
}

// ---- Binary HID frames: epoch (u16 big-endian), op, operands. Lengths are exact.

sealed interface HidFrame {
    val epoch: Int

    /** [usageId] is a keyboard-page usage 0x04..0xE7; ids Bootleg has no [KeyUsage] for are ignored. */
    data class KeyDown(
        override val epoch: Int,
        val usageId: Int,
    ) : HidFrame {
        constructor(epoch: Int, usage: KeyUsage) : this(epoch, usage.id)
    }

    data class KeyUp(
        override val epoch: Int,
        val usageId: Int,
    ) : HidFrame {
        constructor(epoch: Int, usage: KeyUsage) : this(epoch, usage.id)
    }

    data class MouseMove(
        override val epoch: Int,
        val deltaX: Int,
        val deltaY: Int,
    ) : HidFrame

    data class MouseButtons(
        override val epoch: Int,
        val buttonBits: Int,
    ) : HidFrame

    /** Detents: [wheel] > 0 scrolls up, [pan] > 0 scrolls right. */
    data class Scroll(
        override val epoch: Int,
        val wheel: Int,
        val pan: Int,
    ) : HidFrame

    data class ReleaseAll(
        override val epoch: Int,
    ) : HidFrame
}

object HidFrameCodec {
    const val OP_KEY_DOWN = 0x01
    const val OP_KEY_UP = 0x02
    const val OP_MOUSE_MOVE = 0x03
    const val OP_MOUSE_BUTTONS = 0x04
    const val OP_RELEASE_ALL = 0x05
    const val OP_SCROLL = 0x06
    const val KEY_USAGE_ID_MIN = 0x04
    const val KEY_USAGE_ID_MAX = 0xE7
    private const val HEADER_SIZE_BYTES = 3
    private const val BUTTON_BITS_MASK = 0x07

    /** Encoders never emit -128; decode clamps it to -127. */
    val MOUSE_DELTA_RANGE = -127..127

    /** Exact frame length per op, header included. */
    val frameSizeBytesByOp: Map<Int, Int> =
        mapOf(
            OP_KEY_DOWN to 4,
            OP_KEY_UP to 4,
            OP_MOUSE_MOVE to 5,
            OP_MOUSE_BUTTONS to 4,
            OP_RELEASE_ALL to 3,
            OP_SCROLL to 5,
        )

    /** Op byte of a frame, or null if it has no header. Used to route release-all onto the priority lane. */
    fun opOf(frame: ByteArray): Int? = if (frame.size >= HEADER_SIZE_BYTES) frame[2].toInt() and 0xFF else null

    /** Returns null for any malformed frame: unknown op, wrong length, usage out of 0x04..0xE7, stray button bits. */
    fun decode(frame: ByteArray): HidFrame? {
        val op = opOf(frame) ?: return null
        if (frameSizeBytesByOp[op] != frame.size) return null
        val epoch = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        return when (op) {
            OP_KEY_DOWN -> usageIdOf(frame[3])?.let { HidFrame.KeyDown(epoch, it) }
            OP_KEY_UP -> usageIdOf(frame[3])?.let { HidFrame.KeyUp(epoch, it) }
            OP_MOUSE_MOVE -> HidFrame.MouseMove(epoch, mouseDeltaOf(frame[3]), mouseDeltaOf(frame[4]))
            OP_MOUSE_BUTTONS -> {
                val bits = frame[3].toInt() and 0xFF
                if (bits and BUTTON_BITS_MASK.inv() != 0) null else HidFrame.MouseButtons(epoch, bits)
            }
            OP_RELEASE_ALL -> HidFrame.ReleaseAll(epoch)
            OP_SCROLL -> HidFrame.Scroll(epoch, mouseDeltaOf(frame[3]), mouseDeltaOf(frame[4]))
            else -> null
        }
    }

    fun encode(frame: HidFrame): ByteArray {
        require(frame.epoch in 0 until RemoteLimits.LEASE_EPOCH_MODULUS) { "epoch is u16: ${frame.epoch}" }
        val header = byteArrayOf((frame.epoch shr 8).toByte(), frame.epoch.toByte())
        val body =
            when (frame) {
                is HidFrame.KeyDown -> byteArrayOf(OP_KEY_DOWN.toByte(), checkedUsage(frame.usageId))
                is HidFrame.KeyUp -> byteArrayOf(OP_KEY_UP.toByte(), checkedUsage(frame.usageId))
                is HidFrame.MouseMove -> {
                    require(frame.deltaX in MOUSE_DELTA_RANGE && frame.deltaY in MOUSE_DELTA_RANGE) {
                        "mouse delta outside $MOUSE_DELTA_RANGE: ${frame.deltaX}, ${frame.deltaY}"
                    }
                    byteArrayOf(OP_MOUSE_MOVE.toByte(), frame.deltaX.toByte(), frame.deltaY.toByte())
                }
                is HidFrame.MouseButtons -> {
                    require(frame.buttonBits in 0..BUTTON_BITS_MASK)
                    byteArrayOf(OP_MOUSE_BUTTONS.toByte(), frame.buttonBits.toByte())
                }
                is HidFrame.Scroll -> {
                    require(frame.wheel in MOUSE_DELTA_RANGE && frame.pan in MOUSE_DELTA_RANGE) {
                        "scroll outside $MOUSE_DELTA_RANGE: ${frame.wheel}, ${frame.pan}"
                    }
                    byteArrayOf(OP_SCROLL.toByte(), frame.wheel.toByte(), frame.pan.toByte())
                }
                is HidFrame.ReleaseAll -> byteArrayOf(OP_RELEASE_ALL.toByte())
            }
        return header + body
    }

    /** HID mouse reports (motion and scroll) are symmetric (-127..127), so -128 is clamped. */
    private fun mouseDeltaOf(byte: Byte): Int = byte.toInt().coerceAtLeast(MOUSE_DELTA_RANGE.first)

    private fun usageIdOf(byte: Byte): Int? = (byte.toInt() and 0xFF).takeIf { it in KEY_USAGE_ID_MIN..KEY_USAGE_ID_MAX }

    private fun checkedUsage(usageId: Int): Byte {
        require(usageId in KEY_USAGE_ID_MIN..KEY_USAGE_ID_MAX) { "usage 0x%02x outside 0x04..0xE7".format(usageId) }
        return usageId.toByte()
    }
}
