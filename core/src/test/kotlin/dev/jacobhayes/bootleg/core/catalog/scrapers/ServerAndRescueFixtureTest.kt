package dev.jacobhayes.bootleg.core.catalog.scrapers

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Category
import dev.jacobhayes.bootleg.core.catalog.FixtureHttp
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.HttpResponse
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.NetbootXyzScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.ShredOsScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.SystemRescueScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.clonezillaScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.gpartedScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.AlpineScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.ProxmoxScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.TrueNasScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.almaLinuxScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.rockyScraper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDate
import java.util.Collections

/** L1: each scraper against responses recorded from the live sites (re-record with `-Precord`). */
class ServerAndRescueFixtureTest {
    private val http = FixtureHttp(Path.of("src/test/resources/fixtures/catalog/server-rescue"))

    private fun scrape(scraper: Scraper): List<Release> = runBlocking { scraper.releases(http) }

    private fun List<Release>.single(
        arch: Arch,
        variant: String = "",
        version: String? = null,
    ): Release = single { it.arch == arch && it.variant == variant && (version == null || it.version == version) }

    @Test
    fun `server and rescue scrapers cover the expected distros with unique ids`() {
        val distros = serverAndRescueScrapers.flatMap { it.distros }
        assertEquals(
            listOf(
                "alpine",
                "rocky",
                "almalinux",
                "proxmox-ve",
                "truenas",
                "systemrescue",
                "gparted-live",
                "clonezilla",
                "shredos",
                "netboot-xyz",
            ),
            distros.map { it.id },
        )
        assertEquals(distros.size, distros.map { it.id }.toSet().size)
        assertEquals(5, distros.count { it.category == Category.SERVER })
        assertEquals(5, distros.count { it.category == Category.RESCUE })
        assertTrue(distros.all { it.homepage.startsWith("https://") })
    }

    @Test
    fun alpine() {
        val releases = scrape(AlpineScraper())
        // aarch64 has no "extended" flavor.
        assertEquals(5, releases.size)
        assertEquals(listOf("Standard", "Extended", "Virtual"), releases.filter { it.arch == Arch.X86_64 }.map { it.variant })
        assertEquals(listOf("Standard", "Virtual"), releases.filter { it.arch == Arch.ARM64 }.map { it.variant })
        assertEquals(
            Release(
                distroId = "alpine",
                version = "3.24.2",
                arch = Arch.X86_64,
                variant = "Standard",
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-standard-3.24.2-x86_64.iso",
                sha256 = "20c026e3a788bfb75fc8b50a54bcc12aee85e3c75909740bba6a6f4563d63296",
                sumsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-standard-3.24.2-x86_64.iso.sha256",
                sizeBytes = 370147328,
                fileName = "alpine-standard-3.24.2-x86_64.iso",
            ),
            releases.single(Arch.X86_64, "Standard"),
        )
        assertEquals(
            "a57ba668b5f6b17a670fcf8e799d5d7fe43766ed086d6ce2927b0625bf43dbf6",
            releases.single(Arch.ARM64, "Virtual").sha256,
        )
    }

    @Test
    fun rocky() {
        val releases = scrape(rockyScraper)
        assertEquals(18, releases.size)
        assertEquals(setOf("8.10", "9.8", "10.2"), releases.map { it.version }.toSet())
        assertEquals(
            Release(
                distroId = "rocky",
                version = "10.2",
                arch = Arch.X86_64,
                variant = "DVD",
                url = "https://dl.rockylinux.org/pub/rocky/10/isos/x86_64/Rocky-10.2-x86_64-dvd1.iso",
                sha256 = "16ca9c96cdb221ba6e1f68579f21bd69fd8da81c6a921d9068949796f91c8feb",
                sumsUrl = "https://dl.rockylinux.org/pub/rocky/10/isos/x86_64/CHECKSUM",
                sizeBytes = 10226171904,
                sumsSigned = true,
                fileName = "Rocky-10.2-x86_64-dvd1.iso",
            ),
            releases.single(Arch.X86_64, "DVD", "10.2"),
        )
        assertEquals(
            "2924b291feaebe083bc0d0553e472be0a38ed5c91b4841129ec80b0cecc10efd",
            releases.single(Arch.ARM64, "Minimal", "9.8").sha256,
        )
        // Rocky 8's CHECKSUM has no size comments.
        assertEquals(null, releases.single(Arch.X86_64, "Boot", "8.10").sizeBytes)
        assertTrue(releases.none { "latest" in it.fileName })
    }

