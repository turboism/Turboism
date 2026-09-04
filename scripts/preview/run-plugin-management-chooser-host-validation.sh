#!/usr/bin/env bash
# Exact-host two-phase validation: chooser stages a direct JAR, then restart applies and loads it.
# Usage: bash scripts/preview/run-plugin-management-chooser-host-validation.sh <5302|5203> [run-label] [runner-options...]
set -euo pipefail

# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-plugin-management-chooser-host-validation.sh <5302|5203> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-plugin-management-chooser-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
chooser_probe="$bundle_root/plugins/plugin-management-chooser-validation-probe.jar"
target="$bundle_root/fixtures/install-target.jar"
verifier="$bundle_root/plugins/plugin-management-restart-validation-probe.jar"
evidence_root="$repo_root/build/host-validation/plugin-management-direct-jar/$version/$run_label"
stage_evidence="$evidence_root/stage"
restart_evidence="$evidence_root/restart"
resume_root="$evidence_root/resume-home"
expected_sha256="$(sha256sum "$target" | cut -d' ' -f1)"
dry_run=0
for option in "$@"; do
  [ "$option" = "--dry-run" ] && dry_run=1
done

rm -rf "$evidence_root"
mkdir -p "$evidence_root"

bash "$runner" \
  --name plugin-management-direct-jar-stage \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$chooser_probe:plugin-management-chooser-validation-probe.jar" \
  --home-file "$target:fixtures/install-target.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option "-Dturboism.validation.hostVersion=$version" \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  --jvm-option '-Dturboism.validation.phase=stage' \
  --jvm-option '-Dturboism.validation.selectedJar={HOME}\fixtures\install-target.jar' \
  --jvm-option '-Dturboism.validation.expectedPluginId=dev.turboism.validation.plugin-management-chooser' \
  --ready-marker 'Plugin load complete' \
  --ready-marker 'PLUGIN_CHOOSER_PROBE_READY' \
  --failure-marker 'PLUGIN_CHOOSER_RESULT status=FAIL' \
  --result-file 'state/plugin-management-chooser-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --local-evidence-dir "$stage_evidence" \
  --ready-timeout 300 \
  --result-timeout 300 \
  --exit-timeout 120 \
  "$@"

mkdir -p "$resume_root/state/runtime/plugin-management"
if [ "$dry_run" -eq 1 ]; then
  printf '{}\n' > "$resume_root/state/runtime/plugin-management/pending.json"
else
  tar xf "$stage_evidence/turboism-home-logs-state.tar" -C "$resume_root" state/runtime/plugin-management
  [ -s "$resume_root/state/runtime/plugin-management/pending.json" ] || {
    echo "error: stage evidence did not preserve a pending install journal" >&2
    exit 1
  }
fi
restart_hook="$resume_root/plugin-management-restart-remote-pre-launch.sh"
cp "$repo_root/scripts/preview/plugin-management-restart-remote-pre-launch.sh" "$restart_hook"
printf '\n__PLUGIN_MANAGEMENT_STATE__\n' >> "$restart_hook"
tar --create --gzip --directory "$resume_root" state/runtime/plugin-management | base64 --wrap=76 >> "$restart_hook"

bash "$runner" \
  --name plugin-management-direct-jar-restart \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$verifier:plugin-management-restart-validation-probe.jar" \
  --remote-pre-launch "$restart_hook" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option "-Dturboism.validation.hostVersion=$version" \
  --jvm-option '-Dturboism.validation.phase=verify-load' \
  --jvm-option "-Dturboism.validation.expectedJarSha256=$expected_sha256" \
  --jvm-option '-Dturboism.validation.expectedPluginId=dev.turboism.validation.plugin-management-chooser' \
  --ready-marker 'PLUGIN_RESTART_PROBE_READY' \
  --failure-marker 'PLUGIN_RESTART_RESULT status=FAIL' \
  --result-file 'state/plugin-management-restart-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --local-evidence-dir "$restart_evidence" \
  --ready-timeout 300 \
  --result-timeout 300 \
  --exit-timeout 120 \
  "$@"

printf '[plugin-management-direct-jar] PASS version=%s stage=%s restart=%s\n' \
  "$version" "$stage_evidence" "$restart_evidence"
