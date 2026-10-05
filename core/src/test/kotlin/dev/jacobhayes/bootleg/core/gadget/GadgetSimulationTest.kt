package dev.jacobhayes.bootleg.core.gadget

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** L2: the real controller against the kernel model, with crashes, faults and HAL interference. */
class GadgetSimulationTest {
    private fun GadgetRig.assertConverged(
        context: String,
        foreignBefore: GadgetState? = null,
        undisturbed: Boolean = false,
    ) {
        val state = state()
        val journal = journal()
        val violations = GadgetInvariants.violations(state, GadgetTarget.of(journal), foreignBefore = foreignBefore)
        assertEquals(emptyList<String>(), violations, "$context: invariants (journal=$journal)")
        assertEquals(emptyList<String>(), fs.foreignWrites, "$context: Bootleg wrote foreign paths")
        assertEquals(emptyList<String>(), fs.hidFdHazards, "$context: a hidg fd was open across a rebind or removal")
        // Without HAL interference nothing can bind mid-transition, so the executor never even tries to link while bound.
        // Without crashes, faults or function switches, every HAL timer has fired before our next switch (the
        // stable-bound and HAL-quiet waits outlast them), so nothing can bind into our window and no link is refused.
        // Otherwise a pending HAL rebind may land inside a later window; the switch then unbinds again.
        if (undisturbed) {
            assertEquals(emptyList<String>(), fs.boundSymlinkRejections, "$context: linked while bound")
            assertEquals(emptyList<String>(), fs.bindsInExecutorWindow, "$context: bound inside our window")
            val longestUs = fs.executorWindowsUs.maxOrNull() ?: 0
            assertTrue(longestUs < GadgetRig.SWITCH_WINDOW_US_MAX, "$context: unbound for ${longestUs}us")
        }
        assertEquals(TransitionPhase.IDLE, journal.phase, "$context: phase")
        assertTrue("ffs.adb" in state.configLinks.filterKeys { it.startsWith("function") }.values, "$context: ADB linked")
        for ((lun, intent) in journal.slotIntents) {
            if (intent != SlotIntent.EXPORTED) assertEquals("", state.luns[lun]?.file, "$context: lun $lun is $intent")
        }
    }

    /** Reconciles (advancing time) until the gadget is clean; bounded. */
    private fun GadgetRig.settle(controller: DefaultGadgetController): ApplyResult {
        var result: ApplyResult = ApplyResult.NeedsAttention("not reconciled")
        repeat(GadgetLimits.RECONCILE_ATTEMPT_COUNT_MAX) {
            result = controller.reconcile()
            if (result is ApplyResult.Applied || result is ApplyResult.Partial) return result
            clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
        }
        return result
    }

    @Test
    fun `random desired sequences converge with host ejects in between`(): Unit =
        runBlocking {
            checkAll(60, Arb.long()) { seed ->
                val random = Random(seed)
                val rig = GadgetRig(seed)
                val before = rig.state()
                val controller = rig.controller()
                repeat(5) { step ->
                    val desired = randomDesired(random)
                    val result = controller.apply(desired)
                    assertTrue(result is ApplyResult.Applied, "seed $seed step $step: $result")
                    // The app's session reopens after every call; the next change must close it first.
                    rig.hidNodes.reopen()
                    // With the measured HAL and realistic op costs, every switch finishes long before the HAL can bind into it.
                    rig.assertConverged("seed $seed step $step", before, undisturbed = true)
                    val state = rig.state()
                    for (slot in desired.slots) {
                        val lun = state.luns.getValue(slot.lun)
                        val intent = rig.journal().slotIntents[slot.lun]
                        assertEquals(if (intent == SlotIntent.EXPORTED) slot.backingPath else "", lun.file)
                        assertEquals(slot.mode.readOnly to slot.mode.cdrom, lun.ro to lun.cdrom)
                    }
                    if (desired.slots.isNotEmpty() && random.nextBoolean()) {
                        val lun = random.nextInt(desired.slots.size)
                        rig.fs.hostEject(lun)
                        rig.clock.sleepMs(GadgetLimits.RECONCILE_INTERVAL_MS_MIN)
                        assertTrue(controller.reconcile() is ApplyResult.Applied)
                        rig.hidNodes.reopen()
                        assertEquals(SlotIntent.HOST_EJECTED, rig.journal().slotIntents[lun])
                        rig.assertConverged("seed $seed step $step after host eject", before)
                    }
                }
            }
        }

