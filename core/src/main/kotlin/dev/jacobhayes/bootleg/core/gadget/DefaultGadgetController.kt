package dev.jacobhayes.bootleg.core.gadget

import java.io.IOException

/**
 * Plans, applies and verifies gadget changes over a [ConfigFs]. Every phase is journaled before it
 * starts so a fresh instance can finish an interrupted transition in [reconcile]. Not thread-safe:
 * callers serialize.
 */
class DefaultGadgetController(
    private val fs: ConfigFs,
    private val props: UsbProps,
    private val journalStore: JournalStore,
    private val deadMan: DeadMan,
    private val clock: Clock,
    hidDescriptors: HidDescriptorProvider,
    private val hidNodes: HidNodeGuard,
    private val allowedRoots: List<String> = GadgetInvariants.ALLOWED_ROOTS_DEFAULT,
    private val newLunInstanceNonce: () -> String = LunMarker::newNonce,
    /** Size of the file at a kernel path (root: fstat of the confined open), or null if it can't be read. */
    private val fileSizeBytes: (path: String) -> Long? = { null },
) : GadgetController {
    private val reader = GadgetReader(fs)
    private val planner = GadgetPlanner(hidDescriptors)
    private var lastReconcileMs: Long? = null
    private var reconcileAttemptCount = 0

    /**
     * When this controller last bound the UDC, until a stable-bound wait confirms the HAL's post-bind
     * drop and rebind are over. While set, nothing runs bound-phase ops or verifies.
     */
    private var ownBindMs: Long? = null

    init {
        GadgetInvariants.requireAllowedRoots(allowedRoots)
    }

    override fun read(): GadgetState = GadgetReader(fs, props).read()

    /** Applies [desired]. Slots that were host- or user-ejected and are unchanged stay ejected; use [reinsert]. */
    override fun apply(desired: Desired): ApplyResult {
        GadgetInvariants.requireValid(desired, allowedRoots)
        reconcileAttemptCount = 0
        val quiet = awaitHalQuiet() ?: return halBusy()
        settleBound(knownUdc(), quiet.boundSinceMs)?.let { return it.result() }
        val state = reader.read()
        val previous = absorbHostEjects(journalStore.load(), state)
        val intents =
            desired.slots.associate { slot ->
                val previousIntent = previous.slotIntents[slot.lun] ?: SlotIntent.EXPORTED
                val keepEjected = previousIntent in EJECTED_INTENTS && previous.desired.slots.getOrNull(slot.lun) == slot
                slot.lun to if (keepEjected) previousIntent else SlotIntent.EXPORTED
            }
        val journal = Journal(desired, intents, emptyMap(), previous.phase, state.udc.ifEmpty { previous.udc }, previous.lunInstanceNonce)
        return converge(journal, state, foreignBefore = state, allowEmergencyOff = true)
    }

    /**
     * [apply], then [reconnectAfterMediaChange]: with [reconnect] (the Settings switch), a disk that went
     * in or changed in place is followed by a re-plug, so hosts that don't poll for media notice it.
     */
    fun apply(
        desired: Desired,
        reconnect: Boolean,
    ): ApplyResult {
        val before = reader.read()
        val result = apply(desired)
        return if (reconnect) reconnectAfterMediaChange(before, result) else result
    }

    /** [reinsert], then [reconnectAfterMediaChange] as for [apply]. */
    fun reinsert(
        lun: Int,
        reconnect: Boolean,
    ): ApplyResult {
        val before = reader.read()
        val result = reinsert(lun)
        return if (reconnect) reconnectAfterMediaChange(before, result) else result
    }

    /**
     * Re-plugs USB (one re-enumeration with the same gadget) when [result] put media into a LUN in place,
     * without re-enumerating: some hosts (Windows with media-change polling off) never look again. Skipped
     * while any LUN has writable media, the changed one included: a re-plug pulls a volume the host may
     * have mounted and be writing to. Called under the same lock as the change, so nothing comes between.
     */
    fun reconnectAfterMediaChange(
        before: GadgetState,
        result: ApplyResult,
    ): ApplyResult {
        // A partial result doesn't say whether it already re-enumerated; one more re-plug isn't worth guessing.
        if (result !is ApplyResult.Applied || result.reenumerated) return result
        val state = reader.read()
        val changed = state.luns.any { (lun, after) -> after.file.isNotEmpty() && before.luns[lun]?.file != after.file }
        if (!changed || state.luns.values.any { it.file.isNotEmpty() && !it.ro } || state.udc.isEmpty()) return result
        val journal = journalStore.load()
        return converge(journal, state, foreignBefore = state, allowEmergencyOff = true, reenumerate = true)
    }

    /** Re-exports an ejected or failed slot's media. */
    fun reinsert(lun: Int): ApplyResult {
        val quiet = awaitHalQuiet() ?: return halBusy()
        settleBound(knownUdc(), quiet.boundSinceMs)?.let { return it.result() }
        val state = reader.read()
        val journal = absorbHostEjects(journalStore.load(), state)
        require(lun in journal.desired.slots.indices) { "no slot $lun to reinsert" }
        val reinserted =
            journal.copy(
                slotIntents = journal.slotIntents + (lun to SlotIntent.EXPORTED),
                slotFailures = journal.slotFailures - lun,
                udc = state.udc.ifEmpty { journal.udc },
            )
        return converge(reinserted, state, foreignBefore = state, allowEmergencyOff = true)
    }

    override fun eject(
        lun: Int,
        force: Boolean,
    ): ApplyResult {
        val state = reader.read()
        var journal = absorbHostEjects(journalStore.load(), state)
        require(lun in journal.desired.slots.indices) { "no slot $lun to eject" }
        val intent = journal.slotIntents[lun] ?: SlotIntent.EXPORTED
        if (intent != SlotIntent.EXPORTED) return result(state, save(journal), reenumerated = false)
        val phaseBefore = journal.phase
        journal = save(journal.copy(slotIntents = journal.slotIntents + (lun to SlotIntent.USER_EJECTED), phase = TransitionPhase.MUTATING))
        try {
            if (force) {
                fs.write(GadgetPaths.lunAttribute(lun, "forced_eject"), "1")
            } else {
                fs.write(GadgetPaths.lunAttribute(lun, "file"), "")
            }
        } catch (e: IOException) {
            save(journal.copy(slotIntents = journal.slotIntents + (lun to intent), phase = phaseBefore))
            val reason = if (force) "forced eject of lun $lun failed: ${e.message}" else "$EJECT_PREVENTED_REASON (lun $lun): ${e.message}"
            return ApplyResult.NeedsAttention(reason)
        }
        journal = save(journal.copy(phase = phaseBefore))
        return result(reader.read(), journal, reenumerated = false)
    }

    override fun reconcile(): ApplyResult {
        lastReconcileMs?.let { lastMs ->
            val waitMs = lastMs + GadgetLimits.RECONCILE_INTERVAL_MS_MIN - clock.nowMs()
            if (waitMs > 0) clock.sleepMs(waitMs)
        }
        lastReconcileMs = clock.nowMs()
        val quiet = awaitHalQuiet() ?: return halBusy()
        // An unbound UDC is usually the HAL mid-cycle: settling lets it rebind rather than binding against
        // it, and binds only if it doesn't. A UDC that won't stay bound is reported, not reconciled against.
        settleBound(knownUdc(), quiet.boundSinceMs)?.let { return it.result() }
        val state = reader.read()
        val loaded = journalStore.load()
        var journal = absorbHostEjects(loaded, state).let { it.copy(udc = state.udc.ifEmpty { it.udc }) }
        if (journal != loaded) journal = save(journal)
        // Also before the clean check: a CD-ROM exported truncated by an older version must not stay.
        journal = failOversizedCdroms(downgradeOversizedCdroms(journal))
        val clean =
            journal.phase == TransitionPhase.IDLE &&
                (state.udc.isNotEmpty() || state.configLinks.isEmpty()) &&
                planner.plan(state, GadgetTarget.of(journal), journal.udc).ops.isEmpty()
        if (clean) {
            reconcileAttemptCount = 0
            return result(state, journal, reenumerated = false)
        }
        if (reconcileAttemptCount >= GadgetLimits.RECONCILE_ATTEMPT_COUNT_MAX) {
            return ApplyResult.NeedsAttention("gadget still differs after $reconcileAttemptCount reconcile attempts; apply again")
        }
        reconcileAttemptCount++
        return converge(journal, state, foreignBefore = state, allowEmergencyOff = true)
    }

    /**
     * The journal with host ejects recorded, without reconciling anything. A host eject only empties
     * the LUN's `file` (no USB re-enumeration, so no USB_STATE broadcast), so the app polls this while
     * something is exported to show "Host ejected" and update the notification. Reads only, apart
     * from saving the journal when a host eject was found; no HAL wait, so it is cheap.
     */
    fun observe(): Journal {
        val loaded = journalStore.load()
        val journal = absorbHostEjects(loaded, reader.read())
        return if (journal != loaded) save(journal) else loaded
    }

    override fun bootlegOff(): ApplyResult {
        reconcileAttemptCount = 0
        val state = reader.read()
        val loaded = journalStore.load()
        val journal = Journal(udc = state.udc.ifEmpty { loaded.udc }, lunInstanceNonce = loaded.lunInstanceNonce)
        return converge(journal, state, foreignBefore = state, allowEmergencyOff = false)
    }

    /**
     * Plan, apply, verify; replans on divergence or op failure, at most [GadgetLimits.REPLAN_COUNT_MAX] times.
     * [foreignBefore] is the state at the start of the operation; foreign links it had must survive.
     */
    private fun converge(
        initialJournal: Journal,
        initialState: GadgetState,
        foreignBefore: GadgetState,
        allowEmergencyOff: Boolean,
        reenumerate: Boolean = false,
    ): ApplyResult {
        if (initialJournal.udc.isEmpty()) return ApplyResult.NeedsAttention("UDC is unbound and no UDC name is recorded")
        // Journals from before the LUN marker get a nonce here, so the LUNs this converge configures carry it.
        var journal = initialJournal
        if (journal.lunInstanceNonce.isEmpty()) {
            val nonce = newLunInstanceNonce()
            check(LunMarker.isNonce(nonce)) { "bad LUN instance nonce: '$nonce'" }
            journal = journal.copy(lunInstanceNonce = nonce)
        }
        var state = initialState
        var replanCount = 0
        var forceReenumerate = reenumerate
        var reenumerated = false
        var lastProblem: String? = null
        // Each pass either converges, consumes a replan, or fails a slot for the first time.
        val passCountMax = 1 + GadgetLimits.REPLAN_COUNT_MAX + GadgetLimits.LUN_COUNT_MAX
        for (pass in 0 until passCountMax) {
            // Before every plan, so a file that grew while being exported is caught on the next pass (and ejected).
            journal = failOversizedCdroms(journal)
            val plan = planner.plan(state, GadgetTarget.of(journal), journal.udc, forceReenumerate)
            if (plan.ops.isEmpty()) {
                lastProblem = null
                break
            }
            if (pass > 0 && lastProblem != SLOT_FAILED) replanCount++
            if (replanCount > GadgetLimits.REPLAN_COUNT_MAX) break
            journal = save(journal.copy(phase = TransitionPhase.MUTATING))
            val outcome = execute(plan, state, journal)
            when (outcome) {
                is Outcome.Completed -> {
                    journal = outcome.journal
                    reenumerated = reenumerated || plan.reenumerates
                    forceReenumerate = false
                    lastProblem = "gadget changed after apply"
                }
                is Outcome.Diverged -> {
                    journal = outcome.journal
                    lastProblem = "gadget changed while applying"
                }
                is Outcome.SlotFailed -> {
                    journal =
                        save(
                            outcome.journal.copy(
                                slotIntents = outcome.journal.slotIntents + (outcome.lun to SlotIntent.FAILED),
                                slotFailures = outcome.journal.slotFailures + (outcome.lun to outcome.reason),
                            ),
                        )
                    lastProblem = SLOT_FAILED
                }
                is Outcome.OpFailed -> {
                    journal = outcome.journal
                    lastProblem = outcome.reason
                }
                is Outcome.Refused -> {
                    journal = outcome.journal
                    lastProblem = outcome.reason
                }
            }
            // Replanning won't make a holder let go of a hidg node; the refused op changed nothing, so report it now.
            if (outcome is Outcome.Refused) break
            // Wait out the HAL's post-bind drop before reading back; if it won't settle, finish reports it.
            ownBindMs?.let { bindMs ->
                if (awaitStableBound(bindMs)) {
                    ownBindMs = null
                } else {
                    lastProblem = "UDC did not stay bound for ${STABLE_BOUND_MS}ms after our bind"
                }
            }
            state = reader.read()
            // Someone (HAL, dead-man) bound the UDC mid-transition: the host may have enumerated a
            // half-built gadget, so the next plan re-enumerates even if the topology now looks right.
            if (outcome !is Outcome.Completed && outcome.expectedUdc.isEmpty() && state.udc.isNotEmpty()) forceReenumerate = true
        }
        return finish(journal, lastProblem, reenumerated, foreignBefore, allowEmergencyOff)
    }

    private fun finish(
        initialJournal: Journal,
        lastProblem: String?,
        reenumerated: Boolean,
        foreignBefore: GadgetState,
        allowEmergencyOff: Boolean,
    ): ApplyResult {
        var journal = initialJournal
        var state = reader.read()
        // Verify only a stably bound gadget. The HAL normally rebinds by itself; settling binds only if it doesn't.
        if (state.udc.isEmpty() || ownBindMs != null) {
            val unsettled = settleBound(journal.udc, boundSinceMs = null)
            if (unsettled != null) {
                val reason = unsettled.reason + (lastProblem?.let { " ($it)" } ?: "")
                return when {
                    !unsettled.bindFailed -> ApplyResult.NeedsAttention(reason)
                    allowEmergencyOff -> emergencyOff(journal, reason, foreignBefore)
                    else -> ApplyResult.Failed(reason)
                }
            }
            state = reader.read()
        }
        val violations = GadgetInvariants.violations(state, GadgetTarget.of(journal), allowedRoots)
        // A non-IDLE phase tells reconcile that empty LUNs are ours, not host ejects. Lost foreign links
        // are not ours to fix, so they leave the phase IDLE but still fail the operation.
        journal = save(journal.copy(phase = if (violations.isEmpty()) TransitionPhase.IDLE else TransitionPhase.MUTATING))
        if (violations.isNotEmpty()) {
            return ApplyResult.NeedsAttention(listOfNotNull(lastProblem, violations.joinToString("; ")).joinToString(": "))
        }
        val foreignViolations = GadgetInvariants.foreignViolations(state, foreignBefore)
        if (foreignViolations.isNotEmpty()) return ApplyResult.NeedsAttention(foreignViolations.joinToString("; "))
        return result(state, journal, reenumerated)
    }

    private fun emergencyOff(
        journal: Journal,
        reason: String,
        foreignBefore: GadgetState,
    ): ApplyResult {
        val off =
            converge(
                Journal(phase = TransitionPhase.MUTATING, udc = journal.udc, lunInstanceNonce = journal.lunInstanceNonce),
                reader.read(),
                foreignBefore,
                allowEmergencyOff = false,
            )
        return ApplyResult.Failed(if (off is ApplyResult.Applied) "$reason; Bootleg-off performed" else "$reason; Bootleg-off failed: $off")
    }

    /** Binds [udc] unless something already did; a bind of ours sets [ownBindMs] so the HAL's follow-up gets waited out. */
    private fun bindWithRetries(udc: String): Boolean {
        repeat(BIND_ATTEMPT_COUNT_MAX) {
            if (reader.readUdc().isNotEmpty()) return true
            try {
                // A hidg fd open across this bind would panic the kernel when it is closed.
                hidNodes.closeAll()
                fs.write(GadgetPaths.UDC, udc)
                ownBindMs = clock.nowMs()
                return true
            } catch (_: IOException) {
                // Retried; the HAL may be mid-switch.
            }
        }
        return reader.readUdc().isNotEmpty()
    }

    /** Why the UDC isn't stably bound. [bindFailed]: it is unbound and couldn't be bound, rather than flapping. */
    private data class Unsettled(
        val reason: String,
        val bindFailed: Boolean,
    ) {
        fun result(): ApplyResult = ApplyResult.NeedsAttention(reason)
    }

    /**
     * Brings the UDC to stably bound before bound-phase work or verification: waits (bounded) until it
     * has stayed bound for [STABLE_BOUND_MS], counting from [boundSinceMs] (or our own unconfirmed bind)
     * when known. If it is still unbound after that (the HAL didn't rebind), binds it, at most
     * [BIND_ATTEMPT_COUNT_MAX] tries, and waits again. With nothing linked the kernel can't bind at all,
     * so that counts as settled: the planner then keeps all work inside the switch window. Null when settled.
     */
    private fun settleBound(
        udc: String,
        boundSinceMs: Long?,
    ): Unsettled? {
        if (reader.readUdc().isEmpty() && !hasLinks()) return null
        val sinceMs = listOfNotNull(boundSinceMs ?: clock.nowMs(), ownBindMs).max()
        if (awaitStableBound(sinceMs)) return settled()
        if (reader.readUdc().isNotEmpty()) {
            return Unsettled("UDC kept dropping: not bound for ${STABLE_BOUND_MS}ms within ${SETTLE_WAIT_MS_MAX}ms", bindFailed = false)
        }
        if (!hasLinks()) return settled()
        if (udc.isEmpty()) return Unsettled("UDC is unbound and no UDC name is recorded", bindFailed = true)
        hidHoldersBeforeBind()?.let { return Unsettled(it, bindFailed = false) }
        if (!bindWithRetries(udc)) return Unsettled("UDC $udc could not be bound", bindFailed = true)
        if (awaitStableBound(ownBindMs ?: clock.nowMs())) return settled()
        return Unsettled(
            "UDC $udc did not stay bound for ${STABLE_BOUND_MS}ms after binding it",
            bindFailed = reader.readUdc().isEmpty(),
        )
    }

    /**
     * Before binding an unbound UDC outside a plan: a hidg fd still open from before the unbind (another
     * process's; ours get closed) would be poisoned by the bind. Null when the bind may go ahead.
     */
    private fun hidHoldersBeforeBind(): String? {
        val state = reader.read()
        return closeHidAndFindHolders(HidFunctions.owned(state) + HidFunctions.linked(state))
    }

    /** Closes our hidg fds, then names whoever still holds a node of [functions]; null when nobody does. */
    private fun closeHidAndFindHolders(functions: Set<String>): String? {
        if (functions.isEmpty()) return null
        hidNodes.closeAll()
        val holders = hidNodes.holders(functions.sorted())
        return if (holders.isEmpty()) null else "$HID_NODE_HELD_REASON: ${holders.joinToString(", ")}"
    }

    private fun settled(): Unsettled? {
        ownBindMs = null
        return null
    }

    private fun hasLinks(): Boolean = reader.read().configLinks.isNotEmpty()

    /** The UDC name: live if bound, else the last one journaled. */
    private fun knownUdc(): String = reader.readUdc().ifEmpty { journalStore.load().udc }

    private sealed interface Outcome {
        val journal: Journal

        /** The UDC value the plan expected when execution stopped. */
        val expectedUdc: String

        data class Completed(
            override val journal: Journal,
            override val expectedUdc: String,
        ) : Outcome

        data class Diverged(
            override val journal: Journal,
            override val expectedUdc: String,
        ) : Outcome

        data class SlotFailed(
            override val journal: Journal,
            override val expectedUdc: String,
            val lun: Int,
            val reason: String,
        ) : Outcome

        data class OpFailed(
            override val journal: Journal,
            override val expectedUdc: String,
            val reason: String,
        ) : Outcome

        /** A hidg node of ours is held open, so the plan may not rebind or remove its function. */
        data class Refused(
            override val journal: Journal,
            override val expectedUdc: String,
            val reason: String,
        ) : Outcome
    }

    /** Runs [plan] stage by stage; any divergence or failure stops it so the caller replans. */
    private fun execute(
        plan: Plan,
        startState: GadgetState,
        startJournal: Journal,
    ): Outcome {
        val execution = Execution(startState, startJournal)
        quiesceHid(plan, startState)?.let { reason -> return Outcome.Refused(startJournal, startState.udc, reason) }
        for (stage in plan.stages) {
            if (stage.prepare.isNotEmpty()) execution.awaitBound()?.let { return it }
            stage.prepare.forEach { op -> execution.bound(op)?.let { return it } }
            stage.switch?.let { switch -> execution.switch(switch)?.let { return it } }
            if (stage.cleanup.isNotEmpty()) execution.awaitBound()?.let { return it }
            stage.cleanup.forEach { op -> execution.bound(op)?.let { return it } }
        }
        return Outcome.Completed(execution.journal, execution.expected.udc)
    }

    /**
     * Before a plan that writes the UDC or removes a hid function, while one of ours exists: every hidg
     * fd of ours is released and closed, and no other process holds one. Null when the plan may run,
     * else why not. Runs before the first op, outside any switch window (the scan is slow).
     */
    private fun quiesceHid(
        plan: Plan,
        state: GadgetState,
    ): String? {
        // A switch rebinds every linked hid function, other apps' too: their fds would be poisoned as well.
        val watched =
            if (plan.stages.any { it.switch != null }) {
                HidFunctions.owned(state) + HidFunctions.linked(state)
            } else {
                plan.ops.mapNotNull(HidFunctions::removedBy).toSet()
            }
        return closeHidAndFindHolders(watched)
    }

    private inner class Execution(
        var expected: GadgetState,
        var journal: Journal,
    ) {
        /**
         * Before a prepare or cleanup list: our own bind must have outlasted the HAL's post-bind drop, and
         * an unbound UDC gets the bounded wait for the HAL to rebind (then our own bind; the gadget is
         * between stages, so the host sees a finished one). Bound-phase ops never run (and never journal
         * MUTATING) inside an unbound window; if the UDC won't stay bound, the caller replans.
         */
        fun awaitBound(): Outcome? {
            if (ownBindMs == null && reader.readUdc().isNotEmpty()) return null
            settleBound(journal.udc, boundSinceMs = ownBindMs)?.let { unsettled ->
                return Outcome.OpFailed(journal, journal.udc, "before reconfiguring: ${unsettled.reason}")
            }
            expected = expected.copy(udc = reader.readUdc())
            return null
        }

        /**
         * A prepare/cleanup op, run while bound. The owned state is re-read and compared first. A UDC the
         * HAL dropped in between stops the stage (the replan waits for it again); a different bound UDC
         * value doesn't matter, since these ops never touch a linked function's topology.
         */
        fun bound(op: Op): Outcome? {
            val state = reader.read()
            if (state.udc.isEmpty()) return Outcome.OpFailed(journal, expected.udc, "UDC dropped before $op")
            if (OwnedView.of(state).withoutUdc() != OwnedView.of(expected).withoutUdc()) {
                return Outcome.Diverged(journal, expected.udc)
            }
            if (journal.phase != TransitionPhase.MUTATING) journal = save(journal.copy(phase = TransitionPhase.MUTATING))
            try {
                run(op)
            } catch (e: HidNodeInUseException) {
                return Outcome.Refused(journal, expected.udc, e.message ?: HID_NODE_HELD_REASON)
            } catch (e: IOException) {
                val lun = (op as? Op.Write)?.let { GadgetPaths.lunOf(it.path) }
                val reason = "$op failed: ${e.message}"
                return if (lun != null && journal.slotIntents[lun] != SlotIntent.FAILED && lun in journal.desired.slots.indices) {
                    Outcome.SlotFailed(journal, expected.udc, lun, reason)
                } else {
                    Outcome.OpFailed(journal, expected.udc, reason)
                }
            }
            expected = GadgetSimulation.apply(expected, op)
            return null
        }

        /**
         * Unbind, relink, bind, with nothing else in between: the HAL rebinds a few hundred ms after any
         * unbind. Inside the window only the UDC and our own links are re-read, and the journal is not
         * written. If the HAL rebinds mid-switch (seen as a bound UDC, or a link refused), the switch
         * unbinds again (dead-man re-armed) and carries on, at most [SWITCH_REBIND_COUNT_MAX] times.
         *
         * [Switch.unlinkedOps] (only planned when nothing is linked, so nothing can bind) run between the
         * unlinks and the links. A LUN `file` that can't be opened there fails only its slot, reported
         * after the bind so the journal is still never written inside the window.
         */
        fun switch(switch: Switch): Outcome? {
            journal = save(journal.copy(phase = TransitionPhase.UNBINDING))
            unbind()?.let { return it }
            val attemptCountMax = switch.unlinks.size + switch.links.size + SWITCH_REBIND_COUNT_MAX
            var attemptCount = 0

            fun relink(op: Op): Outcome? {
                while (true) {
                    attemptCount++
                    if (attemptCount > attemptCountMax) {
                        return Outcome.OpFailed(journal, "", "the UDC kept being rebound during the switch ($attemptCount attempts)")
                    }
                    if (reader.readUdc().isNotEmpty()) unbind()?.let { return it }
                    if (reader.readOwnedLinks() != expected.configLinks.filterKeys(GadgetPaths::isOwnedName)) {
                        return Outcome.Diverged(journal, "")
                    }
                    try {
                        run(op)
                        expected = GadgetSimulation.apply(expected, op)
                        return null
                    } catch (e: IOException) {
                        // Refused because something bound the UDC in between: unbind and retry.
                        if (reader.readUdc().isEmpty()) return Outcome.OpFailed(journal, "", "$op failed: ${e.message}")
                    }
                }
            }
            switch.unlinks.forEach { op -> relink(op)?.let { return it } }
            var slotFailure: Outcome.SlotFailed? = null
            for (op in switch.unlinkedOps) {
                try {
                    run(op)
                    expected = GadgetSimulation.apply(expected, op)
                } catch (e: HidNodeInUseException) {
                    // Nothing is linked here, so stopping leaves nothing the host could see half-built.
                    return Outcome.Refused(journal, "", e.message ?: HID_NODE_HELD_REASON)
                } catch (e: IOException) {
                    val reason = "$op failed: ${e.message}"
                    val lun =
                        (op as? Op.Write)
                            ?.takeIf {
                                it.path.endsWith(
                                    "/file",
                                ) &&
                                    it.value.isNotEmpty()
                            }?.let { GadgetPaths.lunOf(it.path) }
                    val slotCanFail = lun != null && journal.slotIntents[lun] != SlotIntent.FAILED && lun in journal.desired.slots.indices
                    // The file write is a LUN's last op, so the rest of the gadget can still be finished and bound.
                    if (lun == null || !slotCanFail || slotFailure != null) return Outcome.OpFailed(journal, "", reason)
                    slotFailure = Outcome.SlotFailed(journal, switch.bind?.udc.orEmpty(), lun, reason)
                }
            }
            switch.links.forEach { op -> relink(op)?.let { return it } }
            val bind = switch.bind ?: return slotFailure
            // Already bound means the HAL bound after our last relink, so the host saw the finished gadget.
            if (reader.readUdc().isEmpty()) {
                try {
                    run(bind)
                } catch (e: IOException) {
                    if (reader.readUdc().isEmpty()) return Outcome.OpFailed(journal, "", "$bind failed: ${e.message}")
                }
            }
            ownBindMs = clock.nowMs()
            expected = expected.copy(udc = bind.udc)
            return slotFailure
        }

        private fun unbind(): Outcome? {
            deadMan.arm(journal.udc, GadgetLimits.DEAD_MAN_REBIND_DELAY_S)
            if (reader.readUdc().isNotEmpty()) {
                try {
                    run(Op.Unbind)
                } catch (e: IOException) {
                    if (reader.readUdc().isNotEmpty()) return Outcome.OpFailed(journal, expected.udc, "unbind failed: ${e.message}")
                }
            }
            expected = expected.copy(udc = "")
            return null
        }
    }

    private fun run(op: Op) {
        check(GadgetPaths.touchedPaths(op).all(GadgetPaths::isMutableByBootleg)) { "refusing to touch a foreign path: $op" }
        when (op) {
            // Cheap once quiesceHid closed everything; no UDC write of ours ever crosses an open hidg fd of ours.
            Op.Unbind -> {
                hidNodes.closeAll()
                fs.write(GadgetPaths.UDC, "")
            }
            is Op.Bind -> {
                hidNodes.closeAll()
                fs.write(GadgetPaths.UDC, op.udc)
            }
            is Op.Mkdir -> fs.mkdir(op.path)
            is Op.Rmdir -> {
                HidFunctions.removedBy(op)?.let(::requireHidNodeFree)
                fs.rmdir(op.path)
            }
            is Op.Write -> fs.write(op.path, op.value)
            is Op.WriteBytes -> fs.writeBytes(op.path, op.bytes)
            is Op.Symlink -> fs.symlink(op.target, op.link)
            is Op.Unlink -> fs.unlink(op.link)
        }
    }

    /** Right before removing [function]: nobody may hold its node, not even someone who opened it after quiesceHid. */
    private fun requireHidNodeFree(function: String) {
        closeHidAndFindHolders(setOf(function))?.let { throw HidNodeInUseException(it) }
    }

    /** Why each exported CD-ROM slot's file can't be a CD-ROM ([CdromLimits]); the kernel would truncate or refuse it. */
    private fun oversizedCdroms(journal: Journal): Map<Int, String> =
        journal.desired.slots
            .filter { it.mode == LunMode.CDROM && (journal.slotIntents[it.lun] ?: SlotIntent.EXPORTED) == SlotIntent.EXPORTED }
            .mapNotNull { slot ->
                val sizeBytes = fileSizeBytes(slot.backingPath) ?: return@mapNotNull null
                CdromLimits.problem(slot.backingPath.substringAfterLast('/'), sizeBytes)?.let { slot.lun to it }
            }.toMap()

    /** An explicit CD-ROM the file can't be fails its slot (never exported truncated). */
    private fun failOversizedCdroms(journal: Journal): Journal {
        val problems = oversizedCdroms(journal)
        if (problems.isEmpty()) return journal
        return save(
            journal.copy(
                slotIntents = journal.slotIntents + problems.mapValues { SlotIntent.FAILED },
                slotFailures = journal.slotFailures + problems,
            ),
        )
    }

    /**
     * Reconcile (also the restore after reboot): a CD-ROM slot Auto chose ([SlotSpec.auto]) whose file
     * can't be one becomes a read-only disk, with a note saying why. An explicit CD-ROM is left to fail.
     */
    private fun downgradeOversizedCdroms(journal: Journal): Journal {
        val autoLuns =
            journal.desired.slots
                .filter { it.auto }
                .map { it.lun }
                .toSet()
        val problems = oversizedCdroms(journal).filterKeys { it in autoLuns }
        if (problems.isEmpty()) return journal
        val slots = journal.desired.slots.map { slot -> if (slot.lun in problems) slot.copy(mode = LunMode.DISK_RO) else slot }
        val notes = problems.mapValues { (_, reason) -> "Exported as a read-only disk: ${reason.substringBefore(". Use ")}" }
        return save(journal.copy(desired = journal.desired.copy(slots = slots), slotNotes = journal.slotNotes + notes))
    }

    /**
     * Marks exported slots whose LUN the host emptied. Only trusted when no transition of ours is in
     * flight (an interrupted one leaves the phase non-IDLE). The UDC and our link are deliberately not
     * required: the HAL may unbind, or someone may unlink, after the host ejected, and that must not
     * bring the media back. Empty LUNs only count while the mass_storage function still looks like we
     * left it (same LUN set, ro/cdrom as journaled, and every LUN still carrying our [LunMarker]); a
     * missing or recreated function or LUN is not a host eject, even one whose defaults match (all RW).
     */
    private fun absorbHostEjects(
        journal: Journal,
        state: GadgetState,
    ): Journal {
        if (journal.phase != TransitionPhase.IDLE) return journal
        val slots = journal.desired.slots

        fun intent(slot: SlotSpec) = journal.slotIntents[slot.lun] ?: SlotIntent.EXPORTED
        // Journals from before the marker have no nonce; their LUNs can only be compared by flags.
        val marker = GadgetTarget.of(journal).lunMarker
        val asLeft =
            state.luns.keys == slots.map { it.lun }.toSet() &&
                (marker == null || state.luns.values.all { it.inquiryString == marker }) &&
                slots.filter { intent(it) != SlotIntent.FAILED }.all { slot ->
                    val lun = state.luns.getValue(slot.lun)
                    lun.ro == slot.mode.readOnly && lun.cdrom == slot.mode.cdrom
                }
        if (!asLeft) return journal
        val ejected = slots.filter { intent(it) == SlotIntent.EXPORTED && state.luns[it.lun]?.file == "" }.map { it.lun }
        if (ejected.isEmpty()) return journal
        return journal.copy(slotIntents = journal.slotIntents + ejected.associateWith { SlotIntent.HOST_EJECTED })
    }

    private fun result(
        state: GadgetState,
        journal: Journal,
        reenumerated: Boolean,
    ): ApplyResult {
        val violations = GadgetInvariants.violations(state, GadgetTarget.of(journal), allowedRoots)
        return when {
            violations.isNotEmpty() -> ApplyResult.NeedsAttention(violations.joinToString("; "))
            journal.slotFailures.isNotEmpty() -> ApplyResult.Partial(state, journal.slotFailures)
            else -> ApplyResult.Applied(state, reenumerated)
        }
    }

    /** [boundSinceMs]: since when the UDC was seen bound at every poll of the wait, or null if unbound at the end. */
    private data class HalQuiet(
        val boundSinceMs: Long?,
    )

    /**
     * Waits (bounded) until the HAL is quiet: sys.usb.config unchanged for [HAL_QUIET_MS], and
     * sys.usb.state equal to it when the device reports a state at all. A stable empty config with an
     * empty state counts as quiet (nothing is switching), not as permanently busy. The UDC is sampled
     * on the same polls, so a UDC bound throughout the wait needs no separate stable-bound wait
     * (the HAL's drop-and-rebind takes longer than a poll interval, so it can't hide between polls).
     * Null if the HAL stays busy.
     */
    private fun awaitHalQuiet(): HalQuiet? {
        val startMs = clock.nowMs()
        var quietSinceMs: Long? = null
        var quietConfig: String? = null
        var boundSinceMs: Long? = null
        repeat((HAL_QUIET_WAIT_MS_MAX / HAL_POLL_INTERVAL_MS).toInt() + 1) {
            val nowMs = clock.nowMs()
            boundSinceMs = if (reader.readUdc().isEmpty()) null else boundSinceMs ?: nowMs
            val usbState = props.usbState()
            val usbConfig = props.usbConfig()
            // The Pixel 6 leaves sys.usb.state empty, so there the signal is sys.usb.config holding still.
            val settled = usbState.isEmpty() || usbState == usbConfig
            if (settled && usbConfig == quietConfig) {
                if (nowMs - checkNotNull(quietSinceMs) >= HAL_QUIET_MS) return HalQuiet(boundSinceMs)
            } else if (settled) {
                quietSinceMs = nowMs
                quietConfig = usbConfig
            } else {
                quietSinceMs = null
                quietConfig = null
            }
            if (nowMs - startMs >= HAL_QUIET_WAIT_MS_MAX) return null
            clock.sleepMs(HAL_POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Waits (bounded by [SETTLE_WAIT_MS_MAX]) until the UDC has stayed bound for [STABLE_BOUND_MS],
     * counting from [boundSinceMs] if it is bound now. On the Pixel the HAL drops the UDC once a few
     * hundred ms after our bind and rebinds about a second later; reconfiguring, verifying or binding
     * during that cycle would race it. A drop and rebind can both fall between two polls, so after our
     * own bind it also waits until [HAL_POST_BIND_ACTIVITY_MS_MAX] has passed. True only once the full
     * interval has passed: a UDC that is bound at the deadline but not for long enough is not stable.
     */
    private fun awaitStableBound(boundSinceMs: Long): Boolean {
        val startMs = clock.nowMs()
        val halQuietAfterMs = ownBindMs?.let { it + HAL_POST_BIND_ACTIVITY_MS_MAX } ?: Long.MIN_VALUE
        var stableSinceMs: Long? = boundSinceMs
        repeat((SETTLE_WAIT_MS_MAX / SETTLE_POLL_INTERVAL_MS).toInt() + 1) {
            val nowMs = clock.nowMs()
            val bound = reader.readUdc().isNotEmpty()
            if (!bound) {
                stableSinceMs = null
            } else if (stableSinceMs == null) {
                stableSinceMs = nowMs
            }
            if (bound && nowMs - checkNotNull(stableSinceMs) >= STABLE_BOUND_MS && nowMs >= halQuietAfterMs) return true
            if (nowMs - startMs >= SETTLE_WAIT_MS_MAX) return false
            clock.sleepMs(SETTLE_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun halBusy() = ApplyResult.NeedsAttention("USB HAL did not settle within ${HAL_QUIET_WAIT_MS_MAX}ms")

    private fun save(journal: Journal): Journal {
        journalStore.save(journal)
        return journal
    }

    /** The part of [GadgetState] Bootleg's ops touch. */
    private data class OwnedView(
        val udc: String,
        val functions: Set<String>,
        val links: Map<String, String>,
        val luns: Map<Int, LunState>,
    ) {
        fun withoutUdc() = copy(udc = "")

        companion object {
            fun of(state: GadgetState) =
                OwnedView(
                    udc = state.udc,
                    functions = state.functions.filter(GadgetPaths::isOwnedName).toSet(),
                    links = state.configLinks.filterKeys(GadgetPaths::isOwnedName),
                    luns = state.luns.toMap(),
                )
        }
    }

    companion object {
        const val HAL_QUIET_MS = 1_000L
        const val HAL_QUIET_WAIT_MS_MAX = 10_000L
        const val HAL_POLL_INTERVAL_MS = 100L
        const val BIND_ATTEMPT_COUNT_MAX = 3

        /** HAL rebinds absorbed within one switch before it gives up and replans. */
        const val SWITCH_REBIND_COUNT_MAX = 5

        /**
         * Longer than the HAL's post-bind drop delay (≤ 600ms measured), so the drop is seen, not raced, and
         * at least its drop-to-rebind delay (≤ 1s measured): once the UDC has held this long after the
         * HAL's rebind, no HAL rebind is still pending that could land inside our next switch.
         */
        const val STABLE_BOUND_MS = 1_000L

        /** The HAL's post-bind drop comes ≤ 600ms after our bind and its rebind ≤ 1s after that (measured). */
        const val HAL_POST_BIND_ACTIVITY_MS_MAX = 1_600L
        const val SETTLE_WAIT_MS_MAX = 3_000L
        const val SETTLE_POLL_INTERVAL_MS = 50L
        const val EJECT_PREVENTED_REASON = "host prevents removal; force eject?"
        const val HID_NODE_HELD_REASON = "a keyboard/mouse device node is held open, so the USB gadget was left as it was"
        private const val SLOT_FAILED = "slot failed"
        private val EJECTED_INTENTS = setOf(SlotIntent.HOST_EJECTED, SlotIntent.USER_EJECTED)
    }
}
