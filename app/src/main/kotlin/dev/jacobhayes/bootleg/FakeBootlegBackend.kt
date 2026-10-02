package dev.jacobhayes.bootleg

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.GadgetLimits
import dev.jacobhayes.bootleg.core.gadget.GadgetNames
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.LunMode
import dev.jacobhayes.bootleg.core.gadget.LunState
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.storage.StoragePaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
) : BootlegBackend {
    private val rootFlow = MutableStateFlow(rootStatus)
    private val journalFlow = MutableStateFlow(initialJournal)
    private val imagesFlow = MutableStateFlow(initialImages)
    private val gadgetFlow = MutableStateFlow<GadgetState?>(stateFor(initialJournal))

    override val rootStatus: StateFlow<RootStatus> = rootFlow.asStateFlow()
    override val gadgetState: StateFlow<GadgetState?> = gadgetFlow.asStateFlow()
    override val journal: StateFlow<Journal> = journalFlow.asStateFlow()
    override val images: StateFlow<List<ImageFile>> = imagesFlow.asStateFlow()

    override suspend fun refreshImages() = delay(latencyMs)

    override suspend fun apply(desired: Desired): ApplyResult {
        delay(latencyMs)
        require(desired.slots.size <= GadgetLimits.LUN_COUNT_MAX) { "too many slots: ${desired.slots.size}" }
        require(desired.slots.map { it.lun } == desired.slots.indices.toList()) { "LUNs must be dense" }
        (rootFlow.value as? RootStatus.Unavailable)?.let { return ApplyResult.Failed("Root unavailable: ${it.reason}") }
        val journal =
            Journal(
                desired = desired,
                slotIntents = desired.slots.associate { it.lun to SlotIntent.EXPORTED },
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
        journalFlow.update { it.copy(slotIntents = it.slotIntents + (lun to SlotIntent.USER_EJECTED)) }
        val state = stateFor(journalFlow.value)
        gadgetFlow.value = state
        return ApplyResult.Applied(state, reenumerated = false)
    }

    override suspend fun reinsert(lun: Int): ApplyResult {
        delay(latencyMs)
        journalFlow.update { it.copy(slotIntents = it.slotIntents + (lun to SlotIntent.EXPORTED)) }
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
        val functions = if (luns.isEmpty()) setOf("ffs.adb") else setOf("ffs.adb", GadgetNames.MASS_STORAGE_FUNCTION)
        return GadgetState(udc = FAKE_UDC, functions = functions, configLinks = emptyMap(), luns = luns)
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