    @Test
    fun `crash at every op, then dead-man and a fresh reconcile, converges and keeps host ejects`() {
        repeat(CRASH_SCENARIO_COUNT) { scenario ->
            val random = Random(scenario.toLong())
            val network = TetherMode.entries[scenario % TetherMode.entries.size]
            val first =
                randomDesired(
                    random,
                ).let { if (it.slots.isEmpty()) randomDesired(Random(scenario + 1000L), lunCountMax = 3) else it }
                    .copy(network = network)
            val second = randomDesired(random).copy(network = network)
            // Keep a host-ejected slot unchanged across the transition when there is one.
            val ejectedLun = if (first.slots.isNotEmpty()) random.nextInt(first.slots.size) else null
            val target =
                if (ejectedLun != null && second.slots.size > ejectedLun) {
                    second.copy(slots = second.slots.map { if (it.lun == ejectedLun) first.slots[ejectedLun] else it }).takeIfValid()
                        ?: second
                } else {
                    second
                }
            var crashAt = 0
            while (true) {
                val context = "scenario $scenario crash at mutation $crashAt"
                val rig = GadgetRig(scenario.toLong())
                val before = rig.state()
                rig.controller().apply(first)
                rig.hidNodes.reopen()
                if (ejectedLun != null) {
                    rig.fs.hostEject(ejectedLun)
                    rig.controller().reconcile()
                    rig.hidNodes.reopen()
                }
                val ejectedKept = ejectedLun != null && target.slots.getOrNull(ejectedLun) == first.slots[ejectedLun]
                rig.scripted.crashAt = rig.scripted.mutationCount + crashAt
                val dying = rig.controller()
                val crashed =
                    try {
                        dying.apply(target)
                        false
                    } catch (_: SimulatedCrash) {
                        true
                    }
                rig.scripted.crashAt = null
                // The kernel closes a dead process's fds; the dead-man may then bind.
                if (crashed) rig.hidNodes.processDied()
                if (crashed && rig.deadMan.arms.isNotEmpty()) rig.deadMan.fire()
                val result = rig.settle(rig.controller())
                rig.hidNodes.reopen()
                assertTrue(result is ApplyResult.Applied || result is ApplyResult.Partial, "$context: $result")
                assertEquals(target, rig.journal().desired, context)
                rig.assertConverged(context, before)
                // Only the host's eject survives; our own half-done swaps must not look like host ejects.
                for (slot in target.slots) {
                    val expected = if (ejectedKept && slot.lun == ejectedLun) SlotIntent.HOST_EJECTED else SlotIntent.EXPORTED
                    assertEquals(expected, rig.journal().slotIntents[slot.lun], "$context: lun ${slot.lun} intent")
                }
                if (!crashed) break
                crashAt++
                assertTrue(crashAt < CRASH_POINT_COUNT_MAX, "plan never finished")
            }
        }
    }

    @Test
    fun `crash at every op of setting the prefix up or changing its network function, then dead-man and a fresh reconcile, converges`() {
        val changes = TetherMode.entries.map { null to it } + TetherMode.entries.zipWithNext() + TetherMode.entries.reversed().zipWithNext()
        for ((from, to) in changes) {
            var crashAt = 0
            while (true) {
                val context = "$from to $to, crash at mutation $crashAt"
                val rig = GadgetRig()
                val before = rig.state()
                if (from != null) {
                    rig.controller().apply(Desired(hid = HidSpec(keyboard = true), network = from))
                    rig.hidNodes.reopen()
                }
                rig.scripted.crashAt = rig.scripted.mutationCount + crashAt
                val crashed =
                    try {
                        rig.controller().establishPrefix(to)
                        false
                    } catch (_: SimulatedCrash) {
                        true
                    }
                rig.scripted.crashAt = null
                if (crashed) rig.hidNodes.processDied()
                if (crashed && rig.deadMan.arms.isNotEmpty()) rig.deadMan.fire()
                val result = rig.settle(rig.controller())
                rig.hidNodes.reopen()
                assertTrue(result is ApplyResult.Applied, "$context: $result")
                assertEquals(to, rig.journal().desired.network, context)
                assertEquals(
                    LinkOrder.prefix(to),
                    rig
                        .state()
                        .configLinks.keys
                        .take(3),
                    context,
                )
                assertEquals(0L, rig.usbFunctions.current(), "$context: Android's USB mode is back")
                assertEquals(null, rig.journal().usbFunctionsToRestore, context)
                rig.assertConverged(context, before)
                if (!crashed) break
                crashAt++
                assertTrue(crashAt < CRASH_POINT_COUNT_MAX, "plan never finished")
            }
        }
    }

