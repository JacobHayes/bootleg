package dev.jacobhayes.bootleg.ui.drives

import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.DefaultGadgetController
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetLimits
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.LunModes
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import dev.jacobhayes.bootleg.core.gadget.TetherMode
import dev.jacobhayes.bootleg.core.tether.TetherResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A slot as edited in the UI. [modeOverride] null means Auto ([LunModes.auto]: by extension and size).
 * [fresh] marks an image the user (re)picked in this draft: after Apply it is inserted even if the
 * same image was ejected in that slot before, because the core keeps an eject when the slot spec
 * doesn't change.
 */
data class DraftSlot(
    val path: String,
    val modeOverride: LunMode? = null,
    val fresh: Boolean = false,
) {
    /** The mode it is exported with; [sizeBytes] (null if unknown) decides whether an ISO fits a CD-ROM. */
    fun mode(sizeBytes: Long?): LunMode = modeOverride ?: LunModes.auto(path, sizeBytes)

    val displayName: String get() = path.substringAfterLast('/')
}

data class DrivesUiState(
    val slots: List<DraftSlot> = emptyList(),
    /** True when [slots] differ from what's applied. */
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    /** LUN whose normal eject the host refused; the UI offers Force eject for it. */
    val forceEjectOffer: Int? = null,
)

/**
 * Holds the slot draft across screens and config changes. The draft follows the journal until the
 * user edits it; Apply turns it into a [Desired] (dense LUNs, HID untouched) and hands it to the backend.
 */
