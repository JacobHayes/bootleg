package dev.jacobhayes.bootleg.root

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.jacobhayes.bootleg.core.gadget.Clock
import dev.jacobhayes.bootleg.core.gadget.DeadMan
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.JournalStore
import dev.jacobhayes.bootleg.core.gadget.UsbProps
import dev.jacobhayes.bootleg.core.tether.TetherTransition
import dev.jacobhayes.bootleg.core.tether.TetherTransitionStore
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.UUID

/** Runs programs as root by argv (never through a shell string), with a timeout and bounded output; see [CommandRunner]. */
object RootCommands {
    const val COMMAND_TIMEOUT_MS = 10_000L

    private val runner =
        CommandRunner(
            sessionLauncher = listOf("setsid"),
            signalGroup = { processGroupId, signal ->
                try {
                    Os.kill(-processGroupId, signal)
                    true
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.ESRCH) throw e
                    false
                }
            },
        )

    fun run(
        argv: List<String>,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): CommandRunner.Output = runner.run(argv, timeoutMs)

    /** Like [run] but fails loudly on a non-zero exit code; returns the output. */
    fun runChecked(
        argv: List<String>,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): String {
        val output = run(argv, timeoutMs)
        check(output.exitCode == 0) { "${argv[0]} exited ${output.exitCode}: ${output.text.take(500)}" }
        return output.text
    }
}

/**
 * Reads USB properties in-process through the hidden `android.os.SystemProperties.get` (reflection
 * works in the root process). Forking `getprop` costs ~110ms per read on the Pixel 6, which made the
 * HAL-quiet wait and every state read slow; `getprop` stays as the fallback.
 */
class RootUsbProps : UsbProps {
    override fun usbState(): String = get("sys.usb.state")

    override fun usbConfig(): String = get("sys.usb.config")

    private fun get(name: String): String {
        val method = systemPropertiesGet ?: return getprop(name)
        return try {
            method.invoke(null, name) as String
        } catch (_: ReflectiveOperationException) {
            getprop(name)
        }
    }

    private fun getprop(name: String): String = RootCommands.runChecked(listOf("getprop", name)).trim()

    private companion object {
        val systemPropertiesGet: Method? =
            try {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            } catch (_: ReflectiveOperationException) {
                null
            }
    }
}

class SystemClock : Clock {
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    override fun sleepMs(durationMs: Long) {
        require(durationMs >= 0) { "negative sleep: $durationMs" }
        Thread.sleep(durationMs)
    }
}

/** Journal persisted as JSON with write-temp, fsync, rename so a crash never leaves a torn file. */
class FileJournalStore(
    private val file: File = File("/data/adb/bootleg/journal.json"),
) : JournalStore {
    fun exists(): Boolean = file.exists()

    override fun load(): Journal {
        if (!file.exists()) return Journal()
        return runCatching { BootlegJson.decodeFromString<Journal>(file.readText()) }
            .getOrElse { error("journal at $file is unreadable: ${it.message}") }
    }

    override fun save(journal: Journal) = writeAtomically(file, BootlegJson.encodeToString(journal).toByteArray())
}

/**
 * Writes [bytes] to [file] with write-temp, fsync, rename and a directory fsync, so a crash never
 * leaves a torn file and a power loss can't bring the old one back. The directory is made 0700.
 */
fun writeAtomically(
    file: File,
    bytes: ByteArray,
) {
    val directory = checkNotNull(file.parentFile)
    if (!directory.exists()) check(directory.mkdirs()) { "cannot create $directory" }
    Os.chmod(directory.path, ROOT_STATE_DIRECTORY_MODE)
    val temporary = File(directory, "${file.name}.tmp")
    FileOutputStream(temporary).use { stream ->
        stream.write(bytes)
        stream.fd.sync()
    }
    Os.rename(temporary.path, file.path)
    // The rename lives in the directory entry; without this a power loss can bring back the old file.
    fsyncDirectory(directory)
}

private fun fsyncDirectory(directory: File) {
    val directoryDescriptor = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
    try {
        Os.fsync(directoryDescriptor)
    } finally {
        Os.close(directoryDescriptor)
    }
}

internal const val ROOT_STATE_DIRECTORY_MODE = 0b111_000_000 // 0700

/**
 * Whether a FunctionFS function (`ffs.<instance>`) has its descriptors written, so it can bind: its
 * mount (in init's namespace, `<instance> <dir> functionfs`) shows `ep1`. Unknown (no mount) is not ready.
 */
fun functionFsReady(function: String): Boolean {
    val instance = function.removePrefix("ffs.")
    val mountPoint =
        runCatching { File("/proc/1/mounts").readLines() }
            .getOrDefault(emptyList())
            .map { it.split(' ') }
            .firstOrNull { it.size > 2 && it[0] == instance && it[2] == "functionfs" }
            ?.get(1) ?: return false
    return File("/proc/1/root$mountPoint/ep1").exists()
}

