package dev.jacobhayes.bootleg.core.image

import dev.jacobhayes.bootleg.core.gadget.LunMode
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.zip.CRC32

/** Builds synthetic image headers; only the structures the sniffer reads are filled in. */
private object Headers {
    const val SIZE = ImageSniffer.HEADER_SIZE_BYTES
    const val ISO_SIZE_BYTES = 700L * 1024 * 1024

    fun blank() = ByteArray(SIZE)

    fun ByteArray.put(
        offset: Int,
        text: String,
    ) = text.encodeToByteArray().copyInto(this, offset)

    fun ByteArray.putUInt32(
        offset: Int,
        value: Long,
    ) {
        for (i in 0 until 4) this[offset + i] = (value ushr (8 * i)).toByte()
    }

    fun ByteArray.putUInt64(
        offset: Int,
        value: Long,
    ) {
        for (i in 0 until 8) this[offset + i] = (value ushr (8 * i)).toByte()
    }

    fun ByteArray.putBootSignature(offset: Int = 0) {
        this[offset + 510] = 0x55
        this[offset + 511] = 0xAA.toByte()
    }

    fun ByteArray.withIso(
        label: String = "Ubuntu 26.04 LTS amd64",
        elTorito: Boolean = true,
    ): ByteArray {
        var sector = 16

        fun descriptor(type: Int): Int {
            val offset = sector++ * 2048
            this[offset] = type.toByte()
            put(offset + 1, "CD001")
            this[offset + 6] = 1
            return offset
        }
        val primary = descriptor(1)
        put(primary + 40, label.padEnd(32, ' '))
        if (elTorito) put(descriptor(0) + 7, "EL TORITO SPECIFICATION")
        descriptor(255)
        return this
    }

    fun ByteArray.withMbr(vararg partitions: MbrPartition): ByteArray {
        partitions.forEachIndexed { index, partition ->
            val entry = 446 + 16 * index
            this[entry] = if (partition.bootable) 0x80.toByte() else 0
            this[entry + 4] = partition.typeCode.toByte()
            putUInt32(entry + 8, partition.startSector)
            putUInt32(entry + 12, partition.sectorCount)
        }
        putBootSignature()
        return this
    }

    /** EFI System Partition type GUID C12A7328-F81F-11D2-BA4B-00A0C93EC93B in on-disk byte order. */
    val efiSystemGuidBytes =
        listOf(0x28, 0x73, 0x2A, 0xC1, 0x1F, 0xF8, 0xD2, 0x11, 0xBA, 0x4B, 0x00, 0xA0, 0xC9, 0x3E, 0xC9, 0x3B)

    /** A protective MBR plus a GPT header that validates unless a parameter breaks it, and one ESP entry. */
    fun ByteArray.withGpt(
        entryCount: Long = 128,
        entrySizeBytes: Long = 128,
        entriesLba: Long = 2,
        revision: Long = 0x0001_0000,
        headerSizeBytes: Long = 92,
        headerLba: Long = 1,
        firstUsableLba: Long = 34,
        lastUsableLba: Long = 16_777_182,
        validCrc: Boolean = true,
    ): ByteArray {
        withMbr(MbrPartition(false, 0xEE, 1, 0xFFFF_FFFFL))
        put(512, "EFI PART")
        putUInt32(512 + 8, revision)
        putUInt32(512 + 12, headerSizeBytes)
        putUInt64(512 + 24, headerLba)
        putUInt64(512 + 32, lastUsableLba + 33)
        putUInt64(512 + 40, firstUsableLba)
        putUInt64(512 + 48, lastUsableLba)
        putUInt64(512 + 72, entriesLba)
        putUInt32(512 + 80, entryCount)
        putUInt32(512 + 84, entrySizeBytes)
        val crcLength = headerSizeBytes.coerceIn(92, 512).toInt()
        val crc = CRC32().apply { update(this@withGpt, 512, crcLength) }.value
        putUInt32(512 + 16, if (validCrc) crc else crc xor 1)
        val entry = 1024
        efiSystemGuidBytes.forEachIndexed { index, value -> this[entry + index] = value.toByte() }
        putUInt64(entry + 32, 2048)
        putUInt64(entry + 40, 206847)
        "EFI system partition".toByteArray(Charsets.UTF_16LE).copyInto(this, entry + 56)
        return this
    }

