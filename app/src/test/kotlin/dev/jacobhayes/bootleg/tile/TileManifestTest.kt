package dev.jacobhayes.bootleg.tile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The tile and the drives notification show Bootleg's own glyph, not a system one (it once looked like Bluetooth). */
class TileManifestTest {
    @Test
    fun `the tile uses the drive glyph`() {
        val manifest =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(File("src/main/AndroidManifest.xml"))
        val services = manifest.getElementsByTagName("service")
        val tile =
            (0 until services.length)
                .map { services.item(it) as Element }
                .single { it.getAttributeNS(ANDROID_NS, "name") == ".tile.BootlegTileService" }
        assertEquals("@drawable/ic_drive", tile.getAttributeNS(ANDROID_NS, "icon"))
    }

    @Test
    fun `the drives notification uses the drive glyph`() {
        val source = File("src/main/kotlin/dev/jacobhayes/bootleg/service/BootlegService.kt").readText()
        assertEquals(listOf("R.drawable.ic_drive"), Regex("""setSmallIcon\(([^)]*)\)""").findAll(source).map { it.groupValues[1] }.toList())
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
