package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.tether.TetherResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Go's `connJson`: the accepted connection, its listener's transport, and who it is as bound by
 * Go (WhoIs on the tailnet, a bearer token or cookie on the hotspot), never from request data.
 * Every string is untrusted display text except the IDs and hashes.
 */
@Serializable
data class ConnInfo(
    /** [TRANSPORT_TAILNET] or [TRANSPORT_HOTSPOT]. */
    val transport: String,
    val remoteAddr: String = "",
    val identity: ConnIdentity,
    val browser: Boolean = false,
    val userAgent: String = "",
) {
    val displayName: String
        get() =
            when (identity) {
                is ConnIdentity.Node -> identity.name.ifBlank { identity.nodeId }
                is ConnIdentity.Token, is ConnIdentity.Pairing -> "hotspot $remoteAddr"
            }

    companion object {
        const val TRANSPORT_TAILNET = "tailnet"
        const val TRANSPORT_HOTSPOT = "hotspot"
        private val sha256HexRegex = Regex("[0-9a-f]{64}")

        @OptIn(ExperimentalSerializationApi::class)
        private val json =
            Json {
                classDiscriminator = "kind"
                explicitNulls = false
                // Go is trusted and may add informational fields.
                ignoreUnknownKeys = true
            }

        /** Parses and bounds Go's `connJson`; null if malformed or inconsistent. */
        fun parse(connJson: String): ConnInfo? {
            // UTF-8 never takes fewer bytes than UTF-16 chars, so the cheap check runs first.
            if (connJson.length > RemoteLimits.CONN_JSON_SIZE_BYTES_MAX) return null
            if (connJson.encodeToByteArray().size > RemoteLimits.CONN_JSON_SIZE_BYTES_MAX) return null
            val info = runCatching { json.decodeFromString(serializer(), connJson) }.getOrNull() ?: return null
            return info.takeIf { it.isWellFormed() }
        }

        fun encode(info: ConnInfo): String = json.encodeToString(serializer(), info)

        fun isTokenHash(text: String): Boolean = sha256HexRegex.matches(text)

        private fun ConnInfo.isWellFormed(): Boolean {
            val textOk =
                listOf(remoteAddr, userAgent).all { it.length <= RemoteLimits.IDENTITY_TEXT_LENGTH_MAX }
            val identityOk =
                when (identity) {
                    is ConnIdentity.Node ->
                        transport == TRANSPORT_TAILNET &&
                            identity.nodeId.isNotBlank() &&
                            listOf(
                                identity.nodeId,
                                identity.user,
                                identity.name,
                            ).all { it.length <= RemoteLimits.IDENTITY_TEXT_LENGTH_MAX } &&
                            identity.tags.size <= RemoteLimits.IDENTITY_TAG_COUNT_MAX &&
                            identity.tags.all { it.length <= RemoteLimits.IDENTITY_TEXT_LENGTH_MAX }
                    is ConnIdentity.Token -> transport == TRANSPORT_HOTSPOT && isTokenHash(identity.tokenHash)
                    is ConnIdentity.Pairing -> transport == TRANSPORT_HOTSPOT && isTokenHash(identity.tokenHash)
                }
            return textOk && identityOk
        }
    }
}

@Serializable
sealed interface ConnIdentity {
    /** A tailnet node, from WhoIs. */
    @Serializable
    @SerialName("node")
    data class Node(
        /** Tailscale stable node ID. */
        val nodeId: String,
        /** Login name, empty for tagged nodes. */
        val user: String = "",
        val tags: List<String> = emptyList(),
        val name: String = "",
    ) : ConnIdentity

    /** A hotspot client presenting a token; Go sends only its SHA-256 (lower-case hex of the token's ASCII). */
    @Serializable
    @SerialName("token")
    data class Token(
        val tokenHash: String,
    ) : ConnIdentity

    /** A hotspot `/pair` exchange: Go minted a token with this hash and stores it only if Kotlin accepts the code. */
    @Serializable
    @SerialName("pair")
    data class Pairing(
        val tokenHash: String,
    ) : ConnIdentity
}

/** What an authorized session is authorized as; also what `revoke(kind, id)` names toward Go. */
sealed interface Principal {
    val kind: String
    val id: String

    data class Node(
        val nodeId: String,
    ) : Principal {
        override val kind: String get() = "node"
        override val id: String get() = nodeId
    }

    data class Token(
        val sha256: String,
    ) : Principal {
        override val kind: String get() = "token"
        override val id: String get() = sha256
    }
}

/** A tailnet node approved in the app. Times are wall-clock Unix ms (they survive restarts). */
@Serializable
data class ApprovalRecord(
    val nodeId: String,
    val displayName: String,
    val user: String,
    val approvedAtMs: Long,
    val expiresAtMs: Long,
    /** "Allow writable disks", settable only in the app. */
    val allowRw: Boolean = false,
) {
    fun isValidAt(wallMs: Long): Boolean = wallMs < expiresAtMs
}

