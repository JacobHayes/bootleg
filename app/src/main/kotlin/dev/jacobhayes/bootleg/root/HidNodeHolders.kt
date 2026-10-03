package dev.jacobhayes.bootleg.root

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.hid.DeviceNumber
import java.io.File
import java.io.IOException

/**
 * Finds every process holding a hid function's `/dev/hidgN` open (as root), by scanning
 * `/proc/<pid>/fd`. Only fds whose link points under `/dev/` are stat'ed, so the scan never calls
 * into another filesystem (a hung FUSE daemon can't stall it). Bounded in processes, fds and time;
 * a scan that hits a bound reports itself as a holder, so callers fail closed.
 */
class HidNodeHolders(
    private val proc: Proc = RealProc,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    /** The few /proc and configfs reads the scan needs; a fake in JVM tests. */
    interface Proc {
        /** `dev` ("major:minor") of a gadget function, or null if it doesn't exist; throws IOException if unreadable. */
        fun functionDev(function: String): String?

        /** Every pid, or null if /proc can't be listed. */
        fun pids(): List<Int>?

        /** fd numbers of [pid]: empty if it exited, null if it is alive but its fds can't be listed. */
        fun fds(pid: Int): List<Int>?

        /** Link target of `/proc/<pid>/fd/<fd>`, or null if it went away; other failures throw IOException. */
        fun fdTarget(
            pid: Int,
            fd: Int,
        ): String?

        /** Device number if `/proc/<pid>/fd/<fd>` is a character device, else null; failures as [fdTarget]. */
        fun fdCharDevice(
            pid: Int,
            fd: Int,
        ): DeviceNumber?

        fun processName(pid: Int): String
    }

    /** Any read the scan can't trust (anything but a process or fd that went away) fails it closed. */
    fun scan(functions: Collection<String>): List<String> {
        require(functions.size <= FUNCTION_COUNT_MAX) { "${functions.size} functions to scan; limit is $FUNCTION_COUNT_MAX" }
        return try {
            scanOrThrow(functions)
        } catch (e: IOException) {
            listOf("holder scan incomplete: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // A malformed `dev` attribute.
            listOf("holder scan incomplete: ${e.message}")
        }
    }

    private fun scanOrThrow(functions: Collection<String>): List<String> {
        val wanted =
            functions
                .mapNotNull { function -> proc.functionDev(function)?.let { DeviceNumber.parse(it) to function } }
                .toMap()
        if (wanted.isEmpty()) return emptyList()
        val deadlineMs = nowMs() + SCAN_MS_MAX
        val pids = proc.pids() ?: return listOf("holder scan incomplete: /proc can't be listed")
        if (pids.size > PROCESS_COUNT_MAX) return listOf("holder scan incomplete: ${pids.size} processes (limit $PROCESS_COUNT_MAX)")
        val holders = mutableListOf<String>()
        var fdCount = 0
        for (pid in pids) {
            if (nowMs() > deadlineMs) return holders + "holder scan incomplete: over ${SCAN_MS_MAX}ms"
            val fds = proc.fds(pid) ?: return holders + "holder scan incomplete: fds of pid $pid can't be listed"
            fdCount += fds.size
            if (fdCount > FD_COUNT_MAX) return holders + "holder scan incomplete: over $FD_COUNT_MAX fds"
            for (fd in fds) {
                val target = proc.fdTarget(pid, fd) ?: continue
                if (!target.startsWith(DEVICE_PREFIX)) continue
                val function = proc.fdCharDevice(pid, fd)?.let(wanted::get) ?: continue
                holders += "pid $pid (${proc.processName(pid)}) fd $fd -> $target ($function)"
            }
        }
        return holders
    }

    private object RealProc : Proc {
        private const val PROC = "/proc"
        private const val NAME_LENGTH_MAX = 64

        override fun functionDev(function: String): String? {
            if (!exists("${GadgetNames.GADGET_ROOT}/functions/$function")) return null
            // An existing function whose dev can't be read must not count as "nothing to check".
            return File("${GadgetNames.GADGET_ROOT}/functions/$function/dev").readText()
        }

        override fun pids(): List<Int>? = File(PROC).list()?.mapNotNull { it.toIntOrNull() }

        override fun fds(pid: Int): List<Int>? {
            val names = File("$PROC/$pid/fd").list() ?: return if (exists("$PROC/$pid")) null else emptyList()
            return names.mapNotNull { it.toIntOrNull() }
        }

        override fun fdTarget(
            pid: Int,
            fd: Int,
        ): String? =
            try {
                Os.readlink("$PROC/$pid/fd/$fd")
            } catch (e: ErrnoException) {
                goneOrThrow(e, pid, fd)
            }

        override fun fdCharDevice(
            pid: Int,
            fd: Int,
        ): DeviceNumber? =
            try {
                val stat = Os.stat("$PROC/$pid/fd/$fd")
                if (OsConstants.S_ISCHR(stat.st_mode)) DeviceNumber.fromRdev(stat.st_rdev) else null
            } catch (e: ErrnoException) {
                goneOrThrow(e, pid, fd)
            }

        /** False only for ENOENT; any other stat failure (EACCES, ...) throws, since File.exists() would read it as missing. */
        private fun exists(path: String): Boolean =
            try {
                Os.stat(path)
                true
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ENOENT) throw IOException("$path: ${e.message}", e)
                false
            }

        /** Null for a process or fd that exited mid-scan; anything else makes the scan untrustworthy. */
        private fun <T> goneOrThrow(
            e: ErrnoException,
            pid: Int,
            fd: Int,
        ): T? {
            if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ESRCH) return null
            throw IOException("pid $pid fd $fd: ${e.message}", e)
        }

        override fun processName(pid: Int): String =
            runCatching {
                File("$PROC/$pid/comm")
                    .readText()
                    .trim()
                    .take(NAME_LENGTH_MAX)
            }.getOrDefault("?")
    }

    companion object {
        const val PROCESS_COUNT_MAX = 4_096
        const val FD_COUNT_MAX = 262_144
        const val SCAN_MS_MAX = 2_000L
        const val FUNCTION_COUNT_MAX = 16
        private const val DEVICE_PREFIX = "/dev/"
    }
}
