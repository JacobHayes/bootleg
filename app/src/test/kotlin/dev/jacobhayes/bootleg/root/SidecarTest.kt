package dev.jacobhayes.bootleg.root

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SidecarTest {
    private val digest = "A".repeat(64)

    /** A file read in [chunkBytes] pieces; [growBy] more bytes appear after every read (a writer appending). */
    private class GrowingFile(
        initial: ByteArray,
        private val chunkBytes: Int,
        private val growBy: Int = 0,
    ) {
        var content = initial
        var bytesRead = 0L
        private var position = 0

        fun read(
            buffer: ByteArray,
            offset: Int,
            count: Int,
        ): Int {
            val available = content.size - position
            val taken = minOf(count, chunkBytes, available)
            content.copyInto(buffer, offset, position, position + taken)
            position += taken
            bytesRead += taken
            content += ByteArray(growBy) { 'x'.code.toByte() }
            return taken
        }
    }

    @Test
    fun `reads a small sidecar in pieces and finds its digest`() {
        val file = GrowingFile("$digest  disk.img\n".toByteArray(), chunkBytes = 7)
        val bytes = Sidecar.readBounded(file::read)
        assertEquals(digest.lowercase(), bytes?.let(Sidecar::parse))
    }

    @Test
    fun `a sidecar over the limit is refused without reading past limit plus one`() {
        val file = GrowingFile(ByteArray(Sidecar.SIZE_BYTES_MAX * 4) { 'a'.code.toByte() }, chunkBytes = 1_000)
        assertNull(Sidecar.readBounded(file::read))
        assertEquals(Sidecar.SIZE_BYTES_MAX + 1L, file.bytesRead)
    }

    @Test
    fun `a sidecar that grows while being read is bounded`() {
        // Starts small (it would have passed a length check) and grows faster than it's read.
        val file = GrowingFile("$digest\n".toByteArray(), chunkBytes = 16, growBy = 64)
        assertNull(Sidecar.readBounded(file::read))
        assertTrue(file.bytesRead <= Sidecar.SIZE_BYTES_MAX + 1L, "read ${file.bytesRead} bytes")
    }

    @Test
    fun `exactly the limit is fine and no digest parses as null`() {
        val file = GrowingFile(ByteArray(Sidecar.SIZE_BYTES_MAX) { ' '.code.toByte() }, chunkBytes = 4_096)
        val bytes = Sidecar.readBounded(file::read)
        assertEquals(Sidecar.SIZE_BYTES_MAX, bytes?.size)
        assertNull(Sidecar.parse(bytes!!))
    }
}
