package dev.jacobhayes.bootleg.catalog

import dev.jacobhayes.bootleg.core.catalog.Catalog
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One distro as the UI sees it: the last good releases plus the latest fetch error, if any. */
data class DistroEntry(
    val distro: Distro,
    val releases: List<Release>,
    /** When [releases] were fetched; null if never fetched successfully. */
    val fetchedAtMs: Long?,
    /** Error from the most recent refresh; [releases] are then stale (from cache). */
    val error: String?,
)

data class CatalogState(
    val entries: List<DistroEntry> = emptyList(),
    val refreshing: Boolean = false,
    val lastRefreshAttemptMs: Long? = null,
)

/**
 * Runs the scrapers (bounded parallelism, each failing independently) and keeps the last good
 * result per distro as JSON in app storage. Nothing is bundled: with no cache and no network the
 * catalog is empty.
 */
class CatalogRepository(
    scrapers: List<Scraper>,
    http: Http,
    private val cacheFile: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class CachedDistro(
        val distro: Distro,
        val releases: List<Release>,
        val fetchedAtMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private val stateFlow = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = stateFlow.asStateFlow()

    val distros: List<Distro> = scrapers.flatMap { it.distros }

    /**
     * Core's aggregator: one request cap shared by all scrapers, per-request and per-scraper deadlines,
     * a per-scraper release cap, release validation and de-duplication. Rejects duplicate distro ids.
     */
    private val catalog = Catalog(scrapers, http)

    suspend fun loadCache() {
        val cached = withContext(Dispatchers.IO) { readCache() }
        stateFlow.update { state ->
            state.copy(
                entries =
                    distros.map { distro ->
                        val hit = cached[distro.id]
                        DistroEntry(distro, hit?.releases.orEmpty(), hit?.fetchedAtMs, error = null)
                    },
            )
        }
    }

    fun isStale(maxAgeMs: Long): Boolean {
        val entries = stateFlow.value.entries
        if (entries.isEmpty()) return distros.isNotEmpty()
        return entries.any { it.fetchedAtMs == null || nowMs() - it.fetchedAtMs > maxAgeMs }
    }

    suspend fun refresh() {
        if (!refreshMutex.tryLock()) return
        try {
            stateFlow.update { it.copy(refreshing = true, lastRefreshAttemptMs = nowMs()) }
            val result = withContext(Dispatchers.IO) { catalog.fetchAll() }
            val previous = stateFlow.value.entries.associateBy { it.distro.id }
            val releasesByDistro = result.releases.groupBy { it.distroId }
            val errorsByDistro =
                result.errors
                    .flatMap { error ->
                        error.distroIds.map { it to error.message }
                    }.groupBy({ it.first }, { it.second })
            val entries =
                distros.map { distro ->
                    val releases = releasesByDistro[distro.id].orEmpty()
                    val errors = errorsByDistro[distro.id].orEmpty()
                    if (releases.isEmpty() && errors.isNotEmpty()) {
                        // The scraper failed (or every release it returned was invalid): keep the last good result.
                        val old = previous[distro.id]
                        DistroEntry(distro, old?.releases.orEmpty(), old?.fetchedAtMs, errors.first().take(ERROR_LENGTH_MAX))
                    } else {
                        // Releases core rejected (bad URL, name or hash) are dropped; the valid ones still show.
                        DistroEntry(distro, releases, nowMs(), error = null)
                    }
                }
            stateFlow.update { it.copy(entries = entries) }
            withContext(Dispatchers.IO) { writeCache(entries) }
        } finally {
            stateFlow.update { it.copy(refreshing = false) }
            refreshMutex.unlock()
        }
    }

    private fun readCache(): Map<String, CachedDistro> {
        if (!cacheFile.exists()) return emptyMap()
        return try {
            json.decodeFromString<List<CachedDistro>>(cacheFile.readText()).associateBy { it.distro.id }
        } catch (error: Exception) {
            // A corrupt cache only costs a refetch.
            cacheFile.delete()
            emptyMap()
        }
    }

    private fun writeCache(entries: List<DistroEntry>) {
        val good =
            entries.mapNotNull { entry ->
                entry.fetchedAtMs?.let { CachedDistro(entry.distro, entry.releases, it) }
            }
        val temp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        temp.writeText(json.encodeToString(good))
        check(temp.renameTo(cacheFile)) { "could not replace $cacheFile" }
    }

    private companion object {
        const val ERROR_LENGTH_MAX = 200
    }
}
