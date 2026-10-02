package dev.jacobhayes.bootleg.core.catalog.scrapers.rescue

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.checkedReleases
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.getText
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.parseSha256Sums

/**
 * GParted Live and Clonezilla Live share one release process: a stable `CHECKSUMS.TXT` (MD5 through
 * BLAKE3 sections, plus a `.gpg` signature) on the project site, with the ISOs on SourceForge under
 * `<folder>/<version>/`. The checksums file names the current stable version.
 */
class LiveCdScraper(
    private val distro: Distro,
    private val checksumsUrl: String,
    /** File name prefix before the version, e.g. "gparted-live". */
    private val filePrefix: String,
    /** SourceForge `project/<name>/<folder>` path holding per-version directories. */
    private val sourceForgePath: String,
) : Scraper {
    override val distros = listOf(distro)

    override suspend fun releases(http: Http): List<Release> =
        checkedReleases(
            parseLiveCdChecksums(http.getText(checksumsUrl), filePrefix).map { image ->
                Release(
                    distroId = distro.id,
                    version = image.version,
                    arch = image.arch,
                    url = "https://downloads.sourceforge.net/project/$sourceForgePath/${image.version}/${image.fileName}",
                    sha256 = image.sha256,
                    sumsUrl = checksumsUrl,
                    sumsSigned = true,
                    fileName = image.fileName,
                )
            },
        )
}

internal data class LiveCdImage(
    val version: String,
    val arch: Arch,
    val fileName: String,
    val sha256: String,
)

internal fun parseLiveCdChecksums(
    text: String,
    filePrefix: String,
): List<LiveCdImage> {
    val imageName = Regex("""^${Regex.escape(filePrefix)}-(\d+(?:\.\d+)+-\d+)-(amd64|arm64)\.iso$""")
    val images =
        parseSha256Sums(text).mapNotNull { (fileName, sha256) ->
            val match = imageName.matchEntire(fileName) ?: return@mapNotNull null
            val arch = if (match.groupValues[2] == "amd64") Arch.X86_64 else Arch.ARM64
            LiveCdImage(match.groupValues[1], arch, fileName, sha256)
        }
    val versions = images.map { it.version }.distinct()
    check(versions.size <= 1) { "$filePrefix: stable checksums list several versions: $versions" }
    return images.sortedBy { it.arch }
}

val gpartedScraper =
    LiveCdScraper(
        Distro("gparted-live", "GParted Live", Category.RESCUE, "https://gparted.org/livecd.php"),
        checksumsUrl = "https://gparted.org/gparted-live/stable/CHECKSUMS.TXT",
        filePrefix = "gparted-live",
        sourceForgePath = "gparted/gparted-live-stable",
    )

val clonezillaScraper =
    LiveCdScraper(
        Distro("clonezilla", "Clonezilla Live", Category.RESCUE, "https://clonezilla.org"),
        checksumsUrl = "https://clonezilla.org/downloads/stable/data/CHECKSUMS.TXT",
        filePrefix = "clonezilla-live",
        sourceForgePath = "clonezilla/clonezilla_live_stable",
    )
