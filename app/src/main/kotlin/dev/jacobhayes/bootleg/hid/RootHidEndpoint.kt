package dev.jacobhayes.bootleg.hid

import android.os.RemoteException
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.root.IBootlegRoot

/**
 * App side of the root HID relay: each report is one synchronous binder call (well under 1ms),
 * made from the session's single writer, so reports keep their order. A dead root service reads
 * as a disconnect, and the session reopens through the backend (which rebinds root).
 */
class RootHidEndpoint(
    private val root: IBootlegRoot,
    private val kind: HidKind,
) : HidEndpoint {
    override fun write(report: ByteArray): HidWriteResult =
        try {
            val ordinal = root.hidWrite(kind.wireName, report)
            HidWriteResult.entries.getOrNull(ordinal) ?: HidWriteResult.FAILED
        } catch (e: RemoteException) {
            HidWriteResult.DISCONNECTED
        }

    override fun readOutputReport(): ByteArray? =
        try {
            root.hidReadOutput(kind.wireName).takeIf { it.isNotEmpty() }
        } catch (e: RemoteException) {
            null
        }

    override fun close() {
        try {
            root.hidClose(kind.wireName)
        } catch (e: RemoteException) {
            // Root is gone; its death handling already released everything.
        }
    }
}
