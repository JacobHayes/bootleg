package dev.jacobhayes.bootleg.core.gadget

/**
 * The only ops that run while the UDC is unbound: [Op.Unbind], then [unlinks], [unlinkedOps] and
 * [links], then [bind]. The Pixel USB HAL rebinds the UDC a few hundred ms after any unbind, so this
 * window must stay tiny.
 *
 * [unlinkedOps] is empty unless nothing at all is linked after [unlinks]. The kernel refuses to bind a
 * config without functions (EINVAL), so with nothing to keep bound the reconfiguration that normally
 * runs bound (prepare, or cleanup when nothing gets relinked) runs here instead, on unlinked functions,
 * at most [GadgetPlanner.UNLINKED_OP_COUNT_MAX] ops. The host sees no device meanwhile, and the HAL's
 * rebind can't bind an empty config. A null [bind] means nothing is linked afterwards either, so the
 * gadget stays unbound.
 */
data class Switch(
    val unlinks: List<Op.Unlink>,
    val links: List<Op.Symlink>,
    val bind: Op.Bind?,
    val unlinkedOps: List<Op> = emptyList(),
) {
    val ops: List<Op> get() = listOf(Op.Unbind) + unlinks + unlinkedOps + links + listOfNotNull(bind)
}

/**
 * One transition: [prepare] runs while bound and only reconfigures unlinked functions (plus in-place
 * media swaps on linked LUNs), [switch] relinks, and [cleanup] runs while bound and removes functions
 * that are no longer linked or wanted. Both are empty when nothing is linked that could keep the UDC
 * bound; their ops then sit in [Switch.unlinkedOps].
 */
data class Stage(
    val prepare: List<Op>,
    val switch: Switch?,
    val cleanup: List<Op>,
) {
    val ops: List<Op> get() = prepare + switch?.ops.orEmpty() + cleanup
}

/** Ordered stages; [reenumerates] is true when the plan binds the UDC (the host sees a reconnect). */
data class Plan(
    val stages: List<Stage>,
) {
    val ops: List<Op> get() = stages.flatMap { it.ops }
    val reenumerates: Boolean get() = stages.any { it.switch?.bind != null }

    companion object {
        val EMPTY = Plan(emptyList())
    }
}

/** HID function attributes (boot-protocol keyboard and mouse). */
object HidFunctionSpec {
    const val SUBCLASS_BOOT = 1
    const val KEYBOARD_PROTOCOL = 1
    const val KEYBOARD_REPORT_LENGTH_BYTES = 8
    const val MOUSE_PROTOCOL = 2
    const val MOUSE_REPORT_LENGTH_BYTES = 3
}

/**
 * Pure planner: maps (live state, target) to staged configfs ops. It only ever touches names
 * containing `bootleg` plus `UDC`.
 *
 * Configfs refuses links into a bound config and LUN/HID attribute changes on a linked function, and
 * the HAL rebinds the UDC soon after any unbind. So everything except relinking happens while bound,
 * on unlinked functions: a new function is fully built before its link appears. A linked
 * mass_storage function whose LUN set, `ro`/`cdrom`, `removable` or `nofua` must change is first
 * detached in its own switch (unbind, unlink, bind), reconfigured while unlinked, then relinked.
 * If nothing else is linked the detach can't bind, so detach, reconfiguration and relink form one
 * switch with the reconfiguration in [Switch.unlinkedOps]. Media swaps on a LUN whose flags stay the
 * same happen in place, without re-enumerating.
 */
