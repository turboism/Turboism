#!/usr/bin/env bash
# Runs the isolated GraalVM script runtime against exact Cubism 5.3.02.
# The packaged wildcard allows worktree IDs, project versions, and transitive
# Graal dependency names to vary without baking them into this validation path.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run-graal-script-host-validation.sh [run-label] [options forwarded to run-cubism-host-validation.sh]

Environment:
  TURBOISM_GRAAL_JAVA   Windows-visible Graal Java path; default is the current
                        validation host installation if present.
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

fixture_src='/home/local-user/Documents/测试 混合模式.cmo3'
fixture_sha256='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4'
default_graal_java='Z:\home\local-user\TurboismValidation\tools\graalvm-25.2.4\bin\java.exe'
graal_java="${TURBOISM_GRAAL_JAVA:-$default_graal_java}"
cubism_java="${TURBOISM_CUBISM_JAVA:-}"

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