    fun ByteArray.withFat32BootSector(
        offset: Int = 0,
        label: String = "BOOTLEG",
    ): ByteArray {
        this[offset] = 0xEB.toByte()
        this[offset + 1] = 0x58
        this[offset + 2] = 0x90.toByte()
        put(offset + 3, "MSDOS5.0")
        this[offset + 12] = 0x02 // 512 bytes per sector
        put(offset + 0x47, label.padEnd(11, ' '))
        put(offset + 0x52, "FAT32   ")
        putBootSignature(offset)
        return this
    }

    fun ByteArray.withFat16BootSector(label: String): ByteArray {
        this[0] = 0xEB.toByte()
        this[1] = 0x3C
        this[2] = 0x90.toByte()
        this[12] = 0x02
        put(0x2B, label.padEnd(11, ' '))
        put(0x36, "FAT16   ")
        putBootSignature()
        return this
    }
}

class ImageSnifferTest {
    private fun sniff(
        header: ByteArray,
        sizeBytes: Long = Headers.ISO_SIZE_BYTES,
    ) = ImageSniffer.sniff(header, sizeBytes)

    @Test
    fun `plain ISO9660 with El Torito`() {
        val info = with(Headers) { sniff(blank().withIso()) }
        assertEquals(ImageKind.ISO9660, info.kind)
        assertTrue(info.elTorito)
        assertFalse(info.isHybrid)
        assertEquals("Ubuntu 26.04 LTS amd64", info.volumeLabel)
        assertNull(info.partitionTable)
        assertEquals(Headers.ISO_SIZE_BYTES, info.sizeBytes)
    }

    @Test
    fun `ISO9660 without El Torito is not bootable`() {
        val info = with(Headers) { sniff(blank().withIso(label = "DATA", elTorito = false)) }
        assertEquals(ImageKind.ISO9660, info.kind)
        assertFalse(info.elTorito)
        assertEquals("DATA", info.volumeLabel)
    }

    @Test
    fun `blank ISO label is null`() {
        assertNull(with(Headers) { sniff(blank().withIso(label = "")) }.volumeLabel)
    }

    @Test
    fun `isohybrid ISO with MBR`() {
        val partition = MbrPartition(true, 0x00, 0, 1_433_600)
        val bootable = MbrPartition(true, 0x17, 0, 1_433_600)
        val info = with(Headers) { sniff(blank().withIso().withMbr(bootable)) }
        assertEquals(ImageKind.HYBRID_ISO, info.kind)
        assertTrue(info.isHybrid)
        assertTrue(info.elTorito)
        assertEquals(PartitionTable.Mbr(listOf(bootable)), info.partitionTable)
        // Empty entries (type 0) are omitted, and a table with none left is no table: the ISO stays pure.
        val emptyTable = with(Headers) { sniff(blank().withIso().withMbr(partition)) }
        assertEquals(ImageKind.ISO9660, emptyTable.kind)
        assertNull(emptyTable.partitionTable)
    }

    @Test
    fun `boot signature with four empty partition entries is not an MBR`() {
        val header = with(Headers) { blank().apply { putBootSignature() } }
        header[0] = 0xFA.toByte() // boot code
        assertEquals(ImageKind.UNKNOWN, sniff(header).kind)
        assertNull(sniff(header).partitionTable)
    }

    @Test
    fun `pure ISO with junk at LBA 1 stays CD-ROM only`() {
        val header =
            with(Headers) {
                blank().withIso().apply {
                    putBootSignature()
                    put(512, "EFI PART")
                    for (offset in 520 until 1024) this[offset] = (offset * 31).toByte()
                }
            }
        val info = sniff(header)
        assertEquals(ImageKind.ISO9660, info.kind)
        assertNull(info.partitionTable)
        assertEquals(setOf(LunMode.CDROM), ImageSniffer.allowedModes(info))
    }

    @Test
    fun `hybrid ISO with GPT`() {
        val info = with(Headers) { sniff(blank().withIso().withGpt()) }
        assertEquals(ImageKind.HYBRID_ISO, info.kind)
        val gpt = info.partitionTable as PartitionTable.Gpt
        assertEquals(128, gpt.entryCount)
        assertEquals(listOf(GptPartition("C12A7328-F81F-11D2-BA4B-00A0C93EC93B", 2048, 206847, "EFI system partition")), gpt.partitions)
    }

    @Test
    fun `GPT disk`() {
        val info = with(Headers) { sniff(blank().withGpt(), 8L shl 30) }
        assertEquals(ImageKind.GPT_DISK, info.kind)
        assertEquals(1, (info.partitionTable as PartitionTable.Gpt).partitions.size)
    }

