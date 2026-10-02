package dev.jacobhayes.bootleg.core.image

/** Partition type bytes for a single-partition MBR. */
enum class MbrPartitionType(
    val code: Int,
) {
    FAT32_LBA(0x0C),
    EXFAT_NTFS(0x07),
    LINUX(0x83),
}

object Mbr {
    const val SECTOR_SIZE_BYTES = 512
    const val PARTITION_START_SECTOR = 2048L // 1 MiB alignment
    const val PARTITION_START_BYTES = PARTITION_START_SECTOR * SECTOR_SIZE_BYTES
    private const val SECTOR_COUNT_MAX = 0xFFFF_FFFFL // MBR caps a partition at 2 TiB

    /** The first sector of a disk with one partition spanning from 1 MiB to the end of the disk. */
    fun singlePartitionSector(
        diskSizeBytes: Long,
        type: MbrPartitionType,
    ): ByteArray {
        require(diskSizeBytes % SECTOR_SIZE_BYTES == 0L) { "disk size must be a multiple of 512: $diskSizeBytes" }
        val diskSectorCount = diskSizeBytes / SECTOR_SIZE_BYTES
        require(diskSectorCount > PARTITION_START_SECTOR) { "disk too small for a partition: $diskSizeBytes bytes" }
        val partitionSectorCount = diskSectorCount - PARTITION_START_SECTOR
        require(partitionSectorCount <= SECTOR_COUNT_MAX) { "MBR partitions are limited to 2 TiB" }

        val sector = ByteArray(SECTOR_SIZE_BYTES)
        val entry = 446
        sector[entry] = 0x00 // not bootable
        // CHS fields are unused by modern firmware; 0xFE 0xFF 0xFF marks "use LBA".
        sector[entry + 1] = 0xFE.toByte()
        sector[entry + 2] = 0xFF.toByte()
        sector[entry + 3] = 0xFF.toByte()
        sector[entry + 4] = type.code.toByte()
        sector[entry + 5] = 0xFE.toByte()
        sector[entry + 6] = 0xFF.toByte()
        sector[entry + 7] = 0xFF.toByte()
        writeUInt32LittleEndian(sector, entry + 8, PARTITION_START_SECTOR)
        writeUInt32LittleEndian(sector, entry + 12, partitionSectorCount)
        sector[510] = 0x55
        sector[511] = 0xAA.toByte()
        return sector
    }

    private fun writeUInt32LittleEndian(
        buffer: ByteArray,
        offset: Int,
        value: Long,
    ) {
        check(value in 0..SECTOR_COUNT_MAX)
        for (i in 0 until 4) buffer[offset + i] = (value ushr (8 * i)).toByte()
    }
}
