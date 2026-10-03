package dev.jacobhayes.bootleg.catalog

import dev.jacobhayes.bootleg.core.catalog.Catalog
import dev.jacobhayes.bootleg.core.catalog.Distro
import dev.jacobhayes.bootleg.core.catalog.Feed
import dev.jacobhayes.bootleg.core.catalog.FeedResult
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** One distro as the UI (and the remote) sees it: the last good releases plus its feed's status. */
data class DistroEntry(
    val distro: Distro,
    /** The last good releases (possibly from the cache); empty if never fetched. */
    val releases: List<Release>,
    /** When [releases] were fetched; null if never fetched successfully. */
    val fetchedAtMs: Long?,
    /** Its feed is being fetched right now. */
    val loading: Boolean = false,
    /** The feed's latest attempt failed with this; [releases] are then from an earlier success. */
    val error: String? = null,
    /** When that attempt failed; set exactly when [error] is. */
    val failedAtMs: Long? = null,
) {
    init {
        require((error == null) == (failedAtMs == null)) { "error and failedAtMs go together" }
    }
}

data class CatalogState(
    /** Every distro, in display order (category, then name), whether or not it has loaded. */
    val entries: List<DistroEntry> = emptyList(),
    /** The on-device cache has been read into [entries]. */
    val cacheLoaded: Boolean = false,
    /** True while any feed is being fetched. */
    val refreshing: Boolean = false,
    /** The last fetch found no usable network: entries keep their saved results and aren't marked failed. */
    val offline: Boolean = false,
)

/**
 * The catalog with stale-while-revalidate: the saved result per distro shows at once, and each feed
 * (one per scraper, see [Catalog]) refreshes on its own in [scope], so leaving the screen doesn't
 * cancel it and one slow or failing feed only ever marks its own entries. The last good result per
 * feed is saved as JSON in app storage. Nothing is bundled: with no cache and no network every entry
 * shows "not loaded".
 *
 * API for the UI and the remote: [state] is the current snapshot; [refreshStale] (on open),
 * [refreshAll] (user asked) and [refresh] (one entry's feed) start fetches and return their job.
 */
