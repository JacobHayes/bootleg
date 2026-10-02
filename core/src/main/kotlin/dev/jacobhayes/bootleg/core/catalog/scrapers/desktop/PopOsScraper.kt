package dev.jacobhayes.bootleg.core.catalog.scrapers.desktop

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.catalog.getTextOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Pop!_OS from System76's build API (`api.pop-os.org/builds/<version>/<channel>?arch=<arch>`, the
 * same endpoint pop.system76.com uses). The API has no version index, so it probes the Ubuntu LTS
 * versions Pop!_OS is based on (even years, `YY.04`) within the last [LTS_LOOKBACK_YEARS] years; a
 * 404 means no build.
 */
class PopOsScraper(
    private val today: () -> LocalDate = { LocalDate.now() },
) : Scraper {
    override val distros = listOf(Distro("pop-os", "Pop!_OS", Category.DESKTOP, "https://system76.com/pop/"))

    @Serializable
    private data class Build(
        val version: String,
        val url: String,
        val size: Long? = null,
        @SerialName("sha_sum") val sha256: String,
        val channel: String,
        val build: String,
    )

    private data class Probe(
        val version: String,
        val channel: String,
        val arch: Arch,
    )

    override suspend fun releases(http: Http): List<Release> {
        val year = today().year
        val versions = (year - LTS_LOOKBACK_YEARS..year).filter { it % 2 == 0 }.map { "%02d.04".format(it % 100) }
        val probes =
            versions.flatMap { version ->
                channels.keys.flatMap { channel -> archNames.values.map { arch -> Probe(version, channel, arch) } }
            }
        return ScraperSupport
            .fanOut(probes) { probe ->
                val archName = archNames.entries.first { it.value == probe.arch }.key
                val body = http.getTextOrNull("$API/builds/${probe.version}/${probe.channel}?arch=$archName") ?: return@fanOut null
                val build = ScraperSupport.json.decodeFromString(Build.serializer(), body)
                require(build.version == probe.version && build.channel == probe.channel) { "API answered $build for $probe" }
                Release(
                    distroId = "pop-os",
                    version = build.version,
                    arch = probe.arch,
                    variant = channels.getValue(probe.channel),
                    url = build.url,
                    sha256 = build.sha256.lowercase(),
                    sizeBytes = build.size,
                    fileName = ScraperSupport.fileNameOf(build.url),
                )
            }.filterNotNull()
    }

    companion object {
        const val API = "https://api.pop-os.org"
        private const val LTS_LOOKBACK_YEARS = 4
        private val channels = mapOf("intel" to "Intel/AMD", "nvidia" to "NVIDIA")
        private val archNames = mapOf("amd64" to Arch.X86_64, "arm64" to Arch.ARM64)
    }
}