class GadgetPlanner(
    private val hidDescriptors: HidDescriptorProvider,
) {
    fun plan(
        current: GadgetState,
        desired: Desired,
        udc: String = current.udc,
    ): Plan = plan(current, GadgetTarget.of(desired), udc)

    fun plan(
        current: GadgetState,
        target: GadgetTarget,
        udc: String = current.udc,
        forceReenumerate: Boolean = false,
    ): Plan {
        val detach = detachStage(current, target, udc)
        val afterDetach = detach?.let { GadgetSimulation.apply(current, it.ops) } ?: current
        val main = mainStage(afterDetach, target, udc, forceReenumerate && detach == null)
        val mainSwitch = main.switch
        check(detach == null || mainSwitch != null) { "a detached function must be relinked: $main" }
        val detachSwitch = detach?.switch
        val stages =
            if (detachSwitch != null && detachSwitch.bind == null && mainSwitch != null) {
                // Nothing else was linked, so the detach couldn't bind: detach and relink become one switch.
                val merged = Stage(main.prepare, mainSwitch.copy(unlinks = detachSwitch.unlinks + mainSwitch.unlinks), main.cleanup)
                listOf(withinWindowIfUnbindable(current, merged))
            } else {
                listOfNotNull(detach, withinWindowIfUnbindable(afterDetach, main))
            }
        val plan = Plan(stages.filter { it.ops.isNotEmpty() })
        checkPlan(current, plan)
        return plan
    }

    /**
     * Moves bound-phase ops into the switch window when nothing is linked during it, since the UDC
     * can't be bound then: prepare ops on functions linked at the start (this switch unlinks them), all
     * of prepare if nothing at all is linked at the start, and cleanup if nothing gets relinked.
     * Otherwise [stage] is returned unchanged.
     */
    private fun withinWindowIfUnbindable(
        before: GadgetState,
        stage: Stage,
    ): Stage {
        val switch = stage.switch ?: return stage
        val unlinked = switch.unlinks.map { it.link.substringAfterLast('/') }.toSet()
        if ((before.configLinks.keys - unlinked).isNotEmpty()) return stage
        val linkedAtStart = before.configLinks.values.toSet()
        val bindableAtStart = before.configLinks.isNotEmpty()
        val (staysBound, moved) =
            stage.prepare.partition { op ->
                bindableAtStart && GadgetPaths.functionOf(GadgetPaths.touchedPaths(op).single()) !in linkedAtStart
            }
        val relinks = switch.links.isNotEmpty()
        val window = switch.copy(unlinkedOps = moved + if (relinks) emptyList() else stage.cleanup, bind = switch.bind.takeIf { relinks })
        return Stage(
            prepare = staysBound,
            // Unbound with nothing linked and nothing to change: there is no switch to make.
            switch = window.takeIf { it.unlinks.isNotEmpty() || it.unlinkedOps.isNotEmpty() || relinks },
            cleanup = if (relinks) stage.cleanup else emptyList(),
        )
    }

    /** Unlinks mass_storage when a linked LUN needs a change configfs only allows while unlinked. */
    private fun detachStage(
        current: GadgetState,
        target: GadgetTarget,
        udc: String,
    ): Stage? {
        val massStorage = GadgetNames.MASS_STORAGE_FUNCTION
        val linkNames = current.configLinks.filter { (name, function) -> GadgetPaths.isOwnedName(name) && function == massStorage }.keys
        if (target.luns.isEmpty() || linkNames.isEmpty()) return null
        val reconfigure =
            current.luns.keys != target.luns.indices.toSet() ||
                target.luns.withIndex().any { (lun, lunTarget) ->
                    val state = current.luns.getValue(lun)
                    val mode = lunTarget.mode
                    (mode != null && (state.ro != mode.readOnly || state.cdrom != mode.cdrom)) || !state.removable || state.nofua
                }
        if (!reconfigure) return null
        // With nothing else linked the UDC can't bind; it stays unbound until the relink.
        val bindable = (current.configLinks.keys - linkNames).isNotEmpty()
        val unlinks = linkNames.sorted().map { Op.Unlink(GadgetPaths.configLink(it)) }
        return Stage(prepare = emptyList(), switch = Switch(unlinks, emptyList(), if (bindable) bind(udc) else null), cleanup = emptyList())
    }

    private fun mainStage(
        state: GadgetState,
        target: GadgetTarget,
        udc: String,
        forceReenumerate: Boolean,
    ): Stage {
        val massStorage = GadgetNames.MASS_STORAGE_FUNCTION
        val wanted = target.functions
        val ownedFunctions = state.functions.filter(GadgetPaths::isOwnedName).toSet()
        val ownedLinks = state.configLinks.filterKeys(GadgetPaths::isOwnedName)
        val linked = state.configLinks.values.toSet()
        val wrongLinks = ownedLinks.filter { (name, function) -> name != function || name !in wanted }.keys.sorted()
        val missingLinks = wanted.filter { ownedLinks[it] != it }

        val prepare = mutableListOf<Op>()
        // An unlinked HID function may be half-written (crash before link), so it is rebuilt.
        val rebuilt = wanted.filter { it != massStorage && it in ownedFunctions && it !in linked }
        rebuilt.forEach { prepare += Op.Rmdir(GadgetPaths.function(it)) }
        val created = wanted.filter { it !in ownedFunctions || it in rebuilt }
        created.forEach { prepare += Op.Mkdir(GadgetPaths.function(it)) }
        if (target.luns.isNotEmpty()) {
            // Still linked after any detach means the LUN set and flags already match (or a foreign
            // link pins it, which only the invariants can report): media swaps only.
            if (massStorage in linked) {
                planMediaSwaps(state, target, prepare)
            } else {
                planLuns(state, target, massStorageKept = massStorage in ownedFunctions, prepare)
            }
        }
        created.forEach { planHidAttributes(it, prepare) }

        val switch =
            if (wrongLinks.isNotEmpty() || missingLinks.isNotEmpty() || forceReenumerate || state.udc.isEmpty()) {
                Switch(
                    unlinks = wrongLinks.map { Op.Unlink(GadgetPaths.configLink(it)) },
                    links = missingLinks.map { Op.Symlink(GadgetPaths.function(it), GadgetPaths.configLink(it)) },
                    bind = bind(udc),
                )
            } else {
                null
            }

        val linkedAfterSwitch = (state.configLinks - wrongLinks.toSet()).values.toSet()
        val cleanup = mutableListOf<Op>()
        for (function in ownedFunctions.filter { it !in wanted && it !in linkedAfterSwitch }.sorted()) {
            if (function == massStorage) {
                state.luns.keys
                    .filter { it > 0 }
                    .sortedDescending()
                    .forEach { cleanup += Op.Rmdir(GadgetPaths.lunDir(it)) }
            }
            cleanup += Op.Rmdir(GadgetPaths.function(function))
        }
        return Stage(prepare, switch, cleanup)
    }

    private fun bind(udc: String): Op.Bind {
        require(udc.isNotEmpty()) { "plan needs to bind but no UDC name is known" }
        return Op.Bind(udc)
    }

    /**
     * Negative space: bound-phase ops never relink, never touch a linked function beyond a media swap
     * (or our LUN marker), and only run while something is linked (so the UDC can be bound). Window ops
     * run only while nothing is linked, and a switch binds exactly when something is linked at its end.
     */
    private fun checkPlan(
        current: GadgetState,
        plan: Plan,
    ) {
        check(plan.ops.flatMap(GadgetPaths::touchedPaths).all(GadgetPaths::isMutableByBootleg)) { "plan touches a foreign path: $plan" }
        var state = current

        fun reconfigure(op: Op) {
            check(op !is Op.Unbind && op !is Op.Bind && op !is Op.Symlink && op !is Op.Unlink) { "relinking outside a switch: $op" }
            val function = GadgetPaths.functionOf(GadgetPaths.touchedPaths(op).single())
            if (function != null && function in state.configLinks.values) {
                check(op is Op.Write && GadgetPaths.lunOf(op.path) != null && op.path.substringAfterLast('/') in IN_PLACE_LUN_ATTRIBUTES) {
                    "plan changes linked function $function beyond a media swap: $op"
                }
            }
            state = GadgetSimulation.apply(state, op)
        }

        fun bound(op: Op) {
            check(state.configLinks.isNotEmpty()) { "bound-phase op with nothing linked, so the UDC can't be bound: $op" }
            reconfigure(op)
        }
        for (stage in plan.stages) {
            stage.prepare.forEach(::bound)
            stage.switch?.let { switch ->
                state = GadgetSimulation.apply(state, listOf(Op.Unbind) + switch.unlinks)
                check(switch.unlinkedOps.isEmpty() || state.configLinks.isEmpty()) { "window ops while something is linked: $switch" }
                check(switch.unlinkedOps.size <= UNLINKED_OP_COUNT_MAX) { "${switch.unlinkedOps.size} ops inside one window" }
                switch.unlinkedOps.forEach(::reconfigure)
                state = GadgetSimulation.apply(state, switch.links)
                check((switch.bind != null) == state.configLinks.isNotEmpty()) { "a switch binds iff something is linked: $switch" }
                switch.bind?.let { state = GadgetSimulation.apply(state, it) }
            }
            stage.cleanup.forEach(::bound)
        }
    }

    private fun planMediaSwaps(
        state: GadgetState,
        target: GadgetTarget,
        ops: MutableList<Op>,
    ) {
        for ((lun, lunTarget) in target.luns.withIndex()) {
            val lunState = state.luns[lun] ?: continue
            // Only a marker of ours, written in place: the kernel lets inquiry_string change on a linked LUN.
            if (needsMarker(lunState, target)) {
                ops +=
                    Op.Write(GadgetPaths.lunAttribute(lun, "inquiry_string"), checkNotNull(target.lunMarker))
            }
            val mode = lunTarget.mode
            if (mode != null && (lunState.ro != mode.readOnly || lunState.cdrom != mode.cdrom)) continue
            if (lunState.file == lunTarget.file) continue
            if (lunState.file.isNotEmpty()) ops += Op.Write(GadgetPaths.lunAttribute(lun, "file"), "")
            if (lunTarget.file.isNotEmpty()) ops += Op.Write(GadgetPaths.lunAttribute(lun, "file"), lunTarget.file)
        }
    }

    private fun planLuns(
        current: GadgetState,
        target: GadgetTarget,
        massStorageKept: Boolean,
        ops: MutableList<Op>,
    ) {
        val lunCount = target.luns.size
        val luns = if (massStorageKept) current.luns.toMutableMap() else mutableMapOf(0 to DEFAULT_LUN)
        luns.keys
            .filter { it > 0 && it >= lunCount }
            .sortedDescending()
            .forEach {
                ops += Op.Rmdir(GadgetPaths.lunDir(it))
                luns.remove(it)
            }
        for (lun in 0 until lunCount) {
            if (lun !in luns) {
                ops += Op.Mkdir(GadgetPaths.lunDir(lun))
                luns[lun] = DEFAULT_LUN
            }
        }
        for ((lun, lunTarget) in target.luns.withIndex()) {
            var state = luns.getValue(lun)

            fun write(
                attribute: String,
                value: String,
            ) {
                ops += Op.Write(GadgetPaths.lunAttribute(lun, attribute), value)
            }

            fun flag(value: Boolean) = if (value) "1" else "0"
            val mode = lunTarget.mode
            if (mode != null && (state.ro != mode.readOnly || state.cdrom != mode.cdrom)) {
                // The kernel only lets ro/cdrom change with no file open. Order keeps cdrom => ro at every step.
                if (state.file.isNotEmpty()) write("file", "")
                if (mode.cdrom) {
                    if (!state.ro) write("ro", "1")
                    if (!state.cdrom) write("cdrom", "1")
                } else {
                    if (state.cdrom) write("cdrom", "0")
                    if (state.ro != mode.readOnly) write("ro", flag(mode.readOnly))
                }
                state = state.copy(file = "", ro = mode.readOnly, cdrom = mode.cdrom)
            }
            // In-place media swaps need removable=1; nofua=0 keeps host FUA writes durable.
            if (!state.removable) write("removable", "1")
            if (state.nofua) write("nofua", "0")
            if (needsMarker(state, target)) write("inquiry_string", checkNotNull(target.lunMarker))
            if (state.file != lunTarget.file) {
                if (state.file.isNotEmpty()) write("file", "")
                if (lunTarget.file.isNotEmpty()) write("file", lunTarget.file)
            }
        }
    }

    /** A LUN without [GadgetTarget.lunMarker]; a kernel without `inquiry_string` (null) can't carry one. */
    private fun needsMarker(
        lun: LunState,
        target: GadgetTarget,
    ): Boolean = target.lunMarker != null && lun.inquiryString != null && lun.inquiryString != target.lunMarker

    private fun planHidAttributes(
        function: String,
        ops: MutableList<Op>,
    ) {
        val (protocol, reportLengthBytes, descriptor) =
            when (function) {
                GadgetNames.HID_KEYBOARD_FUNCTION ->
                    Triple(HidFunctionSpec.KEYBOARD_PROTOCOL, HidFunctionSpec.KEYBOARD_REPORT_LENGTH_BYTES, hidDescriptors.keyboard())
                GadgetNames.HID_MOUSE_FUNCTION ->
                    Triple(HidFunctionSpec.MOUSE_PROTOCOL, HidFunctionSpec.MOUSE_REPORT_LENGTH_BYTES, hidDescriptors.mouse())
                else -> return
            }
        require(descriptor.isNotEmpty()) { "$function: empty HID report descriptor" }
        ops += Op.Write(GadgetPaths.functionAttribute(function, "subclass"), HidFunctionSpec.SUBCLASS_BOOT.toString())
        ops += Op.Write(GadgetPaths.functionAttribute(function, "protocol"), protocol.toString())
        ops += Op.Write(GadgetPaths.functionAttribute(function, "report_length"), reportLengthBytes.toString())
        ops += Op.WriteBytes(GadgetPaths.functionAttribute(function, "report_desc"), descriptor)
    }

    companion object {
        /**
         * Ops one window may run while nothing is linked. Worst case: 7 LUN mkdirs or rmdirs, 7 writes for
         * each of 8 LUNs (file, ro, cdrom, removable, nofua, inquiry_string, file), the function mkdir and 2
         * rebuilt HID functions (rmdir, mkdir, 4 attributes each): 76; or a cleanup of 7 LUNs and 3 functions.
         */
        const val UNLINKED_OP_COUNT_MAX = 80

        /** Kernel defaults for a freshly created LUN. */
        val DEFAULT_LUN = LunState(file = "", ro = false, cdrom = false, removable = true, nofua = false, inquiryString = "")

        /** LUN attributes the plan may write while the function is linked: media swaps and our marker. */
        private val IN_PLACE_LUN_ATTRIBUTES = setOf("file", "inquiry_string")
    }
}