class DrivesController(
    private val backend: BootlegBackend,
    private val scope: CoroutineScope,
) {
    private val draft = MutableStateFlow<List<DraftSlot>?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val forceEjectOffer = MutableStateFlow<Int?>(null)

    /** LUNs force-ejected since the last apply: macOS only sees their media again after USB reconnects. */
    private val forceEjected = mutableSetOf<Int>()

    val state: StateFlow<DrivesUiState> =
        combine(draft, backend.journal, busy, message, forceEjectOffer) { draft, journal, busy, message, offer ->
            val applied = slotsOf(journal)
            val slots = draft ?: applied
            DrivesUiState(slots, dirty = draft != null && draft != applied, busy = busy, message = message, forceEjectOffer = offer)
        }.stateIn(scope, SharingStarted.Eagerly, DrivesUiState())

    val canAddSlot: Boolean get() = state.value.slots.size < GadgetLimits.LUN_COUNT_MAX

    /** The image's size from the library, or null for a file outside it (root's size check still guards a CD-ROM). */
    fun sizeOf(path: String): Long? =
        backend.images.value
            .firstOrNull { it.path == path }
            ?.sizeBytes

    /** [modeOverride]: a new flash drive is made to be written, so it goes in writable. */
    fun addSlot(
        path: String,
        modeOverride: LunMode? = null,
    ): Boolean {
        if (!canAddSlot) return false
        editDraft { it + DraftSlot(path, modeOverride, fresh = true) }
        return true
    }

    fun replaceImage(
        index: Int,
        path: String,
        modeOverride: LunMode? = null,
    ) = editDraft { slots -> slots.mapIndexed { i, slot -> if (i == index) DraftSlot(path, modeOverride, fresh = true) else slot } }

    fun setMode(
        index: Int,
        modeOverride: LunMode?,
    ) = editDraft { slots -> slots.mapIndexed { i, slot -> if (i == index) slot.copy(modeOverride = modeOverride) else slot } }

    fun removeSlot(index: Int) = editDraft { slots -> slots.filterIndexed { i, _ -> i != index } }

    fun discardDraft() {
        draft.value = null
    }

    fun showMessage(text: String) {
        message.value = text
    }

    fun clearMessage() {
        message.value = null
    }

    fun apply() =
        run("Applying") {
            val slots = state.value.slots
            val result = backend.apply(desiredFor(slots, backend.journal.value, ::sizeOf))
            if (result is ApplyResult.Applied && result.reenumerated) forceEjected.clear()
            draft.value = null
            reinsertFreshSlots(slots, result) ?: result
        }

    /**
     * Re-inserts slots the user picked again in this draft that are still ejected after [applied]
     * (same image as before, so the core kept the eject). Returns the first failure, else the last
     * re-insert's result, or null when nothing needed it.
     */
    private suspend fun reinsertFreshSlots(
        slots: List<DraftSlot>,
        applied: ApplyResult,
    ): ApplyResult? {
        if (applied !is ApplyResult.Applied && applied !is ApplyResult.Partial) return null
        var last: ApplyResult? = null
        for ((lun, slot) in slots.withIndex()) {
            if (!slot.fresh || backend.journal.value.slotIntents[lun] !in EJECTED_INTENTS) continue
            last = backend.reinsert(lun)
            if (last !is ApplyResult.Applied) return last
        }
        return last
    }

    /** Adds the image as a new slot and applies straight away (Library's "Expose"). */
    fun expose(path: String): Boolean {
        if (!addSlot(path)) {
            message.value = "All ${GadgetLimits.LUN_COUNT_MAX} slots are in use"
            return false
        }
        apply()
        return true
    }

    fun eject(
        lun: Int,
        force: Boolean,
    ) = run(if (force) "Force ejecting" else "Ejecting") {
        forceEjectOffer.value = null
        backend.eject(lun, force).also {
            offerForceEjectIfPrevented(lun, it)
            if (force && it is ApplyResult.Applied) forceEjected += lun
        }
    }

    fun reinsert(lun: Int) =
        run("Re-inserting") {
            backend.reinsert(lun).also {
                if (it is ApplyResult.Applied && forceEjected.remove(lun)) message.value = REINSERTED_AFTER_FORCE_EJECT
            }
        }

    /** Stores the mode; tethering that is on switches over, and a refused switch keeps the old mode. */
    fun setUsbTetherMode(mode: TetherMode) {
        val previous = backend.usbTetherMode.value
        if (backend.usbTetheringBusy.value || previous == mode) return
        backend.setUsbTetherMode(mode)
        if (backend.usbTethering.value == true) setUsbTethering(true) { if (it is TetherResult.Refused) backend.setUsbTetherMode(previous) }
    }

    /** Turns USB tethering on (over the mode) or off; the root side refuses a mode change while a disk is exposed. */
    fun setUsbTethering(
        on: Boolean,
        afterResult: (TetherResult) -> Unit = {},
    ) {
        if (backend.usbTetheringBusy.value) return
        message.value = null
        scope.launch {
            message.value =
                try {
                    describe(backend.setUsbTethering(on).also(afterResult))
                } catch (error: Exception) {
                    "USB tethering failed: ${error.message ?: error::class.simpleName}"
                }
        }
    }

    fun dismissForceEjectOffer() {
        forceEjectOffer.value = null
    }

    private fun offerForceEjectIfPrevented(
        lun: Int,
        result: ApplyResult,
    ) {
        if (result is ApplyResult.NeedsAttention && result.reason.startsWith(EJECT_PREVENTED_REASON)) forceEjectOffer.value = lun
    }

    fun ejectAll() =
        run("Ejecting all") {
            val luns =
                backend.journal.value.desired.slots
                    .map { it.lun }
            var last: ApplyResult? = null
            for (lun in luns) {
                last = backend.eject(lun, force = false)
                offerForceEjectIfPrevented(lun, last)
                if (last is ApplyResult.Failed || last is ApplyResult.NeedsAttention) break
            }
            last
        }

    private fun editDraft(change: (List<DraftSlot>) -> List<DraftSlot>) {
        draft.update { current ->
            val next = change(current ?: slotsOf(backend.journal.value))
            check(next.size <= GadgetLimits.LUN_COUNT_MAX) { "at most ${GadgetLimits.LUN_COUNT_MAX} slots" }
            next
        }
    }

    private fun run(
        label: String,
        action: suspend () -> ApplyResult?,
    ) {
        if (busy.value) return
        busy.value = true
        message.value = null
        scope.launch {
            try {
                val result = action()
                // An action may have set a more specific message itself.
                if (message.value == null) message.value = describe(result)
            } catch (error: Exception) {
                message.value = "$label failed: ${error.message ?: error::class.simpleName}"
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        /** Prefix of the NeedsAttention reason when the host has the medium mounted (EBUSY). */
        const val EJECT_PREVENTED_REASON = DefaultGadgetController.EJECT_PREVENTED_REASON

        private val EJECTED_INTENTS = setOf(SlotIntent.HOST_EJECTED, SlotIntent.USER_EJECTED)

        const val REINSERTED_AFTER_FORCE_EJECT =
            "Re-inserted. After a force eject, macOS sees the drive again only once USB reconnects (unplug and replug the cable)."

        fun slotsOf(journal: Journal): List<DraftSlot> =
            journal.desired.slots.sortedBy { it.lun }.map { spec ->
                // Journals from before the Auto flag: a mode matching the extension rule was Auto.
                val auto = spec.auto || spec.mode == LunModes.auto(spec.backingPath, sizeBytes = null)
                DraftSlot(spec.backingPath, modeOverride = spec.mode.takeUnless { auto })
            }

        fun desiredFor(
            slots: List<DraftSlot>,
            journal: Journal,
            sizeOf: (path: String) -> Long?,
        ): Desired {
            require(slots.size <= GadgetLimits.LUN_COUNT_MAX) { "at most ${GadgetLimits.LUN_COUNT_MAX} slots, got ${slots.size}" }
            val specs =
                slots.mapIndexed { lun, slot ->
                    SlotSpec(
                        lun,
                        slot.path,
                        slot.mode(sizeOf(slot.path)),
                        auto =
                            slot.modeOverride == null,
                    )
                }
            val writable = specs.filter { it.mode == LunMode.DISK_RW }.map { it.backingPath }
            require(writable.size == writable.toSet().size) { "an image can be exported writable only once" }
            val writableExportedTwice = writable.any { path -> specs.count { it.backingPath == path } > 1 }
            require(!writableExportedTwice) { "a writable image can't be exported in another slot too" }
            return Desired(slots = specs, hid = journal.desired.hid)
        }

        fun describe(result: TetherResult): String =
            when (result) {
                is TetherResult.Changed -> {
                    val change = if (result.active) "USB tethering on" else "USB tethering off"
                    when (val setup = result.setup) {
                        null, is ApplyResult.Applied -> change
                        else -> "$change. Drives: ${describe(setup)}"
                    }
                }
                is TetherResult.Unchanged -> if (result.active) "USB tethering is already on" else "USB tethering is already off"
                is TetherResult.Refused -> result.reason
                is TetherResult.Failed -> "USB tethering: ${result.reason}"
            }

        fun describe(result: ApplyResult?): String? =
            when (result) {
                null -> null
                is ApplyResult.Applied -> if (result.reenumerated) "Applied — the host saw the USB device reconnect" else "Done"
                is ApplyResult.Partial ->
                    "Some slots failed: " +
                        result.slotFailures.entries.joinToString { "slot ${it.key + 1}: ${it.value}" }
                is ApplyResult.Failed -> "Failed: ${result.reason}"
                is ApplyResult.NeedsAttention -> "Needs attention: ${result.reason}"
            }
    }
}
