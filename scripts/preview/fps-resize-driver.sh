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

FPS_CUBISM_JAR_TOKEN="Live2D_Cubism.jar"

# Enumerate only Wine-hosted Cubism JVMs. The selector is deliberately not part
# of this process-type query because the parent `bash -s -- SELECTOR` command
# also carries it.
fps_cubism_java_pids() {
  ps -eo pid=,comm=,args= | awk -v jar="$FPS_CUBISM_JAR_TOKEN" '
    $2 == "java.exe" && index($0, jar) { print $1 }
  '
}

fps_cmdline_matches_cubism_selector() {
  local cmd=$1 selector=$2
  cmd=${cmd//\\/\/}
  [[ "$cmd" == *"$FPS_CUBISM_JAR_TOKEN"* && "$cmd" == *"$selector"* ]]
}

fps_resize_main() {
  # The wrapper passes a unique suffix of its purpose-named project copy. Never
  # discover a run by "newest task" because multiple validation windows may start
  # together.
  local selector=${1:-}
  [ -n "$selector" ] || return 0
  case "$selector" in */*|*\\*) return 0 ;; esac

  # 1. Wait for the Cubism JVM whose command line carries the unique project suffix.
  local pid="" p cmd
  for _ in $(seq 1 300); do
    while read -r p; do
      [[ "$p" =~ ^[0-9]+$ ]] || continue
      cmd=$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null || true)
      if fps_cmdline_matches_cubism_selector "$cmd" "$selector"; then
        pid=$p
        break
      fi
    done < <(fps_cubism_java_pids)
    [ -n "$pid" ] && break
    sleep 1
  done
  [ -n "$pid" ] || return 0
  local start_ticks_before
  start_ticks_before=$(awk '{print $22}' "/proc/$pid/stat")

  # 2. Wait for the visible project-titled window of that exact JVM.
  local win="" c t
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
  [ -n "$win" ] || return 0

  # 3. Bounded resize bursts covering the exerciser sampling window; identity
  #    and window ownership are revalidated before every resize.
  eval "$(xdotool getwindowgeometry --shell "$win" 2>/dev/null || true)"
  local end=$((SECONDS + 240))
  while [ "$SECONDS" -lt "$end" ]; do
    [ -r "/proc/$pid/stat" ] || break
    [ "$(awk '{print $22}' "/proc/$pid/stat")" = "$start_ticks_before" ] || break
    xdotool search --onlyvisible --pid "$pid" 2>/dev/null | grep -Fxq "$win" || break
    xdotool windowsize "$win" $((WIDTH - 40)) "$HEIGHT" 2>/dev/null || break
    sleep 0.15
    xdotool windowsize "$win" "$WIDTH" "$HEIGHT" 2>/dev/null || break
    sleep 0.15
  done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  fps_resize_main "$@"
fi
