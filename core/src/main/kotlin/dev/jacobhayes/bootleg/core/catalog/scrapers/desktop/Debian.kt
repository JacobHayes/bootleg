package dev.jacobhayes.bootleg.core.catalog.scrapers.desktop

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.catalog.getText

private const val DEBIAN_CD = "https://cdimage.debian.org/debian-cd"
private val debianArches = mapOf("amd64" to Arch.X86_64, "arm64" to Arch.ARM64)

/** Debian stable live images (one per desktop), from `current-live/<arch>/iso-hybrid/SHA256SUMS`. */
class DebianLiveScraper : Scraper {
    override val distros = listOf(Distro("debian", "Debian", Category.DESKTOP, "https://www.debian.org"))

    override suspend fun releases(http: Http): List<Release> {
        val archesUrl = "$DEBIAN_CD/current-live/"
        val published = ScraperSupport.listingNames(http.getText(archesUrl), archesUrl).map { it.removeSuffix("/") }
        val arches = debianArches.filterKeys { it in published }
        return ScraperSupport
            .fanOut(arches.toList()) { (debianArch, arch) ->
                val directory = "$archesUrl$debianArch/iso-hybrid/"
                val imageRegex = Regex("""^debian-live-(\d+(?:\.\d+)*)-${Regex.escape(debianArch)}-([a-z0-9-]+)\.iso$""")
                ScraperSupport.parseSha256Sums(http.getText(directory + "SHA256SUMS")).mapNotNull { (fileName, hash) ->
                    val (version, desktop) = imageRegex.matchEntire(fileName)?.destructured ?: return@mapNotNull null
                    Release(
                        distroId = "debian",
                        version = version,
                        arch = arch,
                        variant = desktopNames[desktop] ?: desktop,
                        url = directory + fileName,
                        sha256 = hash,
                        sumsUrl = directory + "SHA256SUMS",
                        sumsSigned = true,
                        fileName = fileName,
                    )
                }
            }.flatten()
    }

    private val desktopNames =
        mapOf(
            "gnome" to "GNOME",
            "kde" to "KDE Plasma",
            "xfce" to "Xfce",
            "cinnamon" to "Cinnamon",
            "mate" to "MATE",
            "lxde" to "LXDE",
            "lxqt" to "LXQt",
            "standard" to "Standard",
            "debian-junior" to "Junior",
        )
}

/** Debian stable netinst, from `current/<arch>/iso-cd/SHA256SUMS`. */
class DebianNetinstScraper : Scraper {
    override val distros = listOf(Distro("debian-netinst", "Debian netinst", Category.SERVER, "https://www.debian.org/CD/netinst/"))

    override suspend fun releases(http: Http): List<Release> =
        ScraperSupport
            .fanOut(debianArches.toList()) { (debianArch, arch) ->
                val directory = "$DEBIAN_CD/current/$debianArch/iso-cd/"
                val imageRegex = Regex("""^debian-(\d+(?:\.\d+)*)-${Regex.escape(debianArch)}-netinst\.iso$""")
                ScraperSupport.parseSha256Sums(http.getText(directory + "SHA256SUMS")).mapNotNull { (fileName, hash) ->
                    val version = imageRegex.matchEntire(fileName)?.groupValues?.get(1) ?: return@mapNotNull null
                    Release(
                        distroId = "debian-netinst",
                        version = version,
                        arch = arch,
                        variant = "netinst",
                        url = directory + fileName,
                        sha256 = hash,
                        sumsUrl = directory + "SHA256SUMS",
                        sumsSigned = true,
                        fileName = fileName,
                    )
                }
            }.flatten()
}
