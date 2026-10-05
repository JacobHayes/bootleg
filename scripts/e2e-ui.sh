#!/usr/bin/env bash
# Runs the on-device UI end-to-end tests (scripts/e2e-ui/*.sh) against the real app, with this Mac
# as USB host. Each test checks one behaviour, runs in its own subshell and puts back what it
# changed, so any selection runs alone or in any order. One setup per run: device lock, adb
# serials, the resident uiautomator dump server; shared fixtures (netboot.xyz.iso, a FAT blank)
# are made on first use and kept on the phone for later runs.
#
# Usage: scripts/e2e-ui.sh [--list] [--install] [TEST...]
#   TEST       a test name (drives.iso-cdrom), an area (drives) or a glob ('blank.*', '*eject*').
#              Default: every test that isn't opt-in. Opt-in tests run only when named exactly.
#   --list     print the tests and exit
#   --install  install app/build/outputs/apk/debug/app-debug.apk first unless the phone already
#              has this build, and pre-grant root (scripts/install-apk.sh). Otherwise the installed
#              app is used as is (`mise run deploy`).
#
# Results: one TSV row per test appended to build/e2e-ui/results.tsv (run id, start time, test,
# PASS/FAIL/SKIP, seconds, passed and failed check counts, failed check names). Screenshots go to
# build/e2e-shots/<test>/. Exit status 0 only when every selected test passed.
#
# Environment: BOOTLEG_WIFI_SERIAL / BOOTLEG_USB_SERIAL override the detected adb serials;
# BOOTLEG_DEVICE_LOCK_HELD=1 when the caller already holds the device lock; BOOTLEG_UI_SERVER=0
# falls back to `uiautomator dump` (slow).
#
# Bounds: every adb call (scripts/ui.sh), helper request and helper build step has a timeout. A
# signal (INT, TERM, HUP) during setup exits at once; during the tests it skips the rest after the
# running test. The device lock is released on every exit but SIGKILL.
# shellcheck source=scripts/e2e-ui-lib.sh
source "$(dirname "$0")/e2e-ui-lib.sh"

RESULTS_DIR=build/e2e-ui
RESULTS_FILE=$RESULTS_DIR/results.tsv
RESULTS_HEADER=$'run_id\tstarted_at\ttest\tstatus\tduration_s\tchecks_passed\tchecks_failed\tfailed_checks'
UI_SERVER_JAR=$RESULTS_DIR/ui-dump-server.jar
APK=app/build/outputs/apk/debug/app-debug.apk

# --- registry ----------------------------------------------------------------------------------------

TEST_NAMES=()
declare -A TEST_DESCRIPTION=() TEST_TRANSPORT=() TEST_OPT_IN=()

# define_test NAME DESCRIPTION [--usb] [--opt-in]: registers test_<NAME with . and - as _>.
define_test() {
    local name=$1 description=$2 option
    shift 2
    [[ $name =~ ^[a-z]+\.[a-z0-9-]+$ ]] || { echo "bad test name '$name'" >&2; exit 2; }
    [[ -z ${TEST_DESCRIPTION[$name]:-} ]] || { echo "test '$name' defined twice" >&2; exit 2; }
    TEST_NAMES+=("$name")
    TEST_DESCRIPTION[$name]=$description
    TEST_TRANSPORT[$name]=wifi
    TEST_OPT_IN[$name]=false
    for option in "$@"; do
        case $option in
            --usb) TEST_TRANSPORT[$name]=usb ;;
            --opt-in) TEST_OPT_IN[$name]=true ;;
            *) echo "define_test $name: unknown option $option" >&2; exit 2 ;;
        esac
    done
}
test_function() { local name=${1//./_}; echo "test_${name//-/_}"; }

for area in catalog drives blank hid settings tile remote doze; do
    # shellcheck source=/dev/null
    source "scripts/e2e-ui/$area.sh"
done
for name in "${TEST_NAMES[@]}"; do
    declare -F "$(test_function "$name")" >/dev/null || { echo "test $name has no function $(test_function "$name")" >&2; exit 2; }
done

# --- arguments ---------------------------------------------------------------------------------------

list=false
install=false
patterns=()
for argument in "$@"; do
    case $argument in
        --list) list=true ;;
        --install) install=true ;;
        -h | --help) sed -n '2,/^source/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
        -*) echo "unknown option $argument" >&2; exit 2 ;;
        *) patterns+=("$argument") ;;
    esac
done

