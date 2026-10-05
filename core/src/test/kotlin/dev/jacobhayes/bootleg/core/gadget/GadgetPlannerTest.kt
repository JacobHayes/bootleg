package dev.jacobhayes.bootleg.core.gadget

import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.img
import dev.jacobhayes.bootleg.core.gadget.GadgetRig.Companion.iso
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class GadgetPlannerTest {
    private val planner = GadgetPlanner(FakeHidDescriptors)
    private val udc = FakeConfigFs.PIXEL_UDC
    private val keyboard = "functions/hid.bootleg_kbd"
    private val massStorage = "functions/mass_storage.bootleg"
    private val massStorageLink = "configs/b.1/mass_storage.bootleg"
    private val hidOn = HidSpec(keyboard = true, mouse = true)
    private val prefixThenAdb = LinkOrder.prefix(TetherMode.DEFAULT) + "function0"

    private fun kernelWith(desired: Desired): PlannerKernel = PlannerKernel().also { it.converge(planner, GadgetTarget.of(desired)) }

    private fun cdrom(
        path: String,
        network: TetherMode = TetherMode.DEFAULT,
    ) = Desired(listOf(SlotSpec(0, path, LunMode.CDROM)), network = network)

    private fun PlannerKernel.links() = read().configLinks.keys.toList()

    @Test
    fun `empty desired on a foreign-only gadget plans nothing`() {
        assertEquals(Plan.EMPTY, planner.plan(PlannerKernel().read(), Desired()))
    }

    @Test
    fun `first use links the prefix with the RNDIS function held, has the HAL relink after it, then releases or unlinks it and binds`() {
        for (network in TetherMode.entries) {
            val kernel = PlannerKernel()
            val desired = Desired(prefix = true, network = network)
            val stage = planner.plan(kernel.read(), desired).stages.single()
            assertTrue(stage.prepare.containsAll(listOf(Op.StartRndis, Op.Mkdir(keyboard))), "${stage.prepare}")
            val prefix = LinkOrder.prefix(network)
            // In NCM mode the held RNDIS function is only a gate ahead of the prefix, unlinked once the HAL has relinked.
            val gate = listOf(GadgetNames.RNDIS_FUNCTION)
            val (held, end) = if (network == TetherMode.RNDIS) prefix to Op.ReleaseRndis else gate + prefix to GadgetPlanner.gateUnlink
            val links = held.map { Op.Symlink(GadgetPaths.function(it), GadgetPaths.configLink(it)) }
            assertEquals(Switch(emptyList(), links, Op.Bind(udc), afterLinks = listOf(Op.RelinkForeign, end)), stage.switch)
            kernel.execute(Plan(listOf(stage)))
            assertEquals(prefix + "function0", kernel.links())
            if (network == TetherMode.NCM) {
                assertEquals("bootleg_ncm0", kernel.read().ncmInterface)
                assertEquals(listOf("start", "stop"), kernel.daemon.events, "the gate is gone")
                assertFalse(GadgetNames.RNDIS_FUNCTION in kernel.read().functions)
            } else {
                assertEquals(RndisState.READY, kernel.read().rndis)
            }
            assertEquals(Plan.EMPTY, planner.plan(kernel.read(), desired))
        }
    }

    @Test
    fun `HID on or off never changes the gadget, and a first disk comes after the HAL's links`() {
        val kernel = kernelWith(Desired(prefix = true))
        for (hid in listOf(HidSpec(), HidSpec(keyboard = true), hidOn)) {
            assertEquals(Plan.EMPTY, planner.plan(kernel.read(), Desired(hid = hid, prefix = true)), "$hid")
        }
        assertEquals(Plan.EMPTY, planner.plan(kernel.read(), Desired(hid = hidOn)), "HID on wants the prefix by itself")
        val plan = planner.plan(kernel.read(), cdrom(iso(1)))
        assertEquals(Switch(emptyList(), listOf(Op.Symlink(massStorage, massStorageLink)), Op.Bind(udc)), plan.stages.single().switch)
        kernel.execute(plan)
        assertEquals(prefixThenAdb + GadgetNames.MASS_STORAGE_FUNCTION, kernel.links())
    }

    @Test
    fun `same-mode media swap is in place`() {
        val kernel = kernelWith(cdrom(iso(1)))
        val plan = planner.plan(kernel.read(), cdrom(iso(2)))
        assertEquals(listOf(Op.Write("$massStorage/lun.0/file", ""), Op.Write("$massStorage/lun.0/file", iso(2))), plan.ops)
        assertFalse(plan.reenumerates)
    }

    @Test
    fun `mode change detaches only mass storage, reconfigures it unlinked, then relinks it`() {
        val kernel = kernelWith(cdrom(iso(1)))
        val plan = planner.plan(kernel.read(), Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RW))))
        assertEquals(
            listOf(
                Op.Unbind,
                Op.Unlink(massStorageLink),
                Op.Bind(udc),
                Op.Write("$massStorage/lun.0/file", ""),
                Op.Write("$massStorage/lun.0/cdrom", "0"),
                Op.Write("$massStorage/lun.0/ro", "0"),
                Op.Write("$massStorage/lun.0/file", img(1)),
                Op.Unbind,
                Op.Symlink(massStorage, massStorageLink),
                Op.Bind(udc),
            ),
            plan.ops,
        )
        kernel.execute(plan)
        assertTrue(LinkOrder.prefixFirst(kernel.read().configLinks, TetherMode.DEFAULT))
    }

    @Test
    fun `a linked LUN with removable=0 or nofua=1 is detached, repaired and relinked, and then verifies`() {
        for ((attribute, broken) in listOf("removable" to "0", "nofua" to "1")) {
            val kernel = kernelWith(cdrom(iso(1)))
            val fs = kernel.fs
            fs.asActor("other") {
                fs.unlink(massStorageLink)
                fs.write("$massStorage/lun.0/$attribute", broken)
                fs.symlink(massStorage, massStorageLink)
                fs.write("UDC", udc)
            }
            val target = GadgetTarget.of(cdrom(iso(1)))
            assertTrue(GadgetInvariants.violations(kernel.read(), target).any { "$attribute=$broken" in it }, attribute)
            val plan = planner.plan(kernel.read(), target)
            assertTrue(plan.reenumerates, attribute)
            assertTrue(Op.Write("$massStorage/lun.0/$attribute", if (broken == "0") "1" else "0") in plan.ops, attribute)
            kernel.execute(plan)
            assertEquals(emptyList<String>(), GadgetInvariants.violations(kernel.read(), target), attribute)
            assertEquals(emptyList<Op>(), planner.plan(kernel.read(), target).ops, attribute)
        }
    }

    @Test
    fun `the LUN marker is written in place on a linked function, without re-enumerating`() {
        val kernel = kernelWith(cdrom(iso(1)))
        assertEquals("", kernel.read().luns[0]?.inquiryString)
        val target = GadgetTarget.of(cdrom(iso(1))).copy(lunMarker = GadgetRig.MARKER)
        val plan = planner.plan(kernel.read(), target)
        assertEquals(listOf(Op.Write("$massStorage/lun.0/inquiry_string", GadgetRig.MARKER)), plan.ops)
        assertFalse(plan.reenumerates)
        kernel.execute(plan)
        assertEquals(GadgetRig.MARKER, kernel.read().luns[0]?.inquiryString)
        assertEquals(emptyList<String>(), GadgetInvariants.violations(kernel.read(), target))
    }

    @Test
    fun `adding a LUN relinks only mass storage because configfs forbids mkdir while linked`() {
        val kernel = kernelWith(cdrom(iso(1)))
        val desired = Desired(listOf(SlotSpec(0, iso(1), LunMode.CDROM), SlotSpec(1, img(2), LunMode.DISK_RO)))
        assertEquals(
            listOf(
                Op.Unbind,
                Op.Unlink(massStorageLink),
                Op.Bind(udc),
                Op.Mkdir("$massStorage/lun.1"),
                Op.Write("$massStorage/lun.1/ro", "1"),
                Op.Write("$massStorage/lun.1/file", img(2)),
                Op.Unbind,
                Op.Symlink(massStorage, massStorageLink),
                Op.Bind(udc),
            ),
            planner.plan(kernel.read(), desired).ops,
        )
    }

    @Test
    fun `failed slot keeps its flags and gets no file`() {
        val kernel = kernelWith(cdrom(iso(1)))
        val journal = Journal(desired = cdrom(iso(2)), slotIntents = mapOf(0 to SlotIntent.FAILED))
        assertEquals(listOf(Op.Write("$massStorage/lun.0/file", "")), planner.plan(kernel.read(), GadgetTarget.of(journal)).ops)
    }

    @Test
    fun `a layout an older version left is replaced by the prefix ahead of the HAL's links`() {
        // Mass storage, keyboard and the plain boot mouse, linked after ADB, with no RNDIS.
        val kernel = PlannerKernel()
        val fs = kernel.fs
        val bootMouse = "functions/hid.bootleg_mouse"
        fs.asActor("older version") {
            fs.mkdir(massStorage)
            for (function in listOf(keyboard, bootMouse)) {
                fs.mkdir(function)
                fs.write("$function/report_length", "8")
                fs.writeBytes("$function/report_desc", byteArrayOf(1))
            }
            fs.write("UDC", "")
            listOf(massStorage, keyboard, bootMouse).forEach { fs.symlink(it, "configs/b.1/${it.substringAfterLast('/')}") }
            fs.write("UDC", udc)
        }
        val target = GadgetTarget.of(Desired(hid = hidOn))
        kernel.converge(planner, target)
        assertEquals(prefixThenAdb, kernel.links())
        assertFalse("hid.bootleg_mouse" in kernel.read().functions, "the old boot mouse is removed")
        assertEquals(emptyList<String>(), GadgetInvariants.violations(kernel.read(), target))
    }

    @Test
    fun `a prefix that lost its place is relinked in one switch with a fresh held daemon, never bound without it`() {
        val desired = Desired(prefix = true, network = TetherMode.RNDIS)
        val prefix = LinkOrder.prefix(TetherMode.RNDIS)
        val kernel = kernelWith(desired)
        val fs = kernel.fs
        fs.asActor("other") {
            fs.write("UDC", "")
            prefix.forEach { fs.unlink(GadgetPaths.configLink(it)) }
            prefix.forEach { fs.symlink(GadgetPaths.function(it), GadgetPaths.configLink(it)) }
            fs.write("UDC", udc)
        }
        val plan = planner.plan(kernel.read(), desired)
        assertEquals(1, plan.ops.count { it is Op.Bind }, "${plan.ops}")
        kernel.execute(plan)
        assertEquals(prefix + "function0", kernel.links())
        assertEquals(listOf("start", "release", "start", "release"), kernel.daemon.events)
    }

    @Test
    fun `an establishment a crash interrupted resumes from the held daemon, finishing the HAL round trip first`() {
        for (network in TetherMode.entries) {
            val desired = Desired(prefix = true, network = network)
            val establishment = planner.plan(PlannerKernel().read(), desired).stages.single()
            // The root process died with the links made and the daemon held, before or after the HAL relinked.
            for ((afterRelink, roundTripPending) in listOf(false to true, true to true, true to false)) {
                val kernel = PlannerKernel()
                kernel.execute(Plan(listOf(Stage(establishment.prepare, null, emptyList()))))
                kernel.fs.write("UDC", "")
                checkNotNull(establishment.switch).links.forEach { kernel.fs.symlink(it.target, it.link) }
                if (afterRelink) kernel.execute(Plan(listOf(Stage(emptyList(), null, listOf(Op.RelinkForeign)))))
                val target = GadgetTarget.of(desired).copy(usbRoundTrip = roundTripPending)
                val resume = planner.plan(kernel.read(), target, udc)
                assertFalse(Op.StartRndis in resume.ops, "${resume.ops}")
                assertEquals(
                    roundTripPending,
                    Op.RelinkForeign in resume.ops,
                    "a pending round trip ends before the release: ${resume.ops}",
                )
                kernel.execute(resume)
                assertEquals(LinkOrder.prefix(network) + "function0", kernel.links())
                assertEquals(emptyList<String>(), GadgetInvariants.violations(kernel.read(), target))
            }
        }
    }

    @Test
    fun `a dead daemon is restarted in place, or waits while only in-place work is needed`() {
        val rndis = TetherMode.RNDIS
        val kernel = kernelWith(cdrom(iso(1), rndis))
        kernel.daemon.die()
        val restart = listOf(Op.Unbind, Op.StartRndis, Op.ReleaseRndis, Op.Bind(udc))
        val swap = planner.plan(kernel.read(), GadgetTarget.of(cdrom(iso(2), rndis)), repairRndis = false)
        assertEquals(listOf(Op.Write("$massStorage/lun.0/file", ""), Op.Write("$massStorage/lun.0/file", iso(2))), swap.ops)
        val disk = GadgetTarget.of(Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RW)), network = rndis))
        assertEquals(restart, planner.plan(kernel.read(), disk, repairRndis = false).ops, "any switch restarts it first")
        assertEquals(Plan.EMPTY, planner.plan(kernel.read(), GadgetTarget.of(cdrom(iso(1), rndis)), repairRndis = false))
        val pending = GadgetTarget.of(cdrom(iso(1), rndis)).copy(usbRoundTrip = true)
        assertEquals(
            listOf(Op.Unbind, Op.StartRndis, Op.RelinkForeign, Op.ReleaseRndis, Op.Bind(udc)),
            planner.plan(kernel.read(), pending).ops,
        )
        val plan = planner.plan(kernel.read(), GadgetTarget.of(cdrom(iso(1), rndis)))
        assertEquals(restart, plan.ops)
        kernel.execute(plan)
        assertEquals(LinkOrder.prefix(rndis) + "function0" + GadgetNames.MASS_STORAGE_FUNCTION, kernel.links())
    }

    @Test
    fun `Bootleg-off removes everything, and with nothing else linked it all happens inside the window`() =
        TetherMode.entries.forEach { network ->
            val kernel = PlannerKernel()
            kernel.fs.asActor("setup") {
                kernel.fs.write("UDC", "")
                kernel.fs.unlink("configs/b.1/function0")
            }
            val desired = Desired(listOf(SlotSpec(0, iso(1), LunMode.CDROM)), hidOn, network = network)
            val on = planner.plan(kernel.read(), GadgetTarget.of(desired), udc)
            assertFalse(Op.RelinkForeign in on.ops, "nothing foreign to relink")
            assertEquals(network == TetherMode.RNDIS, Op.StartRndis in on.ops, "NCM needs no gate without a round trip")
            kernel.converge(planner, GadgetTarget.of(desired))
            assertEquals(udc, kernel.fs.udc)
            assertEquals(emptyList<String>(), GadgetInvariants.violations(kernel.read(), GadgetTarget.of(desired)))
            val off = planner.plan(kernel.read(), Desired(), udc)
            val offSwitch = checkNotNull(off.stages.single().switch)
            assertEquals(null, offSwitch.bind)
            if (network == TetherMode.RNDIS) {
                val rmdir = offSwitch.unlinkedOps.indexOf(Op.Rmdir("functions/ffs.bootleg_rndis"))
                assertTrue(offSwitch.unlinkedOps.indexOf(Op.StopRndis) in 0 until rmdir)
            }
            kernel.execute(off)
            assertEquals("", kernel.fs.udc)
            assertEquals(emptyList<String>(), kernel.read().functions.filter(GadgetPaths::isOwnedName))
            assertEquals(Plan.EMPTY, planner.plan(kernel.read(), Desired(), udc), "unbound with nothing linked is converged")
        }

    @Test
    fun `an unlinked HID function left by a crash is rebuilt`() {
        val kernel = PlannerKernel()
        kernel.fs.mkdir(keyboard)
        val ops = planner.plan(kernel.read(), Desired(hid = HidSpec(keyboard = true))).ops
        val rmdir = ops.indexOf(Op.Rmdir(keyboard))
        assertTrue(rmdir >= 0, "$ops")
        assertTrue(ops.indexOf(Op.Mkdir(keyboard)) > rmdir, "$ops")
    }

    @Test
    fun `an unlinked mass storage function is reconfigured in place before linking`() {
        val kernel = kernelWith(Desired(prefix = true))
        kernel.fs.mkdir(massStorage)
        kernel.fs.mkdir("$massStorage/lun.1")
        kernel.fs.mkdir("$massStorage/lun.2")
        val desired = Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO)))
        val plan = planner.plan(kernel.read(), desired)
        assertEquals(1, plan.stages.size)
        assertEquals(
            listOf(Op.Rmdir("$massStorage/lun.2"), Op.Rmdir("$massStorage/lun.1"), Op.Write("$massStorage/lun.0/ro", "1")),
            plan.stages[0].prepare.take(3),
        )
        kernel.execute(plan)
        assertEquals(emptyList<Op>(), planner.plan(kernel.read(), desired).ops)
    }

    @Test
    fun `random plans run cleanly on the kernel model, only touch owned paths, and converge`(): Unit =
        runBlocking {
            checkAll(200, Arb.long()) { seed ->
                val random = Random(seed)
                val kernel = PlannerKernel()
                repeat(4) {
                    // Either network function, so some steps are mode changes.
                    val desired = randomDesired(random).copy(network = TetherMode.entries.random(random))
                    // No marker (an old journal), or one of two nonces (a new journal adopting older LUNs).
                    val marker = listOf(null, GadgetRig.MARKER, LunMarker.inquiryString("zz99")).random(random)
                    val target = GadgetTarget.of(desired).copy(lunMarker = marker)
                    repeat(PlannerKernel.PASS_COUNT_MAX) {
                        val before = kernel.read()
                        val plan = planner.plan(before, target)
                        if (plan.ops.isEmpty()) return@repeat
                        assertTrue(plan.ops.flatMap(GadgetPaths::touchedPaths).all(GadgetPaths::isMutableByBootleg))
                        kernel.execute(plan)
                        val after = kernel.read()
                        val simulated = GadgetSimulation.apply(before, plan.ops)
                        assertEquals(simulated, after)
                        assertEquals(kernel.fs.linkOrder(), after.configLinks.keys.toList(), "the reader keeps the kernel's link order")
                        assertEquals(simulated.configLinks.toList(), after.configLinks.toList(), "the simulation keeps the link order")
                        // Swaps (and marker writes) that keep the topology never re-enumerate.
                        if (!plan.reenumerates) {
                            assertTrue(
                                plan.ops.all { it is Op.Write && (it.path.endsWith("/file") || it.path.endsWith("/inquiry_string")) },
                            )
                        }
                        for (stage in plan.stages) {
                            assertTrue(
                                (stage.prepare + stage.cleanup).none {
                                    it is Op.Unbind ||
                                        it is Op.Bind ||
                                        it is Op.Symlink ||
                                        it is Op.Unlink
                                },
                            )
                        }
                    }
                    val after = kernel.read()
                    assertEquals(emptyList<String>(), GadgetInvariants.violations(after, target))
                    assertEquals(emptyList<Op>(), planner.plan(after, target).ops, "replanning a converged gadget must be a no-op")
                }
            }
        }

    @Test
    fun `random plans never move the prefix once it is first, and bind only with it first`(): Unit =
        runBlocking {
            checkAll(300, Arb.long()) { seed ->
                val random = Random(seed)
                val network = TetherMode.entries.random(random)
                val kernel = kernelWith(Desired(prefix = true, network = network))
                repeat(6) {
                    if (random.nextInt(8) == 0) kernel.daemon.die()
                    val desired = if (random.nextInt(3) == 0) Desired(hid = HidSpec(random.nextBoolean(), true)) else randomDesired(random)
                    val target = GadgetTarget.of(desired.copy(network = network))
                    val plan = planner.plan(kernel.read(), target)
                    var state = kernel.read()
                    for (op in plan.ops) {
                        state = GadgetSimulation.apply(state, op)
                        if (op is Op.Bind) {
                            assertTrue(LinkOrder.isCanonical(state.configLinks), "bound out of order: $state")
                            assertTrue(LinkOrder.prefixServed(state, network), "bound without the prefix first and served: $state")
                        }
                    }
                    val prefixTouched =
                        plan.ops.filter { op ->
                            (op is Op.Unlink || op is Op.Symlink) &&
                                GadgetPaths.touchedPaths(op).single().substringAfterLast('/') in LinkOrder.prefix(network)
                        }
                    assertEquals(emptyList<Op>(), prefixTouched, "the prefix was relinked: ${plan.ops}")
                    kernel.converge(planner, target)
                    assertEquals(emptyList<String>(), GadgetInvariants.violations(kernel.read(), target))
                }
            }
        }
}

