#!/usr/bin/env bash
# E2E (UI) flows 6 + 7: Quick Settings tile and the drives notification. Adds the tile, exposes
# netboot.xyz.iso from the Library, then checks: the tile is active (expanded shade); the ongoing
# notification is on a low-importance silent channel and lists the drive; the tile's and the
# notification's Eject all, while the Mac holds the disk, leave it mounted (the toast is in the
# screenshots); a Mac eject turns the tile inactive and removes the notification; tapping the
# inactive tile opens the app. The tile is removed again at the end.
#
# Needs netboot.xyz.iso in the Library (scripts/e2e-ui-catalog.sh downloads it).
# Usage: scripts/e2e-ui-tile.sh
FLOW=tile
source "$(dirname "$0")/e2e-ui-lib.sh"

TILE=$PACKAGE/.tile.BootlegTileService
ISO=netboot.xyz.iso
VOLUME=iPXE
MOUNT_WAIT_S=25

# tile_state: "true"/"false" (the Bootleg tile's checked state in the expanded Quick Settings),
# empty when the tile isn't found.
tile_state() {
    adb shell cmd statusbar expand-settings
    sleep 1.2
    # SystemUI: the checkable tile node holds a child labelled "Bootleg".
    ui dump | xmllint --xpath 'string((//node[@checkable="true"][node[@content-desc="Bootleg" or @text="Bootleg"]])[1]/@checked)' - 2>/dev/null
}
tile_checked() { [[ $(tile_state) == true ]]; }
tile_unchecked() { [[ $(tile_state) == false ]]; }
notification_listed() { notification_titles | grep -qxF "1 disk exported"; }
no_notification() { ! notification_titles | grep -q exported; }
app_resumed() { adb shell dumpsys activity activities | grep -q "topResumedActivity=.*$ACTIVITY"; }

take_lock
on_cleanup drives_off
on_cleanup "adb shell cmd statusbar remove-tile $TILE"
on_cleanup "adb shell cmd statusbar collapse"
launch_app
require "$ISO is in the Library (run e2e-ui-catalog.sh first)" bash -c "adb shell ls /sdcard/bootleg/$ISO >/dev/null"
require "Mac screen unlocked (a locked Mac refuses ejects)" mac_screen_unlocked
drives_off
adb shell cmd statusbar add-tile "$TILE"

step "inactive tile"
check "tile inactive with nothing exported" tile_unchecked
shot tile-inactive
adb shell cmd statusbar collapse

step "expose from the Library"
tab Library
tap_after "$ISO" Expose
check "Mac mounts $VOLUME" wait_until "$MOUNT_WAIT_S" mac_volume_present "$VOLUME"
check "notification lists the drive" wait_until 10 notification_listed
check "notification text is Slot 1 · $ISO (CD)" bash -c "[[ \"$(notification_field text | head -1)\" == \"Slot 1 · $ISO (CD)\" ]]"
log "    notification: $(notification_field text | head -1)"
check "exports channel is low importance and silent" exports_channel_ok
check "tile active" tile_checked
shot tile-active

step "tile Eject all while the Mac holds the disk"
adb shell cmd statusbar click-tile "$TILE"
sleep 1.2
shot tile-eject-refused-toast
check "volume still mounted (refused, not dropped)" mac_volume_present "$VOLUME"
adb shell cmd statusbar collapse

step "notification Eject all while the Mac holds the disk"
adb shell cmd statusbar expand-notifications
sleep 1.5
check "notification offers Eject all" shade_eject_all "1 disk exported"
shot notification-eject-refused-toast
check "volume still mounted (refused, not dropped)" mac_volume_present "$VOLUME"
adb shell cmd statusbar collapse

step "Mac eject"
diskutil eject "$(mac_disk_of "$VOLUME")" >/dev/null
check "notification gone within 10s" wait_until 10 no_notification
check "tile inactive again" wait_until 10 tile_unchecked
shot tile-after-host-eject

step "inactive tile opens the app"
adb shell input keyevent KEYCODE_HOME
sleep 0.8
adb shell cmd statusbar click-tile "$TILE"
check "app comes to the front" wait_until 5 app_resumed
