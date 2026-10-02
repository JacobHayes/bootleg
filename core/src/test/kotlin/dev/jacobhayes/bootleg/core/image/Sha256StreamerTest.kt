package dev.jacobhayes.bootleg.core.image

import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class Sha256StreamerTest {
    /** Returns at most [chunkBytes] per read, like a slow pipe or network stream. */
    private class TrickleInputStream(
        private val data: ByteArray,
        private val chunkBytes: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= data.size) return -1
            val count = minOf(length, chunkBytes, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    @Test
    fun `known vectors`() {
        assertEquals(
            Sha256Result("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 0),
            Sha256Streamer.digest(ByteArrayInputStream(ByteArray(0))),
        )
        assertEquals(
            Sha256Result("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3),
            Sha256Streamer.digest(ByteArrayInputStream("abc".encodeToByteArray())),
        )
    }

    @Test
    fun `one million a across many small buffers with monotonic progress`() {
        val progress = mutableListOf<Long>()
        val result =
            Sha256Streamer.digest(
                TrickleInputStream(ByteArray(1_000_000) { 'a'.code.toByte() }, chunkBytes = 3000),
                bufferSizeBytes = Sha256Streamer.BUFFER_SIZE_BYTES_MIN,
            ) { progress += it }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", result.hex)
        assertEquals(1_000_000L, result.sizeBytes)
        assertEquals(1_000_000L, progress.last())
        assertTrue(progress.zipWithNext().all { (first, second) -> second > first })
        assertTrue(progress.zipWithNext().all { (first, second) -> second - first <= Sha256Streamer.BUFFER_SIZE_BYTES_MIN })
    }

    @Test
    fun `size limit stops runaway input`() {
        assertThrows<IllegalStateException> {
            Sha256Streamer.digest(ByteArrayInputStream(ByteArray(10_000)), bufferSizeBytes = 4096, sizeBytesMax = 9_999)
        }
        assertEquals(10_000L, Sha256Streamer.digest(ByteArrayInputStream(ByteArray(10_000)), sizeBytesMax = 10_000).sizeBytes)
    }

    @Test
    fun `buffer size is bounded`() {
        val empty = ByteArrayInputStream(ByteArray(0))
        assertThrows<IllegalArgumentException> { Sha256Streamer.digest(empty, bufferSizeBytes = Sha256Streamer.BUFFER_SIZE_BYTES_MIN - 1) }
        assertThrows<IllegalArgumentException> { Sha256Streamer.digest(empty, bufferSizeBytes = Sha256Streamer.BUFFER_SIZE_BYTES_MAX + 1) }
        assertThrows<IllegalArgumentException> { Sha256Streamer.digest(empty, sizeBytesMax = -1) }
    }

    @Test
    fun `progress callback can cancel by throwing`() {
        class Cancelled : RuntimeException()
        assertThrows<Cancelled> {
            Sha256Streamer.digest(ByteArrayInputStream(ByteArray(100_000)), bufferSizeBytes = 4096) { if (it > 8192) throw Cancelled() }
        }
    }

    @Test
    fun `streaming matches one-shot digest for any chunking`() =
        runBlocking<Unit> {
            checkAll(100, Arb.byteArray(Arb.int(0..20_000), Arb.byte()), Arb.int(1..5000)) { data, chunkBytes ->
                val expected = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                val result =
                    Sha256Streamer.digest(
                        TrickleInputStream(data, chunkBytes),
                        bufferSizeBytes = Sha256Streamer.BUFFER_SIZE_BYTES_MIN,
                    )
                assertEquals(Sha256Result(expected, data.size.toLong()), result)
            }
        }
}
