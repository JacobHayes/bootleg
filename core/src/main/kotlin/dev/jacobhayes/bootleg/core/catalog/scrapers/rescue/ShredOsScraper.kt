package dev.jacobhayes.bootleg.core.catalog.scrapers.rescue

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.checkedReleases
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.isSha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * ShredOS x86-64 USB images from the latest GitHub release (one unauthenticated API call; the
 * limit is 60/h per IP). Upstream publishes only SHA-1 files, so the sha256 is GitHub's asset
 * `digest`, which GitHub computes on upload.
 */
class ShredOsScraper(
    private val latestReleaseApiUrl: String = "https://api.github.com/repos/PartialVolume/shredos.x86_64/releases/latest",
) : Scraper {
    override val distros = listOf(Distro("shredos", "ShredOS", Category.RESCUE, "https://github.com/PartialVolume/shredos.x86_64"))

    override suspend fun releases(http: Http): List<Release> {
        val response = http.get(latestReleaseApiUrl)
        if (response.status == 404) return emptyList()
        check(response.status != 403 && response.status != 429) {
            "shredos: GitHub API rate limit hit (HTTP ${response.status}, 60 unauthenticated requests/hour); try again later"
        }
        check(response.status in 200..299) { "GET $latestReleaseApiUrl: HTTP ${response.status}" }
        return checkedReleases(
            parseShredOsRelease(response.body).map { image ->
                Release(
                    distroId = "shredos",
                    version = image.version,
                    arch = Arch.X86_64,
                    variant = image.variant,
                    url = image.url,
                    sha256 = image.sha256,
                    sizeBytes = image.sizeBytes,
                    fileName = image.fileName,
                )
            },
        )
    }
}

internal data class ShredOsImage(
    val version: String,
    val variant: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long?,
)

private val json = Json { ignoreUnknownKeys = true }

/** x86-64 `.img` assets of a GitHub release JSON object; assets without a sha256 digest are skipped. */
internal fun parseShredOsRelease(body: String): List<ShredOsImage> {
    val release = json.parseToJsonElement(body) as? JsonObject ?: error("shredos: release is not a JSON object")
    if (release["draft"]?.jsonPrimitive?.booleanOrNull == true ||
        release["prerelease"]?.jsonPrimitive?.booleanOrNull == true
    ) {
        return emptyList()
    }
    val assets = checkNotNull(release["assets"]) { "shredos: release has no assets field" }.jsonArray
    val imageName = Regex("""^shredos-[\w.]+_x86-64_v(\d+(?:\.\d+)+)_\d{8}(_lite)?\.img$""")
    return assets.mapNotNull { element ->
        val asset = element.jsonObject
        val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val match = imageName.matchEntire(name) ?: return@mapNotNull null
        val url = checkNotNull(asset["browser_download_url"]?.jsonPrimitive?.contentOrNull) { "shredos: asset $name has no download URL" }
        val digest = asset["digest"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val sha256 = digest.removePrefix("sha256:").takeIf { digest.startsWith("sha256:") && isSha256(it) } ?: return@mapNotNull null
        ShredOsImage(
            version = match.groupValues[1],
            variant = if (match.groupValues[2].isEmpty()) "" else "Lite",
            fileName = name,
            url = url,
            sha256 = sha256.lowercase(),
            sizeBytes = asset["size"]?.jsonPrimitive?.longOrNull,
        )
    }
}
