package dev.jacobhayes.bootleg.core.image

import dev.jacobhayes.bootleg.core.gadget.LunMode
import java.util.zip.CRC32

enum class ImageKind {
    /** ISO9660 only: must be exposed as a CD-ROM. */
    ISO9660,

    /** ISO9660 that also carries an MBR and/or GPT, so it boots as a CD or as a disk. */
    HYBRID_ISO,
    MBR_DISK,
    GPT_DISK,

    /** A FAT filesystem with no partition table ("superfloppy"). */
    FAT_VOLUME,

    /** Header is all zeros (fresh sparse image) or the file is empty. */
    BLANK,
    UNKNOWN,
}

data class MbrPartition(
    val bootable: Boolean,
    val typeCode: Int,
    val startSector: Long,
    val sectorCount: Long,
)

data class GptPartition(
    val typeGuid: String,
    val firstLba: Long,
    val lastLba: Long,
    val name: String,
)

sealed interface PartitionTable {
    data class Mbr(
        val partitions: List<MbrPartition>,
    ) : PartitionTable

    /** [partitions] lists the entries that fit in the sniffed header; [entryCount] is what the (validated) header declares. */
    data class Gpt(
        val entryCount: Int,
        val partitions: List<GptPartition>,
    ) : PartitionTable
}

data class ImageInfo(
    val kind: ImageKind,
    val sizeBytes: Long,
    /** El Torito boot record present (ISO is CD-bootable). */
    val elTorito: Boolean = false,
    val volumeLabel: String? = null,
    val partitionTable: PartitionTable? = null,
) {
    val isHybrid: Boolean get() = kind == ImageKind.HYBRID_ISO
}

/** Classifies an image from its first bytes; never reads the file itself (the caller does bounded I/O). */
object ImageSniffer {
    /** Callers pass the first min(file size, this) bytes. Covers ISO descriptors in sectors 16..31 and a full GPT entry array. */
    const val HEADER_SIZE_BYTES = 64 * 1024

    private const val SECTOR_SIZE_BYTES = 512
    private const val ISO_SECTOR_SIZE_BYTES = 2048
    private const val ISO_FIRST_DESCRIPTOR_SECTOR = 16
    private const val ISO_DESCRIPTOR_BOOT_RECORD = 0
    private const val ISO_DESCRIPTOR_PRIMARY = 1
    private const val ISO_DESCRIPTOR_TERMINATOR = 255
    private const val MBR_PARTITION_TABLE_OFFSET = 446
    private const val MBR_PARTITION_ENTRY_SIZE_BYTES = 16
    private const val MBR_PARTITION_ENTRY_COUNT = 4
    private const val GPT_HEADER_OFFSET = 512
    private const val GPT_REVISION_1_0 = 0x0001_0000L
    private const val GPT_HEADER_SIZE_BYTES_MIN = 92
    private const val GPT_ENTRY_SIZE_BYTES = 128L
    private const val GPT_ENTRY_COUNT_MAX = 1024
    private const val GPT_NAME_SIZE_BYTES = 72
    private val ISO_STANDARD_IDENTIFIER = "CD001".encodeToByteArray()
    private val EL_TORITO_IDENTIFIER = "EL TORITO SPECIFICATION".encodeToByteArray()
    private val GPT_SIGNATURE = "EFI PART".encodeToByteArray()

