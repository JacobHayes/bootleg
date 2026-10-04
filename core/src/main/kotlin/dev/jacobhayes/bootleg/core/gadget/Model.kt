package dev.jacobhayes.bootleg.core.gadget

import kotlinx.serialization.Serializable

/** Hard limits (TigerStyle: every resource is bounded). */
object GadgetLimits {
    const val LUN_COUNT_MAX = 8
    const val REPLAN_COUNT_MAX = 3
    const val RECONCILE_ATTEMPT_COUNT_MAX = 5
    const val RECONCILE_INTERVAL_MS_MIN = 2_000L
    const val DEAD_MAN_REBIND_DELAY_S = 15
}

/** Names Bootleg owns inside the gadget. Everything else in configfs is foreign and read-only to us. */
object GadgetNames {
    const val GADGET_ROOT = "/config/usb_gadget/g1"
    const val CONFIG_DIR = "configs/b.1"
    const val MASS_STORAGE_FUNCTION = "mass_storage.bootleg"

    /** The RNDIS network function: FunctionFS, served by the `bootleg-rndis` daemon. */
    const val RNDIS_FUNCTION = "ffs.bootleg_rndis"
    const val HID_KEYBOARD_FUNCTION = "hid.bootleg_kbd"

    /** The scroll-wheel mouse (see [HidFunctionSpec.MOUSE_REPORT_LENGTH_BYTES]). */
    const val HID_MOUSE_FUNCTION = "hid.bootleg_mouse_wheel"
    const val OWNED_MARKER = "bootleg"
}

@Serializable
enum class LunMode { CDROM, DISK_RO, DISK_RW }

/** One exposed disk. [backingPath] is what gets written into `lun.N/file`. */
@Serializable
data class SlotSpec(
    val lun: Int,
    val backingPath: String,
    val mode: LunMode,
    /** [mode] came from the Auto rule ([LunModes.auto]), not the user's choice, so restore may pick another one. */
    val auto: Boolean = false,
)

@Serializable
data class HidSpec(
    val keyboard: Boolean = false,
    val mouse: Boolean = false,
)

/**
 * What the user wants exposed. LUNs must be dense: 0 until slots.size. [prefix] keeps Bootleg's fixed
 * prefix (RNDIS, keyboard, mouse; see [LinkOrder]) linked while nothing else is wanted; any slot or
 * HID input also wants it. Only Bootleg-off clears it.
 */
@Serializable
data class Desired(
    val slots: List<SlotSpec> = emptyList(),
    val hid: HidSpec = HidSpec(),
    val prefix: Boolean = false,
) {
    val isEmpty: Boolean get() = slots.isEmpty() && !hid.keyboard && !hid.mouse && !prefix

    /** Whether Bootleg's prefix should be linked. */
    val wantsPrefix: Boolean get() = !isEmpty
}

/**
 * The RNDIS function's daemon: [ABSENT] (not mounted), [HELD] (ep0 open, no descriptors: a config with
 * the function linked can't bind), [READY] (serving), or [DEAD] (mounted, nobody holds ep0: linked, it
 * makes every bind fail).
 */
@Serializable
enum class RndisState { ABSENT, HELD, READY, DEAD }

@Serializable
data class LunState(
    val file: String,
    val ro: Boolean,
    val cdrom: Boolean,
    val removable: Boolean,
    val nofua: Boolean,
    /**
     * `inquiry_string`, or null if the kernel has no such attribute. Bootleg writes a marker carrying
     * [Journal.lunInstanceNonce] into it, so a LUN it configured can be told apart from a recreated one.
     */
    val inquiryString: String? = null,
)

/** A snapshot of the live configfs gadget, as read by [GadgetReader]. */
@Serializable
data class GadgetState(
    /** Bound UDC name, or "" when unbound. */
    val udc: String,
    /** Names of directories under functions/. */
    val functions: Set<String>,
    /** Link name in configs/b.1 -> target function name. */
    val configLinks: Map<String, String>,
    /** LUN state of mass_storage.bootleg, index -> state. Empty if the function doesn't exist. */
    val luns: Map<Int, LunState>,
    /** Value of sys.usb.state at read time, for the "HAL is quiet" check. */
    val usbState: String = "",
    val usbConfig: String = "",
    val rndis: RndisState = RndisState.ABSENT,
)

