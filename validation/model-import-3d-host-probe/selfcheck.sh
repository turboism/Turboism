#!/usr/bin/env bash
# T006 offline selfcheck: build, SelfTest regression, probe runs (clean +
# injected fault + determinism repeat), negative path/arg/verifier cases.
# Everything stays inside build/model-import-3d-offline/.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
REPO=$(cd "$SCRIPT_DIR/../.." && pwd -P)
. "$SCRIPT_DIR/selfcheck/paths.sh"
OUT_ROOT="$REPO/build/model-import-3d-offline"
OUT=""
DRY=0

usage() {
    cat <<'EOF'
usage: selfcheck.sh [--dry-run] [--out <dir>]

  --dry-run   print the plan; no compile, no files, no queue job, no host
  --out <dir> fresh build/model-import-3d-offline/runs/<new-id> (default: UUID)
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY=1; shift ;;
        --out) [ $# -ge 2 ] || { echo "missing value for --out" >&2; exit 3; }
               OUT=$2; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $1" >&2; usage >&2; exit 3 ;;
    esac
done

[ -n "$OUT" ] || OUT=$(new_run_path "$OUT_ROOT")
CANON=$(fresh_run_path "$OUT" "$OUT_ROOT") || exit 3
CLASSES="$CANON/classes"

echo "plan:"
echo "  classes  = $CLASSES"
echo "  selftest = java ... SelfTest (regression, expect exit 0)"
echo "  run(a)   = $CANON/run-a   (clean fixture, expect exit 0)"
echo "  run(b)   = $CANON/run-b   (fault=cell, expect exit 2)"
echo "  run(c)   = $CANON/run-c   (repeat of a, payload must be identical)"
echo "  negatives= $CANON/neg-*   (arg/path/verifier counterexamples)"
echo "  verify   = python3 $SCRIPT_DIR/selfcheck/verify_report.py"
if [ "$DRY" -eq 1 ]; then
    echo "dry-run: nothing written, nothing compiled, no queue job, no host"
    exit 0
fi

# build claims this absent run atomically. Never pre-delete or reuse outputs.
bash "$SCRIPT_DIR/build.sh" --out "$CLASSES"
trap 'code=$?; seal_run "$CANON" "$code" || exit 3' EXIT
umask 077
LOG="$CANON/selfcheck-log.txt"
(set -o noclobber; : > "$LOG")
record() { { printf '$'; printf ' %q' "$@"; printf '\n'; } >> "$LOG"; }

JAVACMD=(java -Djava.awt.headless=true -cp "$CLASSES"
    dev.turboism.validation.modelimport3d.Main)
SELFTEST=(java -Djava.awt.headless=true -cp "$CLASSES"
    dev.turboism.validation.modelimport3d.SelfTest)

run_logged() { # label, expected_exit, argv...
    local label=$1 want=$2; shift 2
    record "$@"
    set +e
    "$@" 2>&1 | tee -a "$LOG"
    local st=("${PIPESTATUS[@]}")
    set -e
    echo "exit=${st[0]}" >> "$LOG"
    if [ "${st[1]:-0}" -ne 0 ]; then
        echo "FAIL[$label]: tee/log write failed" | tee -a "$LOG"; exit 1
    fi
    if [ "${st[0]}" -ne "$want" ]; then
        echo "FAIL[$label]: expected exit $want, got ${st[0]}" \
            | tee -a "$LOG"; exit 1
    fi
}

# --- regression unit: every R1 defect must be absent ---
run_logged selftest 0 "${SELFTEST[@]}"

# --- probe runs ---
run_logged run-a 0 "${JAVACMD[@]}" --out "$CANON/run-a"
run_logged run-b 2 "${JAVACMD[@]}" --out "$CANON/run-b" --fault cell
run_logged run-c 0 "${JAVACMD[@]}" --out "$CANON/run-c"

# --- determinism: paths passed as argv, never interpolated into Python code ---
PA=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["payloadSha256"])' "$CANON/run-a/report.json")
PC=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["payloadSha256"])' "$CANON/run-c/report.json")
if [ "$PA" != "$PC" ]; then
    echo "FAIL: payloadSha256 differs run-a=$PA run-c=$PC" | tee -a "$LOG"; exit 1
