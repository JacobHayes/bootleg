package dev.jacobhayes.bootleg.root

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import dev.jacobhayes.bootleg.BlankImageFormat
import dev.jacobhayes.bootleg.core.gadget.DefaultGadgetController
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import dev.jacobhayes.bootleg.core.hid.HidDescriptors

/**
 * Root side of the app (libsu RootService, daemon mode: it outlives the app's process).
 * Every call checks the caller's uid, validates its arguments and serializes against the others.
 */
class BootlegRootService : RootService() {
    private val gadgetLock = Any()

    // Formatting can take a while; it must not block ejects.
    private val imageLock = Any()
    private val journalStore = FileJournalStore()
    private val images = ImageStore()
    private lateinit var controller: DefaultGadgetController

    override fun onCreate() {
        super.onCreate()
        controller =
            createGadgetController(
                fs = RootConfigFs(),
                props = RootUsbProps(),
                journalStore = journalStore,
                deadMan = ShellDeadMan(),
                clock = SystemClock(),
                hidDescriptors = HidDescriptors,
                allowedRoots = ImagePaths.allowedRoots,
            )
        runCatching { images.sweepStaleLoopDevices() }.onFailure { Log.w(TAG, "loop sweep failed", it) }
        // Without a journal, reconcile would tear down whatever Bootleg exported before; adopt it instead.
        if (!journalStore.exists()) {
            synchronized(gadgetLock) {
                runCatching { adoptableDesired(controller.read())?.let { controller.apply(it) } }
                    .onFailure { Log.w(TAG, "adopting live gadget state failed", it) }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder =
        object : IBootlegRoot.Stub() {
            override fun readGadget(): String = call { BootlegJson.encodeToString(controller.read()) }

            override fun readJournal(): String = call { BootlegJson.encodeToString(journalStore.load()) }

            override fun apply(desiredJson: String): String =
                call {
                    val desired = BootlegJson.decodeFromString<Desired>(desiredJson)
                    BootlegJson.encodeToString(controller.apply(canonicalize(desired)))
                }

            override fun eject(
                lun: Int,
                force: Boolean,
            ): String = call { BootlegJson.encodeToString(controller.eject(lun, force)) }

            override fun reinsert(lun: Int): String = call { BootlegJson.encodeToString(controller.reinsert(lun)) }

            override fun bootlegOff(): String = call { BootlegJson.encodeToString(controller.bootlegOff()) }

            override fun reconcile(): String = call { BootlegJson.encodeToString(controller.reconcile()) }

            override fun createBlankImage(
                name: String,
                sizeBytes: Long,
                format: String,
                preallocate: Boolean,
            ): String =
                call(imageLock) {
                    BootlegJson.encodeToString(images.createBlank(name, sizeBytes, BlankImageFormat.valueOf(format), preallocate))
                }

            override fun deleteImage(path: String) =
                call(imageLock) {
                    // Lock order is imageLock, then gadgetLock (nothing takes them the other way round):
                    // holding both keeps an apply from exporting the file between the check and the unlink.
                    synchronized(gadgetLock) {
                        val exported =
                            controller
                                .read()
                                .luns.values
                                .map { it.file }
                                .filter { it.isNotEmpty() }
                                .toSet()
                        images.delete(path, exported)
                    }
                }

            override fun listImages(): String = call(imageLock) { BootlegJson.encodeToString(images.list()) }

            override fun grantSelfStorage(): Boolean =
                call(imageLock) {
                    images.ensureStoreDirectory()
                    RootCommands.run(listOf("appops", "set", "--uid", packageName, "MANAGE_EXTERNAL_STORAGE", "allow")).exitCode == 0
                }
        }

    /** Image paths become canonical kernel paths before they reach the controller. */
    private fun canonicalize(desired: Desired): Desired =
        desired.copy(
            slots =
                desired.slots.map { slot: SlotSpec ->
                    slot.copy(backingPath = PathGuard.requireImage(slot.backingPath, ImagePaths.allowedRoots))
                },
        )

    /**
     * Enforces the caller check and rethrows failures as exception types Binder carries back to
     * the app (IllegalArgumentException for bad input, IllegalStateException for everything else).
     */
    private fun <T> call(
        lock: Any = gadgetLock,
        block: () -> T,
    ): T {
        val callerUid = Binder.getCallingUid()
        if (callerUid != applicationInfo.uid) throw SecurityException("caller uid $callerUid is not the app")
        return synchronized(lock) {
            try {
                block()
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "root call failed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private companion object {
        const val TAG = "BootlegRoot"
    }
}