/** Primitive configfs mutations. Paths are relative to [GadgetNames.GADGET_ROOT]. */
@Serializable
sealed interface Op {
    @Serializable data object Unbind : Op

    @Serializable data class Bind(
        val udc: String,
    ) : Op

    @Serializable data class Mkdir(
        val path: String,
    ) : Op

    @Serializable data class Rmdir(
        val path: String,
    ) : Op

    @Serializable data class Write(
        val path: String,
        val value: String,
    ) : Op

    /** Binary attribute write (HID `report_desc`). */
    @Serializable class WriteBytes(
        val path: String,
        val bytes: ByteArray,
    ) : Op {
        override fun equals(other: Any?): Boolean = other is WriteBytes && path == other.path && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()

        override fun toString(): String = "WriteBytes(path=$path, sizeBytes=${bytes.size})"
    }

    @Serializable data class Symlink(
        val target: String,
        val link: String,
    ) : Op

    @Serializable data class Unlink(
        val link: String,
    ) : Op

    /** Mounts the RNDIS function (made by [Mkdir]) if needed and starts its daemon held. */
    @Serializable data object StartRndis : Op

    /** Has the held daemon write its descriptors: the function can bind from now on. */
    @Serializable data object ReleaseRndis : Op

    /** Stops the daemon of the unlinked RNDIS function and unmounts it, before its [Rmdir]. */
    @Serializable data object StopRndis : Op

    /**
     * Has the USB HAL relink its own functions (a round trip through another USB mode and back, see
     * [Journal.usbFunctionsToRestore]), so they come after Bootleg's links. Only while a held RNDIS function
     * keeps the config unbindable, so the host never sees the layout in between.
     */
    @Serializable data object RelinkForeign : Op
}

/** The daemon serving [GadgetNames.RNDIS_FUNCTION] (root: a process; tests: a fake). Each call throws IOException. */
interface RndisDaemon {
    /** Cheap: read before every op. */
    fun state(): RndisState

    /** Mounts the function if needed and starts the daemon held; returns once it reports held. */
    fun start()

    /** Has the held daemon write its descriptors; returns once it has. */
    fun release()

    /** Stops the daemon, if any, and unmounts. */
    fun stop()
}

/** Android's USB function setting (`UsbManager`'s `FUNCTION_*` mask), which the HAL applies. */
interface UsbFunctions {
    fun current(): Long

    /** Asks for [functions]; the HAL re-plugs USB asynchronously. Throws IOException. */
    fun set(functions: Long)

    companion object {
        const val MTP = 1L shl 2
        const val PTP = 1L shl 4

        /** Android's own USB tethering (its RNDIS or NCM function). */
        const val TETHERING = (1L shl 5) or (1L shl 10)
    }
}

/**
 * A UDC write that didn't return within its deadline is still pending in the kernel. Until it returns,
 * every configfs access fails with this at once: reading or writing the gadget would hang behind it.
 */
class UdcStuckException(
    message: String,
) : java.io.IOException(message)

/** Thin filesystem seam over configfs so the executor can run against a fake in tests. */
interface ConfigFs {
    fun exists(path: String): Boolean

    fun list(path: String): List<String>

    /**
     * A config directory's entries with its links in the order they were linked, oldest first: the
     * composite's interface order. Configfs lists a config's entries newest first.
     */
    fun listInLinkOrder(path: String): List<String>

    fun read(path: String): String

    fun readLink(path: String): String?

    fun write(
        path: String,
        value: String,
    )

    fun writeBytes(
        path: String,
        bytes: ByteArray,
    )

    fun mkdir(path: String)

    fun rmdir(path: String)

    fun symlink(
        target: String,
        link: String,
    )

