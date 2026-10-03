package dev.jacobhayes.bootleg.core.catalog.scrapers.desktop

import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Catalog
import dev.jacobhayes.bootleg.core.catalog.FixtureHttp
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.core.catalog.Scraper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate

/** L1: every desktop scraper against recorded upstream responses (`mise run fixtures:refresh` re-records). */
class DesktopScraperFixtureTest {
    private val http = FixtureHttp()
    private val today = { LocalDate.of(2026, 10, 2) }

    private fun scrape(scraper: Scraper): List<Release> {
        val releases = runBlocking { scraper.releases(http) }
        val ids = scraper.distros.map { it.id }.toSet()
        releases.forEach { assertNull(Catalog.validate(it, ids), "invalid: $it") }
        return releases
    }

    private fun List<Release>.single(
        distroId: String,
        arch: Arch,
        variant: String = "",
        version: String? = null,
    ): Release {
        val matches =
            filter {
                it.distroId == distroId &&
                    it.arch == arch &&
                    it.variant == variant &&
                    (version == null || it.version == version)
            }
        assertEquals(1, matches.size, "expected one $distroId $arch '$variant' $version in $this")
        return matches.first()
    }

    @Test
    fun `Ubuntu offers supported series with the newest point release per arch`() {
        val releases = scrape(UbuntuScraper(today))
        assertEquals(setOf("22.04.5 LTS", "24.04.5 LTS", "24.04.5.1 LTS", "26.04.1 LTS"), releases.map { it.version }.toSet())
        val noble = releases.single("ubuntu", Arch.X86_64, version = "24.04.5.1 LTS")
        assertEquals("https://releases.ubuntu.com/noble/ubuntu-24.04.5.1-desktop-amd64.iso", noble.url)
        assertEquals("4da4a0c9035da8e68a59a838674f403f0a54472c78a83b4fb7f78d03588f85a7", noble.sha256)
        assertEquals("https://releases.ubuntu.com/noble/SHA256SUMS", noble.sumsUrl)
        assertEquals("2029-05-31", noble.supportedUntil)
        assertTrue(noble.sumsSigned)
        val resoluteArm = releases.single("ubuntu", Arch.ARM64, version = "26.04.1 LTS")
        assertEquals("https://cdimage.ubuntu.com/ubuntu/releases/resolute/release/ubuntu-26.04.1-desktop-arm64.iso", resoluteArm.url)
        assertEquals("c54d196489d3c867975fb3bbb72ca52ec2e137456e305481f81096304e4d2517", resoluteArm.sha256)
        // 22.04 has no arm64 desktop ISO, and +largemem server builds are skipped.
        assertTrue(releases.none { it.distroId == "ubuntu" && it.arch == Arch.ARM64 && it.version.startsWith("22.04") })
        assertTrue(releases.none { "largemem" in it.url })
        val server = releases.single("ubuntu-server", Arch.ARM64, version = "22.04.5 LTS")
        assertEquals("eafec62cfe760c30cac43f446463e628fada468c2de2f14e0e2bc27295187505", server.sha256)
        assertEquals(11, releases.size)
        assertEquals(6, releases.count { it.distroId == "ubuntu-server" })
    }

    @Test
    fun `Ubuntu returns nothing once every series is past end of life`() {
        assertEquals(emptyList<Release>(), scrape(UbuntuScraper { LocalDate.of(2099, 1, 1) }))
    }

    @Test
    fun `Debian live has one image per desktop and netinst has both arches`() {
        val live = scrape(DebianLiveScraper())
        assertEquals(
            setOf("Cinnamon", "Junior", "GNOME", "KDE Plasma", "LXDE", "LXQt", "MATE", "Standard", "Xfce"),
            live
                .map {
                    it.variant
                }.toSet(),
        )
        assertTrue(live.all { it.version == "13.7.0" && it.arch == Arch.X86_64 })
        val gnome = live.single("debian", Arch.X86_64, "GNOME")
        assertEquals("https://cdimage.debian.org/debian-cd/current-live/amd64/iso-hybrid/debian-live-13.7.0-amd64-gnome.iso", gnome.url)
        assertEquals("e94859a83b305cae5125dc9c6080ced040c59d099ea7381e7f300fd1dba87a17", gnome.sha256)

        val netinst = scrape(DebianNetinstScraper())
        assertEquals(2, netinst.size)
        val arm = netinst.single("debian-netinst", Arch.ARM64, "netinst")
        assertEquals("https://cdimage.debian.org/debian-cd/current/arm64/iso-cd/debian-13.7.0-arm64-netinst.iso", arm.url)
        assertEquals("6e93fa1759bd9d4b0fc11e938987de6967ee7de5297dac1be27c3a75cc17024b", arm.sha256)
    }

