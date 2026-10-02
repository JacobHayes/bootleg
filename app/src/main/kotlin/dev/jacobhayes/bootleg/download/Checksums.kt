package dev.jacobhayes.bootleg.download

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Parses SHA256SUMS-style files. Local stand-in for core's checksum utils (swap at merge).
 * Accepts GNU (`<hash>  name`, `<hash> *name`), BSD (`SHA256 (name) = <hash>`) and bare-hash files.
 * Lines inside PGP armour or comments simply don't match and are skipped.
 */
object Checksums {
    const val SUMS_LINE_COUNT_MAX = 100_000
    private const val HASH_BUFFER_BYTES = 1 shl 16

    private val gnuLine = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$""")
    private val bsdLine = Regex("""^SHA256\s*\((.+)\)\s*=\s*([0-9a-fA-F]{64})\s*$""")
    private val bareLine = Regex("""^([0-9a-fA-F]{64})\s*$""")
    private val hexSha256 = Regex("^[0-9a-f]{64}$")

    data class Entry(
        val sha256: String,
        /** File name as written, or null for a bare hash. */
        val name: String?,
    )

    fun parse(text: String): List<Entry> =
        text
            .lineSequence()
            .take(SUMS_LINE_COUNT_MAX)
            .mapNotNull { line -> parseLine(line.trim()) }
            .toList()

    private fun parseLine(line: String): Entry? {
        bsdLine.matchEntire(line)?.let { return Entry(it.groupValues[2].lowercase(), it.groupValues[1]) }
        gnuLine.matchEntire(line)?.let { return Entry(it.groupValues[1].lowercase(), it.groupValues[2]) }
        bareLine.matchEntire(line)?.let { return Entry(it.groupValues[1].lowercase(), null) }
        return null
    }

    /**
     * Finds the hash for one of [candidateNames] (matched by base name, so `./x.iso` matches `x.iso`).
     * A file holding only a bare hash (a per-image `.sha256`) applies to the image.
     */
    fun find(
        text: String,
        candidateNames: List<String>,
    ): String? {
        require(candidateNames.isNotEmpty()) { "need at least one candidate name" }
        val entries = parse(text)
        val wanted = candidateNames.map { baseName(it) }.toSet()
        val matches = entries.filter { it.name != null && baseName(it.name) in wanted }.map { it.sha256 }.distinct()
        check(matches.size <= 1) { "conflicting hashes for $candidateNames" }
        if (matches.size == 1) return matches.single()
        return entries.singleOrNull()?.takeIf { it.name == null }?.sha256
    }

    fun isSha256(value: String): Boolean = hexSha256.matches(value)

    fun sha256(
        input: InputStream,
        onProgress: (bytesRead: Long) -> Unit = {},
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
            onProgress(total)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(
        file: File,
        onProgress: (bytesRead: Long) -> Unit = {},
    ): String = file.inputStream().use { sha256(it, onProgress) }

    /** GNU format, so `sha256sum -c` works on the sidecar. */
    fun sidecarLine(
        sha256: String,
        fileName: String,
    ): String {
        require(isSha256(sha256)) { "not a lowercase sha256: $sha256" }
        require('/' !in fileName && '\n' !in fileName) { "bad file name: $fileName" }
        return "$sha256  $fileName\n"
    }

    private fun baseName(name: String): String = name.substringAfterLast('/')
}
