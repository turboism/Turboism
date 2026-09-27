#!/usr/bin/env bash
# Thin blocked T040 wrapper. The unified Runner remains the only lifecycle owner.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$root/scripts/preview/run-cubism-host-validation.sh"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" \
  bash "$root/scripts/dev/worktree-id.sh")"
# shellcheck source=/dev/null
source "$root/scripts/preview/host-validation-env.sh"
# `turboism_select_fixture` also assigns `fixture_sha256` from an unrelated .env key, so the
# reviewed pairs live in their own variables and are re-asserted after every selector call.
circle100_fixture_name=atlas_mapping_100.cmo3
circle100_fixture_sha256=2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e
fixture_name="$circle100_fixture_name"
fixture_sha256="$circle100_fixture_sha256"
# The production-scale fixture lives behind its own key so the Circle100 workflow cannot be
# switched by accident; the pair is resolve below by run label and always hash-checked.
heavy_fixture_name=heavy.cmo3
heavy_fixture_sha256=029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c
# The 5203 profile has exactly one reviewed pair — the opacity fixture the mcp 5203 wrapper
# already pins. Its canonical name is fixed regardless of the local file's basename, so the
# driver allowlist sees the same reviewed name on every machine.
opacity52_fixture_name=part-opacity-fixture-52-final.cmo3
opacity52_fixture_sha256=331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028
runtime_source_binding=target-pd

fail() {
  printf 'atlas-image-shadow host validation: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'EOF'
Usage:
  run-atlas-image-shadow-host-validation.sh <5303|5203> <run-label>
    [--bundle-manifest <published manifest>]
    [--prepare-dir <directory> | --dry-run]

The T040 manifest is intentionally runnable=false until manager admission. This
wrapper does not build, launch, prepare in this offline checkout, install hooks,
or run a client/collector. The 5203 profile runs the same UI scene without the
5303-only T039 shadow agent; its bundle manifest omits every t039* key.
EOF
  exit 2
}

[[ $# -ge 2 ]] || usage
version="$1"
run_label="$2"
shift 2
case "$version" in
  5303)
    official_jar_sha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166
    t039_class_sha256=ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6
    t039_shape_sha256=a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f
    # Fixed path inside Runner's isolated 5303 Proton prefix, not a build-host path.
    runtime_trusted_source_path='C:\Program Files\Live2D Cubism 5.3.03\app\lib\Live2D_Cubism.jar'
    ;;
  5203)
    official_jar_sha256=bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd
    t039_class_sha256=''
    t039_shape_sha256=''
    runtime_trusted_source_path='C:\Program Files\Live2D Cubism 5.2\app\lib\Live2D_Cubism.jar'
    ;;
  *) fail 'T040 admits only exact host versions 5303 and 5203' ;;
esac
[[ "$run_label" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || fail 'run label is not bounded and safe'

# Fixture profile and wall-time budgets. `-heavy` selects the production-scale model and the
# budgets it can outgrow; every other label keeps the exact Circle100 numbers this scene has
# always used, so an unset property can never silently relax a budget. The 5203 profile has a
# single reviewed fixture, so -heavy has no meaning there and fails closed.
[[ "$run_label" == *-heavy* && "$version" != 5303 ]] \
  && fail "-heavy is a 5303-only fixture profile"
fixture_env_key="TURBOISM_HOST_VALIDATION_FIXTURE_$version"
heavy_fixture_env_key="TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_$version"
startup_seconds=240
close_poll_seconds=240
layout_dialog_seconds=60
driver_timeout_seconds=900
result_timeout_seconds=900
exit_timeout_seconds=120
# Optional dwell between EDITOR CONFIRMED and OK — lets lazily-triggered atlas
# work (deferred per-page texture generation) run before the editor closes.
# Env-driven, bounded, unset = 0 = previous behavior.
editor_settle_seconds="${TURBOISM_ATLAS_IMAGE_SHADOW_EDITOR_SETTLE_SECONDS:-0}"
[[ "$editor_settle_seconds" =~ ^[0-9]{1,4}$ ]] \
  || fail 'editor settle seconds must be 0-9999'
# Read-only menu-tree enumeration for fixed-contract label discovery (export path).
# Env-driven, default off; the driver writes run/menu-tree.txt next to stage evidence.
menu_dump="${TURBOISM_ATLAS_IMAGE_SHADOW_MENU_DUMP:-false}"
[[ "$menu_dump" == "true" || "$menu_dump" == "false" ]] \
  || fail 'menu dump must be true or false'
# Export-dialog shape discovery: opens the host's moc3 export dialog, dumps its
# component tree, then closes it via window-close. No export is ever confirmed.
export_probe="${TURBOISM_ATLAS_IMAGE_SHADOW_EXPORT_PROBE:-false}"
[[ "$export_probe" == "true" || "$export_probe" == "false" ]] \
  || fail 'export probe must be true or false'
# Real-order export observation: the probe runs after the editor round-trip so the
# export pipeline sees caches produced by the editor pass.
export_after_editor="${TURBOISM_ATLAS_IMAGE_SHADOW_EXPORT_AFTER_EDITOR:-false}"
[[ "$export_after_editor" == "true" || "$export_after_editor" == "false" ]] \
  || fail 'export-after-editor flag must be true or false'
# Second editor open→OK round-trip: exercises cache-reuse verdicts on the reopened
# editor's fresh atlas instances.
editor_reopen="${TURBOISM_ATLAS_IMAGE_SHADOW_EDITOR_REOPEN:-false}"
[[ "$editor_reopen" == "true" || "$editor_reopen" == "false" ]] \
  || fail 'editor-reopen flag must be true or false'
case "$run_label" in
  *-heavy*)
    fixture_env_key="$heavy_fixture_env_key"
    fixture="${!heavy_fixture_env_key:-}"
    fixture_name="$heavy_fixture_name"
    fixture_sha256="$heavy_fixture_sha256"
    startup_seconds=1800
    close_poll_seconds=1800
    layout_dialog_seconds=900
    driver_timeout_seconds=3600
    result_timeout_seconds=3600
    exit_timeout_seconds=300
    ;;
  *)
    # The default fixture stays behind the reviewed per-version key; the heavy profile above
    # must not depend on that key being configured. Under 5203 the canonical name is the
    # reviewed opacity52 basename rather than the local file's own name.
    turboism_select_fixture "$version" || exit 2
    fixture="${fixture_src:-}"
    if [[ "$version" == 5203 ]]; then
      fixture_name="$opacity52_fixture_name"
      fixture_sha256="$opacity52_fixture_sha256"
    else
      fixture_name="$circle100_fixture_name"
      fixture_sha256="$circle100_fixture_sha256"
    fi
    ;;
