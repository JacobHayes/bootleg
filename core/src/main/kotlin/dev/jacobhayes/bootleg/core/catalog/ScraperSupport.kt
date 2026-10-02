package dev.jacobhayes.bootleg.core.catalog

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

/** Shared parsing and fetching helpers for scrapers. Everything is bounded and fails loudly on surprises. */
object ScraperSupport {
    const val SUMS_LINES_MAX = 20_000
    const val LINKS_MAX = 20_000
    const val FAN_OUT_MAX = 64
    const val SHA256_HEX_LENGTH = 64
    private const val VERSION_SEGMENTS_MAX = 16

    val json = Json { ignoreUnknownKeys = true }

    private val sha256Regex = Regex("^[0-9a-f]{64}$")
    private val gnuLine = Regex("""^([0-9a-fA-F]+) [ *](.+)$""")
    private val bsdLine = Regex("""^([A-Za-z0-9-]+) ?\((.+)\) ?= ?([0-9a-fA-F]+)$""")

    /** Multi-algorithm files (GParted, Clonezilla) group entries under `### SHA256SUMS:` style headers. */
    private val sectionHeader = Regex("""^###\s*([A-Za-z0-9]+SUMS)\s*:?$""", RegexOption.IGNORE_CASE)

    fun isSha256(value: String): Boolean = sha256Regex.matches(value)

