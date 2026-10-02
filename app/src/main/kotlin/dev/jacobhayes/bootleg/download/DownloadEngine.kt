package dev.jacobhayes.bootleg.download

import android.system.ErrnoException
import android.system.OsConstants
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.storage.StoragePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Transfers one download: resolve the expected hash, fetch with Range/If-Range resume into
 * `<name>.partial`, hash the whole file, atomically rename, and write a `<name>.sha256` sidecar.
 */
class DownloadEngine(
    private val store: DownloadStore,
    private val storeDir: File,
    baseClient: OkHttpClient,
    private val sumsHttp: Http,
    private val freeBytes: (File) -> Long,
    private val onImageAdded: suspend () -> Unit,
) {
    enum class Outcome { DONE, RESCHEDULE }

    private val client =
        baseClient
            .newBuilder()
            .connectTimeout(DownloadLimits.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DownloadLimits.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    private val permits = Semaphore(DownloadLimits.CONCURRENT_DOWNLOADS_MAX)

    private class StopException(
        val reason: String,
        val retryable: Boolean,
    ) : Exception(reason)

    fun partialFile(fileName: String) = File(storeDir, fileName + StoragePaths.PARTIAL_SUFFIX)

    fun finalFile(fileName: String) = File(storeDir, fileName)

    private fun sidecarFile(fileName: String) = File(storeDir, fileName + StoragePaths.SIDECAR_SUFFIX)

    suspend fun run(
        fileName: String,
        onProgress: (DownloadRecord) -> Unit,
    ): Outcome =
        permits.withPermit {
            withContext(Dispatchers.IO) {
                val initial = store[fileName] ?: return@withContext Outcome.DONE
                if (!initial.isActive) return@withContext Outcome.DONE
                try {
                    transfer(fileName, onProgress)
                    Outcome.DONE
                } catch (cancelled: CancellationException) {
                    // Cancelled by the user (record removed): the partial must not outlive the record.
                    if (store[fileName] == null) partialFile(fileName).delete()
                    // Whoever stopped us (pause, cancel, JobScheduler) may already have set the status.
                    store.update(fileName) {
                        if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.VERIFYING) {
                            it.copy(status = DownloadStatus.PAUSED)
                        } else {
                            it
                        }
                    }
                    throw cancelled
                } catch (stop: StopException) {
                    fail(fileName, stop.reason, stop.retryable)
                } catch (error: IOException) {
                    if (isOutOfSpace(error)) {
                        fail(fileName, "Out of storage space", retryable = false)
                    } else {
                        fail(fileName, "Network error: ${error.message ?: error::class.simpleName}", retryable = true)
                    }
                } catch (error: Exception) {
                    // Broken invariants (rename/delete failures, bad records) fail this download only.
                    fail(fileName, error.message ?: error::class.simpleName ?: "Unexpected error", retryable = false)
                }
            }
        }

    private fun fail(
        fileName: String,
        reason: String,
        retryable: Boolean,
    ): Outcome {
        val record = store[fileName] ?: return Outcome.DONE
        if (retryable && record.rescheduleCount < DownloadLimits.RESCHEDULES_MAX) {
            store.update(fileName) {
                it.copy(status = DownloadStatus.QUEUED, error = "$reason — retrying", rescheduleCount = it.rescheduleCount + 1)
            }
            return Outcome.RESCHEDULE
        }
        store.update(fileName) { it.copy(status = DownloadStatus.FAILED, error = reason) }
        return Outcome.DONE
    }

    private suspend fun transfer(
        fileName: String,
        onProgress: (DownloadRecord) -> Unit,
    ) {
        if (!(storeDir.isDirectory || storeDir.mkdirs()) || !storeDir.canWrite()) {
            throw StopException("No storage access to $storeDir (All files access not granted)", retryable = false)
        }
        val partial = partialFile(fileName)
        store.update(fileName) { it.copy(status = DownloadStatus.RUNNING, error = null, downloadedBytes = partial.length()) }
        resolveExpectedHash(fileName)

        repeat(DownloadLimits.ATTEMPTS_PER_RUN_MAX) {
            currentCoroutineContext().ensureActive()
            when (fetchOnce(fileName, partial, onProgress)) {
                Step.FETCHED_FRESH -> {
                    // A clean full download that doesn't match the published hash won't get better by retrying.
                    if (verifyAndFinish(fileName, partial, onProgress)) return
                    throw StopException("Checksum mismatch", retryable = false)
                }

                Step.FETCHED_RESUMED, Step.REVERIFY -> {
                    // A resumed file may mix two versions of the image; on mismatch, start over from zero.
                    if (verifyAndFinish(fileName, partial, onProgress)) return
                }

                Step.RETRY_FROM_ZERO -> {
                    check(!partial.exists() || partial.delete()) { "cannot delete $partial" }
                }
            }
        }
        throw StopException("Gave up after ${DownloadLimits.ATTEMPTS_PER_RUN_MAX} attempts", retryable = true)
    }

    private enum class Step { FETCHED_FRESH, FETCHED_RESUMED, REVERIFY, RETRY_FROM_ZERO }

    private suspend fun fetchOnce(
        fileName: String,
        partial: File,
        onProgress: (DownloadRecord) -> Unit,
    ): Step {
        val record = checkNotNull(store[fileName]) { "record vanished: $fileName" }
        val offset = partial.length()
        val request =
            Request
                .Builder()
                .url(record.url)
                .apply {
                    ResumePlanner
                        .requestHeaders(offset, ResumePlanner.Validator(record.etag, record.lastModified))
                        .forEach { (name, value) -> header(name, value) }
                }.build()
        val call = client.newCall(request)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            return call.execute().use { response ->
                val action =
                    ResumePlanner.decide(
                        requestedOffsetBytes = offset,
                        status = response.code,
                        contentRange = response.header("Content-Range"),
                        contentLengthBytes = response.body.contentLength().takeIf { it >= 0 },
                    )
                when (action) {
                    is ResumePlanner.Action.Append -> {
                        writeBody(fileName, response, partial, append = true, totalBytes = action.totalBytes, onProgress)
                        Step.FETCHED_RESUMED
                    }

                    is ResumePlanner.Action.Restart -> {
                        store.update(fileName) {
                            it.copy(etag = response.header("ETag"), lastModified = response.header("Last-Modified"))
                        }
                        writeBody(fileName, response, partial, append = false, totalBytes = action.totalBytes, onProgress)
                        Step.FETCHED_FRESH
                    }

                    is ResumePlanner.Action.RetryFromZero -> Step.RETRY_FROM_ZERO

                    ResumePlanner.Action.Reverify -> Step.REVERIFY

                    is ResumePlanner.Action.Fail -> throw StopException(action.reason, action.retryable)
                }
            }
        } finally {
            cancelHandle.dispose()
        }
    }

    private suspend fun writeBody(
        fileName: String,
        response: Response,
        partial: File,
        append: Boolean,
        totalBytes: Long?,
        onProgress: (DownloadRecord) -> Unit,
    ) {
        val startBytes = if (append) partial.length() else 0L
        if (totalBytes != null) {
            val needed = totalBytes - startBytes + DownloadLimits.FREE_SPACE_MARGIN_BYTES
            val available = freeBytes(storeDir)
            if (available < needed) {
                throw StopException("Not enough space: need ${needed / MIB} MiB, ${available / MIB} MiB free", retryable = false)
            }
        }
        var written = startBytes
        store.update(fileName) { it.copy(totalBytes = totalBytes ?: it.totalBytes, downloadedBytes = written) }
        var lastReportMs = 0L
        val job = currentCoroutineContext().job
        FileOutputStream(partial, append).use { output ->
            response.body.byteStream().use { input ->
                val buffer = ByteArray(DownloadLimits.BUFFER_BYTES)
                while (true) {
                    if (!job.isActive || store[fileName]?.status != DownloadStatus.RUNNING) {
                        throw CancellationException("download stopped")
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (totalBytes != null && written > totalBytes) throw IOException("server sent more than $totalBytes bytes")
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastReportMs >= DownloadLimits.PROGRESS_INTERVAL_MS) {
                        lastReportMs = nowMs
                        store.update(fileName, persist = false) { it.copy(downloadedBytes = written) }?.let(onProgress)
                    }
                }
            }
            output.fd.sync()
        }
        store.update(fileName) { it.copy(downloadedBytes = written, totalBytes = totalBytes ?: written) }?.let(onProgress)
        if (totalBytes != null && written < totalBytes) throw IOException("connection closed at $written of $totalBytes bytes")
    }

    private suspend fun resolveExpectedHash(fileName: String) {
        val record = store[fileName] ?: return
        if (record.expectedSha256 != null || record.sumsUrl == null) return
        val hash =
            try {
                val response = sumsHttp.get(record.sumsUrl)
                if (response.status != 200) null else Checksums.find(response.body, listOf(remoteName(record.url), record.fileName))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null
            }
        if (hash != null) store.update(fileName) { it.copy(expectedSha256 = hash) }
    }

    /** Returns true when the download is finished (verified or unverifiable), false on mismatch. */
    private suspend fun verifyAndFinish(
        fileName: String,
        partial: File,
        onProgress: (DownloadRecord) -> Unit,
    ): Boolean {
        resolveExpectedHash(fileName)
        store.update(fileName) { it.copy(status = DownloadStatus.VERIFYING, downloadedBytes = partial.length()) }?.let(onProgress)
        val job = currentCoroutineContext().job
        val actual =
            Checksums.sha256(partial) {
                if (!job.isActive || store[fileName]?.status != DownloadStatus.VERIFYING) {
                    throw CancellationException("verify stopped")
                }
            }
        val expected = store[fileName]?.expectedSha256
        if (expected != null && expected != actual) {
            check(partial.delete()) { "cannot delete $partial" }
            store.update(fileName) { it.copy(verify = VerifyState.MISMATCH, downloadedBytes = 0) }
            return false
        }
        val target = finalFile(fileName)
        check(partial.renameTo(target)) { "cannot rename $partial to $target" }
        if (expected != null) writeSidecar(fileName, expected)
        val verify = if (expected != null) VerifyState.VERIFIED else VerifyState.UNAVAILABLE
        store.update(fileName) { it.copy(status = DownloadStatus.COMPLETED, verify = verify, error = null) }?.let(onProgress)
        onImageAdded()
        return true
    }

    private fun writeSidecar(
        fileName: String,
        sha256: String,
    ) {
        val sidecar = sidecarFile(fileName)
        val temp = File(storeDir, ".${sidecar.name}.tmp")
        temp.writeText(Checksums.sidecarLine(sha256, fileName))
        check(temp.renameTo(sidecar)) { "cannot write $sidecar" }
    }

    private fun remoteName(url: String): String = url.substringBefore('?').substringAfterLast('/')

    private fun isOutOfSpace(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(CAUSE_DEPTH_MAX).any { cause ->
            (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) || cause.message?.contains("ENOSPC") == true
        }

    private companion object {
        const val MIB = 1024L * 1024
        const val CAUSE_DEPTH_MAX = 8
    }
}
