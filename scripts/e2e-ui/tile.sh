# shellcheck shell=bash disable=SC2034 # sourced by scripts/e2e-ui.sh, which uses the variables
# Quick Settings tile and drives notification tests. Each adds the tile and removes it again. With
# netboot.xyz.iso exposed from the Library: the tile is active (expanded shade); the ongoing
# notification is on a low-importance silent channel and lists the drive; the tile's and the
# notification's Eject all, while the Mac holds the disk, leave it mounted (the toast is in the
# screenshots); a Mac eject turns the tile inactive and removes the notification. With nothing
# exported the tile is inactive and tapping it opens the app. Sourced by scripts/e2e-ui.sh.

define_test tile.inactive "tile inactive with nothing exported"
define_test tile.expose-from-library "Library Expose: Mac mounts, notification lists the drive (silent channel), tile active"
define_test tile.eject-all-refused "tile Eject all while the Mac holds the disk drops nothing"
define_test tile.notification-eject-all-refused "notification Eject all while the Mac holds the disk drops nothing"
define_test tile.host-eject "Mac eject: notification gone, tile inactive again"
define_test tile.opens-app "the inactive tile opens the app"

TILE=$PACKAGE/.tile.BootlegTileService
TILE_MOUNT_WAIT_S=25

# tile_state: "true"/"false" (the Bootleg tile's checked state in the expanded Quick Settings),
# empty when the tile isn't found.
tile_state_now() {
    # SystemUI: the checkable tile node holds a child labelled "Bootleg".
    ui_dump | xmllint --xpath 'string((//node[@checkable="true"][node[@content-desc="Bootleg" or @text="Bootleg"]])[1]/@checked)' - 2>/dev/null
}
tile_shown() { [[ -n $(tile_state_now) ]]; }
tile_state() {
    adb shell cmd statusbar expand-settings
    wait_until "$SHADE_WAIT_S" tile_shown || true
    tile_state_now
}
tile_checked() { [[ $(tile_state) == true ]]; }
tile_unchecked() { [[ $(tile_state) == false ]]; }
notification_listed() { notification_titles | grep -qxF "1 disk exported"; }
no_notification() { ! notification_titles | grep -q exported; }
app_resumed() { adb shell dumpsys activity activities | grep -q "topResumedActivity=.*$ACTIVITY"; }
app_not_resumed() { ! app_resumed; }
notification_text_is() { [[ $(notification_field text | head -1) == "$1" ]]; }

# tile_test_start: the tile added, nothing exported; cleanup collapses the shade, removes the tile,
# drops the drives.
tile_test_start() {
    need_iso
    require_mac_unlocked
    on_cleanup drives_off
    on_cleanup "adb shell cmd statusbar remove-tile $TILE"
    on_cleanup "adb shell cmd statusbar collapse"
    drives_off
    adb shell cmd statusbar add-tile "$TILE"
}

# expose_from_library: Library -> the ISO's Expose button; the Mac mounts it.
expose_from_library() {
    tab Library
    tap_after "$ISO" Expose
    require "Mac mounts $ISO_VOLUME" wait_until "$TILE_MOUNT_WAIT_S" mac_volume_present "$ISO_VOLUME"
}

test_tile_inactive() {
    tile_test_start
    check "tile inactive with nothing exported" tile_unchecked
    shot tile-inactive
}

test_tile_expose_from_library() {
    tile_test_start
    tab Library
    tap_after "$ISO" Expose
    check "Mac mounts $ISO_VOLUME" wait_until "$TILE_MOUNT_WAIT_S" mac_volume_present "$ISO_VOLUME"
    check "notification lists the drive" wait_until 10 notification_listed
    check "notification text is Slot 1 · $ISO (CD)" notification_text_is "Slot 1 · $ISO (CD)"
    log "    notification: $(notification_field text | head -1)"
    check "exports channel is low importance and silent" exports_channel_ok
    check "tile active" tile_checked
    shot tile-active
}

test_tile_eject_all_refused() {
    tile_test_start
    expose_from_library
    require "tile active" wait_until 10 tile_checked
    adb shell cmd statusbar click-tile "$TILE"
    sleep "$REFUSAL_SETTLE_S"
    shot tile-eject-refused-toast
    check "volume still mounted (refused, not dropped)" mac_volume_present "$ISO_VOLUME"
}

test_tile_notification_eject_all_refused() {
    tile_test_start
    expose_from_library
    require "notification lists the drive" wait_until 10 notification_listed
    check "notification offers Eject all" shade_eject_all "1 disk exported"
    shot notification-eject-refused-toast
    check "volume still mounted (refused, not dropped)" mac_volume_present "$ISO_VOLUME"
}

test_tile_host_eject() {
    tile_test_start
    expose_from_library
    require "tile active" wait_until 10 tile_checked
    adb shell cmd statusbar collapse
    diskutil eject "$(mac_disk_of "$ISO_VOLUME")" >/dev/null
    check "notification gone within 10s" wait_until 10 no_notification
    check "tile inactive again" wait_until 10 tile_unchecked
    shot tile-after-host-eject
}

test_tile_opens_app() {
    tile_test_start
    adb shell input keyevent KEYCODE_HOME
    wait_until 5 app_not_resumed || true
    adb shell cmd statusbar click-tile "$TILE"
    check "app comes to the front" wait_until 5 app_resumed
}
