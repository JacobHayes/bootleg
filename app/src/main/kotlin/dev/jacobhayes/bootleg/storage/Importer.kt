package dev.jacobhayes.bootleg.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.image.Sha256Streamer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A file the user picked with ACTION_OPEN_DOCUMENT. */
data class PickedFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    /** Real path usable in place (/data/media/0/...), or null if it must be imported. */
    val mediaPath: String?,
)

/** Long-running file work started from the Library: importing picked files and re-hashing images. */
class FileTasks(
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
    private val storeDir: File,
    private val onImageAdded: suspend () -> Unit,
) {
    data class Progress(
        val label: String,
        val doneBytes: Long,
        val totalBytes: Long?,
    )

    sealed interface Outcome {
        data class Success(
            val message: String,
        ) : Outcome

        data class Failure(
            val message: String,
        ) : Outcome
    }

    private val progressFlow = MutableStateFlow<Map<String, Progress>>(emptyMap())
    private val outcomeFlow = MutableStateFlow<Outcome?>(null)
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Keyed by file name (imports) or path (verification). */
    val progress: StateFlow<Map<String, Progress>> = progressFlow.asStateFlow()
    val lastOutcome: StateFlow<Outcome?> = outcomeFlow.asStateFlow()

    fun clearOutcome() {
        outcomeFlow.value = null
    }

    fun describe(uri: Uri): PickedFile {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                size = if (cursor.isNull(1)) null else cursor.getLong(1)
            }
        }
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val mediaPath = StoragePaths.mediaPathForDocument(uri.authority, documentId)
        return PickedFile(uri, name ?: uri.lastPathSegment ?: "image", size, mediaPath)
    }

    fun import(file: PickedFile) {
        val name = file.displayName
        if (!StoragePaths.isValidImageName(name)) {
            outcomeFlow.value = Outcome.Failure("Can't import a file named \"$name\"")
            return
        }
        val target = File(storeDir, name)
        if (target.exists()) {
            outcomeFlow.value = Outcome.Failure("$name is already in the library")
            return
        }
        start(name, "Importing $name", file.sizeBytes) { report ->
            check(storeDir.isDirectory || storeDir.mkdirs()) { "cannot create $storeDir" }
            val partial = File(storeDir, StoragePaths.importStagingName(name))
            try {
                val input = checkNotNull(resolver.openInputStream(file.uri)) { "cannot open $name" }
                input.use { source ->
                    partial.outputStream().use { sink ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            copied += read
                            report(copied)
                        }
                        sink.fd.sync()
                    }
                }
                StoragePaths.moveIntoPlace(partial, target)
            } finally {
                partial.delete()
            }
            onImageAdded()
            "Imported $name"
        }
    }

    /** Re-hashes [fusePath] and compares with [expectedSha256]. */
    fun verify(
        fusePath: String,
        sizeBytes: Long,
        expectedSha256: String,
    ) {
        require(ScraperSupport.isSha256(expectedSha256)) { "not a sha256: $expectedSha256" }
        val name = fusePath.substringAfterLast('/')
        start(fusePath, "Verifying $name", sizeBytes) { report ->
            val actual = File(fusePath).inputStream().use { input -> Sha256Streamer.digest(input) { report(it) }.hex }
            if (actual != expectedSha256) throw IllegalStateException("$name doesn't match its checksum")
            "$name matches its checksum"
        }
    }

    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
    }

    private fun start(
        key: String,
        label: String,
        totalBytes: Long?,
        work: suspend (report: (Long) -> Unit) -> String,
    ) {
        if (progressFlow.value.containsKey(key)) return
        progressFlow.update { it + (key to Progress(label, 0, totalBytes)) }
        val job =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                var lastReportMs = 0L
                try {
                    val message =
                        work { done ->
                            val now = System.currentTimeMillis()
                            if (now - lastReportMs >= PROGRESS_INTERVAL_MS) {
                                lastReportMs = now
                                progressFlow.update { it + (key to Progress(label, done, totalBytes)) }
                            }
                        }
                    outcomeFlow.value = Outcome.Success(message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    outcomeFlow.value = Outcome.Failure(error.message ?: "$label failed")
                } finally {
                    progressFlow.update { it - key }
                    jobs.remove(key)
                }
            }
        jobs[key] = job
        job.start()
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 1 shl 16
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
