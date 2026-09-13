#!/usr/bin/env bash
# Test-only 025 external PSD edit pipeline probe, through the shared local queue.
#
# Environment controls (all optional):
#   EXTERNAL_PSD_PHASE          pipeline (default) | reopen | gui
#   EXTERNAL_PSD_CYCLES         save cycles for pipeline phase
#   EXTERNAL_PSD_PERSIST=1      append the mediated SAVE_AS persist tail (pipeline only)
#   EXTERNAL_PSD_WITH_PLUGIN    path to the production external-psd-edit jar (gui phase)
#   EXTERNAL_PSD_FIXTURE_LOCAL  reopen stage: use this saved copy instead of the source
#   EXTERNAL_PSD_MARKER_*       reopen stage: layer/offset/char recorded by a persist run
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
id="$(bash "$root/scripts/dev/worktree-id.sh")"

phase="${EXTERNAL_PSD_PHASE:-pipeline}"
plugins=(--plugin "$root/build/external-psd-edit-host-probe.jar:external-psd-edit-host-probe.jar")
if [ -n "${EXTERNAL_PSD_WITH_PLUGIN:-}" ]; then
  plugins+=(--plugin "$EXTERNAL_PSD_WITH_PLUGIN:external-psd-edit.jar")
fi

if [ -n "${EXTERNAL_PSD_FIXTURE_LOCAL:-}" ]; then
  fixture=(--fixture-local "$EXTERNAL_PSD_FIXTURE_LOCAL")
else
  turboism_select_fixture 5302
  fixture=(--fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256"
    --require-fixture-unchanged)
fi

options=(
  "--jvm-option" "-Dturboism.validation.externalpsd.phase=$phase"
  "--jvm-option" '-Dturboism.validation.externalpsd.runId={TASK_ID}'
)
if [ -n "${EXTERNAL_PSD_CYCLES:-}" ]; then
  options+=("--jvm-option" "-Dturboism.validation.externalpsd.cycles=$EXTERNAL_PSD_CYCLES")
fi
if [ "${EXTERNAL_PSD_PERSIST:-0}" = "1" ]; then
  options+=(
    "--jvm-option" '-Dturboism.validation.externalpsd.persist=1'
    "--jvm-option" '-Dturboism.preview.userFileFixedGrant={HOME}/persisted-document.cmo3'
  )
fi
for key in markerLayer markerOffset markerChar; do
  var="EXTERNAL_PSD_${key^^}"
  if [ -n "${!var:-}" ]; then
    options+=("--jvm-option" "-Dturboism.validation.externalpsd.$key=${!var}")
  fi
done

exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name external-psd-edit-pipeline --version 5302 --run-label 025-us4 \
  --bundle-root "$root/build/preview/$id" \
  --agent "$root/build/preview/$id/turboism-agent.jar" \
  "${plugins[@]}" \
  "${fixture[@]}" \
  --fixture-name external-psd-edit-025.cmo3 \
  --remote-pre-launch "$root/scripts/preview/external-psd-edit-psd-association-pre-launch.sh" \
  "${options[@]}" \
  --failure-marker 'EXTERNAL_PSD_EDIT_RESULT status=BLOCKED' \
  --result-file state/dev.turboism.validation.externalpsd/external-psd-edit-result.properties \
  --result-pass-line status=PASS --result-fail-line status=FAIL \
  --result-timeout 480 --exit-timeout 90 "$@"
