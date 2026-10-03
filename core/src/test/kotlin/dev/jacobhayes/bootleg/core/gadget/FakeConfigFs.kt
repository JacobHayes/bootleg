package dev.jacobhayes.bootleg.core.gadget

import java.io.IOException

class ConfigFsException(
    val errno: String,
    detail: String,
) : IOException("$errno: $detail")

/** A gadget change that would panic the real kernel once a hidg fd is closed. An Error, so nothing catches it. */
class HidFdHazard(
    message: String,
) : Error(message)

/**
 * In-memory configfs gadget that models the kernel behaviour Bootleg relies on: UDC bind/unbind
 * rules, mass_storage LUN attribute rules, configfs rmdir/link rules and "unlink unbinds".
 * Paths are relative to the gadget root. Mutations by [EXECUTOR] outside Bootleg's names are recorded
 * in [foreignWrites] so tests can assert Bootleg never touches them.
 */
class FakeConfigFs(
    val udcName: String = PIXEL_UDC,
    val hostFiles: MutableSet<String> = mutableSetOf(),
) : ConfigFs {
    private sealed interface Node

    private class Dir(
        val defaultGroup: Boolean,
    ) : Node

    private class Attr(
        var bytes: ByteArray,
        val readable: Boolean = true,
    ) : Node

    private class Link(
        val target: String,
    ) : Node

    private val nodes = sortedMapOf<String, Node>()

    /** LUNs whose host has sent PREVENT MEDIUM REMOVAL. */
    val preventRemoval = mutableSetOf<Int>()
    val foreignWrites = mutableListOf<String>()

    /** "actor link" for every symlink rejected because the UDC was bound. */
    val boundSymlinkRejections = mutableListOf<String>()

    /** Makes binding fail for some link sets (link name -> target), like a function whose bind() errors. */
    var rejectBind: (Map<String, String>) -> Boolean = { false }
    var actor: String = EXECUTOR
        private set

    /** Called after every UDC bind (true) or unbind (false), with the actor; the HAL model hooks in here. */
    var onUdcChange: (bound: Boolean, actor: String) -> Unit = { _, _ -> }

    /** Time source for [executorWindowsUs]; the rig wires it to its FakeClock. */
    var nowUs: () -> Long = { 0L }

    /**
     * When the current call started, if a [ScriptedConfigFs] charges it time first. A window opens when
     * the unbind write starts (the host loses the device then), not when it returns.
     */
    var callStartUs: Long? = null

    /** Durations of unbound windows the executor opened (unbind start) and closed (bind return) itself. */
    val executorWindowsUs = mutableListOf<Long>()

    /** Runs before every mutation (with its actor and path), at the moment it reaches the kernel model. */
    var beforeMutation: (actor: String, path: String) -> Unit = { _, _ -> }

    /** Since when the UDC has been bound (by anyone), or null while unbound. */
    var boundSinceUs: Long? = null
        private set

    /** Actors that bound the UDC while an executor-opened window was still open. */
    val bindsInExecutorWindow = mutableListOf<String>()
    private var executorWindowStartUs: Long? = null

    /** Open `/dev/hidgN` fds: function -> holders, one entry per fd. */
    private val hidFds = mutableMapOf<String, MutableList<String>>()

    /**
     * Every gadget change that would have left a hidg fd to panic the kernel when closed (see
     * [HidNodeGuard]). Each one also throws [HidFdHazard] at the moment it happens.
     */
    val hidFdHazards = mutableListOf<String>()

    /**
     * Fds someone else (HAL, dead-man) rebound under: still open, but closing one panics the kernel
     * (process exit included). function -> holders.
     */
    private val poisonedHidFds = mutableMapOf<String, MutableList<String>>()

    fun isPoisoned(
        function: String,
        holder: String,
    ): Boolean = holder in poisonedHidFds[function].orEmpty()

    init {
        nodes[""] = Dir(defaultGroup = true)
        attr("UDC", "")
        dir(GadgetPaths.FUNCTIONS_DIR, defaultGroup = true)
        dir("configs", defaultGroup = true)
        dir(GadgetNames.CONFIG_DIR, defaultGroup = false)
    }

    fun <T> asActor(
        name: String,
        block: () -> T,
    ): T {
        val previous = actor
        actor = name
        try {
            return block()
        } finally {
            actor = previous
        }
    }

    val udc: String get() = text("UDC")

    /** [holder] opens [function]'s `/dev/hidgN`; the node exists only while the function is linked and bound. */
    fun openHidFd(
        function: String,
        holder: String,
    ) {
        require(function.startsWith("hid.")) { "$function has no hidg node" }
        if (udc.isEmpty() || !isLinked(function)) throw ConfigFsException("ENOENT", "/dev/hidg of $function: not bound")
        hidFds.getOrPut(function) { mutableListOf() } += holder
    }

    /** Closes one of [holder]'s fds on [function]; false if it had none. */
    fun closeHidFd(
        function: String,
        holder: String,
    ): Boolean {
        val holders = hidFds[function] ?: return false
        if (isPoisoned(function, holder)) hidFdHazard("close of $holder's fd on $function after a rebind")
        val closed = holders.remove(holder)
        if (holders.isEmpty()) hidFds.remove(function)
        return closed
    }

    fun hidFdHolders(function: String): List<String> = hidFds[function].orEmpty().toList()

    /** The process [holder] exits: the kernel closes its fds. */
    fun exitHidHolder(holder: String) {
        if (poisonedHidFds.values.any { holder in it }) hidFdHazard("exit of $holder, which holds an fd that crossed a rebind")
        hidFds.values.forEach { holders -> holders.removeAll { it == holder } }
        hidFds.values.removeAll { it.isEmpty() }
    }

    private fun hidFdHazard(what: String) {
        val hazard = "$actor $what while hidg fds are open: $hidFds"
        hidFdHazards += hazard
        throw HidFdHazard(hazard)
    }

    /** The host ejects the medium (START STOP UNIT), as macOS does on "Eject". */
    fun hostEject(lun: Int) = asActor(HOST) { setText(lunPath(lun, "file"), "") }

    override fun exists(path: String): Boolean = path in nodes

    override fun list(path: String): List<String> {
        if (nodes[path] !is Dir) throw ConfigFsException("ENOTDIR", path)
        return children(path).map { it.substringAfterLast('/') }
    }

    override fun read(path: String): String {
        val node = nodes[path] ?: throw ConfigFsException("ENOENT", path)
        if (node !is Attr) throw ConfigFsException("EISDIR", path)
        if (!node.readable) throw ConfigFsException("EPERM", "$path is write-only")
        return String(node.bytes) + "\n"
    }

    override fun readLink(path: String): String? {
        val node = nodes[path] ?: throw ConfigFsException("ENOENT", path)
        return (node as? Link)?.let { "../../../../usb_gadget/g1/${it.target}" }
    }

    override fun write(
        path: String,
        value: String,
    ) = writeBytes(path, value.toByteArray())

    override fun writeBytes(
        path: String,
        bytes: ByteArray,
    ) {
        recordMutation(path)
        val node = nodes[path] ?: throw ConfigFsException("ENOENT", path)
        if (node !is Attr) throw ConfigFsException("EISDIR", path)
        val value = String(bytes).trimEnd('\n')
        val lun = lunOf(path)
        val function = functionOf(path)
        when {
            path == "UDC" -> writeUdc(value.trim())
            lun != null -> writeLun(path, lun, path.substringAfterLast('/'), value)
            function != null && function.startsWith("hid.") -> {
                if (isLinked(function)) throw ConfigFsException("EBUSY", "$path: function is linked")
                node.bytes = bytes
            }
            else -> node.bytes = bytes
        }
    }

    override fun mkdir(path: String) {
        recordMutation(path)
        if (path in nodes) throw ConfigFsException("EEXIST", path)
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        if (nodes[parent] !is Dir) throw ConfigFsException("ENOENT", path)
        when {
            parent == GadgetPaths.FUNCTIONS_DIR -> createFunction(name)
            parent.startsWith("${GadgetPaths.FUNCTIONS_DIR}/mass_storage.") && parent.count { it == '/' } == 1 -> {
                val index = GadgetPaths.lunIndex(name) ?: throw ConfigFsException("EINVAL", path)
                if (index == 0 || index >= KERNEL_LUN_COUNT_MAX) throw ConfigFsException("EINVAL", path)
                if (isLinked(parent.substringAfter('/'))) throw ConfigFsException("EBUSY", "$path: function is linked")
                createLun(path, defaultGroup = false)
            }
            else -> throw ConfigFsException("EPERM", path)
        }
    }

    override fun rmdir(path: String) {
        recordMutation(path)
        val node = nodes[path] ?: throw ConfigFsException("ENOENT", path)
        if (node !is Dir) throw ConfigFsException("ENOTDIR", path)
        if (node.defaultGroup) throw ConfigFsException("EPERM", "$path is a default group")
        if (subtree(path).any { it != path && (nodes[it] as? Dir)?.defaultGroup == false }) throw ConfigFsException("ENOTEMPTY", path)
        if (nodes.values.any { it is Link && it.target == path }) throw ConfigFsException("EBUSY", "$path is linked")
        val lunFunction = lunOf(path)?.let { functionOf(path) }
        if (lunFunction != null && isLinked(lunFunction)) throw ConfigFsException("EBUSY", "$path: function is linked")
        functionName(path)?.let { function -> if (hidFds.containsKey(function)) hidFdHazard("rmdir $path") }
        subtree(path).forEach { nodes.remove(it) }
    }

    override fun symlink(
        target: String,
        link: String,
    ) {
        recordMutation(link)
        val config = link.substringBeforeLast('/')
        if (!config.startsWith("configs/") || nodes[config] !is Dir) throw ConfigFsException("EPERM", link)
        if (link in nodes) throw ConfigFsException("EEXIST", link)
        if (target.substringBeforeLast('/') != GadgetPaths.FUNCTIONS_DIR || nodes[target] !is Dir) throw ConfigFsException("ENOENT", target)
        if (children(config).any { (nodes[it] as? Link)?.target == target }) throw ConfigFsException("EEXIST", "$target already linked")
        // The kernel refuses to add a function to a bound gadget's config (EINVAL on the Pixel 6).
        if (udc.isNotEmpty()) {
            boundSymlinkRejections += "$actor $link"
            throw ConfigFsException("EINVAL", "$link: gadget is bound")
        }
        nodes[link] = Link(target)
    }

    override fun unlink(link: String) {
        recordMutation(link)
        val target = nodes[link] as? Link ?: throw ConfigFsException("ENOENT", link)
        // Kernels without the f_hidg lifetime fix free the function under its open fds here.
        if (hidFds.containsKey(target.target.substringAfterLast('/'))) hidFdHazard("unlink $link")
        nodes.remove(link)
        // The kernel force-unbinds a bound gadget when a function is unlinked.
        if (udc.isNotEmpty()) unbind()
    }

    // --- kernel semantics ---

    private fun writeUdc(value: String) {
        if (value.isEmpty()) {
            if (udc.isEmpty()) throw ConfigFsException("ENODEV", "UDC not bound")
            unbind()
            return
        }
        if (udc.isNotEmpty()) throw ConfigFsException("EBUSY", "UDC already bound to $udc")
        if (value != udcName) throw ConfigFsException("ENODEV", "no UDC $value")
        val linked = links()
        if (linked.isEmpty()) throw ConfigFsException("EINVAL", "config has no functions")
        if (rejectBind(linked)) throw ConfigFsException("EINVAL", "a function failed to bind")
        for (function in linked.values.map { it.substringAfterLast('/') }.filter { it.startsWith("hid.") }) {
            val reportLength = text("${GadgetPaths.function(function)}/report_length").toIntOrNull() ?: 0
            val descriptor = (nodes["${GadgetPaths.function(function)}/report_desc"] as Attr).bytes
            if (reportLength <= 0 || descriptor.isEmpty()) throw ConfigFsException("EINVAL", "$function bind failed: no report descriptor")
        }
        // hidg_bind re-initializes the cdev that an fd opened under an earlier bind still points into.
        val linkedHid = linked.values.map { it.substringAfterLast('/') }.filter { it.startsWith("hid.") }
        val open = linkedHid.filter(hidFds::containsKey)
        if (open.isNotEmpty()) {
            // Bootleg must never do this; someone else can, and then those fds may never be closed.
            if (actor == EXECUTOR) hidFdHazard("bind")
            open.forEach { function -> poisonedHidFds.getOrPut(function) { mutableListOf() } += hidFds.getValue(function) }
        }
        setText("UDC", value)
        executorWindowStartUs?.let { startUs ->
            if (actor == EXECUTOR) executorWindowsUs += nowUs() - startUs else bindsInExecutorWindow += actor
        }
        executorWindowStartUs = null
        boundSinceUs = nowUs()
        onUdcChange(true, actor)
    }

    private fun unbind() {
        // Bootleg releases and closes its fds while the host can still see the release; nobody else may hold one.
        if (actor == EXECUTOR && hidFds.keys.any(::isLinked)) hidFdHazard("unbind")
        setText("UDC", "")
        // A disconnect resets the SCSI state, including the host's prevent-medium-removal.
        preventRemoval.clear()
        if (actor == EXECUTOR) executorWindowStartUs = callStartUs ?: nowUs()
        boundSinceUs = null
        onUdcChange(false, actor)
    }

    private fun writeLun(
        path: String,
        lun: Int,
        attribute: String,
        value: String,
    ) {
        val open = text(lunPath(path, "file")).isNotEmpty()
        val prevented = open && lun in preventRemoval && functionOf(path) == GadgetNames.MASS_STORAGE_FUNCTION

        fun flag(): String =
            when (value.trim()) {
                "0", "1" -> value.trim()
                else -> throw ConfigFsException("EINVAL", "$path: $value")
            }
        when (attribute) {
            "file" -> {
                if (prevented) throw ConfigFsException("EBUSY", "$path: medium removal prevented")
                if (value.isNotEmpty() && value !in hostFiles) throw ConfigFsException("ENOENT", value)
                setText(path, value)
            }
            "forced_eject" -> {
                // fsg_store_forced_eject also clears the host's prevent_medium_removal.
                setText(lunPath(path, "file"), "")
                if (functionOf(path) == GadgetNames.MASS_STORAGE_FUNCTION) preventRemoval -= lun
            }
            "ro" -> {
                if (open) throw ConfigFsException("EBUSY", "$path: file is open")
                setText(path, flag())
            }
            "cdrom" -> {
                if (open) throw ConfigFsException("EBUSY", "$path: file is open")
                val cdrom = flag()
                setText(path, cdrom)
                if (cdrom == "1") setText(lunPath(path, "ro"), "1")
            }
            "removable", "nofua" -> {
                // Conservative: treat these like the other function-level attributes, locked while linked.
                if (functionOf(path)?.let(::isLinked) == true) throw ConfigFsException("EBUSY", "$path: function is linked")
                setText(path, flag())
            }
            else -> setText(path, value)
        }
    }

    private fun createFunction(name: String) {
        val type = name.substringBefore('.', "")
        if (type.isEmpty() || name.substringAfter('.').isEmpty()) throw ConfigFsException("EINVAL", name)
        val path = GadgetPaths.function(name)
        dir(path, defaultGroup = false)
        when (type) {
            "mass_storage" -> {
                attr("$path/stall", "1")
                createLun("$path/lun.0", defaultGroup = true)
            }
            "hid" -> {
                attr("$path/protocol", "0")
                attr("$path/subclass", "0")
                attr("$path/report_length", "0")
                attr("$path/report_desc", "")
                attr("$path/no_out_endpoint", "0")
            }
        }
    }

    private fun createLun(
        path: String,
        defaultGroup: Boolean,
    ) {
        dir(path, defaultGroup)
        attr("$path/file", "")
        attr("$path/ro", "0")
        attr("$path/cdrom", "0")
        attr("$path/removable", "1")
        attr("$path/nofua", "0")
        attr("$path/inquiry_string", "")
        nodes["$path/forced_eject"] = Attr(ByteArray(0), readable = false)
    }

    // --- helpers ---

    private fun recordMutation(path: String) {
        if (actor == EXECUTOR && !GadgetPaths.isMutableByBootleg(path)) foreignWrites += path
        beforeMutation(actor, path)
    }

    private fun links(): Map<String, String> =
        children(GadgetNames.CONFIG_DIR)
            .mapNotNull { child -> (nodes[child] as? Link)?.let { child.substringAfterLast('/') to it.target } }
            .toMap()

    private fun isLinked(function: String): Boolean = nodes.values.any { it is Link && it.target == GadgetPaths.function(function) }

    private fun children(path: String): List<String> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        return nodes.keys.filter { it.isNotEmpty() && it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
    }

    private fun subtree(path: String): List<String> = nodes.keys.filter { it == path || it.startsWith("$path/") }

    private fun text(path: String): String = String((nodes[path] as Attr).bytes)

    private fun setText(
        path: String,
        value: String,
    ) {
        (nodes[path] as Attr).bytes = value.toByteArray()
    }

    private fun lunPath(
        anyLunPath: String,
        attribute: String,
    ): String = anyLunPath.substringBeforeLast('/') + "/" + attribute

    private fun lunPath(
        lun: Int,
        attribute: String,
    ): String = GadgetPaths.lunAttribute(lun, attribute)

    private fun lunOf(path: String): Int? {
        val parts = path.split('/')
        if (parts.size < 3 || parts[0] != GadgetPaths.FUNCTIONS_DIR || !parts[1].startsWith("mass_storage.")) return null
        return GadgetPaths.lunIndex(parts[2])
    }

    private fun functionOf(path: String): String? {
        val parts = path.split('/')
        return if (parts.size >= 2 && parts[0] == GadgetPaths.FUNCTIONS_DIR) parts[1] else null
    }

    /** The function if [path] is a function directory itself. */
    private fun functionName(path: String): String? = functionOf(path)?.takeIf { path == GadgetPaths.function(it) }

    private fun dir(
        path: String,
        defaultGroup: Boolean,
    ) {
        nodes[path] = Dir(defaultGroup)
    }

    private fun attr(
        path: String,
        value: String,
    ) {
        nodes[path] = Attr(value.toByteArray())
    }

    companion object {
        const val PIXEL_UDC = "11110000.dwc3"
        const val EXECUTOR = "executor"
        const val HAL = "hal"
        const val HOST = "host"
        const val DEAD_MAN = "dead-man"
        private const val KERNEL_LUN_COUNT_MAX = 16

        val PIXEL_FOREIGN_FUNCTIONS =
            listOf("ffs.adb", "ffs.mtp", "ffs.ptp", "hid.keyboard", "hid.touchpad", "ncm.gs4", "accessory.gs2", "midi.gs5", "rndis.gs4")

        /** The Pixel 6 gadget as found on the device: ADB plus USB HID Client's functions, bound. */
        fun pixel(hostFiles: Set<String> = emptySet()): FakeConfigFs {
            val fs = FakeConfigFs(hostFiles = hostFiles.toMutableSet())
            fs.asActor("setup") {
                fs.attr("idVendor", "0x18d1")
                fs.attr("idProduct", "0x4ee7")
                fs.dir("strings", defaultGroup = true)
                fs.attr("${GadgetNames.CONFIG_DIR}/MaxPower", "900")
                fs.attr("${GadgetNames.CONFIG_DIR}/bmAttributes", "0x80")
                fs.dir("${GadgetNames.CONFIG_DIR}/strings", defaultGroup = true)
                PIXEL_FOREIGN_FUNCTIONS.forEach { fs.mkdir(GadgetPaths.function(it)) }
                for (hid in listOf("hid.keyboard", "hid.touchpad")) {
                    fs.write(GadgetPaths.functionAttribute(hid, "report_length"), "8")
                    fs.writeBytes(GadgetPaths.functionAttribute(hid, "report_desc"), byteArrayOf(0x05, 0x01, 0x09, 0x06))
                }
                fs.symlink(GadgetPaths.function("ffs.adb"), GadgetPaths.configLink("function0"))
                fs.symlink(GadgetPaths.function("hid.keyboard"), GadgetPaths.configLink("hid.keyboard"))
                fs.symlink(GadgetPaths.function("hid.touchpad"), GadgetPaths.configLink("hid.touchpad"))
                fs.write("UDC", PIXEL_UDC)
            }
            return fs
        }
    }
}
