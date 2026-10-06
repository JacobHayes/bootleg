# Keeps the phone's screen on while a run that needs it unlocked is going ("stay awake while
# plugged in"), so the screen doesn't time out and lock mid-run. Source it (POSIX sh); don't run it.
# The prior setting is kept on the phone, so a run killed before restoring it is undone by the next.

STAY_AWAKE_PRIOR_FILE=/data/local/tmp/bootleg-stay-awake-prior
STAY_AWAKE_ADB_TIMEOUT_S=15
STAY_AWAKE_ALL_SOURCES=7 # AC, USB and wireless charging

# stay_awake_on SERIAL: saves the setting (unless a killed run left its save), keeps the screen on and
# wakes it (a screen that is off but not yet locked would lock; waking never unlocks).
stay_awake_on() {
    timeout "$STAY_AWAKE_ADB_TIMEOUT_S" adb -s "$1" shell "
        f=$STAY_AWAKE_PRIOR_FILE
        [ -s \$f ] || settings get global stay_on_while_plugged_in >\$f || exit 1
        grep -qx '[0-7]' \$f || { rm -f \$f; exit 1; }
        settings put global stay_on_while_plugged_in $STAY_AWAKE_ALL_SOURCES &&
        input keyevent KEYCODE_WAKEUP" ||
        echo "WARN: could not keep the phone's screen on; it may lock mid-run" >&2
}

# stay_awake_restore SERIAL: puts back the saved setting, if there is one.
stay_awake_restore() {
    timeout "$STAY_AWAKE_ADB_TIMEOUT_S" adb -s "$1" shell "
        f=$STAY_AWAKE_PRIOR_FILE
        [ -s \$f ] || exit 0
        settings put global stay_on_while_plugged_in \$(cat \$f) && rm -f \$f" ||
        echo "WARN: could not restore the phone's stay-awake setting; the next run will" >&2
}
