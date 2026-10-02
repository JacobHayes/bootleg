package dev.jacobhayes.bootleg.core.catalog.scrapers

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.HttpResponse
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.ShredOsScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.parseLiveCdChecksums
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.parseNetbootXyzBuildDate
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.parseShredOsRelease
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.parseSystemRescueDownloadPage
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.AlpineScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.ProxmoxScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.ServerScraperLimits
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.checkedReleases
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.newestStableTrueNasIso
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.numericVersionOrder
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.parseAlpineLatestReleases
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.parseEnterpriseLinuxChecksum
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.parseMajorVersions
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.parseProxmoxIsoDates
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.parseProxmoxSums
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.parseSingleSha256
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.selectProxmoxImages
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class ServerAndRescueParsersTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val hashC = "C".repeat(64)

    @Test
    fun `single sha256 accepts bare and named hashes, rejects others`() {
        assertEquals(hashA, parseSingleSha256("$hashA\n"))
        assertEquals(hashC.lowercase(), parseSingleSha256("$hashC  file.iso"))
        assertThrows<IllegalStateException> { parseSingleSha256("") }
        assertThrows<IllegalStateException> { parseSingleSha256("<html>") }
    }

    @Test
    fun `numeric version order compares numerically, not lexically`() {
        val sorted = listOf("9.10-1", "9.2-1", "10.0-1", "9.2-2", "9.2").sortedWith(numericVersionOrder)
        assertEquals(listOf("9.2", "9.2-1", "9.2-2", "9.10-1", "10.0-1"), sorted)
    }

    @Test
    fun `alpine yaml parser handles block scalars and rejects unknown shapes`() {
        val yaml =
            """
            ---
            -
              title: "Standard"
              desc: |
                Alpine as it was intended.

              flavor: alpine-standard
              file: alpine-standard-3.24.2-x86_64.iso
            -
              flavor: alpine-virt
            """.trimIndent()
        val entries = parseAlpineLatestReleases(yaml)
        assertEquals(2, entries.size)
        assertEquals("Standard", entries[0]["title"])
        assertEquals("alpine-standard-3.24.2-x86_64.iso", entries[0]["file"])
        assertEquals("alpine-virt", entries[1]["flavor"])
        assertThrows<IllegalStateException> { parseAlpineLatestReleases("") }
        assertThrows<IllegalStateException> { parseAlpineLatestReleases("<html><body>404</body></html>") }
        assertThrows<IllegalStateException> { parseAlpineLatestReleases("  key: before-item") }
        assertThrows<IllegalStateException> { parseAlpineLatestReleases("- inline: item") }
        // Over the line limit fails instead of silently parsing a truncated file.
        val entry = "-\n  flavor: alpine-virt\n"
        val tooLong = entry.repeat(ServerScraperLimits.SUMS_LINES_MAX / 2 + 1)
        assertThrows<IllegalStateException> { parseAlpineLatestReleases(tooLong) }
        assertEquals(
            ServerScraperLimits.SUMS_LINES_MAX / 2 - 1,
            parseAlpineLatestReleases(
                entry.repeat(
                    ServerScraperLimits.SUMS_LINES_MAX / 2 - 1,
                ),
            ).size,
        )
    }

    @Test
    fun `alpine rejects an entry with a bad hash`() {
        val yaml =
            """
            -
              branch: v3.24
              arch: x86_64
              version: 3.24.2
              flavor: alpine-standard
              file: alpine-standard-3.24.2-x86_64.iso
              sha256: nothex
            """.trimIndent()
        val http = StaticHttp(mapOf("x86_64" to yaml, "aarch64" to yaml.replace("x86_64", "aarch64")))
        assertThrows<IllegalStateException> { runBlocking { AlpineScraper().releases(http) } }
    }

    @Test
    fun `major versions come from bare-number directories only, newest first`() {
        val html =
            """
            <a href="../">..</a><a href="10/">10/</a><a href="10.2/">x</a>
            <a href="9/">9/</a><a href="9.9-beta/">b</a><a href="RPM-GPG-KEY">k</a>
            """.trimIndent()
        assertEquals(listOf(10, 9), parseMajorVersions(html))
        assertEquals(emptyList<Int>(), parseMajorVersions("<html></html>"))
    }

    @Test
    fun `enterprise linux checksum keeps newest point release per variant and drops latest aliases`() {
        val text =
            """
            # Rocky-9.7-x86_64-dvd.iso: 100 bytes
            SHA256 (Rocky-9.7-x86_64-dvd.iso) = $hashA
            # Rocky-9.10-x86_64-dvd.iso: 200 bytes
            SHA256 (Rocky-9.10-x86_64-dvd.iso) = $hashB
            SHA256 (Rocky-9-latest-x86_64-dvd.iso) = $hashB
            SHA256 (Rocky-9.10-aarch64-dvd.iso) = $hashA
            SHA256 (Rocky-9.10-x86_64-boot.iso) = $hashA
            SHA256 (Rocky-9.10-x86_64-dvd.iso.manifest) = $hashA
            """.trimIndent()
        val images = parseEnterpriseLinuxChecksum(text, "Rocky", 9, "x86_64")
        assertEquals(listOf("Boot", "DVD"), images.map { it.variant })
        val dvd = images.single { it.variant == "DVD" }
        assertEquals("9.10", dvd.version)
        assertEquals(hashB, dvd.sha256)
        assertEquals(200L, dvd.sizeBytes)
        assertEquals(emptyList<Any>(), parseEnterpriseLinuxChecksum(text, "Rocky", 10, "x86_64"))
    }

    @Test
    fun `proxmox parses every VE image and ignores other products`() {
        val text =
            """
            $hashA  proxmox-ve_8.4-1.iso
            $hashC  proxmox-ve_9.2-1-arm64.iso
            $hashA  proxmox-backup-server_4.2-1.iso
            """.trimIndent()
        assertEquals(
            listOf(Triple(8, "8.4-1", Arch.X86_64), Triple(9, "9.2-1", Arch.ARM64)),
            parseProxmoxSums(text).map { Triple(it.major, it.version, it.arch) },
        )
        assertEquals(emptyList<Any>(), parseProxmoxSums("$hashA  proxmox-backup-server_4.2-1.iso"))
    }

    @Test
    fun `proxmox offers the previous major for 13 months after the newest major's first ISO`() {
        val images =
            parseProxmoxSums(
                """
                $hashA  proxmox-ve_7.4-1.iso
                $hashA  proxmox-ve_8.3-1.iso
                $hashA  proxmox-ve_8.4-1.iso
                $hashA  proxmox-ve_9.1-1.iso
                $hashB  proxmox-ve_9.10-1.iso
                $hashC  proxmox-ve_9.2-1-arm64.iso
                """.trimIndent(),
            )
        val dates =
            mapOf(
                "proxmox-ve_9.1-1.iso" to LocalDate.of(2025, 11, 19),
                "proxmox-ve_9.10-1.iso" to LocalDate.of(2026, 5, 21),
                "proxmox-ve_9.2-1-arm64.iso" to LocalDate.of(2026, 8, 4),
            )

        fun offered(today: LocalDate) = selectProxmoxImages(images, dates, today).map { it.arch to it.version }
        val newestOnly = listOf(Arch.X86_64 to "9.10-1", Arch.ARM64 to "9.2-1")
        assertEquals(newestOnly + (Arch.X86_64 to "8.4-1"), offered(LocalDate.of(2026, 12, 18)))
        assertEquals(newestOnly, offered(LocalDate.of(2026, 12, 19)))
        assertEquals(emptyList<Any>(), selectProxmoxImages(emptyList(), dates, LocalDate.of(2026, 1, 1)))
        // No date for the newest major means the page changed shape: fail rather than guess.
        assertThrows<IllegalStateException> { selectProxmoxImages(images, emptyMap(), LocalDate.of(2026, 1, 1)) }
    }

    @Test
    fun `proxmox with no VE images returns empty, not an error`() {
        val http = StaticHttp(mapOf("SHA256SUMS" to "$hashA  proxmox-mail-gateway_9.1-1.iso"))
        assertEquals(emptyList<Release>(), runBlocking { ProxmoxScraper().releases(http) })
    }

    @Test
    fun `proxmox ISO page dates pair each file with the date in its own card`() {
        val html =
            """
            <div class="card"><span class="filename">proxmox-ve_9.2-1.iso</span>
              <div class="meta"><span>1.6G</span><span>2026-05-21</span></div></div>
            <div class="card"><span class="filename">proxmox-ve_9.1-1.iso</span><div class="meta"><span>1.7G</span></div></div>
            <div class="card"><span class="filename"> proxmox-ve_8.4-1.iso </span><span>2025-04-09</span></div>
            """.trimIndent()
        assertEquals(
            mapOf("proxmox-ve_9.2-1.iso" to LocalDate.of(2026, 5, 21), "proxmox-ve_8.4-1.iso" to LocalDate.of(2025, 4, 9)),
            parseProxmoxIsoDates(html),
        )
        assertEquals(emptyMap<String, LocalDate>(), parseProxmoxIsoDates("<html>404</html>"))
    }

    @Test
    fun `truenas picks the newest stable ISO and skips betas`() {
        val html =
            """
            <a href="https://download.sys.truenas.net/TrueNAS-SCALE-Goldeye/25.10.7/TrueNAS-SCALE-25.10.7.iso">a</a>
            <a href="https://download.sys.truenas.net/TrueNAS-SCALE-Fangtooth/25.04.2.6/TrueNAS-SCALE-25.04.2.6.iso">b</a>
            <a href="https://iso.sys.truenas.net/TrueNAS-26-BETA/26.0.0-BETA.3/TrueNAS-26.0.0-BETA.3.iso">c</a>
            <a href="http://insecure.example/TrueNAS-99.0.iso">d</a>
            """.trimIndent()
        val iso = newestStableTrueNasIso(html, "https://www.truenas.com/download/")
        assertEquals("25.10.7", iso?.version)
        assertEquals("https://download.sys.truenas.net/TrueNAS-SCALE-Goldeye/25.10.7/TrueNAS-SCALE-25.10.7.iso", iso?.url)
        // Future naming without "SCALE" still parses.
        assertEquals(
            "26.0.0",
            newestStableTrueNasIso("""<a href="https://x.net/TrueNAS-26/26.0.0/TrueNAS-26.0.0.iso">""", "https://x.net/")?.version,
        )
        assertNull(newestStableTrueNasIso("<html>no links</html>", "https://x.net/"))
    }

    @Test
    fun `system rescue needs both an ISO link and a sha256 link for the same version`() {
        val page =
            """
            <a href="https://fastly-cdn.system-rescue.org/releases/13.02/systemrescue-13.02-amd64.iso">iso</a>
            <a href="/releases/13.02/systemrescue-13.02-amd64.iso.sha256">sha256</a>
            <a href="https://fastly-cdn.system-rescue.org/releases/14.00/systemrescue-14.00-amd64.iso">newer iso, no sums yet</a>
            """.trimIndent()
        val links = parseSystemRescueDownloadPage(page, "https://www.system-rescue.org/Download/")
        assertEquals("13.02", links?.version)
        assertEquals("https://www.system-rescue.org/releases/13.02/systemrescue-13.02-amd64.iso.sha256", links?.sumsUrl)
        assertNull(parseSystemRescueDownloadPage("<html></html>", "https://www.system-rescue.org/Download/"))
    }

    @Test
    fun `live cd checksums map amd64 and arm64 and reject mixed versions`() {
        val text =
            """
            ### SHA256SUMS:
            $hashA  clonezilla-live-3.3.3-37-amd64.iso
            $hashB  clonezilla-live-3.3.3-37-arm64.iso
            $hashC  clonezilla-live-3.3.3-37-amd64.zip
            """.trimIndent()
        val images = parseLiveCdChecksums(text, "clonezilla-live")
        assertEquals(listOf(Arch.X86_64, Arch.ARM64), images.map { it.arch })
        assertEquals(listOf(hashA, hashB), images.map { it.sha256 })
        val mixed = "$hashA  gparted-live-1.8.1-6-amd64.iso\n$hashB  gparted-live-1.9.0-1-amd64.iso"
        assertThrows<IllegalStateException> { parseLiveCdChecksums(mixed, "gparted-live") }
    }

    @Test
    fun `shredos release JSON keeps x86-64 img assets with a sha256 digest`() {
        val json =
            """
            {"tag_name":"v1","draft":false,"prerelease":false,"assets":[
              {"name":"shredos-2025.11_31_x86-64_v0.42_20260716.img","size":10,"digest":"sha256:$hashA","browser_download_url":"https://github.com/a/b.img"},
              {"name":"shredos-2025.11_31_x86-64_v0.42_20260716_lite.img","size":5,"digest":null,"browser_download_url":"https://github.com/a/c.img"},
              {"name":"shredos-2025.11_31_i686_v0.42_20260716_lite.img","size":5,"digest":"sha256:$hashB","browser_download_url":"https://github.com/a/d.img"},
              {"name":"shredos-2025.11_31_x86-64_v0.42_20260716.iso","size":5,"digest":"sha256:$hashB","browser_download_url":"https://github.com/a/e.iso"}
            ]}
            """.trimIndent()
        val images = parseShredOsRelease(json)
        assertEquals(listOf("shredos-2025.11_31_x86-64_v0.42_20260716.img"), images.map { it.fileName })
        assertEquals("0.42", images.single().version)
        assertEquals(emptyList<Any>(), parseShredOsRelease("""{"prerelease":true,"assets":[]}"""))
        assertThrows<IllegalStateException> { parseShredOsRelease("[]") }
        assertThrows<IllegalStateException> { parseShredOsRelease("""{"tag_name":"v1"}""") }
    }

    @Test
    fun `shredos reports GitHub rate limiting clearly and treats 404 as no releases`() {
        val limited = assertThrows<IllegalStateException> { runBlocking { ShredOsScraper().releases(StatusHttp(403)) } }
        assertTrue("rate limit" in limited.message.orEmpty())
        assertEquals(emptyList<Release>(), runBlocking { ShredOsScraper().releases(StatusHttp(404)) })
    }

    @Test
    fun `netboot build date parses the header and tolerates its absence`() {
        assertEquals("2026-10-01", parseNetbootXyzBuildDate("# netboot.xyz bootloaders generated at Thu Oct  1 11:05:18 UTC 2026\n"))
        assertEquals("2026-12-24", parseNetbootXyzBuildDate("# generated at Thu Dec 24 01:00:00 UTC 2026"))
        assertNull(parseNetbootXyzBuildDate("$hashA *netboot.xyz.iso"))
    }

    @Test
    fun `release invariants reject unsafe output`() {
        val scraper = ProxmoxScraper()
        val good = Release("proxmox-ve", "9.2-1", Arch.X86_64, url = "https://x/y.iso", sha256 = hashA, fileName = "y.iso")
        assertEquals(listOf(good), scraper.checkedReleases(listOf(good)))
        val bad =
            listOf(
                good.copy(distroId = "other"),
                good.copy(url = "http://x/y.iso"),
                good.copy(fileName = "../y.iso"),
                good.copy(sha256 = "abc"),
                good.copy(sha256 = hashC),
                good.copy(sha256 = null, sumsUrl = null),
                good.copy(sizeBytes = 0),
            )
        for (release in bad) assertThrows<IllegalStateException>(release.toString()) { scraper.checkedReleases(listOf(release)) }
        assertThrows<IllegalStateException> { scraper.checkedReleases(listOf(good, good)) }
    }

    /** Serves the first body whose key is a substring of the URL. */
    private class StaticHttp(
        private val bodies: Map<String, String>,
    ) : Http {
        override suspend fun get(url: String): HttpResponse {
            val body = bodies.entries.firstOrNull { it.key in url }?.value ?: return HttpResponse(404, "", emptyMap(), url)
            return HttpResponse(200, body, emptyMap(), url)
        }

        override suspend fun head(url: String): HttpResponse = HttpResponse(200, "", emptyMap(), url)
    }

    private class StatusHttp(
        private val status: Int,
    ) : Http {
        override suspend fun get(url: String) = HttpResponse(status, "{}", emptyMap(), url)

        override suspend fun head(url: String) = HttpResponse(status, "", emptyMap(), url)
    }
}