    @Test
    fun `Fedora offers the two newest stable releases and skips betas and other editions`() {
        val releases = scrape(FedoraScraper())
        assertEquals(setOf("43", "44"), releases.map { it.version }.toSet())
        assertEquals(setOf("Workstation", "KDE Plasma", "Server", "Server netinst"), releases.map { it.variant }.toSet())
        assertEquals(16, releases.size)
        val workstation = releases.single("fedora", Arch.ARM64, "Workstation", "44")
        assertEquals(
            "https://download.fedoraproject.org/pub/fedora/linux/releases/44/Workstation/aarch64/iso/Fedora-Workstation-Live-44-1.7.aarch64.iso",
            workstation.url,
        )
        assertTrue(workstation.sha256!!.startsWith("162ba3c552a2d241c7c63ec26777af0255ee1b5"))
        assertTrue(workstation.sizeBytes!! > 1_000_000_000)
    }

    @Test
    fun `Mint offers the newest point release of the two newest majors plus LMDE`() {
        val releases = scrape(MintScraper())
        assertEquals(setOf("21.3", "22.3", "7"), releases.map { it.version }.toSet())
        assertEquals(8, releases.size)
        val cinnamon = releases.single("linuxmint", Arch.X86_64, "Cinnamon", "22.3")
        assertEquals("https://mirrors.kernel.org/linuxmint/stable/22.3/linuxmint-22.3-cinnamon-64bit.iso", cinnamon.url)
        assertEquals("a081ab202cfda17f6924128dbd2de8b63518ac0531bcfe3f1a1b88097c459bd4", cinnamon.sha256)
        val lmde = releases.single("linuxmint", Arch.X86_64, "LMDE Cinnamon")
        assertEquals("https://mirrors.kernel.org/linuxmint/debian/lmde-7-cinnamon-64bit.iso", lmde.url)
        assertEquals("https://mirrors.kernel.org/linuxmint/debian/sha256sum.txt", lmde.sumsUrl)
    }

    @Test
    fun `Pop!_OS probes LTS versions and treats 404 as no build`() {
        val releases = scrape(PopOsScraper(today))
        assertEquals(5, releases.size)
        val arm = releases.single("pop-os", Arch.ARM64, "NVIDIA", "24.04")
        assertEquals("https://iso.pop-os.org/24.04/arm64/nvidia/3/pop-os_24.04_arm64_nvidia_3.iso", arm.url)
        assertEquals("8bc00f77a60baf5d189c0dc924523df0f67daa92c944c2f3203fe17f1b70ac50", arm.sha256)
        assertEquals(3213494272, arm.sizeBytes)
        assertEquals(setOf("22.04", "24.04"), releases.map { it.version }.toSet())
    }

    @Test
    fun `openSUSE Leap resolves versioned images through metalinks`() {
        val releases = scrape(OpenSuseLeapScraper())
        assertEquals(6, releases.size)
        val leap16 = releases.single("opensuse-leap", Arch.X86_64, "Offline installer", "16.0")
        assertEquals(
            "https://download.opensuse.org/distribution/leap/16.0/offline/Leap-16.0-offline-installer-x86_64-Build178.27.install.iso",
            leap16.url,
        )
        assertEquals("3c0ace903c0a78b13a09d5649ffc4409370f6d8dab2a79fea310b20ac20a2ac2", leap16.sha256)
        assertEquals(4538236928, leap16.sizeBytes)
        assertEquals("Leap-16.0-offline-installer-x86_64-Build178.27.install.iso", leap16.fileName)
        releases.single("opensuse-leap", Arch.ARM64, "Network installer", "15.6")
    }

