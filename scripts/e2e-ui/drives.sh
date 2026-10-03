# shellcheck shell=bash disable=SC2034 # sourced by scripts/e2e-ui.sh, which uses the variables
# Drives tests: an ISO slot (Auto -> CD-ROM) mounts read-only (cd9660) on the Mac; a Mac eject
# shows "Host ejected" and clears the notification (no USB_STATE arrives for that, so the app has
# to notice by itself); Re-insert mounts it again; the app's Eject while the Mac holds the disk
# offers Force eject; a slot re-added after a force eject exports again. Two slots (ISO + a FAT
# blank): both mount, the notification's Eject all is refused while the Mac holds them, Mac
# ejects show in the app, a force-stop + relaunch keeps the slots, and Eject all + removing the
# slots leaves the gadget clean. Sourced by scripts/e2e-ui.sh.

define_test drives.iso-cdrom "ISO slot: Auto · CD-ROM, Mac mounts it read-only cd9660, notification + silent channel"
define_test drives.host-eject "Mac eject: slot shows Host ejected, notification gone, LUN stays empty"
define_test drives.reinsert "Re-insert after a Mac eject: mounted and Exported again"
define_test drives.force-eject "app Eject while the Mac holds the disk offers Force eject; Force eject after a Mac unmount"
define_test drives.readd-after-force-eject "an image removed and re-added after a force eject is exported again"
define_test drives.two-slots "ISO + FAT slots: both mount, FAT defaults read-only, notification says 2 disks"
define_test drives.shade-eject-all-refused "notification Eject all while the Mac holds both disks drops nothing"
define_test drives.restart-keeps-slots "after Mac ejects, a force-stop + relaunch keeps the slots; Eject all + remove leaves the gadget clean"

HOST_EJECT_NOTICE_S=10
MOUNT_WAIT_S=25

slot_status() {
    # The status line under a slot's image name.
    ui_texts | awk -v name="$1" 'found { print; exit } $0 == name { found = 1 }'
}
slot_is() { [[ $(slot_status "$1") == "$2" ]]; }
volume_read_only() { diskutil info "/Volumes/$1" | grep -qE 'Media Read-Only: +Yes'; }
volume_cd9660() { mount | grep -q "on /Volumes/$1 (cd9660, .*read-only"; }
no_exported_notification() { ! notification_titles | grep -q exported; }
notification_says() { notification_titles | grep -qxF "$1"; }
host_ejected_count_is() { [[ $(ui_texts | grep -c '^Host ejected$') == "$1" ]]; }
library_lists() { tab Library && has_text "$1"; }

# drives_test_start: what every drives test needs (the ISO, an unlocked Mac, no drives) and its cleanup.
drives_test_start() {
    need_iso
    require_mac_unlocked
    on_cleanup drives_off
    drives_off
}

# expose_iso: one ISO slot (Drives -> Add slot), applied and mounted on the Mac.
expose_iso() {
    add_slot "$ISO"
    apply_drives
    require "Mac mounts $ISO_VOLUME" wait_until "$MOUNT_WAIT_S" mac_volume_present "$ISO_VOLUME"
    require "slot shows Exported" wait_until 10 slot_is "$ISO" Exported
}

# force_eject_iso: the Mac unmounts (keeps the disk), the app force ejects; the slot shows Ejected.
force_eject_iso() {
    local disk
    disk=$(mac_disk_of "$ISO_VOLUME")
    diskutil unmount "/Volumes/$ISO_VOLUME" >/dev/null
    more_after "$ISO"
    ui tap "Force eject"
    ui wait "Force eject slot 1?" 5
    ui tap "Force eject" # confirm
    check "slot shows Ejected after force eject" wait_until 10 slot_is "$ISO" Ejected
    diskutil eject "$disk" >/dev/null 2>&1 || true
}

# expose_two: ISO + FAT slots, applied and both mounted.
expose_two() {
    need_fat_fixture
    add_slot "$ISO"
    add_slot "$FAT_FIXTURE"
    check "FAT slot defaults to Auto · Disk (read-only)" has_text "Auto · Disk (read-only)"
    apply_drives
    require "Mac mounts $ISO_VOLUME" wait_until "$MOUNT_WAIT_S" mac_volume_present "$ISO_VOLUME"
    require "Mac mounts $FAT_VOLUME" wait_until "$MOUNT_WAIT_S" mac_volume_present "$FAT_VOLUME"
}

test_drives_iso_cdrom() {
    drives_test_start
    add_slot "$ISO"
    check "draft shows Auto · CD-ROM" has_text "Auto · CD-ROM"
    shot draft
    apply_drives
    check "Mac mounts $ISO_VOLUME" wait_until "$MOUNT_WAIT_S" mac_volume_present "$ISO_VOLUME"
    check "mounted read-only (media)" volume_read_only "$ISO_VOLUME"
    check "mounted as ISO9660, read-only" volume_cd9660 "$ISO_VOLUME"
    check "slot shows Exported" wait_until 10 slot_is "$ISO" Exported
    check "notification: 1 disk exported" wait_until 10 notification_says "1 disk exported"
    check "exports channel is low importance and silent" exports_channel_ok
}

