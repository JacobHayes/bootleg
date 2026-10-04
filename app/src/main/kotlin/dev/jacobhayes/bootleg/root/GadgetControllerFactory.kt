package dev.jacobhayes.bootleg.root

import dev.jacobhayes.bootleg.core.gadget.Clock
import dev.jacobhayes.bootleg.core.gadget.ConfigFs
import dev.jacobhayes.bootleg.core.gadget.DeadMan
import dev.jacobhayes.bootleg.core.gadget.DefaultGadgetController
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetInvariants
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.HidDescriptorProvider
import dev.jacobhayes.bootleg.core.gadget.HidNodeGuard
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.JournalStore
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.MouseLayout
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import dev.jacobhayes.bootleg.core.gadget.UsbProps

/** The single construction point for the gadget controller used by the root service. */
fun createGadgetController(
    fs: ConfigFs,
    props: UsbProps,
    journalStore: JournalStore,
    deadMan: DeadMan,
    clock: Clock,
    hidDescriptors: HidDescriptorProvider,
    hidNodes: HidNodeGuard,
    allowedRoots: List<String>,
    fileSizeBytes: (path: String) -> Long?,
): DefaultGadgetController =
    DefaultGadgetController(fs, props, journalStore, deadMan, clock, hidDescriptors, hidNodes, allowedRoots, fileSizeBytes = fileSizeBytes)

/** Which file a path names: two hard links to one inode are the same file. */
data class FileIdentity(
    val device: Long,
    val inode: Long,
)

/** An image opened for validation: its normalized kernel path and identity. */
data class OpenedImage(
    val path: String,
    val identity: FileIdentity,
)

/**
 * [desired] with every backing path replaced by its normalized kernel path, as [open] (the confined,
 * symlink-refusing open) resolves it. Rejects the whole set before anything is applied when one file
 * would be exported more than once with any copy writable, by path and by `(st_dev, st_ino)`.
 */
fun canonicalizeDesired(
    desired: Desired,
    open: (path: String) -> OpenedImage,
): Desired {
    val opened = desired.slots.associate { slot -> slot.lun to open(slot.backingPath) }
    val canonical = desired.copy(slots = desired.slots.map { it.copy(backingPath = opened.getValue(it.lun).path) })
    GadgetInvariants.requireNoDuplicateWritable(canonical.slots) { it.backingPath }
    GadgetInvariants.requireNoDuplicateWritable(canonical.slots) { opened.getValue(it.lun).identity }
    return canonical
}

/**
 * The live mass-storage export as a [Desired], for adopting it when there is no journal (first
 * start, or the journal was lost). Returns null when there is nothing to adopt or the live LUNs
 * can't be expressed (an empty LUN has no backing path to put in a slot).
 */
fun adoptableDesired(state: GadgetState): Desired? {
    // A linked mouse function's name says its layout; with both linked (a crash mid-swap) keep the wheel.
    val mouseLayouts = MouseLayout.entries.filter { it.functionName in state.configLinks }
    val hid =
        HidSpec(
            keyboard = GadgetNames.HID_KEYBOARD_FUNCTION in state.configLinks,
            mouse = mouseLayouts.isNotEmpty(),
            mouseLayout = mouseLayouts.firstOrNull() ?: MouseLayout.WHEEL,
        )
    val linkedLuns = if (GadgetNames.MASS_STORAGE_FUNCTION in state.configLinks) state.luns else emptyMap()
    // One empty LUN next to HID is the placeholder that keeps HID's interface numbers, not a slot.
    val placeholder = (hid.keyboard || hid.mouse) && linkedLuns.size == 1 && linkedLuns[0]?.file == ""
    val luns = if (placeholder) emptyMap() else linkedLuns
    if (luns.keys.sorted() != luns.keys.indices.toList() || luns.values.any { it.file.isEmpty() }) return null
    if (luns.isEmpty() && !hid.keyboard && !hid.mouse) return null
    val slots =
        luns.toSortedMap().map { (lun, lunState) ->
            val mode =
                when {
                    lunState.cdrom -> LunMode.CDROM
                    lunState.ro -> LunMode.DISK_RO
                    else -> LunMode.DISK_RW
                }
            SlotSpec(lun, lunState.file, mode)
        }
    return Desired(slots = slots, hid = hid)
}