    fun sniff(
        header: ByteArray,
        fileSizeBytes: Long,
    ): ImageInfo {
        require(fileSizeBytes >= 0) { "negative file size: $fileSizeBytes" }
        require(header.size <= HEADER_SIZE_BYTES) { "header is ${header.size} bytes; pass at most $HEADER_SIZE_BYTES" }
        require(header.size.toLong() == minOf(fileSizeBytes, HEADER_SIZE_BYTES.toLong())) {
            "header must be the first min(file size, $HEADER_SIZE_BYTES) bytes: got ${header.size} of $fileSizeBytes"
        }
        if (header.all { it == 0.toByte() }) return ImageInfo(ImageKind.BLANK, fileSizeBytes)

        val iso = readIsoDescriptors(header)
        val gpt = readGpt(header)
        val fatLabel = fatVolumeLabel(header, 0)
        val mbr = if (fatLabel == null) readMbr(header) else null
        val partitionTable = gpt ?: mbr

        if (iso != null) {
            val kind = if (partitionTable != null) ImageKind.HYBRID_ISO else ImageKind.ISO9660
            return ImageInfo(kind, fileSizeBytes, iso.elTorito, iso.volumeLabel, partitionTable)
        }
        if (fatLabel != null) return ImageInfo(ImageKind.FAT_VOLUME, fileSizeBytes, volumeLabel = fatLabel.ifEmpty { null })
        if (gpt != null) return ImageInfo(ImageKind.GPT_DISK, fileSizeBytes, partitionTable = gpt)
        if (mbr != null) {
            val label = mbr.partitions.firstNotNullOfOrNull { partitionFatLabel(header, it) }
            return ImageInfo(ImageKind.MBR_DISK, fileSizeBytes, volumeLabel = label?.ifEmpty { null }, partitionTable = mbr)
        }
        return ImageInfo(ImageKind.UNKNOWN, fileSizeBytes)
    }

    /** Modes the user may pick: a pure ISO9660 image only makes sense as a CD-ROM. */
    fun allowedModes(info: ImageInfo): Set<LunMode> =
        when (info.kind) {
            ImageKind.ISO9660 -> setOf(LunMode.CDROM)
            ImageKind.HYBRID_ISO, ImageKind.UNKNOWN -> LunMode.entries.toSet()
            ImageKind.MBR_DISK, ImageKind.GPT_DISK, ImageKind.FAT_VOLUME, ImageKind.BLANK -> setOf(LunMode.DISK_RO, LunMode.DISK_RW)
        }

    /** SPEC: `.iso` → CD-ROM, `.img` → read-only disk; content wins where the name would not work. */
    fun recommendedMode(
        fileName: String,
        info: ImageInfo,
    ): LunMode {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mode =
            when (info.kind) {
                ImageKind.ISO9660 -> LunMode.CDROM
                ImageKind.HYBRID_ISO -> if (extension == "img") LunMode.DISK_RO else LunMode.CDROM
                ImageKind.UNKNOWN -> if (extension == "iso") LunMode.CDROM else LunMode.DISK_RO
                ImageKind.MBR_DISK, ImageKind.GPT_DISK, ImageKind.FAT_VOLUME, ImageKind.BLANK -> LunMode.DISK_RO
            }
        check(mode in allowedModes(info)) { "recommended $mode is not allowed for ${info.kind}" }
        return mode
    }

    private data class IsoDescriptors(
        val volumeLabel: String?,
        val elTorito: Boolean,
    )

    private fun readIsoDescriptors(header: ByteArray): IsoDescriptors? {
        var volumeLabel: String? = null
        var primaryFound = false
        var elTorito = false
        val sectorCountMax = header.size / ISO_SECTOR_SIZE_BYTES
        for (sector in ISO_FIRST_DESCRIPTOR_SECTOR until sectorCountMax) {
            val offset = sector * ISO_SECTOR_SIZE_BYTES
            if (!header.matchesAt(offset + 1, ISO_STANDARD_IDENTIFIER)) break
            when (header[offset].toInt() and 0xFF) {
                ISO_DESCRIPTOR_PRIMARY -> {
                    primaryFound = true
                    volumeLabel = header.asciiAt(offset + 40, 32).ifEmpty { null }
                }
                ISO_DESCRIPTOR_BOOT_RECORD -> elTorito = elTorito || header.matchesAt(offset + 7, EL_TORITO_IDENTIFIER)
                ISO_DESCRIPTOR_TERMINATOR -> break
            }
        }
        return if (primaryFound) IsoDescriptors(volumeLabel, elTorito) else null
    }