test_drives_host_eject() {
    drives_test_start
    expose_iso
    diskutil eject "$(mac_disk_of "$ISO_VOLUME")" >/dev/null
    check "app shows Host ejected within ${HOST_EJECT_NOTICE_S}s" wait_until "$HOST_EJECT_NOTICE_S" slot_is "$ISO" "Host ejected"
    check "notification gone after the host eject" wait_until "$HOST_EJECT_NOTICE_S" no_exported_notification
    check "LUN stays empty (never re-inserted on its own)" test "$(exported_luns)" == 0
    shot host-ejected
}

test_drives_reinsert() {
    drives_test_start
    expose_iso
    diskutil eject "$(mac_disk_of "$ISO_VOLUME")" >/dev/null
    require "app shows Host ejected" wait_until "$HOST_EJECT_NOTICE_S" slot_is "$ISO" "Host ejected"
    ui tap Re-insert
    check "Mac mounts $ISO_VOLUME again" wait_until "$MOUNT_WAIT_S" mac_volume_present "$ISO_VOLUME"
    check "slot shows Exported again" wait_until 10 slot_is "$ISO" Exported
}

test_drives_force_eject() {
    drives_test_start
    expose_iso
    ui tap Eject
    check "Force eject offered (host prevents removal)" wait_until 10 has_text "Force eject"
    shot force-offer
    ui tap Cancel
    ui gone Cancel 5
    check "volume still mounted after the refusal" mac_volume_present "$ISO_VOLUME"
    force_eject_iso
}

test_drives_readd_after_force_eject() {
    drives_test_start
    expose_iso
    force_eject_iso
    remove_slot "$ISO"
    add_slot "$ISO"
    apply_drives
    # Finding: re-adding an image to the slot it was ejected from kept it ejected (the controller
    # couldn't tell a remove + re-add in one draft from an unchanged slot).
    check "re-added $ISO slot is exported, not still Ejected" wait_until 10 slot_is "$ISO" Exported
    if slot_is "$ISO" Ejected; then ui tap Re-insert; fi
    check "Mac mounts $ISO_VOLUME" wait_until "$MOUNT_WAIT_S" mac_volume_present "$ISO_VOLUME"
}

test_drives_two_slots() {
    drives_test_start
    expose_two
    check "$FAT_VOLUME is read-only" volume_read_only "$FAT_VOLUME"
    check "notification: 2 disks exported" wait_until 10 notification_says "2 disks exported"
    shot two-slots
}

test_drives_shade_eject_all_refused() {
    drives_test_start
    on_cleanup "adb shell cmd statusbar collapse"
    expose_two
    require "notification: 2 disks exported" wait_until 10 notification_says "2 disks exported"
    check "notification offers Eject all" shade_eject_all "2 disks exported"
    shot notification-eject-all-refused
    adb shell cmd statusbar collapse
    launch_app
    check "both volumes still mounted (refused, nothing dropped)" bash -c "[[ -d /Volumes/$ISO_VOLUME && -d /Volumes/$FAT_VOLUME ]]"
}

test_drives_restart_keeps_slots() {
    drives_test_start
    expose_two
    diskutil eject "$(mac_disk_of "$ISO_VOLUME")" >/dev/null
    diskutil eject "$(mac_disk_of "$FAT_VOLUME")" >/dev/null
    check "both slots show Host ejected" wait_until "$HOST_EJECT_NOTICE_S" host_ejected_count_is 2

    # After the Mac ejected both (so nothing mounted can vanish): a force-stop + relaunch must keep
    # the journaled slots. Finding O8: Android delivers BOOT_COMPLETED to an app leaving the
    # force-stopped state, and BootReceiver then forgot (or restored) every drive as after a reboot.
    step "app restart keeps the slots"
    restart_app
    check "Drives lists both slots after a force-stop + relaunch" wait_until 10 has_text "$FAT_FIXTURE"
    check "root status resolves (not stuck on Checking…)" wait_until 10 has_text Root
    check "Library lists images after a restart" library_lists "$FAT_FIXTURE"
    tab Drives

    step "Eject all, remove the slots"
    if has_text "$ISO"; then
        ui tap "Eject all"
        ui wait "Eject all drives?" 5
        ui tap "Eject all" # the confirm dialog's button (the dialog is the only window in the dump)
        ui gone "Eject all drives?" 5
        wait_until 10 not_text "Working…" || true
        remove_slot "$FAT_FIXTURE"
        remove_slot "$ISO"
        apply_drives
    fi
    check "Drives empty" wait_until 10 has_text "No drives yet"
    check "gadget clean (no bootleg functions, UDC bound)" wait_until 10 gadget_clean
}
