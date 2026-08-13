#!/usr/bin/env bash
# Samples /proc metrics for one Java process while its exact identity holds.
#
# Usage:
#   collect-cubism-process-metrics.sh PID OUTPUT.csv [INTERVAL_SECONDS] [EXPECTED_START_TICKS]
#
# The start tick is enforced on every iteration: with EXPECTED_START_TICKS the
# probe runner passes its already recorded value; without it (legacy
# three-argument baseline callers) the numeric tick is snapshotted once at
# collector startup and then enforced identically. A missing/disappeared
# process before the initial snapshot fails without producing a sample, and a
# reused Java PID is never measured on any path.
#
# Offline self-test (no host launch):
#   bash collect-cubism-process-metrics.sh --self-test
set -euo pipefail

if [[ "${1:-}" == "--self-test" ]]; then
  failures=0
  # Four-argument path: a mismatched expected tick must fail without samples.
  sleep 30 &
  child=$!
  out=$(mktemp)
  if "$0" "$child" "$out" 1 "0" >/dev/null 2>&1; then
    echo "FAIL: metrics collection accepted a mismatched expected tick" >&2
    failures=$((failures + 1))
  fi
  rows=$(tail -n +2 "$out" | wc -l)
  if [[ "$rows" -ne 0 ]]; then
    echo "FAIL: metrics collection produced $rows sample rows on a mismatched tick" >&2
    failures=$((failures + 1))
  fi
  rm -f "$out"
  kill "$child" 2>/dev/null || true
  wait "$child" 2>/dev/null || true

  # Three-argument path: records for a live exact child, then exits after the
  # child ends (identity snapshot enforced from startup).
  sleep 30 &
  child=$!
  out=$(mktemp)
  "$0" "$child" "$out" 0.2 >/dev/null 2>&1 &
  collector=$!
  waited=0
  while [[ "$waited" -lt 50 ]] && [[ "$(tail -n +2 "$out" 2>/dev/null | wc -l)" -lt 1 ]]; do
    sleep 0.2
    waited=$((waited + 1))
  done
  rows=$(tail -n +2 "$out" 2>/dev/null | wc -l)
  if [[ "$rows" -lt 1 ]]; then
    echo "FAIL: three-argument collection recorded no samples for a live child" >&2
    failures=$((failures + 1))
  fi
  kill "$child" 2>/dev/null || true
  wait "$child" 2>/dev/null || true
  if ! wait "$collector" 2>/dev/null; then
    echo "FAIL: three-argument collection did not exit after the child ended" >&2
    failures=$((failures + 1))
  fi
  rm -f "$out"

  # Three-argument path: an unavailable/invalid initial PID fails without a
  # sample row.
  out=$(mktemp)
  if "$0" 2147483647 "$out" 1 >/dev/null 2>&1; then
    echo "FAIL: three-argument collection accepted an unavailable initial PID" >&2
    failures=$((failures + 1))
  fi
  rows=$(tail -n +2 "$out" | wc -l)
  if [[ "$rows" -ne 0 ]]; then
    echo "FAIL: three-argument collection produced $rows sample rows for an unavailable PID" >&2
    failures=$((failures + 1))
  fi
  rm -f "$out"

  if [[ "$failures" -ne 0 ]]; then
    echo "collect-cubism-process-metrics self-test: FAIL ($failures)" >&2
    exit 1
  fi
  echo "collect-cubism-process-metrics self-test: PASS"
  exit 0
fi

pid=${1:?usage: collect-cubism-process-metrics.sh PID OUTPUT.csv [INTERVAL_SECONDS] [EXPECTED_START_TICKS]}
output=${2:?usage: collect-cubism-process-metrics.sh PID OUTPUT.csv [INTERVAL_SECONDS] [EXPECTED_START_TICKS]}
interval=${3:-1}
expected_ticks=${4:-}

case "$pid" in (*[!0-9]*|'') echo "PID must be numeric" >&2; exit 2;; esac
[[ "$interval" =~ ^[0-9]+([.][0-9]+)?$ ]] || { echo "interval must be numeric" >&2; exit 2; }

mkdir -p "$(dirname "$output")"
printf 'epoch_ms,pid,cpu_ticks,rss_bytes,threads,read_bytes,write_bytes\n' > "$output"
page_size=$(getconf PAGESIZE)

# Establish the enforced identity before any sample: the caller-provided tick,
# or a startup snapshot for the legacy three-argument interface. A process that
# is missing or disappears here fails without a sample.
if [[ -z "$expected_ticks" ]]; then
  [[ -r "/proc/$pid/stat" ]] || {
    echo "metrics: Java pid $pid is not available at collector startup" >&2
    exit 3
  }
  expected_ticks=$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null) || {
    echo "metrics: Java pid $pid disappeared before its identity snapshot" >&2
    exit 3
  }
fi
[[ "$expected_ticks" =~ ^[0-9]+$ ]] || { echo "expected start ticks must be numeric" >&2; exit 2; }

while :; do
  [[ -r "/proc/$pid/stat" ]] || break
  # One /proc/<pid>/stat snapshot per iteration; verify the start tick before
  # recording and stop when it differs or disappears.
  stat=$(cat "/proc/$pid/stat" 2>/dev/null) || break
  rest=${stat#*) }
  read -r -a fields <<< "$rest"
  ticks=${fields[19]:-}
  if [[ "$ticks" != "$expected_ticks" ]]; then
    echo "metrics: Java pid $pid start tick changed; stopping (expected $expected_ticks, saw $ticks)" >&2
    exit 3
  fi
  epoch_ms=$(date +%s%3N)
  # /proc/PID/stat fields after comm: state is field 3, so array index 11/12/17/21
  # map to utime/stime/num_threads/rss, and index 19 to starttime (field 22).
  cpu_ticks=$((fields[11] + fields[12]))
  threads=${fields[17]}
  rss_bytes=$((fields[21] * page_size))
  read_bytes=0
  write_bytes=0
  if [[ -r "/proc/$pid/io" ]]; then
    read_bytes=$(awk '$1=="read_bytes:" {print $2}' "/proc/$pid/io")
    write_bytes=$(awk '$1=="write_bytes:" {print $2}' "/proc/$pid/io")
  fi
  printf '%s,%s,%s,%s,%s,%s,%s\n' \
    "$epoch_ms" "$pid" "$cpu_ticks" "$rss_bytes" "$threads" "$read_bytes" "$write_bytes" >> "$output"
  sleep "$interval"
done