    @Test
    fun `enterprise linux fetches newest majors first and scopes a failing CHECKSUM to its major and arch`() {
        val broken = "https://dl.rockylinux.org/pub/rocky/9/isos/x86_64/CHECKSUM"
        val partial = FailingHttp(http) { url -> url == broken }
        val releases = runBlocking { rockyScraper.releases(partial) }
        assertEquals(scrape(rockyScraper).filterNot { it.version == "9.8" && it.arch == Arch.X86_64 }.toSet(), releases.toSet())
        assertEquals(listOf("10.2", "9.8", "8.10"), releases.map { it.version }.distinct())
        val majorsRequested =
            partial.requested
                .filter {
                    it.endsWith(
                        "/CHECKSUM",
                    )
                }.map { it.substringAfter("/rocky/").substringBefore('/') }
        assertEquals(listOf("10", "10", "9", "9", "8", "8"), majorsRequested)

        val allBroken = FailingHttp(http) { url -> url.endsWith("/CHECKSUM") }
        val error = assertThrows<IllegalStateException> { runBlocking { rockyScraper.releases(allBroken) } }
        assertEquals("rocky 10 x86_64: connection reset", error.message)
    }

    /** Replays [fixtures], but fails the requests matching [fails] like a dropped connection. */
    private class FailingHttp(
        private val fixtures: Http,
        private val fails: (String) -> Boolean,
    ) : Http {
        val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun get(url: String): HttpResponse {
            requested += url
            if (fails(url)) throw IOException("connection reset")
            return fixtures.get(url)
        }

        override suspend fun head(url: String) = fixtures.head(url)
    }

    @Test
    fun almaLinux() {
        val releases = scrape(almaLinuxScraper)
        assertEquals(18, releases.size)
        assertEquals(setOf("8.10", "9.8", "10.2"), releases.map { it.version }.toSet())
        val minimal = releases.single(Arch.ARM64, "Minimal", "10.2")
        assertEquals("https://repo.almalinux.org/almalinux/10/isos/aarch64/AlmaLinux-10.2-aarch64-minimal.iso", minimal.url)
        assertEquals("ca5dd9b7e1193de5881df850a93b611c6ba0ba8c5860f54ee822c53585b3014d", minimal.sha256)
        assertEquals(1657339904, minimal.sizeBytes)
        assertEquals("https://repo.almalinux.org/almalinux/10/isos/aarch64/CHECKSUM", minimal.sumsUrl)
        assertTrue(releases.all { it.sumsSigned })
    }

    @Test
    fun proxmox() {
        // The fixture's oldest Proxmox VE 9 ISO is dated 2025-11-19, so on this day 8.x is still offered.
        val releases = scrape(ProxmoxScraper(today = { LocalDate.of(2026, 10, 2) }))
        assertEquals(listOf("9.2-1" to Arch.X86_64, "9.2-1" to Arch.ARM64, "8.4-1" to Arch.X86_64), releases.map { it.version to it.arch })
        assertEquals(
            "d237d70ca48a9f6eb47f95fd4fd337722c3f69f8106393844d027d28c26523d8",
            releases.single(Arch.X86_64, version = "8.4-1").sha256,
        )
        // Thirteen months after that, only the newest major remains.
        assertEquals(
            listOf("9.2-1", "9.2-1"),
            scrape(ProxmoxScraper(today = { LocalDate.of(2026, 12, 19) })).map { it.version },
        )
        val x86 = releases.single(Arch.X86_64, version = "9.2-1")
        assertEquals("9.2-1", x86.version)
        assertEquals("https://enterprise.proxmox.com/iso/proxmox-ve_9.2-1.iso", x86.url)
        assertEquals("4e88fe416df9b527624a175f24c9aa07c714d3332afb1ee3dbf3879573ef2c6c", x86.sha256)
        assertEquals("https://enterprise.proxmox.com/iso/SHA256SUMS", x86.sumsUrl)
        val arm = releases.single(Arch.ARM64)
        assertEquals("https://enterprise.proxmox.com/iso/proxmox-ve_9.2-1-arm64.iso", arm.url)
        assertEquals("b1619dcd1f5b1a6d67d77b59e7e2fa2033174551d1e1b9dc22b2171ec093abbd", arm.sha256)
    }