    @Test
    fun `GPT headers that fail validation are not GPT`() {
        with(Headers) {
            val invalid =
                mapOf(
                    "entry size 100" to blank().withGpt(entrySizeBytes = 100),
                    "entry size 256" to blank().withGpt(entrySizeBytes = 256),
                    "entry count 1M" to blank().withGpt(entryCount = 1_000_000),
                    "entry count 0" to blank().withGpt(entryCount = 0),
                    "entries at 2^40" to blank().withGpt(entriesLba = 1L shl 40),
                    "entries at -1" to blank().withGpt(entriesLba = -1),
                    "entries at LBA 1" to blank().withGpt(entriesLba = 1),
                    "revision 2.0" to blank().withGpt(revision = 0x0002_0000),
                    "header size 91" to blank().withGpt(headerSizeBytes = 91),
                    "header size 513" to blank().withGpt(headerSizeBytes = 513),
                    "header LBA 2" to blank().withGpt(headerLba = 2),
                    "last usable before first" to blank().withGpt(firstUsableLba = 100, lastUsableLba = 99),
                    "first usable inside entries" to blank().withGpt(firstUsableLba = 2),
                    "bad CRC" to blank().withGpt(validCrc = false),
                )
            for ((case, header) in invalid) {
                val info = sniff(header)
                // Only the protective MBR is left.
                assertEquals(ImageKind.MBR_DISK, info.kind, case)
                assertEquals(PartitionTable.Mbr(listOf(MbrPartition(false, 0xEE, 1, 0xFFFF_FFFFL))), info.partitionTable, case)
            }
            // A header size above 92 is fine as long as the CRC covers it.
            assertEquals(ImageKind.GPT_DISK, sniff(blank().withGpt(headerSizeBytes = 512)).kind)
        }
    }

    @Test
    fun `GPT entries beyond the header are skipped`() {
        // The entry array starts exactly at the end of the 64 KiB header.
        val header = with(Headers) { blank().withGpt(entriesLba = 128, firstUsableLba = 160) }
        val gpt = sniff(header).partitionTable as PartitionTable.Gpt
        assertEquals(128, gpt.entryCount)
        assertEquals(emptyList<GptPartition>(), gpt.partitions)
    }

    @Test
    fun `plain MBR disk with a FAT32 partition reads its label`() {
        val header =
            with(Headers) {
                blank().withFat32BootSector(offset = 63 * 512, label = "MYUSB").withMbr(MbrPartition(true, 0x0C, 63, 2_000_000))
            }
        val info = sniff(header, 1L shl 30)
        assertEquals(ImageKind.MBR_DISK, info.kind)
        assertEquals("MYUSB", info.volumeLabel)
        assertEquals(PartitionTable.Mbr(listOf(MbrPartition(true, 0x0C, 63, 2_000_000))), info.partitionTable)
    }

    @Test
    fun `MBR disk whose partition starts beyond the header has no label`() {
        val info = with(Headers) { sniff(blank().withMbr(MbrPartition(false, 0x83, 2048, 1000))) }
        assertEquals(ImageKind.MBR_DISK, info.kind)
        assertNull(info.volumeLabel)
    }

    @Test
    fun `boot signature with invalid status bytes is not an MBR`() {
        val header = with(Headers) { blank().apply { putBootSignature() } }
        header[446] = 0x42
        header[100] = 1
        assertEquals(ImageKind.UNKNOWN, sniff(header).kind)
    }

    @Test
    fun `unpartitioned FAT volumes`() {
        val fat32 = with(Headers) { sniff(blank().withFat32BootSector(label = "BOOTLEG")) }
        assertEquals(ImageKind.FAT_VOLUME, fat32.kind)
        assertEquals("BOOTLEG", fat32.volumeLabel)
        val fat16 = with(Headers) { sniff(blank().withFat16BootSector("FLOPPY")) }
        assertEquals(ImageKind.FAT_VOLUME, fat16.kind)
        assertEquals("FLOPPY", fat16.volumeLabel)
        assertNull(with(Headers) { sniff(blank().withFat16BootSector("NO NAME")) }.volumeLabel)
    }

    @Test
    fun `blank and empty images`() {
        assertEquals(ImageKind.BLANK, sniff(Headers.blank(), 1L shl 30).kind)
        assertEquals(ImageKind.BLANK, sniff(ByteArray(0), 0).kind)
        assertEquals(ImageKind.BLANK, sniff(ByteArray(100), 100).kind)
    }

