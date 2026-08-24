#!/usr/bin/env bash
# Runs the isolated GraalVM script runtime against exact Cubism 5.3.02.
# The packaged wildcard allows worktree IDs, project versions, and transitive
# Graal dependency names to vary without baking them into this validation path.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run-graal-script-host-validation.sh [run-label] [options forwarded to run-cubism-host-validation.sh]

Environment:
  TURBOISM_GRAAL_JAVA
                        Required Windows-visible Graal Java executable.
  TURBOISM_GRAAL_VALIDATION_FIXTURE_REMOTE
                        Required validation-host path to the licensed fixture.
  TURBOISM_GRAAL_VALIDATION_FIXTURE_SHA256
                        Required SHA-256 binding for that fixture.
  TURBOISM_CUBISM_JAVA  Optional Windows-visible Java executable for Cubism itself.
                        The isolated script host remains a separate process.
EOF
}

run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != -* ]]; then
  run_label="$1"
  shift
fi
if [ "${1:-}" = '--help' ]; then
  usage
  exit 0
fi

fixture_src="${TURBOISM_GRAAL_VALIDATION_FIXTURE_REMOTE:-}"
fixture_sha256="${TURBOISM_GRAAL_VALIDATION_FIXTURE_SHA256:-}"
graal_java="${TURBOISM_GRAAL_JAVA:-}"
cubism_java="${TURBOISM_CUBISM_JAVA:-}"

[ -n "$fixture_src" ] || {
  echo "error: TURBOISM_GRAAL_VALIDATION_FIXTURE_REMOTE is required" >&2
  exit 2
}
[ -n "$fixture_sha256" ] || {
  echo "error: TURBOISM_GRAAL_VALIDATION_FIXTURE_SHA256 is required" >&2
  exit 2
}
[[ "$fixture_sha256" =~ ^[0-9a-fA-F]{64}$ ]] || {
  echo "error: TURBOISM_GRAAL_VALIDATION_FIXTURE_SHA256 must be 64 hex characters" >&2
  exit 2
}
[ -n "$graal_java" ] || {
  echo "error: TURBOISM_GRAAL_JAVA is required" >&2
  exit 2
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle="$repo_root/build/preview/$worktree_id"
agent="$bundle/turboism-agent.jar"
probe="${TURBOISM_GRAAL_VALIDATION_PROBE:-$repo_root/build/graal-script-host-validation-exerciser.jar}"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
script_dir="${TURBOISM_GRAAL_VALIDATION_SCRIPTS:-$repo_root/validation/graal-script-host-probe/scripts}"

[ -f "$agent" ] || { echo "error: build previewBundle first" >&2; exit 1; }
[ -f "$probe" ] || { echo "error: run validation/graal-script-host-probe/build.sh first" >&2; exit 1; }
[ -d "$bundle/graal/lib" ] || { echo "error: preview Graal libraries are missing" >&2; exit 1; }
[ -d "$script_dir" ] || { echo "error: Graal validation scripts are missing" >&2; exit 1; }

cubism_java_args=()
if [ -n "$cubism_java" ]; then
  cubism_java_args+=(
    --cubism-java "$cubism_java"
    --cubism-java-console-marker 'GraalVM CE 25.2.4'
  )
fi

exec bash "$runner" \
  --name graal-script \
  --version 5302 \
  --run-label "$run_label" \
  --bundle-root "$bundle" \
  --agent "$agent" \
  --plugin "$probe:graal-script-host-validation-exerciser.jar" \
  --home-dir "$script_dir:scripts" \
  --home-dir "$bundle/graal:graal" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.graal.enabled=true' \
  --jvm-option "-Dturboism.graal.java=$graal_java" \
  --jvm-option '-Dturboism.graal.classpath={HOME}\graal\lib\*' \
  --result-file 'state/dev.turboism.validation.graal-script/graal-script-result.txt' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --result-timeout 420 \
  --exit-timeout 120 \
  "${cubism_java_args[@]}" \
  "$@"
