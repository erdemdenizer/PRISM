#!/usr/bin/env bash
# Verifies a Linux application image of PRISM by what it really does (ADR-0018).
#
#   verify-image.sh <prism-...-linux-x64.tar.gz> <results folder> [image-probe.jar]
#
# Every check is PASS, FAIL, INFO or NOT TESTED in results.tsv; a check that was not carried out is
# never counted as passed. Exit 0 only when nothing is FAIL or NOT TESTED; the verdict is in summary.txt.
#
# Run it as an ordinary user inside a desktop session (DISPLAY or WAYLAND_DISPLAY set): the probe and
# the window start JavaFX. It starts and stops only processes of the image it unpacks. Logs, settings
# and journals go to folders of its own: XDG_STATE_HOME and XDG_CONFIG_HOME are redirected.
set -u

archive="$(readlink -f "${1:?archive}")"
results="${2:?results folder}"
probe="${3:-$(dirname "$(readlink -f "$0")")/image-probe.jar}"
mkdir -p "$results"
results="$(readlink -f "$results")"
work="$results/work"
rm -rf "$work"
mkdir -p "$work"
tsv="$results/results.tsv"
printf 'scenario\tcheck\tresult\tdetail\n' > "$tsv"
scenario=setup

rep() { # check result detail
    printf '%s\t%s\t%s\t%s\n' "$scenario" "$1" "$2" "$(printf '%s' "${3:-}" | tr '\t\n' '  ')" >> "$tsv"
    echo "$2 $scenario/$1 ${3:-}"
}
check() { # check ok(true|false) detail
    if [ "$2" = true ]; then rep "$1" PASS "${3:-}"; else rep "$1" FAIL "${3:-}"; fi
}
is() { [ "$1" = "$2" ] && echo true || echo false; }
matches() { printf '%s' "$1" | grep -Eq -- "$2" && echo true || echo false; }

