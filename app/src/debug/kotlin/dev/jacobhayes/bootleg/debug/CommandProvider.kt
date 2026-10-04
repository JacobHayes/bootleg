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
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.hid.HidTestSequence
import dev.jacobhayes.bootleg.core.remote.LeaseHolder
import dev.jacobhayes.bootleg.core.remote.Principal
import dev.jacobhayes.bootleg.core.tether.TetherMode
import dev.jacobhayes.bootleg.root.BootlegJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
                            "PROBE_FILE" -> {
                                // How the app's FUSE view sees a store file (stale entries stat fine but don't open).
                                val name = requireNotNull(arguments.getString("name") ?: arg) { "missing name" }
                                val file = java.io.File(dev.jacobhayes.bootleg.storage.StoragePaths.STORE_FUSE_DIR, name)
                                val open =
                                    runCatching {
                                        java.nio.file.Files
                                            .newByteChannel(file.toPath())
                                            .close()
                                    }.fold({ "ok" }, { "${it::class.simpleName}: ${it.message}" })
                                buildJsonObject {
                                    put("exists", file.exists())
                                    put("open", open)
                                    put(
                                        "stale",
                                        dev.jacobhayes.bootleg.storage.StoragePaths
                                            .isStaleEntry(file),
                                    )
                                }.toString()
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
                            "HID_ENABLE" ->
                                result(
                                    app.hid.setSpec(
                                        HidSpec(arguments.getBoolean("keyboard", true), arguments.getBoolean("mouse", true)),
                                    ),
                                )
                            "HID_OFF" -> result(app.hid.setSpec(HidSpec()))
                            "HID_STATE" -> hidStateJson(app)
                            // Only fixed harmless sequences (F24, a Shift tap, a cancelling mouse move, a cancelling
                            // scroll): never arbitrary input.
                            "HID_SEND" -> {
                                val name = arguments.getString("sequence") ?: arg
                                val sequence =
                                    requireNotNull(name?.let(HidTestSequence::fromWireName)) {
                                        "sequence must be one of ${HidTestSequence.entries.map { it.wireName }}"
                                    }
                                // The phone can't see the Mac's pointer: the caller must have parked it (AGENTS.md).
                                require(sequence != HidTestSequence.SCROLL || arguments.getBoolean("pointerParked", false)) {
                                    "scroll needs --extra pointerParked:b:true, sent only while the Mac's pointer is parked on an inert spot"
                                }
                                val sent = app.hid.sendTestSequence(sequence)
                                buildJsonObject {
                                    put("sequence", sent.sequence)
                                    put("queued", sent.queued)
                                    put("written", sent.written)
                                    put("dropped", sent.dropped)
                                    put("link", sent.link.toString())
                                }.toString()
                            }
                            // USB tethering (L4 check:device:tether): the same path as the Drives switch.
                            "TETHER_ON" -> BootlegJson.encodeToString(backend.setUsbTethering(true))
                            "TETHER_OFF" -> BootlegJson.encodeToString(backend.setUsbTethering(false))
                            "TETHER_MODE" -> {
                                val mode =
                                    requireNotNull(
                                        arguments.getString("mode")?.let(TetherMode::fromWireName),
                                    ) { "mode must be rndis or ncm" }
                                backend.setUsbTetherMode(mode)
                                buildJsonObject { put("mode", mode.wireName) }.toString()
                            }
                            "TETHER_STATE" ->
                                buildJsonObject {
                                    put("tethered", backend.usbTethered())
                                    put("observed", backend.usbTethering.value)
                                    put("choice", app.prefs.usbTetherChoice.value)
                                    put("mode", backend.usbTetherMode.value.wireName)
                                }.toString()
                            // Remote (L4 check:device:remote). Approve/revoke here stand in for the in-app buttons.
                            "REMOTE_ON" -> {
                                withContext(Dispatchers.Main) { app.remote.setEnabled(true) }
                                remoteStateJson(app)
                            }
                            "REMOTE_OFF" -> {
                                withContext(Dispatchers.Main) { app.remote.setEnabled(false) }
                                withTimeout(REMOTE_WAIT_MS) { app.remote.state.first { !it.running } }
                                remoteStateJson(app)
                            }
                            "REMOTE_STATE" -> remoteStateJson(app)
                            "CATALOG_REFRESH" -> {
                                // mode: stale (default, what opening the Catalog does), all, none (just report)
                                when (arguments.getString("mode") ?: arg ?: "stale") {
                                    "stale" -> app.catalog.refreshStale().join()
                                    "all" -> app.catalog.refreshAll().join()
                                    "none" -> app.catalog.awaitCache()
                                    else -> throw IllegalArgumentException("mode must be stale, all or none")
                                }
                                catalogStateJson(app)
                            }
                            "CATALOG_PROFILE" ->
                                withContext(Dispatchers.IO) {
                                    CatalogProfiler
                                        .profile(
                                            dev.jacobhayes.bootleg.catalog
                                                .catalogScrapers(),
                                        ).toString()
                                }
                            "REMOTE_APPROVE" -> {
                                val nodeId =
                                    requireNotNull(arguments.getString("node") ?: arg) { "missing --extra node:s:<stable node id>" }
                                app.remote.approve(nodeId, arguments.getBoolean("rw", false))
                                withTimeout(REMOTE_WAIT_MS) {
                                    app.remote.state.first { state ->
                                        state.status.approvals.any {
                                            it.nodeId ==
                                                nodeId
                                        }
                                    }
                                }
                                remoteStateJson(app)
                            }
                            "REMOTE_REVOKE" -> {
                                val nodeId =
                                    requireNotNull(arguments.getString("node") ?: arg) { "missing --extra node:s:<stable node id>" }
                                app.remote.revoke(Principal.Node(nodeId))
                                withTimeout(REMOTE_WAIT_MS) {
                                    app.remote.state.first { state ->
                                        state.status.approvals.none {
                                            it.nodeId ==
                                                nodeId
                                        }
                                    }
                                }
                                remoteStateJson(app)
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

    private fun catalogStateJson(app: BootlegApp): String {
        val state = app.catalog.state.value
        return buildJsonObject {
            put("offline", state.offline)
            put("refreshing", state.refreshing)
            put("cacheLoaded", state.cacheLoaded)
            putJsonArray("entries") {
                state.entries.forEach { entry ->
                    addJsonObject {
                        put("id", entry.distro.id)
                        put("releases", entry.releases.size)
                        put("fetchedAtMs", entry.fetchedAtMs)
                        put("loading", entry.loading)
                        put("error", entry.error)
                    }
                }
            }
        }.toString()
    }

    private fun hidStateJson(app: BootlegApp): String {
        val stats = app.hid.stats.value
        return buildJsonObject {
            put("keyboard", app.hid.spec.value.keyboard)
            put("mouse", app.hid.spec.value.mouse)
            put(
                "link",
                app.hid.link.value
                    .toString(),
            )
            put("written", stats.reportsWritten)
            put("dropped", stats.reportsDropped)
            put("releaseAll", stats.releaseAllCount)
            put("lastError", stats.lastError)
            put(
                "leds",
                app.hid.leds.value
                    ?.toString(),
            )
        }.toString()
    }

    private fun remoteStateJson(app: BootlegApp): String {
        val state = app.remote.state.value
        val status = state.status
        return buildJsonObject {
            put("enabled", state.enabled)
            put("running", state.running)
            put("backendState", state.tailnet.backendState)
            put("dnsName", state.tailnet.fqdn)
            putJsonArray("tailscaleIPs") { state.tailnet.tailscaleIPs.forEach { add(it) } }
            put("needsLogin", state.tailnet.needsLogin)
            put("localNetworkGranted", state.localNetworkGranted)
            put("keepAwake", state.keepAwake)
            put("error", state.error)
            put(
                "lease",
                when (val holder = status.leaseHolder) {
                    null -> "free"
                    LeaseHolder.Local -> "local"
                    is LeaseHolder.Remote -> "session:${holder.sessionId}"
                },
            )
            putJsonArray("sessions") {
                status.sessions.forEach { session ->
                    addJsonObject {
                        put("sessionId", session.sessionId)
                        put("transport", session.transport)
                        put("name", session.displayName)
                        put("auth", session.auth.name.lowercase())
                        put("holdsLease", session.holdsLease)
                    }
                }
            }
            putJsonArray("pending") {
                status.pending.forEach { pending ->
                    addJsonObject {
                        put("nodeId", pending.nodeId)
                        put("name", pending.displayName)
                        put("user", pending.user)
                    }
                }
            }
            putJsonArray("approvals") {
                status.approvals.forEach { approval ->
                    addJsonObject {
                        put("nodeId", approval.nodeId)
                        put("name", approval.displayName)
                        put("allowRw", approval.allowRw)
                    }
                }
            }
        }.toString()
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

        /** Remote off waits for Go to close; approve/revoke wait one actor step. */
        const val REMOTE_WAIT_MS = 15_000L
    }
}
