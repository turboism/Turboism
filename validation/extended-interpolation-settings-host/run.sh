#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
mode="${1:?mode edit or restart required}"
shift
case "$mode" in
  edit) restart=false; config="$root/validation/extended-interpolation-settings-host/config-off.json" ;;
  restart) restart=true; config="${1:?saved config required}"; shift ;;
  *) exit 2 ;;
esac
worktree="$(bash "$root/scripts/dev/worktree-id.sh")"
bundle="$root/build/preview/$worktree"
exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name extended-interpolation-settings --version 5304 --run-label "$mode" \
  --bundle-root "$bundle" --agent "$bundle/turboism-agent.jar" --home-config "$config" \
  --aux-agent "$root/build/extended-interpolation-settings-probe.jar" \
  --aux-agent "$root/build/native-startup-assistance-agent.jar" \
  --fixture-host "$TURBOISM_HOST_VALIDATION_FIXTURE_5303" \
  --fixture-sha256 "$TURBOISM_HOST_VALIDATION_FIXTURE_5303_SHA256" \
  --require-fixture-unchanged --ready-marker 'Turboism Developer Preview started' \
  --trigger state/extended-settings/start.flag --result-file state/extended-settings/result.properties \
  --jvm-option '-Dturboism.validation.startupAssistance=true' \
  --jvm-option '-Dturboism.validation.extendedSettings.home={HOME}' \
  --jvm-option "-Dturboism.validation.extendedSettings.restart=$restart" \
  --ready-timeout 600 --result-timeout 180 --exit-timeout 120 "$@"
