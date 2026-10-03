#!/usr/bin/env bash
# E2E (UI) flow 3: Library -> Create blank image for each format: exFAT 1 GB (sparse), ext4 1 GB,
# FAT32 64 MB preallocated, Raw 64 MB. Each is checked as root on the phone (MBR partition at
# 1 MiB: blkid + fsck -n on a read-only loop device; allocation; owner/group/label like the store).
# The exFAT image is exposed read/write: the Mac mounts it writable, a file written there is
# found intact on the phone after the Mac ejects it. ext4 and Raw are never exposed (macOS can't
# read them and would show a modal dialog). All test images are deleted through the UI.
#
# Usage: scripts/e2e-ui-blank.sh
FLOW=blank
source "$(dirname "$0")/e2e-ui-lib.sh"

EXFAT=e2e-blank-exfat.img
EXT4=e2e-blank-ext4.img
FAT=e2e-blank-fat.img
RAW=e2e-blank-raw.img
VOLUME=BOOTLEG
SMALL_BYTES=64000000
LARGE_BYTES=1000000000
MOUNT_WAIT_S=25
MNT=/data/local/tmp/bootleg-e2e-blank

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
raw_is_zero() { [[ $(root "cmp -n 1048576 $STORE/$RAW /dev/zero >/dev/null 2>&1 && echo zero") == zero ]]; }

take_lock
on_cleanup "root 'umount $MNT 2>/dev/null; rmdir $MNT 2>/dev/null' || true"
on_cleanup drives_off
for image in "$EXFAT" "$EXT4" "$FAT" "$RAW"; do on_cleanup "delete_from_library $image"; done
launch_app
require "Mac screen unlocked (a locked Mac refuses ejects)" mac_screen_unlocked
for image in "$EXFAT" "$EXT4" "$FAT" "$RAW"; do delete_from_library "$image"; done

step "create"
create_blank "$EXFAT" "1 GB" exFAT
create_blank "$EXT4" "1 GB" ext4
create_blank "$FAT" 0.064 FAT32 preallocate
create_blank "$RAW" 0.064 Raw
shot library

step "verify on the phone"
check "exFAT: 1 GB" size_is "$EXFAT" "$LARGE_BYTES"
check "exFAT: filesystem clean" fs_check "$EXFAT" exfat
check "exFAT: sparse (< 1 MiB allocated)" test "$(allocated_kib "$EXFAT")" -lt 1024
check "ext4: 1 GB" size_is "$EXT4" "$LARGE_BYTES"
check "ext4: filesystem clean" fs_check "$EXT4" ext4
check "ext4: sparse (< 64 MiB allocated)" test "$(allocated_kib "$EXT4")" -lt 65536
check "FAT32: 64 MB" size_is "$FAT" "$SMALL_BYTES"
check "FAT32: filesystem clean" fs_check "$FAT" vfat
check "FAT32: preallocated (≥ 60 MiB allocated)" test "$(allocated_kib "$FAT")" -ge 61440
check "Raw: 64 MB" size_is "$RAW" "$SMALL_BYTES"
check "Raw: sparse (< 1 MiB allocated)" test "$(allocated_kib "$RAW")" -lt 1024
check "Raw: first MiB is zeros" raw_is_zero
for image in "$EXFAT" "$EXT4" "$FAT" "$RAW"; do
    check "$image: owner/group/label like the store" owned_like_store "$image"
done

step "exFAT read/write on the Mac"
add_slot "$EXFAT" "Disk (read/write)"
check "slot shows Disk (read/write)" has_text "Disk (read/write)"
apply_drives
check "Mac mounts $VOLUME" wait_until "$MOUNT_WAIT_S" mac_volume_present "$VOLUME"
check "mounted read-write (exFAT)" bash -c "diskutil info /Volumes/$VOLUME | grep -qE 'Volume Read-Only: +No' && mount | grep -q 'on /Volumes/$VOLUME (exfat'"
note="bootleg e2e $(date +%s)"
printf '%s\n' "$note" >"/Volumes/$VOLUME/e2e-note.txt"
head -c 3000000 /dev/urandom >"/Volumes/$VOLUME/e2e-blob.bin"
blob_sha=$(shasum -a 256 "/Volumes/$VOLUME/e2e-blob.bin" | awk '{ print $1 }')
diskutil eject "$(mac_disk_of "$VOLUME")" >/dev/null
check "app shows Host ejected" wait_until 10 has_text "Host ejected"
phone_sha=$(root "mkdir -p $MNT; L=\$(losetup -f -s -r -o 1048576 $STORE/$EXFAT); mount -t exfat -o ro \$L $MNT && sha256sum $MNT/e2e-blob.bin; cat $MNT/e2e-note.txt >&2; umount $MNT; losetup -d \$L 2>/dev/null; rmdir $MNT" 2>/dev/null | awk '{ print $1 }')
check "file written on the Mac is intact on the phone" test "$phone_sha" == "$blob_sha"
check "exFAT still clean after the Mac's writes" fs_check "$EXFAT" exfat
more_after "$EXFAT"
ui tap "Remove slot"
sleep 0.5
apply_drives
check "gadget clean" wait_until 10 gadget_clean

step "delete through the UI"
for image in "$EXFAT" "$EXT4" "$FAT" "$RAW"; do
    delete_from_library "$image"
    # The store is checked as root: the app's /sdcard (FUSE) view can keep a stale entry after a
    # root delete (findings O10), which is reported but doesn't fail this flow.
    check "$image deleted" bash -c "[[ -z \$(adb shell \"su -c 'ls $STORE/$image 2>/dev/null'\") ]]"
    adb shell ls "/sdcard/bootleg/$image" >/dev/null 2>&1 && log "  WARN  $image still resolves through /sdcard (stale FUSE entry, O10)"
done
