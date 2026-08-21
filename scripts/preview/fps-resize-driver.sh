#!/usr/bin/env bash
# Bounded host-side resize driver for the FPS counting exerciser session.
#
# The Cubism editor viewport repaints on demand, so a static fixture may not
# produce any renderScene call inside the exerciser's sampling window. This
# driver alternates the editor window size (WM configure events are delivered
# to wine/Proton windows; XTEST input is not) to force modeling-canvas
# re-layout/re-render, matching the legacy camera-scenario approach.
#
# Safety: it only ever touches windows of the Cubism JVM whose command line
# carries the exact task fixture path, and it revalidates the JVM start tick
# and window ownership before every resize burst. It never signals or cleans
# any process.
set -u
export DISPLAY=${DISPLAY:-:0}

# The wrapper passes a unique suffix of its purpose-named project copy. Never
# discover a run by "newest task" because multiple validation windows may start
# together.
selector=${1:-}
[ -n "$selector" ] || exit 0
case "$selector" in */*|*\\*) exit 0 ;; esac

# 1. Wait for the Cubism JVM whose command line carries the unique project suffix.
pid=""
for _ in $(seq 1 300); do
  while read -r p; do
    cmd=$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null || true)
    cmd=${cmd//\\/\/}
    case "$cmd" in
      *"$selector"*) pid=$p; break ;;
    esac
  done < <(pgrep -f -- "$selector" 2>/dev/null || true)
  [ -n "$pid" ] && break
  sleep 1
done
[ -n "$pid" ] || exit 0
start_ticks_before=$(awk '{print $22}' "/proc/$pid/stat")

# 2. Wait for the visible project-titled window of that exact JVM.
win=""
for _ in $(seq 1 120); do
  while read -r c; do
    t=$(xdotool getwindowname "$c" 2>/dev/null || true)
    case "$t" in
      *"$selector"*) win=$c; break ;;
    esac
  done < <(xdotool search --onlyvisible --pid "$pid" 2>/dev/null || true)
  [ -n "$win" ] && break
  sleep 1
done
[ -n "$win" ] || exit 0

# 3. Bounded resize bursts covering the exerciser sampling window; identity
#    and window ownership are revalidated before every resize.
eval "$(xdotool getwindowgeometry --shell "$win" 2>/dev/null || true)"
end=$((SECONDS + 240))
while [ "$SECONDS" -lt "$end" ]; do
  [ -r "/proc/$pid/stat" ] || break
  [ "$(awk '{print $22}' "/proc/$pid/stat")" = "$start_ticks_before" ] || break
  xdotool search --onlyvisible --pid "$pid" 2>/dev/null | grep -Fxq "$win" || break
  xdotool windowsize "$win" $((WIDTH - 40)) "$HEIGHT" 2>/dev/null || break
  sleep 0.15
  xdotool windowsize "$win" "$WIDTH" "$HEIGHT" 2>/dev/null || break
  sleep 0.15
done
exit 0
