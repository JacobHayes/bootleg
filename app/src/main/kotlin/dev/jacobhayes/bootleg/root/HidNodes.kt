package dev.jacobhayes.bootleg.root

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.hid.DeviceNumber
import dev.jacobhayes.bootleg.core.hid.HidDeviceNodes
import dev.jacobhayes.bootleg.core.hid.HidKind
import java.io.File
import java.nio.file.Files

/**
 * An opened `/dev/hidgN`, what its function takes per report, and the binding it was opened under: the
 * inode of the node's sysfs directory, which the kernel recreates on every bind of the function (see
 * [HidRelay.Binding]).
 */
class OpenedHidNode(
    val descriptor: ParcelFileDescriptor,
    val functionName: String,
    val reportLengthBytes: Int,
    val sysfsPath: String,
    val sysfsInode: Long,
)

/** Root side of `hidOpen`: resolves our function's `/dev/hidgN` by device number and opens it. */
object HidNodes {
    private const val GADGET_ENTRY_COUNT_MAX = 256
    private const val SYSFS_CLASS_DIRECTORY = "/sys/class/hidg"

    fun open(kind: HidKind): OpenedHidNode {
        val gadget = File(GadgetNames.GADGET_ROOT)
        val functionName = kind.resolveFunction(existing = entries(File(gadget, "functions")).toSet(), linked = linkedFunctions(gadget))
        val function = File(gadget, "functions/$functionName")
        // The layout comes from the function's name; the kernel's report_length must agree.
        val reportLengthBytes = kind.reportLengthBytesOf(functionName)
        val liveLengthBytes = File(function, "report_length").readText().trim().toIntOrNull()
        check(liveLengthBytes == reportLengthBytes) { "$functionName has report_length $liveLengthBytes, expected $reportLengthBytes" }
        val devAttribute = File(function, "dev")
        check(devAttribute.exists()) { "$functionName does not exist; enable keyboard and mouse first" }
        val expected = DeviceNumber.parse(devAttribute.readText())
        val names = File(HidDeviceNodes.DEVICE_DIRECTORY).list().orEmpty().filter(HidDeviceNodes::isCandidateName)
        check(names.size <= HidDeviceNodes.NODE_COUNT_MAX) { "${names.size} hidg nodes; limit is ${HidDeviceNodes.NODE_COUNT_MAX}" }
        val candidates =
            names
                .map { "${HidDeviceNodes.DEVICE_DIRECTORY}/$it" }
                .mapNotNull { path ->
                    val stat = runCatching { Os.lstat(path) }.getOrNull() ?: return@mapNotNull null
                    if (OsConstants.S_ISCHR(stat.st_mode)) path to DeviceNumber.fromRdev(stat.st_rdev) else null
                }.toMap()
        val path = HidDeviceNodes.select(expected, candidates)
        // Taken before the open: a rebind in between then reads as a changed binding (safe), never the reverse.
        val sysfsPath = "$SYSFS_CLASS_DIRECTORY/${path.substringAfterLast('/')}"
        val sysfsInode = sysfsInode(sysfsPath) ?: error("$sysfsPath is missing; the gadget is not bound")
        val fd = Os.open(path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            // The node could have been replaced between the scan and the open.
            val opened = Os.fstat(fd)
            check(OsConstants.S_ISCHR(opened.st_mode) && DeviceNumber.fromRdev(opened.st_rdev) == expected) {
                "$path changed while opening it"
            }
            return OpenedHidNode(ParcelFileDescriptor.dup(fd), functionName, reportLengthBytes, sysfsPath, sysfsInode)
        } finally {
            Os.close(fd)
        }
    }

    /**
     * Whether the UDC is bound and each of [kinds]' functions has its device (`/sys/dev/char/M:m`
     * exists only while the function is bound). Anything unreadable counts as not bound.
     */
    fun gadgetBound(kinds: Set<HidKind>): Boolean {
        val udc = runCatching { File("${GadgetNames.GADGET_ROOT}/UDC").readText().trim() }.getOrDefault("")
        if (udc.isEmpty()) return false
        val gadget = File(GadgetNames.GADGET_ROOT)
        return kinds.all { kind ->
            runCatching {
                // The mouse's live function depends on the layout; resolved like an open.
                val functionName =
                    kind.resolveFunction(
                        existing = entries(File(gadget, "functions")).toSet(),
                        linked = linkedFunctions(gadget),
                    )
                val number = DeviceNumber.parse(File(gadget, "functions/$functionName/dev").readText())
                File("/sys/dev/char/${number.major}:${number.minor}").exists()
            }.getOrDefault(false)
        }
    }

    /** Inode of the device's sysfs directory (following the class symlink), or null while it doesn't exist. */
    fun sysfsInode(sysfsPath: String): Long? =
        try {
            Os.stat(sysfsPath).st_ino
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) null else throw e
        }

    private fun entries(directory: File): List<String> {
        val names = directory.list().orEmpty().toList()
        check(names.size <= GADGET_ENTRY_COUNT_MAX) { "${names.size} entries in $directory; limit is $GADGET_ENTRY_COUNT_MAX" }
        return names
    }

    /** Function names linked into the config (link targets, since the HAL names its links functionN). */
    private fun linkedFunctions(gadget: File): Set<String> {
        val config = File(gadget, GadgetNames.CONFIG_DIR)
        return entries(config)
            .mapNotNull { name ->
                val link = File(config, name).toPath()
                if (Files.isSymbolicLink(link)) Files.readSymbolicLink(link).fileName.toString() else null
            }.toSet()
    }
}
