package dev.jacobhayes.bootleg.core.catalog

import kotlinx.serialization.Serializable

object CatalogLimits {
    const val FETCH_CONCURRENCY_MAX = 4
    const val REQUEST_TIMEOUT_MS = 20_000L
    const val RESPONSE_BYTES_MAX = 8L * 1024 * 1024
}

@Serializable
enum class Arch { X86_64, ARM64 }

@Serializable
enum class Category { DESKTOP, SERVER, RESCUE }

@Serializable
data class Distro(
    /** Stable id, e.g. "ubuntu", "ubuntu-server", "kubuntu", "debian-netinst". */
    val id: String,
    val name: String,
    val category: Category,
    val homepage: String,
)

@Serializable
data class Release(
    val distroId: String,
    /** Human version, e.g. "26.04 LTS", "43", "2026.10.01". */
    val version: String,
    val arch: Arch,
    /** Optional edition/variant, e.g. "KDE", "netinst", "live". Empty if none. */
    val variant: String = "",
    val url: String,
    /** Hex sha256 if known up front; otherwise [sumsUrl] must be set. */
    val sha256: String? = null,
    /** URL of a SHA256SUMS-style file containing this image's hash. */
    val sumsUrl: String? = null,
    val sizeBytes: Long? = null,
    /** ISO-8601 date when support ends, if published. */
    val supportedUntil: String? = null,
    /** True if the distro publishes a signature for the sums (not verified in v1). */
    val sumsSigned: Boolean = false,
    /** File name to store as, e.g. "ubuntu-26.04-desktop-amd64.iso". */
    val fileName: String,
)

data class HttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
    /** Final URL after redirects. */
    val url: String,
)

/**
 * Network seam. Real impl uses OkHttp (app) / java.net.http (tests); fixtures replay recordings.
 *
 * Size contract: an implementation must bound the body while reading it, not after. It reads at
 * most [CatalogLimits.RESPONSE_BYTES_MAX] + 1 bytes of the raw body and throws [java.io.IOException]
 * if that extra byte arrives, so an oversized or endless response never gets materialized. The
 * limit is in bytes on the wire (after content decoding), not in decoded characters.
 */
interface Http {
    suspend fun get(url: String): HttpResponse

    suspend fun head(url: String): HttpResponse
}

/** One scraper per distro (or family). Must not throw for "no releases"; return empty instead. */
interface Scraper {
    val distros: List<Distro>

    suspend fun releases(http: Http): List<Release>
}