esac
[[ -n "$fixture" ]] || fail "fixture requires $fixture_env_key in ignored .env/environment"
[[ "$fixture" = /* ]] || fail "fixture source path must be absolute: $fixture"
if [[ "$version" == 5303 ]]; then
  [[ "$(basename -- "$fixture")" == "$fixture_name" ]] \
    || fail "fixture source basename mismatch: $fixture"
fi
[[ -f "$fixture" && ! -L "$fixture" ]] || fail "fixture source is not a regular non-symlink file"
fixture="$(realpath -e -- "$fixture")"
[[ "$(sha256sum "$fixture" | cut -d' ' -f1)" == "$fixture_sha256" ]] \
  || fail "allowlisted $fixture_name hash mismatch: $fixture"

# The layout scale is this scene's only per-job parameter, and the queue hands the wrapper
# exactly {version} and {runLabel}: so it is selected here from an explicit, strictly
# allowlisted run-label suffix. An unknown -scale<digits> suffix fails closed instead of
# silently collecting evidence from the wrong side of the host's 0.45 kernel threshold.
layout_scale_percent=40
layout_scale_matches=0
remaining_label="$run_label"
while [[ "$remaining_label" == *-scale* ]]; do
  [[ "$remaining_label" =~ -scale([0-9]+)(.*)$ ]] \
    || fail "run label has a malformed layout scale: $run_label"
  case "${BASH_REMATCH[1]}" in
    40|60) layout_scale_percent="${BASH_REMATCH[1]}" ;;
    *) fail "run label selects an unknown layout scale: $run_label" ;;
  esac
  layout_scale_matches=$((layout_scale_matches + 1))
  remaining_label="${BASH_REMATCH[2]}"
done
[[ "$layout_scale_matches" -le 1 ]] \
  || fail "run label selects the layout scale more than once: $run_label"

# `-nolayout` models the "load / keep the texture set" path: the editor is opened and confirmed
# without touching any layout. Default stays the auto-layout scenario.
layout_mode=auto-scale
case "$run_label" in
  *-nolayout*) layout_mode=preserve ;;
esac

# Triangulation-hash A/B. `-meshprobe` installs the observation-only agent (identical
# instrumentation, native hash); `-meshfix` installs the same agent with the corrected hash. Both
# need an explicitly supplied agent JAR, and both write their digest into the task home.
mesh_hash_mode=''
case "$run_label" in
  *-meshfix*) mesh_hash_mode=fix ;;
  *-meshprobe*) mesh_hash_mode=probe ;;
esac
mesh_hash_agent=''
mesh_hash_agent_sha256=''
# The official class bytes are pinned by the JAR hash, so this per-class digest is stable.
mesh_hash_triple_sha256=6f06427c59d3907fe0d4ec80c72a318410d2e5169e18a8263ddfaa526813bd90
if [[ -n "$mesh_hash_mode" ]]; then
  mesh_hash_agent="${TURBOISM_MESH_HASH_AGENT:-}"
  [[ -n "$mesh_hash_agent" ]] \
    || fail 'mesh hash labels require TURBOISM_MESH_HASH_AGENT'
  [[ "$mesh_hash_agent" = /* ]] || fail 'mesh hash agent path must be absolute'
  [[ -f "$mesh_hash_agent" && ! -L "$mesh_hash_agent" ]] \
    || fail 'mesh hash agent is not a regular non-symlink file'
  mesh_hash_agent="$(realpath -e -- "$mesh_hash_agent")"
  [[ "$(basename -- "$mesh_hash_agent")" == mesh-hash-agent.jar ]] \
    || fail 'mesh hash agent basename must be mesh-hash-agent.jar'
  mesh_hash_agent_sha256="$(sha256sum "$mesh_hash_agent" | cut -d' ' -f1)"
fi

# `-meshoff` stages a reviewed home config carrying meshTriangulationHashFix=false, so the
# production premain preference gate — not the hook policy and not a missing schema field —
# takes the native path. It must pair with `-meshprobe`: only the observation-only agent can
# witness that the target class stayed unpatched; a fix-mode agent would mask the outcome.
mesh_pref_off=0
case "$run_label" in
  *-meshoff*) mesh_pref_off=1 ;;
esac
mesh_off_config=''
mesh_off_config_sha256=4f5ce6bd90565daa5d4c7bf0077f30ad46e65367e499d16200403494b68a3c11
if [[ "$mesh_pref_off" == 1 ]]; then
  [[ "$mesh_hash_mode" == probe ]] \
    || fail '-meshoff requires -meshprobe observation and forbids -meshfix'
  mesh_off_config="$root/validation/mesh-triangulation-hash/home-config-meshoff.json"
  [[ -f "$mesh_off_config" && ! -L "$mesh_off_config" ]] \
    || fail 'mesh-off home config is not a regular non-symlink file'
  mesh_off_config="$(realpath -e -- "$mesh_off_config")"
  [[ "$(sha256sum "$mesh_off_config" | cut -d' ' -f1)" == "$mesh_off_config_sha256" ]] \
    || fail 'mesh-off home config hash mismatch'
fi
# The mesh-hash agent targets a 5303-reviewed class; its labels stay 5303-only.
if [[ -n "$mesh_hash_mode" || "$mesh_pref_off" == 1 ]]; then
  [[ "$version" == 5303 ]] || fail 'mesh hash labels are 5303-only'
fi

# `-atlasoff` stages a reviewed home config carrying atlasTileBbox=false and
# atlasCacheReuse=false, so the production premain preference gate — not the hook
# policy and not a missing schema field — keeps both atlas optimizations unpatched.
# It is required by -tilepatch (tiled): the production transformer and the
# validation agent both rewrite the same class, and whichever ran first would
# silently decide which kernel the run measured. -tilepatchref (hashOnly) never
# rewrites that class — it may pair with -atlasoff for a pure reference run, or
# omit it to record page digests under the production-patched kernel.
# `-reuseoff` stages a config with only atlasCacheReuse=false: the tile-bbox
# kernel stays on, isolating the cache-reuse guard's contribution.
atlas_pref_off=0
atlas_reuse_pref_off=0
# Independent flag detection: `case` stops at the first match, so a label carrying
# both suffixes must still set both flags for the mutual-exclusion check below.
if [[ "$run_label" == *-atlasoff* ]]; then atlas_pref_off=1; fi
if [[ "$run_label" == *-reuseoff* ]]; then atlas_reuse_pref_off=1; fi
atlas_off_config=''
atlas_off_config_sha256=50bb2216f556c1ef6c5ae6408638aed6e1d90007fcd430c2fe9d0b8411b4c978
atlas_reuse_off_config_sha256=27879fd5a48fb5ace98ad0b5b85a92a217b3bf340a7178f947e1ce515012c6ed
if [[ "$atlas_pref_off" == 1 ]]; then
  [[ "$mesh_pref_off" == 0 ]] \
    || fail '-atlasoff cannot combine with -meshoff: a single home config is staged'
  atlas_off_config="$root/validation/atlas-image-tile-patch/home-config-atlasoff.json"
  [[ -f "$atlas_off_config" && ! -L "$atlas_off_config" ]] \
    || fail 'atlas-off home config is not a regular non-symlink file'
  atlas_off_config="$(realpath -e -- "$atlas_off_config")"
  [[ "$(sha256sum "$atlas_off_config" | cut -d' ' -f1)" == "$atlas_off_config_sha256" ]] \
    || fail 'atlas-off home config hash mismatch'
fi
reuse_off_config=''
if [[ "$atlas_reuse_pref_off" == 1 ]]; then
  [[ "$mesh_pref_off" == 0 && "$atlas_pref_off" == 0 ]] \
    || fail '-reuseoff cannot combine with -meshoff/-atlasoff: one home config is staged'
  reuse_off_config="$root/validation/atlas-image-tile-patch/home-config-atlasreuseoff.json"
  [[ -f "$reuse_off_config" && ! -L "$reuse_off_config" ]] \
    || fail 'reuse-off home config is not a regular non-symlink file'
  reuse_off_config="$(realpath -e -- "$reuse_off_config")"
  [[ "$(sha256sum "$reuse_off_config" | cut -d' ' -f1)" == "$atlas_reuse_off_config_sha256" ]] \
    || fail 'reuse-off home config hash mismatch'
fi

# `-atlastiming` installs the validation-only per-call timing agent: stack-neutral enter/exit
# weaving on the seven reviewed 5303 atlas-path methods, writing per-call records and a summary
# inside the task home. It must not pair with `-meshprobe`/`-meshfix`: that agent's reflective
# digest inside updateMesh would distort the measured durations.
timing_agent=''
timing_agent_sha256=''
case "$run_label" in
  *-atlastiming*)
    [[ -z "$mesh_hash_mode" ]] \
      || fail '-atlastiming forbids mesh agents: their probe work distorts measured durations'
    timing_agent="${TURBOISM_ATLAS_TIMING_AGENT:-}"
    [[ -n "$timing_agent" ]] \
      || fail 'atlastiming labels require TURBOISM_ATLAS_TIMING_AGENT'
    [[ "$timing_agent" = /* ]] || fail 'atlas timing agent path must be absolute'
    [[ -f "$timing_agent" && ! -L "$timing_agent" ]] \
      || fail 'atlas timing agent is not a regular non-symlink file'
    timing_agent="$(realpath -e -- "$timing_agent")"
    [[ "$(basename -- "$timing_agent")" == atlas-timing-agent.jar ]] \
      || fail 'atlas timing agent basename must be atlas-timing-agent.jar'
    timing_agent_sha256="$(sha256sum "$timing_agent" | cut -d' ' -f1)"
    ;;
esac

# `-tilepatch` / `-tilepatchref` install the validation-only kernel-patch agent:
# `ref` = hash-only reference run (page-hash hook only, pipeline untouched);
# `-tilepatch` = same hook + the private workaround body replaced by the
# bbox-scratch delegate. Both write per-page SHA-256 digests into the task home;
# equal digests across the pair prove real-host pixel equivalence. The label may
# combine with -atlastiming (different methods — chaining is stack-neutral).
tilepatch_agent=''
tilepatch_agent_sha256=''
tilepatch_mode=''
case "$run_label" in
  *-tilepatchref*) tilepatch_mode=hashOnly ;;
  *-tilepatch*)    tilepatch_mode=tiled ;;
esac
if [[ -n "$tilepatch_mode" ]]; then
  tilepatch_agent="${TURBOISM_TILE_PATCH_AGENT:-}"
  [[ -n "$tilepatch_agent" ]] \
    || fail 'tilepatch labels require TURBOISM_TILE_PATCH_AGENT'
  [[ "$tilepatch_agent" = /* ]] || fail 'tile patch agent path must be absolute'
  [[ -f "$tilepatch_agent" && ! -L "$tilepatch_agent" ]] \
    || fail 'tile patch agent is not a regular non-symlink file'
  tilepatch_agent="$(realpath -e -- "$tilepatch_agent")"
  [[ "$(basename -- "$tilepatch_agent")" == tile-patch-agent.jar ]] \
    || fail 'tile patch agent basename must be tile-patch-agent.jar'
  tilepatch_agent_sha256="$(sha256sum "$tilepatch_agent" | cut -d' ' -f1)"
  if [[ "$tilepatch_mode" == tiled ]]; then
    [[ "$atlas_pref_off" == 1 ]] \
      || fail '-tilepatch requires -atlasoff: the production transformer must not race the validation agent for the same class'
  fi
fi

# Production-on runs compose two instruments on the same class; the pairing flags are
# resolved after the manifest is validated below.
t039_before_main=0
atlas_admit_sha256=''

# Profiling is opt-in per run label so the default scene keeps its exact capture artifacts and
# overhead. `-jfr` additionally starts a bounded JDK Flight Recording that dumps on exit; it
# changes no scene action, parameter or result file, and the recording stays inside the
# task-owned home (never the fixture).
jfr_enabled=0
case "$run_label" in
  *-jfr) jfr_enabled=1 ;;
esac

# Both profiles share one delivery root; the 5203 bundle publishes under a versioned
# manifest name so it can coexist with the reviewed 5303 bundle.
default_manifest_name=bundle.manifest
[[ "$version" == 5203 ]] && default_manifest_name=bundle-5203.manifest
manifest="${TURBOISM_ATLAS_IMAGE_SHADOW_BUNDLE_MANIFEST:-$root/build/preview/$worktree_id/atlas-image-shadow/$default_manifest_name}"
prepare_dir=''
dry_run=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --bundle-manifest)
      [[ $# -ge 2 ]] || fail 'missing --bundle-manifest value'
      [[ -z "${TURBOISM_ATLAS_IMAGE_SHADOW_BUNDLE_MANIFEST:-}" ]] \
        || fail 'manifest supplied twice'
      manifest="$2"; shift 2 ;;
    --prepare-dir)
      [[ $# -ge 2 ]] || fail 'missing --prepare-dir value'
      [[ -z "$prepare_dir" && "$dry_run" == 0 ]] || fail 'prepare/dry-run may be selected once'
      prepare_dir="$2"; shift 2 ;;
    --dry-run)
      [[ -z "$prepare_dir" && "$dry_run" == 0 ]] || fail 'prepare/dry-run may be selected once'
      dry_run=1; shift ;;
    *) usage ;;
  esac
done

[[ -f "$manifest" && ! -L "$manifest" ]] || fail "bundle manifest is not a regular file: $manifest"
manifest="$(realpath -e -- "$manifest")"
declare -A values=()
while IFS='=' read -r key value || [[ -n "$key$value" ]]; do
  [[ -z "$key" ]] && continue
  [[ "$key" == \#* ]] && continue
  [[ "$key" =~ ^[A-Za-z][A-Za-z0-9]*$ ]] || fail "invalid manifest key: $key"
  [[ -n "$value" ]] || fail "empty manifest value: $key"
  [[ -z "${values[$key]+present}" ]] || fail "duplicate manifest key: $key"
  values[$key]="$value"
done < "$manifest"

# The 5203 bundle omits every t039* key: the T039 shadow agent is 5303-only, so both
# the required-key list and the unknown-key allowlist are profile-specific.
if [[ "$version" == 5303 ]]; then
  required_keys=(schemaVersion scene worktreeId bundleRoot productionAgent productionAgentSha256 \
    t039Agent t039AgentSha256 driver driverSha256 fixture fixtureName fixtureSha256 \
    officialJarSha256 t039Profile t039SourceBinding t039TrustedSourcePath t039JarSha256 \
    t039ClassSha256 t039ShapeSha256 t039LoaderClass t039HelperSha256 t039T038HelperSha256 \
    t039ShadowMode t039ShadowOptIn runnable)
else
  required_keys=(schemaVersion scene worktreeId bundleRoot productionAgent productionAgentSha256 \
    driver driverSha256 fixture fixtureName fixtureSha256 officialJarSha256 runnable)
fi
for key in "${required_keys[@]}"; do
  [[ -n "${values[$key]+present}" ]] || fail "manifest missing key: $key"
done
for key in "${!values[@]}"; do
  case "$key" in
    schemaVersion|scene|worktreeId|bundleRoot|productionAgent|productionAgentSha256|driver|driverSha256|fixture|fixtureName|fixtureSha256|officialJarSha256|runnable) ;;
    t039*)
      [[ "$version" == 5303 ]] || fail "5203 manifest must not carry T039 key: $key"
      case "$key" in
        t039Agent|t039AgentSha256|t039Profile|t039SourceBinding|t039TrustedSourcePath|t039JarSha256|t039ClassSha256|t039ShapeSha256|t039LoaderClass|t039HelperSha256|t039T038HelperSha256|t039ShadowMode|t039ShadowOptIn) ;;
        *) fail "unknown manifest key: $key" ;;
      esac ;;
    *) fail "unknown manifest key: $key" ;;
  esac
done

[[ "${values[schemaVersion]}" == 1 ]] || fail 'manifest schemaVersion must be 1'
[[ "${values[scene]}" == "atlas-image-shadow:$version" ]] || fail 'manifest scene mismatch'
[[ "${values[worktreeId]}" == "$worktree_id" ]] || fail 'manifest worktree ID mismatch'
[[ "${values[fixture]}" == "$fixture" ]] || fail 'manifest fixture is not the fixed reviewed source'
[[ "${values[fixtureName]}" == "$fixture_name" ]] || fail 'manifest fixture name mismatch'
[[ "${values[fixtureSha256]}" == "$fixture_sha256" ]] || fail 'manifest fixture hash mismatch'
[[ "${values[officialJarSha256]}" == "$official_jar_sha256" ]] || fail 'manifest official JAR hash mismatch'
if [[ "$version" == 5303 ]]; then
  [[ "${values[t039Profile]}" == 5303 ]] || fail 'T039 profile mismatch'
  [[ "${values[t039JarSha256]}" == "$official_jar_sha256" ]] || fail 'T039 official JAR hash mismatch'
  [[ "${values[t039ClassSha256]}" == "$t039_class_sha256" ]] || fail 'T039 class hash mismatch'
  [[ "${values[t039ShapeSha256]}" == "$t039_shape_sha256" ]] || fail 'T039 shape hash mismatch'
  [[ "${values[t039SourceBinding]}" == "$runtime_source_binding" ]] || fail 'T039 source binding mismatch'
  [[ "${values[t039TrustedSourcePath]}" == "$runtime_trusted_source_path" ]] \
    || fail 'T039 trusted source path is not the fixed task-prefix candidate'
  [[ "${values[t039ShadowMode]}" == shadow-ready ]] || fail 'T039 shadow mode is not shadow-ready'
  [[ "${values[t039ShadowOptIn]}" == T039_SHADOW_EXPLICIT_OPT_IN ]] || fail 'T039 opt-in mismatch'
  for key in t039AgentSha256 t039HelperSha256 t039T038HelperSha256; do
    [[ "${values[$key]}" =~ ^[0-9a-f]{64}$ ]] || fail "$key is not a lowercase SHA-256"
  done
  [[ "${values[t039LoaderClass]}" =~ ^[A-Za-z0-9_$.]{1,160}$ ]] || fail 'T039 loader class is unsafe'
fi
for key in productionAgentSha256 driverSha256; do
  [[ "${values[$key]}" =~ ^[0-9a-f]{64}$ ]] || fail "$key is not a lowercase SHA-256"
done
[[ "${values[runnable]}" == false ]] || fail 'T040 manifest must remain runnable=false until manager admission'

bundle_root="$(realpath -e -- "${values[bundleRoot]}")"
expected_bundle_root="$root/build/preview/$worktree_id/atlas-image-shadow/"
[[ "$bundle_root" == "$expected_bundle_root"* ]] || fail 'bundle is outside this worktree delivery root'
[[ "$bundle_root" != *latest* ]] || fail 'latest artifact pointers are forbidden'

require_artifact() {
  local path="$1" expected="$2" basename_expected="$3" label="$4"
  [[ -f "$path" && ! -L "$path" ]] || fail "$label is not a regular non-symlink file"
  local actual
  actual="$(realpath -e -- "$path")"
  [[ "$actual" == "$bundle_root"/* ]] || fail "$label is outside bundle root"
  [[ "$(basename -- "$actual")" == "$basename_expected" ]] || fail "$label basename mismatch"
  [[ "$(sha256sum "$actual" | cut -d' ' -f1)" == "$expected" ]] || fail "$label SHA-256 mismatch"
  printf '%s' "$actual"
}
production_agent="$(require_artifact "${values[productionAgent]}" "${values[productionAgentSha256]}" \
  turboism-agent.jar productionAgent)"

# When the production atlas preference is on under 5303, the production transformer and the
# T039 shadow weave both target com/live2d/util/f/g. T039 must premain first — it pins the
# pristine class bytes, weaves its two boundary probes, and self-removes — then the
# production transformer sees the deterministic woven output, admitted via a bounded
# extra-digest property. The woven digest was computed offline from this exact T039 build
# through patchOfficial5303; a rebuilt T039 agent can change the woven bytes, so the pairing
# is gated on the pinned agent hash. The 5203 profile ships no T039 agent, so the
# production transformer sees the pristine e/g bytes and the reviewed digest admits them.
if [[ "$version" == 5303 && "$atlas_pref_off" == 0 ]]; then
  [[ "${values[t039AgentSha256]}" == "725920f9c008a1c86d6a9ac99fa89e3cbcc55b35221462be247d2844c35f3fad" ]] \
    || fail 'production-on runs require the pinned T039 build that produced the admitted woven digest'
  t039_before_main=1
  atlas_admit_sha256=800e3f6758e47bb1d8d974e72dcfed220f8773e15a5f05cac101ae2b56c1d160
fi

t039_agent=''
if [[ "$version" == 5303 ]]; then
  t039_agent="$(require_artifact "${values[t039Agent]}" "${values[t039AgentSha256]}" \
    t039-shadow-agent.jar t039Agent)"
fi
driver="$(require_artifact "${values[driver]}" "${values[driverSha256]}" \
  atlas-image-shadow-scene-driver.jar driver)"
[[ -f "$runner" ]] || fail "Runner is missing: $runner"

runner_args=(
  --name atlas-image-shadow
  --version "$version"
  --run-label "$run_label"
  --bundle-root "$bundle_root"
  --agent "$production_agent"
)
# Keep the 5303 agent order identical to the reviewed profile: T039 before the driver.
if [[ "$version" == 5303 ]]; then
  runner_args+=(--aux-agent "$t039_agent:t039-shadow-agent.jar")
fi
runner_args+=(
  --aux-agent "$driver:atlas-image-shadow-scene-driver.jar"
  --fixture-local "$fixture"
  --fixture-sha256 "$fixture_sha256"
  --fixture-name "$fixture_name"
  --require-fixture-unchanged
  --agent-host-class com.live2d.cubism.CEAppCtrl
  --agent-timeout 180
  --result-file state/atlas-image-shadow/result.txt
  --result-pass-line 'collectionStatus=COMPLETE'
  --result-fail-line 'collectionStatus=FAILED'
  --result-timeout "$result_timeout_seconds"
  --exit-timeout "$exit_timeout_seconds"
  --jvm-option '-Dturboism.validation.atlasImageShadow.home={HOME}'
  --jvm-option '-Dturboism.validation.atlasImageShadow.taskId={TASK_ID}'
  --jvm-option '-Dturboism.validation.atlasImageShadow.fixture={FIXTURE}'
  --jvm-option '-Dturboism.validation.atlasImageShadow.fixtureName={FIXTURE_NAME}'
  --jvm-option "-Dturboism.validation.atlasImageShadow.fixtureSha256=$fixture_sha256"
  --jvm-option "-Dturboism.validation.atlasImageShadow.version=$version"
  --jvm-option '-Dturboism.validation.atlasImageShadow.outputRelative=state/atlas-image-shadow'
  --jvm-option "-Dturboism.validation.atlasImageShadow.timeoutSeconds=$driver_timeout_seconds"
  --jvm-option "-Dturboism.validation.atlasImageShadow.startupSeconds=$startup_seconds"
  --jvm-option "-Dturboism.validation.atlasImageShadow.closePollSeconds=$close_poll_seconds"
  --jvm-option "-Dturboism.validation.atlasImageShadow.layoutDialogSeconds=$layout_dialog_seconds"
  --jvm-option "-Dturboism.validation.atlasImageShadow.layoutScalePercent=${layout_scale_percent}"
  --jvm-option "-Dturboism.validation.atlasImageShadow.layoutMode=${layout_mode}"
  --jvm-option "-Dturboism.validation.atlasImageShadow.editorSettleSeconds=${editor_settle_seconds}"
  --jvm-option "-Dturboism.validation.atlasImageShadow.menuDump=${menu_dump}"
  --jvm-option "-Dturboism.validation.atlasImageShadow.exportProbe=${export_probe}"
  --jvm-option "-Dturboism.validation.atlasImageShadow.exportProbeAfterEditor=${export_after_editor}"
  --jvm-option "-Dturboism.validation.atlasImageShadow.editorReopen=${editor_reopen}"
)

# The 5303 profile pins the whole T039 property contract; the 5203 profile must carry
# none of it (the driver fails closed if any t039.* leaks through).
if [[ "$version" == 5303 ]]; then
  runner_args+=(
    --jvm-option '-Dturboism.validation.t039.profile=5303'
    --jvm-option '-Dturboism.validation.t039.runId={TASK_ID}'
    --jvm-option "-Dturboism.validation.t039.sourceBinding=${values[t039SourceBinding]}"
    --jvm-option "-Dturboism.validation.t039.trustedSourcePaths=${values[t039TrustedSourcePath]}"
    --jvm-option '-Dturboism.validation.t039.jarSha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166'
    --jvm-option "-Dturboism.validation.t039.classSha256=${values[t039ClassSha256]}"
    --jvm-option "-Dturboism.validation.t039.shapeSha256=${values[t039ShapeSha256]}"
    --jvm-option "-Dturboism.validation.t039.loaderClass=${values[t039LoaderClass]}"
    --jvm-option "-Dturboism.validation.t039.helperSha256=${values[t039HelperSha256]}"
    --jvm-option "-Dturboism.validation.t039.t038HelperSha256=${values[t039T038HelperSha256]}"
    --jvm-option '-Dturboism.validation.t039.maxEvents=64'
    --jvm-option '-Dturboism.validation.t039.shadowMode=shadow-ready'
    --jvm-option '-Dturboism.validation.t039.shadowOptIn=T039_SHADOW_EXPLICIT_OPT_IN'
  )
fi

if [[ -n "$mesh_hash_mode" ]]; then
  runner_args+=(--aux-agent "$mesh_hash_agent:mesh-hash-agent.jar")
  runner_args+=(--jvm-option "-Dturboism.validation.meshHash.mode=$mesh_hash_mode")
  runner_args+=(--jvm-option '-Dturboism.validation.meshHash.optIn=MESH_HASH_EXPLICIT_OPT_IN')
  runner_args+=(--jvm-option "-Dturboism.validation.meshHash.tripleSha256=$mesh_hash_triple_sha256")
  runner_args+=(--jvm-option '-Dturboism.validation.meshHash.output={HOME}\mesh-hash-evidence.properties')
fi

if [[ "$mesh_pref_off" == 1 ]]; then
  runner_args+=(--home-config "$mesh_off_config")
fi
if [[ "$atlas_pref_off" == 1 ]]; then
  runner_args+=(--home-config "$atlas_off_config")
elif [[ "$atlas_reuse_pref_off" == 1 ]]; then
  runner_args+=(--home-config "$reuse_off_config")
fi
if [[ "$t039_before_main" == 1 ]]; then
  runner_args+=(--aux-agent-before-main t039-shadow-agent.jar)
  runner_args+=(--jvm-option "-Dturboism.atlasTileBbox.admitClassSha256=$atlas_admit_sha256")
fi

if [[ -n "$timing_agent" ]]; then
  runner_args+=(--aux-agent "$timing_agent:atlas-timing-agent.jar")
  runner_args+=(--jvm-option '-Dturboism.validation.atlasTiming.optIn=ATLAS_TIMING_EXPLICIT_OPT_IN')
  runner_args+=(--jvm-option '-Dturboism.validation.atlasTiming.output={HOME}\atlas-timing')
  # Cache-reuse verdicts are swallowed on the host's late-session stderr; mirror them
  # to a task-home evidence file so sig-skip hits are durable proof.
  runner_args+=(--jvm-option '-Dturboism.atlasCacheReuse.evidenceFile={HOME}\atlas-cache-reuse-verdicts.txt')
fi
if [[ -n "$tilepatch_agent" ]]; then
  runner_args+=(--aux-agent "$tilepatch_agent:tile-patch-agent.jar")
  runner_args+=(--jvm-option '-Dturboism.validation.tilePatch.optIn=TILE_PATCH_EXPLICIT_OPT_IN')
  runner_args+=(--jvm-option "-Dturboism.validation.tilePatch.mode=$tilepatch_mode")
  runner_args+=(--jvm-option '-Dturboism.validation.tilePatch.output={HOME}\tile-patch')
fi

if [[ "$jfr_enabled" == 1 ]]; then
  runner_args+=(--jvm-option \
    "-XX:StartFlightRecording=filename={HOME}\\atlas-profiling.jfr,settings=profile,stackdepth=256,maxsize=512m,dumponexit=true")
fi
if [[ -n "$prepare_dir" ]]; then
  runner_args+=(--prepare-dir "$prepare_dir")
elif [[ "$dry_run" == 1 ]]; then
  runner_args+=(--dry-run)
fi

if [[ -n "$mesh_hash_mode" ]]; then
  printf 'meshHashMode=%s\nmeshHashAgentSha256=%s\nmeshHashTripleSha256=%s\n' \
    "$mesh_hash_mode" "$mesh_hash_agent_sha256" "$mesh_hash_triple_sha256" >&2
fi
if [[ "$mesh_pref_off" == 1 ]]; then
  printf 'meshPreference=off\nmeshOffConfigSha256=%s\n' "$mesh_off_config_sha256" >&2
fi
if [[ "$atlas_pref_off" == 1 ]]; then
  printf 'atlasPreference=off\natlasOffConfigSha256=%s\n' "$atlas_off_config_sha256" >&2
elif [[ "$atlas_reuse_pref_off" == 1 ]]; then
  printf 'atlasPreference=tile-bbox-only\natlasReuseOffConfigSha256=%s\n' \
    "$atlas_reuse_off_config_sha256" >&2
elif [[ -n "$timing_agent" || "$tilepatch_mode" == hashOnly ]]; then
  # Self-describing evidence: a timing/digest run without -atlasoff measured the
  # production-patched kernel (the preference defaults to enabled). Under 5303 T039
  # pre-weaves before the main agent and the woven digest is admitted explicitly;
  # under 5203 the production transformer sees the pristine e/g bytes directly.
  if [[ "$t039_before_main" == 1 ]]; then
    printf 'atlasPreference=default-on\natlasAdmitClassSha256=%s\nt039BeforeMain=1\n' \
      "$atlas_admit_sha256" >&2
  else
    printf 'atlasPreference=default-on\n' >&2
  fi
fi
if [[ -n "$timing_agent" ]]; then
  printf 'atlasTimingAgentSha256=%s\n' "$timing_agent_sha256" >&2
fi
if [[ -n "$tilepatch_agent" ]]; then
  printf 'tilePatchAgentSha256=%s\n' "$tilepatch_agent_sha256" >&2
  printf 'tilePatchMode=%s\n' "$tilepatch_mode" >&2
fi

# No readiness markers, hook, client, or collector option is permitted here; the only staged
# home input is the hash-pinned -meshoff config above.
exec bash "$runner" "${runner_args[@]}"
