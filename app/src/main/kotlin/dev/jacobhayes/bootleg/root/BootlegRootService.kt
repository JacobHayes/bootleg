package dev.jacobhayes.bootleg.root

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import dev.jacobhayes.bootleg.BlankImageFormat
import dev.jacobhayes.bootleg.core.gadget.DefaultGadgetController
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.HidNodeGuard
import dev.jacobhayes.bootleg.core.hid.HidDescriptors
import dev.jacobhayes.bootleg.core.hid.HidKind

/**
 * Root side of the app (libsu RootService, daemon mode: it outlives the app's process).
 * Every call checks the caller's uid, validates its arguments and serializes against the others.
 */
class BootlegRootService : RootService() {
    private val gadgetLock = Any()

    // Formatting can take a while; it must not block ejects.
    private val imageLock = Any()

    // HID writes take at most the 50ms write deadline; they must never wait behind a gadget apply.
    private val hidLock = Any()
    private val keepAwakeLock = Any()
    private val hidRelay = HidRelay()
    private val hidHolders = HidNodeHolders()

    /** The controller's view of the relay: it closes our hidg fds before any UDC write or hid removal. */
    private val hidNodes =
        object : HidNodeGuard {
            override fun closeAll() = hidRelay.closeAllForGadgetChange()

            override fun holders(functions: Collection<String>): List<String> = hidHolders.scan(functions)
        }
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
                hidNodes = hidNodes,
                allowedRoots = ImagePaths.allowedRoots,
            )
        runCatching { images.sweepStaleLoopDevices() }.onFailure { Log.w(TAG, "loop sweep failed", it) }
        // Without a journal, reconcile would tear down whatever Bootleg exported before; adopt it instead.
        if (!journalStore.exists()) {
            synchronized(gadgetLock) {
                runCatching { gadgetChange { adoptableDesired(controller.read())?.let { controller.apply(it) } } }
                    .onFailure { Log.w(TAG, "adopting live gadget state failed", it) }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder =
        object : IBootlegRoot.Stub() {
            override fun readGadget(): String = call { BootlegJson.encodeToString(controller.read()) }

            // Records host ejects on the way: the host emptying a LUN sends no broadcast, so readers poll this.
            override fun readJournal(): String = call { BootlegJson.encodeToString(controller.observe()) }

            override fun apply(desiredJson: String): String =
                call {
                    val desired = BootlegJson.decodeFromString<Desired>(desiredJson)
                    BootlegJson.encodeToString(gadgetChange { controller.apply(canonicalize(desired)) })
                }

            override fun eject(
                lun: Int,
                force: Boolean,
            ): String = call { BootlegJson.encodeToString(gadgetChange { controller.eject(lun, force) }) }

            override fun reinsert(lun: Int): String = call { BootlegJson.encodeToString(gadgetChange { controller.reinsert(lun) }) }

            override fun bootlegOff(): String = call { BootlegJson.encodeToString(gadgetChange { controller.bootlegOff() }) }

            override fun reconcile(): String = call { BootlegJson.encodeToString(gadgetChange { controller.reconcile() }) }

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

            override fun hidOpen(
                kind: String,
                client: IBinder,
            ) = call(hidLock) { hidRelay.open(hidKind(kind), client) }

            override fun hidWrite(
                kind: String,
                report: ByteArray,
            ): Int = call(hidLock) { hidRelay.write(hidKind(kind), report).ordinal }

            override fun hidReadOutput(kind: String): ByteArray = call(hidLock) { hidRelay.readOutput(hidKind(kind)) }

            override fun hidClose(kind: String) = call(hidLock) { hidRelay.close(hidKind(kind)) }

            override fun setKeepAwake(
                stayOnWhilePluggedIn: Int,
                lowLatency: Boolean,
                wakeScreen: Boolean,
            ) = call(keepAwakeLock) {
                require(stayOnWhilePluggedIn in STAY_ON_VALUE_MIN..STAY_ON_VALUE_MAX) {
                    "stay_on_while_plugged_in must be $STAY_ON_VALUE_MIN..$STAY_ON_VALUE_MAX, got $stayOnWhilePluggedIn"
                }
                RootCommands.runChecked(listOf("settings", "put", "global", "stay_on_while_plugged_in", stayOnWhilePluggedIn.toString()))
                RootCommands.runChecked(listOf("cmd", "wifi", "force-low-latency-mode", if (lowLatency) "enabled" else "disabled"))
                if (wakeScreen) RootCommands.runChecked(listOf("input", "keyevent", "KEYCODE_WAKEUP"))
            }
        }

    /** Runs a controller call that may close the relay; HID opens are allowed again once it has returned. */
    private fun <T> gadgetChange(block: () -> T): T =
        try {
            block()
        } finally {
            hidRelay.endGadgetChange()
        }

    private fun hidKind(kind: String): HidKind = requireNotNull(HidKind.fromWireName(kind)) { "unknown HID kind: $kind" }

    /** Image paths become canonical kernel paths, checked as a set by inode, before they reach the controller. */
    private fun canonicalize(desired: Desired): Desired =
        canonicalizeDesired(desired) { path ->
            ConfinedFiles.openImage(path, ImagePaths.allowedRoots).use { OpenedImage(it.path, it.identity) }
        }

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
        const val STAY_ON_VALUE_MIN = 0

        // AC | USB | wireless.
        const val STAY_ON_VALUE_MAX = 7
    }
}
