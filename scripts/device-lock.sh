# The device lock (AGENTS.md, Device rules), shared by every script that takes it. Source it (POSIX
# sh); don't run it. The lock is the directory; its owner file says who holds it, so a waiter can
# name the holder, and a release removes only a lock this process still owns.

DEVICE_LOCK_DIR=/private/tmp/claude-501/bootleg-device.lock
DEVICE_LOCK_OWNER_FILE=$DEVICE_LOCK_DIR/owner
# How often a waiter repeats who holds the lock when the holder hasn't changed.
DEVICE_LOCK_REPORT_INTERVAL_S=30
device_lock_owner_line=

# device_lock_take NAME WAIT_S_MAX: takes the lock, waiting at most WAIT_S_MAX seconds, and records
# NAME as the owner. With BOOTLEG_DEVICE_LOCK_HELD=1 the caller already holds it: only checks it
# exists, and device_lock_release keeps it. Returns 1 (with the reason on stderr) if not taken.
device_lock_take() {
    _lock_name=$1
    _lock_wait_s_max=$2
    if [ "${BOOTLEG_DEVICE_LOCK_HELD:-0}" = 1 ]; then
        [ -d "$DEVICE_LOCK_DIR" ] && return 0
        echo "$_lock_name: BOOTLEG_DEVICE_LOCK_HELD=1 but $DEVICE_LOCK_DIR doesn't exist" >&2
        return 1
    fi
    _lock_waited_s=0
    _lock_reported=
    until mkdir "$DEVICE_LOCK_DIR" 2>/dev/null; do
        _lock_owner=$(cat "$DEVICE_LOCK_OWNER_FILE" 2>/dev/null) || _lock_owner="unknown (no owner file)"
        if [ "$_lock_waited_s" -ge "$_lock_wait_s_max" ]; then
            echo "$_lock_name: device lock $DEVICE_LOCK_DIR still held after ${_lock_wait_s_max}s by $_lock_owner" >&2
            return 1
        fi
        if [ "$_lock_owner" != "$_lock_reported" ] || [ $((_lock_waited_s % DEVICE_LOCK_REPORT_INTERVAL_S)) = 0 ]; then
            echo "$_lock_name: waiting for the device lock, held by $_lock_owner" >&2
            _lock_reported=$_lock_owner
        fi
        sleep 1
        _lock_waited_s=$((_lock_waited_s + 1))
    done
    device_lock_owner_line="pid=$$ script=$_lock_name started=$(date '+%Y-%m-%dT%H:%M:%S%z')"
    echo "$device_lock_owner_line" >"$DEVICE_LOCK_OWNER_FILE"
}

# Releases the lock only if this process took it and still owns it: removes the owner file, then the
# directory. A lock someone else took meanwhile is left alone, with a warning.
device_lock_release() {
    [ -n "$device_lock_owner_line" ] || return 0
    _lock_owner=$(cat "$DEVICE_LOCK_OWNER_FILE" 2>/dev/null) || _lock_owner=
    if [ "$_lock_owner" = "$device_lock_owner_line" ]; then
        rm -f "$DEVICE_LOCK_OWNER_FILE"
        rmdir "$DEVICE_LOCK_DIR" || echo "WARN: could not remove the device lock $DEVICE_LOCK_DIR" >&2
    else
        echo "WARN: the device lock is no longer ours (owner: ${_lock_owner:-none}); left it alone" >&2
    fi
    device_lock_owner_line=
}
