package dev.jacobhayes.bootleg.core.gadget

import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.PriorityQueue
import kotlin.random.Random

/** Thrown by [ScriptedConfigFs] to simulate the executor process dying. An Error, so nothing catches it. */
class SimulatedCrash(
    mutationIndex: Int,
) : Error("simulated crash at mutation $mutationIndex")

/**
 * What each configfs call costs on the device with in-process root file IO (no shell per op). UDC
 * writes dominate: they tear down or start the whole gadget. Measured on the Pixel 6 (root shell
 * builtins timed with EPOCHREALTIME, no fork in the timed span, 5 cycles with ADB linked):
 * unbind 9–20ms, bind 61–76ms, so the costs below sit at the slow end of each. The other costs are
 * estimates for small configfs attribute IO (a LUN `file` write opens the image).
 */
data class OpCostsUs(
    val read: Long = 50,
    val write: Long = 150,
    val lunFileWrite: Long = 2_000,
    val mkdir: Long = 1_000,
    val rmdir: Long = 1_000,
    val link: Long = 300,
    val unbind: Long = 20_000,
    val bind: Long = 70_000,
) {
    companion object {
        val FREE = OpCostsUs(0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * Wraps a [ConfigFs]: advances [clock] by each call's cost (so HAL timers fire between ops), runs
 * [beforeCall] before every call (HAL interference) and, by mutation index, injects IOExceptions
 * ([faultAt]) or a crash ([crashAt]). The failing mutation is not performed.
 */
class ScriptedConfigFs(
    private val delegate: ConfigFs,
    private val clock: FakeClock? = null,
    private val costs: OpCostsUs = OpCostsUs(),
    private val beforeCall: (call: String) -> Unit = {},
) : ConfigFs {
    var faultAt: Set<Int> = emptySet()
    var crashAt: Int? = null
    var mutationCount = 0
        private set

    private fun call(
        description: String,
        costUs: Long,
    ) {
        (delegate as? FakeConfigFs)?.callStartUs = clock?.nowUs
        clock?.advanceUs(costUs)
        beforeCall(description)
    }

    private fun mutation(
        description: String,
        costUs: Long,
    ) {
        call(description, costUs)
        val index = mutationCount++
        if (index == crashAt) throw SimulatedCrash(index)
        if (index in faultAt) throw IOException("EIO: injected fault at mutation $index ($description)")
    }

    private fun writeCost(
        path: String,
        value: String,
    ): Long =
        when {
            path == GadgetPaths.UDC -> if (value.isEmpty()) costs.unbind else costs.bind
            path.endsWith("/file") && value.isNotEmpty() -> costs.lunFileWrite
            else -> costs.write
        }

    override fun exists(path: String): Boolean {
        call("exists $path", costs.read)
        return delegate.exists(path)
    }

    override fun list(path: String): List<String> {
        call("list $path", costs.read)
        return delegate.list(path)
    }

    override fun read(path: String): String {
        call("read $path", costs.read)
        return delegate.read(path)
    }

    override fun readLink(path: String): String? {
        call("readLink $path", costs.read)
        return delegate.readLink(path)
    }

    override fun write(
        path: String,
        value: String,
    ) {
        mutation("write $path", writeCost(path, value))
        delegate.write(path, value)
    }

    override fun writeBytes(
        path: String,
        bytes: ByteArray,
    ) {
        mutation("writeBytes $path", costs.write)
        delegate.writeBytes(path, bytes)
    }

    override fun mkdir(path: String) {
        mutation("mkdir $path", costs.mkdir)
        delegate.mkdir(path)
    }

    override fun rmdir(path: String) {
        mutation("rmdir $path", costs.rmdir)
        delegate.rmdir(path)
    }

    override fun symlink(
        target: String,
        link: String,
    ) {
        mutation("symlink $link", costs.link)
        delegate.symlink(target, link)
    }

    override fun unlink(link: String) {
        mutation("unlink $link", costs.link)
        delegate.unlink(link)
    }
}

/** Microsecond clock with timers that fire, in order, as time advances (by op costs or sleeps). */
class FakeClock(
    startMs: Long = 1_000_000L,
) : Clock {
    private class Timer(
        val atUs: Long,
        val sequence: Long,
        val action: () -> Unit,
    )

    var nowUs = startMs * 1_000
        private set
    val nowMs: Long get() = nowUs / 1_000
    var sleptMs = 0L
        private set
    private val timers = PriorityQueue<Timer>(compareBy<Timer>({ it.atUs }, { it.sequence }))
    private var timerSequence = 0L

    override fun nowMs(): Long = nowMs

    override fun sleepMs(durationMs: Long) {
        require(durationMs >= 0)
        advanceUs(durationMs * 1_000)
        sleptMs += durationMs
    }

    fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ) {
        require(delayMs >= 0)
        timers += Timer(nowUs + delayMs * 1_000, timerSequence++, action)
    }

    fun advanceUs(durationUs: Long) {
        require(durationUs >= 0)
        val endUs = nowUs + durationUs
        repeat(TIMER_FIRE_COUNT_MAX) {
            val next = timers.peek()
            if (next == null || next.atUs > endUs) {
                nowUs = endUs
                return
            }
            timers.poll()
            nowUs = maxOf(nowUs, next.atUs)
            next.action()
        }
        error("more than $TIMER_FIRE_COUNT_MAX timers fired in one advance")
    }

    private companion object {
        const val TIMER_FIRE_COUNT_MAX = 10_000
    }
}

/**
 * The Pixel USB HAL as measured on the device:
 * - after any unbind it didn't make, it rebinds the UDC [rebindDelayMs] later (350ms typical, up to
 *   ~700ms when adbd reopens FunctionFS slowly); unlinking from a bound config counts, since the
 *   kernel unbinds;
 * - [postBindDropDelayMs] after a bind it didn't make, it drops the UDC once and rebinds
 *   [postBindRebindDelayMs] after that;
 * - on `setFunctions` it unbinds, rewrites only `functionN` links, and rebinds.
 * Also serves sys.usb.state/config, which differ for [SETTLE_MS] after each switch.
 */
class FakeHal(
    private val fs: FakeConfigFs,
    private val clock: FakeClock,
    seed: Long = 0,
) : UsbProps {
    private val random = Random(seed)

    /** sys.usb.config; tests may set it directly (e.g. empty on some devices). */
    var config = "adb"
    private var busyUntilMs = Long.MIN_VALUE

    /** Random interference budget for [maybeInterfere]; the HAL goes quiet once it is spent. */
    var interferenceRemaining = 0
    var interferenceProbability = 0.05
    var switchCount = 0
        private set

    /** Null turns the automatic rebind off. */
    var rebindDelayMs: LongRange? = REBIND_DELAY_MS
    var postBindDropDelayMs: LongRange? = POST_BIND_DROP_DELAY_MS
    var postBindRebindDelayMs: LongRange = POST_BIND_REBIND_DELAY_MS
    var rebindCount = 0
        private set
    var dropCount = 0
        private set

    /** The Pixel 6 leaves sys.usb.state empty; other devices report it. */
    var reportsUsbState = false
    private var busyReadCount = 0L

    private val busy: Boolean get() = clock.nowMs() < busyUntilMs

    init {
        fs.onUdcChange = { bound, actor -> if (actor != FakeConfigFs.HAL) onUdcChange(bound) }
    }

    override fun usbState(): String =
        when {
            !reportsUsbState -> ""
            busy -> "none"
            else -> config
        }

    /** While switching, sys.usb.config flips between "none" and the target, as init applies it. */
    override fun usbConfig(): String = if (busy && busyReadCount++ % 2 == 0L) "none" else config

    private fun onUdcChange(bound: Boolean) {
        if (!bound) {
            rebindDelayMs?.let { range -> clock.schedule(delayIn(range)) { rebindIfUnbound() } }
            return
        }
        val dropDelayMs = postBindDropDelayMs?.let(::delayIn) ?: return
        clock.schedule(dropDelayMs) {
            if (fs.udc.isNotEmpty()) {
                dropCount++
                fs.asActor(FakeConfigFs.HAL) { fs.write(GadgetPaths.UDC, "") }
                clock.schedule(delayIn(postBindRebindDelayMs)) { rebindIfUnbound() }
            }
        }
    }

    private fun delayIn(range: LongRange): Long = random.nextLong(range.first, range.last + 1)

    private fun rebindIfUnbound() {
        if (fs.udc.isNotEmpty()) return
        fs.asActor(FakeConfigFs.HAL) {
            try {
                fs.write(GadgetPaths.UDC, fs.udcName)
                rebindCount++
            } catch (_: IOException) {
                // Nothing bindable (or a function refuses); the real HAL gives up too.
            }
        }
    }

    fun maybeInterfere() {
        if (interferenceRemaining <= 0 || random.nextDouble() >= interferenceProbability) return
        interferenceRemaining--
        setFunctions(listOf("adb", "mtp,adb", "ptp,adb").random(random))
    }

    fun setFunctions(newConfig: String) =
        fs.asActor(FakeConfigFs.HAL) {
            switchCount++
            if (fs.udc.isNotEmpty()) fs.write(GadgetPaths.UDC, "")
            fs.list(GadgetNames.CONFIG_DIR).filter { FUNCTION_LINK.matches(it) }.forEach { fs.unlink(GadgetPaths.configLink(it)) }
            newConfig.split(',').forEachIndexed { index, name ->
                fs.symlink(GadgetPaths.function("ffs.$name"), GadgetPaths.configLink("function$index"))
            }
            try {
                fs.write(GadgetPaths.UDC, fs.udcName)
            } catch (_: IOException) {
                // The real HAL retries later; the controller's reconcile binds in the meantime.
            }
            config = newConfig
            busyUntilMs = clock.nowMs() + SETTLE_MS
        }

    /** Makes sys.usb.state lag sys.usb.config for [durationMs] from now. */
    fun busyFor(durationMs: Long) {
        busyUntilMs = clock.nowMs() + durationMs
    }

    companion object {
        const val SETTLE_MS = 500L
        val REBIND_DELAY_MS = 350L..700L

        // Measured: the drop came 4ms and 560ms after our bind, the rebind 560ms to ~1s after the drop.
        val POST_BIND_DROP_DELAY_MS = 5L..600L
        val POST_BIND_REBIND_DELAY_MS = 500L..1_000L
        private val FUNCTION_LINK = Regex("function[0-9]+")
    }
}

/** Round-trips through JSON so the journal's serializability is exercised too. Each save costs an fsync. */
class InMemoryJournalStore(
    private val clock: FakeClock? = null,
    private val saveCostUs: Long = 0,
) : JournalStore {
    private var json: String? = null
    var saveCount = 0
        private set

    override fun load(): Journal = json?.let { Json.decodeFromString(Journal.serializer(), it) } ?: Journal()

    override fun save(journal: Journal) {
        clock?.advanceUs(saveCostUs)
        json = Json.encodeToString(Journal.serializer(), journal)
        saveCount++
    }
}

/** Records arms; [fire] runs the detached rebind as the real shell would (bind only if still unbound). */
class RecordingDeadMan(
    private val fs: FakeConfigFs,
    private val clock: FakeClock? = null,
    private val armCostUs: Long = 0,
) : DeadMan {
    val arms = mutableListOf<Pair<String, Int>>()

    /** Whether the UDC was still bound when each arm happened. */
    val boundAtArm = mutableListOf<Boolean>()

    override fun arm(
        udc: String,
        delaySeconds: Int,
    ) {
        // Forking the detached shell takes a while; the HAL may act meanwhile.
        clock?.advanceUs(armCostUs)
        arms += udc to delaySeconds
        boundAtArm += fs.udc.isNotEmpty()
    }

    fun fire() {
        val (udc, _) = arms.lastOrNull() ?: return
        fs.asActor(FakeConfigFs.DEAD_MAN) {
            if (fs.udc.isEmpty()) {
                try {
                    fs.write(GadgetPaths.UDC, udc)
                } catch (_: IOException) {
                    // The shell's echo fails silently, like the real one.
                }
            }
        }
    }
}

/**
 * The root HID relay as [HidNodeGuard] sees it, over [FakeConfigFs]'s fd model: [reopen] is the app's
 * session opening its endpoints after a controller call (when the root service lifts the fence).
 */
class FakeHidNodes(
    private val fs: FakeConfigFs,
) : HidNodeGuard {
    var fenced = false
        private set
    var closeAllCount = 0
        private set
    var holderScanCount = 0
        private set

    /** Releases written before closing, in order, so tests can check they reached the host while bound. */
    val releasesWhileBound = mutableListOf<Boolean>()

    override fun closeAll() {
        closeAllCount++
        fenced = true
        for (function in RELAY_FUNCTIONS) {
            if (RELAY !in fs.hidFdHolders(function) || function in quarantined) continue
            // Like the relay's binding check: an fd someone else rebound under is kept open forever.
            if (fs.isPoisoned(function, RELAY)) {
                quarantined += function
                continue
            }
            releasesWhileBound += fs.udc.isNotEmpty()
            check(fs.closeHidFd(function, RELAY))
        }
    }

    /** Functions whose relay fd crossed someone else's rebind; still open (the holder scan sees them). */
    val quarantined = mutableSetOf<String>()

    override fun holders(functions: Collection<String>): List<String> {
        holderScanCount++
        return functions.flatMap { function -> fs.hidFdHolders(function).map { "$it ($function)" } }
    }

    /** The controller call ended: the fence lifts and the session opens whatever is linked and bound. */
    fun reopen() {
        fenced = false
        // The relay refuses opens once anything is quarantined.
        if (fs.udc.isEmpty() || quarantined.isNotEmpty()) return
        val state = GadgetReader(fs).read()
        for (function in RELAY_FUNCTIONS.filter { it in state.configLinks.values }) {
            if (RELAY !in fs.hidFdHolders(function)) fs.openHidFd(function, RELAY)
        }
    }

    /** The root process died: the kernel closed its fds. */
    fun processDied() {
        fs.exitHidHolder(RELAY)
        fenced = false
    }

    fun relayOpenCount(): Int = RELAY_FUNCTIONS.sumOf { function -> fs.hidFdHolders(function).count { it == RELAY } }

    companion object {
        const val RELAY = "relay"
        val RELAY_FUNCTIONS = listOf(GadgetNames.HID_KEYBOARD_FUNCTION) + MouseLayout.entries.map { it.functionName }
    }
}

object FakeHidDescriptors : HidDescriptorProvider {
    override fun keyboard(): ByteArray = byteArrayOf(0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0xC0.toByte())

    override fun mouse(layout: MouseLayout): ByteArray =
        byteArrayOf(0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, layout.reportLengthBytes.toByte(), 0xC0.toByte())
}

/**
 * A Pixel-like gadget with the measured HAL behaviour (automatic rebinds, post-bind drop), realistic
 * op costs on the clock, a journal and a dead-man wired up for controller tests.
 */
class GadgetRig(
    seed: Long = 0,
    hostFiles: Set<String> = HOST_FILES,
    costs: OpCostsUs = OpCostsUs(),
) {
    val fs = FakeConfigFs.pixel(hostFiles)
    val clock = FakeClock()
    val hal = FakeHal(fs, clock, seed)
    val scripted = ScriptedConfigFs(fs, clock, costs) { hal.maybeInterfere() }
    val journalStore = InMemoryJournalStore(clock, JOURNAL_SAVE_COST_US)
    var deadMan = RecordingDeadMan(fs, clock, DEAD_MAN_ARM_COST_US)
        private set
    val hidNodes = FakeHidNodes(fs)

    init {
        fs.nowUs = { clock.nowUs }
    }

    /** A fresh controller process over the same kernel state and journal. */
    fun controller(): DefaultGadgetController {
        deadMan = RecordingDeadMan(fs, clock, DEAD_MAN_ARM_COST_US)
        return DefaultGadgetController(
            scripted,
            hal,
            journalStore,
            deadMan,
            clock,
            FakeHidDescriptors,
            hidNodes,
            newLunInstanceNonce = { NONCE },
        )
    }

    fun state(): GadgetState = GadgetReader(fs, hal).read()

    fun journal(): Journal = journalStore.load()

    companion object {
        const val JOURNAL_SAVE_COST_US = 5_000L
        const val DEAD_MAN_ARM_COST_US = 20_000L

        /**
         * Longest allowed unbound window, from the start of the unbind write to the return of the bind
         * write; the device measured 70–94ms for unbind → relink → bind. In the model a window is the two
         * UDC writes (20ms + 70ms) plus at most 6 relinks with their UDC and link re-reads (0.6ms each),
         * about 94ms. Anything that must stay outside the window would push it over: a journal fsync
         * (5ms), a dead-man fork (20ms), a stable-bound poll (50ms) or a getprop (110ms). The HAL's
         * earliest rebind comes 350ms after the unbind.
         */
        const val SWITCH_WINDOW_US_MAX = 95_000L

        /** The rig's LUN instance nonce, and the `inquiry_string` its controllers write. */
        const val NONCE = "r1g0"
        val MARKER = LunMarker.inquiryString(NONCE)

        val HOST_FILES: Set<String> =
            (0 until 16).flatMap { listOf("/data/media/0/bootleg/image$it.iso", "/data/media/0/bootleg/disk$it.img") }.toSet()

        fun iso(index: Int) = "/data/media/0/bootleg/image$index.iso"

        fun img(index: Int) = "/data/media/0/bootleg/disk$index.img"
    }
}
