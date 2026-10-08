#!/usr/bin/env bash
# Offline-only T029-EDGE candidate validation: compiles the edge-list reference
# simulation plus the batch-local first-hit index candidate, replays fixed and
# seeded scripts comparing per-operation state, then prints a synthetic-cost
# benchmark table. Never defines or executes official classes, never touches a
# host, prepares nothing.
set -euo pipefail
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
slice_dir="$root/validation/mesh-triangulation-hash/edge-index"

fail() {
  printf 'edge-index: %s\n' "$*" >&2
  exit 1
}

mkdir -p "$root/build/edge-index"
work="$(mktemp -d "$root/build/edge-index/compile.XXXXXX")"
classes="$work/classes"
mkdir -p "$classes"

mapfile -t sources < <(find "$slice_dir/src" -name '*.java' | sort)
[[ "${#sources[@]}" -gt 0 ]] || fail 'no sources found'
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$classes" "${sources[@]}"

java -cp "$classes" dev.turboism.validation.edgeindex.EdgeIndexSelfCheck \
  > "$work/selfcheck.log" 2>&1 || { cat "$work/selfcheck.log" >&2; fail 'self-check failed'; }
cat "$work/selfcheck.log"
grep -q '^EDGE_INDEX_SELFCHECK PASS ' "$work/selfcheck.log" \
  || fail 'self-check did not report PASS'

java -cp "$classes" dev.turboism.validation.edgeindex.EdgeIndexBenchmark \
  > "$work/bench.log" 2>&1 || { cat "$work/bench.log" >&2; fail 'benchmark failed'; }
cat "$work/bench.log"
grep -q '^EDGE_INDEX_BENCH PASS ' "$work/bench.log" || fail 'benchmark did not report PASS'

printf 'EDGE_INDEX_RUN PASS work=%s hostExecuted=false officialClassLoaded=false\n' "$work"