/** Pure model of how an op changes [GadgetState] (mirrors kernel side effects we rely on). */
object GadgetSimulation {
    fun apply(
        state: GadgetState,
        op: Op,
    ): GadgetState =
        when (op) {
            Op.Unbind -> state.copy(udc = "")
            is Op.Bind -> state.copy(udc = op.udc)
            is Op.Mkdir -> {
                val lun = GadgetPaths.lunOf(op.path)
                val function = functionName(op.path)
                when {
                    lun != null -> state.copy(luns = (state.luns + (lun to GadgetPlanner.DEFAULT_LUN)).toSortedMap())
                    function == GadgetNames.MASS_STORAGE_FUNCTION ->
                        state.copy(functions = state.functions + function, luns = sortedMapOf(0 to GadgetPlanner.DEFAULT_LUN))
                    function != null -> state.copy(functions = state.functions + function)
                    else -> state
                }
            }
            is Op.Rmdir -> {
                val lun = GadgetPaths.lunOf(op.path)
                val function = functionName(op.path)
                when {
                    lun != null -> state.copy(luns = state.luns - lun)
                    function == GadgetNames.MASS_STORAGE_FUNCTION -> state.copy(functions = state.functions - function, luns = emptyMap())
                    function != null -> state.copy(functions = state.functions - function)
                    else -> state
                }
            }
            is Op.Write -> {
                val lun = GadgetPaths.lunOf(op.path)
                val lunState = lun?.let { state.luns[it] }
                when {
                    op.path == GadgetPaths.UDC -> state.copy(udc = op.value)
                    lun == null || lunState == null -> state
                    else -> state.copy(luns = state.luns + (lun to applyLunWrite(lunState, op.path.substringAfterLast('/'), op.value)))
                }
            }
            is Op.WriteBytes -> state
            is Op.Symlink ->
                state.copy(
                    configLinks =
                        state.configLinks + (op.link.substringAfterLast('/') to op.target.substringAfterLast('/')),
                )
            // Unlinking from a bound gadget makes the kernel unbind it.
            is Op.Unlink -> state.copy(configLinks = state.configLinks - op.link.substringAfterLast('/'), udc = "")
        }

    fun apply(
        state: GadgetState,
        ops: List<Op>,
    ): GadgetState = ops.fold(state, ::apply)

    private fun functionName(path: String): String? {
        val prefix = GadgetPaths.FUNCTIONS_DIR + "/"
        return if (path.startsWith(prefix) && '/' !in path.removePrefix(prefix)) path.removePrefix(prefix) else null
    }

    private fun applyLunWrite(
        lun: LunState,
        attribute: String,
        value: String,
    ): LunState {
        val flag = value.trim() == "1"
        return when (attribute) {
            "file" -> lun.copy(file = value.trimEnd('\n'))
            "forced_eject" -> lun.copy(file = "")
            "ro" -> lun.copy(ro = flag)
            // Writing cdrom=1 also sets ro in the kernel.
            "cdrom" -> lun.copy(cdrom = flag, ro = lun.ro || flag)
            "removable" -> lun.copy(removable = flag)
            "nofua" -> lun.copy(nofua = flag)
            "inquiry_string" -> lun.copy(inquiryString = value.trimEnd('\n'))
            else -> lun
        }
    }
}
