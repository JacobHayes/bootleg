package dev.jacobhayes.bootleg.core.gadget

import java.io.IOException

/**
 * Keeps `/dev/hidgN` descriptors out of the gadget changes that make closing them panic the kernel.
 *
 * f_hid re-initializes a function's cdev on every bind (`hidg_bind` → `cdev_init`), which empties the
 * cdev's inode list. An fd opened under one bind and still open when the same linked function is bound
 * again keeps its inode on the wiped list, and closing it later (process exit included) makes
 * `cd_forget` hit `list_del corruption`: a kernel panic. Kernels without the f_hidg lifetime fix also
 * free the function under an open fd on unlink. So [DefaultGadgetController] calls [closeAll] before
 * each of its UDC writes, and checks [holders] before a plan that switches or removes a hid function
 * and before each hid rmdir.
 */
interface HidNodeGuard {
    /**
     * Writes release-all to every hidg fd Bootleg holds, closes it, and refuses new opens until the
     * current controller call ends (the caller lifts that). Idempotent and cheap once nothing is open,
     * so it may run inside a switch window.
     */
    fun closeAll()

    /**
     * Every process holding a node of [functions] open, as readable descriptions; empty when none. A
     * bounded scan that reports an incomplete scan as a holder, so callers fail closed. Too slow for a
     * switch window.
     */
    fun holders(functions: Collection<String>): List<String>
}

/** A hid function's node is open, so removing or rebinding it is refused. */
class HidNodeInUseException(
    message: String,
) : IOException(message)

/** Bootleg's own HID functions, the ones whose nodes [HidNodeGuard] watches. */
object HidFunctions {
    private const val HID_PREFIX = "hid."

    fun isOwnedHid(function: String): Boolean = isHid(function) && GadgetPaths.isOwnedName(function)

    fun isHid(function: String): Boolean = function.startsWith(HID_PREFIX)

    fun owned(state: GadgetState): Set<String> = state.functions.filter(::isOwnedHid).toSet()

    /** Linked hid functions, other apps' included. */
    fun linked(state: GadgetState): Set<String> =
        state.configLinks.values
            .filter(::isHid)
            .toSet()

    /** The hid function [op] removes (rmdir of `functions/hid.*bootleg*` itself), or null. */
    fun removedBy(op: Op): String? {
        if (op !is Op.Rmdir) return null
        val function = GadgetPaths.functionOf(op.path) ?: return null
        return function.takeIf { isOwnedHid(it) && op.path == GadgetPaths.function(it) }
    }
}
