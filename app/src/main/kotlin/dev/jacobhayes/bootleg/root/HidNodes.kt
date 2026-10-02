package dev.jacobhayes.bootleg.root

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.hid.DeviceNumber
import dev.jacobhayes.bootleg.core.hid.HidDeviceNodes
import dev.jacobhayes.bootleg.core.hid.HidKind
import java.io.File

/** Root side of `hidOpen`: resolves our function's `/dev/hidgN` by device number and opens it. */
object HidNodes {
    fun open(kind: HidKind): ParcelFileDescriptor {
        val devAttribute = File("${GadgetNames.GADGET_ROOT}/functions/${kind.functionName}/dev")
        check(devAttribute.exists()) { "${kind.functionName} does not exist; enable keyboard and mouse first" }
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
        val fd = Os.open(path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            // The node could have been replaced between the scan and the open.
            val opened = Os.fstat(fd)
            check(OsConstants.S_ISCHR(opened.st_mode) && DeviceNumber.fromRdev(opened.st_rdev) == expected) {
                "$path changed while opening it"
            }
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }
}
