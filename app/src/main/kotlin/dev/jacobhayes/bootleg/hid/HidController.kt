package dev.jacobhayes.bootleg.hid

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import dev.jacobhayes.bootleg.BootlegBackend
import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.HidSpec
import dev.jacobhayes.bootleg.core.hid.BootKey
import dev.jacobhayes.bootleg.core.hid.HidKind
import dev.jacobhayes.bootleg.core.hid.HidTestSequence
import dev.jacobhayes.bootleg.core.hid.KeyStroke
import dev.jacobhayes.bootleg.core.hid.KeyUsage
import dev.jacobhayes.bootleg.core.hid.KeyboardInput
import dev.jacobhayes.bootleg.core.hid.KeyboardLeds
import dev.jacobhayes.bootleg.core.hid.KeyboardReport
import dev.jacobhayes.bootleg.core.hid.Modifier
import dev.jacobhayes.bootleg.core.hid.ModifierChipState
import dev.jacobhayes.bootleg.core.hid.MouseButton
import dev.jacobhayes.bootleg.core.hid.MouseInput
import dev.jacobhayes.bootleg.core.hid.MouseReport
import dev.jacobhayes.bootleg.core.hid.TextTyper
import dev.jacobhayes.bootleg.core.hid.TouchpadAction
import dev.jacobhayes.bootleg.core.hid.UsLayout
import dev.jacobhayes.bootleg.core.remote.HidPort
import dev.jacobhayes.bootleg.core.remote.HidSendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** A long-running HID action (paste-as-typing, boot keys) with progress, one at a time. */
data class HidJobProgress(
    val label: String,
    val fraction: Float,
    val error: String? = null,
)

/** Result of a debug test sequence, for the L4 check. */
data class HidTestResult(
    val sequence: String,
    val queued: Int,
    val written: Long,
    val dropped: Long,
    val link: HidLinkState,
)

