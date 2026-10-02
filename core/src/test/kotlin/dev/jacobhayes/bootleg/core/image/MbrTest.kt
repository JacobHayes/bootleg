package dev.jacobhayes.bootleg.core.image

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MbrTest {
    private val mebibyte = 1024L * 1024

    @Test
    fun `64 MiB FAT32 sector is byte exact`() {
        val sector = Mbr.singlePartitionSector(64 * mebibyte, MbrPartitionType.FAT32_LBA)
        val expected = ByteArray(512)
        // status, CHS start (LBA marker), type, CHS end, LBA start 2048, sector count 131072 - 2048 = 0x1F800
        val entry = listOf(0x00, 0xFE, 0xFF, 0xFF, 0x0C, 0xFE, 0xFF, 0xFF, 0x00, 0x08, 0x00, 0x00, 0x00, 0xF8, 0x01, 0x00)
        entry.forEachIndexed { index, value -> expected[446 + index] = value.toByte() }
        expected[510] = 0x55
        expected[511] = 0xAA.toByte()
        assertArrayEquals(expected, sector)
    }

    @Test
    fun `partition type byte follows the requested type`() {
        for (type in MbrPartitionType.entries) {
            assertEquals(type.code.toByte(), Mbr.singlePartitionSector(64 * mebibyte, type)[446 + 4])
        }
        assertEquals(listOf(0x0C, 0x07, 0x83), MbrPartitionType.entries.map { it.code })
    }

    @Test
    fun `only the first partition entry is used`() {
        val sector = Mbr.singlePartitionSector(64 * mebibyte, MbrPartitionType.LINUX)
        assertEquals(0, (0 until 446).count { sector[it] != 0.toByte() }, "boot code area is zero")
        assertEquals(0, (462 until 510).count { sector[it] != 0.toByte() }, "entries 2-4 are zero")
    }

    @Test
    fun `smallest disk has a one sector partition`() {
        val sector = Mbr.singlePartitionSector(Mbr.PARTITION_START_BYTES + 512, MbrPartitionType.LINUX)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), sector.copyOfRange(446 + 12, 446 + 16))
    }

    @Test
    fun `largest disk has a 0xFFFFFFFF sector partition`() {
        val diskSizeBytes = (Mbr.PARTITION_START_SECTOR + 0xFFFF_FFFFL) * 512
        val sector = Mbr.singlePartitionSector(diskSizeBytes, MbrPartitionType.EXFAT_NTFS)
        assertArrayEquals(ByteArray(4) { 0xFF.toByte() }, sector.copyOfRange(446 + 12, 446 + 16))
        assertThrows<IllegalArgumentException> { Mbr.singlePartitionSector(diskSizeBytes + 512, MbrPartitionType.EXFAT_NTFS) }
    }

    @Test
    fun `invalid sizes are rejected`() {
        assertThrows<IllegalArgumentException> { Mbr.singlePartitionSector(64 * mebibyte + 1, MbrPartitionType.LINUX) }
        assertThrows<IllegalArgumentException> { Mbr.singlePartitionSector(Mbr.PARTITION_START_BYTES, MbrPartitionType.LINUX) }
        assertThrows<IllegalArgumentException> { Mbr.singlePartitionSector(0, MbrPartitionType.LINUX) }
        assertThrows<IllegalArgumentException> { Mbr.singlePartitionSector(-512, MbrPartitionType.LINUX) }
    }

    @Test
    fun `sniffer reads back what the writer wrote`() {
        val diskSizeBytes = 64 * mebibyte
        val header = ByteArray(ImageSniffer.HEADER_SIZE_BYTES)
        Mbr.singlePartitionSector(diskSizeBytes, MbrPartitionType.FAT32_LBA).copyInto(header)
        val info = ImageSniffer.sniff(header, diskSizeBytes)
        assertEquals(ImageKind.MBR_DISK, info.kind)
        assertEquals(PartitionTable.Mbr(listOf(MbrPartition(false, 0x0C, 2048, 131072 - 2048))), info.partitionTable)
    }
}