    @Test
    fun `openSUSE Tumbleweed pins the current snapshot per arch`() {
        val releases = scrape(OpenSuseTumbleweedScraper())
        assertEquals(10, releases.size)
        val dvd = releases.single("opensuse-tumbleweed", Arch.X86_64, "DVD")
        assertEquals("20260930", dvd.version)
        assertEquals("https://download.opensuse.org/tumbleweed/iso/openSUSE-Tumbleweed-DVD-x86_64-Snapshot20260930-Media.iso", dvd.url)
        assertEquals("0ae329f1727aa4ca953b6f20f4b68906de55b66859a76a5d798e60f473f9db99", dvd.sha256)
        assertEquals("20260924", releases.single("opensuse-tumbleweed", Arch.ARM64, "KDE Live").version)
    }

    @Test
    fun `Arch Linux offers the latest monthly ISO`() {
        val release = scrape(ArchLinuxScraper()).single()
        assertEquals("2026.10.01", release.version)
        assertEquals("https://geo.mirror.pkgbuild.com/iso/2026.10.01/archlinux-2026.10.01-x86_64.iso", release.url)
        assertEquals("684ded26c63240ff4a41e8c25ee84ea6da233f557364821f13d12c2b0a9059a5", release.sha256)
        assertEquals("https://geo.mirror.pkgbuild.com/iso/2026.10.01/sha256sums.txt", release.sumsUrl)
        assertEquals(1640497152, release.sizeBytes)
    }

    @Test
    fun `NixOS offers graphical and minimal images of the stable channel`() {
        val releases = scrape(NixOsScraper())
        assertEquals(4, releases.size)
        val graphical = releases.single("nixos", Arch.X86_64, "Graphical")
        assertEquals("26.05", graphical.version)
        assertEquals(
            "https://releases.nixos.org/nixos/26.05/nixos-26.05.11006.4feb8eb8bf30/nixos-graphical-26.05.11006.4feb8eb8bf30-x86_64-linux.iso",
            graphical.url,
        )
        assertEquals("1bf87b417a252ed116cf3a8812c893b76d9fc5af762e91e63126058fef963a1d", graphical.sha256)
        assertEquals(graphical.url + ".sha256", graphical.sumsUrl)
    }

    @Test
    fun `NixOS keeps the deprecated previous release during its security-support overlap`() {
        // Synthetic status: 26.05 stable, 25.11 deprecated, 25.05 unmaintained, unstable rolling, plus -small variants.
        val overlap = FixtureHttp(Path.of("src/test/resources/fixtures/catalog/nixos-deprecated"), record = false)
        val releases = runBlocking { NixOsScraper().releases(overlap) }
        assertEquals(mapOf("26.05" to 4, "25.11" to 4), releases.groupingBy { it.version }.eachCount())
        val deprecated = releases.single("nixos", Arch.ARM64, "Minimal", "25.11")
        assertEquals(
            "https://releases.nixos.org/nixos/25.11/nixos-25.11.9876.b6018f87da91/nixos-minimal-25.11.9876.b6018f87da91-aarch64-linux.iso",
            deprecated.url,
        )
        releases.forEach { assertNull(Catalog.validate(it, setOf("nixos")), "invalid: $it") }
    }

    @Test
    fun `Omarchy takes the ISO and checksum linked from its home page`() {
        val release = scrape(OmarchyScraper()).single()
        assertEquals("4.0.4", release.version)
        assertEquals("https://iso.omarchy.org/omarchy-4.0.4.iso", release.url)
        assertEquals("https://iso.omarchy.org/omarchy-4.0.4.iso.sha256", release.sumsUrl)
        assertTrue(release.sumsSigned)
    }

    @Test
    fun `desktop scrapers declare unique distros`() {
        val ids = desktopScrapers.flatMap { scraper -> scraper.distros.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(listOf("ubuntu", "ubuntu-server", "debian", "debian-netinst", "fedora", "linuxmint", "pop-os").all { it in ids })
    }
}
