package dev.jacobhayes.bootleg.core.catalog.scrapers.server

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper

/**
 * TrueNAS Community Edition. There is no structured release feed: the download.truenas.com index
 * is over 11 MB and the update server's trains.json is stale, so this reads the official download
 * page, takes the newest stable (numeric-only version) ISO link, and fetches its `.sha256`.
 */
class TrueNasScraper(
    private val downloadPageUrl: String = "https://www.truenas.com/download-truenas-community-edition/",
) : Scraper {
    override val distros =
        listOf(Distro("truenas", "TrueNAS Community Edition", Category.SERVER, "https://www.truenas.com/truenas-community-edition/"))

    override suspend fun releases(http: Http): List<Release> {
        val image = newestStableTrueNasIso(http.getText(downloadPageUrl), downloadPageUrl) ?: return emptyList()
        // The .sha256 holds a bare hash with no file name, so it is resolved here instead of passed on as sumsUrl.
        val sha256 = parseSingleSha256(http.getText("${image.url}.sha256"))
        return checkedReleases(
            listOf(
                Release(
                    distroId = "truenas",
                    version = image.version,
                    arch = Arch.X86_64,
                    url = image.url,
                    sha256 = sha256,
                    // TrueNAS signs the ISO (.gpg), not a sums file.
                    sumsSigned = false,
                    fileName = image.url.substringAfterLast('/'),
                ),
            ),
        )
    }
}

internal data class TrueNasIso(
    val version: String,
    val url: String,
)

/** Newest ISO link whose version is purely numeric (skips BETA/RC/nightly), or null if the page has none. */
internal fun newestStableTrueNasIso(
    html: String,
    pageUrl: String,
): TrueNasIso? {
    val isoName = Regex("""/TrueNAS-(?:SCALE-)?(\d+(?:\.\d+)+)\.iso$""")
    return hrefs(html)
        .map { resolveUrl(pageUrl, it) }
        .filter { it.startsWith("https://") }
        .mapNotNull { url -> isoName.find(url)?.let { TrueNasIso(it.groupValues[1], url) } }
        .distinct()
        .maxWithOrNull(compareBy(numericVersionOrder) { it.version })
}