# Every process whose executable is inside the given folder.
image_processes() {
    local dir="$1" pid exe
    for pid in /proc/[0-9]*; do
        exe="$(readlink "$pid/exe" 2>/dev/null)" || continue
        case "$exe" in "$dir"/*) echo "${pid#/proc/}" ;; esac
    done
}
isolated() { # name: points the XDG folders of the next start at a fresh place of its own
    export XDG_STATE_HOME="$work/$1/state" XDG_CONFIG_HOME="$work/$1/config"
    mkdir -p "$XDG_STATE_HOME" "$XDG_CONFIG_HOME"
}
log_of() { cat "$XDG_STATE_HOME"/prism-srm/logs/*.log 2>/dev/null; }

# ----- The archive -----
scenario=archive
if [ -f "$archive.sha256" ]; then
    expected="$(cut -d' ' -f1 "$archive.sha256")"
    actual="$(sha256sum "$archive" | cut -d' ' -f1)"
    check sha256 "$(is "$expected" "$actual")" "$actual"
else
    rep sha256 'NOT TESTED' "no $archive.sha256 beside the archive"
fi
tar -xzf "$archive" -C "$work"
image="$work/PRISM"
check unpacked "$( [ -d "$image" ] && echo true || echo false)" "$image"
for launcher in PRISM prism-demo; do
    check "launcher-$launcher-executable" "$( [ -x "$image/bin/$launcher" ] && echo true || echo false)" "bin/$launcher"
done
rep runtime INFO "$(grep -E '^(JAVA_VERSION|IMPLEMENTOR)=' "$image/lib/runtime/release" | tr '\n' ' ')"
desktop=false
if [ -n "${DISPLAY:-}" ] || [ -n "${WAYLAND_DISPLAY:-}" ]; then desktop=true; fi
rep desktop-session INFO "DISPLAY=${DISPLAY:-} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-}"

# ----- What the runtime provides, and SAFE-2 as a result, with no button in between -----
scenario=probe
if [ ! -f "$probe" ]; then
    rep probe 'NOT TESTED' "no probe jar at $probe"
elif [ "$desktop" != true ]; then
    rep probe 'NOT TESTED' 'no desktop session: JavaFX cannot start'
else
    # A test copy of the image with one more launcher; never delivered.
    copy="$work/probe-copy"
    cp -a "$image" "$copy"
    cp "$copy/bin/prism-demo" "$copy/bin/prism-probe"
    cp "$probe" "$copy/lib/app/image-probe.jar"
    sed -e 's/^app\.mainclass=.*/app.mainclass=prism.srm.app.ImageProbe/' \
        -e 's|^\[JavaOptions\]|app.classpath=$APPDIR/image-probe.jar\n\n[JavaOptions]\njava-options=-Dstdout.encoding=UTF-8\njava-options=-Xmx64m|' \
        "$copy/lib/app/prism-demo.cfg" > "$copy/lib/app/prism-probe.cfg"
    isolated probe
    timeout 180 "$copy/bin/prism-probe" check > "$results/probe-stdout.txt" 2> "$results/probe-stderr.txt"
    exit_code=$?
    v() { grep -m1 "^$1=" "$results/probe-stdout.txt" | cut -d= -f2-; }
    check ended "$(is "$exit_code" 0)" "exit=$exit_code last line [$(tail -1 "$results/probe-stdout.txt")]"
    check localedata-module "$(is "$(v module.jdk.localedata)" true)" "$(v modules) modules"
    check turkish-number "$(is "$(v tr.number)" '1.234.567,5')" "$(v tr.number)"
    check turkish-percent "$(matches "$(v tr.percent)" '^%[[:space:]]?25$')" "$(v tr.percent)"
    check turkish-date "$(matches "$(v tr.date)" '^15 Ocak 2026')" "$(v tr.date)"
    check turkish-upper-i "$(is "$(v tr.upper-i)" 'İ')" "$(v tr.upper-i)"
    rep product-formatter INFO "$(v tr.product-bytes)"
    check javafx-toolkit "$(is "$(v javafx.toolkit)" true)" 'Platform.startup returned on the image runtime'
    check logback-service "$(matches "$(v logging.factory)" logback)" "$(v logging.factory)"
    check platform-lists-processes "$( [ "$(v listing.processes)" -gt 0 ] 2>/dev/null && echo true || echo false)" \
        "$(v listing.processes) processes through OSHI and JNA"
    check own-row-found "$(is "$(v listing.own-row)" true)" "pid $(v pid)"
    rep java INFO "$(v java.vendor) $(v java.version) locale=$(v default.locale) oom-signal=$(v oom.signal)"
    rep control-profile INFO "$(v control.profile)"
    for action in CHANGE_PRIORITY SUSPEND END_TASK FORCE_KILL; do
        answer="$(v "safe2.$action")"
        ok=false
        if ! printf '%s' "$answer" | grep -q '^SUCCESS' && printf '%s' "$answer" | grep -q 'own process' \
            && [ "$(v "safe2.$action.alive")" = true ]; then ok=true; fi
        check "safe2-$action-refused" "$ok" "[$answer] alive afterwards=$(v "safe2.$action.alive")"
    done
    answer="$(v safe2.MANAGE)"
    ok=false
    if ! printf '%s' "$answer" | grep -q '^SUCCESS' && printf '%s' "$answer" | grep -q 'own process'; then ok=true; fi
    check safe2-MANAGE-refused "$ok" "[$answer]"
    check runtime-stopped "$(is "$(v runtime.stopped)" true)" 'real runtime shut down in the probe'
fi

# ----- One process for one PRISM, and a controlled shutdown on SIGTERM -----
scenario=single-process
if [ "$desktop" != true ]; then
    rep window 'NOT TESTED' 'no desktop session'
else
    isolated single-process
    started_at=$(date +%s%N)
    "$image/bin/PRISM" > "$results/prism-stdout.txt" 2> "$results/prism-stderr.txt" &
    pid=$!
    for _ in $(seq 1 600); do
        log_of | grep -q 'Process monitoring started' && break
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.2
    done
    rep start INFO "$(( ($(date +%s%N) - started_at) / 1000000 )) ms to monitoring"
    check started "$(log_of | grep -q 'PRISM started on Java' && echo true || echo false)" \
        "$(log_of | grep -m1 -o 'PRISM started on Java.*' | cut -c1-120)"
    check monitoring "$(log_of | grep -q 'Process monitoring started' && echo true || echo false)" 'process monitoring started'
    exe="$(readlink "/proc/$pid/exe" 2>/dev/null)"
    check launcher-runs-the-jvm "$(is "$exe" "$image/bin/PRISM")" "pid $pid exe=$exe"
    children="$(pgrep -P "$pid" | tr '\n' ' ')"
    check no-child-process "$(is "$children" '')" "children: $children"
    count="$(image_processes "$image" | wc -l)"
    check one-prism-process "$(is "$count" 1)" "$count process(es) of the image"
    check k16-reported "$(log_of | grep -q 'not validated for desktop applications' && echo true || echo false)" \
        'native limits reported as not enabled (K16)'

    # SIGTERM bypasses the controlled shutdown by design: the JVM's shutdown hook releases what the
    # limiter and OOM Guard hold and nothing else (LIFE-5). It is checked as that, not as LIFE-1.
    scenario=sigterm
    kill -TERM "$pid"
    ended=false
    for _ in $(seq 1 100); do
        if ! kill -0 "$pid" 2>/dev/null; then ended=true; break; fi
        sleep 0.2
    done
    check ended-within-20s "$ended" "SIGTERM to $pid, the shutdown hook path (LIFE-5)"
    left="$(image_processes "$image" | tr '\n' ' ')"
    check nothing-left "$(is "$left" '')" "processes of the image afterwards: $left"
    if [ "$ended" != true ]; then kill -KILL "$pid" 2>/dev/null; fi
fi

# ----- The controlled shutdown (LIFE-1) runs when the window is closed -----
# Closing the window is a desktop action this script cannot take on Wayland. It waits for it: a
# person closes the PRISM window (or a test driver sends Alt+F4 to it). Not closed in time: NOT TESTED.
scenario=window-close
if [ "$desktop" != true ]; then
    rep controlled 'NOT TESTED' 'no desktop session'
else
    isolated window-close
    "$image/bin/PRISM" > "$results/close-stdout.txt" 2> "$results/close-stderr.txt" &
    pid=$!
    for _ in $(seq 1 600); do
        log_of | grep -q 'Process monitoring started' && break
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.2
    done
    echo ">>> Close the PRISM window now (its close button or Alt+F4); waiting up to ${PRISM_VERIFY_CLOSE_WAIT:-180} s."
    closed=false
    for _ in $(seq 1 $(( ${PRISM_VERIFY_CLOSE_WAIT:-180} * 5 ))); do
        if ! kill -0 "$pid" 2>/dev/null; then closed=true; break; fi
        sleep 0.2
    done
    if [ "$closed" != true ]; then
        rep controlled 'NOT TESTED' 'the window was not closed in time'
        kill -TERM "$pid" 2>/dev/null
        sleep 5
        kill -KILL "$pid" 2>/dev/null
    else
        wait "$pid"
        rep exit-code INFO "exit=$?"
        check controlled "$(log_of | grep -q 'Shutdown complete: monitoring and its executors are stopped.' && echo true || echo false)" \
            "$(log_of | grep -E 'Shutdown (started|complete)' | sed -E 's/^.* - //' | tr '\n' ' ')"
        left="$(image_processes "$image" | tr '\n' ' ')"
        check nothing-left "$(is "$left" '')" "processes of the image afterwards: $left"
    fi
fi

# ----- The demo launcher -----
scenario=demo
isolated demo
help="$(timeout 60 "$image/bin/prism-demo" --help 2>&1)"
demo_exit=$?
check help "$( [ "$demo_exit" = 0 ] && printf '%s' "$help" | grep -q -- '--cpu' && echo true || echo false)" \
    "exit=$demo_exit $(printf '%s' "$help" | head -1)"

# ----- Verdict -----
scenario=verdict
fails=$(awk -F'\t' 'NR>1 && $3=="FAIL"' "$tsv" | wc -l)
untested=$(awk -F'\t' 'NR>1 && $3=="NOT TESTED"' "$tsv" | wc -l)
passes=$(awk -F'\t' 'NR>1 && $3=="PASS"' "$tsv" | wc -l)
{
    echo "image: $archive"
    echo "PASS $passes, FAIL $fails, NOT TESTED $untested"
    if [ "$fails" = 0 ] && [ "$untested" = 0 ]; then echo "verdict: ACCEPTED"; else echo "verdict: NOT ACCEPTED"; fi
} | tee "$results/summary.txt"
[ "$fails" = 0 ] && [ "$untested" = 0 ]
