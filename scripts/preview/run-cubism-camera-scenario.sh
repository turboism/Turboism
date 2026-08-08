#!/usr/bin/env bash
set -euo pipefail

pid=${1:?usage: run-cubism-camera-scenario.sh PID WINDOW_TITLE_TOKEN}
title_token=${2:?usage: run-cubism-camera-scenario.sh PID WINDOW_TITLE_TOKEN}

export DISPLAY=${DISPLAY:-:0}
window=""
while read -r candidate; do
  title=$(xdotool getwindowname "$candidate" 2>/dev/null || true)
  if [[ "$title" == *"$title_token"* ]]; then
    window=$candidate
    break
  fi
done < <(xdotool search --onlyvisible --pid "$pid" 2>/dev/null || true)
[[ -n "$window" ]] || { echo "main Cubism window not found" >&2; exit 3; }

xdotool windowactivate --sync "$window"
sleep 2

# Render-load driver: XTEST mouse/keyboard input is not delivered to the
# wine/Proton-hosted Cubism window, but window-manager configure events are.
# Alternating the window size forces Cubism to re-layout and re-render its
# modeling canvas on every change (verified on host: ~90% CPU per resize
# cycle), producing real renderScene activity for the capture window.
eval "$(xdotool getwindowgeometry --shell "$window")"
for _ in $(seq 1 55); do
  xdotool windowsize "$window" $((WIDTH - 40)) $HEIGHT
  sleep 0.15
  xdotool windowsize "$window" $WIDTH $HEIGHT
  sleep 0.15
done

printf '%s\n' "$window"
