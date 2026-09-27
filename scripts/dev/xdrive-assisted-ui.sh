#!/usr/bin/env bash
# External X-input driver for boundingbox-warp-mirror assisted UI validation.
#
# XTEST delivery WORKS on this stack (verified: xdotool click reaches an X
# client). Failures in earlier rounds were (a) covering windows stealing clicks
# (Cubism's own "主页" home dialog, the cmd.exe console) and (b) coordinates —
# xdotool getwindowgeometry misreports under Wine, so the driver derives the
# AWT->X offset by pointer-hit scanning the Cubism window's real top-left.
#
# Protocol (markers stream live into turboism-home/logs/runtime/*/turboism-*.log):
#   WARP_MIRROR_UI_CLICK_TARGET seq=N x=X y=Y mod=none|ctrl
#       AWT-space click target; each unseen seq clicked in order.
#   WARP_MIRROR_UI_MAINWIN x=.. y=.. w=.. h=..
#       Largest non-probe window's AWT bounds for calibration.
#   WARP_MIRROR_PROBE_RESULT / WARP_MIRROR_PROBE_JVM_EXIT — terminal markers.
#
# Usage: xdrive-assisted-ui.sh <queue-dir>
set -u
QUEUE="$1"
export DISPLAY=${XDRIVE_DISPLAY:-:0}

log() { echo "[xdrive $(date +%H:%M:%S)] $*"; }