/** A paired hotspot client. Only the token's SHA-256 is stored. */
@Serializable
data class TokenRecord(
    val sha256: String,
    val label: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val allowRw: Boolean = false,
) {
    fun isValidAt(wallMs: Long): Boolean = wallMs < expiresAtMs
}

/** Persisted hotspot pairing state (noBackupFilesDir). */
@Serializable
data class PairingRecord(
    val tokens: List<TokenRecord> = emptyList(),
    /** Wall-clock times of recent pairing attempts, at most [RemoteLimits.PAIRING_ATTEMPT_COUNT_MAX]. */
    val attemptAtMs: List<Long> = emptyList(),
    /** Set after too many attempts; cleared only from the phone. */
    val lockedOut: Boolean = false,
)

/** An unknown tailnet node that connected; shown in the app for approval. */
data class PendingApproval(
    val nodeId: String,
    val displayName: String,
    val user: String,
    val tags: List<String>,
    val firstSeenWallMs: Long,
)

/** An image the remote may mount, named by [imageId]; [path] never leaves the phone. */
data class RemoteImage(
    val imageId: String,
    val displayName: String,
    val sizeBytes: Long,
    val path: String,
    val defaultMode: LunMode,
    /** The image matched the distro's published SHA-256 (a `.sha256` sidecar). */
    val verified: Boolean = false,
) {
    companion object {
        private const val IMAGE_ID_HEX_LENGTH = 16

        /** A stable opaque ID for an image path. */
        fun idFor(path: String): String = sha256Hex(path).take(IMAGE_ID_HEX_LENGTH)

        fun defaultModeFor(path: String): LunMode = if (path.endsWith(".iso", ignoreCase = true)) LunMode.CDROM else LunMode.DISK_RO
    }
}

data class RemoteSlot(
    val lun: Int,
    val path: String,
    val mode: LunMode,
    val intent: SlotIntent,
)

/** The app's view of the gadget and image library (from `BootlegBackend`), fed to the controller. */
data class DeviceSnapshot(
    val slots: List<RemoteSlot> = emptyList(),
    val hid: HidSpec = HidSpec(),
    val images: List<RemoteImage> = emptyList(),
    val hidLink: HidLinkStatus = HidLinkStatus.STOPPED,
    /** Android's USB tethering; null until Android has reported it. */
    val usbTethering: Boolean? = null,
)

/**
 * The app's HID session as remote clients see it (`state.hid.link`). Coarser than the app's link
 * state on purpose (no attempt counts, no host-listening flips), so it changes only when the link does.
 */
enum class HidLinkStatus(
    val wireName: String,
) {
    /** No session yet; the first input opens one. */
    STOPPED("stopped"),
    CONNECTING("connecting"),
    OPEN("open"),

    /** Root dropped the fds (a gadget change, a re-enumeration) or an open failed; input is refused until it reopens. */
    RECONNECTING("reconnecting"),

    /** Gave up; the next input retries. */
    FAILED("failed"),

    /** An fd was open across someone else's rebind; keyboard and mouse work again only after the phone restarts. */
    RESTART_REQUIRED("restart_required"),
}

sealed interface DeviceOp {
    data class Apply(
        val desired: Desired,
    ) : DeviceOp

    data class Eject(
        val lun: Int,
        val force: Boolean,
    ) : DeviceOp

    data class Reinsert(
        val lun: Int,
    ) : DeviceOp

    data class SetHid(
        val spec: HidSpec,
    ) : DeviceOp

    data class SetUsbTethering(
        val on: Boolean,
    ) : DeviceOp
}

sealed interface DeviceOutcome {
    data object Applied : DeviceOutcome

    data class Partial(
        val slotFailures: Map<Int, String>,
    ) : DeviceOutcome

    data class Failed(
        val reason: String,
    ) : DeviceOutcome

    data class NeedsAttention(
        val reason: String,
    ) : DeviceOutcome

    data object TimedOut : DeviceOutcome

    /** Not started: an earlier operation (one that timed out) is still running on the device lane. */
    data object Busy : DeviceOutcome

    /** USB tethering is now [active]; [warning] when it changed but the gadget then needs attention. */
    data class Tethering(
        val active: Boolean,
        val warning: String?,
    ) : DeviceOutcome

    /** USB tethering refused: a disk is exposed and the re-plug would pull it from the host. */
    data class DisksExposed(
        val reason: String,
    ) : DeviceOutcome

