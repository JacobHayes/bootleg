package dev.jacobhayes.bootleg.core.catalog.scrapers.desktop

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.catalog.getText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Fedora Workstation, KDE Plasma and Server from fedoraproject.org/releases.json, which carries
 * sha256 and size for every image. Fedora supports the two newest releases; pre-releases
 * ("45 Beta") are skipped.
 */
class FedoraScraper : Scraper {
    override val distros = listOf(Distro("fedora", "Fedora", Category.DESKTOP, "https://fedoraproject.org"))

    @Serializable
    private data class Entry(
        val version: String,
        val arch: String,
        val link: String,
        val variant: String,
        val subvariant: String = "",
        val sha256: String? = null,
        val size: String? = null,
    )

    override suspend fun releases(http: Http): List<Release> {
        val entries = ScraperSupport.json.decodeFromString(ListSerializer(Entry.serializer()), http.getText(RELEASES_URL))
        require(entries.size <= ENTRIES_MAX) { "releases.json too large: ${entries.size} entries" }
        val stableVersions =
            entries
                .map { it.version }
                .filter { it.all(Char::isDigit) }
                .distinct()
                .sortedWith(ScraperSupport.versionComparator)
                .takeLast(SUPPORTED_RELEASE_COUNT)
                .toSet()
        return entries.mapNotNull { entry ->
            if (entry.version !in stableVersions || !entry.link.endsWith(".iso")) return@mapNotNull null
            val arch = arches[entry.arch] ?: return@mapNotNull null
            val fileName = ScraperSupport.fileNameOf(entry.link)
            val variant = variantOf(entry.variant, fileName) ?: return@mapNotNull null
            val sha256 = requireNotNull(entry.sha256) { "no sha256 for $fileName" }.lowercase()
            Release(
                distroId = "fedora",
                version = entry.version,
                arch = arch,
                variant = variant,
                url = entry.link,
                sha256 = sha256,
                sizeBytes = entry.size?.toLongOrNull(),
                fileName = fileName,
            )
        }
    }

    private fun variantOf(
        variant: String,
        fileName: String,
    ): String? =
        when (variant) {
            "Workstation" -> "Workstation"
            "KDE" -> "KDE Plasma"
            "Server" ->
                when {
                    "-dvd-" in fileName -> "Server"
                    "-netinst-" in fileName -> "Server netinst"
                    else -> null
                }
            else -> null
        }

    companion object {
        const val RELEASES_URL = "https://fedoraproject.org/releases.json"
        private const val SUPPORTED_RELEASE_COUNT = 2
        private const val ENTRIES_MAX = 10_000
        private val arches = mapOf("x86_64" to Arch.X86_64, "aarch64" to Arch.ARM64)
    }
}
