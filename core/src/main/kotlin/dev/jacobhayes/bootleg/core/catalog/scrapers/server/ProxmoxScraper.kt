package dev.jacobhayes.bootleg.core.catalog.scrapers.server

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Proxmox VE installer ISOs from the SHA256SUMS of the official ISO directory, newest point
 * release per arch for each offered major. The directory keeps old majors around, so the
 * directory page's dates decide which majors are still offered (see [selectProxmoxImages]).
 */
class ProxmoxScraper(
    private val isoRootUrl: String = "https://enterprise.proxmox.com/iso/",
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
) : Scraper {
    override val distros =
        listOf(Distro("proxmox-ve", "Proxmox VE", Category.SERVER, "https://www.proxmox.com/en/products/proxmox-virtual-environment"))

    override suspend fun releases(http: Http): List<Release> {
        val sumsUrl = "${isoRootUrl}SHA256SUMS"
        val images = parseProxmoxSums(http.getText(sumsUrl))
        if (images.isEmpty()) return emptyList()
        val dates = parseProxmoxIsoDates(http.getText(isoRootUrl))
        return checkedReleases(
            selectProxmoxImages(images, dates, today()).map { image ->
                Release(
                    distroId = "proxmox-ve",
                    version = image.version,
                    arch = image.arch,
                    url = "$isoRootUrl${image.fileName}",
                    sha256 = image.sha256,
                    sumsUrl = sumsUrl,
                    // SHA256SUMS.asc is published alongside.
                    sumsSigned = true,
                    fileName = image.fileName,
                )
            },
        )
    }
}

internal data class ProxmoxImage(
    val major: Int,
    val version: String,
    val arch: Arch,
    val fileName: String,
    val sha256: String,
)

/** Every Proxmox VE installer ISO in SHA256SUMS; other products (backup server, mail gateway) are skipped. */
internal fun parseProxmoxSums(text: String): List<ProxmoxImage> {
    val imageName = Regex("""^proxmox-ve_((\d+)\.\d+-\d+)(-arm64)?\.iso$""")
    return parseSha256Sums(text).mapNotNull { (fileName, sha256) ->
        val match = imageName.matchEntire(fileName) ?: return@mapNotNull null
        val arch = if (match.groupValues[3].isEmpty()) Arch.X86_64 else Arch.ARM64
        ProxmoxImage(match.groupValues[2].toInt(), match.groupValues[1], arch, fileName, sha256)
    }
}

/** File name -> upload date from the ISO directory page, whose cards show each file's name, then its date. */
internal fun parseProxmoxIsoDates(html: String): Map<String, LocalDate> {
    val fileNames =
        Regex("""class="filename"\s*>\s*([^<\s]+)\s*<""")
            .findAll(html)
            .take(ServerScraperLimits.INDEX_LINKS_MAX + 1)
            .toList()
    check(fileNames.size <= ServerScraperLimits.INDEX_LINKS_MAX) {
        "proxmox: ISO page lists more than ${ServerScraperLimits.INDEX_LINKS_MAX} files"
    }
    val date = Regex("""<span>\s*(\d{4}-\d{2}-\d{2})\s*</span>""")
    return fileNames
        .mapIndexedNotNull { index, match ->
            val cardEnd = fileNames.getOrNull(index + 1)?.range?.first ?: html.length
            val cardDate = date.find(html.substring(match.range.last + 1, cardEnd)) ?: return@mapIndexedNotNull null
            match.groupValues[1] to LocalDate.parse(cardDate.groupValues[1])
        }.toMap()
}

/** How long after a new major's first ISO the previous major is still offered. */
internal const val PROXMOX_PREVIOUS_MAJOR_SUPPORT_MONTHS = 13L

/**
 * Proxmox supports the previous major for roughly a year after a new major ships (aligned with
 * Debian oldstable), and the directory carries no EOL data. So the previous major is offered while
 * the newest major's oldest dated ISO is less than [PROXMOX_PREVIOUS_MAJOR_SUPPORT_MONTHS] months
 * old; older majors never are. The directory may have pruned the x.0 ISO, which makes that date
 * later than the real launch and can keep the previous major a few months past its EOL; the
 * alternative (hard-coding EOL dates) goes stale silently.
 */
internal fun selectProxmoxImages(
    images: List<ProxmoxImage>,
    dates: Map<String, LocalDate>,
    today: LocalDate,
): List<ProxmoxImage> {
    val newestMajor = images.maxOfOrNull { it.major } ?: return emptyList()
    val newestMajorFirstDate =
        checkNotNull(images.filter { it.major == newestMajor }.mapNotNull { dates[it.fileName] }.minOrNull()) {
            "proxmox: ISO page has no date for any Proxmox VE $newestMajor ISO"
        }
    val previousMajorSupported = newestMajorFirstDate.plusMonths(PROXMOX_PREVIOUS_MAJOR_SUPPORT_MONTHS).isAfter(today)
    val offeredMajors = if (previousMajorSupported) setOf(newestMajor, newestMajor - 1) else setOf(newestMajor)
    return images
        .filter { it.major in offeredMajors }
        .groupBy { it.major to it.arch }
        .map { (_, sameMajorAndArch) -> sameMajorAndArch.maxWith(compareBy(numericVersionOrder) { it.version }) }
        .sortedWith(compareByDescending<ProxmoxImage> { it.major }.thenBy { it.arch })
}
