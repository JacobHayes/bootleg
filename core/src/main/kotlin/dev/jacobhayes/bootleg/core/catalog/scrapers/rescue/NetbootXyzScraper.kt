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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * netboot.xyz iPXE boot images (ISO and USB img, plain and Secure Boot, x86_64 and arm64). The
 * images are rebuilt in place at fixed URLs, so the version is the build date from the checksums
 * file header.
 */
class NetbootXyzScraper(
    private val ipxeRootUrl: String = "https://boot.netboot.xyz/ipxe/",
) : Scraper {
    override val distros = listOf(Distro("netboot-xyz", "netboot.xyz", Category.RESCUE, "https://netboot.xyz"))

    override suspend fun releases(http: Http): List<Release> {
        val sumsUrl = "${ipxeRootUrl}netboot.xyz-sha256-checksums.txt"
        val text = http.getText(sumsUrl)
        val sums = ScraperSupport.parseSha256Sums(text)
        val version = parseNetbootXyzBuildDate(text) ?: "latest"
        return checkedReleases(
            netbootXyzImages.mapNotNull { image ->
                val sha256 = sums[image.fileName] ?: return@mapNotNull null
                Release(
                    distroId = "netboot-xyz",
                    version = version,
                    arch = image.arch,
                    variant = image.variant,
                    url = "$ipxeRootUrl${image.fileName}",
                    sha256 = sha256,
                    sumsUrl = sumsUrl,
                    fileName = image.fileName,
                )
            },
        )
    }

    private data class Image(
        val fileName: String,
        val arch: Arch,
        val variant: String,
    )

    private companion object {
        val netbootXyzImages =
            listOf(
                Image("netboot.xyz.iso", Arch.X86_64, "ISO"),
                Image("netboot.xyz.img", Arch.X86_64, "USB"),
                Image("netboot.xyz-sb.iso", Arch.X86_64, "Secure Boot ISO"),
                Image("netboot.xyz-sb.img", Arch.X86_64, "Secure Boot USB"),
                Image("netboot.xyz-arm64.iso", Arch.ARM64, "ISO"),
                Image("netboot.xyz-arm64.img", Arch.ARM64, "USB"),
                Image("netboot.xyz-sb-arm64.iso", Arch.ARM64, "Secure Boot ISO"),
                Image("netboot.xyz-sb-arm64.img", Arch.ARM64, "Secure Boot USB"),
            )
    }
}

/** ISO date from `# netboot.xyz bootloaders generated at Thu Oct  1 11:05:18 UTC 2026`, or null. */
internal fun parseNetbootXyzBuildDate(text: String): String? {
    val match = Regex("""generated at \w{3}\s+(\w{3})\s+(\d{1,2})\s+[\d:]+\s+\w+\s+(\d{4})""").find(text) ?: return null
    val (month, day, year) = match.destructured
    return try {
        LocalDate.parse("$month $day $year", DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH)).toString()
    } catch (_: DateTimeParseException) {
        null
    }
}