    @Test
    fun `small non-blank file is unknown`() {
        assertEquals(ImageKind.UNKNOWN, sniff("hello".encodeToByteArray(), 5).kind)
    }

    @Test
    fun `header size must match the file`() {
        assertThrows<IllegalArgumentException> { ImageSniffer.sniff(ByteArray(10), 20) }
        assertThrows<IllegalArgumentException> { ImageSniffer.sniff(ByteArray(100), 1L shl 30) }
        assertThrows<IllegalArgumentException> { ImageSniffer.sniff(ByteArray(Headers.SIZE + 1), 1L shl 30) }
        assertThrows<IllegalArgumentException> { ImageSniffer.sniff(ByteArray(0), -1) }
    }

    @Test
    fun `recommended modes follow SPEC and content`() {
        val iso = with(Headers) { sniff(blank().withIso()) }
        val hybrid = with(Headers) { sniff(blank().withIso().withGpt()) }
        val mbr = with(Headers) { sniff(blank().withMbr(MbrPartition(false, 0x83, 2048, 1000))) }
        val unknown = sniff("hello".encodeToByteArray(), 5)
        val blank = sniff(Headers.blank(), 1L shl 30)
        val cases =
            listOf(
                Triple("ubuntu.iso", iso, LunMode.CDROM),
                Triple("ubuntu.img", iso, LunMode.CDROM), // pure ISO9660 only works as a CD
                Triple("ubuntu.ISO", hybrid, LunMode.CDROM),
                Triple("ubuntu.img", hybrid, LunMode.DISK_RO),
                Triple("ubuntu", hybrid, LunMode.CDROM),
                Triple("disk.img", mbr, LunMode.DISK_RO),
                Triple("disk.iso", mbr, LunMode.DISK_RO), // a disk image misnamed .iso
                Triple("mystery.iso", unknown, LunMode.CDROM),
                Triple("mystery.bin", unknown, LunMode.DISK_RO),
                Triple("new.img", blank, LunMode.DISK_RO),
            )
        for ((name, info, mode) in cases) assertEquals(mode, ImageSniffer.recommendedMode(name, info), "$name ${info.kind}")
    }

    @Test
    fun `allowed modes - pure ISO is CD-ROM only, hybrid can be either`() {
        assertEquals(setOf(LunMode.CDROM), ImageSniffer.allowedModes(with(Headers) { sniff(blank().withIso()) }))
        assertEquals(LunMode.entries.toSet(), ImageSniffer.allowedModes(with(Headers) { sniff(blank().withIso().withGpt()) }))
        assertEquals(setOf(LunMode.DISK_RO, LunMode.DISK_RW), ImageSniffer.allowedModes(with(Headers) { sniff(blank().withGpt()) }))
    }

    @Test
    fun `recommendation is always allowed for every kind and name`() {
        val infos = ImageKind.entries.map { ImageInfo(it, 1L shl 20) }
        for (info in infos) {
            for (name in listOf("a.iso", "a.img", "a", "a.ISO", "a.raw", ".iso")) {
                assertTrue(ImageSniffer.recommendedMode(name, info) in ImageSniffer.allowedModes(info))
            }
        }
    }

    @Test
    fun `fuzz - arbitrary bytes never crash the sniffer`() =
        runBlocking<Unit> {
            checkAll(200, Arb.byteArray(Arb.int(0..4096), Arb.byte())) { bytes ->
                ImageSniffer.sniff(bytes, bytes.size.toLong())
            }
        }

    @Test
    fun `fuzz - corrupted valid headers never crash the sniffer`() =
        runBlocking<Unit> {
            val templates =
                with(Headers) {
                    listOf(
                        blank().withIso().withGpt(),
                        blank().withGpt(),
                        blank().withFat32BootSector(offset = 63 * 512).withMbr(MbrPartition(true, 0x0C, 63, 100)),
                    )
                }
            // Corrupt bytes in the regions the sniffer parses.
            val hotOffsets = Arb.int(0..2047)
            checkAll(300, Arb.element(templates), Arb.list(Arb.pair(hotOffsets, Arb.byte()), 1..16)) { template, edits ->
                val header = template.copyOf()
                for ((offset, value) in edits) header[offset] = value
                for ((offset, value) in edits) header[(63 * 512 + offset) % header.size] = value
                for ((offset, value) in edits) header[0x8000 + offset] = value
                ImageSniffer.sniff(header, Headers.ISO_SIZE_BYTES)
            }
        }
}
