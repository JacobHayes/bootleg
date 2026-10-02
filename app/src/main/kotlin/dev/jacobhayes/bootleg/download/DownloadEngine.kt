package dev.jacobhayes.bootleg.download

import android.system.ErrnoException
import android.system.OsConstants
import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.ScraperSupport
import dev.jacobhayes.bootleg.core.catalog.resolveSha256
import dev.jacobhayes.bootleg.core.image.Sha256Streamer
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
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipException

/**
 * Transfers one download: resolve the expected hash, fetch with Range/If-Range resume into
 * `<name>.partial`, hash the whole file, atomically rename, and write a `<name>.sha256` sidecar.
 * A zipped release downloads the archive into `<name>.partial`, verifies it, then extracts the one
 * image entry (see [ArchiveExtractor]) and deletes the archive.
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
                } catch (exists: FileAlreadyExistsException) {
                    // An import (or another download) of the same name finished first; never replace it.
                    partialFile(fileName).delete()
                    fail(fileName, "$fileName is already in the library", retryable = false)
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
                        expectedTotalBytes = record.totalBytes,
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
        // The sums file names what the URL serves (the archive for a zipped release), so look it up under that name.
        val source =
            Release(
                distroId = "download",
                version = "download",
                arch = Arch.X86_64,
                url = record.url,
                sumsUrl = record.sumsUrl,
                fileName = if (record.archiveEntry != null) remoteName(record.url) else record.fileName,
            )
        // Fail closed: a release that names a sums file is only ever stored verified against it.
        val hash =
            try {
                resolveSha256(source, sumsHttp)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw StopException("Couldn't get the checksum: ${error.message ?: error::class.simpleName}", retryable = true)
            }
        checkNotNull(hash) { "sums lookup returned nothing for $fileName" }
        store.update(fileName) { it.copy(expectedSha256 = hash) }
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
            partial
                .inputStream()
                .use { input ->
                    Sha256Streamer.digest(input) {
                        if (!job.isActive || store[fileName]?.status != DownloadStatus.VERIFYING) {
                            throw CancellationException("verify stopped")
                        }
                    }
                }.hex
        val record = checkNotNull(store[fileName]) { "record vanished: $fileName" }
        val expected = record.expectedSha256
        if (expected != null && expected != actual) {
            check(partial.delete()) { "cannot delete $partial" }
            store.update(fileName) { it.copy(verify = VerifyState.MISMATCH, downloadedBytes = 0) }
            return false
        }
        val target = finalFile(fileName)
        val imageSha256 =
            if (record.archiveEntry != null) {
                extractArchive(record, partial, target, archiveVerified = expected != null)
            } else {
                StoragePaths.moveIntoPlace(partial, target)
                expected
            }
        if (imageSha256 != null) writeSidecar(fileName, imageSha256)
        val verify = if (imageSha256 != null) VerifyState.VERIFIED else VerifyState.UNAVAILABLE
        store.update(fileName) { it.copy(status = DownloadStatus.COMPLETED, verify = verify, error = null) }?.let(onProgress)
        onImageAdded()
        return true
    }

    /**
     * Extracts the release's image from the verified [archive] into [target] and deletes the archive.
     * Returns the image hash when it is trustworthy (the archive or the image itself was verified).
     */
    private suspend fun extractArchive(
        record: DownloadRecord,
        archive: File,
        target: File,
        archiveVerified: Boolean,
    ): String? {
        val entry = checkNotNull(record.archiveEntry)
        val temp = File(storeDir, StoragePaths.extractStagingName(record.fileName))
        check(!temp.exists() || temp.delete()) { "cannot delete $temp" }
        val job = currentCoroutineContext().job
        var moved = false
        try {
            val extracted =
                try {
                    ArchiveExtractor.extract(archive, entry, temp) {
                        if (!job.isActive || store[record.fileName]?.status != DownloadStatus.VERIFYING) {
                            throw CancellationException("extract stopped")
                        }
                    }
                } catch (corrupt: ZipException) {
                    archive.delete()
                    throw StopException("Archive is corrupt: ${corrupt.message}", retryable = false)
                }
            if (record.imageSha256 != null && record.imageSha256 != extracted.sha256) {
                archive.delete()
                store.update(record.fileName) { it.copy(verify = VerifyState.MISMATCH, downloadedBytes = 0) }
                throw StopException("Checksum mismatch in the extracted image", retryable = false)
            }
            StoragePaths.moveIntoPlace(temp, target)
            moved = true
            check(archive.delete()) { "cannot delete $archive" }
            return extracted.sha256.takeIf { archiveVerified || record.imageSha256 != null }
        } finally {
            if (!moved) temp.delete()
        }
    }

    private fun writeSidecar(
        fileName: String,
        sha256: String,
    ) {
        val sidecar = sidecarFile(fileName)
        val temp = File(storeDir, ".${sidecar.name}.tmp")
        require(ScraperSupport.isSha256(sha256)) { "not a lowercase sha256: $sha256" }
        // GNU format, so `sha256sum -c` works on the sidecar.
        temp.writeText("$sha256  $fileName\n")
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
