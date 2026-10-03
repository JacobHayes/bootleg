#!/usr/bin/env bash
# E2E (UI) flow 2: Drives. One ISO slot (Auto -> CD-ROM): the Mac mounts it read-only (cd9660);
# a Mac eject shows "Host ejected" in the app and clears the notification (no USB_STATE arrives
# for that, so the app has to notice by itself); Re-insert mounts it again; the app's Eject while
# the Mac holds the disk offers Force eject; Force eject after a Mac unmount. Then two slots at once
# (ISO + a FAT blank created here): both mount, the notification's Eject all is refused while the
# Mac holds them (the volumes stay), Mac ejects show up in the app, a force-stop + relaunch still
# lists the slots, and Eject all / removing the slots leaves the gadget clean.
#
# Needs netboot.xyz.iso in the Library (scripts/e2e-ui-catalog.sh downloads it).
# Usage: scripts/e2e-ui-drives.sh
FLOW=drives
source "$(dirname "$0")/e2e-ui-lib.sh"

ISO=netboot.xyz.iso
ISO_VOLUME=iPXE
FAT=e2e-drives-fat.img
FAT_VOLUME=BOOTLEG
HOST_EJECT_NOTICE_S=10
MOUNT_WAIT_S=25

slot_status() {
    # The status line under a slot's image name.
    ui texts | awk -v name="$1" 'found { print; exit } $0 == name { found = 1 }'
}
slot_is() { [[ $(slot_status "$1") == "$2" ]]; }
volume_read_only() { diskutil info "/Volumes/$1" | grep -qE 'Media Read-Only: +Yes'; }
volume_cd9660() { mount | grep -q "on /Volumes/$1 (cd9660, .*read-only"; }
no_notification() { [[ -z $(notification_titles | grep -E 'exported') ]]; }
notification_says() { notification_titles | grep -qxF "$1"; }

take_lock
on_cleanup drives_off
on_cleanup "delete_from_library $FAT"
launch_app
require "$ISO is in the Library (run e2e-ui-catalog.sh first)" bash -c "adb shell ls /sdcard/bootleg/$ISO >/dev/null"
require "Mac screen unlocked (a locked Mac refuses ejects)" mac_screen_unlocked
drives_off

step "one ISO slot"
add_slot "$ISO"
check "draft shows Auto · CD-ROM" has_text "Auto · CD-ROM"
shot draft
apply_drives
check "Mac mounts $ISO_VOLUME" wait_until "$MOUNT_WAIT_S" mac_volume_present "$ISO_VOLUME"
check "mounted read-only (media)" volume_read_only "$ISO_VOLUME"
check "mounted as ISO9660, read-only" volume_cd9660 "$ISO_VOLUME"
check "slot shows Exported" slot_is "$ISO" Exported
check "notification: 1 disk exported" notification_says "1 disk exported"
check "exports channel is low importance and silent" exports_channel_ok

step "host eject"
diskutil eject "$(mac_disk_of "$ISO_VOLUME")" >/dev/null
check "app shows Host ejected within ${HOST_EJECT_NOTICE_S}s" wait_until "$HOST_EJECT_NOTICE_S" slot_is "$ISO" "Host ejected"
check "notification gone after the host eject" wait_until "$HOST_EJECT_NOTICE_S" no_notification
check "LUN stays empty (never re-inserted on its own)" test "$(exported_luns)" == 0
shot host-ejected

step "re-insert"
ui tap Re-insert
check "Mac mounts $ISO_VOLUME again" wait_until "$MOUNT_WAIT_S" mac_volume_present "$ISO_VOLUME"
check "slot shows Exported again" wait_until 10 slot_is "$ISO" Exported

step "app eject while the Mac holds the disk"
ui tap Eject
check "Force eject offered (host prevents removal)" wait_until 10 has_text "Force eject"
shot force-offer
ui tap Cancel
check "volume still mounted after the refusal" mac_volume_present "$ISO_VOLUME"
disk=$(mac_disk_of "$ISO_VOLUME")
diskutil unmount "/Volumes/$ISO_VOLUME" >/dev/null
more_after "$ISO"
ui tap "Force eject"
sleep 0.6
ui tap "Force eject" # confirm
check "slot shows Ejected after force eject" wait_until 10 slot_is "$ISO" Ejected
diskutil eject "$disk" >/dev/null 2>&1 || true

step "two slots: ISO + FAT"
create_blank "$FAT" 0.064 FAT32
tab Drives
more_after "$ISO"
ui tap "Remove slot"
sleep 0.5
add_slot "$ISO"
add_slot "$FAT"
check "FAT slot defaults to Auto · Disk (read-only)" has_text "Auto · Disk (read-only)"
apply_drives
# Finding: re-adding an image to the slot it was ejected from keeps it ejected (the controller
# can't tell a remove + re-add in one draft from an unchanged slot).
check "re-added $ISO slot is exported, not still Ejected" slot_is "$ISO" Exported
if slot_is "$ISO" Ejected; then ui tap Re-insert; fi
check "Mac mounts $ISO_VOLUME" wait_until "$MOUNT_WAIT_S" mac_volume_present "$ISO_VOLUME"
check "Mac mounts $FAT_VOLUME" wait_until "$MOUNT_WAIT_S" mac_volume_present "$FAT_VOLUME"
check "$FAT_VOLUME is read-only" volume_read_only "$FAT_VOLUME"
check "notification: 2 disks exported" wait_until 10 notification_says "2 disks exported"
shot two-slots

step "notification Eject all while the Mac holds both"
check "notification offers Eject all" shade_eject_all "2 disks exported"
shot notification-eject-all-refused
adb shell cmd statusbar collapse
launch_app
check "both volumes still mounted (refused, nothing dropped)" bash -c "[[ -d /Volumes/$ISO_VOLUME && -d /Volumes/$FAT_VOLUME ]]"

step "Mac ejects both; Eject all; slots removed"
diskutil eject "$(mac_disk_of "$ISO_VOLUME")" >/dev/null
diskutil eject "$(mac_disk_of "$FAT_VOLUME")" >/dev/null
check "both slots show Host ejected" wait_until "$HOST_EJECT_NOTICE_S" bash -c \
    "[[ \$(scripts/ui.sh texts | grep -c '^Host ejected$') == 2 ]]"

# After the Mac ejected both (so nothing mounted can vanish): a force-stop + relaunch must keep the
# journaled slots. Finding O8: Android delivers BOOT_COMPLETED to an app leaving the force-stopped
# state, and BootReceiver then forgets (or restores) every drive as if the phone had rebooted.
step "app restart keeps the slots"
restart_app
check "Drives lists both slots after a force-stop + relaunch" wait_until 10 has_text "$FAT"
check "root status resolves (not stuck on Checking…)" wait_until 10 has_text Root
check "Library lists images after a restart" bash -c "scripts/ui.sh tap Library >/dev/null; sleep 1; scripts/ui.sh has $FAT"
tab Drives
if has_text "$ISO"; then
    ui tap "Eject all"
    sleep 0.6
    ui tap "Eject all" # confirm
    sleep 2
    for name in "$FAT" "$ISO"; do
        more_after "$name"
        ui tap "Remove slot"
        sleep 0.5
    done
    apply_drives
fi
check "Drives empty" wait_until 10 has_text "No drives yet"
check "gadget clean (no bootleg functions, UDC bound)" wait_until 10 gadget_clean
