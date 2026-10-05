package dev.jacobhayes.bootleg.core.tether

import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.Clock
import dev.jacobhayes.bootleg.core.gadget.GadgetController
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.core.gadget.LinkOrder
import dev.jacobhayes.bootleg.core.gadget.TetherMode
import kotlinx.serialization.Serializable
import java.io.IOException

/** Bounds on one USB tethering change (TigerStyle: every wait is bounded). */
object TetherLimits {
    /** Android answers `startTethering` within ~35ms on the Pixel 6. */
    const val START_CALLBACK_WAIT_MS_MAX = 2_000L

    /** Start attempts; only a transient error ([TetherErrors.isTransient]) earns the second one. */
    const val START_ATTEMPT_COUNT_MAX = 2

    /** Android answers `stopTethering` at once; whether it stopped shows in the tethered set. */
    const val STOP_CALLBACK_WAIT_MS_MAX = 2_000L

    /** Android tethers or untethers an interface within ~100ms of the request. */
    const val INTERFACE_WAIT_MS_MAX = 3_000L
    const val POLL_INTERVAL_MS = 100L

    /** How long an on-to-off broadcast must hold before it can clear the remembered choice. */
    const val EXTERNAL_STOP_CONFIRM_MS = 3_000L
}

/** Android's `TetheringManager.TETHER_ERROR_*` codes, named for messages. */
object TetherErrors {
    const val SERVICE_UNAVAILABLE = 2
    const val INTERNAL_ERROR = 5

    /** A stop that matches no request of the caller's: someone else's stop erased it. */
    const val UNKNOWN_REQUEST = 17

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
            UNKNOWN_REQUEST to "unknown request",
            18 to "duplicate request",
        )

    fun describe(code: Int): String = "${names[code] ?: "error"} ($code)"

    fun isTransient(code: Int): Boolean = code == SERVICE_UNAVAILABLE || code == INTERNAL_ERROR
}

/** What one `setUsbTethering` call did. [active] is Android's state of Bootleg's tethering when it returned. */
@Serializable
sealed interface TetherResult {
    val active: Boolean

    /** Tethering changed. [setup]: setting the prefix up first (a first use or a mode change), when that was needed. */
    @Serializable
    data class Changed(
        override val active: Boolean,
        val setup: ApplyResult? = null,
    ) : TetherResult

    /** Already in the requested state: nothing was done. */
    @Serializable
    data class Unchanged(
        override val active: Boolean,
    ) : TetherResult

    /** Not attempted: it needs the prefix set up (a first use or a mode change), which re-plugs USB, and disks are exposed. */
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

/** Pure rules for Bootleg's USB tethering. */
object TetherPolicy {
    const val DISKS_EXPOSED_REASON = "Eject every disk first: switching the tethering mode re-plugs USB"

    /** The TAP of Bootleg's RNDIS daemon. */
    const val RNDIS_INTERFACE = "bootleg_rndis"

    /** `TetheringManager.TETHERING_VIRTUAL`: an interface tethered by name, Bootleg's and others' (AVF's `avf_tap_fixed`). */
    const val TETHERING_VIRTUAL = 7

    private val ncmInterfacePattern = Regex("""^bootleg_ncm\d+$""")

    /** LUNs whose media the host can see, linked or about to be; a re-plug pulls it away. Ejected (empty) LUNs don't count. */
    fun exposedLuns(gadget: GadgetState): List<Int> =
        gadget.luns
            .filterValues { it.file.isNotEmpty() }
            .keys
            .sorted()

    fun isBootlegInterface(name: String): Boolean = name == RNDIS_INTERFACE || ncmInterfacePattern.matches(name)

    /** Bootleg's tethering, over either network function. */
    fun bootlegTethered(tetheredInterfaces: Collection<String>): Boolean = tetheredInterfaces.any(::isBootlegInterface)

