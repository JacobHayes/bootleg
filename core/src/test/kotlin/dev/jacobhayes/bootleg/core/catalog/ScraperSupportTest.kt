package dev.jacobhayes.bootleg.core.catalog

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.hex
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScraperSupportTest {
    private val hashA = "a".repeat(64)
    private val hashB = "0123456789abcdef".repeat(4)

    @Test
    fun `parses GNU text and binary sums lines`() {
        val sums = ScraperSupport.parseSha256Sums("$hashA  ubuntu.iso\n${hashB.uppercase()} *kubuntu.iso\n\n# comment\n")
        assertEquals(mapOf("ubuntu.iso" to hashA, "kubuntu.iso" to hashB), sums)
    }

    @Test
    fun `parses BSD lines and skips other algorithms`() {
        val text =
            """
            SHA256 (Fedora-Workstation-Live-44-1.7.x86_64.iso) = $hashA
            SHA512 (Fedora-Workstation-Live-44-1.7.x86_64.iso) = ${"b".repeat(128)}
            SHA256 (./other.iso) = $hashB
            """.trimIndent()
        assertEquals(
            mapOf("Fedora-Workstation-Live-44-1.7.x86_64.iso" to hashA, "other.iso" to hashB),
            ScraperSupport.parseSha256Sums(text),
        )
    }

    @Test
    fun `parses PGP clearsigned sums`() {
        val text =
            """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA256

            # Fedora-Server-dvd-x86_64-44-1.7.iso: 1 bytes
            SHA256 (Fedora-Server-dvd-x86_64-44-1.7.iso) = $hashA
            -----BEGIN PGP SIGNATURE-----

            iQIzBAEBCAAdFiEE
            =abcd
            -----END PGP SIGNATURE-----
            """.trimIndent()
        assertEquals(mapOf("Fedora-Server-dvd-x86_64-44-1.7.iso" to hashA), ScraperSupport.parseSha256Sums(text))
    }

    @Test
    fun `rejects HTML, non-SHA-256 hashes and conflicting entries`() {
        assertThrows<IllegalArgumentException> { ScraperSupport.parseSha256Sums("<!DOCTYPE html>\n<html>Not Found</html>") }
        assertThrows<IllegalArgumentException> { ScraperSupport.parseSha256Sums("${"c".repeat(128)}  file.iso") }
        assertThrows<IllegalArgumentException> { ScraperSupport.parseSha256Sums("abc  file.iso") }
        assertThrows<IllegalArgumentException> { ScraperSupport.parseSha256Sums("$hashA  file.iso\n$hashB  file.iso") }
        assertThrows<IllegalArgumentException> { ScraperSupport.parseSha256Sums("$hashA file.iso") }
        assertThrows<IllegalArgumentException> {
            ScraperSupport.parseSha256Sums(
                "$hashA  file.iso\n".repeat(
                    ScraperSupport.SUMS_LINES_MAX + 1,
                ),
            )
        }
    }

    @Test
    fun `reads only the SHA256SUMS section of sectioned files`() {
        // BLAKE3 hashes are 64 hex chars too, so taking any other section would yield wrong "sha256" values.
        val text =
            """
            ### MD5SUMS:
            d41d8cd98f00b204e9800998ecf8427e  live.iso

            ### SHA256SUMS:
            $hashA  live.iso

            ### B3SUMS:
            $hashB  live.iso
            """.trimIndent()
        assertEquals(mapOf("live.iso" to hashA), ScraperSupport.parseSha256Sums(text))
        assertThrows<IllegalArgumentException> { ScraperSupport.parseSha256Sums("### B3SUMS:\n$hashA  x.iso") }
    }

    @Test
    fun `empty and comment-only sums files are rejected`() {
        assertThrows<IllegalArgumentException> { ScraperSupport.parseSha256Sums("") }
        assertThrows<IllegalArgumentException> { ScraperSupport.parseSha256Sums("# nothing here\n") }
    }

    @Test
    fun `only ever fails with IllegalArgumentException and only yields lowercase sha256`() =
        runBlocking<Unit> {
            val hex = Arb.string(64, Codepoint.hex())
            val line =
                Arb.choice(
                    Arb.bind(hex, Arb.string(1..20, Codepoint.az())) { hash, name -> "$hash  $name.iso" },
                    Arb.bind(hex, Arb.string(1..20, Codepoint.az())) { hash, name -> "SHA256 ($name.img) = $hash" },
                    Arb.element("### SHA256SUMS:", "### B3SUMS:", "# comment", "", "-----BEGIN PGP SIGNATURE-----"),
                    Arb.string(0..120),
                )
            checkAll(1_000, Arb.list(line, 0..30)) { lines ->
                try {
                    val sums = ScraperSupport.parseSha256Sums(lines.joinToString("\n"))
                    assertTrue(sums.isNotEmpty() && sums.values.all { ScraperSupport.isSha256(it) })
                } catch (_: IllegalArgumentException) {
                }
            }
        }

    @Test
    fun `listing names handles Apache and MirrorCache pages`() {
        val apache =
            """
            <a href="?C=N;O=D">Name</a><a href="/ubuntu/">Parent</a>
            <a href="noble/">noble/</a><a href="24.04/">24.04/</a><a href="SHA256SUMS">SHA256SUMS</a>
            <a href="https://help.ubuntu.com/community">help</a><a href="noble/release/">deep</a>
            """.trimIndent()
        assertEquals(
            listOf("noble/", "24.04/", "SHA256SUMS"),
            ScraperSupport.listingNames(apache, "https://cdimage.ubuntu.com/ubuntu/releases/"),
        )
        val mirrorCache = """<a href="../">..</a><a href="./Leap-16.0.iso">x</a><a href="./Leap-16.0.iso.mirrorlist">m</a>"""
        assertEquals(
            listOf("Leap-16.0.iso", "Leap-16.0.iso.mirrorlist"),
            ScraperSupport.listingNames(mirrorCache, "https://download.opensuse.org/distribution/leap/16.0/offline/"),
        )
        assertThrows<IllegalArgumentException> { ScraperSupport.listingNames(apache, "https://example.org/no-slash") }
    }

    @Test
    fun `links resolve relative hrefs`() {
        val links = ScraperSupport.links("""<a href="/x.iso">x</a><a href="https://other.org/y">y</a><a>none</a>""", "https://omarchy.org/")
        assertEquals(listOf("https://omarchy.org/x.iso", "https://other.org/y"), links)
    }

    @Test
    fun `versions compare numerically per segment`() {
        val sorted = listOf("24.04.5.1", "9", "24.04", "24.04.10", "24.04.5", "1.0-rc1", "1.0").sortedWith(ScraperSupport.versionComparator)
        assertEquals(listOf("1.0-rc1", "1.0", "9", "24.04", "24.04.5", "24.04.5.1", "24.04.10"), sorted)
        assertEquals(0, ScraperSupport.compareVersions("26.04 LTS", "26.04 LTS"))
        assertTrue(ScraperSupport.compareVersions("2026.10.01", "2026.09.01") > 0)
    }

    @Test
    fun `version comparison is antisymmetric and matches integer order`() =
        runBlocking<Unit> {
            checkAll(Arb.list(Arb.int(0..999), 1..5), Arb.list(Arb.int(0..999), 1..5)) { left, right ->
                val leftVersion = left.joinToString(".")
                val rightVersion = right.joinToString(".")
                val forward = ScraperSupport.compareVersions(leftVersion, rightVersion)
                assertEquals(-Integer.signum(forward), Integer.signum(ScraperSupport.compareVersions(rightVersion, leftVersion)))
                val expected = left.zip(right).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: left.size.compareTo(right.size)
                assertEquals(Integer.signum(expected), Integer.signum(forward), "$leftVersion vs $rightVersion")
            }
        }

    @Test
    fun `file name is the decoded last path segment`() {
        assertEquals("a b.iso", ScraperSupport.fileNameOf("https://example.org/dir/a%20b.iso?x=1"))
        assertThrows<IllegalArgumentException> { ScraperSupport.fileNameOf("https://example.org/dir/") }
    }

    @Test
    fun `fan-out is bounded`() {
        assertThrows<IllegalArgumentException> {
            runBlocking { ScraperSupport.fanOut((0..ScraperSupport.FAN_OUT_MAX).toList()) { it } }
        }
        assertEquals(listOf(2, 4), runBlocking { ScraperSupport.fanOut(listOf(1, 2)) { it * 2 } })
    }
}
