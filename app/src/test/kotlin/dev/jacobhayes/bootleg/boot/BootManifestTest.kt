package dev.jacobhayes.bootleg.boot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Restore after reboot re-exports images from `/data/media/0`, which is credential-encrypted: it is
 * readable only once the user has unlocked after boot. Android delivers BOOT_COMPLETED to a
 * component that isn't direct-boot aware only after that unlock (LOCKED_BOOT_COMPLETED comes before
 * it), so the receiver needs no unlock wait of its own as long as it stays that way. This guards it.
 */
class BootManifestTest {
    private val manifest =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))

    @Test
    fun `nothing is direct-boot aware`() {
        val aware =
            manifest.allElements().filter { it.getAttributeNS(ANDROID_NS, "directBootAware") == "true" }.map { it.tagName }
        assertEquals(emptyList<String>(), aware, "direct-boot aware components could run before /data/media is unlocked")
    }

    @Test
    fun `the boot receiver hears only BOOT_COMPLETED`() {
        val receiver =
            manifest.allElements().single { it.tagName == "receiver" && it.getAttributeNS(ANDROID_NS, "name") == ".boot.BootReceiver" }
        val actions =
            receiver
                .getElementsByTagName("action")
                .let { nodes -> (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") } }
        assertEquals(listOf("android.intent.action.BOOT_COMPLETED"), actions)
        assertTrue(actions.none { it.contains("LOCKED_BOOT_COMPLETED") })
    }

    private fun org.w3c.dom.Document.allElements(): List<Element> {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