find_log() {
    ls -t "$QUEUE"/turboism-home/logs/runtime/*/turboism-*.log \
        "$QUEUE"/queue-*/turboism-home/logs/runtime/*/turboism-*.log 2>/dev/null | head -1
}
cubism_win() { xdotool search --name 'Live2D Cubism Editor' 2>/dev/null | head -1; }
win_at() {
    xdotool mousemove "$1" "$2" 2>/dev/null
    sleep 0.05
    xdotool getmouselocation --shell 2>/dev/null | sed -n 's/^WINDOW=//p'
}
win_name() { xdotool getwindowname "$1" 2>/dev/null; }

# Remove everything stacked above the Cubism window so clicks can't be stolen.
# "主页" is Cubism's own home dialog — close it like a user would.
clear_coverers() {
    local cw=$1 id name iter=0
    while [ $iter -lt 12 ]; do
        iter=$((iter + 1))
        local covered=""
        for probe in "120 660" "500 400" "700 500"; do
            set -- $probe
            id=$(win_at "$1" "$2")
            [ -n "$id" ] && [ "$id" != "$cw" ] && covered="$id" && break
        done
        [ -z "$covered" ] && return 0
        name=$(win_name "$covered")
        case "$name" in
            *主页*) log "closing Cubism home dialog id=$covered"; xdotool windowclose "$covered" 2>/dev/null ;;
            *选择镜像复制方向*) log "keeping plugin dialog id=$covered name=$name" ;;
            *) log "minimizing coverer id=$covered name=$name"; xdotool windowminimize "$covered" 2>/dev/null ;;
        esac
        sleep 0.4
    done
}

OFF_X=0; OFF_Y=0      # CLICK_TARGET is AWT screen-space == X pointer space
CALIBRATED=0

# Walk left/up at 10px from a hit point until the top window changes.
scan_top_left() {
    local w=$1 hx=$2 hy=$3 x y id minx=$2 miny=$3
    for x in $(seq $hx -10 0); do
        id=$(win_at "$x" "$hy")
        [ "$id" = "$w" ] && minx=$x || break
    done
    for y in $(seq $hy -10 0); do
        id=$(win_at "$minx" "$y")
        [ "$id" = "$w" ] && miny=$y || break
    done
    echo "$minx $miny"
}

# Derive OFF = X_topleft - AWT_topleft. Preferred anchor: the probe's small
# always-on-top instruction window (WARP_MIRROR_UI_ANCHOR) — its real X bounds
# are scannable even above the Cubism window. Fallback: the Cubism main window
# edge (WARP_MIRROR_UI_MAINWIN), valid only when its top-left is on-screen.
calibrate() {
    local ax ay line iw cw hx hy tl
    line=$(grep 'WARP_MIRROR_UI_ANCHOR' "$LOG" 2>/dev/null | tail -1)
    if [ -n "$line" ]; then
        iw=$(xdotool search --name 'WarpMirror' 2>/dev/null | head -1)
        ax=$(echo "$line" | sed -n 's/.* x=\(-\?[0-9]*\) .*/\1/p')
        ay=$(echo "$line" | sed -n 's/.* y=\(-\?[0-9]*\) .*/\1/p')
        if [ -n "$iw" ] && [ -n "$ax" ] && [ -n "$ay" ]; then
            xdotool windowactivate --sync "$iw" 2>/dev/null
            for y in $(seq 20 60 1000); do
                for x in $(seq 20 60 1880); do
                    [ "$(win_at "$x" "$y")" = "$iw" ] && { hx=$x; hy=$y; break 2; }
                done
            done
            if [ -n "$hx" ]; then
                tl=$(scan_top_left "$iw" "$hx" "$hy")
                OFF_X=$(( ${tl%% *} - ax )); OFF_Y=$(( ${tl##* } - ay ))
                CALIBRATED=1
                log "calibrated(instr): X tl=$tl awt=$ax,$ay off=$OFF_X,$OFF_Y"
                # CLICK_TARGET coordinates are AWT screen-space (getLocationOnScreen);
                # on Xvfb that IS the X pointer space — the frame offset would double-
                # apply decoration displacement and land ~30px low. Force identity.
                OFF_X=0; OFF_Y=0
                log "calibrated(instr): screen-space targets -> off forced to 0,0"
                return 0
            fi
            log "calibration: instruction window not hit"
        fi
    fi
    line=$(grep 'WARP_MIRROR_UI_MAINWIN' "$LOG" 2>/dev/null | tail -1)
    [ -z "$line" ] && return 1
    ax=$(echo "$line" | sed -n 's/.* x=\(-\?[0-9]*\) .*/\1/p')
    ay=$(echo "$line" | sed -n 's/.* y=\(-\?[0-9]*\) .*/\1/p')
    { [ -z "$ax" ] || [ -z "$ay" ]; } && return 1
    cw=$(cubism_win); [ -z "$cw" ] && return 1
    xdotool windowactivate --sync "$cw" 2>/dev/null
    clear_coverers "$cw"
    hx=""; hy=""
    for y in $(seq 60 80 1000); do
        for x in $(seq 60 80 1880); do
            [ "$(win_at "$x" "$y")" = "$cw" ] && { hx=$x; hy=$y; break 2; }
        done
    done
    [ -z "$hx" ] && { log "calibration: no cubism hit"; return 1; }
    tl=$(scan_top_left "$cw" "$hx" "$hy")
    OFF_X=$(( ${tl%% *} - ax )); OFF_Y=$(( ${tl##* } - ay ))
    CALIBRATED=1
    log "calibrated(main): X tl=$tl awt=$ax,$ay off=$OFF_X,$OFF_Y"
    # Same reason as above: emitted targets are already screen coordinates.
    OFF_X=0; OFF_Y=0
    log "calibrated(main): screen-space targets -> off forced to 0,0"
}

LAST_SEQ=0
DEADLINE=$(( $(date +%s) + 4200 ))
log "driver armed: queue=$QUEUE"

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    LOG=$(find_log)
    if [ -z "$LOG" ]; then sleep 2; continue; fi
    if grep -q 'WARP_MIRROR_PROBE_JVM_EXIT\|WARP_MIRROR_PROBE_RESULT' "$LOG" 2>/dev/null; then
        log "probe finished — driver exit"; break
    fi
    CW=$(cubism_win)
    if [ -n "$CW" ] && [ "$CALIBRATED" = 0 ]; then
        calibrate "$CW"
    fi

    while IFS=' ' read -r SEQ TX TY MOD; do
        [ -z "$SEQ" ] && continue
        LAST_SEQ=$SEQ
        PX=$((TX + OFF_X)); PY=$((TY + OFF_Y))
        if [ "$PX" -lt 0 ] || [ "$PY" -lt 0 ]; then
            log "skip seq=$SEQ offscreen X=$PX,$PY"
            continue
        fi
        if xdotool search --onlyvisible --name '选择镜像复制方向' >/dev/null 2>&1; then
            log "skip seq=$SEQ plugin dialog open — probe drives it"
            continue
        fi
        if [ -n "$CW" ]; then
            xdotool windowactivate --sync "$CW" 2>/dev/null
            HIT=$(win_at "$PX" "$PY")
            if [ -n "$HIT" ] && [ "$HIT" != "$CW" ]; then
                log "point $PX,$PY covered by id=$HIT name=$(win_name "$HIT" | cut -c1-30) — clearing"
                clear_coverers "$CW"
            fi
        fi
        if [ "$MOD" = "ctrl" ]; then
            xdotool keydown ctrl; sleep 0.08
            xdotool mousemove "$PX" "$PY" click 1; sleep 0.05
            xdotool keyup ctrl
        else
            xdotool mousemove "$PX" "$PY" click 1
        fi
        log "click seq=$SEQ awt=$TX,$TY -> X=$PX,$PY mod=$MOD"
        sleep 0.25
    done < <(grep 'WARP_MIRROR_UI_CLICK_TARGET' "$LOG" 2>/dev/null \
        | awk -v last="$LAST_SEQ" '{
            seq=""; x=""; y=""; mod="";
            for (i=1; i<=NF; i++) {
                if ($i ~ /^seq=/) seq=substr($i,5);
                else if ($i ~ /^x=/) x=substr($i,3);
                else if ($i ~ /^y=/) y=substr($i,3);
                else if ($i ~ /^mod=/) mod=substr($i,5);
            }
            if (seq != "" && seq+0 > last+0) print seq, x, y, mod;
        }')
    sleep 0.35
done
log "driver exit"
