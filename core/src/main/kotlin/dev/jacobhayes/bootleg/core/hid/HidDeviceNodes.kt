package dev.jacobhayes.bootleg.core.hid

import dev.jacobhayes.bootleg.core.gadget.GadgetNames

/** One of Bootleg's two HID functions; [wireName] is what crosses the root AIDL boundary. */
enum class HidKind(
    val wireName: String,
    val functionName: String,
    val reportSizeBytes: Int,
) {
    KEYBOARD("keyboard", GadgetNames.HID_KEYBOARD_FUNCTION, HidLimits.KEYBOARD_REPORT_SIZE_BYTES),
    MOUSE("mouse", GadgetNames.HID_MOUSE_FUNCTION, HidLimits.MOUSE_REPORT_SIZE_BYTES),
    ;

    /** The report that releases every key or button this function can hold. */
    val releaseAllReport: ByteArray
        get() =
            when (this) {
                KEYBOARD -> KeyboardReport.RELEASE_ALL.encode()
                MOUSE -> MouseReport().encode()
            }

    companion object {
        fun fromWireName(name: String): HidKind? = entries.firstOrNull { it.wireName == name }
    }
}

/** A Linux character device number. */
data class DeviceNumber(
    val major: Int,
    val minor: Int,
) {
    init {
        require(major in 0..MAJOR_MAX) { "major out of range: $major" }
        require(minor in 0..MINOR_MAX) { "minor out of range: $minor" }
    }

    companion object {
        private const val MAJOR_MAX = 0xFFF
        private const val MINOR_MAX = 0xFFFFF
        private val devAttributePattern = Regex("""(\d{1,4}):(\d{1,7})""")

        /** Parses a configfs/sysfs `dev` attribute ("major:minor"). */
        fun parse(devAttribute: String): DeviceNumber {
            val match = requireNotNull(devAttributePattern.matchEntire(devAttribute.trim())) { "malformed dev attribute: '$devAttribute'" }
            return DeviceNumber(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }

        /** Decodes `st_rdev` the way glibc/bionic `major()`/`minor()` do. */
        fun fromRdev(rdev: Long): DeviceNumber {
            val major = ((rdev ushr 8) and 0xFFFL) or ((rdev ushr 32) and 0xFFFF_F000L)
            val minor = (rdev and 0xFFL) or ((rdev ushr 12) and 0xFFFF_FF00L)
            return DeviceNumber(major.toInt(), minor.toInt())
        }
    }
}

/**
 * Finds the `/dev/hidgN` node of one of our functions by device number. The index N is not
 * ours to assume: other apps (e.g. USB HID Client) may own hidg devices too.
 */
object HidDeviceNodes {
    const val DEVICE_DIRECTORY = "/dev"
    const val NODE_PREFIX = "hidg"

    /** More hidg nodes than this means something is badly wrong; refuse rather than scan forever. */
    const val NODE_COUNT_MAX = 64

    fun isCandidateName(name: String): Boolean =
        name.startsWith(NODE_PREFIX) && name.drop(NODE_PREFIX.length).let { it.isNotEmpty() && it.all(Char::isDigit) }

    /** Returns the one path in [candidates] (path -> device number of a char device) matching [expected]. */
    fun select(
        expected: DeviceNumber,
        candidates: Map<String, DeviceNumber>,
    ): String {
        require(candidates.size <= NODE_COUNT_MAX) { "${candidates.size} hidg nodes; limit is $NODE_COUNT_MAX" }
        val matches = candidates.filterValues { it == expected }.keys
        check(matches.isNotEmpty()) { "no /dev/hidg node has device number ${expected.major}:${expected.minor}" }
        check(matches.size == 1) { "several nodes share device number ${expected.major}:${expected.minor}: $matches" }
        return matches.single()
    }
}
