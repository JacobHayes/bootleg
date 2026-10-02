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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

/**
 * Manjaro's official editions (KDE Plasma, GNOME, Xfce; full and minimal) from the iso-info JSON that
 * manjaro.org's download page is built from. Each ISO has a `.sha256` file next to it. x86_64 only:
 * Manjaro ARM ships per-board `.img.xz` images, not bootable ISOs.
 */
class ManjaroScraper : Scraper {
    override val distros = listOf(Distro("manjaro", "Manjaro", Category.DESKTOP, "https://manjaro.org"))

    @Serializable
    private data class Image(
        val image: String? = null,
        val checksum: String? = null,
        val minimal: Image? = null,
    )

    override suspend fun releases(http: Http): List<Release> {
        val sections =
            ScraperSupport.json.decodeFromString(
                MapSerializer(String.serializer(), JsonElement.serializer()),
                http.getText(FILE_INFO_URL),
            )
        val official = requireNotNull(sections["official"]) { "no official section in $FILE_INFO_URL" }
        val editions = ScraperSupport.json.decodeFromJsonElement(MapSerializer(String.serializer(), Image.serializer()), official)
        return editions.flatMap { (edition, info) ->
            val name = editionNames[edition] ?: return@flatMap emptyList()
            listOfNotNull(release(info, name), info.minimal?.let { release(it, "$name Minimal") })
        }
    }

    private fun release(
        info: Image,
        variant: String,
    ): Release? {
        val url = info.image ?: return null
        val checksum = requireNotNull(info.checksum) { "no checksum for $url" }
        val version = versionRegex.find(url)?.groupValues?.get(1) ?: throw IllegalArgumentException("no version in $url")
        return Release(
            distroId = "manjaro",
            version = version,
            arch = Arch.X86_64,
            variant = variant,
            url = url,
            sumsUrl = checksum,
            fileName = ScraperSupport.fileNameOf(url),
        )
    }

    companion object {
        const val FILE_INFO_URL = "https://gitlab.manjaro.org/web/iso-info/-/raw/master/file-info.json"
        private val versionRegex = Regex("""^https://download\.manjaro\.org/[a-z0-9]+/(\d+(?:\.\d+)*)/""")
        private val editionNames = mapOf("plasma" to "KDE Plasma", "gnome" to "GNOME", "xfce" to "Xfce")
    }
}