fi
if ! cmp -s "$CANON/run-a/material.png" "$CANON/run-c/material.png"; then
    echo "FAIL: material.png not deterministic" | tee -a "$LOG"; exit 1
fi
echo "determinism: payloadSha256=$PA identical; material.png identical" | tee -a "$LOG"

run_logged repeat-out-refused 3 "${JAVACMD[@]}" --out "$CANON/run-a"
run_logged repeat-selfcheck-refused 3 bash "$SCRIPT_DIR/selfcheck.sh" --out "$CANON"
run_logged repeat-build-refused 3 bash "$SCRIPT_DIR/build.sh" --out "$CLASSES"
mkdir "$CANON/neg-empty"
run_logged empty-out-refused 3 "${JAVACMD[@]}" --out "$CANON/neg-empty"
run_logged unknown-fault 3 "${JAVACMD[@]}" --out "$CANON/neg-unkfault" --fault not-a-supported-fault
run_logged missing-out-value 3 "${JAVACMD[@]}" --out
run_logged out-escapes-root 3 "${JAVACMD[@]}" --out "$REPO/tmp-escape"
run_logged root-override-refused 3 "${JAVACMD[@]}" --out "$CANON/neg-root" --root "$OUT_ROOT"
run_logged raw-traversal-refused 3 "${JAVACMD[@]}" --out "$CANON/neg-empty/../neg-traversal"

# All negative-test paths are inside THIS exclusively claimed run.
NEG="$CANON/neg-symlink"
mkdir -p "$NEG/inside-real"
ln -s "$NEG/inside-real" "$NEG/inside-link"
run_logged out-symlink-inside-root 3 "${JAVACMD[@]}" --out "$NEG/inside-link/run"
ln -s "$CANON/nonexistent-target" "$NEG/dangling"
run_logged out-dangling-symlink 3 "${JAVACMD[@]}" --out "$NEG/dangling/run"
mkdir "$NEG/leaf"
echo "DO NOT OVERWRITE" > "$NEG/leaf/report.json.sentinel"
ln -s "$NEG/leaf/report.json.sentinel" "$NEG/leaf/report.json"
SB=$(sha256sum "$NEG/leaf/report.json.sentinel" | awk '{print $1}')
run_logged leaf-symlink-refused 3 "${JAVACMD[@]}" --out "$NEG/leaf"
SA=$(sha256sum "$NEG/leaf/report.json.sentinel" | awk '{print $1}')
if [ "$SB" != "$SA" ]; then
    echo "FAIL: sentinel overwritten ($SB -> $SA)" | tee -a "$LOG"; exit 1
fi
echo "leaf-symlink sentinel intact: $SA" | tee -a "$LOG"
run_logged script-symlink-out 3 bash "$SCRIPT_DIR/build.sh" --dry-run --out "$NEG/inside-link/classes"
# These rejected foreign paths need no fixture creation outside the run.
run_logged script-frozen-dir 3 bash "$SCRIPT_DIR/build.sh" --dry-run --out "$OUT_ROOT/main-review-r1/new/classes"
run_logged main-frozen-dir 3 "${JAVACMD[@]}" --out "$OUT_ROOT/main-review-r1/new"

# Verify the clean control, then isolated copies with fixed-identity controls.
# Covers the original remove-native / corrupt-PNG / zero-digest / payload / JSON
# negatives plus semantic and build-provenance regressions, not path rejection.
VPY=(python3 "$CANON/inputs/selfcheck/verify_report.py")
run_logged verify-run-a 0 "${VPY[@]}" "$CANON/run-a/report.json"
run_logged report-integrity 0 python3 "$CANON/inputs/selfcheck/test_report_integrity.py" \
    "$CANON/run-a/report.json"

echo "selfcheck PASS (offline only; native assertions remain NOT_RUN/BLOCKED)"
echo "log: $LOG"
