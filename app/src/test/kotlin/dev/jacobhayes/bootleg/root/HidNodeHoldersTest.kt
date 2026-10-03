package dev.jacobhayes.bootleg.root

import dev.jacobhayes.bootleg.core.hid.DeviceNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HidNodeHoldersTest {
    private class FakeProc(
        val functions: Map<String, String>,
        /** pid -> fd -> (link target, char device or null) */
        val processes: Map<Int, Map<Int, Pair<String, DeviceNumber?>>>,
        val unreadable: Set<Int> = emptySet(),
        val statFails: Boolean = false,
    ) : HidNodeHolders.Proc {
        val statted = mutableListOf<String>()

        override fun functionDev(function: String) = functions[function]?.also { if (it == UNREADABLE) throw java.io.IOException("EIO") }

        override fun pids() = processes.keys.toList()

        override fun fds(pid: Int) = if (pid in unreadable) null else processes[pid].orEmpty().keys.toList()

        override fun fdTarget(
            pid: Int,
            fd: Int,
        ) = processes[pid]?.get(fd)?.first

        override fun fdCharDevice(
            pid: Int,
            fd: Int,
        ): DeviceNumber? {
            statted += "$pid/$fd"
            if (statFails) throw java.io.IOException("EACCES")
            return processes[pid]?.get(fd)?.second
        }

        override fun processName(pid: Int) = "proc$pid"
    }

    private val keyboardDev = DeviceNumber(480, 0)
    private val foreignDev = DeviceNumber(480, 2)

    @Test
    fun `finds holders by device number, deleted nodes included, and stats only device links`() {
        val proc =
            FakeProc(
                mapOf("hid.bootleg_kbd" to "480:0\n"),
                mapOf(
                    10 to mapOf(3 to ("/dev/hidg0 (deleted)" to keyboardDev), 4 to ("/storage/emulated/0/x" to null)),
                    11 to mapOf(5 to ("/dev/hidg2" to foreignDev), 6 to ("socket:[1]" to null)),
                ),
            )
        val holders = HidNodeHolders(proc) { 0L }.scan(listOf("hid.bootleg_kbd", "hid.bootleg_mouse"))
        assertEquals(listOf("pid 10 (proc10) fd 3 -> /dev/hidg0 (deleted) (hid.bootleg_kbd)"), holders)
        assertEquals(listOf("10/3", "11/5"), proc.statted, "never stat a non-/dev link (FUSE could hang)")
    }

    @Test
    fun `a scan over its time budget fails closed`() {
        var nowMs = 0L
        val proc = FakeProc(mapOf("hid.bootleg_kbd" to "480:0"), (1..5).associateWith { emptyMap() })
        val holders =
            HidNodeHolders(proc) {
                nowMs.also { nowMs += HidNodeHolders.SCAN_MS_MAX }
            }.scan(listOf("hid.bootleg_kbd"))
        assertTrue(holders.single().startsWith("holder scan incomplete"), "$holders")
    }

    @Test
    fun `a live process whose fds can't be listed fails the scan closed`() {
        val proc = FakeProc(mapOf("hid.bootleg_kbd" to "480:0"), mapOf(1 to emptyMap(), 2 to emptyMap()), unreadable = setOf(2))
        val holders = HidNodeHolders(proc) { 0L }.scan(listOf("hid.bootleg_kbd"))
        assertTrue(holders.single().startsWith("holder scan incomplete"), "$holders")
    }

    @Test
    fun `an unreadable dev attribute or fd fails the scan closed`() {
        val unreadableDev = FakeProc(mapOf("hid.bootleg_kbd" to UNREADABLE), mapOf(1 to emptyMap()))
        assertTrue(HidNodeHolders(unreadableDev) { 0L }.scan(listOf("hid.bootleg_kbd")).single().startsWith("holder scan incomplete"))
        val malformedDev = FakeProc(mapOf("hid.bootleg_kbd" to "garbage"), mapOf(1 to emptyMap()))
        assertTrue(HidNodeHolders(malformedDev) { 0L }.scan(listOf("hid.bootleg_kbd")).single().startsWith("holder scan incomplete"))
        val statFails =
            FakeProc(mapOf("hid.bootleg_kbd" to "480:0"), mapOf(1 to mapOf(3 to ("/dev/hidg0" to keyboardDev))), statFails = true)
        assertTrue(HidNodeHolders(statFails) { 0L }.scan(listOf("hid.bootleg_kbd")).single().startsWith("holder scan incomplete"))
    }

    @Test
    fun `missing functions need no scan`() {
        val proc = FakeProc(emptyMap(), mapOf(1 to mapOf(3 to ("/dev/hidg0" to keyboardDev))))
        assertEquals(emptyList<String>(), HidNodeHolders(proc) { 0L }.scan(listOf("hid.bootleg_kbd")))
        assertEquals(emptyList<String>(), proc.statted)
    }

    private companion object {
        const val UNREADABLE = "<unreadable>"
    }
}