/**
 * The tethering transition marker next to the journal. An unreadable marker counts as unverified
 * and written now, so the gate waits its full window instead of trusting it.
 */
class FileTetherTransitionStore(
    private val file: File = File("/data/adb/bootleg/tether-transition.json"),
    private val clock: Clock,
) : TetherTransitionStore {
    /**
     * When this process first found the marker unreadable. An unreadable marker counts as written then,
     * not at every load, so its window ends (HID opens wait on it too, and they load it every 50ms).
     */
    private var unreadableSinceMs: Long? = null

    @Synchronized
    override fun load(): TetherTransition? {
        if (!file.exists()) {
            unreadableSinceMs = null
            return null
        }
        return runCatching { BootlegJson.decodeFromString<TetherTransition>(file.readText()) }
            .onSuccess { unreadableSinceMs = null }
            .getOrElse {
                val sinceMs = unreadableSinceMs ?: clock.nowMs().also { nowMs -> unreadableSinceMs = nowMs }
                Log.w(TAG, "tethering marker at $file is unreadable; waiting it out", it)
                TetherTransition(requestedOn = false, startedAtMs = sinceMs, verified = false)
            }
    }

    @Synchronized
    override fun save(transition: TetherTransition) {
        unreadableSinceMs = null
        writeAtomically(file, BootlegJson.encodeToString(transition).toByteArray())
    }

    @Synchronized
    override fun clear() {
        unreadableSinceMs = null
        if (!file.exists()) return
        if (!file.delete() && file.exists()) error("cannot delete $file")
        fsyncDirectory(checkNotNull(file.parentFile))
    }

    private companion object {
        const val TAG = "BootlegTether"
    }
}

/**
 * Spawns a detached root shell that rebinds the UDC if it is still unbound after the delay.
 * It runs in its own session so it outlives this process; values are passed as positional
 * arguments, never interpolated into the script.
 *
 * Each arm writes a fresh token to [tokenFile] and the shell acts only if the token is still its own,
 * so [disarm] (a new token) stops every watchdog of a finished transition from touching a later one.
 * When the bind fails, an unserved RNDIS function of ours is the likely cause: unless some process
 * holds a hidg fd (removing a HID function under it panics the kernel once it's closed), the shell
 * unlinks all of Bootleg's functions and binds again, so ADB comes back and nothing of ours sits on
 * another function's interface number. With the NCM function linked it binds as is only while every
 * linked FunctionFS function is ready (a failed bind after f_ncm panics), else goes straight to that.
 * Every write runs under `timeout`.
 */
class ShellDeadMan(
    private val gadgetRoot: String = GadgetNames.GADGET_ROOT,
    private val tokenFile: File = File("/data/adb/bootleg/deadman"),
) : DeadMan {
    override fun arm(
        udc: String,
        delaySeconds: Int,
    ) {
        require(UDC_NAME.matches(udc)) { "invalid UDC name: '$udc'" }
        require(delaySeconds in 1..DELAY_SECONDS_MAX) { "dead-man delay out of range: $delaySeconds" }
        val token = newToken()
        ProcessBuilder(
            "setsid",
            "sh",
            "-c",
            SCRIPT,
            "bootleg-deadman",
            delaySeconds.toString(),
            udc,
            "$gadgetRoot/UDC",
            tokenFile.path,
            token,
            "$gadgetRoot/${GadgetNames.CONFIG_DIR}",
            WRITE_TIMEOUT_SECONDS.toString(),
            "/proc",
        ).redirectInput(File("/dev/null"))
            .redirectOutput(File("/dev/null"))
            .redirectError(File("/dev/null"))
            .start()
    }

    override fun disarm() {
        newToken()
    }

    private fun newToken(): String {
        val token = UUID.randomUUID().toString()
        writeAtomically(tokenFile, token.toByteArray())
        return token
    }

    internal companion object {
        private const val DELAY_SECONDS_MAX = 60
        private const val WRITE_TIMEOUT_SECONDS = 3
        private val UDC_NAME = Regex("[A-Za-z0-9._-]{1,64}")

        // $1 delay, $2 UDC name, $3 UDC path, $4 token file, $5 token, $6 config dir, $7 write timeout, $8
        // /proc. The token is checked again right before each change, so a disarm while this runs stops
        // what is left. One `ls` lists every fd: a readlink per fd (~20k of them) takes minutes. With the NCM
        // function linked the first bind is tried only while every linked FunctionFS function has its ep1:
        // a bind that fails after f_ncm has bound panics this kernel.
        const val SCRIPT =
            "sleep \"\$1\"; token_file=\$4 token=\$5 config=\$6 proc=\$8; " +
                "ours() { [ \"\$(cat \"\$token_file\")\" = \"\$token\" ]; }; ours || exit 0; " +
                "[ -z \"\$(timeout \"\$7\" cat \"\$3\")\" ] || exit 0; ours || exit 0; " +
                "ready() { for link in \"\$config\"/*; do f=\$(readlink \"\$link\") || continue; f=\${f##*/}; case \$f in ffs.*) " +
                "m=\$(awk -v n=\"\${f#ffs.}\" '\$1 == n && \$3 == \"functionfs\" { print \$2 }' \"\$proc/1/mounts\"); " +
                "[ -n \"\$m\" ] && [ -e \"\$proc/1/root\$m/ep1\" ] || return 1;; esac; done; }; " +
                "if [ ! -L \"\$6/${GadgetNames.NCM_FUNCTION}\" ] || ready; then " +
                "timeout \"\$7\" sh -c 'echo \"\$1\" > \"\$2\"' bind \"\$2\" \"\$3\" && exit 0; fi; " +
                "ls -l \"\$8\"/[0-9]*/fd/ 2>/dev/null | grep -q ' -> /dev/hidg' && exit 0; " +
                "ours || exit 0; for link in \"\$6\"/*bootleg*; do [ -L \"\$link\" ] && rm \"\$link\"; done; ours || exit 0; " +
                "timeout \"\$7\" sh -c 'echo \"\$1\" > \"\$2\"' bind \"\$2\" \"\$3\""
    }
}

