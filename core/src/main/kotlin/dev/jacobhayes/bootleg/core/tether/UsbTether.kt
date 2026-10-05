package dev.jacobhayes.bootleg.core.tether

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Clock
import dev.jacobhayes.bootleg.core.gadget.GadgetController
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.HidFunctions
import dev.jacobhayes.bootleg.core.gadget.HidNodeGuard
import dev.jacobhayes.bootleg.core.gadget.LinkOrder
import dev.jacobhayes.bootleg.core.gadget.RndisState
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * The transport USB tethering uses, one at a time: Bootleg's own RNDIS function (Windows 7 and
 * later, Linux) or Android's NCM (macOS, Linux, Windows 10 2004 or later).
 */
@Serializable
enum class TetherMode(
    val wireName: String,
) {
    RNDIS("rndis"),
    NCM("ncm"),
    ;

    companion object {
        val DEFAULT = NCM

        fun fromWireName(name: String): TetherMode? = entries.firstOrNull { it.wireName == name }
    }
}

/** Bounds on one USB tethering change (TigerStyle: every wait is bounded). */
object TetherLimits {
    /** Android answers `startTethering` within ~5ms on the Pixel 6. */
    const val START_CALLBACK_WAIT_MS_MAX = 2_000L

    /** Start attempts; only a transient error ([TetherErrors.isTransient]) earns the second one. */
    const val START_ATTEMPT_COUNT_MAX = 2

    /** A tethered interface appears ~1.2s after the request and is gone ~1.1s after a stop. */
    const val INTERFACE_WAIT_MS_MAX = 5_000L

    /** The HAL's switch (unbind, relink, rebind) ends ~1.9s after the request; then the UDC must hold. */
    const val SWITCH_WAIT_MS_MAX = 5_000L

    /** How long the UDC must stay bound after the HAL's switch before the gadget counts as settled. */
    const val SWITCH_STABLE_BOUND_MS = 1_000L
    const val POLL_INTERVAL_MS = 100L

    /** A transition marker left by a root process that died is waited out at most this long after it was written. */
    const val TRANSITION_GATE_MS_MAX = 10_000L

    /** Android tethers or untethers the RNDIS TAP within ~100ms of the request. */
    const val RNDIS_INTERFACE_WAIT_MS_MAX = 3_000L

    /** How long an on-to-off broadcast must hold before it can clear the remembered choice; `ncm0` comes back ~4s after a re-enumeration (Windows 7). */
    const val EXTERNAL_STOP_CONFIRM_MS = 10_000L
}

/** Android's `TetheringManager.TETHER_ERROR_*` codes, named for messages. */
object TetherErrors {
    const val SERVICE_UNAVAILABLE = 2
    const val INTERNAL_ERROR = 5

    private val names =
        mapOf(
            1 to "unknown interface",
            SERVICE_UNAVAILABLE to "tethering service unavailable",
            3 to "tethering unsupported",
            4 to "interface unavailable",
            INTERNAL_ERROR to "internal error",
            6 to "tether interface error",
            7 to "untether interface error",
            8 to "enable forwarding error",
            9 to "disable forwarding error",
            10 to "interface configuration error",
            11 to "carrier entitlement check failed",
            12 to "DHCP server error",
            13 to "carrier entitlement unknown",
            14 to "no permission to change tethering",
            15 to "no permission to access tethering",
            16 to "unknown tethering type",
        )

    fun describe(code: Int): String = "${names[code] ?: "error"} ($code)"

    fun isTransient(code: Int): Boolean = code == SERVICE_UNAVAILABLE || code == INTERNAL_ERROR
}

/** What one `setUsbTethering` call did. [active] is Android's state when it returned. */
@Serializable
sealed interface TetherResult {
    val active: Boolean

    /** Tethering changed. [reconcile] is the gadget reconcile that followed the HAL's re-plug. */
    @Serializable
    data class Changed(
        override val active: Boolean,
        val reconcile: ApplyResult,
        /** The HAL's switch finished within its bound; if not, [reconcile] ran anyway. */
        val switchSettled: Boolean,
    ) : TetherResult

    /** Already in the requested state: nothing was done, nothing re-plugged. */
    @Serializable
    data class Unchanged(
        override val active: Boolean,
    ) : TetherResult

