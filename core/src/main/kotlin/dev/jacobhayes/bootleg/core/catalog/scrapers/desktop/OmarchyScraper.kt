package dev.jacobhayes.bootleg.core.catalog.scrapers.desktop

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.catalog.getText

/**
 * Omarchy publishes no API or index (iso.omarchy.org has no listing), so the ISO and its `.sha256`
 * are taken from the links on omarchy.org's home page, matched by URL shape rather than page
 * structure. x86_64 only.
 */
class OmarchyScraper : Scraper {
    override val distros = listOf(Distro("omarchy", "Omarchy", Category.DESKTOP, "https://omarchy.org"))

    override suspend fun releases(http: Http): List<Release> {
        val links = ScraperSupport.links(http.getText(HOME_URL), HOME_URL).toSet()
        val newest =
            links
                .mapNotNull { link -> isoRegex.matchEntire(link)?.let { link to it.groupValues[1] } }
                .maxWithOrNull(compareBy(ScraperSupport.versionComparator) { it.second }) ?: return emptyList()
        val (url, version) = newest
        val sumsUrl = "$url.sha256"
        require(sumsUrl in links) { "omarchy.org links $url but not its checksum" }
        return listOf(
            Release(
                distroId = "omarchy",
                version = version,
                arch = Arch.X86_64,
                url = url,
                sumsUrl = sumsUrl,
                sumsSigned = "$url.sig" in links,
                fileName = ScraperSupport.fileNameOf(url),
            ),
        )
    }

    companion object {
        const val HOME_URL = "https://omarchy.org/"
        private val isoRegex = Regex("""^https://iso\.omarchy\.org/omarchy-(\d+(?:\.\d+)*)\.iso$""")
    }
}