class CatalogRepository(
    scrapers: List<Scraper>,
    http: Http,
    private val cacheFile: File,
    private val scope: CoroutineScope,
    /** False when the app has no usable network (none, or blocked for this app, e.g. in Doze). */
    private val networkAvailable: () -> Boolean = { true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class CachedDistro(
        val distro: Distro,
        val releases: List<Release>,
        val fetchedAtMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val catalog = Catalog(scrapers, http)
    val distros: List<Distro> = catalog.distros

    private val stateFlow = MutableStateFlow(CatalogState(entries = distros.map { DistroEntry(it, emptyList(), fetchedAtMs = null) }))
    val state: StateFlow<CatalogState> = stateFlow.asStateFlow()

    // Read at construction so the saved results are there before the screen asks for them.
    private val cacheLoad = scope.async(ioDispatcher) { loadCache() }
    private val cacheWriteMutex = Mutex()

    /** Latest fetch per feed id; a feed is never fetched twice at once. Guarded by itself. */
    private val feedJobs = HashMap<String, Job>()

    /** Waits until the cache has been read into [state]. */
    suspend fun awaitCache() = cacheLoad.await()

    /*
     * The refresh functions return a job that completes when their fetches have. Cancelling it only
     * stops waiting: fetches are shared (the screen, the remote) and run to completion in [scope].
     */

    /**
     * Fetches the feeds that are stale (older than [maxAgeMs] or never fetched) or whose last attempt
     * failed, except those that failed in the last [FAILED_RETRY_INTERVAL_MS]; also waits for fetches
     * already running.
     */
    fun refreshStale(maxAgeMs: Long = MAX_AGE_MS): Job = refreshStale(maxAgeMs, retryRecentFailures = false)

    private fun refreshStale(
        maxAgeMs: Long,
        retryRecentFailures: Boolean,
    ): Job =
        scope.launch {
            awaitCache()
            // Opening with nothing due still learns (and shows) that there's no network.
            stateFlow.update { it.copy(offline = !networkAvailable()) }
            val due = feedsToRefresh(stateFlow.value.entries, catalog.feeds, nowMs(), maxAgeMs, retryRecentFailures)
            (due.map { start(it) } + runningFetches()).joinAll()
        }

    private fun runningFetches(): List<Job> = synchronized(feedJobs) { feedJobs.values.filter { it.isActive } }

    /** Fetches every feed (the user asked). */
    fun refreshAll(): Job = scope.launch { catalog.feeds.map { start(it) }.joinAll() }

    /** Fetches the feed that fills [distroId] (a card's Retry). */
    fun refresh(distroId: String): Job {
        val feed = requireNotNull(catalog.feedOf(distroId)) { "unknown distro $distroId" }
        return scope.launch { start(feed).join() }
    }

    /**
     * The default network changed (gained or lost internet, got blocked or unblocked for the app).
     * Coming back online, it waits for fetches still running (they may yet fail for want of a
     * network) and then catches up on everything stale or failed, recent failures included.
     */
    fun onNetworkChanged() {
        val available = networkAvailable()
        val wasOffline = stateFlow.value.offline
        stateFlow.update { it.copy(offline = !available) }
        if (available && wasOffline) {
            scope.launch {
                runningFetches().joinAll()
                refreshStale(MAX_AGE_MS, retryRecentFailures = true).join()
            }
        }
    }

    /** Starts [feed]'s fetch, or returns the one running. Cancelling the result cancels the fetch for everyone. */
    internal fun start(feed: Feed): Job =
        synchronized(feedJobs) {
            val previous = feedJobs[feed.id]
            if (previous != null && previous.isActive) return previous
            // A cancelled fetch may still be cleaning up its entries: the next one starts after it has.
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    previous?.join()
                    fetchFeed(feed)
                }
            feedJobs[feed.id] = job
            job.invokeOnCompletion { synchronized(feedJobs) { if (feedJobs[feed.id] === job) feedJobs.remove(feed.id) } }
            job.start()
            job
        }

    private suspend fun fetchFeed(feed: Feed) {
        awaitCache()
        if (!networkAvailable()) {
            stateFlow.update { it.copy(offline = true) }
            return
        }
        val ids = feed.distros.map { it.id }.toSet()
        updateEntries(ids) { it.copy(loading = true) }
        try {
            when (val result = withContext(ioDispatcher) { catalog.fetch(feed.id) }) {
                is FeedResult.Fetched -> {
                    val fetchedAtMs = nowMs()
                    val byDistro = result.releases.groupBy { it.distroId }
                    stateFlow.update { it.copy(offline = false) }
                    updateEntries(ids) { entry ->
                        entry.copy(
                            releases = byDistro[entry.distro.id].orEmpty(),
                            fetchedAtMs = fetchedAtMs,
                            error = null,
                            failedAtMs = null,
                        )
                    }
                    writeCache()
                }
                is FeedResult.Failed ->
                    if (!networkAvailable()) {
                        // The network went away or got blocked for us: not this feed's fault.
                        stateFlow.update { it.copy(offline = true) }
                    } else {
                        val failedAtMs = nowMs()
                        updateEntries(ids) { it.copy(error = result.message, failedAtMs = failedAtMs) }
                    }
            }
        } finally {
            // Also on cancellation, which leaves the entry as it was: cancelled is not failed.
            updateEntries(ids) { it.copy(loading = false) }
        }
    }

    private fun updateEntries(
        ids: Set<String>,
        transform: (DistroEntry) -> DistroEntry,
    ) {
        stateFlow.update { state ->
            val entries = state.entries.map { if (it.distro.id in ids) transform(it) else it }
            state.copy(entries = entries, refreshing = entries.any { it.loading })
        }
    }

    private fun loadCache() {
        val cached = readCache()
        stateFlow.update { state ->
            state.copy(
                cacheLoaded = true,
                entries =
                    state.entries.map { entry ->
                        val hit = cached[entry.distro.id]
                        // A fetch that finished before the cache was read is newer than the cache.
                        if (hit == null ||
                            entry.fetchedAtMs != null
                        ) {
                            entry
                        } else {
                            entry.copy(releases = hit.releases, fetchedAtMs = hit.fetchedAtMs)
                        }
                    },
            )
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

    /** Saves the good results. A failed write keeps them in memory; the next success writes again. */
    private suspend fun writeCache() =
        cacheWriteMutex.withLock {
            withContext(ioDispatcher) {
                // Written from the latest state under the lock, so a slower writer never overwrites newer results.
                val good =
                    stateFlow.value.entries.mapNotNull { entry ->
                        entry.fetchedAtMs?.let { CachedDistro(entry.distro, entry.releases, it) }
                    }
                val temp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
                try {
                    temp.writeText(json.encodeToString(good))
                    if (!temp.renameTo(cacheFile)) throw IOException("could not replace $cacheFile")
                } catch (error: IOException) {
                    // Storage trouble must not take the app down: the cache is only a head start.
                    temp.delete()
                }
            }
        }

    companion object {
        /** A feed older than this refreshes when the catalog opens. */
        const val MAX_AGE_MS = 6L * 60 * 60 * 1000

        /** A feed whose last attempt failed is retried on open only after this long (the card's Retry is immediate). */
        const val FAILED_RETRY_INTERVAL_MS = 60_000L

        /** The feeds [CatalogRepository.refreshStale] starts: stale, never fetched or failed; not loading; not just failed. */
        fun feedsToRefresh(
            entries: List<DistroEntry>,
            feeds: List<Feed>,
            nowMs: Long,
            maxAgeMs: Long,
            /** Also retry feeds that failed within [FAILED_RETRY_INTERVAL_MS] (the network just came back). */
            retryRecentFailures: Boolean = false,
        ): List<Feed> {
            require(maxAgeMs >= 0) { "maxAgeMs must not be negative" }
            val byId = entries.associateBy { it.distro.id }
            return feeds.filter { feed ->
                val feedEntries = feed.distros.mapNotNull { byId[it.id] }
                val stale =
                    feedEntries.size < feed.distros.size ||
                        feedEntries.any { it.fetchedAtMs == null || nowMs - it.fetchedAtMs > maxAgeMs || it.error != null }
                val loading = feedEntries.any { it.loading }
                val justFailed =
                    !retryRecentFailures && feedEntries.any { it.failedAtMs != null && nowMs - it.failedAtMs < FAILED_RETRY_INTERVAL_MS }
                stale && !loading && !justFailed
            }
        }
    }
}
