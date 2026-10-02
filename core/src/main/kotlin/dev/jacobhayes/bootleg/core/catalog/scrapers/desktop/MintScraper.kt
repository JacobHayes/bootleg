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
 * Linux Mint (Cinnamon/MATE/Xfce) and LMDE from the kernel.org mirror's directory listings and signed
 * sha256sum.txt files (Mint has no API). Mint keeps supporting the previous major series (built on
 * the previous Ubuntu LTS) for years after a new one ships, so the newest point release of each of
 * the two newest majors is offered.
 * x86_64 only (Mint publishes no arm64 images).
 */
class MintScraper : Scraper {
    override val distros = listOf(Distro("linuxmint", "Linux Mint", Category.DESKTOP, "https://linuxmint.com"))

    override suspend fun releases(http: Http): List<Release> {
        val stableUrl = "$MIRROR/stable/"
        val versions =
            ScraperSupport
                .listingNames(http.getText(stableUrl), stableUrl)
                .map { it.removeSuffix("/") }
                .filter { versionRegex.matches(it) }
        val newestPerMajor =
            versions
                .groupBy { it.substringBefore('.') }
                .values
                .map { group -> group.maxWith(ScraperSupport.versionComparator) }
                .sortedWith(ScraperSupport.versionComparator)
                .takeLast(SUPPORTED_MAJOR_COUNT)
        val directories = newestPerMajor.map { "$stableUrl$it/" } + "$MIRROR/debian/"
        return ScraperSupport.fanOut(directories) { directory -> parse(directory, http.getText(directory + "sha256sum.txt")) }.flatten()
    }

    private fun parse(
        directory: String,
        sumsText: String,
    ): List<Release> {
        val sums = ScraperSupport.parseSha256Sums(sumsText)
        val mint =
            sums.mapNotNull { (fileName, hash) ->
                val (version, edition, edge) = mintRegex.matchEntire(fileName)?.destructured ?: return@mapNotNull null
                release(directory, fileName, hash, version, editions.getValue(edition) + if (edge.isEmpty()) "" else " Edge")
            }
        // LMDE's directory keeps old releases; only the newest is supported.
        val lmde =
            sums
                .mapNotNull { (fileName, hash) ->
                    val version = lmdeRegex.matchEntire(fileName)?.groupValues?.get(1) ?: return@mapNotNull null
                    release(directory, fileName, hash, version, "LMDE Cinnamon")
                }.maxWithOrNull(compareBy(ScraperSupport.versionComparator) { it.version })
        return mint + listOfNotNull(lmde)
    }

    private fun release(
        directory: String,
        fileName: String,
        hash: String,
        version: String,
        variant: String,
    ) = Release(
        distroId = "linuxmint",
        version = version,
        arch = Arch.X86_64,
        variant = variant,
        url = directory + fileName,
        sha256 = hash,
        sumsUrl = directory + "sha256sum.txt",
        sumsSigned = true,
        fileName = fileName,
    )

    companion object {
        const val MIRROR = "https://mirrors.kernel.org/linuxmint"
        private const val SUPPORTED_MAJOR_COUNT = 2
        private val versionRegex = Regex("""^\d+(?:\.\d+)?$""")
        private val mintRegex = Regex("""^linuxmint-(\d+(?:\.\d+)?)-(cinnamon|mate|xfce)-64bit(-edge)?\.iso$""")
        private val lmdeRegex = Regex("""^lmde-(\d+)-cinnamon-64bit\.iso$""")
        private val editions = mapOf("cinnamon" to "Cinnamon", "mate" to "MATE", "xfce" to "Xfce")
    }
}
