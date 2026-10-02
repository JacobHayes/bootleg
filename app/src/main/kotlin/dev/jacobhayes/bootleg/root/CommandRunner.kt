package dev.jacobhayes.bootleg.root

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a program by argv (never through a shell string) with a timeout and bounded output. Each
 * command starts in its own session ([sessionLauncher], `setsid` on the phone), so it leads a process
 * group holding everything it starts. A timeout kills that whole group, and the run returns only once
 * the group is gone and the output reader has finished (each bounded), so nothing a timed-out command
 * spawned is left running or holding our pipe.
 *
 * [signalGroup] sends a signal to a process group: true if it was delivered, false if no process is
 * left in the group (signal 0 only checks).
 */
class CommandRunner(
    private val sessionLauncher: List<String>,
    private val signalGroup: (processGroupId: Int, signal: Int) -> Boolean,
    private val killWaitMs: Long = KILL_WAIT_MS,
    private val readerJoinTimeoutMs: Long = READER_JOIN_TIMEOUT_MS,
) {
    data class Output(
        val exitCode: Int,
        val text: String,
    )

    fun run(
        argv: List<String>,
        timeoutMs: Long,
    ): Output {
        require(argv.isNotEmpty()) { "empty argv" }
        require(timeoutMs > 0) { "timeoutMs must be positive: $timeoutMs" }
        // The shell prints its pid (the session and group leader, since setsid execs it) and then execs
        // the program by argv, keeping that pid.
        val process =
            ProcessBuilder(sessionLauncher + listOf("sh", "-c", PRINT_PID_THEN_EXEC, "bootleg-cmd") + argv)
                .redirectErrorStream(true)
                .redirectInput(File("/dev/null"))
                .start()
        val output = BoundedOutput()
        val reader =
            thread(name = "root-cmd-output", isDaemon = true) {
                val buffer = ByteArray(READ_CHUNK_BYTES)
                process.inputStream.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.append(buffer, count)
                    }
                }
            }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            terminate(process, output, reader)
            error("${argv[0]} timed out after ${timeoutMs}ms")
        }
        reader.join(readerJoinTimeoutMs)
        // Exited, but something it started still holds the output pipe: end the group too.
        if (reader.isAlive) terminate(process, output, reader)
        return Output(process.exitValue(), output.textAfterPid().trim())
    }

    /** SIGKILLs the command's process group, then waits (bounded) until the group and the output reader are gone. */
    private fun terminate(
        process: Process,
        output: BoundedOutput,
        reader: Thread,
    ) {
        val processGroupId = output.processGroupId()
        processGroupId?.let { signalGroup(it, SIGKILL) }
        process.destroyForcibly()
        check(process.waitFor(killWaitMs, TimeUnit.MILLISECONDS)) { "command did not exit within ${killWaitMs}ms of SIGKILL" }
        if (processGroupId != null) {
            val deadlineMs = System.nanoTime() / NANOS_PER_MS + killWaitMs
            while (signalGroup(processGroupId, SIGNAL_CHECK_ONLY)) {
                check(
                    System.nanoTime() / NANOS_PER_MS < deadlineMs,
                ) { "process group $processGroupId survived SIGKILL for ${killWaitMs}ms" }
                Thread.sleep(GROUP_POLL_INTERVAL_MS)
            }
        }
        reader.join(readerJoinTimeoutMs)
        check(!reader.isAlive) { "command output stayed open ${readerJoinTimeoutMs}ms after its process group ended" }
    }

    /** Output capped at [OUTPUT_BYTES_MAX]; its first line is the pid the wrapper shell printed. */
    private class BoundedOutput {
        private val bytes = ByteArrayOutputStream()

        @Synchronized
        fun append(
            buffer: ByteArray,
            count: Int,
        ) {
            val room = OUTPUT_BYTES_MAX - bytes.size()
            if (room > 0) bytes.write(buffer, 0, minOf(count, room))
        }

        @Synchronized
        fun processGroupId(): Int? {
            val text = bytes.toString(Charsets.UTF_8.name())
            if ('\n' !in text) return null
            return text.substringBefore('\n').toIntOrNull()?.takeIf { it > 1 }
        }

        @Synchronized
        fun textAfterPid(): String = bytes.toString(Charsets.UTF_8.name()).substringAfter('\n', "")
    }

    companion object {
        const val SIGKILL = 9
        const val SIGNAL_CHECK_ONLY = 0
        const val KILL_WAIT_MS = 2_000L
        const val READER_JOIN_TIMEOUT_MS = 1_000L
        private const val OUTPUT_BYTES_MAX = 64 * 1024
        private const val READ_CHUNK_BYTES = 4096
        private const val GROUP_POLL_INTERVAL_MS = 10L
        private const val NANOS_PER_MS = 1_000_000L
        private const val PRINT_PID_THEN_EXEC = "echo \$\$; exec \"\$@\""
    }
}
