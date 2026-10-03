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
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method

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

    override fun save(journal: Journal) {
        val directory = checkNotNull(file.parentFile)
        if (!directory.exists()) check(directory.mkdirs()) { "cannot create $directory" }
        Os.chmod(directory.path, DIRECTORY_MODE)
        val temporary = File(directory, "${file.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(BootlegJson.encodeToString(journal).toByteArray())
            stream.fd.sync()
        }
        Os.rename(temporary.path, file.path)
        // The rename lives in the directory entry; without this a power loss can bring back the old journal.
        val directoryDescriptor = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            Os.fsync(directoryDescriptor)
        } finally {
            Os.close(directoryDescriptor)
        }
    }

    private companion object {
        const val DIRECTORY_MODE = 0b111_000_000 // 0700
    }
}

/**
 * Spawns a detached root shell that rebinds the UDC if it is still unbound after the delay.
 * It runs in its own session so it outlives this process; values are passed as positional
 * arguments, never interpolated into the script.
 */
class ShellDeadMan(
    private val udcPath: String = "${GadgetNames.GADGET_ROOT}/UDC",
) : DeadMan {
    override fun arm(
        udc: String,
        delaySeconds: Int,
    ) {
        require(UDC_NAME.matches(udc)) { "invalid UDC name: '$udc'" }
        require(delaySeconds in 1..DELAY_SECONDS_MAX) { "dead-man delay out of range: $delaySeconds" }
        ProcessBuilder("setsid", "sh", "-c", SCRIPT, "bootleg-deadman", delaySeconds.toString(), udc, udcPath)
            .redirectInput(File("/dev/null"))
            .redirectOutput(File("/dev/null"))
            .redirectError(File("/dev/null"))
            .start()
    }

    private companion object {
        const val DELAY_SECONDS_MAX = 60
        val UDC_NAME = Regex("[A-Za-z0-9._-]{1,64}")
        const val SCRIPT = "sleep \"\$1\"; [ -z \"\$(cat \"\$3\")\" ] && echo \"\$2\" > \"\$3\""
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
                ConfinedFile(confined.path, file, FileIdentity(stat.st_dev, stat.st_ino))
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
        // The root itself (/data/media/0) is a system path owned by root, so it is trusted.
        var directory = open(confined.root.trimEnd('/'), confined)
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
) : AutoCloseable {
    /** Given to `lun.N/file`: the kernel resolves this magic link in our process to the same inode. */
    val procPath: String get() = "/proc/self/fd/${descriptor.fd}"

    override fun close() = descriptor.close()
}
