#!/usr/bin/env bash
# Test-only 025 external PSD edit pipeline probe, through the shared local queue.
#
# Environment controls (all optional):
#   EXTERNAL_PSD_PHASE          pipeline (default) | reopen | gui | structure-native | structure-sdk | prepare-second-document
#   EXTERNAL_PSD_CYCLES         save cycles for pipeline phase
#   EXTERNAL_PSD_PERSIST=1      append the mediated SAVE_AS persist tail (pipeline only)
#   EXTERNAL_PSD_CONTENT_PROFILE control7 (default) | f1; passed to the validation probe
#   EXTERNAL_PSD_STRUCTURE_VARIANT add | delete | merge | canvas (structural phases only)
#   EXTERNAL_PSD_STRUCTURE_SOURCE  fixed official-writer PSD staged under task home
#   EXTERNAL_PSD_SECOND_DOCUMENT  reviewed original CMO for prepare-second-document
#   EXTERNAL_PSD_F2_ACCEPTANCE=1  prepare-second-document only: run the F2 SDK replace
#                                 + stale-write-rejection acceptance slice in the second document
#   EXTERNAL_PSD_WITH_PLUGIN    path to the production external-psd-edit jar (gui phase)
#   EXTERNAL_PSD_FIXTURE_LOCAL  reopen stage: use this saved copy instead of the source
#   EXTERNAL_PSD_POSTEDITSHA256 / EXTERNAL_PSD_POSTEDITIMAGESHA256 /
#   EXTERNAL_PSD_POSTEDITTARGETRGBSHA256
#                               reopen stage: post-edit export hashes recorded by a persist run
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
structural_input=()
case "$phase" in
  prepare-second-document)
    [[ ${EXTERNAL_PSD_CONTENT_PROFILE:-control7} == control7 && -z ${EXTERNAL_PSD_WITH_PLUGIN:-}
       && ${EXTERNAL_PSD_PERSIST:-0} == 0 ]] || {
      echo 'second-document preparation requires control7 without plugin/persist' >&2; exit 2;
    }
    [[ ${EXTERNAL_PSD_FIXTURE_LOCAL:-} == /* && -f $EXTERNAL_PSD_FIXTURE_LOCAL
       && ${EXTERNAL_PSD_SECOND_DOCUMENT:-} == /* && -f $EXTERNAL_PSD_SECOND_DOCUMENT ]] || {
      echo 'second-document preparation requires absolute first and second CMO files' >&2; exit 2;
    }
    [[ $(sha256sum -- "$EXTERNAL_PSD_FIXTURE_LOCAL" | cut -d ' ' -f 1) == 59d5aa5775e86a2917b05a7322f05f0cdcfead994000fede91ab023cab19f308
       && $(sha256sum -- "$EXTERNAL_PSD_SECOND_DOCUMENT" | cut -d ' ' -f 1) == 7ed2d0296791cca5f3ad8a7ccd955999a35dc755d32a62268cdfd0007d95b41c ]] || {
      echo 'second-document controls differ from reviewed CMO assets' >&2; exit 2;
    }
    structural_input=(--home-file "$EXTERNAL_PSD_SECOND_DOCUMENT:second-document/prepared-control.cmo3")
    if [ "${EXTERNAL_PSD_F2_ACCEPTANCE:-0}" == "1" ]; then
      options+=("--jvm-option" "-Dturboism.validation.externalpsd.f2Acceptance=true")
    fi
    # The startup home dialog remains visible indefinitely. Use the existing
    # exact-host startup hook in this task's isolated home; keep dialog gates intact.
    options+=(--home-config "$root/validation/external-psd-edit-host-probe/second-document-home-config.json")
    ;;
  structure-native|structure-sdk)
    [[ ${EXTERNAL_PSD_CONTENT_PROFILE:-} == f1 ]] || {
      echo 'structural controls require EXTERNAL_PSD_CONTENT_PROFILE=f1' >&2; exit 2;
    }
    case "${EXTERNAL_PSD_STRUCTURE_VARIANT:-}" in
      add|delete|merge|canvas) ;;
      *) echo 'unknown structural variant' >&2; exit 2 ;;
    esac
    [[ ${EXTERNAL_PSD_STRUCTURE_SOURCE:-} == /* && -f $EXTERNAL_PSD_STRUCTURE_SOURCE ]] || {
      echo 'structural controls require an absolute PSD source file' >&2; exit 2;
    }
    [[ -z ${EXTERNAL_PSD_WITH_PLUGIN:-} ]] || {
      echo 'structural controls do not run the production GUI plugin' >&2; exit 2;
    }
    structural_input=(--home-file "$EXTERNAL_PSD_STRUCTURE_SOURCE:structural-input/external-edit.psd")
    options+=(
      --jvm-option "-Dturboism.validation.externalpsd.structureVariant=$EXTERNAL_PSD_STRUCTURE_VARIANT"
      --jvm-option '-Dturboism.preview.userFileFixedGrant={HOME}/persisted-document.cmo3'
    )
    ;;
esac
if [ -n "${EXTERNAL_PSD_CYCLES:-}" ]; then
  options+=("--jvm-option" "-Dturboism.validation.externalpsd.cycles=$EXTERNAL_PSD_CYCLES")
fi
if [ -n "${EXTERNAL_PSD_CONTENT_PROFILE:-}" ]; then
  options+=(
    "--jvm-option" "-Dturboism.validation.externalpsd.contentProfile=$EXTERNAL_PSD_CONTENT_PROFILE"
  )
fi
if [ "${EXTERNAL_PSD_PERSIST:-0}" = "1" ]; then
  options+=(
    "--jvm-option" '-Dturboism.validation.externalpsd.persist=1'
    "--jvm-option" '-Dturboism.preview.userFileFixedGrant={HOME}/persisted-document.cmo3'
  )
fi
for key in postEditSha256 postEditImageSha256 postEditTargetRgbSha256; do
  var="EXTERNAL_PSD_${key^^}"
  if [ -n "${!var:-}" ]; then
    options+=("--jvm-option" "-Dturboism.validation.externalpsd.$key=${!var}")
  fi
done

# GUI dispatch is admitted only after both reviewed object-table transforms and the
# bootstrap end marker are present in this task's runtime log.  The trigger is
# deliberately fixed to the probe's context-owned state directory; it is not a
# feature result and the probe still performs all target/menu/import gates.
gui_readiness=()
if [ "$phase" = "gui" ]; then
  gui_readiness=(
    --ready-marker 'Context-menu transform applied to com/live2d/cubism/view/palette/deformer/b appendPoints=11'
    --ready-marker 'Context-menu transform applied to com/live2d/cubism/view/palette/parts/T appendPoints=22'
    --ready-marker 'Turboism Developer Preview started'
    --ready-marker 'EXTERNAL_PSD_EDIT_GUI_TRIGGER_ARMED'
    --failure-marker 'Turboism object context-menu hook disabled safely'
    --trigger 'state/dev.turboism.validation.externalpsd/gui-ready.flag'
  )
fi

exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name external-psd-edit-pipeline --version 5302 --run-label 025-us4 \
  --bundle-root "$root/build/preview/$id" \
  --agent "$root/build/preview/$id/turboism-agent.jar" \
  "${plugins[@]}" \
  "${fixture[@]}" \
  "${structural_input[@]}" \
  --fixture-name external-psd-edit-025.cmo3 \
  --remote-pre-launch "$root/scripts/preview/external-psd-edit-psd-association-pre-launch.sh" \
  "${options[@]}" \
  --failure-marker 'EXTERNAL_PSD_EDIT_RESULT status=BLOCKED' \
  --result-file state/dev.turboism.validation.externalpsd/external-psd-edit-result.properties \
  --result-pass-line status=PASS --result-fail-line status=FAIL \
  --result-timeout 480 --exit-timeout 90 "$@" "${gui_readiness[@]}"
