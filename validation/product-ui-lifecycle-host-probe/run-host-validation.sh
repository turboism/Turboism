#!/usr/bin/env bash
# Product windows only: ACP settings never connects an external agent.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo"
source scripts/preview/host-validation-env.sh
[ "$#" -ge 1 ] || { echo 'usage: run-host-validation.sh <version-code> [runner-options...]' >&2; exit 2; }
version="$1"; shift
turboism_select_fixture "$version"
worktree_id="$(scripts/dev/worktree-id.sh)"
bundle="$repo/build/preview/$worktree_id"
plugins=()
for name in acp mcp command-palette perf-stats; do
  shopt -s nullglob
  jars=("$repo/build/worktree/$worktree_id/$name/libs/$name-"*.jar)
  [ "${#jars[@]}" = 1 ] || { echo "build exactly one $name jar first" >&2; exit 2; }
  plugins+=(--plugin "${jars[0]}:$name.jar")
done
exec bash scripts/preview/run-cubism-host-validation.sh \
 --name product-ui-lifecycle --version "$version" --run-label issues54-55-r1 \
 --bundle-root "$bundle" --agent "$bundle/turboism-agent.jar" "${plugins[@]}" \
 --home-config "$repo/validation/incremental-update-host-probe/undo-config.json" \
 --aux-agent "$repo/build/product-ui-lifecycle-host-agent.jar:product-ui-lifecycle-host-agent.jar" \
 --fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256" \
 --fixture-name product-ui-lifecycle.cmo3 --require-fixture-unchanged \
 --ready-marker 'Plugin load complete' \
 --result-file state/product-ui-lifecycle/result.txt --result-pass-line status=PASS --result-fail-line status=FAIL \
 --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
 --jvm-option '-Dturboism.validation.nativeStartupFreeOnly=true' \
 --ready-timeout 300 --result-timeout 480 --exit-timeout 30 "$@"