    fun unlink(link: String)
}

/** Per-slot lifecycle, persisted in the journal. */
@Serializable
enum class SlotIntent { EXPORTED, HOST_EJECTED, USER_EJECTED, FAILED }

/**
 * Any non-IDLE phase tells reconcile a transition of ours was in flight. UNBINDING covers a whole
 * switch (unbind through bind) so nothing is written to the journal inside the unbound window;
 * BINDING is no longer written but stays readable in old journals.
 */
@Serializable
enum class TransitionPhase { IDLE, UNBINDING, MUTATING, BINDING }

/** Durable record of what Bootleg wants and what it was in the middle of doing. */
@Serializable
data class Journal(
    val desired: Desired = Desired(),
    val slotIntents: Map<Int, SlotIntent> = emptyMap(),
    val slotFailures: Map<Int, String> = emptyMap(),
    val phase: TransitionPhase = TransitionPhase.IDLE,
    /** UDC name to rebind to if a transition is interrupted. */
    val udc: String = "",
    /**
     * Identifies the LUNs Bootleg configured (see [LunMarker]): a LUN only counts as host-ejected while
     * its `inquiry_string` still carries this nonce. Empty in journals written before the marker existed.
     */
    val lunInstanceNonce: String = "",
    /** Per slot, why it is exported differently from what was asked (a CD-ROM restored as a read-only disk). */
    val slotNotes: Map<Int, String> = emptyMap(),
    /**
     * Android's USB functions before prefix establishment's round trip: set before it changes the mode,
     * cleared once the prefix is served after it, so a root process that dies in between leaves the
     * round trip to the next one.
     */
    val usbFunctionsToRestore: Long? = null,
)

/** Durable journal storage (app: a file written atomically; tests: in-memory). */
interface JournalStore {
    fun load(): Journal

    fun save(journal: Journal)
}

/** Arms an independent rebind that fires if the UDC is still unbound after the delay. */
interface DeadMan {
    fun arm(
        udc: String,
        delaySeconds: Int,
    )

    /** The transition is over: watchdogs armed so far do nothing when they fire. */
    fun disarm() {}
}

/** Reads Android USB properties (sys.usb.state / sys.usb.config). */
interface UsbProps {
    fun usbState(): String

    fun usbConfig(): String
}

interface Clock {
    fun nowMs(): Long

    fun sleepMs(durationMs: Long)
}

@Serializable
sealed interface ApplyResult {
    @Serializable data class Applied(
        val state: GadgetState,
        val reenumerated: Boolean,
    ) : ApplyResult

    /** Some slots failed; the rest are exported. */
    @Serializable data class Partial(
        val state: GadgetState,
        val slotFailures: Map<Int, String>,
    ) : ApplyResult

    /** Gadget could not be brought to a usable state; Bootleg-off was performed. */
    @Serializable data class Failed(
        val reason: String,
    ) : ApplyResult

    /** HAL never went quiet / too many attempts; user attention needed. */
    @Serializable data class NeedsAttention(
        val reason: String,
    ) : ApplyResult
}

/** The gadget control surface used by the app's root process. Implemented in core (pure + ConfigFs). */
interface GadgetController {
    fun read(): GadgetState

    fun apply(desired: Desired): ApplyResult

    fun eject(
        lun: Int,
        force: Boolean,
    ): ApplyResult

    /** Called on ACTION_USB_STATE / service start: finish interrupted transitions, re-apply drift. */
    fun reconcile(): ApplyResult

    /** Sets up the prefix (now wanted) without exporting disks, for tethering; null when there is nothing to do. */
    fun establishPrefix(): ApplyResult? = null

    /** Emergency teardown: remove all Bootleg functions and rebind. */
    fun bootlegOff(): ApplyResult
}

/** HID report descriptors written to each `report_desc` of Bootleg's keyboard and mouse functions. Implemented in core/hid. */
interface HidDescriptorProvider {
    fun keyboard(): ByteArray

    fun mouse(): ByteArray
}
