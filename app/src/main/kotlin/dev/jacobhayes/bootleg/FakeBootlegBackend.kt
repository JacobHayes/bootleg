package dev.jacobhayes.bootleg

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetLimits
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.LinkOrder
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.LunState
import dev.jacobhayes.bootleg.core.gadget.RndisState
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.tether.TetherMode
import dev.jacobhayes.bootleg.core.tether.TetherPolicy
import dev.jacobhayes.bootleg.core.tether.TetherResult
import dev.jacobhayes.bootleg.hid.HidEndpoint
import dev.jacobhayes.bootleg.hid.HidRelayNotice
import dev.jacobhayes.bootleg.storage.StoragePaths
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [BootlegBackend] for previews, tests and the UI before the root service is merged.
 * It never touches the device: apply/eject only update in-memory state.
 */
class FakeBootlegBackend(
    initialImages: List<ImageFile> = emptyList(),
    rootStatus: RootStatus = RootStatus.Granted,
    initialJournal: Journal = Journal(),
    private val latencyMs: Long = 0,
    initialUsbTethering: Boolean? = false,
) : BootlegBackend {
    private val rootFlow = MutableStateFlow(rootStatus)
    private val journalFlow = MutableStateFlow(initialJournal)
    private val imagesFlow = MutableStateFlow(initialImages)
    private val gadgetFlow = MutableStateFlow<GadgetState?>(stateFor(initialJournal))

    override val rootStatus: StateFlow<RootStatus> = rootFlow.asStateFlow()
    override val gadgetState: StateFlow<GadgetState?> = gadgetFlow.asStateFlow()
    override val journal: StateFlow<Journal> = journalFlow.asStateFlow()
    override val images: StateFlow<List<ImageFile>> = imagesFlow.asStateFlow()

    private val tetheringFlow = MutableStateFlow<Boolean?>(initialUsbTethering)
    override val usbTethering: StateFlow<Boolean?> = tetheringFlow.asStateFlow()
    override val usbTetheringBusy: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    private val modeFlow = MutableStateFlow(TetherMode.DEFAULT)
    override val usbTetherMode: StateFlow<TetherMode> = modeFlow.asStateFlow()

    override fun setUsbTetherMode(mode: TetherMode) {
        modeFlow.value = mode
    }

    /** Same rule as the root side: NCM is refused while a LUN holds media. */
    override suspend fun setUsbTethering(on: Boolean): TetherResult {
        delay(latencyMs)
        val active = tetheringFlow.value == true
        if (on == active) return TetherResult.Unchanged(active)
        val exposed = gadgetFlow.value?.let(TetherPolicy::exposedLuns).orEmpty()
        if (exposed.isNotEmpty() && modeFlow.value == TetherMode.NCM) {
            return TetherResult.Refused(active, exposed, TetherPolicy.DISKS_EXPOSED_REASON)
        }
        tetheringFlow.value = on
        val state = checkNotNull(gadgetFlow.value)
        return TetherResult.Changed(on, ApplyResult.Applied(state, reenumerated = false), switchSettled = true)
    }

    override suspend fun refreshImages() = delay(latencyMs)

    override suspend fun apply(desired: Desired): ApplyResult {
        delay(latencyMs)
        require(desired.slots.size <= GadgetLimits.LUN_COUNT_MAX) { "too many slots: ${desired.slots.size}" }
        require(desired.slots.map { it.lun } == desired.slots.indices.toList()) { "LUNs must be dense" }
        (rootFlow.value as? RootStatus.Unavailable)?.let { return ApplyResult.Failed("Root unavailable: ${it.reason}") }
        // Like the real controller: re-applying the same slot spec keeps a host or user eject.
        val previous = journalFlow.value
        val journal =
            Journal(
                desired = desired,
                slotIntents =
                    desired.slots.associate { spec ->
                        val kept =
                            previous.slotIntents[spec.lun]?.takeIf {
                                (it == SlotIntent.HOST_EJECTED || it == SlotIntent.USER_EJECTED) &&
                                    previous.desired.slots.getOrNull(spec.lun) == spec
                            }
                        spec.lun to (kept ?: SlotIntent.EXPORTED)
                    },
                udc = FAKE_UDC,
            )
        journalFlow.value = journal
        val state = stateFor(journal)
        gadgetFlow.value = state
        return ApplyResult.Applied(state, reenumerated = true)
    }

    override suspend fun eject(
        lun: Int,
        force: Boolean,
    ): ApplyResult {
        delay(latencyMs)
        if (ejectPreventedByHost && !force) return ApplyResult.NeedsAttention("host prevents removal; force eject?")
        journalFlow.update { it.copy(slotIntents = it.slotIntents + (lun to SlotIntent.USER_EJECTED)) }
        val state = stateFor(journalFlow.value)
        gadgetFlow.value = state
        return ApplyResult.Applied(state, reenumerated = false)
    }

    override suspend fun reinsert(lun: Int): ApplyResult {
        delay(latencyMs)
        require(lun in journalFlow.value.desired.slots.indices) { "no slot $lun" }
        journalFlow.update { it.copy(slotIntents = it.slotIntents + (lun to SlotIntent.EXPORTED), slotFailures = it.slotFailures - lun) }
        val state = stateFor(journalFlow.value)
        gadgetFlow.value = state
        return ApplyResult.Applied(state, reenumerated = false)
    }

    override suspend fun bootlegOff(): ApplyResult {
        journalFlow.value = Journal()
        gadgetFlow.value = stateFor(Journal())
        return ApplyResult.Applied(stateFor(Journal()), reenumerated = true)
    }

    override suspend fun createBlankImage(
        name: String,
        sizeBytes: Long,
        format: BlankImageFormat,
        preallocate: Boolean,
    ): Result<ImageFile> {
        delay(latencyMs)
        require(sizeBytes > 0) { "sizeBytes must be > 0" }
        val path = "${StoragePaths.STORE_MEDIA_DIR}/$name"
        if (imagesFlow.value.any { it.path == path }) return Result.failure(IllegalStateException("$name already exists"))
        val image = ImageFile(path, name, sizeBytes, if (preallocate) sizeBytes else SPARSE_OVERHEAD_BYTES, verifiedSha256 = null)
        imagesFlow.update { (it + image).sortedBy { file -> file.displayName.lowercase() } }
        return Result.success(image)
    }

    override suspend fun deleteImage(path: String): Result<Unit> {
        delay(latencyMs)
        imagesFlow.update { images -> images.filterNot { it.path == path } }
        return Result.success(Unit)
    }

    override suspend fun setHid(spec: HidSpec): ApplyResult = apply(journalFlow.value.desired.copy(hid = spec))

    /** There are no HID devices here; opening never completes, so screens show a steady "connecting" state. */
    override suspend fun openHid(kind: HidKind): HidEndpoint = awaitCancellation()

    /** Tests emit relay notices here. */
    val hidRelayNoticeFlow = MutableSharedFlow<HidRelayNotice>(extraBufferCapacity = HidRelayNotice.BUFFER_COUNT_MAX)
    override val hidRelayNotices: Flow<HidRelayNotice> = hidRelayNoticeFlow.asSharedFlow()

    /** Simulates macOS holding the volume mounted: a normal eject is refused (EBUSY). */
    var ejectPreventedByHost = false

    fun setRootStatus(status: RootStatus) {
        rootFlow.value = status
    }

    private fun stateFor(journal: Journal): GadgetState {
        val luns =
            journal.desired.slots.associate { slot ->
                val ejected = journal.slotIntents[slot.lun] != SlotIntent.EXPORTED
                slot.lun to
                    LunState(
                        file = if (ejected) "" else slot.backingPath,
                        ro = slot.mode != LunMode.DISK_RW,
                        cdrom = slot.mode == LunMode.CDROM,
                        removable = true,
                        nofua = false,
                    )
            }
        val prefix = if (journal.desired.wantsPrefix) LinkOrder.PREFIX else emptyList()
        val functions = (if (luns.isEmpty()) setOf("ffs.adb") else setOf("ffs.adb", GadgetNames.MASS_STORAGE_FUNCTION)) + prefix
        return GadgetState(
            udc = FAKE_UDC,
            functions = functions,
            configLinks = prefix.associateWith { it },
            luns = luns,
            rndis = if (prefix.isEmpty()) RndisState.ABSENT else RndisState.READY,
        )
    }

    companion object {
        private const val FAKE_UDC = "11110000.dwc3"
        private const val SPARSE_OVERHEAD_BYTES = 4L * 1024 * 1024
        private const val GB = 1_000_000_000L

        val demoImages: List<ImageFile> =
            listOf(
                ImageFile(
                    "${StoragePaths.STORE_MEDIA_DIR}/debian-13.2.0-amd64-netinst.iso",
                    "debian-13.2.0-amd64-netinst.iso",
                    754_974_720,
                    754_974_720,
                    "a".repeat(64),
                ),
                ImageFile(
                    "${StoragePaths.STORE_MEDIA_DIR}/ubuntu-26.04-desktop-amd64.iso",
                    "ubuntu-26.04-desktop-amd64.iso",
                    6_270_000_000,
                    6_270_000_000,
                    "b".repeat(64),
                ),
                ImageFile("${StoragePaths.STORE_MEDIA_DIR}/scratch-fat32.img", "scratch-fat32.img", 8 * GB, 52_428_800, null),
                ImageFile("${StoragePaths.STORE_MEDIA_DIR}/memtest86plus-7.20.iso", "memtest86plus-7.20.iso", 21_430_272, 21_430_272, null),
            )

        fun demo(): FakeBootlegBackend =
            FakeBootlegBackend(
                initialImages = demoImages,
                latencyMs = 300,
            )
    }
}