    private fun hasBootSignature(header: ByteArray): Boolean =
        header.size >= SECTOR_SIZE_BYTES && header[510] == 0x55.toByte() && header[511] == 0xAA.toByte()

    private fun readMbr(header: ByteArray): PartitionTable.Mbr? {
        if (!hasBootSignature(header)) return null
        val partitions = mutableListOf<MbrPartition>()
        for (index in 0 until MBR_PARTITION_ENTRY_COUNT) {
            val entry = MBR_PARTITION_TABLE_OFFSET + index * MBR_PARTITION_ENTRY_SIZE_BYTES
            val status = header[entry].toInt() and 0xFF
            if (status != 0x00 && status != 0x80) return null // not a partition table (likely a VBR or boot code)
            val typeCode = header[entry + 4].toInt() and 0xFF
            val sectorCount = header.uInt32LittleEndianAt(entry + 12)
            if (typeCode == 0 || sectorCount == 0L) continue
            partitions += MbrPartition(status == 0x80, typeCode, header.uInt32LittleEndianAt(entry + 8), sectorCount)
        }
        // 55 AA with four empty entries is boot code without a partition table (common in ISO system areas).
        return if (partitions.isEmpty()) null else PartitionTable.Mbr(partitions)
    }

    /**
     * A GPT only counts when its header validates (signature, revision 1.0, size, CRC32, own LBA,
     * 128-byte entries, bounded entry count, sane usable range); "EFI PART" alone can be junk in an
     * ISO's system area.
     */
    private fun readGpt(header: ByteArray): PartitionTable.Gpt? {
        if (header.size < 2 * SECTOR_SIZE_BYTES || !header.matchesAt(GPT_HEADER_OFFSET, GPT_SIGNATURE)) return null
        val revision = header.uInt32LittleEndianAt(GPT_HEADER_OFFSET + 8)
        val headerSizeBytes = header.uInt32LittleEndianAt(GPT_HEADER_OFFSET + 12)
        val headerCrc32 = header.uInt32LittleEndianAt(GPT_HEADER_OFFSET + 16)
        val headerLba = header.uInt64LittleEndianAt(GPT_HEADER_OFFSET + 24)
        val firstUsableLba = header.uInt64LittleEndianAt(GPT_HEADER_OFFSET + 40)
        val lastUsableLba = header.uInt64LittleEndianAt(GPT_HEADER_OFFSET + 48)
        val entriesLba = header.uInt64LittleEndianAt(GPT_HEADER_OFFSET + 72)
        val entryCount = header.uInt32LittleEndianAt(GPT_HEADER_OFFSET + 80)
        val entrySizeBytes = header.uInt32LittleEndianAt(GPT_HEADER_OFFSET + 84)
        val valid =
            revision == GPT_REVISION_1_0 &&
                headerSizeBytes in GPT_HEADER_SIZE_BYTES_MIN.toLong()..SECTOR_SIZE_BYTES.toLong() &&
                gptHeaderCrc32(header, headerSizeBytes.toInt()) == headerCrc32 &&
                headerLba == 1L &&
                entrySizeBytes == GPT_ENTRY_SIZE_BYTES &&
                entryCount in 1L..GPT_ENTRY_COUNT_MAX.toLong() &&
                // Negative values are ≥ 2^63 on disk; the entry array sits between the header and the usable area.
                entriesLba >= 2 &&
                firstUsableLba > entriesLba &&
                lastUsableLba >= firstUsableLba
        if (!valid) return null
        val partitions = mutableListOf<GptPartition>()
        for (index in 0 until entryCount.toInt()) {
            val offset = entriesLba * SECTOR_SIZE_BYTES + index * entrySizeBytes
            if (offset < 0 || offset + entrySizeBytes > header.size) break
            val entry = offset.toInt()
            if ((0 until 16).all { header[entry + it] == 0.toByte() }) continue // unused entry
            partitions +=
                GptPartition(
                    typeGuid = header.guidAt(entry),
                    firstLba = header.uInt64LittleEndianAt(entry + 32),
                    lastLba = header.uInt64LittleEndianAt(entry + 40),
                    name = header.utf16LittleEndianAt(entry + 56, GPT_NAME_SIZE_BYTES),
                )
        }
        return PartitionTable.Gpt(entryCount.toInt(), partitions)
    }