/**
 * Opens files under an allowed root without following a symlink anywhere below it. Each component
 * is opened `O_NOFOLLOW` relative to the directory fd of the one before (via
 * `/proc/self/fd/<dirfd>/<name>`, since [Os] has no `openat`), so swapping a directory for a
 * symlink between a check and the open can't redirect it. The kernel then gets `/proc/self/fd/N`
 * of the opened file, never the path.
 */
object ConfinedFiles {
    private const val TAG = "ConfinedFiles"

    /** Init's root directory: a path walked through it resolves against init's mounts. */
    private const val INIT_ROOT = "/proc/1/root"

    // The SDK has no O_DIRECTORY, so directories are checked with fstat after the open. O_NONBLOCK: a
    // FIFO must fail that check, not hang the binder thread in open().
    private val OPEN_FLAGS = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK

    /** Opens the existing regular file at [path] (user or kernel path) inside [roots]; the caller closes it. */
    fun openImage(
        path: String,
        roots: List<String>,
    ): ConfinedFile {
        val confined = ConfinedPath.parse(ImagePaths.toKernelPath(path), roots)
        return withParent(confined) { parent, rootDevice ->
            val file = open(childPath(parent, confined.name), confined)
            try {
                val stat = Os.fstat(file.fileDescriptor)
                require(OsConstants.S_ISREG(stat.st_mode)) { "not a regular file: $path" }
                require(stat.st_dev == rootDevice) { "file is on another filesystem than ${confined.root}: $path" }
                ConfinedFile(confined.path, file, FileIdentity(stat.st_dev, stat.st_ino), stat.st_size)
            } catch (e: Throwable) {
                file.close()
                throw e
            }
        }
    }

    /**
     * Opens the existing directory at [path] (a kernel path) inside [roots], with no symlink anywhere
     * below the root and on the root's filesystem. Files are then looked up by name inside it
     * ([openIn], [childPath]), so the directory can't be swapped out underneath; the caller closes it.
     */
    fun openDirectory(
        path: String,
        roots: List<String>,
    ): ParcelFileDescriptor {
        val confined = ConfinedPath.parse(path, roots)
        return withParent(confined) { parent, rootDevice ->
            val directory = open(childPath(parent, confined.name), confined)
            try {
                val stat = Os.fstat(directory.fileDescriptor)
                require(OsConstants.S_ISDIR(stat.st_mode)) { "not a directory: $path" }
                require(stat.st_dev == rootDevice) { "directory is on another filesystem than ${confined.root}: $path" }
                directory
            } catch (e: Throwable) {
                directory.close()
                throw e
            }
        }
    }

