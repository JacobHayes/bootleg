package dev.jacobhayes.bootleg.root

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Runs real processes. The phone uses toybox `setsid` and `Os.kill`; perl stands in for both on a dev machine or CI. */
class CommandRunnerTest {
    @TempDir
    lateinit var directory: File

    private val runner =
        CommandRunner(
            sessionLauncher =
                listOf(
                    "perl",
                    "-MPOSIX",
                    "-e",
                    "POSIX::setsid() or die \"setsid: \$!\"; exec { \$ARGV[0] } @ARGV or die \"exec: \$!\"",
                ),
            signalGroup = { processGroupId, signal ->
                ProcessBuilder("perl", "-e", "exit(kill(\$ARGV[1], -\$ARGV[0]) ? 0 : 1)", processGroupId.toString(), signal.toString())
                    .start()
                    .waitFor() == 0
            },
        )

    private fun alive(pid: Int): Boolean = ProcessBuilder("kill", "-0", pid.toString()).start().waitFor() == 0

    /** Waits (bounded) for [file] to hold a pid written by the command under test. */
    private fun pidIn(file: File): Int {
        repeat(200) {
            file
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.toIntOrNull()
                ?.let { return it }
            Thread.sleep(10)
        }
        error("no pid in $file")
    }

    @Test
    fun `returns the exit code and output, without the wrapper's pid line`() {
        val output = runner.run(listOf("sh", "-c", "echo hello; echo world >&2; exit 3"), timeoutMs = 5_000)
        assertEquals(CommandRunner.Output(3, "hello\nworld"), output)
    }

    @Test
    fun `arguments pass through untouched, never as shell text`() {
        val output = runner.run(listOf("printf", "%s|", "a b", "\$HOME", "; echo injected"), timeoutMs = 5_000)
        assertEquals("a b|\$HOME|; echo injected|", output.text)
    }

    @Test
    fun `a timeout kills the whole process tree before returning`() {
        val pidFile = File(directory, "child.pid")
        val startNs = System.nanoTime()
        val error =
            assertThrows<IllegalStateException> {
                runner.run(listOf("sh", "-c", "sleep 60 & echo \$! > \"\$0\"; wait", pidFile.path), timeoutMs = 300)
            }
        assertTrue("timed out" in error.message.orEmpty(), error.message)
        val child = pidIn(pidFile)
        assertFalse(alive(child), "the command's child $child must be dead when run() returns")
        assertTrue((System.nanoTime() - startNs) / 1_000_000 < 5_000, "terminating is bounded")
    }

    @Test
    fun `a background child left holding the output pipe is ended too`() {
        val pidFile = File(directory, "child.pid")
        val startNs = System.nanoTime()
        val output = runner.run(listOf("sh", "-c", "sleep 60 & echo \$! > \"\$0\"; echo done", pidFile.path), timeoutMs = 5_000)
        assertEquals(CommandRunner.Output(0, "done"), output)
        assertFalse(alive(pidIn(pidFile)), "the lingering child must be dead when run() returns")
        assertTrue((System.nanoTime() - startNs) / 1_000_000 < 5_000, "returns without waiting for the child")
    }
}