    /**
     * Parses a SHA-256 sums file into file name -> lowercase hex hash. Accepts GNU (`hash  file`,
     * `hash *file`) and BSD (`SHA256 (file) = hash`) lines, `#` comments and a PGP clearsign wrapper.
     * BSD lines for other algorithms are skipped. In a sectioned file only the SHA256SUMS section is
     * read, because other sections can hold 64-hex hashes too (BLAKE3). Anything else unrecognized, a
     * file listed twice with different hashes, or a file with no SHA-256 entries throws.
     */
    fun parseSha256Sums(text: String): Map<String, String> {
        val lines = text.lines()
        require(lines.size <= SUMS_LINES_MAX) { "sums file too long: ${lines.size} lines" }
        val sectioned = lines.any { sectionHeader.matches(it.trim()) }
        var inSha256Section = !sectioned
        val sums = linkedMapOf<String, String>()
        var inSignature = false
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            val header = sectionHeader.matchEntire(line)
            when {
                line.startsWith("-----BEGIN PGP SIGNATURE") -> inSignature = true
                line.startsWith("-----END PGP SIGNATURE") -> inSignature = false
                inSignature -> Unit
                header != null -> inSha256Section = header.groupValues[1].equals("SHA256SUMS", ignoreCase = true)
                !inSha256Section || line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("-----BEGIN PGP SIGNED MESSAGE") || line.startsWith("Hash:") -> Unit
                else -> {
                    val (name, hash) = parseSumsLine(line) ?: return@forEachIndexed
                    require(name.isNotEmpty()) { "sums line ${index + 1} has no file name" }
                    val previous = sums.put(name, hash)
                    require(previous == null || previous == hash) { "conflicting hashes for $name" }
                }
            }
        }
        require(sums.isNotEmpty()) { "no SHA-256 entries in sums file" }
        return sums
    }

    /** Returns (file name, hash), or null for a BSD line of another algorithm. */
    private fun parseSumsLine(line: String): Pair<String, String>? {
        bsdLine.matchEntire(line)?.let { match ->
            val (algorithm, name, hash) = match.destructured
            if (algorithm.uppercase().replace("-", "") != "SHA256") return null
            return cleanName(name) to checkedHash(hash, line)
        }
        gnuLine.matchEntire(line)?.let { match ->
            val (hash, name) = match.destructured
            return cleanName(name) to checkedHash(hash, line)
        }
        throw IllegalArgumentException("unrecognized sums line: ${line.take(120)}")
    }

    private fun cleanName(name: String): String = name.trim().removePrefix("*").removePrefix("./")

    private fun checkedHash(
        hash: String,
        line: String,
    ): String {
        require(hash.length == SHA256_HEX_LENGTH) { "not a SHA-256 hash (${hash.length} hex chars): ${line.take(120)}" }
        return hash.lowercase()
    }

    /** All `<a href>` targets in an HTML page, resolved against [baseUrl]. */
    fun links(
        html: String,
        baseUrl: String,
    ): List<String> {
        val anchors = Jsoup.parse(html, baseUrl).select("a[href]")
        require(anchors.size <= LINKS_MAX) { "too many links on $baseUrl: ${anchors.size}" }
        return anchors.map { it.absUrl("href") }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * Names of the direct children in a directory listing (files as `name`, directories as `name/`).
     * Works for Apache/nginx autoindex and MirrorCache pages; query links and parents are ignored.
     */
    fun listingNames(
        html: String,
        directoryUrl: String,
    ): List<String> {
        require(directoryUrl.endsWith("/")) { "directory URL must end with /: $directoryUrl" }
        return links(html, directoryUrl)
            .filter { it.startsWith(directoryUrl) && !it.contains('?') && !it.contains('#') }
            .map { it.removePrefix(directoryUrl) }
            .filter { name -> name.isNotEmpty() && !name.removeSuffix("/").contains('/') }
            .distinct()
    }

    /** Compares dotted versions numerically per segment ("24.04.5.1" > "24.04.5" > "24.04"). */
    val versionComparator: Comparator<String> = Comparator { left, right -> compareVersions(left, right) }

    fun compareVersions(
        left: String,
        right: String,
    ): Int {
        val leftSegments = versionSegments(left)
        val rightSegments = versionSegments(right)
        for (index in 0 until maxOf(leftSegments.size, rightSegments.size)) {
            // When one side runs out, a further numeric segment is newer ("1.0.1" > "1.0") and a
            // textual one marks a pre-release ("1.0-rc1" < "1.0").
            val leftSegment = leftSegments.getOrNull(index) ?: return if (rightSegments[index].isNumeric()) -1 else 1
            val rightSegment = rightSegments.getOrNull(index) ?: return if (leftSegments[index].isNumeric()) 1 else -1
            val leftNumber = leftSegment.toBigIntegerOrNull()
            val rightNumber = rightSegment.toBigIntegerOrNull()
            val result =
                when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    // A numeric segment sorts after a textual one ("1.0" > "1.0-rc").
                    leftNumber != null -> 1
                    rightNumber != null -> -1
                    else -> leftSegment.compareTo(rightSegment)
                }
            if (result != 0) return result
        }
        return 0
    }

    private fun String.isNumeric(): Boolean = isNotEmpty() && all(Char::isDigit)

    private fun versionSegments(version: String): List<String> {
        val segments = version.split('.', '-', '_', ' ', '+').filter { it.isNotEmpty() }
        require(segments.size <= VERSION_SEGMENTS_MAX) { "version has too many segments: $version" }
        return segments
    }

    /** The decoded last path segment of a URL. */
    fun fileNameOf(url: String): String {
        val path = URI(url).path ?: ""
        val name = path.substringAfterLast('/')
        require(name.isNotEmpty()) { "URL has no file name: $url" }
        return name
    }

    /** Runs [transform] for every item concurrently. The [Http] wrapper used by [Catalog] bounds actual requests. */
    suspend fun <T, R> fanOut(
        items: List<T>,
        transform: suspend (T) -> R,
    ): List<R> {
        require(items.size <= FAN_OUT_MAX) { "fan-out too wide: ${items.size} > $FAN_OUT_MAX" }
        return coroutineScope { items.map { async { transform(it) } }.awaitAll() }
    }
}

/** GET that requires HTTP 200. */
suspend fun Http.getText(url: String): String {
    val response = get(url)
    if (response.status != 200) throw IOException("GET $url returned HTTP ${response.status}")
    return response.body
}

/** GET that maps 404/410 (or an empty body, which some APIs send instead) to null. */
suspend fun Http.getTextOrNull(url: String): String? {
    val response = get(url)
    if (response.status == 404 || response.status == 410) return null
    if (response.status != 200) throw IOException("GET $url returned HTTP ${response.status}")
    return response.body.ifBlank { null }
}