    /** Opens [name] inside the pinned [directory], never following a symlink at [name]; the caller closes it. */
    fun openIn(
        directory: ParcelFileDescriptor,
        name: String,
        flags: Int,
        mode: Int = 0,
    ): ParcelFileDescriptor {
        val descriptor = Os.open(childPath(directory, name), flags or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, mode)
        try {
            return ParcelFileDescriptor.dup(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    /** [directory] itself, through its fd: listing this lists the pinned directory. Valid in this process only. */
    fun selfPath(directory: ParcelFileDescriptor): String = "/proc/self/fd/${directory.fd}"

    /**
     * Removes the regular file at [confined] and its `.sha256` sidecar (if any). The checks run by name
     * inside the parent's fd; the unlink then goes through [visibleDirectory] (the FUSE view of the
     * parent) when given. Shared storage's FUSE daemon caches entries indefinitely and only learns of
     * changes made through it: unlinked behind its back under `/data/media`, the file keeps showing up
     * to apps (stat succeeds, open fails), so a later download of the same name refuses to replace it.
     * Whatever the FUSE unlink did, the backing entry is removed (or confirmed gone) by fd afterwards.
     */
    fun deleteImage(
        confined: ConfinedPath,
        visibleDirectory: String?,
    ) {
        withParent(confined) { parent, rootDevice ->
            val target = childPath(parent, confined.name)
            val stat = errnoAsArgument(confined) { Os.lstat(target) }
            require(OsConstants.S_ISREG(stat.st_mode)) { "not a regular file: ${confined.path}" }
            require(stat.st_dev == rootDevice) { "file is on another filesystem than ${confined.root}: ${confined.path}" }
            removeEntry(parent, confined.name, visibleDirectory)
            removeEntry(parent, "${confined.name}.sha256", visibleDirectory)
        }
    }

    private fun removeEntry(
        parent: ParcelFileDescriptor,
        name: String,
        visibleDirectory: String?,
    ) {
        if (visibleDirectory != null) {
            try {
                Os.remove("$visibleDirectory/$name")
            } catch (e: ErrnoException) {
                // ENOENT for a missing sidecar; anything else falls back to the backing path below.
                if (e.errno != OsConstants.ENOENT) Log.w(TAG, "unlink through $visibleDirectory failed: ${e.message}")
            }
        }
        try {
            Os.remove(childPath(parent, name))
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.ENOENT) throw e
        }
    }

    /** Walks [confined]'s parent directories down from its root, rejecting symlinks and mount crossings. */
    private fun <T> withParent(
        confined: ConfinedPath,
        block: (parent: ParcelFileDescriptor, rootDevice: Long) -> T,
    ): T {
        // The root itself (/data/media/0) is a system path owned by root, so it is trusted. It is opened
        // in init's mount namespace: ours is the app's, and once it is gone (an APK update) the kernel
        // would report an exported file under it as '/media/0/...', which reconcile takes for other media.
        var directory = open("$INIT_ROOT${confined.root.trimEnd('/')}", confined)
        try {
            val rootStat = Os.fstat(directory.fileDescriptor)
            require(OsConstants.S_ISDIR(rootStat.st_mode)) { "allowed root is not a directory: ${confined.root}" }
            for (component in confined.parentComponents) {
                val next = open(childPath(directory, component), confined)
                directory.close()
                directory = next
                val stat = Os.fstat(directory.fileDescriptor)
                require(OsConstants.S_ISDIR(stat.st_mode)) { "not a directory: $component in ${confined.path}" }
                require(stat.st_dev == rootStat.st_dev) { "path crosses into another filesystem: ${confined.path}" }
            }
            val rootDevice = rootStat.st_dev
            return block(directory, rootDevice)
        } finally {
            directory.close()
        }
    }

    /** The magic link pins [directory]; only [name] is looked up, inside it. Valid in this process only. */
    fun childPath(
        directory: ParcelFileDescriptor,
        name: String,
    ): String {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) { "bad file name: '$name'" }
        return "/proc/self/fd/${directory.fd}/$name"
    }

    private fun open(
        path: String,
        confined: ConfinedPath,
    ): ParcelFileDescriptor {
        val descriptor = errnoAsArgument(confined) { Os.open(path, OPEN_FLAGS, 0) }
        try {
            return ParcelFileDescriptor.dup(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    /** ELOOP/ENOTDIR mean a symlink (or non-directory) in the path; like ENOENT, that is bad input. */
    private inline fun <T> errnoAsArgument(
        confined: ConfinedPath,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: ErrnoException) {
            val reason =
                when (e.errno) {
                    OsConstants.ELOOP, OsConstants.ENOTDIR -> "path traverses a symlink or non-directory"
                    OsConstants.ENOENT -> "no such file"
                    else -> e.message
                }
            throw IllegalArgumentException("$reason: ${confined.path}", e)
        }
}

/** An open image and its normalized kernel path; closing it closes the fd. */
class ConfinedFile(
    val path: String,
    val descriptor: ParcelFileDescriptor,
    /** From the fstat of [descriptor], so it names the inode that was actually opened. */
    val identity: FileIdentity,
    /** The opened file's size (fstat), which is what the kernel sees when it opens the same inode. */
    val sizeBytes: Long,
) : AutoCloseable {
    /** Given to `lun.N/file`: the kernel resolves this magic link in our process to the same inode. */
    val procPath: String get() = "/proc/self/fd/${descriptor.fd}"

    override fun close() = descriptor.close()
}
