package dev.jacobhayes.bootleg.core.gadget

import java.security.SecureRandom

/** Flags written to `lun.N/ro` and `lun.N/cdrom` for a mode. */
val LunMode.readOnly: Boolean get() = this != LunMode.DISK_RW
val LunMode.cdrom: Boolean get() = this == LunMode.CDROM

/**
 * What one LUN should look like. [file] is "" for an ejected or failed slot. A null [mode] leaves
 * `ro`/`cdrom` untouched (a failed slot whose mode may be what failed).
 */
data class LunTarget(
    val file: String,
    val mode: LunMode?,
)

/**
 * The `inquiry_string` Bootleg writes to each of its LUNs: vendor (8), product (16) and revision (4)
 * characters, the revision being the journal's [Journal.lunInstanceNonce]. A recreated function or
 * LUN starts with the kernel's empty value, so an empty `file` only counts as a host eject on a LUN
 * that still carries the marker. Exactly 28 characters, so the kernel's `%-28s` padding leaves it as is.
 */
object LunMarker {
    const val NONCE_LENGTH = 4
    private const val VENDOR = "Bootleg "
    private const val PRODUCT = "Virtual Drive   "
    private const val NONCE_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    private val NONCE = Regex("[0-9a-z]{$NONCE_LENGTH}")
    private val random = SecureRandom()

    fun isNonce(text: String): Boolean = NONCE.matches(text)

    fun inquiryString(nonce: String): String {
        require(isNonce(nonce)) { "LUN instance nonce must be $NONCE_LENGTH of [0-9a-z]: '$nonce'" }
        return (VENDOR + PRODUCT + nonce).also { check(it.length == INQUIRY_STRING_LENGTH) }
    }

    fun newNonce(): String = String(CharArray(NONCE_LENGTH) { NONCE_ALPHABET[random.nextInt(NONCE_ALPHABET.length)] })

    /** The kernel's INQUIRY_STRING_LEN minus its terminator. */
    const val INQUIRY_STRING_LENGTH = 28
}

/**
 * Where Bootleg's functions sit in the config. The kernel numbers a config's interfaces in link order,
 * and Windows binds each child driver by interface number (`USB\VID_x&PID_y&MI_nn`), so a function
 * must never move to another function's number. The [PREFIX] is linked ahead of every foreign link
 * and never relinked: RNDIS (interfaces 0-1, the only place Windows starts it), the keyboard (2) and
 * the mouse (3). Disks follow it ([DISKS]).
 */
object LinkOrder {
    val PREFIX =
        listOf(
            GadgetNames.RNDIS_FUNCTION,
            GadgetNames.HID_KEYBOARD_FUNCTION,
            GadgetNames.HID_MOUSE_FUNCTION,
        )

    /**
     * The one rule for disk placement: mass storage is the last link of ours, after the prefix, wherever
     * configfs appends it (after the HAL's links); relinked as needed, so its and the HAL's interface
     * numbers may shift. Replace this to give disks fixed numbers.
     */
    const val DISKS = GadgetNames.MASS_STORAGE_FUNCTION

    val CANONICAL = PREFIX + DISKS

    /** Bootleg's linked functions in link order ([configLinks] is in link order), by target. */
    fun owned(configLinks: Map<String, String>): List<String> = configLinks.filterKeys(GadgetPaths::isOwnedName).values.toList()

    /** Ours are a subsequence of [CANONICAL] in that order, each linked under its own name. */
    fun isCanonical(configLinks: Map<String, String>): Boolean {
        val owned = owned(configLinks)
        val ownedNames = configLinks.keys.filter(GadgetPaths::isOwnedName)
        return owned == ownedNames && owned.all { it in CANONICAL } && owned == owned.sortedBy(CANONICAL::indexOf)
    }

    /** The prefix is the first three links of the whole config, foreign ones included. */
    fun prefixFirst(configLinks: Map<String, String>): Boolean =
        configLinks.entries.take(PREFIX.size).map { it.key to it.value } == PREFIX.map { it to it }
}

/**
 * The concrete state the planner drives toward: [Desired] with slot intents applied. [prefix] links
 * the prefix ([LinkOrder.PREFIX]); HID on or off doesn't change the gadget. [usbRoundTrip]: an
 * establishment's USB mode round trip is unfinished ([Journal.usbFunctionsToRestore]). [lunMarker], if set, is
 * the `inquiry_string` every LUN must carry (see [LunMarker]).
 */
data class GadgetTarget(
    val luns: List<LunTarget>,
    val prefix: Boolean,
    val lunMarker: String? = null,
    val usbRoundTrip: Boolean = false,
) {
    init {
        require(luns.size <= GadgetLimits.LUN_COUNT_MAX) { "at most ${GadgetLimits.LUN_COUNT_MAX} LUNs, got ${luns.size}" }
        require(lunMarker == null || lunMarker.length == LunMarker.INQUIRY_STRING_LENGTH) { "bad LUN marker: '$lunMarker'" }
        require(prefix || luns.isEmpty()) { "disks need the prefix" }
    }

    /** Owned functions to expose, in creation and link order ([LinkOrder]). */
    val functions: List<String>
        get() =
            buildList {
                if (prefix) addAll(LinkOrder.PREFIX)
                if (luns.isNotEmpty()) add(LinkOrder.DISKS)
            }

    companion object {
        val EMPTY = GadgetTarget(emptyList(), prefix = false)

        fun of(desired: Desired): GadgetTarget = of(Journal(desired = desired))

        fun of(journal: Journal): GadgetTarget {
            val slotLuns =
                journal.desired.slots.sortedBy { it.lun }.map { slot ->
                    when (journal.slotIntents[slot.lun] ?: SlotIntent.EXPORTED) {
                        SlotIntent.EXPORTED -> LunTarget(slot.backingPath, slot.mode)
                        SlotIntent.HOST_EJECTED, SlotIntent.USER_EJECTED -> LunTarget("", slot.mode)
                        SlotIntent.FAILED -> LunTarget("", null)
                    }
                }
            return GadgetTarget(
                luns = slotLuns,
                prefix = journal.desired.wantsPrefix,
                lunMarker = journal.lunInstanceNonce.takeIf { it.isNotEmpty() }?.let(LunMarker::inquiryString),
                usbRoundTrip = journal.usbFunctionsToRestore != null,
            )
        }
    }
}
