package dev.jacobhayes.bootleg.root

import dev.jacobhayes.bootleg.core.gadget.UdcStuckException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/** A UDC write that hangs, modelled by a FIFO nobody reads: its open blocks like a write stuck in the kernel. */
class RootConfigFsUdcTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a UDC write past its deadline fails every access at once until it returns`() {
        val udc = File(directory.toFile(), "UDC")
        assertEquals(0, ProcessBuilder("mkfifo", udc.path).start().waitFor())
        var stuckCalls = 0
        val fs = RootConfigFs(directory.toString(), onUdcStuck = { stuckCalls++ }, udcWriteWaitMs = 50, udcWriteDeadlineMs = 200)
        assertThrows<UdcStuckException> { fs.write("UDC", "udc0") }
        assertEquals(1, stuckCalls, "the RNDIS daemon is killed once")
        assertTrue(fs.udcWriteStuck)
        // Reading the FIFO would block; the guard fails it before touching it.
        assertThrows<UdcStuckException> { fs.read("UDC") }
        assertThrows<UdcStuckException> { fs.exists("UDC") }
        // The kernel lets the write finish.
        assertEquals("udc0", udc.readText())
        repeat(POLL_COUNT_MAX) { if (fs.udcWriteStuck) Thread.sleep(POLL_INTERVAL_MS) }
        assertFalse(fs.udcWriteStuck)
        assertTrue(fs.exists("UDC"))
    }

    private companion object {
        const val POLL_COUNT_MAX = 100
        const val POLL_INTERVAL_MS = 10L
    }
}