    /**
     * The interface Android tethers for [mode], once the prefix is set up with that network function
     * and bound: the RNDIS daemon's TAP, or the NCM function's netdev (read back from `ifname`, named at
     * its first bind). Null while there is none to tether.
     */
    fun interfaceName(
        gadget: GadgetState,
        mode: TetherMode,
    ): String? {
        if (!LinkOrder.prefixServed(gadget, mode) || gadget.udc.isEmpty()) return null
        return when (mode) {
            TetherMode.RNDIS -> RNDIS_INTERFACE
            TetherMode.NCM -> gadget.ncmInterface?.takeIf(ncmInterfacePattern::matches)
        }
    }

    /** Other apps' interfaces tethered by name ([TETHERING_VIRTUAL]); [tethered] maps names to tethering types. */
    fun foreignVirtual(tethered: Map<String, Int>): List<String> =
        tethered.filter { (name, type) -> type == TETHERING_VIRTUAL && !isBootlegInterface(name) }.keys.sorted()
}

/**
 * The tethering choice Bootleg remembers for restore after reboot. Every successful on/off through
 * Bootleg sets it; a stop Bootleg didn't ask for clears it, the way a host eject keeps a disk from
 * coming back.
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
     * Whether an on-to-off observation, re-checked [TetherLimits.EXTERNAL_STOP_CONFIRM_MS] later, was a
     * stop outside Bootleg: no broadcast since has said on ([stillOff]), root's live callback agrees, and
     * no request is in flight. A stale broadcast can't clear a choice a later request set.
     */
    fun externalStopConfirmed(
        stillOff: Boolean,
        rootTethered: Boolean,
        requestInFlight: Boolean,
        shuttingDown: Boolean,
    ): Boolean = stillOff && !rootTethered && !requestInFlight && !shuttingDown
}

/** Android's tethering of an interface by name (`TETHERING_VIRTUAL`), as the root process sees it. */
interface TetherPort {
    sealed interface StartOutcome {
        data object Started : StartOutcome

        data class Failed(
            val errorCode: Int,
        ) : StartOutcome

        data object TimedOut : StartOutcome
    }

    /** Asks Android to tether [interfaceName] and waits (≤ [timeoutMs]) for its answer. */
    fun start(
        interfaceName: String,
        timeoutMs: Long,
    ): StartOutcome

    /** Asks Android to stop the request for [interfaceName]; Android's error code, or null for success or no answer within [timeoutMs]. */
    fun stop(
        interfaceName: String,
        timeoutMs: Long,
    ): Int?

    /** Tethered interfaces, name to tethering type. Cheap (a cached callback value); throws while unknown. */
    fun tethered(): Map<String, Int>

    /** Whether a Bootleg interface left the tethered set since the last call (reports in between may hide it). */
    fun takeBootlegDropped(): Boolean
}

/** The RNDIS daemon's medium: whether frames pass and the host's adapter shows a cable plugged in. */
interface RndisMedium {
    /** False while no daemon runs. */
    fun connected(): Boolean

    /** Returns once the daemon applied it (and, off, drained). Throws [IOException]. */
    fun set(connected: Boolean)
}

/** Root's copy of the remembered choice (set like it by Bootleg's requests), which [UsbTetherController.converge] keeps to. */
interface TetherIntentStore {
    fun load(): Boolean

    fun save(on: Boolean)
}

/**
 * Turns Bootleg's USB tethering on or off: Android tethers the prefix's network interface by name
 * (`TETHERING_VIRTUAL`), the RNDIS daemon's TAP or the NCM function's netdev, and stops it again. Neither
 * touches USB: off reports the cable unplugged (Android takes the NCM link down; the RNDIS medium
 * disconnects). Only setting the prefix up (a first use, or a mode change to the other network
 * function) re-plugs USB, so only that is refused while disks are exposed. Callers serialize it with
 * every other gadget operation.
 *
 * Android keeps one configured virtual interface, and a stop by request ends every pending and serving
 * virtual request, other apps' included. So nothing is started or stopped while another app's virtual
 * interface is tethered; one pending at that moment can't be seen (a documented race).
 */