    companion object {
        fun of(result: ApplyResult): DeviceOutcome =
            when (result) {
                is ApplyResult.Applied -> Applied
                is ApplyResult.Partial -> Partial(result.slotFailures)
                is ApplyResult.Failed -> Failed(result.reason)
                is ApplyResult.NeedsAttention -> NeedsAttention(result.reason)
            }

        fun of(result: TetherResult): DeviceOutcome =
            when (result) {
                is TetherResult.Changed -> {
                    val warnings =
                        listOfNotNull(
                            "the USB switch didn't settle in time".takeUnless { result.switchSettled },
                            when (val reconcile = result.reconcile) {
                                is ApplyResult.Applied -> null
                                is ApplyResult.Partial ->
                                    "some slots failed: " + reconcile.slotFailures.entries.joinToString { "${it.key}: ${it.value}" }
                                is ApplyResult.Failed -> "drives failed: ${reconcile.reason}"
                                is ApplyResult.NeedsAttention -> "drives need attention: ${reconcile.reason}"
                            },
                        )
                    Tethering(result.active, warnings.joinToString("; ").ifEmpty { null })
                }
                is TetherResult.Unchanged -> Tethering(result.active, warning = null)
                is TetherResult.Refused -> DisksExposed(result.reason)
                is TetherResult.Failed -> Failed(result.reason)
            }
    }
}

sealed interface LeaseHolder {
    data class Remote(
        val sessionId: Long,
    ) : LeaseHolder

    /** The phone's Control tab is in the foreground. */
    data object Local : LeaseHolder
}

data class SessionStatus(
    val sessionId: Long,
    val transport: String,
    val displayName: String,
    val auth: AuthStatus,
    val holdsLease: Boolean,
)

data class PairingStatus(
    /** The current single-use code, or null when pairing is off. */
    val code: String?,
    /** On the controller's elapsed clock. */
    val codeExpiresAtElapsedMs: Long?,
    val lockedOut: Boolean,
    val attemptsInWindow: Int,
)

/** Everything the app UI shows about remote control. */
data class RemoteStatus(
    val sessions: List<SessionStatus> = emptyList(),
    val pending: List<PendingApproval> = emptyList(),
    val approvals: List<ApprovalRecord> = emptyList(),
    val tokens: List<TokenRecord> = emptyList(),
    val leaseHolder: LeaseHolder? = null,
    val leaseEpoch: Int = 0,
    val pairing: PairingStatus = PairingStatus(null, null, lockedOut = false, attemptsInWindow = 0),
    val releasePending: Boolean = false,
    val runningJob: JobKind? = null,
)

internal fun sha256Hex(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

enum class JobKind { PASTE, BOOTKEY }

/** One distro of the app's cached catalog (`CatalogRepository`), as the remote sees it. */
data class CatalogDistroSnapshot(
    val distro: Distro,
    val releases: List<Release>,
    /** Wall-clock Unix ms of the last good fetch; null if never fetched. */
    val fetchedAtWallMs: Long?,
    val error: String?,
)

data class CatalogSnapshot(
    val distros: List<CatalogDistroSnapshot> = emptyList(),
    val refreshing: Boolean = false,
)

enum class RemoteDownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    ;

    /** Counts toward [RemoteLimits.DOWNLOAD_ACTIVE_COUNT_MAX]. */
    val isActive: Boolean get() = this == QUEUED || this == RUNNING || this == VERIFYING
}

/** One download record (from the phone or the remote), keyed by [fileName] like the app's store. */
data class RemoteDownload(
    val fileName: String,
    val title: String,
    val status: RemoteDownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val verified: Boolean,
    val error: String?,
) {
    val entryId: String get() = CatalogEntryIds.idFor(fileName)
}

/** Catalog entries and their downloads are named by an opaque id of the stored file name (the store's key). */
object CatalogEntryIds {
    private const val ENTRY_ID_HEX_LENGTH = 16

    fun idFor(fileName: String): String = sha256Hex("catalog\n$fileName").take(ENTRY_ID_HEX_LENGTH)
}

sealed interface DownloadOp {
    data class Start(
        val release: Release,
        val distroName: String,
    ) : DownloadOp

    data class Cancel(
        val fileName: String,
    ) : DownloadOp
}

sealed interface DownloadOutcome {
    data object Started : DownloadOutcome

    data object AlreadyActive : DownloadOutcome

    data object AlreadyDownloaded : DownloadOutcome

    data class Cancelled(
        val cancelled: Boolean,
    ) : DownloadOutcome

    /** [RemoteLimits.DOWNLOAD_ACTIVE_COUNT_MAX] downloads are active, or the download list is full. */
    data class TooMany(
        val reason: String,
    ) : DownloadOutcome

    data class StorageLow(
        val reason: String,
    ) : DownloadOutcome

    data class Rejected(
        val reason: String,
    ) : DownloadOutcome

    data object TimedOut : DownloadOutcome

    /** Not started: an earlier call (one that timed out) is still running on the download lane. */
    data object Busy : DownloadOutcome
}
