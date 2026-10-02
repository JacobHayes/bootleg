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
 * SystemRescue (amd64 only). The download page is the only index; it links the current ISO on
 * the CDN and its `.sha256` (`hash *file`) on the main site.
 */
class SystemRescueScraper(
    private val downloadPageUrl: String = "https://www.system-rescue.org/Download/",
) : Scraper {
    override val distros = listOf(Distro("systemrescue", "SystemRescue", Category.RESCUE, "https://www.system-rescue.org"))

    override suspend fun releases(http: Http): List<Release> {
        val links = parseSystemRescueDownloadPage(http.getText(downloadPageUrl), downloadPageUrl) ?: return emptyList()
        val sums = ScraperSupport.parseSha256Sums(http.getText(links.sumsUrl))
        val sha256 = checkNotNull(sums[links.fileName]) { "systemrescue: ${links.sumsUrl} does not list ${links.fileName}" }
        return checkedReleases(
            listOf(
                Release(
                    distroId = "systemrescue",
                    version = links.version,
                    arch = Arch.X86_64,
                    url = links.isoUrl,
                    sha256 = sha256,
                    sumsUrl = links.sumsUrl,
                    // SystemRescue signs the ISO (.asc), not the sums.
                    sumsSigned = false,
                    fileName = links.fileName,
                ),
            ),
        )
    }
}

internal data class SystemRescueLinks(
    val version: String,
    val fileName: String,
    val isoUrl: String,
    val sumsUrl: String,
)

/** Newest amd64 ISO that has both a direct link and a `.sha256` link, or null if none. */
internal fun parseSystemRescueDownloadPage(
    html: String,
    pageUrl: String,
): SystemRescueLinks? {
    val urls = hrefs(html).map { resolveUrl(pageUrl, it) }.filter { it.startsWith("https://") }
    val isoName = Regex("""/releases/([\d.]+)/(systemrescue-\1-amd64\.iso)$""")
    val isoByVersion =
        urls
            .mapNotNull { url -> isoName.find(url)?.let { it.groupValues[1] to url } }
            .toMap()
    val version =
        isoByVersion.keys
            .filter { v ->
                urls.any { it.endsWith("/releases/$v/systemrescue-$v-amd64.iso.sha256") }
            }.maxWithOrNull(numericVersionOrder)
            ?: return null
    val fileName = "systemrescue-$version-amd64.iso"
    return SystemRescueLinks(
        version = version,
        fileName = fileName,
        isoUrl = isoByVersion.getValue(version),
        sumsUrl = urls.first { it.endsWith("/releases/$version/$fileName.sha256") },
    )
}
