#!/usr/bin/env bash
# External, read-only memory sampler for atlas queue runs. NOT part of the
# queued task boundary: it only reads cgroup v2 files of turboism-queue scopes
# (each queue job gets a fresh dedicated scope). Start before submitting jobs;
# stop by removing the sentinel file.
#
# Per active scope it writes:
#   <out>/<scope>.csv        epoch_ms,memory_current_bytes,memory_peak_bytes
#   <out>/<scope>.properties cgroupInode, first/last sample epochs, final peak
#
# usage: atlas-metrics-watch.sh OUT_DIR   (run in background)
set -u
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out=${1:-"$root/build/atlas-perf-metrics"}
slices="/sys/fs/cgroup/user.slice/user-1000.slice/user@1000.service/app.slice"
mkdir -p "$out"
sentinel="$out/.watching"
: > "$sentinel"
declare -A seen=()
echo "atlas metrics watch: out=$out scopes=$slices" >&2
while [ -f "$sentinel" ]; do
  shopt -s nullglob
  for scope_dir in "$slices"/turboism-queue-*.scope; do
    [ -d "$scope_dir" ] || continue
    scope=$(basename "$scope_dir" .scope)
    csv="$out/$scope.csv"
    if [ -z "${seen[$scope]:-}" ]; then
      seen[$scope]=1
      inode=$(stat -c %i "$scope_dir" 2>/dev/null || echo 0)
      {
        printf 'scope=%s\ncgroupInode=%s\nfirstEpochMs=%s\n' "$scope" "$inode" "$(date +%s%3N)"
      } > "$out/$scope.properties"
      printf 'epoch_ms,memory_current_bytes,memory_peak_bytes\n' > "$csv"
      echo "watching scope=$scope inode=$inode" >&2
    fi
    current=$(cat "$scope_dir/memory.current" 2>/dev/null || echo "")
    peak=$(cat "$scope_dir/memory.peak" 2>/dev/null || echo "")
    [ -n "$current" ] && printf '%s,%s,%s\n' "$(date +%s%3N)" "$current" "${peak:--1}" >> "$csv"
  done
  # Finalize scopes that disappeared (job ended): record last peak seen.
  for scope in "${!seen[@]}"; do
    [ "${seen[$scope]}" = "1" ] || continue
    [ -d "$slices/$scope.scope" ] && continue
    seen[$scope]=done
    last=$(tail -n 1 "$out/$scope.csv" 2>/dev/null | cut -d, -f3)
    printf 'lastEpochMs=%s\nfinalPeakBytes=%s\n' "$(date +%s%3N)" "${last:--1}" >> "$out/$scope.properties"
    echo "scope ended: $scope finalPeak=$last" >&2
  done
  sleep 0.5
done
echo "atlas metrics watch: stopped" >&2