    @Test
    fun `seeded faults never leave the gadget unbound and reconcile recovers`(): Unit =
        runBlocking {
            checkAll(150, Arb.long()) { seed ->
                val random = Random(seed)
                val rig = GadgetRig(seed)
                val before = rig.state()
                val controller = rig.controller()
                repeat(3) { step ->
                    val context = "seed $seed step $step"
                    val desired = randomDesired(random)
                    val start = rig.scripted.mutationCount
                    rig.scripted.faultAt = List(1 + random.nextInt(3)) { start + random.nextInt(40) }.toSet()
                    val result = controller.apply(desired)
                    assertTrue(rig.fs.udc.isNotEmpty(), "$context: unbound after $result")
                    rig.scripted.faultAt = emptySet()
                    if (result !is ApplyResult.Applied && result !is ApplyResult.Partial) {
                        val settled = rig.settle(controller)
                        assertTrue(settled is ApplyResult.Applied || settled is ApplyResult.Partial, "$context: $result then $settled")
                    }
                    rig.assertConverged(context, before)
                    rig.hidNodes.reopen()
                    val failures = rig.journal().slotFailures
                    assertEquals(
                        failures.keys,
                        rig
                            .journal()
                            .slotIntents
                            .filterValues { it == SlotIntent.FAILED }
                            .keys,
                    )
                }
            }
        }

    @Test
    fun `HAL function switches between ops are survived`(): Unit =
        runBlocking {
            checkAll(150, Arb.long()) { seed ->
                val random = Random(seed)
                val rig = GadgetRig(seed)
                rig.hal.interferenceRemaining = 1 + random.nextInt(4)
                rig.hal.interferenceProbability = 0.02 + random.nextDouble() * 0.1
                val controller = rig.controller()
                repeat(4) { step ->
                    val context = "seed $seed step $step (HAL switches ${rig.hal.switchCount})"
                    val desired = randomDesired(random)
                    var result = controller.apply(desired)
                    if (result !is ApplyResult.Applied) result = rig.settle(controller)
                    if (result !is ApplyResult.Applied) fail<Unit>("$context: $result")
                    rig.assertConverged(context)
                    assertEquals(desired, rig.journal().desired)
                }
            }
        }

    @Test
    fun `HAL switch while unbound mid-transition forces a fresh enumeration`() {
        val rig = GadgetRig()
        assertTrue(rig.controller().apply(Desired(prefix = true)) is ApplyResult.Applied)
        val switchesBefore = rig.hal.switchCount
        val armsBefore = rig.deadMan.arms.size
        val desired = Desired(listOf(SlotSpec(0, GadgetRig.iso(1), LunMode.CDROM)), HidSpec(keyboard = true))
        val interfering =
            ScriptedConfigFs(rig.fs) { call ->
                // Between the executor's pre-op check and its link, the HAL switches functions and
                // rebinds: the host enumerates without our disks, yet the gadget then looks as desired.
                if (call == "symlink configs/b.1/mass_storage.bootleg" &&
                    rig.hal.switchCount == switchesBefore
                ) {
                    rig.hal.setFunctions("mtp,adb")
                }
            }
        val result =
            DefaultGadgetController(
                interfering,
                rig.hal,
                rig.journalStore,
                rig.deadMan,
                rig.clock,
                FakeHidDescriptors,
                rig.hidNodes,
                rndisDaemon = rig.rndisDaemon,
                usbFunctions = rig.usbFunctions,
            ).apply(desired)
        assertTrue(result is ApplyResult.Applied, "$result")
        assertEquals(switchesBefore + 1, rig.hal.switchCount)
        // The kernel refused our link into the HAL-bound gadget; the controller unbound, linked and rebound.
        assertEquals(listOf("${FakeConfigFs.EXECUTOR} configs/b.1/mass_storage.bootleg"), rig.fs.boundSymlinkRejections)
        assertEquals(2, rig.deadMan.arms.size - armsBefore, "unbound again after the HAL's bind")
        assertTrue(LinkOrder.prefixFirst(rig.state().configLinks, TetherMode.DEFAULT), "the HAL's switch only appends after the prefix")
        assertTrue((result as ApplyResult.Applied).reenumerated)
        rig.assertConverged("HAL mid-transition")
    }

    private fun Desired.takeIfValid(): Desired? = takeIf { runCatching { GadgetInvariants.requireValid(it) }.isSuccess }

    companion object {
        private const val CRASH_SCENARIO_COUNT = 40
        private const val CRASH_POINT_COUNT_MAX = 500
    }
}
