package dev.jacobhayes.bootleg.catalog

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import java.io.IOException

/**
 * Debug-only extras. The real scrapers already cover netboot.xyz; these exercise UI states the real
 * catalog rarely shows. Off by default so debug builds show the real catalog; flip
 * [DEBUG_FAKE_SCRAPERS_ENABLED] to see fake releases and a failing scraper.
 */
fun debugScrapers(): List<Scraper> = if (DEBUG_FAKE_SCRAPERS_ENABLED) listOf(StaticScraper, FailingScraper) else emptyList()

private const val DEBUG_FAKE_SCRAPERS_ENABLED = false

private object StaticScraper : Scraper {
    private val desktop = Distro("debug-desktop", "Sample Desktop (debug)", Category.DESKTOP, "https://example.org")
    private val server = Distro("debug-server", "Sample Server (debug)", Category.SERVER, "https://example.org")
    override val distros = listOf(desktop, server)

    override suspend fun releases(http: Http): List<Release> =
        listOf(
            sample(desktop, "26.04 LTS", Arch.X86_64, "GNOME", 6_200_000_000),
            sample(desktop, "26.04 LTS", Arch.X86_64, "KDE", 4_100_000_000),
            sample(desktop, "26.04 LTS", Arch.ARM64, "GNOME", 5_900_000_000),
            sample(desktop, "25.10", Arch.X86_64, "GNOME", 5_800_000_000),
            sample(server, "13.2", Arch.X86_64, "netinst", 750_000_000),
            sample(server, "13.2", Arch.ARM64, "netinst", 640_000_000),
        )

    private fun sample(
        distro: Distro,
        version: String,
        arch: Arch,
        variant: String,
        sizeBytes: Long,
    ): Release {
        val slug = "${distro.id}-${version.substringBefore(' ')}-${variant.lowercase()}-${arch.name.lowercase()}.iso"
        return Release(
            distroId = distro.id,
            version = version,
            arch = arch,
            variant = variant,
            url = "https://example.invalid/$slug",
            sha256 = "0".repeat(64),
            sizeBytes = sizeBytes,
            supportedUntil = "2031-04-30",
            fileName = slug,
        )
    }
}

private object FailingScraper : Scraper {
    override val distros = listOf(Distro("debug-broken", "Broken mirror (debug)", Category.DESKTOP, "https://example.invalid"))

    override suspend fun releases(http: Http): List<Release> = throw IOException("Release index returned HTTP 503")
}
