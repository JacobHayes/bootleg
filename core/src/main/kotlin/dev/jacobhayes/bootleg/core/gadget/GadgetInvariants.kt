package dev.jacobhayes.bootleg.core.gadget

/** Positive and negative space checks for desired and live gadget state. */
object GadgetInvariants {
    val ALLOWED_ROOTS_DEFAULT: List<String> = listOf(GadgetPaths.ALLOWED_ROOT_DEFAULT)
    const val ADB_FUNCTION = "ffs.adb"

    /** Rejects a [Desired] Bootleg must never try to apply. */
    fun requireValid(
        desired: Desired,
        allowedRoots: List<String> = ALLOWED_ROOTS_DEFAULT,
    ) {
        requireAllowedRoots(allowedRoots)
        val slots = desired.slots
        require(slots.size <= GadgetLimits.LUN_COUNT_MAX) { "at most ${GadgetLimits.LUN_COUNT_MAX} slots, got ${slots.size}" }
        require(
            slots.map {
                it.lun
            } == slots.indices.toList(),
        ) { "slot LUNs must be dense and ordered 0 until ${slots.size}: ${slots.map { it.lun }}" }
        for (slot in slots) {
            pathProblem(slot.backingPath, allowedRoots)?.let { throw IllegalArgumentException("lun ${slot.lun}: $it") }
        }
        requireNoDuplicateWritable(slots) { it.backingPath }
    }

    /**
     * Rejects [slots] that export one file more than once when any copy is writable. [identity] says
     * which slots are the same file: the path here; the root process, which can `fstat`, also checks
     * `(st_dev, st_ino)` so two hard links to one inode count as the same file.
     */
    fun <K : Any> requireNoDuplicateWritable(
        slots: List<SlotSpec>,
        identity: (SlotSpec) -> K,
    ) {
        val duplicates =
            slots
                .groupBy(identity)
                .filterValues { group -> group.size > 1 && group.any { it.mode == LunMode.DISK_RW } }
                .values
                .map { group -> group.map { it.lun } }
        require(duplicates.isEmpty()) { "a writable file may only be exported once (LUNs ${duplicates.joinToString()} are the same file)" }
    }

    /**
     * Violations of the post-apply invariants, empty when all hold. [foreignBefore] (if given) is a
     * state read before Bootleg acted; see [foreignViolations].
     */
    fun violations(
        state: GadgetState,
        target: GadgetTarget,
        allowedRoots: List<String> = ALLOWED_ROOTS_DEFAULT,
        foreignBefore: GadgetState? = null,
    ): List<String> =
        buildList {
            // The kernel can't bind a config without functions; with nothing linked, unbound is the only state.
            if (state.udc.isEmpty() && state.configLinks.isNotEmpty()) add("UDC is not bound")
            val wanted = target.functions
            for (function in wanted) {
                if (function !in state.functions) add("function $function missing")
                if (state.configLinks[function] != function) add("link $function missing or wrong: ${state.configLinks[function]}")
            }
            state.functions.filter { GadgetPaths.isOwnedName(it) && it !in wanted }.forEach { add("unwanted function $it") }
            state.configLinks.keys
                .filter { GadgetPaths.isOwnedName(it) && it !in wanted }
                .forEach { add("unwanted link $it") }
            addAll(lunViolations(state, allowedRoots))
            if (target.luns.isNotEmpty() &&
                state.luns.size != target.luns.size
            ) {
                add("expected ${target.luns.size} LUNs, found ${state.luns.size}")
            }
            for ((lun, lunTarget) in target.luns.withIndex()) {
                val lunState = state.luns[lun] ?: continue
                if (lunState.file != lunTarget.file) add("lun $lun: file '${lunState.file}' != '${lunTarget.file}'")
                // In-place media swaps need removable=1, and nofua=0 keeps the host's FUA writes durable.
                if (!lunState.removable) add("lun $lun: removable=0")
                if (lunState.nofua) add("lun $lun: nofua=1")
                if (target.lunMarker != null && lunState.inquiryString != null && lunState.inquiryString != target.lunMarker) {
                    add("lun $lun: inquiry_string '${lunState.inquiryString}' is not our marker")
                }
                val mode = lunTarget.mode ?: continue
                if (lunState.ro != mode.readOnly ||
                    lunState.cdrom != mode.cdrom
                ) {
                    add("lun $lun: ro/cdrom ${lunState.ro}/${lunState.cdrom} != $mode")
                }
            }
            if (foreignBefore != null) addAll(foreignViolations(state, foreignBefore))
        }

