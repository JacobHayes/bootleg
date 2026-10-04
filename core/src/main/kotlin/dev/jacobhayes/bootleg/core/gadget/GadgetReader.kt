package dev.jacobhayes.bootleg.core.gadget

import java.io.IOException

/**
 * Reads a [GadgetState] snapshot from configfs. Throws on unparseable attributes. USB properties are
 * read only when [props] is given: they matter only for the HAL-quiet wait, and the executor's checks
 * between ops must stay cheap.
 */
class GadgetReader(
    private val fs: ConfigFs,
    private val props: UsbProps? = null,
) {
    fun read(): GadgetState {
        val udc = readUdc()
        val functions = fs.list(GadgetPaths.FUNCTIONS_DIR).toSet()
        val configLinks = readLinks { true }
        val luns =
            if (GadgetNames.MASS_STORAGE_FUNCTION in functions) {
                fs
                    .list(GadgetPaths.function(GadgetNames.MASS_STORAGE_FUNCTION))
                    .mapNotNull { GadgetPaths.lunIndex(it) }
                    .associateWith { readLun(it) }
                    .toSortedMap()
            } else {
                emptyMap()
            }
        return GadgetState(
            udc = udc,
            functions = functions,
            configLinks = configLinks,
            luns = luns,
            usbState = props?.usbState().orEmpty(),
            usbConfig = props?.usbConfig().orEmpty(),
        )
    }

    fun readUdc(): String = if (fs.exists(GadgetPaths.UDC)) fs.read(GadgetPaths.UDC).trimEnd('\n') else ""

    /** Bootleg's own links only: the cheap check run inside the unbound window. */
    fun readOwnedLinks(): Map<String, String> = readLinks(GadgetPaths::isOwnedName)

    /** In link order (the composite's interface order), which the returned map keeps. */
    private fun readLinks(include: (String) -> Boolean): Map<String, String> =
        fs
            .listInLinkOrder(GadgetNames.CONFIG_DIR)
            .filter(include)
            .mapNotNull { name ->
                // Configfs reports targets like ../../../../usb_gadget/g1/functions/ffs.adb; keep the function name.
                readLinkIfPresent(GadgetPaths.configLink(name))?.let { target -> name to target.trimEnd('/').substringAfterLast('/') }
            }.toMap()

    /**
     * The HAL may remove a `functionN` link between list and readlink, so a failed readlink reads as
     * absent. If one of ours is misread this way, the executor's pre-op checks and replans absorb it.
     */
    private fun readLinkIfPresent(path: String): String? =
        try {
            fs.readLink(path)
        } catch (_: IOException) {
            null
        }

    private fun readLun(lun: Int): LunState {
        fun text(attribute: String) = fs.read(GadgetPaths.lunAttribute(lun, attribute)).trimEnd('\n')

        fun flag(attribute: String): Boolean =
            when (val value = text(attribute)) {
                "0" -> false
                "1" -> true
                else -> error("lun.$lun/$attribute: expected 0 or 1, got '$value'")
            }
        return LunState(
            file = text("file"),
            ro = flag("ro"),
            cdrom = flag("cdrom"),
            removable = flag("removable"),
            nofua = flag("nofua"),
            inquiryString = if (fs.exists(GadgetPaths.lunAttribute(lun, "inquiry_string"))) text("inquiry_string") else null,
        )
    }
}
