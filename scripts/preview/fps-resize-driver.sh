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

# 1. Wait for the newest fps task directory (created by the generic runner
#    before staging) that already carries the copied fixture.
task=""
start_epoch=$(date +%s)
for _ in $(seq 1 300); do
  candidate=$(ls -dt /home/local-user/TurboismValidation/fps/*/*/ 2>/dev/null | head -1)
  candidate=${candidate%/}
  if [ -n "$candidate" ] && [ "$(stat -c %Y "$candidate" 2>/dev/null || echo 0)" -ge $((start_epoch - 10)) ] \
    && [ -f "$candidate/fixture.cmo3" ]; then
    task="$candidate"
    break
  fi
  sleep 1
done
[ -n "$task" ] || exit 0

# 2. Wait for the Cubism JVM whose command line carries the task fixture path.
pid=""
for _ in $(seq 1 300); do
  while read -r p; do
    cmd=$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null || true)
    cmd=${cmd//\\/\/}
    case "$cmd" in
      *"$task/fixture.cmo3"*) pid=$p; break ;;
    esac
  done < <(pgrep -f "fixture.cmo3" 2>/dev/null || true)
  [ -n "$pid" ] && break
  sleep 1
done
[ -n "$pid" ] || exit 0
start_ticks_before=$(awk '{print $22}' "/proc/$pid/stat")

# 3. Wait for the visible fixture-titled window of that exact JVM.
win=""
for _ in $(seq 1 120); do
  while read -r c; do
    t=$(xdotool getwindowname "$c" 2>/dev/null || true)
    case "$t" in
      *fixture.cmo3*) win=$c; break ;;
    esac
  done < <(xdotool search --onlyvisible --pid "$pid" 2>/dev/null || true)
  [ -n "$win" ] && break
  sleep 1
done
[ -n "$win" ] || exit 0

# 4. Bounded resize bursts covering the exerciser sampling window; identity
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
