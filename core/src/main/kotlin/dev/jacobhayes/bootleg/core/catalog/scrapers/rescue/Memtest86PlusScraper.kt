package dev.jacobhayes.bootleg.core.catalog.scrapers.rescue

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.catalog.getText
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.checkedReleases
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.hrefs
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.numericVersionOrder
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.resolveUrl

/**
 * Memtest86+ x86-64 ISO from memtest.org. Upstream ships the ISO only inside a zip
 * (`mt86plus_<version>_x86_64.iso.zip`, holding `memtest.iso`) and publishes sha256 for the zip,
 * not the ISO, so the release names the archive entry and carries the archive's hash. GitHub
 * releases have no binary assets, and there is no arm64 build (only x86 and LoongArch).
 * The GRUB variant is skipped: the plain ISO already boots both BIOS and UEFI.
 */
class Memtest86PlusScraper(
    private val siteUrl: String = "https://www.memtest.org/",
) : Scraper {
    override val distros = listOf(Distro("memtest86plus", "Memtest86+", Category.RESCUE, "https://www.memtest.org"))

    override suspend fun releases(http: Http): List<Release> {
        val archive = newestMemtestArchive(http.getText(siteUrl), siteUrl) ?: return emptyList()
        val sumsUrl = resolveUrl(archive.url, "sha256sum.txt")
        val sums = ScraperSupport.parseSha256Sums(http.getText(sumsUrl))
        // Entries are listed with their version directory, e.g. "v8.10/mt86plus_8.10_x86_64.iso.zip".
        val archiveSha256 =
            checkNotNull(sums.entries.singleOrNull { (name, _) -> name.substringAfterLast('/') == archive.fileName }?.value) {
                "memtest86+: ${archive.fileName} is not listed exactly once in $sumsUrl"
            }
        return checkedReleases(
            listOf(
                Release(
                    distroId = "memtest86plus",
                    version = archive.version,
                    arch = Arch.X86_64,
                    url = archive.url,
                    sumsUrl = sumsUrl,
                    fileName = "mt86plus_${archive.version}_x86_64.iso",
                    archiveEntry = MEMTEST_ARCHIVE_ENTRY,
                    archiveSha256 = archiveSha256,
                ),
            ),
        )
    }
}

/** The single entry inside every `*.iso.zip`. */
internal const val MEMTEST_ARCHIVE_ENTRY = "memtest.iso"

internal data class MemtestArchive(
    val version: String,
    val url: String,
    val fileName: String,
)

/** Newest `mt86plus_<version>_x86_64.iso.zip` linked from the home page, or null if none is. */
internal fun newestMemtestArchive(
    html: String,
    pageUrl: String,
): MemtestArchive? {
    val archiveName = Regex("""^mt86plus_(\d+(?:\.\d+)+)_x86_64\.iso\.zip$""")
    return hrefs(html)
        .map { resolveUrl(pageUrl, it) }
        .filter { it.startsWith("https://") }
        .distinct()
        .mapNotNull { url ->
            val fileName = url.substringBefore('?').substringAfterLast('/')
            val match = archiveName.matchEntire(fileName) ?: return@mapNotNull null
            MemtestArchive(match.groupValues[1], url, fileName)
        }.maxWithOrNull(compareBy(numericVersionOrder) { it.version })
}
