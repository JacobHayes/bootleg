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
    const val HID_KEYBOARD_FUNCTION = "hid.bootleg_kbd"
    const val HID_MOUSE_FUNCTION = "hid.bootleg_mouse"
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
)

@Serializable
data class HidSpec(
    val keyboard: Boolean = false,
    val mouse: Boolean = false,
)

/** What the user wants exposed. LUNs must be dense: 0 until slots.size. */
@Serializable
data class Desired(
    val slots: List<SlotSpec> = emptyList(),
    val hid: HidSpec = HidSpec(),
) {
    val isEmpty: Boolean get() = slots.isEmpty() && !hid.keyboard && !hid.mouse
}

@Serializable
data class LunState(
    val file: String,
    val ro: Boolean,
    val cdrom: Boolean,
    val removable: Boolean,
    val nofua: Boolean,
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

    @Serializable data class Symlink(
        val target: String,
        val link: String,
    ) : Op

    @Serializable data class Unlink(
        val link: String,
    ) : Op
}

/** Thin filesystem seam over configfs so the executor can run against a fake in tests. */
interface ConfigFs {
    fun exists(path: String): Boolean

    fun list(path: String): List<String>

    fun read(path: String): String

    fun readLink(path: String): String?

    fun write(
        path: String,
        value: String,
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

    /** Emergency teardown: remove all Bootleg functions and rebind. */
    fun bootlegOff(): ApplyResult
}

/** HID report descriptors written to each `report_desc` of the `hid.bootleg_kbd` / `hid.bootleg_mouse` functions. Implemented in core/hid. */
interface HidDescriptorProvider {
    fun keyboard(): ByteArray

    fun mouse(): ByteArray
}
