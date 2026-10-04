package dev.jacobhayes.bootleg.root

import android.system.ErrnoException
import android.system.Os
import dev.jacobhayes.bootleg.core.gadget.CdromLimits
import dev.jacobhayes.bootleg.core.gadget.ConfigFs
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
) : ConfigFs {
    override fun exists(path: String): Boolean = File(absolute(path)).exists()

    override fun list(path: String): List<String> = File(absolute(path)).list()?.sorted() ?: emptyList()

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
        require(!path.startsWith("/") && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
            "configfs path must be relative and normalized: '$path'"
        }
        return "$gadgetRoot/$path"
    }

    private companion object {
        val LUN_FILE = Regex("functions/mass_storage\\.[^/]+/lun\\.\\d+/file")
        const val DIRECTORY_MODE = 0b111_101_101 // 0755
    }
}
