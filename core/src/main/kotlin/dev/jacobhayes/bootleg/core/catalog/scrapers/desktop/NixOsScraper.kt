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

/**
 * NixOS graphical and minimal ISOs for the maintained stable channels (see [maintainedStatuses]). Channel status comes from the
 * Prometheus API behind status.nixos.org. Each channel's `latest-*.iso.sha256` names the concrete
 * build, whose immutable URL on releases.nixos.org is used so the hash always matches the file.
 */
class NixOsScraper : Scraper {
    override val distros = listOf(Distro("nixos", "NixOS", Category.DESKTOP, "https://nixos.org"))

    @Serializable
    private data class Query(
        val status: String,
        val data: Data,
    )

    @Serializable
    private data class Data(
        val result: List<Result>,
    )

    @Serializable
    private data class Result(
        val metric: Map<String, String>,
    )

    private data class Image(
        val channel: String,
        val kind: String,
        val arch: Arch,
    )

    override suspend fun releases(http: Http): List<Release> {
        val query = ScraperSupport.json.decodeFromString(Query.serializer(), http.getText(STATUS_URL))
        require(query.status == "success") { "channel status query failed: ${query.status}" }
        val channels =
            query.data.result
                .map { it.metric }
                .filter { (it["status"] in maintainedStatuses) && stableChannelRegex.matches(it["channel"].orEmpty()) }
                .map { it.getValue("channel") }
                .distinct()
        val images = channels.flatMap { channel -> kinds.keys.flatMap { kind -> arches.keys.map { arch -> Image(channel, kind, arch) } } }
        return ScraperSupport.fanOut(images) { image ->
            val archName = arches.getValue(image.arch)
            val sumsText = http.getText("$CHANNELS/${image.channel}/latest-nixos-${image.kind}-$archName-linux.iso.sha256")
            val (fileName, hash) =
                ScraperSupport.parseSha256Sums(sumsText).entries.singleOrNull()
                    ?: throw IllegalArgumentException("expected one entry in ${image.channel} ${image.kind} $archName sums")
            val match =
                Regex("""^nixos-${image.kind}-((\d{2}\.\d{2})\.[0-9a-z.]+)-$archName-linux\.iso$""").matchEntire(fileName)
                    ?: throw IllegalArgumentException("unexpected NixOS image name $fileName")
            val (build, release) = match.destructured
            require(image.channel == "nixos-$release") { "$fileName does not belong to ${image.channel}" }
            val url = "$RELEASES/nixos/$release/nixos-$build/$fileName"
            Release(
                distroId = "nixos",
                version = release,
                arch = image.arch,
                variant = kinds.getValue(image.kind),
                url = url,
                sha256 = hash,
                sumsUrl = "$url.sha256",
                fileName = fileName,
            )
        }
    }

    companion object {
        const val STATUS_URL = "https://prometheus.nixos.org/api/v1/query?query=channel_revision"
        const val CHANNELS = "https://channels.nixos.org"
        const val RELEASES = "https://releases.nixos.org"
        private val stableChannelRegex = Regex("""^nixos-\d{2}\.\d{2}$""")

        /**
         * "stable" is the current release. "deprecated" is the previous one during the roughly one-month
         * overlap after a new release, while it still gets security fixes, so it is offered too. Anything
         * else ("unmaintained", "rolling" unstable, beta) is skipped.
         */
        private val maintainedStatuses = setOf("stable", "deprecated")
        private val kinds = mapOf("graphical" to "Graphical", "minimal" to "Minimal")
        private val arches = mapOf(Arch.X86_64 to "x86_64", Arch.ARM64 to "aarch64")
    }
}