# selected NAME: whether the arguments pick the test.
selected() {
    local name=$1 pattern
    ((${#patterns[@]})) || { [[ ${TEST_OPT_IN[$name]} == false ]]; return; }
    for pattern in "${patterns[@]}"; do
        [[ $pattern == "$name" ]] && return 0
        [[ ${TEST_OPT_IN[$name]} == true ]] && continue
        # shellcheck disable=SC2053 # the pattern is a glob on purpose
        [[ $pattern == "${name%%.*}" || $name == $pattern ]] && return 0
    done
    return 1
}

if $list; then
    for name in "${TEST_NAMES[@]}"; do
        flags=""
        [[ ${TEST_TRANSPORT[$name]} == usb ]] && flags+=" [usb adb]"
        [[ ${TEST_OPT_IN[$name]} == true ]] && flags+=" [opt-in]"
        printf '%-36s %s%s\n' "$name" "${TEST_DESCRIPTION[$name]}" "$flags"
    done
    exit 0
fi

chosen=()
for name in "${TEST_NAMES[@]}"; do
    if selected "$name"; then chosen+=("$name"); fi
done
((${#chosen[@]})) || { echo "no test matches: ${patterns[*]} (see --list)" >&2; exit 2; }

# --- one-time setup ----------------------------------------------------------------------------------

RUN_ID=$(date +%Y%m%dT%H%M%S)-$$
RUN_DIR=$RESULTS_DIR/runs/$RUN_ID
mkdir -p "$RUN_DIR"
export UI_LAST_DUMP_FILE=$RUN_DIR/last-dump.xml
interrupted=false
results=()
suite_status=0
TEARDOWN_PROBE_TIMEOUT_S=10
UI_SERVER_BUILD_TIMEOUT_S=120

suite_finish() {
    local status=$?
    trap - EXIT
    # Teardown is bounded (a quick probe, then bounded adb calls): it finishes, then frees the phone.
    trap '' INT TERM HUP
    current_test=teardown
    current_step=teardown
    # A transport that doesn't answer a quick probe would make every teardown call wait out its
    # own bound: skip the phone side then.
    if [[ -n ${WIFI_SERIAL:-} ]] && ! UI_ADB_CALL_TIMEOUT_S=$TEARDOWN_PROBE_TIMEOUT_S wifi_adb_ready; then
        log "FAIL wireless adb $WIFI_SERIAL doesn't answer; skipped the phone teardown (the dump helper)"
        suite_status=1
    elif [[ -n ${WIFI_SERIAL:-} ]]; then
        use_transport wifi
        ui_server_stop
        [[ -z ${UI_PORT_USB:-} ]] || adb -s "$USB_SERIAL" forward --remove "tcp:$UI_PORT_USB" >/dev/null 2>&1 || true
    fi
    release_lock
    echo
    ((${#results[@]} == 0)) || printf '  %s\n' "${results[@]}"
    ((status == 0 && suite_status != 0)) && status=$suite_status
    log "$([[ $status == 0 ]] && echo PASS || echo FAIL) (results: $RESULTS_FILE, run $RUN_ID)"
    exit "$status"
}
trap suite_finish EXIT
# During setup a signal exits (the EXIT trap releases the lock); the tests switch to a flag below.
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

build_ui_server() {
    local sdk=${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK} build_tools platform java_bin classes
    [[ $UI_SERVER_JAR -nt scripts/e2e-ui/UiDumpServer.java ]] && return 0
    build_tools=$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 | sort -V | tail -1)
    platform=$(find "$sdk/platforms" -mindepth 1 -maxdepth 1 | sort -V | tail -1)
    java_bin=$(dirname "$(command -v javac || timeout --foreground --kill-after=5 "$UI_SERVER_BUILD_TIMEOUT_S" mise which javac)")
    classes=$RESULTS_DIR/ui-dump-server-classes
    rm -rf "$classes"
    mkdir -p "$classes"
    log "building the device helper ($UI_SERVER_JAR)"
    if ! timeout --foreground --kill-after=5 "$UI_SERVER_BUILD_TIMEOUT_S" "$java_bin/javac" -nowarn -Xlint:none --release 17 -cp "$platform/android.jar" -d "$classes" \
        scripts/e2e-ui/UiDumpServer.java >"$RESULTS_DIR/ui-dump-server-build.log" 2>&1 ||
        ! PATH=$java_bin:$PATH timeout --foreground --kill-after=5 "$UI_SERVER_BUILD_TIMEOUT_S" "$build_tools/d8" --min-api 30 --lib "$platform/android.jar" --output "$UI_SERVER_JAR" \
            "$classes"/dev/jacobhayes/bootleg/e2e/*.class >>"$RESULTS_DIR/ui-dump-server-build.log" 2>&1; then
        cat "$RESULTS_DIR/ui-dump-server-build.log" >&2
        die "could not build the device helper"
    fi
}

install_app() {
    local output
    output=$(sh scripts/install-apk.sh "$ANDROID_SERIAL" "$APK")
    log "$output"
}

current_test=setup
test_started_ms=$(now_ms)
log "tests: ${chosen[*]}"
take_lock
USB_SERIAL=$(find_usb_serial)
WIFI_SERIAL=${BOOTLEG_WIFI_SERIAL:-$(scripts/wifi-serial.sh "$USB_SERIAL")}
export USB_SERIAL WIFI_SERIAL
use_transport wifi
phone_awake_and_unlocked || die "the phone is locked: unlock it and run again (the tests never unlock it)"
$install && install_app
[[ -n $(adb shell pm path "$PACKAGE" | tr -d '\r') ]] || die "$PACKAGE is not installed (mise run deploy, or --install)"
if [[ ${BOOTLEG_UI_SERVER:-1} == 1 ]]; then
    build_ui_server
    ui_server_stop # one left by an interrupted run may be an older build
    ui_server_start "$UI_SERVER_JAR"
    ui_server_forward
    UI_PORT_WIFI=$UI_SERVER_PORT
    UI_SERVER_PORT=""
    ANDROID_SERIAL=$USB_SERIAL ui_server_forward
    UI_PORT_USB=$UI_SERVER_PORT
    export BOOTLEG_UI_SERVER=1 BOOTLEG_UI_SERVER_JAR=$UI_SERVER_JAR UI_PORT_WIFI UI_PORT_USB
else
    export BOOTLEG_UI_SERVER=0
fi
mkdir -p "$RESULTS_DIR"
[[ -s $RESULTS_FILE ]] || echo "$RESULTS_HEADER" >"$RESULTS_FILE"
# The tests stop between tests: the running one finishes and cleans up first.
trap 'interrupted=true' INT TERM HUP
log "setup done"

# --- tests ---------------------------------------------------------------------------------------------

# test_finish: runs the test's cleanups, pulls its screenshots, writes its outcome for the runner.
test_finish() {
    local status=$? cleanup failure
    trap - EXIT INT TERM ERR
    set +e
    current_step=cleanup
    for cleanup in "${cleanups[@]}"; do
        log "cleanup: $cleanup"
        # A cleanup that fails may leave the phone changed for the next test: that fails this one.
        (eval "$cleanup") || { log "  FAIL  cleanup: $cleanup"; failures+=("cleanup failed: $cleanup"); }
    done
    pull_shots
    ((status == 0 && ${#failures[@]} > 0)) && status=1
    printf '%s\t%s\t%s\n' "${#passes[@]}" "${#failures[@]}" "$(IFS=';'; echo "${failures[*]}")" >"$RUN_DIR/$current_test.result"
    log "${#passes[@]} passed, ${#failures[@]} failed"
    for failure in "${failures[@]}"; do log "  FAILED: $failure"; done
    exit "$status"
}

run_test() {
    local name=$1 started_ms started_at status duration_ms outcome result counts=(0 0 "")
    started_ms=$(now_ms)
    started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    set +e
    (
        set -eEuo pipefail
        current_test=$name
        current_step=start
        test_started_ms=$started_ms
        passes=()
        failures=()
        cleanups=()
        export BOOTLEG_SHOTS_DIR=build/e2e-shots/$name
        rm -rf "$BOOTLEG_SHOTS_DIR"
        trap test_finish EXIT
        trap 'exit 130' INT
        trap 'exit 143' TERM
        # Name the command that stopped the test (not in $(...): those fail on purpose).
        trap '((BASH_SUBSHELL == 1)) && log "ERROR: \`$BASH_COMMAND\` failed (line $LINENO, step: $current_step)" >&2' ERR
        use_transport "${TEST_TRANSPORT[$name]}"
        log "== start: ${TEST_DESCRIPTION[$name]}"
        launch_app
        "$(test_function "$name")"
    )
    status=$?
    set -e
    duration_ms=$(($(now_ms) - started_ms))
    if [[ -f $RUN_DIR/$name.result ]]; then IFS=$'\t' read -r -a counts <"$RUN_DIR/$name.result"; fi
    if ((status == 0)); then outcome=PASS; else outcome=FAIL suite_status=1; fi
    ((status == 130 || status == 143)) && interrupted=true
    result=$(printf '%s\t%s\t%s\t%s\t%d.%d\t%s\t%s\t%s' "$RUN_ID" "$started_at" "$name" "$outcome" \
        $((duration_ms / 1000)) $((duration_ms % 1000 / 100)) "${counts[0]:-0}" "${counts[1]:-0}" "${counts[2]:-}")
    echo "$result" >>"$RESULTS_FILE"
    results+=("$(printf '%-4s %6d.%ds  %s' "$outcome" $((duration_ms / 1000)) $((duration_ms % 1000 / 100)) "$name")")
}

skip_test() {
    suite_status=1
    printf '%s\t%s\t%s\tSKIP\t0.0\t0\t0\t%s\n' "$RUN_ID" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" >>"$RESULTS_FILE"
    results+=("$(printf 'SKIP %8s  %s (%s)' "" "$1" "$2")")
}

for name in "${chosen[@]}"; do
    if $interrupted; then skip_test "$name" interrupted; continue; fi
    current_test=$name
    use_transport wifi
    if ! wifi_adb_ok || ! phone_awake_and_unlocked; then skip_test "$name" "phone locked or unreachable"; continue; fi
    run_test "$name"
done