/** The kernel model plus the RNDIS daemon fake; [execute] runs a plan's ops against both, as the executor would. */
class PlannerKernel(
    val fs: FakeConfigFs = FakeConfigFs.pixel(GadgetRig.HOST_FILES),
) {
    val daemon = FakeRndisDaemon(fs)

    private object QuietProps : UsbProps {
        override fun usbState() = "adb"

        override fun usbConfig() = "adb"
    }

    fun read() = GadgetReader(fs, QuietProps, daemon).read()

    fun execute(plan: Plan) {
        for (op in plan.ops) {
            when (op) {
                // A switch may start unbound (the HAL dropped the UDC); the executor then skips the write.
                Op.Unbind -> if (fs.udc.isNotEmpty()) fs.write("UDC", "")
                is Op.Bind -> fs.write("UDC", op.udc)
                is Op.Mkdir -> fs.mkdir(op.path)
                is Op.Rmdir -> fs.rmdir(op.path)
                is Op.Write -> fs.write(op.path, op.value)
                is Op.WriteBytes -> fs.writeBytes(op.path, op.bytes)
                is Op.Symlink -> fs.symlink(op.target, op.link)
                is Op.Unlink -> fs.unlink(op.link)
                Op.StartRndis -> daemon.start()
                Op.ReleaseRndis -> daemon.release()
                Op.StopRndis -> daemon.stop()
                // The HAL: its links again, after everything else.
                Op.RelinkForeign ->
                    fs.asActor(FakeConfigFs.HAL) {
                        val foreign = fs.linkOrder().filter { !GadgetPaths.isOwnedName(it) }
                        val targets =
                            foreign.associateWith {
                                checkNotNull(
                                    fs.readLink(GadgetPaths.configLink(it)),
                                ).substringAfterLast('/')
                            }
                        foreign.forEach { fs.unlink(GadgetPaths.configLink(it)) }
                        foreign.forEach { fs.symlink(GadgetPaths.function(targets.getValue(it)), GadgetPaths.configLink(it)) }
                    }
            }
        }
    }

    /** Plans and executes until nothing is left (each prefix step is its own plan). */
    fun converge(
        planner: GadgetPlanner,
        target: GadgetTarget,
    ) {
        repeat(PASS_COUNT_MAX) {
            val plan = planner.plan(read(), target, fs.udcName)
            if (plan.ops.isEmpty()) return
            execute(plan)
        }
        error("did not converge on $target within $PASS_COUNT_MAX plans: ${read()}")
    }

    companion object {
        const val PASS_COUNT_MAX = 4
    }
}

/** Random valid [Desired]: dense LUNs, paths from [GadgetRig.HOST_FILES], no writable file twice; never empty. */
fun randomDesired(
    random: Random,
    lunCountMax: Int = 4,
): Desired {
    val lunCount = if (random.nextInt(10) == 0) random.nextInt(GadgetLimits.LUN_COUNT_MAX + 1) else random.nextInt(lunCountMax + 1)
    val used = mutableSetOf<String>()
    val slots =
        (0 until lunCount).map { lun ->
            val mode = LunMode.entries.random(random)
            var path = if (mode == LunMode.CDROM) iso(random.nextInt(3)) else img(random.nextInt(4))
            if (mode == LunMode.DISK_RW && path in used) path = img(4 + lun)
            used += path
            SlotSpec(lun, path, mode)
        }
    // A read-only reuse of a path already exported writable is still a writable duplicate.
    val writable = slots.filter { it.mode == LunMode.DISK_RW }.map { it.backingPath }.toSet()
    val fixed = slots.map { if (it.mode != LunMode.DISK_RW && it.backingPath in writable) it.copy(backingPath = iso(9)) else it }
    return Desired(fixed, HidSpec(keyboard = random.nextBoolean(), mouse = random.nextInt(3) == 0), prefix = true)
}
