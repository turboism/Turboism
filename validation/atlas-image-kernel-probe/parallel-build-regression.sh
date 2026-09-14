#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REGRESSION_DIR="$(mktemp -d "${TMPDIR:-/tmp}/atlas-image-kernel-parallel.XXXXXX")"
WORKER_A_OUTPUT="$REGRESSION_DIR/worker-a.stdout"
WORKER_B_OUTPUT="$REGRESSION_DIR/worker-b.stdout"

"$SCRIPT_DIR/build.sh" > "$WORKER_A_OUTPUT" 2>&1 &
worker_a_pid=$!
"$SCRIPT_DIR/build.sh" > "$WORKER_B_OUTPUT" 2>&1 &
worker_b_pid=$!

set +e
wait "$worker_a_pid"
worker_a_status=$?
wait "$worker_b_pid"
worker_b_status=$?
set -e

extract_last() {
    local key=$1
    local file=$2
    sed -n "s/^${key}=//p" "$file" | tail -n 1
}

run_a="$(extract_last RUN_DIR "$WORKER_A_OUTPUT")"
run_b="$(extract_last RUN_DIR "$WORKER_B_OUTPUT")"
log_a="$(extract_last LOG_FILE "$WORKER_A_OUTPUT")"
log_b="$(extract_last LOG_FILE "$WORKER_B_OUTPUT")"
hash_a="$(extract_last HASH_FILE "$WORKER_A_OUTPUT")"
hash_b="$(extract_last HASH_FILE "$WORKER_B_OUTPUT")"
snapshot_a="$(extract_last SNAPSHOT_DIR "$WORKER_A_OUTPUT")"
snapshot_b="$(extract_last SNAPSHOT_DIR "$WORKER_B_OUTPUT")"
class_a="$(extract_last CLASS_DIR "$WORKER_A_OUTPUT")"
class_b="$(extract_last CLASS_DIR "$WORKER_B_OUTPUT")"

check_worker() {
    local label=$1
    local status=$2
    local run_dir=$3
    local log_file=$4
    local hash_file=$5
    local snapshot_dir=$6
    local class_dir=$7

    [[ "$status" -eq 0 ]] || {
        printf '%s worker exit status=%s\n' "$label" "$status" >&2
        return 1
    }
    [[ -n "$run_dir" && -n "$log_file" && -n "$hash_file" && -n "$snapshot_dir" && -n "$class_dir" ]]
    [[ -d "$run_dir" && -d "$snapshot_dir" && -d "$class_dir" ]]
    [[ -f "$log_file" && -f "$hash_file" ]]
    grep -Fqx 'OFFLINE_PASS' "$log_file"
    grep -Fqx 'T035_OFFLINE_PASS' "$log_file"
    grep -Fqx "RUN_DIR=$run_dir" "$log_file"
    grep -Fqx "HASH_FILE=$hash_file" "$log_file"
    (
        cd "$snapshot_dir"
        sha256sum -c "$hash_file"
    )
}

check_worker worker-a "$worker_a_status" "$run_a" "$log_a" "$hash_a" "$snapshot_a" "$class_a"
check_worker worker-b "$worker_b_status" "$run_b" "$log_b" "$hash_b" "$snapshot_b" "$class_b"
[[ "$run_a" != "$run_b" ]]
[[ "$log_a" != "$log_b" ]]
[[ "$hash_a" != "$hash_b" ]]
[[ "$class_a" != "$class_b" ]]
cmp --silent "$hash_a" "$hash_b"

printf 'parallelRegressionDir=%s\n' "$REGRESSION_DIR"
printf 'workerAOutput=%s\n' "$WORKER_A_OUTPUT"
printf 'workerBOutput=%s\n' "$WORKER_B_OUTPUT"
printf 'workerARunDir=%s\n' "$run_a"
printf 'workerBRunDir=%s\n' "$run_b"
printf '%s\n' 'parallelBuildEvidence=PASS'
printf '%s\n' 'OFFLINE_PASS'
