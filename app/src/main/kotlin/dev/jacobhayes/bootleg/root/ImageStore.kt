package dev.jacobhayes.bootleg.root

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.jacobhayes.bootleg.BlankImageFormat
import dev.jacobhayes.bootleg.core.image.Mbr
import dev.jacobhayes.bootleg.core.image.MbrPartitionType
import java.io.File
import java.io.FileDescriptor

/**
 * The image store at [ImagePaths.STORE_DIR], operated on as root. Every operation pins the store
 * directory first ([ConfinedFiles.openDirectory]: no symlink anywhere below the media root) and then
 * works by name inside that fd, so a store directory swapped for a symlink can't redirect a create,
 * format, sweep or read. External programs get `/proc/<pid>/fd/<n>` of a file opened that way.
 */
class ImageStore(
    private val storePath: String = ImagePaths.STORE_DIR,
    private val roots: List<String> = ImagePaths.allowedRoots,
) {
    fun list(): List<RootImage> =
        withStore { store ->
            val names = File(ConfinedFiles.selfPath(store)).list() ?: return@withStore emptyList()
            names
                .asSequence()
                .filter { IMAGE_EXTENSION.containsMatchIn(it) }
                .take(IMAGE_COUNT_MAX)
                .mapNotNull { name -> describe(store, name) }
                .sortedBy { it.displayName.lowercase() }
                .toList()
        } ?: emptyList()

    /** Deletes an image (and its `.sha256` sidecar) from the store. Refuses files the gadget exports. */
    fun delete(
        path: String,
        exportedPaths: Set<String>,
    ) {
        val confined = ConfinedPath.parse(ImagePaths.toKernelPath(path), listOf("$storePath/"))
        require(IMAGE_EXTENSION.containsMatchIn(confined.name)) { "not an image: $path" }
        require(confined.path !in exportedPaths) { "image is exported; eject it first: $path" }
        ConfinedFiles.deleteImage(confined)
    }

    fun createBlank(
        name: String,
        sizeBytes: Long,
        format: BlankImageFormat,
        preallocate: Boolean,
    ): RootImage {
        require(BLANK_NAME.matches(name) && !name.startsWith(".")) { "name must match [A-Za-z0-9._-], ≤ 100 chars, end with .img: '$name'" }
        require(sizeBytes % Mbr.SECTOR_SIZE_BYTES == 0L) { "size must be a multiple of ${Mbr.SECTOR_SIZE_BYTES} bytes" }
        require(sizeBytes in minimumSizeBytes(format)..SIZE_BYTES_MAX) {
            "size for $format must be between ${minimumSizeBytes(format)} and $SIZE_BYTES_MAX bytes"
        }
        ensureStoreDirectory()
        return checkNotNull(withStore { store -> createIn(store, name, sizeBytes, format, preallocate) }) { "store directory vanished" }
    }

    private fun createIn(
        store: ParcelFileDescriptor,
        name: String,
        sizeBytes: Long,
        format: BlankImageFormat,
        preallocate: Boolean,
    ): RootImage {
        val freeBytes = Os.fstatvfs(store.fileDescriptor).let { it.f_bavail * it.f_frsize }
        val requiredBytes = if (preallocate) sizeBytes + FREE_SPACE_MARGIN_BYTES else FREE_SPACE_MARGIN_BYTES
        check(freeBytes >= requiredBytes) { "not enough free space: $freeBytes bytes free, $requiredBytes needed" }

        val image = ConfinedFiles.openIn(store, name, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL, FILE_MODE)
        try {
            val descriptor = image.fileDescriptor
            if (preallocate) Os.posix_fallocate(descriptor, 0, sizeBytes) else Os.ftruncate(descriptor, sizeBytes)
            partitionType(format)?.let { writeFully(descriptor, Mbr.singlePartitionSector(sizeBytes, it)) }
            Os.fsync(descriptor)
            if (format != BlankImageFormat.RAW) formatPartition(image, sizeBytes, format)
            adoptOwnership(image, FILE_MODE, reference = store)
        } catch (e: Exception) {
            // By name inside the pinned store; unlink never follows a symlink at the name.
            runCatching { Os.remove(ConfinedFiles.childPath(store, name)) }.onFailure { e.addSuppressed(it) }
            throw e
        } finally {
            image.close()
        }
        return checkNotNull(describe(store, name)) { "created image vanished: $name" }
    }

    /** Detaches loop devices left behind by a crash during formatting. */
    fun sweepStaleLoopDevices() {
        withStore { store ->
            val names = File(ConfinedFiles.selfPath(store)).list() ?: return@withStore
            for (name in names.filter { it.endsWith(".img") }.take(IMAGE_COUNT_MAX)) {
                val image =
                    try {
                        openRegular(store, name)
                    } catch (_: ErrnoException) {
                        continue
                    } ?: continue
                image.use { detachLoopDevices(processPath(it)) }
            }
        }
    }

    /** Detaches every loop device backed by [imagePath] (toybox `losetup -a` shows no file names, so ask per file). */
    private fun detachLoopDevices(imagePath: String) {
        val output = RootCommands.run(listOf("losetup", "-j", imagePath))
        if (output.exitCode != 0) return
        output.text
            .lineSequence()
            .mapNotNull { LOOP_DEVICE_IN_LINE.find(it)?.value }
            .take(LOOP_DEVICE_COUNT_MAX)
            .forEach { device -> RootCommands.run(listOf("losetup", "-d", device)) }
    }

    /**
     * Attaches the partition of [imagePath] to a free loop device. Any attach that doesn't come back as
     * one clean device name (odd output, failure, timeout) is cleaned up by file before failing.
     */
    private fun attachPartition(
        imagePath: String,
        partitionBytes: Long,
    ): String {
        val output =
            try {
                RootCommands.run(
                    listOf("losetup", "-f", "-s", "-o", Mbr.PARTITION_START_BYTES.toString(), "-S", partitionBytes.toString(), imagePath),
                )
            } catch (e: Exception) {
                runCatching { detachLoopDevices(imagePath) }.onFailure { e.addSuppressed(it) }
                throw e
            }
        val device = output.text.trim()
        if (output.exitCode != 0 || !LOOP_DEVICE.matches(device)) {
            detachLoopDevices(imagePath)
            error("losetup exited ${output.exitCode} with unexpected output: '${device.take(200)}'")
        }
        return device
    }

    /** Creates the store directory with the owner, mode and SELinux label of shared storage; refuses a symlink there. */
    fun ensureStoreDirectory() {
        if (withStore { true } == true) return
        val parentPath = storePath.substringBeforeLast('/')
        check("$parentPath/" in roots) { "the store must sit directly in an allowed root: $storePath" }
        // The media root itself is a system directory (trusted); mkdir never follows a symlink at the name.
        try {
            Os.mkdir(storePath, DIRECTORY_MODE)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EEXIST) throw e
        }
        ParcelFileDescriptor.open(File(parentPath), ParcelFileDescriptor.MODE_READ_ONLY).use { parent ->
            ConfinedFiles.openDirectory(storePath, roots).use { store -> adoptOwnership(store, DIRECTORY_MODE, reference = parent) }
        }
    }

    /** Runs [block] with the store directory pinned; null if it doesn't exist yet. A symlink there is rejected. */
    private fun <T> withStore(block: (store: ParcelFileDescriptor) -> T): T? {
        val store =
            try {
                ConfinedFiles.openDirectory(storePath, roots)
            } catch (e: IllegalArgumentException) {
                if ((e.cause as? ErrnoException)?.errno == OsConstants.ENOENT) return null
                throw e
            }
        return store.use(block)
    }

    private fun formatPartition(
        image: ParcelFileDescriptor,
        sizeBytes: Long,
        format: BlankImageFormat,
    ) {
        val partitionBytes = sizeBytes - Mbr.PARTITION_START_BYTES
        val imagePath = processPath(image)
        val device = attachPartition(imagePath, partitionBytes)
        try {
            val argv =
                when (format) {
                    BlankImageFormat.FAT32 -> {
                        // newfs_msdos defaults to 4 KiB clusters, too few for FAT32 below ~260 MiB; use 512 B there.
                        val smallVolume = partitionBytes / FAT32_DEFAULT_CLUSTER_BYTES < FAT32_CLUSTER_COUNT_MIN * 2
                        listOf("newfs_msdos", "-F", "32", "-L", VOLUME_LABEL, "-o", Mbr.PARTITION_START_SECTOR.toString()) +
                            (if (smallVolume) listOf("-c", "1") else emptyList()) +
                            device
                    }
                    BlankImageFormat.EXFAT -> listOf("mkfs.exfat", "-L", VOLUME_LABEL, device)
                    BlankImageFormat.EXT4 -> listOf("mke2fs", "-t", "ext4", "-L", VOLUME_LABEL.lowercase(), device)
                    BlankImageFormat.RAW -> error("raw images are not formatted")
                }
            RootCommands.runChecked(argv, FORMAT_TIMEOUT_MS)
        } finally {
            RootCommands.run(listOf("losetup", "-d", device))
        }
    }

    private fun describe(
        store: ParcelFileDescriptor,
        name: String,
    ): RootImage? {
        val stat =
            try {
                Os.lstat(ConfinedFiles.childPath(store, name))
            } catch (_: ErrnoException) {
                return null
            }
        if (!OsConstants.S_ISREG(stat.st_mode)) return null
        return RootImage(
            path = "$storePath/$name",
            displayName = name,
            sizeBytes = stat.st_size,
            allocatedBytes = stat.st_blocks * STAT_BLOCK_BYTES,
            verifiedSha256 = readSidecar(store, "$name.sha256"),
        )
    }

    /** The sidecar's hash: opened without following a symlink, a regular file, at most [Sidecar.SIZE_BYTES_MAX] bytes read. */
    private fun readSidecar(
        store: ParcelFileDescriptor,
        name: String,
    ): String? {
        val sidecar =
            try {
                openRegular(store, name)
            } catch (_: ErrnoException) {
                return null
            } ?: return null
        return sidecar.use {
            Sidecar.readBounded { buffer, offset, count -> Os.read(it.fileDescriptor, buffer, offset, count) }?.let(Sidecar::parse)
        }
    }

    /** Opens [name] in [store] read-only (O_NOFOLLOW, O_NONBLOCK so a FIFO can't hang us); null unless it is a regular file. */
    private fun openRegular(
        store: ParcelFileDescriptor,
        name: String,
    ): ParcelFileDescriptor? {
        val file = ConfinedFiles.openIn(store, name, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK)
        if (!OsConstants.S_ISREG(Os.fstat(file.fileDescriptor).st_mode)) {
            file.close()
            return null
        }
        return file
    }

    /** A path another process (losetup) resolves to [file]'s inode: our fd, through /proc. */
    private fun processPath(file: ParcelFileDescriptor): String = "/proc/${Os.getpid()}/fd/${file.fd}"

    /** Copies owner, group and SELinux label from [reference] so MediaProvider/MTP and the app can see [target]. */
    private fun adoptOwnership(
        target: ParcelFileDescriptor,
        mode: Int,
        reference: ParcelFileDescriptor,
    ) {
        val stat = Os.fstat(reference.fileDescriptor)
        Os.fchown(target.fileDescriptor, stat.st_uid, stat.st_gid)
        Os.fchmod(target.fileDescriptor, mode)
        // Os has no fsetxattr/fgetxattr; the fd magic links name exactly these inodes.
        Os.setxattr(ConfinedFiles.selfPath(target), SELINUX_XATTR, Os.getxattr(ConfinedFiles.selfPath(reference), SELINUX_XATTR), 0)
    }

    private fun writeFully(
        descriptor: FileDescriptor,
        bytes: ByteArray,
    ) {
        var written = 0
        while (written < bytes.size) {
            val count = Os.pwrite(descriptor, bytes, written, bytes.size - written, written.toLong())
            check(count > 0) { "pwrite made no progress" }
            written += count
        }
    }

    private fun partitionType(format: BlankImageFormat): MbrPartitionType? =
        when (format) {
            BlankImageFormat.RAW -> null
            BlankImageFormat.FAT32 -> MbrPartitionType.FAT32_LBA
            BlankImageFormat.EXFAT -> MbrPartitionType.EXFAT_NTFS
            BlankImageFormat.EXT4 -> MbrPartitionType.LINUX
        }

    private fun minimumSizeBytes(format: BlankImageFormat): Long =
        when (format) {
            BlankImageFormat.RAW -> MIB
            BlankImageFormat.FAT32 -> 33 * MIB + Mbr.PARTITION_START_BYTES
            BlankImageFormat.EXFAT, BlankImageFormat.EXT4 -> 8 * MIB
        }

    companion object {
        private const val MIB = 1024L * 1024
        const val SIZE_BYTES_MAX = 2L * 1024 * 1024 * MIB // MBR caps partitions at 2 TiB
        private const val FREE_SPACE_MARGIN_BYTES = 64 * MIB
        private const val FORMAT_TIMEOUT_MS = 120_000L
        private const val FAT32_DEFAULT_CLUSTER_BYTES = 4_096L
        private const val FAT32_CLUSTER_COUNT_MIN = 65_525L
        private const val IMAGE_COUNT_MAX = 1_000
        private const val STAT_BLOCK_BYTES = 512L
        private const val VOLUME_LABEL = "BOOTLEG"
        private const val SELINUX_XATTR = "security.selinux"
        private const val FILE_MODE = 0b110_110_000 // 0660, like MediaProvider-created files
        private const val DIRECTORY_MODE = 0b010_111_111_000 // 02770 (setgid media_rw, like shared storage)
        private val BLANK_NAME = Regex("[A-Za-z0-9._-]{1,96}\\.img")
        private val IMAGE_EXTENSION = Regex("\\.(iso|img)$", RegexOption.IGNORE_CASE)
        private const val LOOP_DEVICE_COUNT_MAX = 64
        private val LOOP_DEVICE = Regex("/dev/block/loop\\d{1,6}")
        private val LOOP_DEVICE_IN_LINE = Regex("/dev/block/loop\\d{1,6}(?=[:\\s]|$)")
    }
}

/** `.sha256` sidecars: bounded reads and parsing, kept free of Android calls so they test on the JVM. */
object Sidecar {
    const val SIZE_BYTES_MAX = 4_096
    private val SHA256 = Regex("\\b[0-9a-fA-F]{64}\\b")

    /**
     * Reads through [read] (like read(2): bytes read, 0 at end of file) until end of file, but never
     * more than [SIZE_BYTES_MAX] + 1 bytes, so a file that grows while it's read can't make us read
     * more. Null if the file is larger than [SIZE_BYTES_MAX].
     */
    fun readBounded(read: (buffer: ByteArray, offset: Int, count: Int) -> Int): ByteArray? {
        val buffer = ByteArray(SIZE_BYTES_MAX + 1)
        var size = 0
        while (size < buffer.size) {
            val count = read(buffer, size, buffer.size - size)
            check(count >= 0) { "read returned $count" }
            if (count == 0) break
            size += count
        }
        return if (size > SIZE_BYTES_MAX) null else buffer.copyOf(size)
    }

    /** The first SHA-256 hex digest in [bytes], lower-cased, or null. */
    fun parse(bytes: ByteArray): String? = SHA256.find(bytes.toString(Charsets.UTF_8))?.value?.lowercase()
}