class UsbTetherController(
    /** Null when this Android can't tether an interface by name: tethering is then unsupported. */
    private val port: TetherPort?,
    private val gadget: GadgetController,
    private val clock: Clock,
    private val medium: RndisMedium,
    private val intent: TetherIntentStore,
) {
    /** Bootleg's tethering, as root's callback reports it. */
    fun usbTethered(): Boolean = port?.let { TetherPolicy.bootlegTethered(it.tethered().keys) } ?: false

    fun set(
        on: Boolean,
        mode: TetherMode,
    ): TetherResult {
        val port = port ?: return TetherResult.Failed(false, UNSUPPORTED_REASON)
        val result = if (on) turnOn(port, mode) else turnOff(port)
        // A successful request sets the choice and owns whatever left the tethered set meanwhile; after a
        // refusal or failure an earlier external drop stays for convergence.
        TetherChoice.afterRequest(result)?.let {
            intent.save(it)
            port.takeBootlegDropped()
        }
        return result
    }

    /**
     * Stops Bootleg's tethering when Android reports it on although the last request left it off: a
     * start Android finished after its answer timed out, or a request a dead root process left
     * behind. Never starts anything. A stop outside Bootleg (Android untethered it while the choice was
     * on) turns the choice off, as it does the app's. Callers run it, serialized with [set], whenever
     * root's callback reports a change.
     */
    fun converge(): String? {
        val port = port ?: return null
        val tethered = TetherPolicy.bootlegTethered(port.tethered().keys)
        // A drop since the last check counts even if a late start has tethered it again since.
        if (intent.load() && (port.takeBootlegDropped() || !tethered)) intent.save(false)
        if (intent.load()) return null
        return if (tethered || medium.connected()) stop(port) else null
    }

    private fun turnOn(
        port: TetherPort,
        mode: TetherMode,
    ): TetherResult {
        var setup: ApplyResult? = null
        if (TetherPolicy.interfaceName(gadget.read(), mode) == null) {
            // A first use or a mode change: decided up front, so a refusal leaves the old mode as it was.
            val exposed = TetherPolicy.exposedLuns(gadget.read())
            if (exposed.isNotEmpty()) return TetherResult.Refused(usbTethered(), exposed, TetherPolicy.DISKS_EXPOSED_REASON)
            gadget.establishmentRefusal(mode)?.let { return TetherResult.Failed(usbTethered(), it) }
            // The old network function's interface goes with it; its tethering is stopped first.
            stop(port)?.let { return TetherResult.Failed(usbTethered(), it) }
            setup = gadget.establishPrefix(mode)
        }
        val name =
            TetherPolicy.interfaceName(gadget.read(), mode)
                ?: return TetherResult.Failed(usbTethered(), "$NOT_SET_UP_REASON: $setup")
        val serving = name in port.tethered() && (mode != TetherMode.RNDIS || medium.connected())
        if (serving) return if (setup == null) TetherResult.Unchanged(true) else TetherResult.Changed(true, setup)
        val failure =
            start(port, name, mode)?.let { failure ->
                stop(port)?.let { "$failure (and undoing it failed: $it)" } ?: failure
            }
        return if (failure == null) TetherResult.Changed(usbTethered(), setup) else TetherResult.Failed(usbTethered(), failure)
    }

    private fun turnOff(port: TetherPort): TetherResult {
        if (!TetherPolicy.bootlegTethered(port.tethered().keys) && !medium.connected()) return TetherResult.Unchanged(false)
        val failure = stop(port) ?: return TetherResult.Changed(usbTethered())
        return TetherResult.Failed(usbTethered(), failure)
    }

    /** Why tethering [name] (then, for RNDIS, connecting the medium) failed, or null. */
    private fun start(
        port: TetherPort,
        name: String,
        mode: TetherMode,
    ): String? {
        if (name !in port.tethered()) {
            // One retry, only for a transient error; another app's virtual tethering is checked before each attempt.
            var outcome: TetherPort.StartOutcome = TetherPort.StartOutcome.TimedOut
            for (attempt in 1..TetherLimits.START_ATTEMPT_COUNT_MAX) {
                foreignBlocking(port)?.let { return it }
                outcome = port.start(name, TetherLimits.START_CALLBACK_WAIT_MS_MAX)
                if (!(outcome is TetherPort.StartOutcome.Failed && TetherErrors.isTransient(outcome.errorCode))) break
            }
            val failure =
                when (outcome) {
                    TetherPort.StartOutcome.Started ->
                        "Android didn't tether $name within ${TetherLimits.INTERFACE_WAIT_MS_MAX}ms"
                            .takeUnless { poll { name in port.tethered() } }
                    is TetherPort.StartOutcome.Failed -> "Android refused: ${TetherErrors.describe(outcome.errorCode)}"
                    TetherPort.StartOutcome.TimedOut -> "Android didn't answer within ${TetherLimits.START_CALLBACK_WAIT_MS_MAX}ms"
                }
            if (failure != null) return failure
        }
        if (mode != TetherMode.RNDIS) return null
        return try {
            medium.set(true)
            null
        } catch (e: IOException) {
            "the network adapter didn't connect: ${e.message}"
        }
    }

    /**
     * Why turning everything of Bootleg's off failed, or null: the RNDIS medium first (it must be shown
     * drained), then Android's tethering of every Bootleg interface. A stop that matches no request
     * (another app's stop erased ours) is retried once through a start, which makes ours the request
     * again; nothing else ever starts while turning off.
     */
    private fun stop(port: TetherPort): String? {
        if (medium.connected()) {
            try {
                medium.set(false)
            } catch (e: IOException) {
                return "the network adapter didn't disconnect: ${e.message}"
            }
        }
        val ours = port.tethered().keys.filter(TetherPolicy::isBootlegInterface)
        if (ours.isEmpty()) return null
        for (name in ours) {
            // Checked right before every stop: another app's virtual tethering may have started since.
            foreignBlocking(port)?.let { return it }
            if (port.stop(name, TetherLimits.STOP_CALLBACK_WAIT_MS_MAX) != TetherErrors.UNKNOWN_REQUEST) continue
            if (name !in port.tethered()) continue
            foreignBlocking(port)?.let { return it }
            port.start(name, TetherLimits.START_CALLBACK_WAIT_MS_MAX)
            foreignBlocking(port)?.let { return it }
            port.stop(name, TetherLimits.STOP_CALLBACK_WAIT_MS_MAX)
        }
        if (poll { ours.none { it in port.tethered() } }) return null
        return "Android didn't stop tethering within ${TetherLimits.INTERFACE_WAIT_MS_MAX}ms"
    }

    /** Android's one virtual slot is another app's while its interface is tethered: a start or stop of ours would end it. */
    private fun foreignBlocking(port: TetherPort): String? =
        TetherPolicy.foreignVirtual(port.tethered()).takeIf { it.isNotEmpty() }?.let { "$FOREIGN_VIRTUAL_REASON (${it.joinToString()})" }

    /** Polls [done] every [TetherLimits.POLL_INTERVAL_MS] until it holds or [TetherLimits.INTERFACE_WAIT_MS_MAX] passes. */
    private fun poll(done: () -> Boolean): Boolean {
        val deadlineMs = clock.nowMs() + TetherLimits.INTERFACE_WAIT_MS_MAX
        repeat(POLL_COUNT_MAX) {
            if (done()) return true
            val remainingMs = deadlineMs - clock.nowMs()
            if (remainingMs <= 0) return false
            clock.sleepMs(minOf(TetherLimits.POLL_INTERVAL_MS, remainingMs))
        }
        return done()
    }

    companion object {
        const val UNSUPPORTED_REASON = "This Android can't tether an interface by name, so USB tethering is unsupported"
        const val NOT_SET_UP_REASON = "Bootleg's USB functions couldn't be set up"
        const val FOREIGN_VIRTUAL_REASON = "Another app is using virtual tethering; stop it first"

        /** Bounds the loop independently of the clock. */
        private const val POLL_COUNT_MAX = (TetherLimits.INTERFACE_WAIT_MS_MAX / TetherLimits.POLL_INTERVAL_MS).toInt() + 1
    }
}
