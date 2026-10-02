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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

private const val OPENSUSE_DOWNLOAD = "https://download.opensuse.org"

/** A concrete image resolved through MirrorCache's metalink: versioned URL, size and SHA-256. */
data class OpenSuseImage(
    val url: String,
    val sizeBytes: Long?,
    val sha256: String,
)

/**
 * Stable names like `...-Current.iso` are redirects to versioned files, and Leap 16 publishes only
 * SHA-512 files. download.opensuse.org's metalink (`<name>.meta4`) names the versioned file and
 * gives its SHA-256 and size, so both Leap and Tumbleweed resolve the same way.
 */
internal object OpenSuseMetalink {
    private const val METALINK_BYTES_MAX = 1_000_000
    private val originRegex = Regex("""<origin[^>]*>\s*(https://[^<\s]+)\s*</origin>""")
    private val sha256Regex = Regex("""<hash type="sha-256">\s*([0-9a-fA-F]{64})\s*</hash>""")
    private val sizeRegex = Regex("""<size>\s*(\d+)\s*</size>""")

    fun parse(
        metalink: String,
        requestedUrl: String,
    ): OpenSuseImage {
        require(metalink.length <= METALINK_BYTES_MAX) { "metalink too large for $requestedUrl" }
        require("<metalink" in metalink) { "not a metalink for $requestedUrl: ${metalink.take(80)}" }
        val origin = originRegex.find(metalink)?.groupValues?.get(1) ?: requestedUrl
        val sha256 =
            sha256Regex.find(metalink)?.groupValues?.get(1) ?: throw IllegalArgumentException("no sha-256 in metalink for $requestedUrl")
        return OpenSuseImage(
            origin,
            sizeRegex
                .find(metalink)
                ?.groupValues
                ?.get(1)
                ?.toLong(),
            sha256.lowercase(),
        )
    }

    suspend fun resolve(
        http: Http,
        imageUrl: String,
    ): OpenSuseImage = parse(http.getText("$imageUrl.meta4"), imageUrl)
}

/**
 * openSUSE Leap. Versions come from get.opensuse.org's distributions API (state "Stable"); Leap 16
 * ships offline installer ISOs, Leap 15 DVD and NET ISOs, each for x86_64 and aarch64.
 */
class OpenSuseLeapScraper : Scraper {
    override val distros = listOf(Distro("opensuse-leap", "openSUSE Leap", Category.DESKTOP, "https://get.opensuse.org/leap/"))

    @Serializable
    private data class Version(
        val version: String,
        val state: String = "",
    )

    private data class Directory(
        val version: String,
        val url: String,
        val imageRegex: Regex,
    )

    override suspend fun releases(http: Http): List<Release> {
        val body = http.getText(DISTRIBUTIONS_URL)
        val api = ScraperSupport.json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(Version.serializer())), body)
        val leap = requireNotNull(api["Leap"]) { "no Leap entry in $DISTRIBUTIONS_URL" }
        val directories =
            leap.filter { it.state == "Stable" }.map { (version, _) ->
                val major = version.substringBefore('.').toInt()
                val escaped = Regex.escape(version)
                if (major >= 16) {
                    Directory(
                        version,
                        "$OPENSUSE_DOWNLOAD/distribution/leap/$version/offline/",
                        Regex("""^Leap-$escaped-(offline)-installer-(x86_64|aarch64)\.install\.iso$"""),
                    )
                } else {
                    Directory(
                        version,
                        "$OPENSUSE_DOWNLOAD/distribution/leap/$version/iso/",
                        Regex("""^openSUSE-Leap-$escaped-(DVD|NET)-(x86_64|aarch64)-Media\.iso$"""),
                    )
                }
            }
        val images =
            ScraperSupport
                .fanOut(directories) { directory ->
                    ScraperSupport.listingNames(http.getText(directory.url), directory.url).mapNotNull { name ->
                        val (kind, arch) = directory.imageRegex.matchEntire(name)?.destructured ?: return@mapNotNull null
                        Triple(directory, directory.url + name, kind to arch)
                    }
                }.flatten()
        return ScraperSupport.fanOut(images) { (directory, url, kindAndArch) ->
            val image = OpenSuseMetalink.resolve(http, url)
            Release(
                distroId = "opensuse-leap",
                version = directory.version,
                arch = if (kindAndArch.second == "x86_64") Arch.X86_64 else Arch.ARM64,
                variant = leapVariants.getValue(kindAndArch.first),
                url = image.url,
                sha256 = image.sha256,
                sizeBytes = image.sizeBytes,
                fileName = ScraperSupport.fileNameOf(image.url),
            )
        }
    }

    companion object {
        const val DISTRIBUTIONS_URL = "https://get.opensuse.org/api/v0/distributions.json"
        private val leapVariants = mapOf("offline" to "Offline installer", "DVD" to "DVD", "NET" to "Network installer")
    }
}

/** openSUSE Tumbleweed (rolling): the current snapshot's DVD, NET and live ISOs for x86_64 and aarch64. */
class OpenSuseTumbleweedScraper : Scraper {
    override val distros =
        listOf(Distro("opensuse-tumbleweed", "openSUSE Tumbleweed", Category.DESKTOP, "https://get.opensuse.org/tumbleweed/"))

    override suspend fun releases(http: Http): List<Release> {
        val directories =
            mapOf(
                "$OPENSUSE_DOWNLOAD/tumbleweed/iso/" to "x86_64",
                "$OPENSUSE_DOWNLOAD/ports/aarch64/tumbleweed/iso/" to "aarch64",
            )
        val images =
            ScraperSupport
                .fanOut(directories.toList()) { (directoryUrl, arch) ->
                    val imageRegex = Regex("""^openSUSE-Tumbleweed-(DVD|NET|GNOME-Live|KDE-Live|XFCE-Live)-$arch-Current\.iso$""")
                    ScraperSupport.listingNames(http.getText(directoryUrl), directoryUrl).mapNotNull { name ->
                        val kind = imageRegex.matchEntire(name)?.groupValues?.get(1) ?: return@mapNotNull null
                        Triple(directoryUrl + name, kind, arch)
                    }
                }.flatten()
        return ScraperSupport.fanOut(images) { (url, kind, arch) ->
            val image = OpenSuseMetalink.resolve(http, url)
            val fileName = ScraperSupport.fileNameOf(image.url)
            val snapshot = snapshotRegex.find(fileName)?.groupValues?.get(1) ?: throw IllegalArgumentException("no snapshot in $fileName")
            Release(
                distroId = "opensuse-tumbleweed",
                version = snapshot,
                arch = if (arch == "x86_64") Arch.X86_64 else Arch.ARM64,
                variant = tumbleweedVariants.getValue(kind),
                url = image.url,
                sha256 = image.sha256,
                sizeBytes = image.sizeBytes,
                fileName = fileName,
            )
        }
    }

    companion object {
        private val snapshotRegex = Regex("""-Snapshot(\d{8})-""")
        private val tumbleweedVariants =
            mapOf(
                "DVD" to "DVD",
                "NET" to "Network installer",
                "GNOME-Live" to "GNOME Live",
                "KDE-Live" to "KDE Live",
                "XFCE-Live" to "Xfce Live",
            )
    }
}