    /**
     * Foreign functions and links present in [before] but gone from [state]. A link is identified by
     * the function it targets: the HAL renumbers `functionN` links on every switch, so `ffs.adb`
     * moving from function0 to function1 is fine, but `ffs.adb` no longer linked is not. Additions
     * (e.g. the HAL adding MTP) are allowed, and so is ADB being absent before (USB debugging off).
     */
    fun foreignViolations(
        state: GadgetState,
        before: GadgetState,
    ): List<String> =
        buildList {
            val lostFunctions = foreignFunctions(before) - foreignFunctions(state)
            if (lostFunctions.isNotEmpty()) add("foreign functions removed: ${lostFunctions.sorted()}")
            val linkedAfter = foreignLinks(state).values.toSet()
            for ((link, function) in foreignLinks(before).toSortedMap()) {
                if (function in linkedAfter) continue
                val what = if (function == ADB_FUNCTION) "ADB ($function)" else "foreign function $function"
                add("$what is no longer linked (was $link)")
            }
        }

    /** LUN checks that hold regardless of what is desired. */
    fun lunViolations(
        state: GadgetState,
        allowedRoots: List<String> = ALLOWED_ROOTS_DEFAULT,
    ): List<String> =
        buildList {
            if (state.luns.size > GadgetLimits.LUN_COUNT_MAX) add("${state.luns.size} LUNs exceeds ${GadgetLimits.LUN_COUNT_MAX}")
            if (state.luns.keys != (0 until state.luns.size).toSet()) add("LUN indexes not dense: ${state.luns.keys}")
            for ((lun, lunState) in state.luns) {
                if (lunState.cdrom && !lunState.ro) add("lun $lun: cdrom without ro")
                if (lunState.file.isNotEmpty()) pathProblem(lunState.file, allowedRoots)?.let { add("lun $lun: $it") }
            }
            state.luns.values
                .filter { it.file.isNotEmpty() }
                .groupBy { it.file }
                .filterValues { group -> group.size > 1 && group.any { !it.ro } }
                .keys
                .forEach { add("writable file exported more than once: $it") }
        }

    fun foreignFunctions(state: GadgetState): Set<String> = state.functions.filterNot(GadgetPaths::isOwnedName).toSet()

    fun foreignLinks(state: GadgetState): Map<String, String> = state.configLinks.filterKeys { !GadgetPaths.isOwnedName(it) }

    /** Why [path] can't be exported, or null if it can: must be absolute, canonical and under an allowed root. */
    fun pathProblem(
        path: String,
        allowedRoots: List<String>,
    ): String? =
        when {
            path.isEmpty() -> "empty path"
            !path.startsWith("/") -> "path is not absolute: $path"
            path.endsWith("/") -> "path is a directory: $path"
            path.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." } -> "path is not canonical: $path"
            '\n' in path || '\u0000' in path -> "path has control characters"
            allowedRoots.none { path.startsWith(it) } -> "path is outside the allowed roots $allowedRoots: $path"
            else -> null
        }

    fun requireAllowedRoots(allowedRoots: List<String>) {
        require(allowedRoots.isNotEmpty()) { "no allowed roots" }
        for (root in allowedRoots) {
            require(
                root.startsWith("/") && root.endsWith("/") && root.length > 1,
            ) { "allowed root must be an absolute directory ending in '/': $root" }
            require(
                root
                    .split('/')
                    .drop(1)
                    .dropLast(1)
                    .none { it.isEmpty() || it == "." || it == ".." },
            ) { "allowed root is not canonical: $root" }
        }
    }
}