    /** Not attempted: disks are exposed and the re-plug would pull them from the host. */
    @Serializable
    data class Refused(
        override val active: Boolean,
        val exposedLuns: List<Int>,
        val reason: String,
    ) : TetherResult

    @Serializable
    data class Failed(
        override val active: Boolean,
        val reason: String,
    ) : TetherResult
}

sealed interface TetherDecision {
    data object Proceed : TetherDecision

    data object AlreadyDone : TetherDecision

    data class RefuseDisksExposed(
        val exposedLuns: List<Int>,
    ) : TetherDecision
}

/** Pure rules for turning USB tethering on or off. */
object TetherPolicy {
    const val DISKS_EXPOSED_REASON = "Eject every disk first: turning USB tethering on or off re-plugs USB"

    /** Tethered interface names Android uses for USB tethering (`tetherableUsbRegexs`, plus NCM). */
    private val usbInterfacePattern = Regex("""^(rndis|ncm|usb)\d+$""")

    /** LUNs whose media the host can see, linked or about to be; a re-plug pulls it away. Ejected (empty) LUNs don't count. */
    fun exposedLuns(gadget: GadgetState): List<Int> =
        gadget.luns
            .filterValues { it.file.isNotEmpty() }
            .keys
            .sorted()

    fun decide(
        requestOn: Boolean,
        active: Boolean,
        gadget: GadgetState,
    ): TetherDecision {
        if (requestOn == active) return TetherDecision.AlreadyDone
        val exposed = exposedLuns(gadget)
        if (exposed.isNotEmpty()) return TetherDecision.RefuseDisksExposed(exposed)
        return TetherDecision.Proceed
    }

    /** The HAL links Android's tethering function (`ncm.*`, older devices `rndis.*`) as `functionN`. */
    fun tetherFunctionLinked(gadget: GadgetState): Boolean =
        gadget.configLinks.values.any {
            it.startsWith("ncm.") ||
                it.startsWith("rndis.")
        }

    /** The TAP of Bootleg's RNDIS daemon, which Android tethers as a virtual interface. */
    const val RNDIS_INTERFACE = "bootleg_rndis"

    fun isUsbTetherInterface(name: String): Boolean = usbInterfacePattern.matches(name)

    /** USB tethering over either transport. */
    fun usbTethered(tetheredInterfaces: Collection<String>): Boolean =
        tetheredInterfaces.any { isUsbTetherInterface(it) || it == RNDIS_INTERFACE }
}

/**
 * The tethering choice Bootleg remembers for restore after reboot. Every successful on/off through
 * Bootleg sets it; a stop Bootleg didn't ask for (Android's switch, unplug, a USB mode change)
 * clears it, the way a host eject keeps a disk from coming back.
 */
object TetherChoice {
    /**
     * The choice after Android reported [isActive] (previously [wasActive]; null for the first report
     * of this process, which only initializes). [requestInFlight]: a Bootleg on/off is running, so
     * the change is ours. [shuttingDown]: the phone is powering off, which says nothing about what
     * the user wants.
     */
    fun afterObservation(
        choice: Boolean,
        wasActive: Boolean?,
        isActive: Boolean,
        requestInFlight: Boolean,
        shuttingDown: Boolean,
    ): Boolean {
        val externalStop = wasActive == true && !isActive && !requestInFlight && !shuttingDown
        return if (externalStop) false else choice
    }

    /** The choice after a Bootleg request returned [result]; null leaves it alone (refused or failed). */
    fun afterRequest(result: TetherResult): Boolean? =
        when (result) {
            is TetherResult.Changed -> result.active
            is TetherResult.Unchanged -> result.active
            is TetherResult.Refused, is TetherResult.Failed -> null
        }

    fun restoreOnBoot(
        restoreAfterReboot: Boolean,
        choice: Boolean,
    ): Boolean = restoreAfterReboot && choice

    /**
     * Whether an on-to-off observation, re-checked [TetherLimits.EXTERNAL_STOP_CONFIRM_MS] later, was
     * a stop outside Bootleg. Every re-enumeration of Bootleg's drops and re-adds `ncm0` (seconds on
     * some hosts), so root's callback can say off for a while although Android still tethers; but a
     * real stop also unlinks the HAL's tethering function, which a re-enumeration leaves linked.
     * [stillOff]: no broadcast since has said on.
     */
    fun externalStopConfirmed(
        stillOff: Boolean,
        rootTethered: Boolean,
        tetherFunctionLinked: Boolean,
        requestInFlight: Boolean,
        shuttingDown: Boolean,
    ): Boolean = stillOff && !rootTethered && !tetherFunctionLinked && !requestInFlight && !shuttingDown
}

