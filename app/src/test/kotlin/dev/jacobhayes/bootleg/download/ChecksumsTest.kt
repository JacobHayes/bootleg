package dev.jacobhayes.bootleg.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ChecksumsTest {
    private val hashA = "815f2195856a696ea90a85d35ab1b0337a0c8d73235789a199123b7074488eb6"
    private val hashB = "bbe75669d1ee17e250914b578dfd583f71da3fd3b7f20363e66cc9cfc592cb80"

    @Test
    fun `GNU text and binary lines`() {
        val sums =
            """
            # netboot.xyz bootloaders generated at Thu Oct  1 11:05:18 UTC 2026

            $hashA *autoexec.ipxe
            $hashB  netboot.xyz.iso
            """.trimIndent()
        assertEquals(hashA, Checksums.find(sums, listOf("autoexec.ipxe")))
        assertEquals(hashB, Checksums.find(sums, listOf("netboot.xyz.iso")))
        assertNull(Checksums.find(sums, listOf("missing.iso")))
    }

    @Test
    fun `BSD style lines inside a PGP clearsigned file`() {
        val sums =
            """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA256

            # Fedora-Workstation-Live-43-1.6.x86_64.iso: 2400000000 bytes
            SHA256 (Fedora-Workstation-Live-43-1.6.x86_64.iso) = ${hashA.uppercase()}
            -----BEGIN PGP SIGNATURE-----
            iQIzBAEBCAAdFiEE
            -----END PGP SIGNATURE-----
            """.trimIndent()
        assertEquals(hashA, Checksums.find(sums, listOf("Fedora-Workstation-Live-43-1.6.x86_64.iso")))
    }

    @Test
    fun `matches by base name and any candidate`() {
        val sums = "$hashA  ./images/ubuntu-26.04-desktop-amd64.iso\n"
        assertEquals(hashA, Checksums.find(sums, listOf("remote-name.iso", "ubuntu-26.04-desktop-amd64.iso")))
    }

    @Test
    fun `bare hash file applies to the image`() {
        assertEquals(hashB, Checksums.find("$hashB\n", listOf("anything.iso")))
    }

    @Test
    fun `a single named entry for another file does not apply`() {
        assertNull(Checksums.find("$hashA  other.iso\n", listOf("mine.iso")))
    }

    @Test
    fun `conflicting hashes for the same file fail loudly`() {
        val sums = "$hashA  x.iso\n$hashB  x.iso\n"
        assertThrows<IllegalStateException> { Checksums.find(sums, listOf("x.iso")) }
    }

    @Test
    fun `duplicate identical entries are fine`() {
        assertEquals(hashA, Checksums.find("$hashA  x.iso\n$hashA *x.iso\n", listOf("x.iso")))
    }

    @Test
    fun `windows line endings and short hashes`() {
        val sums = "$hashA  x.iso\r\nabc123  y.iso\r\n"
        assertEquals(hashA, Checksums.find(sums, listOf("x.iso")))
        assertNull(Checksums.find(sums, listOf("y.iso")))
    }

    @Test
    fun `sha256 of known input`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Checksums.sha256("abc".byteInputStream()),
        )
    }

    @Test
    fun `sidecar line is GNU format and rejects bad input`() {
        assertEquals("$hashA  x.iso\n", Checksums.sidecarLine(hashA, "x.iso"))
        assertThrows<IllegalArgumentException> { Checksums.sidecarLine(hashA.uppercase(), "x.iso") }
        assertThrows<IllegalArgumentException> { Checksums.sidecarLine(hashA, "a/x.iso") }
    }
}