/**
 * The phone-as-keyboard/mouse feature: enables Bootleg's HID functions through the backend, owns
 * the [HidSession] while enabled, and turns UI input into reports. Input methods must be called on
 * the main thread; the session writes from its own thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HidController(
    private val backend: BootlegBackend,
    private val scope: CoroutineScope,
) {
    private val keyboard = KeyboardInput()
    private val mouse = MouseInput()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val enableMutex = Mutex()

    // Written on the main thread; the remote port reads it from the remote actor's thread.
    @Volatile private var session: HidSession? = null
    private val remoteConnectRequested = AtomicBoolean(false)

    /**
     * While Remote is on the session stays open when the app goes to the background (remote input
     * needs it); backgrounding then only releases everything.
     */
    @Volatile var keepSessionInBackground: Boolean = false

    /** Main thread only: true while [setSpec] changes the gadget; no session may open meanwhile. */
    private var gadgetChanging = false
    private var sessionWatchers: Job? = null
    private var activeJob: Job? = null

    private val linkFlow = MutableStateFlow<HidLinkState>(HidLinkState.Stopped)
    private val ledsFlow = MutableStateFlow<KeyboardLeds?>(null)
    private val statsFlow = MutableStateFlow(HidSessionStats())
    private val chipsFlow = MutableStateFlow(chipStates())
    private val busyFlow = MutableStateFlow(false)
    private val messageFlow = MutableStateFlow<String?>(null)
    private val jobFlow = MutableStateFlow<HidJobProgress?>(null)

    /** What the journal says is exposed (the switch position). */
    val spec: StateFlow<HidSpec> =
        backend.journal
            .map {
                it.desired.hid
            }.stateIn(scope, SharingStarted.Eagerly, backend.journal.value.desired.hid)
    val link: StateFlow<HidLinkState> = linkFlow.asStateFlow()
    val leds: StateFlow<KeyboardLeds?> = ledsFlow.asStateFlow()
    val stats: StateFlow<HidSessionStats> = statsFlow.asStateFlow()
    val chips: StateFlow<Map<Modifier, ModifierChipState>> = chipsFlow.asStateFlow()

    /** True while the gadget is being changed. */
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()
    val message: StateFlow<String?> = messageFlow.asStateFlow()
    val job: StateFlow<HidJobProgress?> = jobFlow.asStateFlow()

    /** Releases everything when no activity is visible any more (app backgrounded, screen locked). */
    val backgroundWatcher: Application.ActivityLifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            private var startedCount = 0

            override fun onActivityStarted(activity: Activity) {
                startedCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount = (startedCount - 1).coerceAtLeast(0)
                if (startedCount == 0) onBackground()
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    fun dismissMessage() {
        messageFlow.value = null
    }

    /** Turns both functions on or off (the Control screen switch). */
    fun setEnabled(enabled: Boolean) {
        scope.launch { setSpec(if (enabled) HidSpec(keyboard = true, mouse = true) else HidSpec()) }
    }

    suspend fun setSpec(target: HidSpec): ApplyResult = withContext(Dispatchers.Main.immediate) { setSpecOnMain(target) }

    private suspend fun setSpecOnMain(target: HidSpec): ApplyResult =
        enableMutex.withLock {
            busyFlow.value = true
            try {
                cancelJob()
                // Release and close before the gadget changes, and open nothing until it's done: a hidg
                // fd open across the change panics the kernel when it is closed.
                gadgetChanging = true
                val result =
                    try {
                        stopSession()
                        backend.setHid(target)
                    } finally {
                        gadgetChanging = false
                    }
                // Partial means some drive slots failed; the HID functions were still applied.
                val applied = result is ApplyResult.Applied || result is ApplyResult.Partial
                if (!applied) messageFlow.value = "Couldn't change keyboard and mouse: ${describe(result)}"
                if (applied && (target.keyboard || target.mouse)) ensureSession(target)
                result
            } finally {
                busyFlow.value = false
            }
        }

    /** Opens the devices if HID is enabled (the Control screen calls this so its status is live). */
    fun connect() {
        ensureSession()
    }

    fun tapModifier(modifier: Modifier) = sendKeyboard(keyboard.tapModifier(modifier))

    fun holdModifier(modifier: Modifier) = sendKeyboard(keyboard.holdModifier(modifier))

    fun releaseModifier(modifier: Modifier) = sendKeyboard(keyboard.releaseModifier(modifier))

    fun tapKey(key: KeyUsage) = sendKeyboard(keyboard.tapKey(key))

    fun chord(
        modifiers: Set<Modifier>,
        key: KeyUsage,
    ) = sendKeyboard(keyboard.chord(modifiers, key))

    /**
     * Types the difference between two versions of the on-screen text field: deleted characters
     * become Backspace, inserted ones are typed. Returns the characters that a US layout can't type.
     */
    fun typeEdit(
        before: String,
        after: String,
    ): List<Char> {
        val edit = TextEdit.between(before, after)
        if (edit.inserted.length > IME_EDIT_CHARS_MAX) {
            messageFlow.value = "That's a lot of text at once. Use Paste as typing for long text."
            return emptyList()
        }
        repeat(edit.deletedCount) { tapKey(KeyUsage.BACKSPACE) }
        val unsupported = mutableListOf<Char>()
        for (char in edit.inserted) {
            val stroke: KeyStroke? = UsLayout.strokeFor(char)
            if (stroke == null) unsupported += char else sendKeyboard(keyboard.typeStroke(stroke))
        }
        return unsupported
    }

    fun touchpad(actions: List<TouchpadAction>) {
        for (action in actions) sendMouse(mouse.apply(action))
    }

    fun click(button: MouseButton) = sendMouse(mouse.click(button))

    /** Paste-as-typing. Unsupported characters are skipped (the UI lists them before starting). */
    fun typeText(text: String) {
        val plan = TextTyper.plan(text)
        if (plan.reports.isEmpty()) return
        startJob("Typing ${text.length} characters") { progress ->
            sendKeyboard(listOf(KeyboardReport.RELEASE_ALL))
            keyboard.reset()
            plan.reports.forEachIndexed { index, report ->
                if (!sendKeyboard(listOf(report))) error("the computer stopped accepting keys")
                progress((index + 1).toFloat() / plan.reports.size)
                delay(TYPING_REPORT_INTERVAL_MS)
            }
        }
    }

    /** Runs a vendor boot key for [durationMs] (mashing a key, or holding a modifier). Missed reports are expected while the target resets. */
    fun runBootKey(
        key: BootKey,
        durationMs: Long,
    ) {
        val plan = BootKeyActions.plan(key, durationMs) ?: return
        val reports = BootKeyActions.withHoldResends(plan)
        startJob("${BootKeyActions.label(key)} for ${durationMs / 1000} s") { progress ->
            keyboard.reset()
            val startMs = SystemClock.elapsedRealtime()
            for (timed in reports) {
                val waitMs = timed.atMs - (SystemClock.elapsedRealtime() - startMs)
                if (waitMs > 0) delay(waitMs)
                sendKeyboard(listOf(timed.report))
                progress((timed.atMs.toFloat() / plan.durationMs).coerceIn(0f, 1f))
            }
        }
    }

    fun cancelJob() {
        activeJob?.cancel()
        activeJob = null
        jobFlow.value = null
    }

    /** Debug/L4 only: sends one harmless fixed sequence and waits for it to be written. */
    suspend fun sendTestSequence(sequence: HidTestSequence): HidTestResult =
        withContext(Dispatchers.Main.immediate) { sendTestSequenceOnMain(sequence) }

    private suspend fun sendTestSequenceOnMain(sequence: HidTestSequence): HidTestResult {
        val active = ensureSession() ?: error("keyboard and mouse are off")
        withTimeoutOrNull(TEST_LINK_WAIT_MS) { active.state.first { it == HidLinkState.Open(hostListening = true) } }
            ?: error("HID link not ready: ${active.state.value}")
        active.awaitIdle(TEST_DRAIN_TIMEOUT_MS)
        val before = active.stats.value
        val queued = sequence.reports.count { (kind, report) -> active.send(kind, report) }
        active.awaitIdle(TEST_DRAIN_TIMEOUT_MS)
        val after = active.stats.value
        return HidTestResult(
            sequence.wireName,
            queued,
            after.reportsWritten - before.reportsWritten,
            after.reportsDropped - before.reportsDropped,
            active.state.value,
        )
    }

    fun onBackground() {
        cancelJob()
        if (keepSessionInBackground) {
            session?.releaseAll()
        } else {
            scope.launch { stopSession() }
        }
    }

    /**
     * The remote controller's view of the HID session. Nonblocking and callable from any thread. A
     * report while no session is open asks the main thread to open one and reports UNAVAILABLE (the
     * controller then waits for the owed release-all before taking more input).
     */
    val remotePort: HidPort =
        object : HidPort {
            override val releaseOwed: Boolean get() = session?.releaseOwed ?: false

            override fun send(
                kind: HidKind,
                report: ByteArray,
            ): HidSendResult {
                val active = session
                if (active == null || kind !in active.kinds || !active.isRunning) {
                    if (remoteConnectRequested.compareAndSet(false, true)) {
                        scope.launch {
                            remoteConnectRequested.set(false)
                            ensureSession()
                        }
                    }
                    return HidSendResult.UNAVAILABLE
                }
                return when (active.offer(kind, report)) {
                    HidOffer.ACCEPTED -> HidSendResult.ACCEPTED
                    HidOffer.QUEUE_FULL -> HidSendResult.QUEUE_FULL
                    HidOffer.UNAVAILABLE -> HidSendResult.UNAVAILABLE
                }
            }

            override fun releaseAll() {
                session?.releaseAll()
            }
        }

    private fun startJob(
        label: String,
        body: suspend (progress: (Float) -> Unit) -> Unit,
    ) {
        cancelJob()
        if (ensureSession() == null) return
        jobFlow.value = HidJobProgress(label, 0f)
        activeJob =
            scope.launch {
                try {
                    body { fraction -> jobFlow.value = HidJobProgress(label, fraction) }
                    jobFlow.value = null
                } catch (e: IllegalStateException) {
                    jobFlow.value = HidJobProgress(label, jobFlow.value?.fraction ?: 0f, error = e.message)
                } finally {
                    // Whatever happened, never leave a key down.
                    session?.releaseAll()
                }
            }
    }

    private fun sendKeyboard(reports: List<KeyboardReport>): Boolean =
        reports.all { send(HidKind.KEYBOARD, it.encode()) }.also {
            chipsFlow.value =
                chipStates()
        }

    private fun sendMouse(reports: List<MouseReport>): Boolean = reports.all { send(HidKind.MOUSE, it.encode()) }

    private fun send(
        kind: HidKind,
        report: ByteArray,
    ): Boolean {
        val active = ensureSession() ?: return false
        return kind in active.kinds && active.send(kind, report)
    }

    /** Starts a session for the enabled functions if there isn't one; null when HID is off or the gadget is changing. */
    private fun ensureSession(enabled: HidSpec = spec.value): HidSession? {
        if (gadgetChanging) return null
        val kinds =
            buildSet {
                if (enabled.keyboard) add(HidKind.KEYBOARD)
                if (enabled.mouse) add(HidKind.MOUSE)
            }
        if (kinds.isEmpty()) return null
        session?.let { if (it.kinds == kinds) return it.also { existing -> existing.start() } }
        val opener = HidEndpointOpener { kind -> backend.openHid(kind) }
        val created = HidSession(opener, sessionScope, kinds, SystemClock::elapsedRealtime)
        session?.let { stale -> sessionScope.launch { stale.stop() } }
        session = created
        sessionWatchers?.cancel()
        sessionWatchers =
            // Undispatched so the relay notices are subscribed before the session's first open: the
            // flow has no replay, and a quarantine refusal must not be missed.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                launch(start = CoroutineStart.UNDISPATCHED) { backend.hidRelayNotices.collect(created::onRelayNotice) }
                launch { created.state.collect { linkFlow.value = it } }
                launch { created.leds.collect { ledsFlow.value = it } }
                launch { created.stats.collect { statsFlow.value = it } }
                launch {
                    created.releaseEpoch.collect {
                        keyboard.reset()
                        mouse.reset()
                        chipsFlow.value = chipStates()
                    }
                }
            }
        created.start()
        return created
    }

    private suspend fun stopSession() {
        val stopping = session ?: return
        session = null
        stopping.stop()
        sessionWatchers?.cancel()
        sessionWatchers = null
        linkFlow.value = HidLinkState.Stopped
        keyboard.reset()
        mouse.reset()
        chipsFlow.value = chipStates()
    }

    private fun chipStates(): Map<Modifier, ModifierChipState> = Modifier.entries.associateWith { keyboard.chipState(it) }

    private fun describe(result: ApplyResult): String =
        when (result) {
            is ApplyResult.Applied -> "applied"
            is ApplyResult.Failed -> result.reason
            is ApplyResult.NeedsAttention -> result.reason
            is ApplyResult.Partial -> "some drives failed"
        }

    companion object {
        /** Paste-as-typing pace: one report every 8 ms is ~60 characters a second, well within what hosts accept. */
        const val TYPING_REPORT_INTERVAL_MS = 8L

        /** The on-screen field types edits directly; bigger edits (a paste into it) go through Paste as typing. */
        const val IME_EDIT_CHARS_MAX = 64

        const val TEST_LINK_WAIT_MS = 10_000L
        const val TEST_DRAIN_TIMEOUT_MS = 2_000L
    }
}

/** A text change as a suffix replacement: delete [deletedCount] characters, then type [inserted]. */
data class TextEdit(
    val deletedCount: Int,
    val inserted: String,
) {
    companion object {
        fun between(
            before: String,
            after: String,
        ): TextEdit {
            val common = before.commonPrefixWith(after).length
            return TextEdit(before.length - common, after.substring(common))
        }
    }
}