/** Android's tethering as the root process sees it (`TetheringManager` with uid 0). */
interface TetherPort {
    sealed interface StartOutcome {
        data object Started : StartOutcome

        data class Failed(
            val errorCode: Int,
        ) : StartOutcome

        data object TimedOut : StartOutcome
    }

    /** Asks Android to start USB tethering and waits (≤ [timeoutMs]) for its answer. */
    fun start(timeoutMs: Long): StartOutcome

    /** Asks Android to stop USB tethering; it doesn't answer, so callers watch [usbTethered]. */
    fun stop()

    /** True while Android reports a USB interface tethered. Cheap (a cached callback value); throws while unknown. */
    fun usbTethered(): Boolean
}

/**
 * Bootleg's RNDIS transport as root sees it: Android tethering the daemon's TAP (`TETHERING_VIRTUAL`,
 * no USB change) and the daemon's medium, which decides whether frames pass and whether the host's
 * adapter shows a cable plugged in. Each converges on its own.
 */
interface RndisTetherPort {
    /** Asks Android to tether the TAP and waits (≤ [timeoutMs]) for its answer. */
    fun start(timeoutMs: Long): TetherPort.StartOutcome

    /** Asks Android to stop tethering the TAP; callers watch [tethered]. */
    fun stop()

    /** Android reports the TAP tethered. Cheap; throws while unknown. */
    fun tethered(): Boolean

    /** The daemon's medium is connected (false while no daemon runs). */
    fun mediaConnected(): Boolean

    /** Connects or disconnects the medium; returns once the daemon applied it (and, off, drained). Throws [IOException]. */
    fun setMedia(connected: Boolean)
}

/**
 * Written before every request, so a root process that dies mid-change, or a start Android never
 * answered, leaves a trace that [UsbTetherController.awaitPendingTransition] waits out.
 */
@Serializable
data class TetherTransition(
    val requestedOn: Boolean,
    /** [Clock.nowMs] (elapsed realtime) when it was written. */
    val startedAtMs: Long,
    /**
     * False when nothing can show the change is over (Android never answered the start, or the
     * marker was unreadable): the gate then always waits the full window.
     */
    val verified: Boolean = true,
) {
    /**
     * Whether the HAL may still re-plug USB for it at [nowMs]: inside its gate window. A marker from an
     * earlier boot (written "in the future") or one whose window has passed is not.
     */
    fun isPendingAt(nowMs: Long): Boolean = startedAtMs <= nowMs && nowMs < startedAtMs + TetherLimits.TRANSITION_GATE_MS_MAX
}

interface TetherTransitionStore {
    /** The marker, or null if there is none. An unreadable marker loads as unverified, written now. */
    fun load(): TetherTransition?

    /** Writes atomically. */
    fun save(transition: TetherTransition)

    fun clear()
}

/**
 * Turns USB tethering on or off: decide (refuse while disks are exposed), mark, request, wait for
 * Android to report the new state and for the HAL's switch to settle, then reconcile the gadget.
 * Callers serialize it with every other gadget operation, so nothing of ours runs during the HAL's
 * switch; the marker covers a root process that dies mid-change. Before the request it closes every
 * hidg fd of ours ([HidNodeGuard.closeAll], which fences opens until the caller lifts it) and refuses
 * while any process holds a linked hid node: the HAL's rebind would leave such an fd to panic the
 * kernel when closed.
 */
