package dev.jacobhayes.bootleg.hid

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.HidLimits

/**
 * A `/dev/hidgN` descriptor opened O_RDWR|O_NONBLOCK. Used inside the root process only: the node
 * is labelled `device`, which apps may not read or write, so reports reach it through
 * [dev.jacobhayes.bootleg.root.HidRelay].
 */
class FdHidEndpoint(
    private val kind: HidKind,
    private val descriptor: ParcelFileDescriptor,
) : HidEndpoint {
    private val readBuffer = ByteArray(OUTPUT_REPORT_SIZE_BYTES_MAX)

    override fun write(report: ByteArray): HidWriteResult {
        require(report.size == kind.reportSizeBytes) { "$kind report must be ${kind.reportSizeBytes} bytes" }
        return try {
            // f_hid takes a report in one write or not at all.
            val writtenBytes = Os.write(descriptor.fileDescriptor, report, 0, report.size)
            if (writtenBytes == report.size) HidWriteResult.WRITTEN else HidWriteResult.FAILED
        } catch (e: ErrnoException) {
            when (e.errno) {
                OsConstants.EAGAIN, OsConstants.EINTR -> HidWriteResult.WOULD_BLOCK
                OsConstants.ENXIO, ESHUTDOWN, OsConstants.ENODEV, OsConstants.EPIPE, OsConstants.EIO -> HidWriteResult.DISCONNECTED
                else -> HidWriteResult.FAILED
            }
        }
    }

    /** Nonblocking writes, waiting for POLLOUT in between, for at most [deadlineMs]. */
    fun writeWithin(
        report: ByteArray,
        deadlineMs: Long = HidLimits.REPORT_WRITE_DEADLINE_MS,
    ): HidWriteResult {
        require(deadlineMs in 0..HidLimits.REPORT_WRITE_DEADLINE_MS) { "deadline must be 0..${HidLimits.REPORT_WRITE_DEADLINE_MS} ms" }
        val endMs = SystemClock.elapsedRealtime() + deadlineMs
        while (true) {
            val result = write(report)
            val remainingMs = endMs - SystemClock.elapsedRealtime()
            if (result != HidWriteResult.WOULD_BLOCK || remainingMs <= 0) return result
            val pollFd =
                StructPollfd().apply {
                    fd = descriptor.fileDescriptor
                    events = OsConstants.POLLOUT.toShort()
                }
            try {
                Os.poll(arrayOf(pollFd), remainingMs.toInt())
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR) return HidWriteResult.FAILED
            }
        }
    }

    override fun readOutputReport(): ByteArray? =
        try {
            val count = Os.read(descriptor.fileDescriptor, readBuffer, 0, readBuffer.size)
            if (count > 0) readBuffer.copyOf(count) else null
        } catch (e: ErrnoException) {
            null
        }

    override fun close() = descriptor.close()

    private companion object {
        /** Linux errno 108; android.system.OsConstants doesn't expose it. f_hid returns it when the host isn't connected. */
        const val ESHUTDOWN = 108

        /** The keyboard's LED report is one byte; f_hid never hands back more than the report length. */
        const val OUTPUT_REPORT_SIZE_BYTES_MAX = 64
    }
}
