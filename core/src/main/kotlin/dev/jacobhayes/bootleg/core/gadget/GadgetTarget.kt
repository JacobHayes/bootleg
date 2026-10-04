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
 * The order Bootleg links its functions in. The kernel numbers a config's interfaces in link order,
 * and Windows binds each child driver by interface number (`USB\VID_x&PID_y&MI_nn`), so a function
 * must never move to another function's number: mass storage first, then the keyboard, then the mouse
 * (either layout, same place).
 */
object LinkOrder {
    val CANONICAL =
        listOf(
            GadgetNames.MASS_STORAGE_FUNCTION,
            GadgetNames.HID_KEYBOARD_FUNCTION,
            GadgetNames.HID_WHEEL_MOUSE_FUNCTION,
            GadgetNames.HID_MOUSE_FUNCTION,
        )

    /** Bootleg's linked functions in link order ([configLinks] is in link order), by target. */
    fun owned(configLinks: Map<String, String>): List<String> = configLinks.filterKeys(GadgetPaths::isOwnedName).values.toList()

    fun isCanonical(configLinks: Map<String, String>): Boolean {
        val owned = owned(configLinks)
        return owned.all { it in CANONICAL } && owned == owned.sortedBy(CANONICAL::indexOf)
    }
}

/**
 * The concrete state the planner drives toward: [Desired] with slot intents applied. [lunMarker], if
 * set, is the `inquiry_string` every LUN must carry (see [LunMarker]).
 */
data class GadgetTarget(
    val luns: List<LunTarget>,
    val hid: HidSpec,
    val lunMarker: String? = null,
) {
    init {
        require(luns.size <= GadgetLimits.LUN_COUNT_MAX) { "at most ${GadgetLimits.LUN_COUNT_MAX} LUNs, got ${luns.size}" }
        require(lunMarker == null || lunMarker.length == LunMarker.INQUIRY_STRING_LENGTH) { "bad LUN marker: '$lunMarker'" }
    }

    /** Owned functions to expose, in creation and link order ([LinkOrder]). */
    val functions: List<String>
        get() =
            buildList {
                if (luns.isNotEmpty()) add(GadgetNames.MASS_STORAGE_FUNCTION)
                if (hid.keyboard) add(GadgetNames.HID_KEYBOARD_FUNCTION)
                if (hid.mouse) add(hid.mouseLayout.functionName)
            }

    companion object {
        val EMPTY = GadgetTarget(emptyList(), HidSpec())

        fun of(desired: Desired): GadgetTarget = of(Journal(desired = desired))

        /**
         * While HID is on without a disk, mass storage stays linked with one empty LUN ([PLACEHOLDER_LUN]):
         * the keyboard and mouse keep their interface numbers whether or not disks are added.
         */
        val PLACEHOLDER_LUN = LunTarget("", LunMode.DISK_RO)

        fun of(journal: Journal): GadgetTarget {
            val hid = journal.desired.hid
            val slotLuns =
                journal.desired.slots.sortedBy { it.lun }.map { slot ->
                    when (journal.slotIntents[slot.lun] ?: SlotIntent.EXPORTED) {
                        SlotIntent.EXPORTED -> LunTarget(slot.backingPath, slot.mode)
                        SlotIntent.HOST_EJECTED, SlotIntent.USER_EJECTED -> LunTarget("", slot.mode)
                        SlotIntent.FAILED -> LunTarget("", null)
                    }
                }
            return GadgetTarget(
                luns = slotLuns.ifEmpty { if (hid.keyboard || hid.mouse) listOf(PLACEHOLDER_LUN) else emptyList() },
                hid = hid,
                lunMarker = journal.lunInstanceNonce.takeIf { it.isNotEmpty() }?.let(LunMarker::inquiryString),
            )
        }
    }
}
