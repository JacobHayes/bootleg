package dev.jacobhayes.bootleg.download

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

object ArchiveLimits {
    const val ENTRY_SIZE_BYTES_MAX = 16L * 1024 * 1024 * 1024
    const val ENTRY_COUNT_MAX = 10_000

    /** Images compress modestly; a much higher ratio means a zip bomb. */
    const val COMPRESSION_RATIO_MAX = 100L

    /** Small, mostly empty images may compress better than the ratio allows; this much is always fine. */
    const val RATIO_EXEMPT_BYTES = 64L * 1024 * 1024
    const val BUFFER_BYTES = 1 shl 16
}

/**
 * Extracts one named entry from a downloaded `.zip` (zipped releases, e.g. Memtest86+). The output
 * path is the caller's, never derived from the archive, and only an exact, unique, safe entry name
 * is accepted. Size is bounded by [ArchiveLimits] on the bytes actually inflated, not the header.
 */
object ArchiveExtractor {
    data class Extracted(
        val sizeBytes: Long,
        /** Lowercase hex sha256 of the extracted bytes. */
        val sha256: String,
    )

    fun isSafeEntryName(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= ENTRY_NAME_LENGTH_MAX &&
            !name.startsWith("/") &&
            !name.endsWith("/") &&
            name.none { it == '\\' || it == ':' || it.isISOControl() } &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** The most bytes an entry may inflate to, given its compressed size. */
    fun entrySizeLimitBytes(compressedBytes: Long): Long {
        require(compressedBytes >= 0) { "negative compressed size: $compressedBytes" }
        val ratioLimit =
            if (compressedBytes > ArchiveLimits.ENTRY_SIZE_BYTES_MAX / ArchiveLimits.COMPRESSION_RATIO_MAX) {
                ArchiveLimits.ENTRY_SIZE_BYTES_MAX
            } else {
                compressedBytes * ArchiveLimits.COMPRESSION_RATIO_MAX
            }
        return minOf(ArchiveLimits.ENTRY_SIZE_BYTES_MAX, maxOf(ArchiveLimits.RATIO_EXEMPT_BYTES, ratioLimit))
    }

    /**
     * Streams [entryName] from [archive] into [destination] (which must not exist), fsyncs it and returns
     * its size and hash. [onProgress] gets the running byte count; throw from it to cancel. On failure the
     * caller deletes [destination].
     */
    fun extract(
        archive: File,
        entryName: String,
        destination: File,
        onProgress: (bytesWritten: Long) -> Unit = {},
    ): Extracted {
        require(isSafeEntryName(entryName)) { "unsafe archive entry name: $entryName" }
        require(!destination.exists()) { "destination exists: $destination" }
        ZipFile(archive).use { zip ->
            val entries =
                zip
                    .entries()
                    .asSequence()
                    .take(ArchiveLimits.ENTRY_COUNT_MAX + 1)
                    .toList()
            check(entries.size <= ArchiveLimits.ENTRY_COUNT_MAX) { "archive has more than ${ArchiveLimits.ENTRY_COUNT_MAX} entries" }
            val matches = entries.filter { it.name == entryName }
            check(matches.size == 1) { "archive must contain $entryName exactly once (found ${matches.size})" }
            val entry = matches.single()
            check(!entry.isDirectory) { "$entryName is a directory" }
            val compressedBytes = if (entry.compressedSize >= 0) entry.compressedSize else archive.length()
            val limitBytes = entrySizeLimitBytes(compressedBytes)
            check(entry.size <= limitBytes) { "$entryName declares ${entry.size} bytes, over the $limitBytes byte limit" }

            val digest = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            var written = 0L
            zip.getInputStream(entry).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(ArchiveLimits.BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        check(written <= limitBytes) { "$entryName inflates past the $limitBytes byte limit" }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        crc.update(buffer, 0, read)
                        onProgress(written)
                    }
                    output.fd.sync()
                }
            }
            check(entry.size < 0 || written == entry.size) { "$entryName: extracted $written bytes, header says ${entry.size}" }
            check(entry.crc < 0 || crc.value == entry.crc) { "$entryName: CRC mismatch" }
            val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            return Extracted(written, hex)
        }
    }

    private const val ENTRY_NAME_LENGTH_MAX = 255
}
