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
    private val massStorage = "functions/mass_storage.bootleg"
    private val massStorageLink = "configs/b.1/mass_storage.bootleg"

    private object QuietProps : UsbProps {
        override fun usbState() = "adb"

        override fun usbConfig() = "adb"
    }

    /** Applies [desired] to a fresh Pixel gadget by executing a plan against the kernel model. */
    private fun gadgetWith(desired: Desired): FakeConfigFs {
        val fs = FakeConfigFs.pixel(GadgetRig.HOST_FILES)
        execute(fs, planner.plan(GadgetReader(fs, QuietProps).read(), desired))
        return fs
    }

    private fun execute(
        fs: FakeConfigFs,
        plan: Plan,
    ) {
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
            }
        }
    }

    private fun read(fs: FakeConfigFs) = GadgetReader(fs, QuietProps).read()

    private fun cdrom(path: String) = Desired(listOf(SlotSpec(0, path, LunMode.CDROM)))

    @Test
    fun `empty desired on a foreign-only gadget plans nothing`() {
        val plan = planner.plan(read(FakeConfigFs.pixel()), Desired())
        assertEquals(Plan.EMPTY, plan)
    }

    @Test
    fun `first CD-ROM export builds the function while bound, then only links inside the switch`() {
        val plan = planner.plan(read(FakeConfigFs.pixel(GadgetRig.HOST_FILES)), cdrom(iso(1)))
        assertEquals(
            listOf(
                Stage(
                    prepare =
                        listOf(
                            Op.Mkdir(massStorage),
                            Op.Write("$massStorage/lun.0/ro", "1"),
                            Op.Write("$massStorage/lun.0/cdrom", "1"),
                            Op.Write("$massStorage/lun.0/file", iso(1)),
                        ),
                    switch = Switch(emptyList(), listOf(Op.Symlink(massStorage, massStorageLink)), Op.Bind(udc)),
                    cleanup = emptyList(),
                ),
            ),
            plan.stages,
        )
        assertTrue(plan.reenumerates)
    }

    @Test
    fun `same-mode media swap is in place`() {
        val fs = gadgetWith(cdrom(iso(1)))
        val plan = planner.plan(read(fs), cdrom(iso(2)))
        assertEquals(listOf(Op.Write("$massStorage/lun.0/file", ""), Op.Write("$massStorage/lun.0/file", iso(2))), plan.ops)
        assertFalse(plan.reenumerates)
    }

    @Test
    fun `mode change detaches the function, reconfigures it unlinked, then relinks`() {
        val fs = gadgetWith(cdrom(iso(1)))
        val plan = planner.plan(read(fs), Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RW))))
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
        assertEquals(2, plan.stages.size)
    }

    @Test
    fun `a linked LUN with removable=0 or nofua=1 is detached, repaired and relinked, and then verifies`() {
        for ((attribute, broken) in listOf("removable" to "0", "nofua" to "1")) {
            val fs = gadgetWith(cdrom(iso(1)))
            fs.asActor("other") {
                fs.unlink(massStorageLink)
                fs.write("$massStorage/lun.0/$attribute", broken)
                fs.symlink(massStorage, massStorageLink)
                fs.write("UDC", udc)
            }
            val target = GadgetTarget.of(cdrom(iso(1)))
            assertTrue(GadgetInvariants.violations(read(fs), target).any { "$attribute=$broken" in it }, attribute)
            val plan = planner.plan(read(fs), target)
            assertTrue(plan.reenumerates, attribute)
            assertTrue(Op.Write("$massStorage/lun.0/$attribute", if (broken == "0") "1" else "0") in plan.ops, attribute)
            execute(fs, plan)
            assertEquals(emptyList<String>(), GadgetInvariants.violations(read(fs), target), attribute)
            assertEquals(emptyList<Op>(), planner.plan(read(fs), target).ops, attribute)
        }
    }

    @Test
    fun `the LUN marker is written in place on a linked function, without re-enumerating`() {
        val fs = gadgetWith(cdrom(iso(1)))
        assertEquals("", read(fs).luns[0]?.inquiryString)
        val target = GadgetTarget.of(cdrom(iso(1))).copy(lunMarker = GadgetRig.MARKER)
        val plan = planner.plan(read(fs), target)
        assertEquals(listOf(Op.Write("$massStorage/lun.0/inquiry_string", GadgetRig.MARKER)), plan.ops)
        assertFalse(plan.reenumerates)
        execute(fs, plan)
        assertEquals(GadgetRig.MARKER, read(fs).luns[0]?.inquiryString)
        assertEquals(emptyList<String>(), GadgetInvariants.violations(read(fs), target))
    }

    @Test
    fun `adding a LUN relinks the function because configfs forbids mkdir while linked`() {
        val fs = gadgetWith(cdrom(iso(1)))
        val desired = Desired(listOf(SlotSpec(0, iso(1), LunMode.CDROM), SlotSpec(1, img(2), LunMode.DISK_RO)))
        val ops = planner.plan(read(fs), desired).ops
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
            ops,
        )
    }

    @Test
    fun `HID attributes and descriptor are written before the link`() {
        val ops = planner.plan(read(FakeConfigFs.pixel()), Desired(hid = HidSpec(keyboard = true, mouse = true))).ops
        val keyboard = "functions/hid.bootleg_kbd"
        val mouse = "functions/hid.bootleg_mouse_wheel"
        // HID without a disk still links mass storage first, with one empty LUN (the placeholder).
        assertEquals(
            listOf(
                Op.Mkdir(massStorage),
                Op.Mkdir(keyboard),
                Op.Mkdir(mouse),
                Op.Write("$massStorage/lun.0/ro", "1"),
                Op.Write("$keyboard/subclass", "1"),
                Op.Write("$keyboard/protocol", "1"),
                Op.Write("$keyboard/report_length", "8"),
                Op.WriteBytes("$keyboard/report_desc", FakeHidDescriptors.keyboard()),
                Op.Write("$mouse/subclass", "1"),
                Op.Write("$mouse/protocol", "2"),
                Op.Write("$mouse/report_length", "5"),
                Op.WriteBytes("$mouse/report_desc", FakeHidDescriptors.mouse(MouseLayout.WHEEL)),
                Op.Unbind,
                Op.Symlink(massStorage, massStorageLink),
                Op.Symlink(keyboard, "configs/b.1/hid.bootleg_kbd"),
                Op.Symlink(mouse, "configs/b.1/hid.bootleg_mouse_wheel"),
                Op.Bind(udc),
            ),
            ops,
        )
    }

    @Test
    fun `empty desired removes every Bootleg function and nothing else`() {
        val desired =
            Desired(
                listOf(SlotSpec(0, iso(1), LunMode.CDROM), SlotSpec(1, img(1), LunMode.DISK_RW), SlotSpec(2, img(2), LunMode.DISK_RO)),
                HidSpec(keyboard = true),
            )
        val fs = gadgetWith(desired)
        val plan = planner.plan(read(fs), Desired())
        assertEquals(
            listOf(
                Op.Unbind,
                Op.Unlink("configs/b.1/hid.bootleg_kbd"),
                Op.Unlink(massStorageLink),
                Op.Bind(udc),
                Op.Rmdir("functions/hid.bootleg_kbd"),
                Op.Rmdir("$massStorage/lun.2"),
                Op.Rmdir("$massStorage/lun.1"),
                Op.Rmdir(massStorage),
            ),
            plan.ops,
        )
        execute(fs, plan)
        assertEquals(FakeConfigFs.PIXEL_FOREIGN_FUNCTIONS.toSet(), read(fs).functions)
    }

    @Test
    fun `changing the mouse layout swaps the mouse function in one switch and leaves the keyboard linked`() {
        for ((from, to) in listOf(MouseLayout.WHEEL to MouseLayout.BOOT, MouseLayout.BOOT to MouseLayout.WHEEL)) {
            val drives = listOf(SlotSpec(0, img(1), LunMode.DISK_RO))
            val fs = gadgetWith(Desired(drives, HidSpec(keyboard = true, mouse = true, mouseLayout = from)))
            val target = Desired(drives, HidSpec(keyboard = true, mouse = true, mouseLayout = to))
            val plan = planner.plan(read(fs), target)
            val stage = plan.stages.single()
            val oldFunction = "functions/${from.functionName}"
            val newFunction = "functions/${to.functionName}"
            assertEquals(
                listOf(
                    Op.Mkdir(newFunction),
                    Op.Write("$newFunction/subclass", "1"),
                    Op.Write("$newFunction/protocol", "2"),
                    Op.Write("$newFunction/report_length", to.reportLengthBytes.toString()),
                    Op.WriteBytes("$newFunction/report_desc", FakeHidDescriptors.mouse(to)),
                ),
                stage.prepare,
            )
            assertEquals(
                Switch(
                    unlinks = listOf(Op.Unlink("configs/b.1/${from.functionName}")),
                    links = listOf(Op.Symlink(newFunction, "configs/b.1/${to.functionName}")),
                    bind = Op.Bind(udc),
                ),
                stage.switch,
            )
            assertEquals(listOf(Op.Rmdir(oldFunction)), stage.cleanup)
            // The keyboard and the drives are never touched.
            assertTrue(plan.ops.flatMap(GadgetPaths::touchedPaths).none { it.contains("bootleg_kbd") || it.contains("mass_storage") })
            execute(fs, plan)
            val after = read(fs)
            assertEquals(to.functionName, after.configLinks[to.functionName])
            assertFalse(from.functionName in after.functions)
            assertEquals(Plan.EMPTY, planner.plan(after, target))
        }
    }

    @Test
    fun `an old linked boot mouse is replaced by the wheel mouse once the wheel layout is wanted`() {
        // What an earlier version left linked: hid.bootleg_mouse with the 3-byte layout.
        val fs = gadgetWith(Desired(hid = HidSpec(keyboard = true, mouse = true, mouseLayout = MouseLayout.BOOT)))
        val plan = planner.plan(read(fs), Desired(hid = HidSpec(keyboard = true, mouse = true)))
        execute(fs, plan)
        assertEquals(
            setOf(GadgetNames.MASS_STORAGE_FUNCTION, GadgetNames.HID_KEYBOARD_FUNCTION, GadgetNames.HID_WHEEL_MOUSE_FUNCTION),
            read(fs)
                .functions
                .filter {
                    "bootleg" in
                        it
                }.toSet(),
        )
        assertEquals("5", fs.read("functions/${GadgetNames.HID_WHEEL_MOUSE_FUNCTION}/report_length").trim())
    }

    @Test
    fun `an unlinked HID function left by a crash is rebuilt`() {
        val fs = FakeConfigFs.pixel()
        fs.mkdir("functions/hid.bootleg_kbd")
        val ops = planner.plan(read(fs), Desired(hid = HidSpec(keyboard = true))).ops
        val rmdir = ops.indexOf(Op.Rmdir("functions/hid.bootleg_kbd"))
        assertTrue(rmdir >= 0, "$ops")
        assertTrue(ops.indexOf(Op.Mkdir("functions/hid.bootleg_kbd")) > rmdir, "$ops")
    }

    @Test
    fun `an unlinked mass storage function is reconfigured in place before linking`() {
        val fs = FakeConfigFs.pixel(GadgetRig.HOST_FILES)
        fs.mkdir(massStorage)
        fs.mkdir("$massStorage/lun.1")
        fs.mkdir("$massStorage/lun.2")
        val plan = planner.plan(read(fs), Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO))))
        assertEquals(1, plan.stages.size)
        assertEquals(
            listOf(Op.Rmdir("$massStorage/lun.2"), Op.Rmdir("$massStorage/lun.1"), Op.Write("$massStorage/lun.0/ro", "1")),
            plan.stages[0].prepare.take(3),
        )
        execute(fs, plan)
        assertEquals(emptyList<Op>(), planner.plan(read(fs), Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO)))).ops)
    }

    @Test
    fun `detaching the only linked function reconfigures it inside one switch, since nothing could bind`() {
        val fs = gadgetWith(cdrom(iso(1)))
        fs.asActor("setup") {
            fs.write("UDC", "")
            listOf("function0", "hid.keyboard", "hid.touchpad").forEach { fs.unlink("configs/b.1/$it") }
            fs.write("UDC", udc)
        }
        val plan = planner.plan(read(fs), Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO)), HidSpec(keyboard = true)))
        val stage = plan.stages.single()
        val switch = checkNotNull(stage.switch)
        assertEquals(listOf(Op.Unlink(massStorageLink)), switch.unlinks)
        assertEquals(
            listOf(
                Op.Write("$massStorage/lun.0/file", ""),
                Op.Write("$massStorage/lun.0/cdrom", "0"),
                Op.Write("$massStorage/lun.0/file", img(1)),
            ),
            switch.unlinkedOps,
        )
        assertEquals(Op.Bind(udc), switch.bind)
        // The new keyboard is built while the mass storage link still keeps the UDC bound.
        assertTrue(
            stage.prepare.isNotEmpty() &&
                stage.prepare.all { GadgetPaths.functionOf(GadgetPaths.touchedPaths(it).single()) == "hid.bootleg_kbd" },
        )
        execute(fs, plan)
        assertEquals(udc, fs.udc)
        assertEquals(
            emptyList<Op>(),
            planner.plan(read(fs), Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO)), HidSpec(keyboard = true))).ops,
        )
    }

    @Test
    fun `with nothing linked at all, everything happens inside the window`() {
        val fs = FakeConfigFs.pixel(GadgetRig.HOST_FILES)
        fs.asActor("setup") {
            fs.write("UDC", "")
            listOf("function0", "hid.keyboard", "hid.touchpad").forEach { fs.unlink("configs/b.1/$it") }
        }
        val desired = Desired(listOf(SlotSpec(0, iso(1), LunMode.CDROM)), HidSpec(keyboard = true))
        val plan = planner.plan(read(fs), desired, udc)
        val stage = plan.stages.single()
        assertEquals(emptyList<Op>(), stage.prepare)
        assertEquals(emptyList<Op>(), stage.cleanup)
        assertTrue(checkNotNull(stage.switch).unlinkedOps.any { it == Op.Mkdir(massStorage) })
        execute(fs, plan)
        assertEquals(udc, fs.udc)
        assertEquals(emptyList<String>(), GadgetInvariants.violations(read(fs), GadgetTarget.of(desired)))

        // Turning everything off again leaves nothing to bind: the cleanup also runs in the window, and no bind is planned.
        val off = planner.plan(read(fs), Desired(), udc)
        val offSwitch = checkNotNull(off.stages.single().switch)
        assertEquals(null, offSwitch.bind)
        assertTrue(offSwitch.unlinkedOps.contains(Op.Rmdir(massStorage)))
        execute(fs, off)
        assertEquals("", fs.udc)
        assertEquals(emptyList<String>(), GadgetInvariants.violations(read(fs), GadgetTarget.of(Desired())))
        assertEquals(Plan.EMPTY, planner.plan(read(fs), Desired(), udc), "unbound with nothing linked is converged, not a switch")
    }

    @Test
    fun `failed slot keeps its flags and gets no file`() {
        val fs = gadgetWith(cdrom(iso(1)))
        val journal =
            Journal(
                desired = cdrom(iso(2)),
                slotIntents = mapOf(0 to SlotIntent.FAILED),
            )
        assertEquals(listOf(Op.Write("$massStorage/lun.0/file", "")), planner.plan(read(fs), GadgetTarget.of(journal)).ops)
    }

    @Test
    fun `random plans run cleanly on the kernel model, only touch owned paths, and converge in one step`(): Unit =
        runBlocking {
            checkAll(200, Arb.long()) { seed ->
                val random = Random(seed)
                val fs = FakeConfigFs.pixel(GadgetRig.HOST_FILES)
                repeat(4) {
                    val desired = randomDesired(random)
                    // No marker (an old journal), or one of two nonces (a new journal adopting older LUNs).
                    val marker = listOf(null, GadgetRig.MARKER, LunMarker.inquiryString("zz99")).random(random)
                    val target = GadgetTarget.of(desired).copy(lunMarker = marker)
                    val before = read(fs)
                    val plan = planner.plan(before, target)
                    assertTrue(plan.ops.flatMap(GadgetPaths::touchedPaths).all(GadgetPaths::isMutableByBootleg))
                    execute(fs, plan)
                    val after = read(fs)
                    val simulated = GadgetSimulation.apply(before, plan.ops)
                    assertEquals(simulated, after)
                    assertEquals(fs.linkOrder(), after.configLinks.keys.toList(), "the reader keeps the kernel's link order")
                    assertEquals(simulated.configLinks.toList(), after.configLinks.toList(), "the simulation keeps the link order")
                    assertEquals(emptyList<String>(), GadgetInvariants.violations(after, target, foreignBefore = before))
                    assertEquals(emptyList<Op>(), planner.plan(after, target).ops, "replanning a converged gadget must be a no-op")
                    // Swaps (and marker writes) that keep the topology never re-enumerate.
                    if (!plan.reenumerates) {
                        assertTrue(plan.ops.all { it is Op.Write && (it.path.endsWith("/file") || it.path.endsWith("/inquiry_string")) })
                    }
                    // Only relinking happens while unbound; nothing relinks while bound.
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
            }
        }

    @Test
    fun `random plans keep every function on its interface number - canonical order, kept links never relinked`(): Unit =
        runBlocking {
            checkAll(300, Arb.long()) { seed ->
                val random = Random(seed)
                val fs = FakeConfigFs.pixel(GadgetRig.HOST_FILES)
                var previous = Desired()
                repeat(6) {
                    val desired = if (random.nextInt(3) == 0) randomHidOnly(random) else randomDesired(random)
                    val before = read(fs)
                    val target = GadgetTarget.of(desired)
                    val plan = planner.plan(before, target)
                    // Bound with our links out of order at any point would hand a function another's number.
                    var state = before
                    for (op in plan.ops) {
                        state = GadgetSimulation.apply(state, op)
                        if (op is Op.Bind) assertTrue(LinkOrder.isCanonical(state.configLinks), "bound out of order: $state")
                    }
                    // A link is only taken down when it goes away, mass storage is reconfigured (detached
                    // before it), or something earlier in the order is (re)linked.
                    val relinked = plan.ops.filterIsInstance<Op.Symlink>().map { it.link.substringAfterLast('/') }
                    val massStorageReconfigured = plan.ops.any { it is Op.Unlink && it.link.endsWith(GadgetNames.MASS_STORAGE_FUNCTION) }
                    for (unlink in plan.ops.filterIsInstance<Op.Unlink>()) {
                        val name = unlink.link.substringAfterLast('/')
                        val rank = LinkOrder.CANONICAL.indexOf(name)
                        val earlierRelinked = relinked.any { LinkOrder.CANONICAL.indexOf(it) in 0 until rank }
                        assertTrue(
                            name !in target.functions || earlierRelinked || massStorageReconfigured,
                            "$name relinked for no reason: $previous -> $desired: ${plan.ops}",
                        )
                    }
                    execute(fs, plan)
                    val after = read(fs)
                    assertTrue(LinkOrder.isCanonical(after.configLinks), "${after.configLinks}")
                    assertEquals(emptyList<String>(), GadgetInvariants.violations(after, target, foreignBefore = before))
                    previous = desired
                }
            }
        }

    @Test
    fun `adding a disk to HID without disks fills the placeholder in place, never moving the keyboard or mouse`() {
        val hid = HidSpec(keyboard = true, mouse = true)
        val fs = gadgetWith(Desired(hid = hid))
        val before = read(fs)
        assertEquals(
            listOf(GadgetNames.MASS_STORAGE_FUNCTION, GadgetNames.HID_KEYBOARD_FUNCTION, GadgetNames.HID_WHEEL_MOUSE_FUNCTION),
            LinkOrder.owned(before.configLinks),
        )
        assertEquals("", before.luns.getValue(0).file)
        val plan = planner.plan(before, Desired(listOf(SlotSpec(0, img(1), LunMode.DISK_RO)), hid))
        assertFalse(plan.reenumerates, "${plan.ops}")
        assertEquals(listOf(Op.Write("$massStorage/lun.0/file", img(1))), plan.ops)
    }

    @Test
    fun `a CD-ROM into the placeholder detaches mass storage with the HID after it and relinks all in order`() {
        val hid = HidSpec(keyboard = true, mouse = true)
        val fs = gadgetWith(Desired(hid = hid))
        val plan = planner.plan(read(fs), Desired(listOf(SlotSpec(0, iso(1), LunMode.CDROM)), hid))
        val detach = plan.stages.first().switch!!
        assertEquals(
            setOf(massStorageLink, "configs/b.1/hid.bootleg_kbd", "configs/b.1/hid.bootleg_mouse_wheel"),
            detach.unlinks.map { it.link }.toSet(),
        )
        execute(fs, plan)
        assertEquals(
            listOf(GadgetNames.MASS_STORAGE_FUNCTION, GadgetNames.HID_KEYBOARD_FUNCTION, GadgetNames.HID_WHEEL_MOUSE_FUNCTION),
            LinkOrder.owned(read(fs).configLinks),
        )
        // The detached HID functions were relinked as they were, not rebuilt.
        assertTrue(plan.ops.none { it is Op.Rmdir || (it is Op.Mkdir && "hid" in it.path) }, "${plan.ops}")
    }

    @Test
    fun `links an older version left out of order are put back in order`() {
        // HID linked first, then mass storage: what a disk change used to leave behind.
        val fs = gadgetWith(Desired(hid = HidSpec(keyboard = true, mouse = true)))
        fs.write("UDC", "")
        fs.unlink(massStorageLink)
        fs.symlink(massStorage, massStorageLink)
        fs.write("UDC", udc)
        val legacy = read(fs)
        assertFalse(LinkOrder.isCanonical(legacy.configLinks))
        val target = Desired(hid = HidSpec(keyboard = true, mouse = true))
        val plan = planner.plan(legacy, target)
        assertTrue(plan.reenumerates)
        execute(fs, plan)
        assertTrue(LinkOrder.isCanonical(read(fs).configLinks), "${read(fs).configLinks}")
        assertEquals(Plan.EMPTY, planner.plan(read(fs), target))
    }
}

/** Random HID-only [Desired] (both, one or the other, either mouse layout). */
fun randomHidOnly(random: Random): Desired {
    val keyboard = random.nextBoolean()
    val mouse = !keyboard || random.nextBoolean()
    return Desired(hid = HidSpec(keyboard, mouse, MouseLayout.entries.random(random)))
}

/** Random valid [Desired]: dense LUNs, paths from [GadgetRig.HOST_FILES], no writable file twice. */
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
    return Desired(
        fixed,
        HidSpec(keyboard = random.nextBoolean(), mouse = random.nextInt(3) == 0, mouseLayout = MouseLayout.entries.random(random)),
    )
}
