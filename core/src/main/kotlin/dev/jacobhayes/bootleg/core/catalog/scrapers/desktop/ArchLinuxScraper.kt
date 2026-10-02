package dev.jacobhayes.bootleg.core.catalog.scrapers.desktop

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.catalog.getText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Arch Linux's monthly ISO from archlinux.org's releng JSON (sha256 + size), served from the geo mirror. x86_64 only. */
class ArchLinuxScraper : Scraper {
    override val distros = listOf(Distro("archlinux", "Arch Linux", Category.DESKTOP, "https://archlinux.org"))

    @Serializable
    private data class Releases(
        @SerialName("latest_version") val latestVersion: String,
        val releases: List<Entry>,
    )

    @Serializable
    private data class Entry(
        val version: String,
        val available: Boolean,
        @SerialName("iso_url") val isoUrl: String,
        @SerialName("sha256_sum") val sha256: String? = null,
        val torrent: Torrent? = null,
    )

    @Serializable
    private data class Torrent(
        @SerialName("file_length") val fileLength: Long? = null,
    )

    override suspend fun releases(http: Http): List<Release> {
        val index = ScraperSupport.json.decodeFromString(Releases.serializer(), http.getText(RELEASES_URL))
        val latest = index.releases.firstOrNull { it.version == index.latestVersion && it.available } ?: return emptyList()
        require(latest.isoUrl.startsWith("/iso/")) { "unexpected iso_url ${latest.isoUrl}" }
        val url = MIRROR + latest.isoUrl
        return listOf(
            Release(
                distroId = "archlinux",
                version = latest.version,
                arch = Arch.X86_64,
                url = url,
                sha256 = requireNotNull(latest.sha256) { "no sha256 for Arch ${latest.version}" }.lowercase(),
                sumsUrl = "$MIRROR/iso/${latest.version}/sha256sums.txt",
                sizeBytes = latest.torrent?.fileLength,
                fileName = ScraperSupport.fileNameOf(url),
            ),
        )
    }

    companion object {
        const val RELEASES_URL = "https://archlinux.org/releng/releases/json/"
        const val MIRROR = "https://geo.mirror.pkgbuild.com"
    }
}