    @Test
    fun trueNas() {
        val release = scrape(TrueNasScraper()).single()
        assertEquals(
            Release(
                distroId = "truenas",
                version = "25.10.7",
                arch = Arch.X86_64,
                url = "https://download.sys.truenas.net/TrueNAS-SCALE-Goldeye/25.10.7/TrueNAS-SCALE-25.10.7.iso",
                sha256 = "54ce9441ce66966a392e28f63604ca3c2c083d0bec4db7bb5af2f74f7a007c8e",
                fileName = "TrueNAS-SCALE-25.10.7.iso",
            ),
            release,
        )
    }

    @Test
    fun systemRescue() {
        val release = scrape(SystemRescueScraper()).single()
        assertEquals("13.02", release.version)
        assertEquals("https://fastly-cdn.system-rescue.org/releases/13.02/systemrescue-13.02-amd64.iso", release.url)
        assertEquals("ad4d670b72859d887c7960142a9a9d36a3e50446694a035e254442f65d6e7572", release.sha256)
        assertEquals("https://www.system-rescue.org/releases/13.02/systemrescue-13.02-amd64.iso.sha256", release.sumsUrl)
    }

    @Test
    fun gparted() {
        val release = scrape(gpartedScraper).single()
        assertEquals("1.8.1-6", release.version)
        assertEquals(Arch.X86_64, release.arch)
        assertEquals(
            "https://downloads.sourceforge.net/project/gparted/gparted-live-stable/1.8.1-6/gparted-live-1.8.1-6-amd64.iso",
            release.url,
        )
        // The SHA256SUMS section, not the BLAKE3 one (also 64 hex chars).
        assertEquals("d789c38779f0d6f7026c12f44c2c52a04f66e28a1aea7d51f3045ad1bbf28411", release.sha256)
        assertEquals("https://gparted.org/gparted-live/stable/CHECKSUMS.TXT", release.sumsUrl)
    }

    @Test
    fun clonezilla() {
        val release = scrape(clonezillaScraper).single()
        assertEquals("3.3.3-37", release.version)
        assertEquals(
            "https://downloads.sourceforge.net/project/clonezilla/clonezilla_live_stable/3.3.3-37/clonezilla-live-3.3.3-37-amd64.iso",
            release.url,
        )
        assertEquals("3079458d926a37d3533e5d5caeb61b6e49c2dc69e2c97e0332ef37986bb3414f", release.sha256)
    }

    @Test
    fun shredOs() {
        val releases = scrape(ShredOsScraper())
        assertEquals(listOf("", "Lite"), releases.map { it.variant })
        val full = releases.single(Arch.X86_64, "")
        assertEquals("0.42", full.version)
        assertEquals(
            "https://github.com/PartialVolume/shredos.x86_64/releases/download/v2025.11_31_x86-64_0.42/shredos-2025.11_31_x86-64_v0.42_20260716.img",
            full.url,
        )
        assertEquals("1f76afd2f0de8c1aa7d07490ce3a7fabe5ccc8ca01d2c4ff96459f66e2b9cc64", full.sha256)
        assertEquals(374886144, full.sizeBytes)
        assertTrue(releases.all { it.fileName.endsWith(".img") && "i686" !in it.fileName })
    }

    @Test
    fun netbootXyz() {
        val releases = scrape(NetbootXyzScraper())
        assertEquals(8, releases.size)
        assertTrue(releases.all { it.version == "2026-10-01" })
        val usb = releases.single(Arch.X86_64, "USB")
        assertEquals("https://boot.netboot.xyz/ipxe/netboot.xyz.img", usb.url)
        assertEquals("7a371d36b81f020b4dde05a4e79e286e392a4561b975223ae35380e3d77d1069", usb.sha256)
        assertEquals("https://boot.netboot.xyz/ipxe/netboot.xyz-sha256-checksums.txt", usb.sumsUrl)
        assertEquals("930f3c49c61e769c9f3498460ed13d7af9ab1d47e3e24268d40e59844adf6907", releases.single(Arch.ARM64, "ISO").sha256)
    }
}
