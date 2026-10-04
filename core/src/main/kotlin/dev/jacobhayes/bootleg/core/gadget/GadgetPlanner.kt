package dev.jacobhayes.bootleg.core.gadget

/**
 * The only ops that run while the UDC is unbound: [Op.Unbind], then [unlinks], [unlinkedOps],
 * [links] and [afterLinks], then [bind]. The Pixel USB HAL rebinds the UDC a few hundred ms after any
 * unbind, so this window must stay tiny, unless the config can't bind meanwhile.
 *
 * [afterLinks] ([Op.StartRndis], [Op.RelinkForeign], then [Op.ReleaseRndis]) run while a held or dead
 * RNDIS function is linked: nothing can bind such a config (ENODEV), so the window may take seconds
 * without the HAL rebinding into it.
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
    val afterLinks: List<Op> = emptyList(),
) {
    val ops: List<Op> get() = listOf(Op.Unbind) + unlinks + unlinkedOps + links + afterLinks + listOfNotNull(bind)
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

    /** Buttons, X, Y, then Wheel and AC Pan appended to the boot report (HID 1.11 B.2). */
    const val MOUSE_REPORT_LENGTH_BYTES = 5
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

    /**
     * [repairRndis]: a linked RNDIS function whose daemon died is restarted (one re-plug) even when
     * nothing else needs a switch; the controller turns it off while disks have media. A plan that
     * switches anyway always repairs first, since a dead function makes every bind fail.
     */
    fun plan(
        current: GadgetState,
        target: GadgetTarget,
        udc: String = current.udc,
        forceReenumerate: Boolean = false,
        repairRndis: Boolean = true,
    ): Plan {
        val roundTrip = Op.RelinkForeign.takeIf { target.usbRoundTrip }
        val plan =
            when {
                !target.prefix -> ordinaryPlan(current, target, udc, forceReenumerate)
                !LinkOrder.prefixFirst(current.configLinks) -> Plan(listOf(establishment(current, target, udc)))
                // A root process died mid-establishment with the links in place: finish it.
                current.rndis == RndisState.HELD -> switchOnly(udc, listOfNotNull(roundTrip, Op.ReleaseRndis))
                current.rndis != RndisState.READY -> {
                    // Dead: restarted (a pending round trip finished first) when allowed, or when anything switches anyway.
                    val ordinary = ordinaryPlan(current, target, udc, forceReenumerate)
                    if (repairRndis ||
                        ordinary.reenumerates
                    ) {
                        switchOnly(udc, listOfNotNull(Op.StartRndis, roundTrip, Op.ReleaseRndis))
                    } else {
                        ordinary
                    }
                }
                else -> ordinaryPlan(current, target, udc, forceReenumerate)
            }
        checkPlan(current, target, plan)
        return plan
    }

    /** A switch that only runs [afterLinks] (which keep the config unbindable until the last), then binds. */
    private fun switchOnly(
        udc: String,
        afterLinks: List<Op>,
    ) = Plan(listOf(Stage(emptyList(), Switch(emptyList(), emptyList(), bind(udc), afterLinks = afterLinks), emptyList())))

    /**
     * Links the prefix ahead of every foreign link. Configfs only appends, so: unlink ours, link the
     * prefix with the RNDIS function held (the config can't bind), have the HAL relink its functions
     * (they land after ours), release the daemon, bind. Only a fresh daemon starts held: a serving or
     * dead one is replaced by [Op.StartRndis], while bound if its function is unlinked, else right after
     * the relink (the old one's function is bindable only for the relink itself, like any switch). Mass
     * storage comes back in the next plan, after the HAL's links.
     */
    private fun establishment(
        state: GadgetState,
        target: GadgetTarget,
        udc: String,
    ): Stage {
        val rndis = GadgetNames.RNDIS_FUNCTION
        val prepare = mutableListOf<Op>()
        val restartLinked = state.rndis != RndisState.HELD && rndis in state.configLinks.values
        if (rndis !in state.functions) prepare += Op.Mkdir(GadgetPaths.function(rndis))
        if (state.rndis != RndisState.HELD && !restartLinked) prepare += Op.StartRndis
        for (function in LinkOrder.PREFIX - rndis) {
            // An unlinked HID function may be half-written (crash before link): rebuilt.
            if (function in state.configLinks.values) continue
            if (function in state.functions) prepare += Op.Rmdir(GadgetPaths.function(function))
            prepare += Op.Mkdir(GadgetPaths.function(function))
            planHidAttributes(function, prepare)
        }
        val unlinks =
            state.configLinks.keys
                .filter(GadgetPaths::isOwnedName)
                .map { Op.Unlink(GadgetPaths.configLink(it)) }
        val links = LinkOrder.PREFIX.map { Op.Symlink(GadgetPaths.function(it), GadgetPaths.configLink(it)) }
        val foreignLinked = state.configLinks.keys.any { !GadgetPaths.isOwnedName(it) }
        val afterLinks =
            listOfNotNull(
                Op.StartRndis.takeIf { restartLinked },
                Op.RelinkForeign.takeIf { foreignLinked || target.usbRoundTrip },
                Op.ReleaseRndis,
            )
        // Mass storage is linked again by the next plan; anything else of ours that isn't wanted goes.
        val unwanted = state.functions.filter { GadgetPaths.isOwnedName(it) && it !in target.functions }
        val cleanup = unwanted.sorted().flatMap { removal(state, it) }
        return withinWindowIfUnbindable(state, Stage(prepare, Switch(unlinks, links, bind(udc), afterLinks = afterLinks), cleanup))
    }

    /** Ops that remove an unlinked owned [function]: its LUNs, its daemon, then the function. */
    private fun removal(
        state: GadgetState,
        function: String,
    ): List<Op> =
        buildList {
            if (function == GadgetNames.MASS_STORAGE_FUNCTION) {
                state.luns.keys
                    .filter { it > 0 }
                    .sortedDescending()
                    .forEach { add(Op.Rmdir(GadgetPaths.lunDir(it))) }
            }
            if (function == GadgetNames.RNDIS_FUNCTION) add(Op.StopRndis)
            add(Op.Rmdir(GadgetPaths.function(function)))
        }

    private fun ordinaryPlan(
        current: GadgetState,
        target: GadgetTarget,
        udc: String,
        forceReenumerate: Boolean,
    ): Plan {
        val detach = detachStage(current, target, udc)
        val afterDetach = detach?.let { GadgetSimulation.apply(current, it.ops) } ?: current
        val detached =
            detach
                ?.switch
                ?.unlinks
                .orEmpty()
                .mapNotNull { current.configLinks[it.link.substringAfterLast('/')] }
                .toSet()
        val main = mainStage(afterDetach, target, udc, forceReenumerate && detach == null, detached)
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
        return Plan(stages.filter { it.ops.isNotEmpty() })
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
        // Our links after mass storage go too: bound without it, they would take its interface numbers
        // for a moment, and Windows binds drivers by number. They come back in order with it.
        val ownedNames = current.configLinks.keys.filter(GadgetPaths::isOwnedName)
        val firstDetached = ownedNames.indexOfFirst { it in linkNames }
        val detachNames = ownedNames.drop(firstDetached)
        // With nothing else linked the UDC can't bind; it stays unbound until the relink.
        val bindable = (current.configLinks.keys - detachNames.toSet()).isNotEmpty()
        val unlinks = detachNames.map { Op.Unlink(GadgetPaths.configLink(it)) }
        return Stage(prepare = emptyList(), switch = Switch(unlinks, emptyList(), if (bindable) bind(udc) else null), cleanup = emptyList())
    }

    private fun mainStage(
        state: GadgetState,
        target: GadgetTarget,
        udc: String,
        forceReenumerate: Boolean,
        detached: Set<String> = emptySet(),
    ): Stage {
        val massStorage = GadgetNames.MASS_STORAGE_FUNCTION
        val wanted = target.functions
        val ownedFunctions = state.functions.filter(GadgetPaths::isOwnedName).toSet()
        val ownedLinks = state.configLinks.filterKeys(GadgetPaths::isOwnedName)
        val linked = state.configLinks.values.toSet()
        val misplaced = ownedLinks.filter { (name, function) -> name != function || name !in wanted }.keys
        // Links that stay must already be the start of the wanted (canonical) order; the first one that
        // isn't, and every link of ours after it, is relinked in order (see [LinkOrder]).
        val kept = ownedLinks.keys.filter { it !in misplaced }
        val keptPrefix = kept.zip(wanted).takeWhile { (have, want) -> have == want }.size
        val wrongLinks = (misplaced + kept.drop(keptPrefix)).sorted()
        val missingLinks = wanted.drop(keptPrefix)

        val prepare = mutableListOf<Op>()
        // An unlinked HID function may be half-written (crash before link), so it is rebuilt; not one
        // this plan's detach just unlinked.
        val rebuilt = wanted.filter { it != massStorage && it in ownedFunctions && it !in linked && it !in detached }
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
        val cleanup = ownedFunctions.filter { it !in wanted && it !in linkedAfterSwitch }.sorted().flatMap { removal(state, it) }
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
        target: GadgetTarget,
        plan: Plan,
    ) {
        check(plan.ops.flatMap(GadgetPaths::touchedPaths).all(GadgetPaths::isMutableByBootleg)) { "plan touches a foreign path: $plan" }
        var state = current

        fun reconfigure(op: Op) {
            check(op !is Op.Unbind && op !is Op.Bind && op !is Op.Symlink && op !is Op.Unlink) { "relinking outside a switch: $op" }
            check(op !is Op.RelinkForeign && op !is Op.ReleaseRndis) { "$op outside a switch's unbindable window" }
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

        // A slow step in a window: only while a linked RNDIS function keeps the config unbindable.
        fun unbindable(op: Op) {
            check(GadgetNames.RNDIS_FUNCTION in state.configLinks.values) { "$op without the RNDIS function linked" }
            // A start replaces the daemon, making the function unbindable; everything after it needs that.
            check(op == Op.StartRndis || state.rndis != RndisState.READY) { "$op while the config could bind" }
            state = GadgetSimulation.apply(state, op)
        }
        for (stage in plan.stages) {
            stage.prepare.forEach(::bound)
            stage.switch?.let { switch ->
                state = GadgetSimulation.apply(state, listOf(Op.Unbind) + switch.unlinks)
                check(switch.unlinkedOps.isEmpty() || state.configLinks.isEmpty()) { "window ops while something is linked: $switch" }
                check(switch.unlinkedOps.size <= UNLINKED_OP_COUNT_MAX) { "${switch.unlinkedOps.size} ops inside one window" }
                switch.unlinkedOps.forEach(::reconfigure)
                state = GadgetSimulation.apply(state, switch.links)
                check(
                    switch.afterLinks.isEmpty() || switch.afterLinks.last() is Op.ReleaseRndis,
                ) { "a release must end the window: $switch" }
                switch.afterLinks.forEach(::unbindable)
                check((switch.bind != null) == state.configLinks.isNotEmpty()) { "a switch binds iff something is linked: $switch" }
                // Every configuration the host can see keeps each function on its interface number.
                check(LinkOrder.isCanonical(state.configLinks)) { "links out of order at a bind: ${LinkOrder.owned(state.configLinks)}" }
                if (switch.bind != null) {
                    val ours = LinkOrder.owned(state.configLinks)
                    check(
                        !target.prefix || ours.isEmpty() || LinkOrder.prefixFirst(state.configLinks),
                    ) { "the prefix isn't first at a bind: $state" }
                    check(
                        GadgetNames.RNDIS_FUNCTION !in ours || state.rndis == RndisState.READY,
                    ) { "binding with the RNDIS function ${state.rndis}" }
                }
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
                    function == GadgetNames.RNDIS_FUNCTION -> state.copy(functions = state.functions - function, rndis = RndisState.ABSENT)
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
            Op.StartRndis -> state.copy(rndis = RndisState.HELD)
            Op.ReleaseRndis -> state.copy(rndis = RndisState.READY)
            Op.StopRndis -> state.copy(rndis = RndisState.ABSENT)
            // The HAL unlinks its functions and links them again after ours.
            Op.RelinkForeign ->
                state.copy(
                    configLinks =
                        state.configLinks.filterKeys(GadgetPaths::isOwnedName) +
                            state.configLinks.filterKeys { !GadgetPaths.isOwnedName(it) },
                )
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
