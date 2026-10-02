package dev.jacobhayes.bootleg.core.catalog.scrapers.server

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.catalog.getText
import dev.jacobhayes.bootleg.core.catalog.getTextOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * RHEL rebuilds that share the `<root>/<major>/isos/<arch>/CHECKSUM` layout (Rocky, AlmaLinux).
 * Supported majors are the bare-number directories in the root listing: both projects move
 * end-of-life majors to a separate vault host, so whatever is listed is supported.
 */
class EnterpriseLinuxScraper(
    private val distro: Distro,
    /** Directory listing root, ending in '/'. */
    private val rootUrl: String,
    /** ISO file name prefix, e.g. "Rocky" in `Rocky-9.8-x86_64-dvd.iso`. */
    private val filePrefix: String,
    private val sumsSigned: Boolean,
) : Scraper {
    init {
        require(rootUrl.startsWith("https://") && rootUrl.endsWith("/")) { "rootUrl must be https and end in '/': $rootUrl" }
        require(filePrefix.isNotBlank())
    }

    override val distros = listOf(distro)

    /**
     * Fetches every major/arch CHECKSUM concurrently, newest major first, so under the shared request
     * cap ([dev.jacobhayes.bootleg.core.catalog.Catalog]) the newest releases are fetched first. A
     * failing major/arch is dropped without affecting the others; only when nothing is left does the
     * scraper fail, with the first error.
     */
    override suspend fun releases(http: Http): List<Release> {
        val targets =
            parseMajorVersions(http.getText(rootUrl)).flatMap { major ->
                archNames.map { (arch, archName) -> Target(major, arch, archName) }
            }
        check(targets.size <= ServerScraperLimits.MAJOR_VERSIONS_MAX * archNames.size) { "too many CHECKSUM files: ${targets.size}" }
        val outcomes =
            coroutineScope {
                targets
                    .map { target ->
                        async {
                            try {
                                Result.success(releasesFor(http, target))
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (exception: Exception) {
                                Result.failure(
                                    IllegalStateException(
                                        "${distro.id} ${target.major} ${target.archName}: ${exception.message}",
                                        exception,
                                    ),
                                )
                            }
                        }
                    }.awaitAll()
            }
        val releases = outcomes.flatMap { it.getOrNull().orEmpty() }
        val firstFailure = outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }
        if (releases.isEmpty() && firstFailure != null) throw firstFailure
        return checkedReleases(releases)
    }

    private suspend fun releasesFor(
        http: Http,
        target: Target,
    ): List<Release> {
        val sumsUrl = "$rootUrl${target.major}/isos/${target.archName}/CHECKSUM"
        val sums = http.getTextOrNull(sumsUrl) ?: return emptyList()
        return parseEnterpriseLinuxChecksum(sums, filePrefix, target.major, target.archName).map { image ->
            Release(
                distroId = distro.id,
                version = image.version,
                arch = target.arch,
                variant = image.variant,
                url = "$rootUrl${target.major}/isos/${target.archName}/${image.fileName}",
                sha256 = image.sha256,
                sumsUrl = sumsUrl,
                sizeBytes = image.sizeBytes,
                sumsSigned = sumsSigned,
                fileName = image.fileName,
            )
        }
    }

    private data class Target(
        val major: Int,
        val arch: Arch,
        val archName: String,
    )

    private companion object {
        val archNames = listOf(Arch.X86_64 to "x86_64", Arch.ARM64 to "aarch64")
    }
}

internal data class EnterpriseLinuxImage(
    val version: String,
    val variant: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long?,
)

/** Bare-number subdirectories (`9/`, `10/`) of a release root listing, newest first. Point releases and betas are skipped. */
internal fun parseMajorVersions(html: String): List<Int> {
    val majors =
        hrefs(html)
            .mapNotNull {
                Regex("""^(?:\./)?(\d{1,3})/$""")
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            }.distinct()
            .sortedDescending()
    check(majors.size <= ServerScraperLimits.MAJOR_VERSIONS_MAX) {
        "more than ${ServerScraperLimits.MAJOR_VERSIONS_MAX} major versions listed: $majors"
    }
    return majors
}

/**
 * Picks the newest point release of each variant from a CHECKSUM file, ignoring the
 * `-latest-` aliases (same bytes, moving name). Sizes come from the `# file: N bytes` comments when present.
 */
internal fun parseEnterpriseLinuxChecksum(
    text: String,
    filePrefix: String,
    major: Int,
    archName: String,
): List<EnterpriseLinuxImage> {
    val sums = ScraperSupport.parseSha256Sums(text)
    val sizes =
        Regex("""^#\s*(\S+):\s*(\d+) bytes\s*$""", RegexOption.MULTILINE)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].toLong() }
    val imageName = Regex("""^${Regex.escape(filePrefix)}-($major\.\d+)-${Regex.escape(archName)}-(boot|dvd1?|minimal)\.iso$""")
    val images =
        sums.mapNotNull { (fileName, sha256) ->
            val match = imageName.matchEntire(fileName) ?: return@mapNotNull null
            val variant = variantNames.getValue(match.groupValues[2])
            EnterpriseLinuxImage(match.groupValues[1], variant, fileName, sha256, sizes[fileName])
        }
    return images
        .groupBy { it.variant }
        .map { (_, sameVariant) -> sameVariant.maxWith(compareBy(numericVersionOrder) { it.version }) }
        .sortedBy { it.variant }
}

private val variantNames = mapOf("boot" to "Boot", "dvd" to "DVD", "dvd1" to "DVD", "minimal" to "Minimal")

val rockyScraper =
    EnterpriseLinuxScraper(
        Distro("rocky", "Rocky Linux", Category.SERVER, "https://rockylinux.org"),
        rootUrl = "https://dl.rockylinux.org/pub/rocky/",
        filePrefix = "Rocky",
        // CHECKSUM.asc is published next to each CHECKSUM.
        sumsSigned = true,
    )

val almaLinuxScraper =
    EnterpriseLinuxScraper(
        Distro("almalinux", "AlmaLinux", Category.SERVER, "https://almalinux.org"),
        rootUrl = "https://repo.almalinux.org/almalinux/",
        filePrefix = "AlmaLinux",
        // CHECKSUM is clearsigned.
        sumsSigned = true,
    )
