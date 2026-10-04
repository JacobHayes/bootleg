package dev.jacobhayes.bootleg.root

import android.system.ErrnoException
import android.system.Os
import dev.jacobhayes.bootleg.core.gadget.CdromLimits
import dev.jacobhayes.bootleg.core.gadget.ConfigFs
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.UdcStuckException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [ConfigFs] over the real configfs gadget, run as root. Paths are relative to [gadgetRoot].
 *
 * Writes to `lun.N/file` open the image through [ConfinedFiles] (no symlink anywhere below the
 * allowed root, checked with fstat) and hand the kernel `/proc/self/fd/N`. The kernel opens the
 * same inode, so a swap between the check and the open has no effect.
 */
class RootConfigFs(
    private val gadgetRoot: String = GadgetNames.GADGET_ROOT,
    private val allowedRoots: List<String> = ImagePaths.allowedRoots,
    /**
     * Called (and must return at once) when a UDC write hasn't returned within [udcWriteWaitMs]: a
     * FunctionFS server holding its function's mutex blocks `functionfs_unbind`, so killing it (the
     * RNDIS daemon) lets the write finish.
     */
    private val onUdcStuck: () -> Unit = {},
    private val udcWriteWaitMs: Long = UDC_WRITE_WAIT_MS,
    private val udcWriteDeadlineMs: Long = UDC_WRITE_DEADLINE_MS,
) : ConfigFs {
    /** A UDC write still pending in the kernel past its deadline: until it returns, every access fails. */
    @Volatile private var pendingUdcWrite: Thread? = null

    val udcWriteStuck: Boolean get() = pendingUdcWrite?.isAlive == true

    override fun exists(path: String): Boolean = File(absolute(path)).exists()

    override fun list(path: String): List<String> = File(absolute(path)).list()?.sorted() ?: emptyList()

    // Configfs adds each new entry at the head of its directory's list, so readdir shows links newest first.
    override fun listInLinkOrder(path: String): List<String> = File(absolute(path)).list()?.reversed() ?: emptyList()

    override fun read(path: String): String = File(absolute(path)).readText().trimEnd('\n')

    override fun readLink(path: String): String? =
        try {
            Os.readlink(absolute(path))
        } catch (_: ErrnoException) {
            null
        }

    override fun write(
        path: String,
        value: String,
    ) {
        require('\n' !in value) { "configfs values are single-line: $path" }
        if (value.isNotEmpty() && LUN_FILE.matches(path)) {
            writeBackingFile(path, value)
        } else if (path == UDC) {
            writeUdc(value)
        } else {
            // An empty write(2) never reaches the kernel; a lone newline clears the attribute.
            writeBytes(path, (value.ifEmpty { "\n" }).toByteArray())
        }
    }

    override fun writeBytes(
        path: String,
        bytes: ByteArray,
    ) {
        require(bytes.isNotEmpty()) { "empty write to $path" }
        FileOutputStream(absolute(path)).use { it.write(bytes) }
    }

    /**
     * A UDC write can hang in the kernel (see [onUdcStuck]), so it runs on its own thread with one
     * deadline, [udcWriteDeadlineMs]. A write still pending then is left behind (nothing interrupts
     * uninterruptible sleep), and every access fails at once with [UdcStuckException] until it returns:
     * reading or writing the gadget would hang behind it.
     */
    private fun writeUdc(value: String) {
        val file = absolute(UDC)
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        val writer =
            thread(name = "udc-write", isDaemon = true) {
                try {
                    FileOutputStream(file).use { it.write(value.ifEmpty { "\n" }.toByteArray()) }
                } catch (e: Throwable) {
                    failure.set(e)
                } finally {
                    done.countDown()
                }
            }
        if (!done.await(udcWriteWaitMs, TimeUnit.MILLISECONDS)) {
            onUdcStuck()
            if (!done.await(udcWriteDeadlineMs - udcWriteWaitMs, TimeUnit.MILLISECONDS)) {
                pendingUdcWrite = writer
                throw UdcStuckException("UDC write '$value' hasn't returned after ${udcWriteDeadlineMs}ms")
            }
        }
        failure.get()?.let { throw it as? IOException ?: IOException(it.message, it) }
    }

    override fun mkdir(path: String) = errnoAsIo { Os.mkdir(absolute(path), DIRECTORY_MODE) }

    override fun rmdir(path: String) = errnoAsIo { Os.remove(absolute(path)) }

    override fun symlink(
        target: String,
        link: String,
    ) = errnoAsIo { Os.symlink(absolute(target), absolute(link)) }

    override fun unlink(link: String) = errnoAsIo { Os.remove(absolute(link)) }

    /** The controller treats IOException as a scoped op failure; anything else is a bug. */
    private inline fun <T> errnoAsIo(block: () -> T): T =
        try {
            block()
        } catch (e: ErrnoException) {
            throw IOException(e.message, e)
        }

    private fun writeBackingFile(
        path: String,
        imagePath: String,
    ) {
        val image =
            try {
                ConfinedFiles.openImage(imagePath, allowedRoots)
            } catch (e: IllegalArgumentException) {
                throw IOException(e.message, e)
            } catch (e: ErrnoException) {
                throw IOException(e.message, e)
            }
        image.use {
            // The kernel would truncate (or refuse) this very inode as a CD-ROM: check the fd it is given, not a path.
            if (read(path.substringBeforeLast('/') + "/cdrom") == "1") {
                CdromLimits.problem(it.path.substringAfterLast('/'), it.sizeBytes)?.let { problem -> throw IOException(problem) }
            }
            // The kernel resolves this magic link in our process context and keeps its own reference.
            writeBytes(path, it.procPath.toByteArray())
        }
        val exported = read(path)
        if (exported != image.path) throw IOException("kernel exported '$exported', expected '${image.path}'")
    }

    private fun absolute(path: String): String {
        if (udcWriteStuck) throw UdcStuckException("a UDC write is still pending in the kernel; USB changes wait until it returns")
        require(!path.startsWith("/") && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
            "configfs path must be relative and normalized: '$path'"
        }
        return "$gadgetRoot/$path"
    }

    companion object {
        private const val UDC = "UDC"
        const val UDC_WRITE_WAIT_MS = 3_000L
        const val UDC_WRITE_DEADLINE_MS = 5_000L
        private val LUN_FILE = Regex("functions/mass_storage\\.[^/]+/lun\\.\\d+/file")
        private const val DIRECTORY_MODE = 0b111_101_101 // 0755
    }
}
