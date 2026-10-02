package dev.jacobhayes.bootleg.core.image

import java.io.InputStream
import java.security.MessageDigest

data class Sha256Result(
    /** Lowercase hex, 64 characters. */
    val hex: String,
    val sizeBytes: Long,
)

/** Streaming SHA-256 for multi-GiB images: constant memory, progress per buffer. */
object Sha256Streamer {
    const val BUFFER_SIZE_BYTES_DEFAULT = 1024 * 1024
    const val BUFFER_SIZE_BYTES_MIN = 4 * 1024
    const val BUFFER_SIZE_BYTES_MAX = 8 * 1024 * 1024

    /** Larger than any image we would expose (MBR caps disks at 2 TiB; this leaves headroom for raw images). */
    const val SIZE_BYTES_MAX = 16L * 1024 * 1024 * 1024 * 1024

    /**
     * Hashes [input] to EOF. [onProgress] receives the running byte count after each buffer; throw from
     * it (e.g. CancellationException) to cancel. The caller owns and closes [input].
     */
    fun digest(
        input: InputStream,
        bufferSizeBytes: Int = BUFFER_SIZE_BYTES_DEFAULT,
        sizeBytesMax: Long = SIZE_BYTES_MAX,
        onProgress: (bytesRead: Long) -> Unit = {},
    ): Sha256Result {
        require(bufferSizeBytes in BUFFER_SIZE_BYTES_MIN..BUFFER_SIZE_BYTES_MAX) { "buffer size out of range: $bufferSizeBytes" }
        require(sizeBytesMax in 0..SIZE_BYTES_MAX) { "size limit out of range: $sizeBytesMax" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSizeBytes)
        var bytesRead = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(count <= buffer.size)
            bytesRead += count
            if (bytesRead > sizeBytesMax) throw IllegalStateException("input exceeds $sizeBytesMax bytes")
            digest.update(buffer, 0, count)
            if (count > 0) onProgress(bytesRead)
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        check(hex.length == 64)
        return Sha256Result(hex, bytesRead)
    }
}
