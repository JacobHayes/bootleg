package dev.jacobhayes.bootleg.root

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.jacobhayes.bootleg.core.gadget.Clock
import dev.jacobhayes.bootleg.core.gadget.RndisDaemon
import dev.jacobhayes.bootleg.core.gadget.RndisState
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/** The daemon's status file (`rndis/cmd/bootleg-rndis`). */
@Serializable
data class RndisStatus(
    val pid: Int,
    val released: Boolean,
    val usb: String,
    val host: Boolean,
    val media: Boolean,
    val drained: Boolean,
)

@Serializable
private data class RndisCommand(
    val release: Boolean,
    val media: Boolean,
)

/**
 * The `bootleg-rndis` daemon serving the RNDIS function. It is its own process (setsid, stdio on files),
 * so this root process dying never leaves the function unserved, and it runs from a root-owned copy at
 * a fixed path, so a later root process (after an APK update too) recognizes it by its command line.
 * Every wait is bounded. Not thread-safe: the root service calls it under the gadget lock.
 */
class RndisDaemons(
    private val apkBinary: File,
    private val macSeed: String,
    private val clock: Clock,
    /** Called on a thread of its own when a daemon this process started exits, for whatever reason. */
    private val onExit: () -> Unit,
    private val run: (argv: List<String>, timeoutMs: Long) -> CommandRunner.Output = RootCommands::run,
    private val stateDirectory: File = File("/data/adb/bootleg/rndis"),
) : RndisDaemon {
    private val binary = File(stateDirectory, "bootleg-rndis")

    override fun state(): RndisState {
        if (!mounted()) return RndisState.ABSENT
        val status = status()?.takeIf { isOurs(it.pid) } ?: return RndisState.DEAD
        return if (status.released) RndisState.READY else RndisState.HELD
    }

    override fun start() {
        // Two ep0 holders can wedge the kernel's unbind, and the daemon lock refuses a second one.
        daemonPids().forEach(::stop)
        install()
        if (!mounted()) {
            File(MOUNT_POINT).mkdirs()
            // no_disconnect: a daemon that dies leaves the gadget bound (its disks keep working) until
            // RNDIS recovery restarts it.
            val output =
                run(inInitNamespace("mount", "-t", "functionfs", "-o", "no_disconnect=1", INSTANCE, MOUNT_POINT), COMMAND_TIMEOUT_MS)
            if (output.exitCode != 0) throw IOException("mount functionfs $INSTANCE failed: ${output.text}")
        }
        listOf(COMMAND_FILE, STATUS_FILE).forEach { File(stateDirectory, it).delete() }
        val argv =
            inInitNamespace(
                "setsid",
                binary.path,
                "-ffs",
                MOUNT_POINT,
                "-tap",
                TAP_INTERFACE,
                "-state",
                stateDirectory.path,
                "-mac-seed",
                macSeed,
            )
        val process =
            ProcessBuilder(argv)
                .redirectInput(File("/dev/null"))
                .redirectOutput(File("/dev/null"))
                .redirectError(File(stateDirectory, STDERR_FILE))
                .start()
        // nsenter and setsid exec in place, so the daemon is our child: its death sends no USB broadcast, but this sees it.
        thread(isDaemon = true, name = "bootleg-rndis-exit") {
            process.waitFor()
            onExit()
        }
        if (!poll(START_WAIT_MS_MAX) { status()?.let { !it.released && isOurs(it.pid) } == true }) {
            throw IOException("the RNDIS daemon didn't start within ${START_WAIT_MS_MAX}ms: ${stderrTail()}")
        }
    }

    override fun release() = command(release = true, media = status()?.media == true) { it.released }

    override fun stop() {
        daemonPids().forEach(::stop)
        if (mounted()) {
            val output = run(inInitNamespace("umount", MOUNT_POINT), COMMAND_TIMEOUT_MS)
            if (output.exitCode != 0) throw IOException("umount $MOUNT_POINT failed: ${output.text}")
        }
        File(MOUNT_POINT).delete()
    }

    /** SIGKILLs every daemon of ours without waiting: a UDC write stuck behind its ep0 needs it gone at once. */
    fun kill() = daemonPids().forEach { signal(it, OsConstants.SIGKILL) }

    /** The daemon's last status, or null when there is none (or it can't be parsed). */
    fun status(): RndisStatus? =
        runCatching { BootlegJson.decodeFromString<RndisStatus>(File(stateDirectory, STATUS_FILE).readText()) }.getOrNull()

    /** Writes a command (a level: the state wanted, not a change), signals the daemon and waits until [done]. */
    private fun command(
        release: Boolean,
        media: Boolean,
        done: (RndisStatus) -> Boolean,
    ) {
        val pid = status()?.pid?.takeIf(::isOurs) ?: throw IOException("no RNDIS daemon")
        writeAtomically(File(stateDirectory, COMMAND_FILE), BootlegJson.encodeToString(RndisCommand(release, media)).toByteArray())
        signal(pid, OsConstants.SIGUSR1)
        if (!poll(COMMAND_WAIT_MS_MAX) { status()?.let { it.pid == pid && done(it) } == true }) {
            throw IOException(
                "the RNDIS daemon didn't apply release=$release media=$media within ${COMMAND_WAIT_MS_MAX}ms: ${stderrTail()}",
            )
        }
    }

    /** Copies the APK's daemon to the fixed root-owned path when it differs (never while one runs). */
    private fun install() {
        check(apkBinary.canExecute()) { "no RNDIS daemon at $apkBinary" }
        if (!stateDirectory.exists()) check(stateDirectory.mkdirs()) { "cannot create $stateDirectory" }
        Os.chmod(stateDirectory.path, ROOT_STATE_DIRECTORY_MODE)
        val bytes = apkBinary.readBytes()
        if (binary.exists() && binary.length() == bytes.size.toLong() && binary.readBytes().contentEquals(bytes)) return
        writeAtomically(binary, bytes)
        Os.chmod(binary.path, ROOT_STATE_DIRECTORY_MODE)
    }

    private fun stop(pid: Int) {
        signal(pid, OsConstants.SIGTERM)
        if (poll(STOP_WAIT_MS_MAX) { !isOurs(pid) }) return
        signal(pid, OsConstants.SIGKILL)
        if (!poll(STOP_WAIT_MS_MAX) { !isOurs(pid) }) throw IOException("the RNDIS daemon (pid $pid) won't exit")
    }

    /** Daemons on our mount, found through /proc (bounded): the status file may name none, or a dead one. */
    private fun daemonPids(): List<Int> =
        File("/proc")
            .list()
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .take(PROCESS_SCAN_MAX)
            .filter(::isOurs)

    private fun isOurs(pid: Int): Boolean {
        val arguments = runCatching { String(File("/proc/$pid/cmdline").readBytes()).split('\u0000') }.getOrNull() ?: return false
        return arguments.firstOrNull() == binary.path && MOUNT_POINT in arguments
    }

    /**
     * This process's mount namespace is the app's, private to it: a mount made there would be invisible
     * to the next root process (an APK update starts one) and would live on with any process left in it.
     * The mount, and the daemon with it, live in init's namespace instead (nsenter execs; no fork).
     */
    private fun inInitNamespace(vararg argv: String): List<String> = listOf("nsenter", "-t", "1", "-m", "--") + argv

    private fun mounted(): Boolean =
        runCatching { File("/proc/1/mounts").readLines() }
            .getOrDefault(emptyList())
            .any { line -> line.split(' ').let { it.size > 2 && it[1] == MOUNT_POINT && it[2] == "functionfs" } }

    private fun signal(
        pid: Int,
        signal: Int,
    ) {
        try {
            Os.kill(pid, signal)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.ESRCH) throw IOException("kill $pid: ${e.message}", e)
        }
    }

    private fun stderrTail(): String =
        runCatching { File(stateDirectory, STDERR_FILE).readLines().takeLast(STDERR_LINES_REPORTED).joinToString(" | ") }.getOrDefault("")

    private fun poll(
        timeoutMs: Long,
        done: () -> Boolean,
    ): Boolean {
        val deadlineMs = clock.nowMs() + timeoutMs
        repeat((timeoutMs / POLL_INTERVAL_MS).toInt() + 1) {
            if (done()) return true
            if (clock.nowMs() >= deadlineMs) return false
            clock.sleepMs(POLL_INTERVAL_MS)
        }
        return done()
    }

    companion object {
        const val INSTANCE = "bootleg_rndis"
        const val MOUNT_POINT = "/dev/bootleg-rndis"
        const val TAP_INTERFACE = "bootleg_rndis"
        const val APK_BINARY_NAME = "libbootlegrndis.so"
        const val START_WAIT_MS_MAX = 3_000L
        const val STOP_WAIT_MS_MAX = 2_000L
        const val COMMAND_WAIT_MS_MAX = 2_000L
        const val COMMAND_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 25L
        const val STDERR_LINES_REPORTED = 3
        private const val COMMAND_FILE = "command.json"
        private const val STATUS_FILE = "status.json"
        private const val STDERR_FILE = "stderr"
        private const val PROCESS_SCAN_MAX = 65_536
    }
}
