# shellcheck shell=bash disable=SC2034 # sourced by scripts/e2e-ui.sh, which uses the variables
# Blank image tests: Library -> Create blank image for each format: exFAT 1 GB (sparse), ext4 1 GB,
# FAT32 64 MB preallocated, Raw 64 MB. Each is checked as root on the phone (MBR partition at
# 1 MiB: blkid + fsck -n on a read-only loop device; allocation; owner/group/label like the store)
# and deleted through the UI again. An exFAT image is exposed read/write: the Mac mounts it
# writable, a file written there is found intact on the phone after the Mac ejects it. ext4 and Raw
# are never exposed (macOS can't read them and would show a modal dialog). Sourced by
# scripts/e2e-ui.sh.

define_test blank.exfat "create exFAT 1 GB (preset): size, clean filesystem, sparse, owner/label; delete"
define_test blank.ext4 "create ext4 1 GB (preset): size, clean filesystem, sparse, owner/label; delete"
define_test blank.fat32-preallocated "create FAT32 64 MB preallocated (custom size): size, clean filesystem, allocated, owner/label; delete"
define_test blank.raw "create Raw 64 MB (custom size): size, sparse, zeroed, owner/label; delete"
define_test blank.exfat-read-write "exFAT exposed read/write: Mac writes a file, ejects, the phone reads it intact"

BLANK_SMALL_BYTES=64000000
BLANK_LARGE_BYTES=1000000000
BLANK_MOUNT_WAIT_S=25
BLANK_MNT=/data/local/tmp/bootleg-e2e-blank
BLANK_VOLUME=BOOTLEG

