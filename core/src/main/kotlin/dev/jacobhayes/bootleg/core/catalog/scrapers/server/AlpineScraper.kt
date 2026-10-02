package dev.jacobhayes.bootleg.core.catalog.scrapers.server

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.getText

/** Alpine latest-stable ISOs from each arch's `latest-releases.yaml`, which carries the sha256 inline. */
class AlpineScraper(
    private val mirror: String = "https://dl-cdn.alpinelinux.org/alpine",
) : Scraper {
    override val distros = listOf(Distro("alpine", "Alpine Linux", Category.SERVER, "https://alpinelinux.org"))

    override suspend fun releases(http: Http): List<Release> =
        checkedReleases(
            archNames.flatMap { (arch, archName) ->
                val yaml = http.getText("$mirror/latest-stable/releases/$archName/latest-releases.yaml")
                parseAlpineLatestReleases(yaml).mapNotNull { entry -> toRelease(entry, arch, archName) }
            },
        )

    private fun toRelease(
        entry: Map<String, String>,
        arch: Arch,
        archName: String,
    ): Release? {
        val variant = flavorVariants[entry["flavor"]] ?: return null
        val file = entry.required("file")
        val branch = entry.required("branch")
        val version = entry.required("version")
        check(entry["arch"] == archName) { "alpine: $file listed under $archName but has arch ${entry["arch"]}" }
        check(file.endsWith(".iso") && '/' !in file) { "alpine: unexpected image file name '$file'" }
        check(Regex("""^v\d+\.\d+$""").matches(branch)) { "alpine: unexpected branch '$branch'" }
        val sha256 = entry.required("sha256")
        check(isSha256(sha256)) { "alpine: bad sha256 for $file" }
        val url = "$mirror/$branch/releases/$archName/$file"
        return Release(
            distroId = "alpine",
            version = version,
            arch = arch,
            variant = variant,
            url = url,
            sha256 = sha256.lowercase(),
            sumsUrl = "$url.sha256",
            sizeBytes = entry["size"]?.toLongOrNull(),
            // Alpine signs the ISO (.asc), not the sums.
            sumsSigned = false,
            fileName = file,
        )
    }

    private fun Map<String, String>.required(key: String): String =
        checkNotNull(
            this[key]?.takeIf {
                it.isNotBlank()
            },
        ) { "alpine: entry missing '$key': $this" }

    private companion object {
        val archNames = listOf(Arch.X86_64 to "x86_64", Arch.ARM64 to "aarch64")
        val flavorVariants = mapOf("alpine-standard" to "Standard", "alpine-extended" to "Extended", "alpine-virt" to "Virtual")
    }
}

/**
 * Parses the narrow YAML shape of `latest-releases.yaml`: a top-level list of flat maps with
 * two-space-indented `key: value` pairs. Block scalars (`desc: |`) are skipped. Not a general YAML parser.
 */
internal fun parseAlpineLatestReleases(yaml: String): List<Map<String, String>> {
    val entries = mutableListOf<MutableMap<String, String>>()
    val keyValue = Regex("""^ {2}([A-Za-z0-9_]+):(?: (.*))?$""")
    val lines = yaml.lineSequence().take(ServerScraperLimits.SUMS_LINES_MAX + 1).toList()
    check(lines.size <= ServerScraperLimits.SUMS_LINES_MAX) { "alpine: yaml has more than ${ServerScraperLimits.SUMS_LINES_MAX} lines" }
    for (line in lines) {
        when {
            line == "---" || line.isBlank() -> Unit
            line == "-" || line.startsWith("- ") -> {
                entries += mutableMapOf()
                val inline = line.removePrefix("-").trim()
                if (inline.isNotEmpty()) error("alpine: inline list items are not supported: '$line'")
            }
            line.startsWith("    ") -> Unit // block scalar continuation
            else -> {
                val match = keyValue.matchEntire(line) ?: error("alpine: unexpected yaml line '$line'")
                val entry = entries.lastOrNull() ?: error("alpine: key before first list item: '$line'")
                entry[match.groupValues[1]] = match.groupValues[2].trim().removeSurrounding("\"")
            }
        }
    }
    check(entries.isNotEmpty()) { "alpine: no entries in latest-releases.yaml" }
    return entries
}
