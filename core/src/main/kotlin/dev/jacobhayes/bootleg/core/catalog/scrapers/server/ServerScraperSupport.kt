package dev.jacobhayes.bootleg.core.catalog.scrapers.server

import dev.jacobhayes.bootleg.core.catalog.CatalogLimits
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import java.net.URI

/** Limits shared by the server and rescue scrapers. */
internal object ServerScraperLimits {
    const val RELEASES_PER_SCRAPER_MAX = 64
    const val SUMS_LINES_MAX = 20_000
    const val INDEX_LINKS_MAX = 5_000
    const val MAJOR_VERSIONS_MAX = 6
}

private val sha256Hex = Regex("^[0-9a-fA-F]{64}$")
private val gnuSumsLine = Regex("""^([0-9a-fA-F]{64}) [ *](\S.*)$""")
private val bsdSumsLine = Regex("""^SHA256 \((.+)\) = ([0-9a-fA-F]{64})$""")

/** Multi-algorithm checksum files (GParted, Clonezilla) group entries under `### SHA256SUMS:` style headers. */
private val sumsSectionHeader = Regex("""^###\s*([A-Za-z0-9]+)\s*:?\s*$""")

internal fun isSha256(text: String): Boolean = sha256Hex.matches(text)

/** GET that fails loudly on a non-2xx status or an oversized body. */
internal suspend fun Http.getText(url: String): String {
    val response = get(url)
    check(response.status in 200..299) { "GET $url: HTTP ${response.status}" }
    check(response.body.length <= CatalogLimits.RESPONSE_BYTES_MAX) {
        "GET $url: response larger than ${CatalogLimits.RESPONSE_BYTES_MAX} bytes"
    }
    return response.body
}

/** Like [getText] but maps 404 to null, for optional per-arch/per-version resources. */
internal suspend fun Http.getTextOrNull(url: String): String? {
    val response = get(url)
    if (response.status == 404) return null
    check(response.status in 200..299) { "GET $url: HTTP ${response.status}" }
    check(response.body.length <= CatalogLimits.RESPONSE_BYTES_MAX) {
        "GET $url: response larger than ${CatalogLimits.RESPONSE_BYTES_MAX} bytes"
    }
    return response.body
}

/**
 * Parses sha256 sums into file name -> lowercase hex.
 *
 * Accepts GNU (`hash  file`, `hash *file`) and BSD (`SHA256 (file) = hash`) lines. Comments, blank
 * lines, PGP armor and other algorithms' lines are skipped. In sectioned files only the SHA256SUMS
 * section is read, because BLAKE3 sums are also 64 hex chars. Throws if nothing parses or a file is
 * listed twice with different hashes.
 */
internal fun parseSha256Sums(text: String): Map<String, String> {
    val lines = text.lineSequence().take(ServerScraperLimits.SUMS_LINES_MAX + 1).toList()
    check(lines.size <= ServerScraperLimits.SUMS_LINES_MAX) { "sums file has more than ${ServerScraperLimits.SUMS_LINES_MAX} lines" }
    val sectioned = lines.any { sumsSectionHeader.matches(it.trim()) }
    var inSha256Section = !sectioned
    val sums = LinkedHashMap<String, String>()
    for (rawLine in lines) {
        val line = rawLine.trim()
        val header = sumsSectionHeader.matchEntire(line)
        if (header != null) {
            inSha256Section = header.groupValues[1].equals("SHA256SUMS", ignoreCase = true)
            continue
        }
        if (!inSha256Section || line.isEmpty() || line.startsWith("#")) continue
        val gnu = gnuSumsLine.matchEntire(line)
        val bsd = bsdSumsLine.matchEntire(line)
        val (hash, file) =
            when {
                gnu != null -> gnu.groupValues[1] to gnu.groupValues[2]
                bsd != null -> bsd.groupValues[2] to bsd.groupValues[1]
                else -> continue
            }
        val fileName = file.trim()
        val previous = sums.put(fileName, hash.lowercase())
        check(previous == null || previous == hash.lowercase()) { "sums list $fileName twice with different hashes" }
    }
    check(sums.isNotEmpty()) { "no sha256 entries found in sums file" }
    return sums
}

/** A `.sha256` file holding one hash, optionally followed by a file name. */
internal fun parseSingleSha256(text: String): String {
    val token =
        text
            .trim()
            .split(Regex("\\s+"), limit = 2)
            .firstOrNull()
            .orEmpty()
    check(isSha256(token)) { "expected a sha256 hash, got '${text.take(80)}'" }
    return token.lowercase()
}

/** Compares dotted/dashed numeric versions ("9.2-1" < "9.10-1"); non-digit runs are separators. */
internal val numericVersionOrder: Comparator<String> =
    Comparator { left, right ->
        val leftParts = left.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toBigInteger() }
        val rightParts = right.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toBigInteger() }
        for (index in 0 until minOf(leftParts.size, rightParts.size)) {
            val comparison = leftParts[index].compareTo(rightParts[index])
            if (comparison != 0) return@Comparator comparison
        }
        leftParts.size.compareTo(rightParts.size)
    }

/** Extracts `href` attribute values from an HTML directory listing or page, bounded. */
internal fun hrefs(html: String): List<String> {
    val links =
        Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .take(ServerScraperLimits.INDEX_LINKS_MAX + 1)
            .toList()
    check(links.size <= ServerScraperLimits.INDEX_LINKS_MAX) { "page has more than ${ServerScraperLimits.INDEX_LINKS_MAX} links" }
    return links
}

internal fun resolveUrl(
    base: String,
    href: String,
): String = URI(base).resolve(href.replace("&amp;", "&")).toString()

/** Checks every invariant the catalog relies on, so a scraper bug surfaces here rather than in a download. */
internal fun Scraper.checkedReleases(releases: List<Release>): List<Release> {
    check(releases.size <= ServerScraperLimits.RELEASES_PER_SCRAPER_MAX) {
        "${releases.size} releases exceeds ${ServerScraperLimits.RELEASES_PER_SCRAPER_MAX}"
    }
    val distroIds = distros.map { it.id }.toSet()
    for (release in releases) {
        check(release.distroId in distroIds) { "release for unknown distro ${release.distroId}" }
        check(release.version.isNotBlank()) { "blank version: $release" }
        check(release.url.startsWith("https://")) { "image URL must be https: ${release.url}" }
        check(release.fileName.isNotBlank() && '/' !in release.fileName && !release.fileName.startsWith(".")) {
            "bad file name: '${release.fileName}'"
        }
        check(release.sha256 == null || isSha256(release.sha256)) { "bad sha256 for ${release.url}" }
        check(release.sha256 == null || release.sha256 == release.sha256.lowercase()) { "sha256 must be lowercase: ${release.url}" }
        check(release.sha256 != null || release.sumsUrl != null) { "no sha256 and no sums URL for ${release.url}" }
        check(release.sumsUrl == null || release.sumsUrl.startsWith("https://")) { "sums URL must be https: ${release.sumsUrl}" }
        check(release.sizeBytes == null || release.sizeBytes > 0) { "non-positive size for ${release.url}" }
    }
    val keys = releases.map { Triple(it.distroId, it.arch, it.url) }
    check(keys.toSet().size == keys.size) { "duplicate releases" }
    return releases
}