# fs_check IMAGE TYPE: blkid type at the 1 MiB partition and a clean read-only fsck.
fs_check() {
    local image=$STORE/$1 type=$2 fsck
    case $type in
        exfat) fsck="fsck.exfat -n" ;;
        ext4) fsck="e2fsck -n" ;;
        vfat) fsck="fsck_msdos -n" ;;
    esac
    root "L=\$(losetup -f -s -r -o 1048576 $image) || exit 1; blkid \$L | grep -q TYPE=\\\"$type\\\" && $fsck \$L >/dev/null 2>&1; s=\$?; losetup -d \$L; exit \$s"
}
size_is() { [[ $(root "stat -c %s $STORE/$1") == "$2" ]]; }
allocated_kib() { root "du -k $STORE/$1" | awk '{ print $1 }'; }
owned_like_store() { [[ $(root "stat -c \"%u %g %C\" $STORE") == "$(root "stat -c \"%u %g %C\" $STORE/$1")" ]] && [[ $(root "stat -c %a $STORE/$1") == 660 ]]; }
raw_is_zero() { [[ $(root "cmp -n 1048576 $STORE/$1 /dev/zero >/dev/null 2>&1 && echo zero") == zero ]]; }

# blank_test_start IMAGE: no leftover from an earlier run; deleted again however the test ends.
blank_test_start() {
    on_cleanup "delete_from_library $1"
    delete_from_library "$1"
}

# delete_blank IMAGE: deletes through the UI and checks the store as root. The app's /sdcard (FUSE)
# view can keep a stale entry after a root delete (findings O10); that is reported, not failed.
delete_blank() {
    delete_from_library "$1"
    check "$1 deleted" bash -c "[[ -z \$(adb shell \"su -c 'ls $STORE/$1 2>/dev/null'\") ]]"
    if adb shell ls "/sdcard/bootleg/$1" >/dev/null 2>&1; then
        log "  WARN  $1 still resolves through /sdcard (stale FUSE entry, O10)"
    fi
}

test_blank_exfat() {
    local image=e2e-blank-exfat.img
    blank_test_start "$image"
    create_blank "$image" "1 GB" exFAT
    check "exFAT: 1 GB" size_is "$image" "$BLANK_LARGE_BYTES"
    check "exFAT: filesystem clean" fs_check "$image" exfat
    check "exFAT: sparse (< 1 MiB allocated)" test "$(allocated_kib "$image")" -lt 1024
    check "$image: owner/group/label like the store" owned_like_store "$image"
    delete_blank "$image"
}

test_blank_ext4() {
    local image=e2e-blank-ext4.img
    blank_test_start "$image"
    create_blank "$image" "1 GB" ext4
    check "ext4: 1 GB" size_is "$image" "$BLANK_LARGE_BYTES"
    check "ext4: filesystem clean" fs_check "$image" ext4
    check "ext4: sparse (< 64 MiB allocated)" test "$(allocated_kib "$image")" -lt 65536
    check "$image: owner/group/label like the store" owned_like_store "$image"
    delete_blank "$image"
}

test_blank_fat32_preallocated() {
    local image=e2e-blank-fat.img
    blank_test_start "$image"
    create_blank "$image" 0.064 FAT32 preallocate
    check "FAT32: 64 MB" size_is "$image" "$BLANK_SMALL_BYTES"
    check "FAT32: filesystem clean" fs_check "$image" vfat
    check "FAT32: preallocated (≥ 60 MiB allocated)" test "$(allocated_kib "$image")" -ge 61440
    check "$image: owner/group/label like the store" owned_like_store "$image"
    delete_blank "$image"
}

test_blank_raw() {
    local image=e2e-blank-raw.img
    blank_test_start "$image"
    create_blank "$image" 0.064 Raw
    check "Raw: 64 MB" size_is "$image" "$BLANK_SMALL_BYTES"
    check "Raw: sparse (< 1 MiB allocated)" test "$(allocated_kib "$image")" -lt 1024
    check "Raw: first MiB is zeros" raw_is_zero "$image"
    check "$image: owner/group/label like the store" owned_like_store "$image"
    delete_blank "$image"
}

mounted_read_write_exfat() {
    diskutil info "/Volumes/$BLANK_VOLUME" | grep -qE 'Volume Read-Only: +No' && mount | grep -q "on /Volumes/$BLANK_VOLUME (exfat"
}

test_blank_exfat_read_write() {
    local image=e2e-blank-exfat-rw.img note blob_sha phone_sha
    require_mac_unlocked
    blank_test_start "$image"
    on_cleanup "root 'umount $BLANK_MNT 2>/dev/null; rmdir $BLANK_MNT 2>/dev/null' || true"
    on_cleanup drives_off
    drives_off
    # 64 MB holds the 3 MB test file; the 1 GB preset is covered by blank.exfat.
    create_blank "$image" 0.064 exFAT
    require "exFAT: filesystem clean before exposing it" fs_check "$image" exfat

    step "expose read/write"
    add_slot "$image" "Disk (read/write)"
    check "slot shows Disk (read/write)" has_text "Disk (read/write)"
    apply_drives
    check "Mac mounts $BLANK_VOLUME" wait_until "$BLANK_MOUNT_WAIT_S" mac_volume_present "$BLANK_VOLUME"
    check "mounted read-write (exFAT)" mounted_read_write_exfat

    step "write on the Mac, read on the phone"
    note="bootleg e2e $(date +%s)"
    printf '%s\n' "$note" >"/Volumes/$BLANK_VOLUME/e2e-note.txt"
    head -c 3000000 /dev/urandom >"/Volumes/$BLANK_VOLUME/e2e-blob.bin"
    blob_sha=$(shasum -a 256 "/Volumes/$BLANK_VOLUME/e2e-blob.bin" | awk '{ print $1 }')
    diskutil eject "$(mac_disk_of "$BLANK_VOLUME")" >/dev/null
    check "app shows Host ejected" wait_until 10 has_text "Host ejected"
    phone_sha=$(root "mkdir -p $BLANK_MNT; L=\$(losetup -f -s -r -o 1048576 $STORE/$image); mount -t exfat -o ro \$L $BLANK_MNT && sha256sum $BLANK_MNT/e2e-blob.bin; cat $BLANK_MNT/e2e-note.txt >&2; umount $BLANK_MNT; losetup -d \$L 2>/dev/null; rmdir $BLANK_MNT" 2>/dev/null | awk '{ print $1 }')
    check "file written on the Mac is intact on the phone" test "$phone_sha" == "$blob_sha"
    check "exFAT still clean after the Mac's writes" fs_check "$image" exfat

    step "remove the slot"
    remove_slot "$image"
    apply_drives
    check "gadget clean" wait_until 10 gadget_clean
    delete_blank "$image"
}
