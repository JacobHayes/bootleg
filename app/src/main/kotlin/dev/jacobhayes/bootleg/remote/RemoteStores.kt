package dev.jacobhayes.bootleg.remote

import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.jacobhayes.bootleg.core.remote.ApprovalRecord
import dev.jacobhayes.bootleg.core.remote.ApprovalStore
import dev.jacobhayes.bootleg.core.remote.PairingRecord
import dev.jacobhayes.bootleg.core.remote.PairingStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * A JSON file in noBackupFilesDir (never backed up or restored to another device), written
 * atomically: temp file, fsync, rename, then fsync the directory.
 */
class AtomicJsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val empty: () -> T,
) {
    fun load(): T {
        if (!file.isFile) return empty()
        require(file.length() <= FILE_BYTES_MAX) { "${file.name} is ${file.length()} bytes; the limit is $FILE_BYTES_MAX" }
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: IllegalArgumentException) {
            // A corrupt store fails closed: nothing is approved or paired until the user approves again.
            Log.w(TAG, "${file.name} is unreadable; starting empty", e)
            empty()
        }
    }

    fun save(value: T) {
        val directory = checkNotNull(file.parentFile) { "${file.path} has no parent directory" }
        check(directory.isDirectory || directory.mkdirs()) { "cannot create ${directory.path}" }
        val encoded = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        check(encoded.size <= FILE_BYTES_MAX) { "${file.name} would be ${encoded.size} bytes; the limit is $FILE_BYTES_MAX" }
        val temporary = File(directory, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(encoded)
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "rename ${temporary.path} -> ${file.path} failed" }
        syncDirectory(directory)
    }

    fun delete() {
        file.delete()
    }

    private fun syncDirectory(directory: File) {
        // Makes the rename durable; best effort (opening a directory read-only works on Linux).
        runCatching {
            Os
                .open(directory.path, OsConstants.O_RDONLY, 0)
                .also { descriptor ->
                    try {
                        Os.fsync(descriptor)
                    } finally {
                        Os.close(descriptor)
                    }
                }
        }
    }

    private companion object {
        const val TAG = "BootlegRemoteStore"

        /** Approvals (≤ 64) and tokens (≤ 16) with display names are a few tens of KiB at most. */
        const val FILE_BYTES_MAX = 1L shl 20
        val json = Json { ignoreUnknownKeys = true }
    }
}

class FileApprovalStore(
    directory: File,
) : ApprovalStore {
    private val file = AtomicJsonFile(File(directory, "approvals.json"), ListSerializer(ApprovalRecord.serializer())) { emptyList() }

    override fun load(): List<ApprovalRecord> = file.load()

    override fun save(records: List<ApprovalRecord>) = file.save(records)
}

class FilePairingStore(
    directory: File,
) : PairingStore {
    private val file = AtomicJsonFile(File(directory, "pairing.json"), PairingRecord.serializer()) { PairingRecord() }

    override fun load(): PairingRecord = file.load()

    override fun save(record: PairingRecord) = file.save(record)
}
