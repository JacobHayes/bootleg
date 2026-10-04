package dev.jacobhayes.bootleg.root

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The dead-man's shell script against plain files: a `UDC` file, a config directory with links, a
 * fake `/proc`, and a `timeout` stand-in on PATH that can fail a bind (as an unserved RNDIS function
 * does) or replace the token mid-run (a disarm racing the watchdog).
 */
class ShellDeadManScriptTest {
    @TempDir
    lateinit var directory: Path

    private val udc by lazy { File(directory.toFile(), "UDC").apply { writeText("") } }
    private val token by lazy { File(directory.toFile(), "token").apply { writeText("mine") } }
    private val config by lazy { File(directory.toFile(), "configs/b.1").apply { mkdirs() } }
    private val proc by lazy { File(directory.toFile(), "proc").also { File(it, "42/fd").mkdirs() } }
    private val bin by lazy { File(directory.toFile(), "bin").apply { mkdirs() } }

    /** A `timeout` that drops its duration and runs the rest, after [before] (shell, may be empty). */
    private fun run(
        before: String = "",
        ownToken: String = "mine",
    ) {
        File(bin, "timeout").apply { writeText("#!/bin/sh\nshift\n$before\nexec \"\$@\"\n") }.setExecutable(true)
        val arguments = listOf("0", "udc0", udc.path, token.path, ownToken, config.path, "3", proc.path)
        val process =
            ProcessBuilder(listOf("sh", "-c", ShellDeadMan.SCRIPT, "bootleg-deadman") + arguments)
                .apply {
                    environment()["PATH"] = "${bin.path}:${System.getenv("PATH")}"
                    environment()["FAKE_CONFIG"] = config.path
                    environment()["FAKE_TOKEN"] = token.path
                }.start()
        assertTrue(process.waitFor(RUN_TIMEOUT_S, TimeUnit.SECONDS), "the script didn't finish")
    }

    private fun symlink(
        file: File,
        target: String,
    ) = Files.createSymbolicLink(file.toPath(), File(target).toPath())

    private fun links(): Set<String> = config.list().orEmpty().toSet()

    // The stand-in fails every bind while a link of ours is there.
    private val bindFailsWithOurs = "if [ \"\$1\" = sh ] && ls \"\$FAKE_CONFIG\" | grep -q bootleg; then exit 1; fi"

    @Test
    fun `binds an unbound UDC, and leaves a bound one or a later transition's alone`() {
        run()
        assertEquals("udc0", udc.readText().trim())
        udc.writeText("other\n")
        run()
        assertEquals("other", udc.readText().trim())
        udc.writeText("")
        run(ownToken = "an earlier transition's")
        assertEquals("", udc.readText())
        // A disarm landing right after the first token check stops the rest.
        run("if [ \"\$1\" = cat ]; then echo disarmed > \"\$FAKE_TOKEN\"; fi")
        assertEquals("", udc.readText())
    }

    @Test
    fun `a failed bind unlinks every function of ours, never another's, and binds again`() {
        listOf("ffs.bootleg_rndis", "hid.bootleg_kbd", "hid.bootleg_mouse_wheel", "function0", "mass_storage.bootleg")
            .forEach { symlink(File(config, it), "/nonexistent/$it") }
        run(bindFailsWithOurs)
        assertEquals("udc0", udc.readText().trim())
        assertEquals(setOf("function0"), links())
    }

    @Test
    fun `a failed bind changes nothing while anyone holds a hidg fd`() {
        listOf("ffs.bootleg_rndis", "hid.bootleg_kbd", "function0").forEach { symlink(File(config, it), "/nonexistent/$it") }
        symlink(File(proc, "42/fd/7"), "/dev/hidg1")
        run(bindFailsWithOurs)
        assertEquals("", udc.readText())
        assertEquals(setOf("ffs.bootleg_rndis", "hid.bootleg_kbd", "function0"), links())
    }

    private companion object {
        const val RUN_TIMEOUT_S = 10L
    }
}