    /** CRC32 of the GPT header with its own CRC field zeroed, per UEFI 2.x §5.3.2. */
    private fun gptHeaderCrc32(
        header: ByteArray,
        headerSizeBytes: Int,
    ): Long {
        val bytes = header.copyOfRange(GPT_HEADER_OFFSET, GPT_HEADER_OFFSET + headerSizeBytes)
        bytes.fill(0, 16, 20)
        return CRC32().apply { update(bytes) }.value
    }

    private fun partitionFatLabel(
        header: ByteArray,
        partition: MbrPartition,
    ): String? {
        val offset = partition.startSector * SECTOR_SIZE_BYTES
        return if (offset + SECTOR_SIZE_BYTES <= header.size) fatVolumeLabel(header, offset.toInt()) else null
    }

    /** Volume label from a FAT boot sector at [offset], "" if unlabeled, or null if it isn't one. */
    private fun fatVolumeLabel(
        header: ByteArray,
        offset: Int,
    ): String? {
        if (offset + SECTOR_SIZE_BYTES > header.size) return null
        val jump = header[offset].toInt() and 0xFF
        if (jump != 0xEB && jump != 0xE9) return null
        if (header[offset + 510] != 0x55.toByte() || header[offset + 511] != 0xAA.toByte()) return null
        val bytesPerSector = header.uInt16LittleEndianAt(offset + 11)
        if (bytesPerSector !in setOf(512, 1024, 2048, 4096)) return null
        val label =
            when {
                header.asciiAt(offset + 0x52, 8) == "FAT32" -> header.asciiAt(offset + 0x47, 11)
                header.asciiAt(offset + 0x36, 8).startsWith("FAT") -> header.asciiAt(offset + 0x2B, 11)
                else -> return null
            }
        return if (label == "NO NAME") "" else label
    }

    private fun ByteArray.matchesAt(
        offset: Int,
        expected: ByteArray,
    ): Boolean = offset >= 0 && offset + expected.size <= size && expected.indices.all { this[offset + it] == expected[it] }

    /** Space-padded ASCII field, trimmed; non-printable bytes end the field. */
    private fun ByteArray.asciiAt(
        offset: Int,
        length: Int,
    ): String =
        (offset until offset + length)
            .map { this[it].toInt() and 0xFF }
            .takeWhile { it in 0x20..0x7E }
            .joinToString("") { it.toChar().toString() }
            .trimEnd()

    private fun ByteArray.utf16LittleEndianAt(
        offset: Int,
        length: Int,
    ): String = String(this, offset, length, Charsets.UTF_16LE).substringBefore('\u0000')

    private fun ByteArray.uInt16LittleEndianAt(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.uInt32LittleEndianAt(offset: Int): Long =
        (0 until 4).fold(0L) { value, i ->
            value or
                ((this[offset + i].toLong() and 0xFF) shl (8 * i))
        }

    /** Values ≥ 2^63 come back negative; callers treat them as out of range. */
    private fun ByteArray.uInt64LittleEndianAt(offset: Int): Long =
        (0 until 8).fold(0L) { value, i ->
            value or
                ((this[offset + i].toLong() and 0xFF) shl (8 * i))
        }

    /** Mixed-endian GUID text form: first three fields little-endian, last two big-endian. */
    private fun ByteArray.guidAt(offset: Int): String {
        fun hex(vararg indices: Int) = indices.joinToString("") { "%02X".format(this[offset + it].toInt() and 0xFF) }
        return "${hex(3, 2, 1, 0)}-${hex(5, 4)}-${hex(7, 6)}-${hex(8, 9)}-${hex(10, 11, 12, 13, 14, 15)}"
    }
}
