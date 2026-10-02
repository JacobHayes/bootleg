package dev.jacobhayes.bootleg.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaScannerConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import dev.jacobhayes.bootleg.BlankImageFormat
import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.ImageFile
import dev.jacobhayes.bootleg.RootStatus
import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.hid.HidEndpoint
import dev.jacobhayes.bootleg.hid.RootHidEndpoint
import dev.jacobhayes.bootleg.root.BootlegJson
import dev.jacobhayes.bootleg.root.BootlegRootService
import dev.jacobhayes.bootleg.root.IBootlegRoot
import dev.jacobhayes.bootleg.root.ImagePaths
import dev.jacobhayes.bootleg.root.RootImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** [BootlegBackend] backed by [BootlegRootService] over [IBootlegRoot]. */
class BootlegBackendImpl(
    private val context: Context,
    private val scope: CoroutineScope,
) : BootlegBackend {
    private val rootStatusFlow = MutableStateFlow<RootStatus>(RootStatus.Unknown)
    private val gadgetStateFlow = MutableStateFlow<GadgetState?>(null)
    private val journalFlow = MutableStateFlow(Journal())
    private val imagesFlow = MutableStateFlow<List<ImageFile>>(emptyList())

    override val rootStatus: StateFlow<RootStatus> = rootStatusFlow.asStateFlow()
    override val gadgetState: StateFlow<GadgetState?> = gadgetStateFlow.asStateFlow()
    override val journal: StateFlow<Journal> = journalFlow.asStateFlow()
    override val images: StateFlow<List<ImageFile>> = imagesFlow.asStateFlow()

    private val bindMutex = Mutex()

    /** Lives as long as this process; when it dies, root releases every HID key and button. */
    private val hidClientToken = Binder()

    @Volatile private var connection: RootConnection? = null

    /** True when any slot is meant to be exported; the foreground service runs while this holds. */
    val hasExports: Boolean get() =
        journalFlow.value.slotIntents.values
            .any { it == SlotIntent.EXPORTED }

    override suspend fun refreshImages() {
        val listed = call { BootlegJson.decodeFromString<List<RootImage>>(it.listImages()) }
        imagesFlow.value = listed.map { it.toImageFile() }
    }

    override suspend fun apply(desired: Desired): ApplyResult = gadgetCall { it.apply(BootlegJson.encodeToString(desired)) }

    override suspend fun eject(
        lun: Int,
        force: Boolean,
    ): ApplyResult = gadgetCall { it.eject(lun, force) }

    override suspend fun reinsert(lun: Int): ApplyResult = gadgetCall { it.reinsert(lun) }

    override suspend fun bootlegOff(): ApplyResult = gadgetCall { it.bootlegOff() }

    override suspend fun setHid(spec: HidSpec): ApplyResult {
        // Apply against the freshest journal so the drives the root side holds are kept as they are.
        runCatchingNonCancel { refreshGadget() }.onFailure { return ApplyResult.Failed(it.message ?: it.javaClass.simpleName) }
        return apply(journalFlow.value.desired.copy(hid = spec))
    }

    override suspend fun openHid(kind: HidKind): HidEndpoint {
        val root = root()
        withContext(Dispatchers.IO) { root.hidOpen(kind.wireName, hidClientToken) }
        return RootHidEndpoint(root, kind)
    }

    suspend fun reconcile(): ApplyResult = gadgetCall { it.reconcile() }

    /** Normal (non-forced) eject of every LUN that currently has media. */
    suspend fun ejectAll(): ApplyResult {
        refreshGadget()
        val loaded =
            gadgetStateFlow.value
                ?.luns
                ?.filterValues { it.file.isNotEmpty() }
                ?.keys
                .orEmpty()
        var last: ApplyResult = ApplyResult.Applied(gadgetStateFlow.value ?: return ApplyResult.Failed("gadget state unknown"), false)
        for (lun in loaded.sorted()) {
            last = eject(lun, force = false)
            if (last !is ApplyResult.Applied) return last
        }
        return last
    }

    override suspend fun createBlankImage(
        name: String,
        sizeBytes: Long,
        format: BlankImageFormat,
        preallocate: Boolean,
    ): Result<ImageFile> =
        runCatchingNonCancel {
            val created =
                call { BootlegJson.decodeFromString<RootImage>(it.createBlankImage(name, sizeBytes, format.name, preallocate)) }
            MediaScannerConnection.scanFile(context, arrayOf(ImagePaths.toSharedStoragePath(created.path)), null, null)
            refreshImages()
            created.toImageFile()
        }

    override suspend fun deleteImage(path: String): Result<Unit> =
        runCatchingNonCancel {
            call { it.deleteImage(path) }
            MediaScannerConnection.scanFile(context, arrayOf(ImagePaths.toSharedStoragePath(ImagePaths.toKernelPath(path))), null, null)
            refreshImages()
        }

    suspend fun grantSelfStorage(): Boolean = call { it.grantSelfStorage() }

    suspend fun setKeepAwake(
        stayOnWhilePluggedIn: Int,
        lowLatency: Boolean,
        wakeScreen: Boolean,
    ) = call { it.setKeepAwake(stayOnWhilePluggedIn, lowLatency, wakeScreen) }

    suspend fun refreshGadget() {
        val (state, journal) =
            call {
                BootlegJson.decodeFromString<GadgetState>(it.readGadget()) to
                    BootlegJson.decodeFromString<Journal>(it.readJournal())
            }
        gadgetStateFlow.value = state
        journalFlow.value = journal
    }

    private suspend fun gadgetCall(block: (IBootlegRoot) -> String): ApplyResult {
        val result =
            try {
                BootlegJson.decodeFromString<ApplyResult>(call(block))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "gadget call failed", e)
                ApplyResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        runCatchingNonCancel { refreshGadget() }
        if (hasExports) BootlegService.start(context)
        return result
    }

    private suspend fun <T> call(block: (IBootlegRoot) -> T): T {
        val root = root()
        return withContext(Dispatchers.IO) { block(root) }
    }

    private suspend fun root(): IBootlegRoot {
        val current =
            bindMutex.withLock {
                connection ?: bind().also { connection = it }
            }
        try {
            return withTimeout(BIND_TIMEOUT_MS) { current.root.await() }
        } catch (e: TimeoutCancellationException) {
            bindMutex.withLock { if (connection === current) connection = null }
            withContext(Dispatchers.Main) { RootService.unbind(current) }
            rootStatusFlow.value = RootStatus.Unavailable("root service did not start within ${BIND_TIMEOUT_MS}ms")
            throw IllegalStateException("root service did not start within ${BIND_TIMEOUT_MS}ms", e)
        }
    }

    private suspend fun bind(): RootConnection {
        val granted = withContext(Dispatchers.IO) { Shell.getShell().isRoot }
        if (!granted) {
            rootStatusFlow.value = RootStatus.Unavailable("root access was not granted")
            error("root access was not granted")
        }
        val rootConnection = RootConnection()
        withContext(Dispatchers.Main) {
            val intent = Intent(context, BootlegRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE)
            RootService.bind(intent, rootConnection)
        }
        return rootConnection
    }

    private inner class RootConnection : ServiceConnection {
        val root = CompletableDeferred<IBootlegRoot>()

        override fun onServiceConnected(
            name: ComponentName,
            service: IBinder,
        ) {
            root.complete(IBootlegRoot.Stub.asInterface(service))
            rootStatusFlow.value = RootStatus.Granted
            scope.launch { runCatchingNonCancel { refreshGadget() } }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "root service disconnected")
            if (connection === this) connection = null
            root.completeExceptionally(IllegalStateException("root service disconnected"))
        }
    }

    private fun RootImage.toImageFile() = ImageFile(path, displayName, sizeBytes, allocatedBytes, verifiedSha256)

    private companion object {
        const val TAG = "BootlegBackend"
        const val BIND_TIMEOUT_MS = 30_000L
    }
}

private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