class UsbTetherController(
    private val port: TetherPort,
    private val gadget: GadgetController,
    private val transitions: TetherTransitionStore,
    private val clock: Clock,
    private val hidNodes: HidNodeGuard,
    /** Null when this Android can't tether an interface by name: RNDIS is then unsupported. */
    private val rndis: RndisTetherPort? = null,
) {
    /** Tethered over either transport, as root's callback reports it. */
    fun usbTethered(): Boolean = port.usbTethered() || rndis?.tethered() == true

    /**
     * Turns tethering on over [mode] (the other transport off first) or everything off. Returns only
     * once no marker is left (the switch settled, or the marker's window was waited out here), so a
     * caller that keeps HID closed until this returns keeps it closed through the HAL's re-plug.
     */
    fun set(
        on: Boolean,
        mode: TetherMode = TetherMode.NCM,
    ): TetherResult {
        val result =
            runCatching {
                awaitPendingTransition()
                val rndisOn = on && mode == TetherMode.RNDIS
                if (rndisOn && rndis == null) return@runCatching TetherResult.Failed(usbTethered(), RNDIS_UNSUPPORTED_REASON)
                // An NCM start that will be refused mustn't take RNDIS down first.
                val ncmDecision = TetherPolicy.decide(on && !rndisOn, port.usbTethered(), gadget.read())
                if (ncmDecision is TetherDecision.RefuseDisksExposed) {
                    return@runCatching TetherResult.Refused(usbTethered(), ncmDecision.exposedLuns, TetherPolicy.DISKS_EXPOSED_REASON)
                }
                val first = if (rndisOn) changeNcm(false) else changeRndis(false)
                if (first is TetherResult.Failed || first is TetherResult.Refused) return@runCatching first
                val second = if (rndisOn) changeRndis(true) else changeNcm(on)
                if (second is TetherResult.Unchanged && first is TetherResult.Changed) first.copy(active = usbTethered()) else second
            }
        if (transitions.load() != null) awaitPendingTransition()
        return result.getOrThrow()
    }

    /**
     * RNDIS on: the prefix in place (set up first, without disks), Android tethering the TAP, then the
     * medium connected, so the host's DHCP request finds Android's server. Off: the medium first, so
     * nothing passes once Android stops. Each part converges on its own (a failed stop or a dead root
     * process can leave one of them behind); a failed start is taken back down. No USB change.
     */
    private fun changeRndis(on: Boolean): TetherResult {
        val port =
            rndis ?: return if (on) TetherResult.Failed(usbTethered(), RNDIS_UNSUPPORTED_REASON) else TetherResult.Unchanged(usbTethered())
        if (port.tethered() == on && port.mediaConnected() == on) return TetherResult.Unchanged(usbTethered())
        var reconcile = ApplyResult.Applied(gadget.read(), reenumerated = false) as ApplyResult
        if (on && !rndisReady(gadget.read())) {
            val exposed = TetherPolicy.exposedLuns(gadget.read())
            if (exposed.isNotEmpty()) return TetherResult.Refused(usbTethered(), exposed, TetherPolicy.DISKS_EXPOSED_REASON)
            gadget.establishPrefix()?.let { reconcile = it }
            if (!rndisReady(gadget.read())) return TetherResult.Failed(usbTethered(), "$RNDIS_NOT_READY_REASON: $reconcile")
            reconcile = gadget.reconcile()
        }
        val failure =
            if (on) {
                rndisOn(port)?.let { failure ->
                    rndisOff(port)?.let { "$failure (and undoing it failed: $it)" } ?: failure
                }
            } else {
                rndisOff(port)
            }
        return if (failure ==
            null
        ) {
            TetherResult.Changed(usbTethered(), reconcile, switchSettled = true)
        } else {
            TetherResult.Failed(usbTethered(), failure)
        }
    }

    /** Why turning RNDIS on failed, or null. */
    private fun rndisOn(port: RndisTetherPort): String? {
        if (!port.tethered()) {
            val failure =
                when (val outcome = requestStart(port::start)) {
                    TetherPort.StartOutcome.Started ->
                        "Android didn't tether the adapter within ${TetherLimits.RNDIS_INTERFACE_WAIT_MS_MAX}ms"
                            .takeUnless { poll(clock.nowMs() + TetherLimits.RNDIS_INTERFACE_WAIT_MS_MAX) { port.tethered() } }
                    is TetherPort.StartOutcome.Failed -> "Android refused: ${TetherErrors.describe(outcome.errorCode)}"
                    TetherPort.StartOutcome.TimedOut -> "Android didn't answer within ${TetherLimits.START_CALLBACK_WAIT_MS_MAX}ms"
                }
            if (failure != null) return failure
        }
        return try {
            port.setMedia(true)
            null
        } catch (e: IOException) {
            "the network adapter didn't connect: ${e.message}"
        }
    }

    /** Why turning RNDIS off failed, or null. A medium that can't be shown drained fails it. */
    private fun rndisOff(port: RndisTetherPort): String? {
        if (port.mediaConnected()) {
            try {
                port.setMedia(false)
            } catch (e: IOException) {
                return "the network adapter didn't disconnect: ${e.message}"
            }
        }
        if (!port.tethered()) return null
        // A stop that throws still gets its bounded check: Android may have untethered anyway.
        val stopError = runCatching { port.stop() }.exceptionOrNull()
        if (poll(clock.nowMs() + TetherLimits.RNDIS_INTERFACE_WAIT_MS_MAX) { !port.tethered() }) return null
        return "Android didn't stop tethering within ${TetherLimits.RNDIS_INTERFACE_WAIT_MS_MAX}ms${stopError?.let {
            " (${it.message})"
        }.orEmpty()}"
    }

    private fun rndisReady(state: GadgetState): Boolean =
        LinkOrder.prefixFirst(state.configLinks) && state.rndis == RndisState.READY && state.udc.isNotEmpty()

    /** NCM: Android's USB tethering, which re-plugs USB through the HAL. */
    private fun changeNcm(on: Boolean): TetherResult {
        var gadgetState = gadget.read()
        when (val decision = TetherPolicy.decide(on, port.usbTethered(), gadgetState)) {
            TetherDecision.AlreadyDone -> return TetherResult.Unchanged(usbTethered())
            is TetherDecision.RefuseDisksExposed ->
                return TetherResult.Refused(usbTethered(), decision.exposedLuns, TetherPolicy.DISKS_EXPOSED_REASON)
            TetherDecision.Proceed -> Unit
        }
        // Once tethering is on, changing the USB mode would end it, so the prefix can't be set up after.
        // Tethering doesn't depend on it: a failure is left for the reconcile below to report.
        if (on && gadget.establishPrefix() != null) gadgetState = gadget.read()
        // The HAL's re-plug rebinds every linked hid function: no hidg fd (ours or anyone's) may be open across it.
        hidNodes.closeAll()
        val holders = hidNodes.holders((HidFunctions.linked(gadgetState) + HidFunctions.owned(gadgetState)).sorted())
        if (holders.isNotEmpty()) return TetherResult.Failed(usbTethered(), "$HID_NODE_HELD_REASON: ${holders.joinToString(", ")}")
        transitions.save(TetherTransition(on, clock.nowMs()))
        if (on) {
            when (val outcome = requestStart(port::start)) {
                TetherPort.StartOutcome.Started -> Unit
                is TetherPort.StartOutcome.Failed -> {
                    // Android refused outright: nothing started, so there is nothing to stop.
                    transitions.clear()
                    return TetherResult.Failed(
                        usbTethered(),
                        "Android refused USB tethering: ${TetherErrors.describe(outcome.errorCode)}",
                    )
                }
                TetherPort.StartOutcome.TimedOut ->
                    return withdraw(
                        "Android didn't answer the tethering request within ${TetherLimits.START_CALLBACK_WAIT_MS_MAX}ms",
                        verified = false,
                    )
            }
        } else {
            port.stop()
        }
        if (!awaitTethered(on)) {
            if (on) return withdraw("USB tethering didn't come up within ${TetherLimits.INTERFACE_WAIT_MS_MAX}ms", verified = true)
            // The marker stays: whatever Android does next, [set] waits it out before returning.
            return TetherResult.Failed(usbTethered(), "USB tethering didn't stop within ${TetherLimits.INTERFACE_WAIT_MS_MAX}ms")
        }
        val switchSettled = awaitSwitchSettled(on, clock.nowMs() + TetherLimits.SWITCH_WAIT_MS_MAX)
        if (switchSettled) {
            transitions.clear()
        } else {
            // The HAL may still re-plug USB: [set] waits out a full window before returning.
            transitions.save(TetherTransition(on, clock.nowMs(), verified = false))
        }
        // Android may change it again meanwhile (its own switch, an unplug), so report what it says now.
        val reconcile = gadget.reconcile()
        return TetherResult.Changed(usbTethered(), reconcile, switchSettled)
    }

    /**
     * Waits out a change that may still re-plug USB, at most until [TetherLimits.TRANSITION_GATE_MS_MAX]
     * after the marker was written: a verified marker until Android reports the requested state and
     * the HAL's switch has settled, an unverified one always that long. Callers run this before
     * anything that can export a disk.
     */
    fun awaitPendingTransition() {
        val marker = transitions.load() ?: return
        val nowMs = clock.nowMs()
        // A marker from the future was written in an earlier boot: elapsed realtime started over since.
        if (marker.startedAtMs <= nowMs) {
            // A re-plug may still come: no hidg fd may be open across it (the caller lifts the fence).
            if (marker.isPendingAt(nowMs)) hidNodes.closeAll()
            val deadlineMs = marker.startedAtMs + TetherLimits.TRANSITION_GATE_MS_MAX
            if (marker.verified) {
                if (awaitTethered(marker.requestedOn, deadlineMs)) awaitSwitchSettled(marker.requestedOn, deadlineMs)
            } else {
                poll(deadlineMs) { false }
            }
        }
        transitions.clear()
    }

    /**
     * A start that didn't come up: stop it again and wait until that settled, so no request is left
     * to re-plug USB later. The marker is rewritten for the stop first; it stays when the stop can't
     * be shown to be over ([verified] false: the start may still be queued behind an unanswered call).
     */
    private fun withdraw(
        reason: String,
        verified: Boolean,
    ): TetherResult.Failed {
        transitions.save(TetherTransition(requestedOn = false, startedAtMs = clock.nowMs(), verified = verified))
        port.stop()
        val stopped = awaitTethered(false) && awaitSwitchSettled(false, clock.nowMs() + TetherLimits.SWITCH_WAIT_MS_MAX)
        if (stopped && verified) transitions.clear()
        return TetherResult.Failed(usbTethered(), reason)
    }

    /** Asks Android to start, retrying once on a transient error. */
    private fun requestStart(start: (Long) -> TetherPort.StartOutcome): TetherPort.StartOutcome {
        var outcome: TetherPort.StartOutcome = TetherPort.StartOutcome.TimedOut
        for (attempt in 1..TetherLimits.START_ATTEMPT_COUNT_MAX) {
            outcome = start(TetherLimits.START_CALLBACK_WAIT_MS_MAX)
            val retry = outcome is TetherPort.StartOutcome.Failed && TetherErrors.isTransient(outcome.errorCode)
            if (!retry) break
        }
        return outcome
    }

    private fun awaitTethered(
        on: Boolean,
        deadlineMs: Long = clock.nowMs() + TetherLimits.INTERFACE_WAIT_MS_MAX,
    ): Boolean = poll(deadlineMs) { port.usbTethered() == on }

    /**
     * Android reports the interface before the HAL has finished: waits until the HAL's tethering
     * function is linked ([on]) or gone, with the UDC bound for [TetherLimits.SWITCH_STABLE_BOUND_MS].
     */
    private fun awaitSwitchSettled(
        on: Boolean,
        deadlineMs: Long,
    ): Boolean {
        var boundSinceMs: Long? = null
        return poll(deadlineMs) {
            val state = gadget.read()
            val nowMs = clock.nowMs()
            val since = if (state.udc.isEmpty()) null else boundSinceMs ?: nowMs
            boundSinceMs = since
            TetherPolicy.tetherFunctionLinked(state) == on && since != null && nowMs - since >= TetherLimits.SWITCH_STABLE_BOUND_MS
        }
    }

    /** Polls [done] every [TetherLimits.POLL_INTERVAL_MS] until it holds or [deadlineMs] passes. */
    private fun poll(
        deadlineMs: Long,
        done: () -> Boolean,
    ): Boolean {
        repeat(POLL_COUNT_MAX) {
            if (done()) return true
            val remainingMs = deadlineMs - clock.nowMs()
            if (remainingMs <= 0) return false
            clock.sleepMs(minOf(TetherLimits.POLL_INTERVAL_MS, remainingMs))
        }
        return done()
    }

    companion object {
        private const val HID_NODE_HELD_REASON = "a keyboard/mouse device node is held open, so USB tethering was left as it was"
        const val RNDIS_UNSUPPORTED_REASON = "This Android can't tether Bootleg's RNDIS adapter; choose NCM"
        const val RNDIS_NOT_READY_REASON = "Bootleg's USB functions couldn't be set up"

        /** No wait here spans more than the transition gate, which bounds the loop independently of the clock. */
        private const val POLL_COUNT_MAX = (TetherLimits.TRANSITION_GATE_MS_MAX / TetherLimits.POLL_INTERVAL_MS).toInt() + 1
    }
}
