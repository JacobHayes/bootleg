package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.hid.HidKind

/** Inputs to [RemoteController.handle]. */
sealed interface RemoteEvent {
    // From Go (bridge callbacks).
    data class Open(
        val sessionId: Long,
        val connJson: String,
    ) : RemoteEvent

    data class Command(
        val sessionId: Long,
        val json: String,
    ) : RemoteEvent

    class Hid(
        val sessionId: Long,
        val frame: ByteArray,
    ) : RemoteEvent {
        override fun toString(): String = "Hid($sessionId, ${frame.joinToString(" ") { "%02x".format(it) }})"
    }

    /** Idempotent. `reason` is Go's (e.g. `dead`, `client_closed`); informational only. */
    data class Closed(
        val sessionId: Long,
        val reason: String,
    ) : RemoteEvent

    // From the actor.
    data object Tick : RemoteEvent

    /** [TransportPort.send] refused a message: the session is a slow consumer. */
    data class SendFailed(
        val sessionId: Long,
    ) : RemoteEvent

    /** The actor's inbound queue for this session overflowed and dropped a command: close it. */
    data class CommandOverflow(
        val sessionId: Long,
    ) : RemoteEvent

    /** The actor's inbound queue for this session overflowed and dropped HID input. */
    data class InputOverflow(
        val sessionId: Long,
    ) : RemoteEvent

    data class HidRejected(
        val result: HidSendResult,
    ) : RemoteEvent

    data class DeviceResult(
        val opId: Long,
        val outcome: DeviceOutcome,
    ) : RemoteEvent

    data class DownloadResult(
        val opId: Long,
        val outcome: DownloadOutcome,
    ) : RemoteEvent

    // From the app.
    data class DeviceChanged(
        val snapshot: DeviceSnapshot,
    ) : RemoteEvent

    data class CatalogChanged(
        val snapshot: CatalogSnapshot,
    ) : RemoteEvent

    data class DownloadsChanged(
        val downloads: List<RemoteDownload>,
    ) : RemoteEvent

    /** The HidSession's owed release-all reached the host (or its endpoints reopened). */
    data object HidReleaseDelivered : RemoteEvent

    data object LocalCaptureStarted : RemoteEvent

    data object LocalCaptureStopped : RemoteEvent

    data class Approve(
        val nodeId: String,
        val allowRw: Boolean,
    ) : RemoteEvent

    data class Deny(
        val nodeId: String,
    ) : RemoteEvent

    data class Revoke(
        val principal: Principal,
    ) : RemoteEvent

    data class SetAllowRw(
        val principal: Principal,
        val allowRw: Boolean,
    ) : RemoteEvent

    data object PairingEnable : RemoteEvent

    data object PairingDisable : RemoteEvent

    data object PairingLockoutReset : RemoteEvent

    data object HotspotStopped : RemoteEvent

    /** Remote turned off: close everything and release all input. */
    data object Shutdown : RemoteEvent
}

/** Outputs of [RemoteController.handle] (HID effects first), dispatched in order by [RemoteActor]. */
sealed interface RemoteEffect {
    data class Authorize(
        val sessionId: Long,
    ) : RemoteEffect

    data class Send(
        val sessionId: Long,
        val json: String,
    ) : RemoteEffect

    data class Close(
        val sessionId: Long,
        val reason: CloseReason,
    ) : RemoteEffect

    data class Revoke(
        val principal: Principal,
    ) : RemoteEffect

    /** Drop anything the actor still queues for this session's input. */
    data class DropQueuedInput(
        val sessionId: Long,
    ) : RemoteEffect

    /** One report on behalf of the lease holder [sessionId] at [epoch] (fenced in the emitting step). */
    class HidSend(
        val sessionId: Long,
        val epoch: Int,
        val kind: HidKind,
        val report: ByteArray,
    ) : RemoteEffect {
        override fun equals(other: Any?): Boolean =
            other is HidSend &&
                sessionId == other.sessionId &&
                epoch == other.epoch &&
                kind == other.kind &&
                report.contentEquals(other.report)

        override fun hashCode(): Int = ((sessionId.hashCode() * 31 + epoch) * 31 + kind.hashCode()) * 31 + report.contentHashCode()

        override fun toString(): String = "HidSend($sessionId@$epoch, $kind, ${report.joinToString(" ") { "%02x".format(it) }})"
    }

    /** Priority release-all ([HidPort.releaseAll]). */
    data object HidReleaseAll : RemoteEffect

    data class Device(
        val opId: Long,
        val op: DeviceOp,
    ) : RemoteEffect

    data class Download(
        val opId: Long,
        val op: DownloadOp,
    ) : RemoteEffect

    /** [DownloadPort.refreshCatalogIfStale]. */
    data object CatalogRefreshIfStale : RemoteEffect

    data class SaveApprovals(
        val records: List<ApprovalRecord>,
    ) : RemoteEffect

    data class SavePairing(
        val record: PairingRecord,
    ) : RemoteEffect

    data class PendingChanged(
        val pending: List<PendingApproval>,
    ) : RemoteEffect

    /** Every lease change, in order; no port, but tests and status follow it. */
    data class LeaseChanged(
        val holder: LeaseHolder?,
        val epoch: Int,
    ) : RemoteEffect
}
