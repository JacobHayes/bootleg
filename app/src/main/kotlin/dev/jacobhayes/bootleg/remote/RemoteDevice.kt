package dev.jacobhayes.bootleg.remote

import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.ImageFile
import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Desired
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.gadget.Journal
import dev.jacobhayes.bootleg.core.gadget.SlotIntent
import dev.jacobhayes.bootleg.core.remote.DevicePort
import dev.jacobhayes.bootleg.core.remote.DeviceSnapshot
import dev.jacobhayes.bootleg.core.remote.HidLinkStatus
import dev.jacobhayes.bootleg.core.remote.RemoteImage
import dev.jacobhayes.bootleg.core.remote.RemoteSlot
import dev.jacobhayes.bootleg.core.tether.TetherResult
import dev.jacobhayes.bootleg.hid.HidController
import dev.jacobhayes.bootleg.hid.HidLinkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The gadget as the remote controller sees it: the same [BootlegBackend] the app's Drives screen
 * uses (so the remote and the phone never disagree), with HID changes going through
 * [HidController] so its session is closed before the functions go away and reopened after.
 */
class RemoteDevice(
    private val backend: BootlegBackend,
    private val hid: HidController,
) : DevicePort {
    override suspend fun apply(desired: Desired): ApplyResult = backend.apply(desired)

    override suspend fun eject(
        lun: Int,
        force: Boolean,
    ): ApplyResult = backend.eject(lun, force)

    override suspend fun reinsert(lun: Int): ApplyResult = backend.reinsert(lun)

    override suspend fun setHid(spec: HidSpec): ApplyResult = hid.setSpec(spec)

    override suspend fun setUsbTethering(on: Boolean): TetherResult = backend.setUsbTethering(on)

    override val snapshots: Flow<DeviceSnapshot> =
        combine(backend.journal, backend.images, hid.link, backend.usbTethering) { journal, images, link, tethering ->
            snapshotOf(journal, images).copy(hidLink = statusOf(link), usbTethering = tethering)
        }.distinctUntilChanged()

    companion object {
        fun statusOf(link: HidLinkState): HidLinkStatus =
            when (link) {
                HidLinkState.Stopped -> HidLinkStatus.STOPPED
                HidLinkState.Opening -> HidLinkStatus.CONNECTING
                // Whether the host is reading flips with every stalled write; not worth a state push.
                is HidLinkState.Open -> HidLinkStatus.OPEN
                is HidLinkState.Reconnecting -> HidLinkStatus.RECONNECTING
                is HidLinkState.Failed -> if (link.restartRequired) HidLinkStatus.RESTART_REQUIRED else HidLinkStatus.FAILED
            }

        fun snapshotOf(
            journal: Journal,
            images: List<ImageFile>,
        ): DeviceSnapshot =
            DeviceSnapshot(
                slots =
                    journal.desired.slots.map { slot ->
                        RemoteSlot(slot.lun, slot.backingPath, slot.mode, journal.slotIntents[slot.lun] ?: SlotIntent.EXPORTED)
                    },
                hid = journal.desired.hid,
                images =
                    images.map { image ->
                        RemoteImage(
                            imageId = RemoteImage.idFor(image.path),
                            displayName = image.displayName,
                            sizeBytes = image.sizeBytes,
                            path = image.path,
                            defaultMode = RemoteImage.defaultModeFor(image.path),
                        )
                    },
            )
    }
}
