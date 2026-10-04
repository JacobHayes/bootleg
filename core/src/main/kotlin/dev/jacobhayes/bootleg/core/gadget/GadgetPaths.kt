package dev.jacobhayes.bootleg.core.gadget

/** configfs paths relative to [GadgetNames.GADGET_ROOT]. */
object GadgetPaths {
    const val UDC = "UDC"
    const val FUNCTIONS_DIR = "functions"
    const val ALLOWED_ROOT_DEFAULT = "/data/media/0/bootleg/"

    val LUN_ATTRIBUTES = listOf("file", "ro", "cdrom", "removable", "nofua")
    private val lunDirRegex = Regex("""lun\.(0|[1-9][0-9]*)""")

    fun function(name: String): String = "$FUNCTIONS_DIR/$name"

    fun configLink(name: String): String = "${GadgetNames.CONFIG_DIR}/$name"

    fun lunDir(lun: Int): String = "${function(GadgetNames.MASS_STORAGE_FUNCTION)}/lun.$lun"

    fun lunAttribute(
        lun: Int,
        attribute: String,
    ): String = "${lunDir(lun)}/$attribute"

    fun functionAttribute(
        function: String,
        attribute: String,
    ): String = "${function(function)}/$attribute"

    /** LUN index of a `lun.N` directory name, or null. */
    fun lunIndex(directoryName: String): Int? =
        lunDirRegex
            .matchEntire(directoryName)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    /** LUN index if [path] is inside `functions/mass_storage.bootleg/lun.N`, else null. */
    fun lunOf(path: String): Int? {
        val prefix = function(GadgetNames.MASS_STORAGE_FUNCTION) + "/"
        if (!path.startsWith(prefix)) return null
        return lunIndex(path.removePrefix(prefix).substringBefore('/'))
    }

    /** Function name if [path] is `functions/<name>` or inside it, else null. */
    fun functionOf(path: String): String? {
        val parts = path.split('/')
        return if (parts.size >= 2 && parts[0] == FUNCTIONS_DIR) parts[1] else null
    }

    fun isOwnedName(name: String): Boolean = GadgetNames.OWNED_MARKER in name

    /** Every path Bootleg may mutate: owned names, plus the UDC file. */
    fun isMutableByBootleg(path: String): Boolean = path == UDC || isOwnedName(path)

    /** Paths an op touches, for ownership checks. */
    fun touchedPaths(op: Op): List<String> =
        when (op) {
            Op.Unbind, is Op.Bind -> listOf(UDC)
            is Op.Mkdir -> listOf(op.path)
            is Op.Rmdir -> listOf(op.path)
            is Op.Write -> listOf(op.path)
            is Op.WriteBytes -> listOf(op.path)
            is Op.Symlink -> listOf(op.link)
            is Op.Unlink -> listOf(op.link)
            Op.StartRndis, Op.ReleaseRndis, Op.StopRndis -> listOf(function(GadgetNames.RNDIS_FUNCTION))
            // The HAL relinks its own functions; Bootleg writes nothing of its.
            Op.RelinkForeign -> emptyList()
        }
}
