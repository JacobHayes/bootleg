package dev.jacobhayes.bootleg.core.catalog.scrapers.desktop

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.catalog.getText
import java.time.LocalDate

/** One Ubuntu series from distro-info-data, e.g. 24.04 LTS "noble". */
data class UbuntuSeries(
    val version: String,
    val codename: String,
    val lts: Boolean,
    val released: LocalDate,
    val endOfLife: LocalDate,
)

/**
 * Ubuntu (desktop) and Ubuntu Server. Supported series come from distro-info-data (the source behind
 * `ubuntu-distro-info`, with release and end-of-life dates). Images and hashes come from the signed
 * SHA256SUMS on cdimage.ubuntu.com (arm64) and releases.ubuntu.com (amd64). The newest point release
 * per series/arch/kind wins.
 */
class UbuntuScraper(
    private val today: () -> LocalDate = { LocalDate.now() },
) : Scraper {
    override val distros: List<Distro> =
        listOf(
            Distro(UBUNTU_ID, "Ubuntu", Category.DESKTOP, "https://ubuntu.com/desktop"),
            Distro(UBUNTU_SERVER_ID, "Ubuntu Server", Category.SERVER, "https://ubuntu.com/server"),
        )

    override suspend fun releases(http: Http): List<Release> {
        val today = today()
        val series =
            parseDistroInfo(http.getText(DISTRO_INFO_URL)).filter { series ->
                !series.released.isAfter(today) && today.isBefore(series.endOfLife)
            }
        if (series.isEmpty()) return emptyList()
        val listingUrl = "$CDIMAGE/ubuntu/releases/"
        val published = ScraperSupport.listingNames(http.getText(listingUrl), listingUrl).toSet()
        val sources =
            series.filter { "${it.codename}/" in published }.flatMap { each ->
                listOf(SumsSource("$listingUrl${each.codename}/release/", each), SumsSource("$RELEASES/${each.codename}/", each))
            }
        return ScraperSupport.fanOut(sources) { source -> releasesFrom(source, http.getText(source.directoryUrl + "SHA256SUMS")) }.flatten()
    }

    private data class SumsSource(
        val directoryUrl: String,
        val series: UbuntuSeries,
    )

    private fun releasesFrom(
        source: SumsSource,
        sumsText: String,
    ): List<Release> {
        val imageRegex =
            Regex(
                """^ubuntu-(${Regex.escape(source.series.version)}(?:\.\d+)*)-(desktop|live-server|minimal)-(amd64|arm64)\.iso$""",
            )
        val candidates =
            ScraperSupport.parseSha256Sums(sumsText).mapNotNull { (fileName, hash) ->
                val match = imageRegex.matchEntire(fileName) ?: return@mapNotNull null
                val (pointVersion, kind, arch) = match.destructured
                Release(
                    distroId = if (kind == "live-server") UBUNTU_SERVER_ID else UBUNTU_ID,
                    version = if (source.series.lts) "$pointVersion LTS" else pointVersion,
                    arch = if (arch == "amd64") Arch.X86_64 else Arch.ARM64,
                    variant = if (kind == "minimal") "Minimal" else "",
                    url = source.directoryUrl + fileName,
                    sha256 = hash,
                    sumsUrl = source.directoryUrl + "SHA256SUMS",
                    supportedUntil = source.series.endOfLife.toString(),
                    sumsSigned = true,
                    fileName = fileName,
                )
            }
        return candidates
            .groupBy { Triple(it.distroId, it.arch, it.variant) }
            .values
            .map { group -> group.maxWith(compareBy(ScraperSupport.versionComparator) { it.version }) }
    }

    companion object {
        const val DISTRO_INFO_URL = "https://debian.pages.debian.net/distro-info-data/ubuntu.csv"
        const val CDIMAGE = "https://cdimage.ubuntu.com"
        const val RELEASES = "https://releases.ubuntu.com"
        const val UBUNTU_ID = "ubuntu"
        const val UBUNTU_SERVER_ID = "ubuntu-server"
        private const val SERIES_MAX = 200

        /** Parses distro-info-data's ubuntu.csv (version,codename,series,created,release,eol,...). */
        fun parseDistroInfo(csv: String): List<UbuntuSeries> {
            val lines = csv.lines().filter { it.isNotBlank() }
            require(lines.size in 2..SERIES_MAX) { "unexpected ubuntu.csv size: ${lines.size} lines" }
            val header = lines.first().split(',')
            val columns = listOf("version", "series", "release", "eol").associateWith { header.indexOf(it) }
            require(columns.values.all { it >= 0 }) { "unexpected ubuntu.csv header: ${lines.first()}" }
            return lines.drop(1).map { line ->
                val fields = line.split(',')
                require(fields.size >= header.size - 3) { "malformed ubuntu.csv line: $line" }
                val version = fields[columns.getValue("version")]
                UbuntuSeries(
                    version = version.removeSuffix(" LTS"),
                    codename = fields[columns.getValue("series")],
                    lts = version.endsWith(" LTS"),
                    released = LocalDate.parse(fields[columns.getValue("release")]),
                    endOfLife = LocalDate.parse(fields[columns.getValue("eol")]),
                )
            }
        }
    }
}
