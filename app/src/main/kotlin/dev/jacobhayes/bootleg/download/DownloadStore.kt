package dev.jacobhayes.bootleg.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Download records, in memory plus a JSON file. Byte progress is kept in memory only (the
 * `.partial` length is the source of truth on restart); everything else is written through.
 */
class DownloadStore(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val recordsFlow = MutableStateFlow(load())
    val records: StateFlow<Map<String, DownloadRecord>> = recordsFlow.asStateFlow()

    operator fun get(fileName: String): DownloadRecord? = recordsFlow.value[fileName]

    /** Stores [record], giving it a free id slot unless it keeps its own; returns what was stored. */
    fun put(record: DownloadRecord): DownloadRecord =
        synchronized(lock) {
            val others = recordsFlow.value - record.fileName
            check(others.size < DownloadLimits.RECORDS_MAX) { "too many downloads (max ${DownloadLimits.RECORDS_MAX})" }
            val stored = withIdSlot(record, others.values.map { it.idSlot }.toSet())
            val next = others + (record.fileName to stored)
            recordsFlow.value = next
            persist(next)
            stored
        }

    /** Applies [change] to an existing record; returns the new record or null if it was removed. */
    fun update(
        fileName: String,
        persist: Boolean = true,
        change: (DownloadRecord) -> DownloadRecord,
    ): DownloadRecord? =
        synchronized(lock) {
            val current = recordsFlow.value[fileName] ?: return null
            val next = change(current)
            check(next.fileName == fileName) { "record key changed: $fileName -> ${next.fileName}" }
            check(next.idSlot == current.idSlot) { "id slot changed for $fileName" }
            val all = recordsFlow.value + (fileName to next)
            recordsFlow.value = all
            if (persist) persist(all)
            next
        }

    fun remove(fileName: String) {
        synchronized(lock) {
            val next = recordsFlow.value - fileName
            recordsFlow.value = next
            persist(next)
        }
    }

    /**
     * Forgets completed downloads whose image is gone from the library (deleted there or by hand), so
     * the Catalog offers them again. [isPresent] answers for one file name; active, paused and failed
     * records stay. Returns the forgotten names.
     */
    fun forgetMissingImages(isPresent: (fileName: String) -> Boolean): Set<String> =
        synchronized(lock) {
            val missing =
                recordsFlow.value.values
                    .filter { it.status == DownloadStatus.COMPLETED && !isPresent(it.fileName) }
                    .map { it.fileName }
                    .toSet()
            if (missing.isNotEmpty()) {
                val next = recordsFlow.value - missing
                recordsFlow.value = next
                persist(next)
            }
            missing
        }

    private fun load(): Map<String, DownloadRecord> {
        if (!file.exists()) return emptyMap()
        val records =
            try {
                json.decodeFromString<List<DownloadRecord>>(file.readText())
            } catch (error: Exception) {
                file.delete()
                emptyList()
            }
        // A process death mid-run leaves RUNNING/VERIFYING behind; those runs are gone, so pause them.
        val used = mutableSetOf<Int>()
        return records.take(DownloadLimits.RECORDS_MAX).associate { loaded ->
            val record = withIdSlot(loaded, used).also { used += it.idSlot }
            val recovered =
                if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.VERIFYING) {
                    record.copy(status = DownloadStatus.PAUSED)
                } else {
                    record
                }
            record.fileName to recovered
        }
    }

    private fun withIdSlot(
        record: DownloadRecord,
        used: Set<Int>,
    ): DownloadRecord {
        if (record.idSlot in 0 until DownloadLimits.RECORDS_MAX && record.idSlot !in used) return record
        val free = (0 until DownloadLimits.RECORDS_MAX).first { it !in used }
        return record.copy(idSlot = free)
    }

    private fun persist(records: Map<String, DownloadRecord>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(records.values.sortedBy { it.createdAtMs }))
        check(temp.renameTo(file)) { "could not replace $file" }
    }
}
