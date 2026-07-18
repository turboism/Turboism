#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKTREE_ID="${TURBOISM_WORKTREE_ID:-$(bash "$ROOT/scripts/dev/worktree-id.sh")}"
BUNDLE="$ROOT/build/worktree/$WORKTREE_ID/tests/migration-suite-safe"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

EXPECTED=(
  roster.tsv
  neighbors/demo.jar
  neighbors/project-inspector.jar
  targets/bounding-box.jar
  targets/clip-mask.jar
  targets/context-menu.jar
  targets/log-filter.jar
  targets/main-toolbar.jar
  targets/mesh.jar
  targets/parameter.jar
  targets/perf-opt.jar
  targets/project-panel.jar
  targets/psd-import.jar
  targets/render-opt.jar
  targets/texture-atlas.jar
  targets/ui-theme.jar
)

build_and_hash() {
  local run="$1"
  TURBOISM_MIGRATION_SUITE_REPRO_INNER=1 \
    "$ROOT/gradlew" :tests:migrationSuiteSafeBundle \
      --rerun-tasks --no-build-cache --offline --console=plain --no-daemon

  [[ -d "$BUNDLE" ]] || { echo "missing bundle directory: $BUNDLE" >&2; exit 1; }

  local actual="$TMP/actual-$run.txt"
  (cd "$BUNDLE" && find . -type f -printf '%P\n' | LC_ALL=C sort) > "$actual"
  printf '%s\n' "${EXPECTED[@]}" | LC_ALL=C sort > "$TMP/expected.txt"
  diff -u "$TMP/expected.txt" "$actual"

  : > "$TMP/sha-$run.txt"
  local file
  for file in "${EXPECTED[@]}"; do
    sha256sum "$BUNDLE/$file" | awk -v name="$file" '{print $1 "  " name}' >> "$TMP/sha-$run.txt"
  done
}

cd "$ROOT"
build_and_hash 1
build_and_hash 2
diff -u "$TMP/sha-1.txt" "$TMP/sha-2.txt"

echo "migration-suite bundle reproducibility: PASS (16/16 raw SHA-256 values identical)"
cat "$TMP/sha-2.txt"
