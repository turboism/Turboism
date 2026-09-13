#!/usr/bin/env bash
# Thin 020 wrapper. It validates one explicit bundle manifest and delegates lifecycle to the Runner.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$root/scripts/preview/run-cubism-host-validation.sh"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" \
  bash "$root/scripts/dev/worktree-id.sh")"
# Machine-specific input paths come from the ignored .env/environment entry.
# shellcheck source=/dev/null
source "$root/scripts/preview/host-validation-env.sh"
turboism_select_fixture 5303 || exit 2
fixture="${fixture_src:-}"
fixture_sha256=2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e
fixture_name=atlas_mapping_100.cmo3
official_jar_sha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166

fail() {
  printf 'atlas-image host validation: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'EOF'
Usage:
  run-atlas-image-host-validation.sh 5303 <run-label>
    [--bundle-manifest <published manifest>]
    [--prepare-dir <directory> | --dry-run]

The wrapper never builds, launches, prepares a host, installs a hook, or runs an
external collector. The queue manager must admit the blocked 020 manifest first.
EOF
  exit 2
}

[[ -n "$fixture" ]] || fail 'fixed Circle100 fixture requires TURBOISM_HOST_VALIDATION_FIXTURE_5303 in ignored .env/environment'
[[ "$fixture" = /* ]] || fail "fixed Circle100 fixture path must be absolute: $fixture"
[[ "$(basename -- "$fixture")" == "$fixture_name" ]] || fail "fixed Circle100 fixture basename mismatch: $fixture"
[[ -f "$fixture" ]] || fail "fixed Circle100 fixture is missing: $fixture"
fixture="$(realpath -e -- "$fixture")"
[[ "$(sha256sum "$fixture" | cut -d' ' -f1)" == "$fixture_sha256" ]] \
  || fail "fixed Circle100 fixture hash mismatch: $fixture"

[[ $# -ge 2 ]] || usage
version="$1"
run_label="$2"
shift 2
[[ "$version" == 5303 ]] || fail "this scene admits only exact host version 5303"
[[ "$run_label" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || fail "run label is not bounded and safe"

manifest="${TURBOISM_ATLAS_IMAGE_BUNDLE_MANIFEST:-$root/build/preview/$worktree_id/atlas-image-observe/bundle.manifest}"
prepare_dir=""
dry_run=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --bundle-manifest)
      [[ $# -ge 2 ]] || fail "missing --bundle-manifest value"
      [[ -z "${TURBOISM_ATLAS_IMAGE_BUNDLE_MANIFEST:-}" ]] || fail "manifest supplied twice"
      manifest="$2"; shift 2 ;;
    --prepare-dir)
      [[ $# -ge 2 ]] || fail "missing --prepare-dir value"
      [[ -z "$prepare_dir" && "$dry_run" == 0 ]] || fail "prepare/dry-run may be selected once"
      prepare_dir="$2"; shift 2 ;;
    --dry-run)
      [[ -z "$prepare_dir" && "$dry_run" == 0 ]] || fail "prepare/dry-run may be selected once"
      dry_run=1; shift ;;
    *)
      usage ;;
  esac
done

[[ -f "$manifest" && ! -L "$manifest" ]] || fail "explicit bundle manifest is not a regular file: $manifest"
manifest="$(realpath -e -- "$manifest")"

# Parse a tiny, non-executable key/value manifest. Unknown or duplicate keys fail closed.
declare -A values=()
while IFS='=' read -r key value || [[ -n "$key$value" ]]; do
  [[ -z "$key" ]] && continue
  [[ "$key" == \#* ]] && continue
  [[ "$key" =~ ^[A-Za-z][A-Za-z0-9]*$ ]] || fail "invalid manifest key: $key"
  [[ -n "$value" ]] || fail "empty manifest value: $key"
  [[ -z "${values[$key]+present}" ]] || fail "duplicate manifest key: $key"
  values[$key]="$value"
done < "$manifest"

required_keys=(schemaVersion scene worktreeId bundleRoot agent agentSha256 observer observerSha256 \
  driver driverSha256 fixture fixtureName fixtureSha256 officialJarSha256 runnable)
for key in "${required_keys[@]}"; do
  [[ -n "${values[$key]+present}" ]] || fail "manifest missing key: $key"
done
for key in "${!values[@]}"; do
  case "$key" in
    schemaVersion|scene|worktreeId|bundleRoot|agent|agentSha256|observer|observerSha256|driver|driverSha256|fixture|fixtureName|fixtureSha256|officialJarSha256|runnable) ;;
    *) fail "unknown manifest key: $key" ;;
  esac
done
[[ "${values[schemaVersion]}" == 1 ]] || fail "manifest schemaVersion must be 1"
[[ "${values[scene]}" == atlas-image-observe:5303 ]] || fail "manifest scene mismatch"
[[ "${values[worktreeId]}" == "$worktree_id" ]] || fail "manifest worktree ID mismatch"
[[ "${values[fixture]}" == "$fixture" ]] || fail "manifest fixture source is not the fixed reviewed source"
[[ "${values[fixtureName]}" == "$fixture_name" ]] || fail "manifest fixture name mismatch"
[[ "${values[fixtureSha256]}" == "$fixture_sha256" ]] || fail "manifest fixture hash mismatch"
[[ "${values[officialJarSha256]}" == "$official_jar_sha256" ]] || fail "manifest official JAR hash mismatch"
# The scheduler owns runnable admission; this checkout's manifest remains false until review.

for key in agentSha256 observerSha256 driverSha256; do
  [[ "${values[$key]}" =~ ^[0-9a-f]{64}$ ]] || fail "$key is not a lowercase SHA-256"
done

bundle_root="$(realpath -e -- "${values[bundleRoot]}")"
[[ -d "$bundle_root" ]] || fail "manifest bundle root is not a directory"
[[ "$bundle_root" != *latest* ]] || fail "latest artifact pointers are forbidden"
expected_bundle_root="$root/build/preview/$worktree_id/atlas-image-observe/"
[[ "$bundle_root" == "$expected_bundle_root"* ]] || fail "bundle is outside this worktree's fixed delivery root"

require_artifact() {
  local path="$1" expected="$2" label="$3"
  [[ -f "$path" && ! -L "$path" ]] || fail "$label is not a regular non-symlink file: $path"
  local actual
  actual="$(realpath -e -- "$path")"
  [[ "$actual" != *latest* ]] || fail "$label uses a latest pointer"
  [[ "$actual" == "$bundle_root"/* ]] || fail "$label is outside the manifest bundle root"
  [[ "$(basename "$actual")" == "$label" ]] || fail "$label basename mismatch: $actual"
  [[ "$(sha256sum "$actual" | cut -d' ' -f1)" == "$expected" ]] \
    || fail "$label SHA-256 mismatch: $actual"
  printf '%s' "$actual"
}

observer="$(require_artifact "${values[observer]}" "${values[observerSha256]}" atlas-image-load-probe.jar)"
driver="$(require_artifact "${values[driver]}" "${values[driverSha256]}" atlas-image-scene-driver.jar)"

agent_root="$(realpath -m -- "$root/build/preview/$worktree_id")"
agent="${values[agent]}"
[[ "$agent" != *latest* ]] || fail "production agent uses a latest pointer"
[[ "$agent" == "$agent_root"/* ]] || fail "production agent is outside this worktree's preview root"
[[ "$(basename "$agent")" == turboism-agent.jar ]] || fail "production agent basename mismatch"
[[ -f "$agent" && ! -L "$agent" ]] || fail "production agent is not a regular non-symlink file"
agent="$(realpath -e -- "$agent")"
[[ "$agent" == "$agent_root"/* ]] || fail "production agent escapes preview root after realpath"
[[ "$(sha256sum "$agent" | cut -d' ' -f1)" == "${values[agentSha256]}" ]] \
  || fail "production agent SHA-256 mismatch"

[[ -f "$runner" ]] || fail "Runner is missing: $runner"

runner_args=(
  --name atlas-image-observe
  --version 5303
  --run-label "$run_label"
  --bundle-root "$bundle_root"
  --agent "$agent"
  --aux-agent "$observer:atlas-image-load-probe.jar"
  --aux-agent "$driver:atlas-image-scene-driver.jar"
  --fixture-local "$fixture"
  --fixture-sha256 "$fixture_sha256"
  --fixture-name "$fixture_name"
  --require-fixture-unchanged
  --agent-host-class com.live2d.cubism.CEAppCtrl
  --agent-timeout 180
  --result-file state/atlas-image-observe/result.txt
  --result-pass-line status=PASS
  --result-fail-line status=FAIL
  --result-timeout 900
  --exit-timeout 120
  --jvm-option '-Dturboism.validation.atlasImageObserve.home={HOME}'
  --jvm-option '-Dturboism.validation.atlasImageObserve.taskId={TASK_ID}'
  --jvm-option '-Dturboism.validation.atlasImageObserve.fixture={FIXTURE}'
  --jvm-option '-Dturboism.validation.atlasImageObserve.fixtureName={FIXTURE_NAME}'
  --jvm-option '-Dturboism.validation.atlasImageObserve.version=5303'
  --jvm-option '-Dturboism.validation.atlasImageObserve.outputRelative=state/atlas-image-observe'
  --jvm-option '-Dturboism.validation.atlasImageObserve.timeoutSeconds=900'
)
if [[ -n "$prepare_dir" ]]; then
  runner_args+=(--prepare-dir "$prepare_dir")
elif [[ "$dry_run" == 1 ]]; then
  runner_args+=(--dry-run)
fi

# Intentionally no home-file, plugin, client program, or remote hook option.
exec bash "$runner" "${runner_args[@]}"
