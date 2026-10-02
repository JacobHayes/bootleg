package dev.jacobhayes.bootleg.ui.drives

import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.DefaultGadgetController
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetLimits
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.SlotSpec
import dev.jacobhayes.bootleg.storage.StoragePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A slot as edited in the UI. [modeOverride] null means Auto (by extension). */
data class DraftSlot(
    val path: String,
    val modeOverride: LunMode? = null,
) {
    val mode: LunMode get() = modeOverride ?: StoragePaths.autoMode(path)
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

    val state: StateFlow<DrivesUiState> =
        combine(draft, backend.journal, busy, message, forceEjectOffer) { draft, journal, busy, message, offer ->
            val applied = slotsOf(journal)
            val slots = draft ?: applied
            DrivesUiState(slots, dirty = draft != null && draft != applied, busy = busy, message = message, forceEjectOffer = offer)
        }.stateIn(scope, SharingStarted.Eagerly, DrivesUiState())

    val canAddSlot: Boolean get() = state.value.slots.size < GadgetLimits.LUN_COUNT_MAX

    fun addSlot(path: String): Boolean {
        if (!canAddSlot) return false
        editDraft { it + DraftSlot(path) }
        return true
    }

    fun replaceImage(
        index: Int,
        path: String,
    ) = editDraft { slots -> slots.mapIndexed { i, slot -> if (i == index) DraftSlot(path) else slot } }

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
            backend.apply(desiredFor(slots, backend.journal.value)).also { draft.value = null }
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
        backend.eject(lun, force).also { offerForceEjectIfPrevented(lun, it) }
    }

    fun reinsert(lun: Int) = run("Re-inserting") { backend.reinsert(lun) }

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
                message.value = describe(action())
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

        fun slotsOf(journal: Journal): List<DraftSlot> =
            journal.desired.slots.sortedBy { it.lun }.map { spec ->
                val auto = StoragePaths.autoMode(spec.backingPath)
                DraftSlot(spec.backingPath, modeOverride = spec.mode.takeIf { it != auto })
            }

        fun desiredFor(
            slots: List<DraftSlot>,
            journal: Journal,
        ): Desired {
            require(slots.size <= GadgetLimits.LUN_COUNT_MAX) { "at most ${GadgetLimits.LUN_COUNT_MAX} slots, got ${slots.size}" }
            val specs = slots.mapIndexed { lun, slot -> SlotSpec(lun, slot.path, slot.mode) }
            val writable = specs.filter { it.mode == LunMode.DISK_RW }.map { it.backingPath }
            require(writable.size == writable.toSet().size) { "an image can be exported writable only once" }
            val writableExportedTwice = writable.any { path -> specs.count { it.backingPath == path } > 1 }
            require(!writableExportedTwice) { "a writable image can't be exported in another slot too" }
            return Desired(slots = specs, hid = journal.desired.hid)
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
