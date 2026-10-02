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
 * Ubuntu and its official flavors. Supported series come from distro-info-data (the source behind
 * `ubuntu-distro-info`, with release and end-of-life dates). Images and hashes come from each
 * project's signed SHA256SUMS on cdimage.ubuntu.com, plus releases.ubuntu.com for Ubuntu's amd64
 * images. The newest point release per series/arch/kind wins.
 */
class UbuntuScraper(
    private val project: UbuntuProject,
    private val today: () -> LocalDate = { LocalDate.now() },
) : Scraper {
    override val distros: List<Distro> = project.distros

    override suspend fun releases(http: Http): List<Release> {
        val today = today()
        val series =
            parseDistroInfo(http.getText(DISTRO_INFO_URL)).mapNotNull { series ->
                supportedUntil(series, today)?.let { series to it }
            }
        if (series.isEmpty()) return emptyList()
        val listingUrl = "$CDIMAGE/${project.directory}/releases/"
        val published = ScraperSupport.listingNames(http.getText(listingUrl), listingUrl).toSet()
        val sources =
            series.filter { (each, _) -> "${each.codename}/" in published }.flatMap { (each, until) ->
                buildList {
                    add(SumsSource("$listingUrl${each.codename}/release/", each, until))
                    if (project == UbuntuProject.UBUNTU) add(SumsSource("$RELEASES/${each.codename}/", each, until))
                }
            }
        return ScraperSupport.fanOut(sources) { source -> releasesFrom(source, http.getText(source.directoryUrl + "SHA256SUMS")) }.flatten()
    }

    private data class SumsSource(
        val directoryUrl: String,
        val series: UbuntuSeries,
        val supportedUntil: LocalDate,
    )

    private fun releasesFrom(
        source: SumsSource,
        sumsText: String,
    ): List<Release> {
        val imageRegex =
            Regex(
                """^${Regex.escape(
                    project.directory,
                )}-(${Regex.escape(source.series.version)}(?:\.\d+)*)-(desktop|live-server|dvd|minimal)-(amd64|arm64)\.iso$""",
            )
        val candidates =
            ScraperSupport.parseSha256Sums(sumsText).mapNotNull { (fileName, hash) ->
                val match = imageRegex.matchEntire(fileName) ?: return@mapNotNull null
                val (pointVersion, kind, arch) = match.destructured
                val distroId = project.distroIdFor(kind) ?: return@mapNotNull null
                val variant = if (kind == "minimal") "Minimal" else ""
                Release(
                    distroId = distroId,
                    version = if (source.series.lts) "$pointVersion LTS" else pointVersion,
                    arch = if (arch == "amd64") Arch.X86_64 else Arch.ARM64,
                    variant = variant,
                    url = source.directoryUrl + fileName,
                    sha256 = hash,
                    sumsUrl = source.directoryUrl + "SHA256SUMS",
                    supportedUntil = source.supportedUntil.toString(),
                    sumsSigned = true,
                    fileName = fileName,
                )
            }
        return candidates
            .groupBy { Triple(it.distroId, it.arch, it.variant) }
            .values
            .map { group -> group.maxWith(compareBy(ScraperSupport.versionComparator) { it.version }) }
    }

    /** End of support for this project's images of [series], or null if not supported on [today]. */
    private fun supportedUntil(
        series: UbuntuSeries,
        today: LocalDate,
    ): LocalDate? {
        // Flavors support LTS releases for 3 years, Ubuntu itself for 5 (standard support).
        val until =
            if (project != UbuntuProject.UBUNTU && series.lts) {
                minOf(series.endOfLife, series.released.plusYears(FLAVOR_LTS_SUPPORT_YEARS))
            } else {
                series.endOfLife
            }
        return until.takeIf { !series.released.isAfter(today) && today.isBefore(it) }
    }

    companion object {
        const val DISTRO_INFO_URL = "https://debian.pages.debian.net/distro-info-data/ubuntu.csv"
        const val CDIMAGE = "https://cdimage.ubuntu.com"
        const val RELEASES = "https://releases.ubuntu.com"
        private const val FLAVOR_LTS_SUPPORT_YEARS = 3L
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

enum class UbuntuProject(
    /** Directory on cdimage.ubuntu.com and ISO file name prefix. */
    val directory: String,
    val distroId: String,
    val displayName: String,
    val homepage: String,
) {
    UBUNTU("ubuntu", "ubuntu", "Ubuntu", "https://ubuntu.com/desktop"),
    KUBUNTU("kubuntu", "kubuntu", "Kubuntu", "https://kubuntu.org"),
    XUBUNTU("xubuntu", "xubuntu", "Xubuntu", "https://xubuntu.org"),
    LUBUNTU("lubuntu", "lubuntu", "Lubuntu", "https://lubuntu.me"),
    MATE("ubuntu-mate", "ubuntu-mate", "Ubuntu MATE", "https://ubuntu-mate.org"),
    BUDGIE("ubuntu-budgie", "ubuntu-budgie", "Ubuntu Budgie", "https://ubuntubudgie.org"),
    STUDIO("ubuntustudio", "ubuntu-studio", "Ubuntu Studio", "https://ubuntustudio.org"),
    CINNAMON("ubuntucinnamon", "ubuntu-cinnamon", "Ubuntu Cinnamon", "https://ubuntucinnamon.org"),
    UNITY("ubuntu-unity", "ubuntu-unity", "Ubuntu Unity", "https://ubuntuunity.org"),
    EDUBUNTU("edubuntu", "edubuntu", "Edubuntu", "https://edubuntu.org"),
    KYLIN("ubuntukylin", "ubuntu-kylin", "Ubuntu Kylin", "https://www.ubuntukylin.com"),
    ;

    val distros: List<Distro>
        get() {
            val desktop = Distro(distroId, displayName, Category.DESKTOP, homepage)
            val server = Distro(UBUNTU_SERVER_ID, "Ubuntu Server", Category.SERVER, "https://ubuntu.com/server")
            return if (this == UBUNTU) listOf(desktop, server) else listOf(desktop)
        }

    /** Maps an ISO kind (desktop, live-server, dvd, minimal) to a distro id, or null to skip it. */
    fun distroIdFor(kind: String): String? =
        when (kind) {
            "live-server" -> UBUNTU_SERVER_ID.takeIf { this == UBUNTU }
            else -> distroId
        }

    companion object {
        const val UBUNTU_SERVER_ID = "ubuntu-server"
    }
}

val ubuntuScrapers: List<Scraper> = UbuntuProject.entries.map { UbuntuScraper(it) }
