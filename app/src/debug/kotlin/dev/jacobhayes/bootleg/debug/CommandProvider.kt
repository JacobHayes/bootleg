package dev.jacobhayes.bootleg.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import dev.jacobhayes.bootleg.BlankImageFormat
import dev.jacobhayes.bootleg.BootlegApp
import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.root.BootlegJson
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug-only control surface for L4 tests, guarded by android.permission.DUMP (adb shell has it):
 *
 *     adb shell content call --uri content://dev.jacobhayes.bootleg.debug --method STATE
 *
 * A provider rather than a broadcast receiver: libsu hands the root binder back to the app via a
 * broadcast, and Android delivers broadcasts to a process one at a time, so a receiver that waits
 * for the first root bind deadlocks until it times out. Provider calls run on binder threads.
 * Arguments come as `--extra key:type:value` (s = string, i = int, l = long, b = boolean).
 */
class CommandProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        val app = BootlegApp.from(checkNotNull(context))
        val backend = app.systemBackend
        val arguments = extras ?: Bundle()
        val json =
            try {
                runBlocking {
                    withTimeout(COMMAND_TIMEOUT_MS) {
                        when (method) {
                            "STATE" -> {
                                backend.refreshGadget()
                                BootlegJson.encodeToString(backend.gadgetState.value)
                            }
                            "JOURNAL" -> {
                                backend.refreshGadget()
                                BootlegJson.encodeToString(backend.journal.value)
                            }
                            "APPLY" -> {
                                val desired =
                                    requireNotNull(
                                        arguments.getString("desired") ?: arg,
                                    ) { "missing desired JSON (arg or --extra desired:s:...)" }
                                result(backend.apply(BootlegJson.decodeFromString<Desired>(desired)))
                            }
                            "EJECT" -> result(backend.eject(arguments.getInt("lun", 0), arguments.getBoolean("force", false)))
                            "REINSERT" -> result(backend.reinsert(arguments.getInt("lun", 0)))
                            "EJECT_ALL" -> result(backend.ejectAll())
                            "OFF" -> result(backend.bootlegOff())
                            "RECONCILE" -> result(backend.reconcile())
                            "GRANT_STORAGE" -> buildJsonObject { put("granted", backend.grantSelfStorage()) }.toString()
                            "IMAGES" -> {
                                backend.refreshImages()
                                backend.images.value.joinToString(",", "[", "]") { "\"${it.path}\"" }
                            }
                            "DELETE_IMAGE" -> {
                                val path = requireNotNull(arguments.getString("path") ?: arg) { "missing path (arg or --extra path:s:...)" }
                                backend.deleteImage(path).getOrThrow()
                                buildJsonObject { put("deleted", path) }.toString()
                            }
                            "CREATE_BLANK" -> {
                                val created =
                                    backend
                                        .createBlankImage(
                                            name = requireNotNull(arguments.getString("name")) { "missing --extra name:s:..." },
                                            sizeBytes = arguments.getLong("size", 0),
                                            format = BlankImageFormat.valueOf(arguments.getString("format") ?: "FAT32"),
                                            preallocate = arguments.getBoolean("preallocate", false),
                                        ).getOrThrow()
                                buildJsonObject {
                                    put("path", created.path)
                                    put("sizeBytes", created.sizeBytes)
                                    put("allocatedBytes", created.allocatedBytes)
                                }.toString()
                            }
                            else -> throw IllegalArgumentException("unknown method $method")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                errorJson("timed out after ${COMMAND_TIMEOUT_MS}ms", e)
            } catch (e: Exception) {
                errorJson(e.message ?: e.javaClass.simpleName, e)
            }
        return Bundle().apply { putString("json", json) }
    }

    private fun result(result: ApplyResult) = BootlegJson.encodeToString(result)

    private fun errorJson(
        message: String,
        cause: Throwable,
    ): String = buildJsonObject { put("error", "$message (${cause.javaClass.simpleName})") }.toString()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    private companion object {
        const val COMMAND_TIMEOUT_MS = 150_000L
    }
}
