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
# Ordinary 5203 scenes keep the opacity pair the mcp wrapper already pins;
# explicit TLPROD scenes additionally admit the fixed heavy pair. Its canonical name is fixed regardless of the local file's basename, so the
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
  run-atlas-image-shadow-host-validation.sh <5303|5302|5203> <run-label>
    [--tri-identity-probe <path-to-pinned-tri-identity-probe.jar>]
    [--tri-weave-ab <dump-only|dump+weave>]
    [--tri-dweave <dm-dump-only|dm-dump+weave>]
    [--tri-tlindex <tl-dump-only|tl-dump+weave>]
    [--tri-tlindex-home-config <absolute path to leg-matching UI config>]
    [--tri-weave-agent <absolute path to tri-weave-agent.jar>]
    [--bundle-manifest <published manifest>]
    [--prepare-dir <directory> | --dry-run]

T029-TLPROD labels (t029-tlprod-<off|on><N>-<heavy|std>-nolayout[-jfr]) run the
production edge-index transformer under the pinned tl-dump-only capture agent:
-off stages a hash-pinned home config carrying meshTriangulationEdgeIndex=false,
-on runs the default-on preference. -off expects pristine class bytes; -on
expects the production-patched bytes the transformer emits. The family admits
5303, 5302, and 5203; every other label family keeps its existing version gates.
The 5302 profile is admitted only for t029-tlprod-* labels.
A captured settings UI config may be staged with --tri-tlindex-home-config;
its explicit edge-index Boolean must match the leg and its SHA is frozen by prepare.

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
# T029-IDENTITY: the probe flag's version/label contract must reject before any other gate —
# a wrong profile cannot even reach fixture resolution with the flag present.
tri_identity_probe_flag=0
tri_weave_ab_flag=0
tri_dweave_flag=0
tri_tlindex_flag=0
for arg in "$@"; do
  [[ "$arg" == --tri-identity-probe ]] && tri_identity_probe_flag=1
  [[ "$arg" == --tri-weave-ab ]] && tri_weave_ab_flag=1
  [[ "$arg" == --tri-dweave ]] && tri_dweave_flag=1
  [[ "$arg" == --tri-tlindex ]] && tri_tlindex_flag=1
done
if [[ "$tri_identity_probe_flag" == 1 ]]; then
  [[ "$version" == 5303 ]] || fail '--tri-identity-probe is 5303-only'
  [[ "$run_label" == t029-identity-01-heavy-nolayout ]] \
    || fail '--tri-identity-probe requires the exact label t029-identity-01-heavy-nolayout'
fi
# T029-TRIAB: the dump+weave A/B flag is 5303-only and only ever runs on the interleaved
# leg labels. A triab label without the flag would silently run baseline — fail closed
# both directions; the mode/label consistency check runs after option parsing.
tri_weave_leg=''
if [[ "$run_label" =~ ^t029-triab-(base|woven)([0-9]+)-heavy-nolayout(-jfr)?$ ]]; then
  tri_weave_leg="${BASH_REMATCH[1]}${BASH_REMATCH[2]}"
fi
if [[ "$tri_weave_ab_flag" == 1 || -n "$tri_weave_leg" ]]; then
  [[ "$version" == 5303 ]] || fail '--tri-weave-ab is 5303-only'
  [[ -n "$tri_weave_leg" ]] \
    || fail '--tri-weave-ab requires a label t029-triab-<base|woven><N>-heavy-nolayout[-jfr]'
  [[ "$tri_weave_ab_flag" == 1 ]] \
    || fail 'a t029-triab-* label requires --tri-weave-ab so the leg cannot run uninstrumented'
fi
# T029-DWEAVE: same bidirectional contract on its own label family. The DWEAVE leg
# carries the dmWeave property namespace — a dweave label without the flag would run
# uninstrumented, and the flag without a dweave label has no leg to bind to.
tri_dweave_leg=''
if [[ "$run_label" =~ ^t029-dweave-(base|woven)([0-9]+)-heavy-nolayout(-jfr)?$ ]]; then
  tri_dweave_leg="${BASH_REMATCH[1]}${BASH_REMATCH[2]}"
fi
if [[ "$tri_dweave_flag" == 1 || -n "$tri_dweave_leg" ]]; then
  [[ "$version" == 5303 ]] || fail '--tri-dweave is 5303-only'
  [[ -n "$tri_dweave_leg" ]] \
    || fail '--tri-dweave requires a label t029-dweave-<base|woven><N>-heavy-nolayout[-jfr]'
  [[ "$tri_dweave_flag" == 1 ]] \
    || fail 'a t029-dweave-* label requires --tri-dweave so the leg cannot run uninstrumented'
fi
# T029-TLINDEX: the four-method edge-index candidate in the tlWeave namespace —
# identical bidirectional label<->flag contract on its own label family.
tri_tlindex_leg=''
if [[ "$run_label" =~ ^t029-tlindex-(base|woven)([0-9]+)-heavy-nolayout(-jfr)?$ ]]; then
  tri_tlindex_leg="${BASH_REMATCH[1]}${BASH_REMATCH[2]}"
fi
# T029-TLPROD: production-rollout legs. The aux tlWeave agent runs tl-dump-only on
# both legs — the production transformer, not the validation weave, is what differs.
# `off` stages the pinned meshTriangulationEdgeIndex=false home config; `on` relies on
# the default-on preference. expectClassSha256 is the bytes the aux agent observes:
# pristine on off legs, production-patched on on legs.
tri_tlprod_leg=''
if [[ "$run_label" =~ ^t029-tlprod-(off|on)([0-9]+)-(heavy|std)-nolayout(-jfr)?$ ]]; then
  tri_tlprod_leg="${BASH_REMATCH[1]}${BASH_REMATCH[2]}"
fi
if [[ "$tri_tlindex_flag" == 1 || -n "$tri_tlindex_leg" || -n "$tri_tlprod_leg" ]]; then
  [[ -n "$tri_tlindex_leg" || -n "$tri_tlprod_leg" ]] \
    || fail '--tri-tlindex requires a label t029-tlindex-<base|woven><N>-heavy-nolayout[-jfr] or t029-tlprod-<off|on><N>-<heavy|std>-nolayout[-jfr]'
  [[ "$tri_tlindex_flag" == 1 ]] \
    || fail 'a t029-tlindex-*/t029-tlprod-* label requires --tri-tlindex so the leg cannot run uninstrumented'
  if [[ -n "$tri_tlindex_leg" ]]; then
    [[ "$version" == 5303 ]] || fail 't029-tlindex-* weave legs are 5303-only'
  fi
fi
case "$version" in
  5303)
    official_jar_sha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166
    t039_class_sha256=ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6
    t039_shape_sha256=a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f
    # Fixed path inside Runner's isolated 5303 Proton prefix, not a build-host path.
    runtime_trusted_source_path='C:\Program Files\Live2D Cubism 5.3.03\app\lib\Live2D_Cubism.jar'
    ;;
  5302)
    official_jar_sha256=988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21
    t039_class_sha256=''
    t039_shape_sha256=''
    runtime_trusted_source_path='C:\Program Files\Live2D Cubism 5.3\app\lib\Live2D_Cubism.jar'
    ;;
  5203)
    official_jar_sha256=bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd
    t039_class_sha256=''
    t039_shape_sha256=''
    runtime_trusted_source_path='C:\Program Files\Live2D Cubism 5.2\app\lib\Live2D_Cubism.jar'
    ;;
  *) fail 'T040 admits only exact host versions 5303, 5302, and 5203' ;;
esac
# 5302 has no reviewed profile in this wrapper outside the production TLINDEX rollout
# legs — every other label family keeps failing closed on it.
if [[ "$version" == 5302 && -z "$tri_tlprod_leg" ]]; then
  fail 'the 5302 profile admits only t029-tlprod-* labels'
fi
[[ "$run_label" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || fail 'run label is not bounded and safe'

# Fixture profile and wall-time budgets. `-heavy` selects the production-scale model and the
# budgets it can outgrow; every other label keeps the exact Circle100 numbers this scene has
# always used, so an unset property can never silently relax a budget. `-heavy` is 5303-only
# outside the tlprod rollout legs — the production transformer evidence needs the heavy model
# on every admitted version, so t029-tlprod-* labels may carry it under 5302/5203 as well.
[[ "$run_label" == *-heavy* && "$version" != 5303 && -z "$tri_tlprod_leg" ]] \
  && fail "-heavy is a 5303-only fixture profile outside t029-tlprod-* legs"
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
    # reviewed opacity52 basename rather than the local file's own name. Production legs
    # do not bypass this fixed name/hash pair with an arbitrary environment-supplied hash.
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

# A t029-tlprod-off* leg stages a reviewed home config carrying
# meshTriangulationEdgeIndex=false, so the production premain preference gate — not the
# hook policy and not a missing schema field — takes the native triangulation path. The
# capture agent still runs tl-dump-only on both legs, so the equivalence witness is
# identical; only the production transformer differs.
tlindex_pref_off=0
[[ "$tri_tlprod_leg" == off* ]] && tlindex_pref_off=1
tlindex_off_config=''
tlindex_off_config_sha256=47bb572625150b9f6a78a75be1e1b62cf1df0b179b58a2beffd7b568c4ae8c43
if [[ "$tlindex_pref_off" == 1 ]]; then
  [[ "$mesh_pref_off" == 0 && "$atlas_pref_off" == 0 && "$atlas_reuse_pref_off" == 0 ]] \
    || fail 'tlprod-off cannot combine with -meshoff/-atlasoff/-reuseoff: one home config is staged'
  tlindex_off_config="$root/validation/triangulation-tlindex/home-config-tlindexoff.json"
  [[ -f "$tlindex_off_config" && ! -L "$tlindex_off_config" ]] \
    || fail 'tlindex-off home config is not a regular non-symlink file'
  tlindex_off_config="$(realpath -e -- "$tlindex_off_config")"
  [[ "$(sha256sum "$tlindex_off_config" | cut -d' ' -f1)" == "$tlindex_off_config_sha256" ]] \
    || fail 'tlindex-off home config hash mismatch'
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
tri_identity_probe=''
tri_weave_ab=''
tri_dweave=''
tri_tlindex=''
tri_weave_agent_arg=''
tri_tlprod_home_config=''
tri_tlprod_home_config_sha256=''
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
    --tri-identity-probe)
      [[ $# -ge 2 ]] || fail 'missing --tri-identity-probe value'
      [[ -z "$tri_identity_probe" ]] || fail 'tri-identity probe supplied twice'
      tri_identity_probe="$2"; shift 2 ;;
    --tri-weave-ab)
      [[ $# -ge 2 ]] || fail 'missing --tri-weave-ab value'
      [[ -z "$tri_weave_ab" ]] || fail 'tri-weave-ab supplied twice'
      tri_weave_ab="$2"; shift 2 ;;
    --tri-dweave)
      [[ $# -ge 2 ]] || fail 'missing --tri-dweave value'
      [[ -z "$tri_dweave" ]] || fail 'tri-dweave supplied twice'
      tri_dweave="$2"; shift 2 ;;
    --tri-tlindex)
      [[ $# -ge 2 ]] || fail 'missing --tri-tlindex value'
      [[ -z "$tri_tlindex" ]] || fail 'tri-tlindex supplied twice'
      tri_tlindex="$2"; shift 2 ;;
    --tri-tlindex-home-config)
      [[ $# -ge 2 ]] || fail 'missing --tri-tlindex-home-config value'
      [[ -z "$tri_tlprod_home_config" ]] || fail 'production home config supplied twice'
      tri_tlprod_home_config="$2"; shift 2 ;;
    --tri-weave-agent)
      [[ $# -ge 2 ]] || fail 'missing --tri-weave-agent value'
      [[ -z "$tri_weave_agent_arg" ]] || fail 'tri-weave-agent supplied twice'
      tri_weave_agent_arg="$2"; shift 2 ;;
    --dry-run)
      [[ -z "$prepare_dir" && "$dry_run" == 0 ]] || fail 'prepare/dry-run may be selected once'
      dry_run=1; shift ;;
    *) usage ;;
  esac
done

# --tri-identity-probe (T029-IDENTITY): a single frozen diagnostic aux agent with the
# strictest admission in this wrapper — only the pinned review build, only the exact frozen
# run label, only under 5303 (the version/label contract already rejected above).
if [[ -n "$tri_identity_probe" ]]; then
  [[ "$tri_identity_probe" = /* ]] || fail 'tri-identity probe path must be absolute'
  [[ -f "$tri_identity_probe" && ! -L "$tri_identity_probe" ]] \
    || fail 'tri-identity probe is not a regular non-symlink file'
  tri_identity_probe="$(realpath -e -- "$tri_identity_probe")"
  [[ "$(sha256sum "$tri_identity_probe" | cut -d' ' -f1)" == \
      9c4a4ccc37c630c8134428334cd4131faccf8f6250e6f45035c659b3bb10ab71 ]] \
    || fail 'tri-identity probe SHA-256 mismatch'
fi

# --tri-weave-ab (T029-TRIAB): the mode must match the leg token encoded in the label —
# a base leg carrying dump+weave (or vice versa) would silently mislabel the verdict
# direction. The agent jar is a new artifact: regular non-symlink file with the fixed
# basename; its SHA-256 is recorded for the admission review rather than hard-pinned here.
tri_weave_mode=''
tri_weave_agent=''
tri_weave_agent_sha256=''
if [[ -n "$tri_weave_ab" ]]; then
  case "$tri_weave_ab" in
    dump-only|dump+weave) tri_weave_mode="$tri_weave_ab" ;;
    *) fail '--tri-weave-ab mode must be dump-only or dump+weave' ;;
  esac
  tri_weave_expected=dump-only
  [[ "$tri_weave_leg" == woven* ]] && tri_weave_expected='dump+weave'
  [[ "$tri_weave_mode" == "$tri_weave_expected" ]] \
    || fail "--tri-weave-ab $tri_weave_mode inconsistent with leg label $run_label"
fi
# --tri-dweave (T029-DWEAVE): identical label↔mode contract in the dmWeave namespace.
# The two namespaces are mutually exclusive by construction — a triab label rejects a
# missing --tri-weave-ab and a dweave label rejects a missing --tri-dweave above.
tri_dweave_mode=''
if [[ -n "$tri_dweave" ]]; then
  case "$tri_dweave" in
    dm-dump-only|dm-dump+weave) tri_dweave_mode="$tri_dweave" ;;
    *) fail '--tri-dweave mode must be dm-dump-only or dm-dump+weave' ;;
  esac
  tri_dweave_expected=dm-dump-only
  [[ "$tri_dweave_leg" == woven* ]] && tri_dweave_expected='dm-dump+weave'
  [[ "$tri_dweave_mode" == "$tri_dweave_expected" ]] \
    || fail "--tri-dweave $tri_dweave_mode inconsistent with leg label $run_label"
fi
# --tri-tlindex (T029-TLINDEX): identical label<->mode contract, tlWeave namespace.
tri_tlindex_mode=''
if [[ -n "$tri_tlindex" ]]; then
  case "$tri_tlindex" in
    tl-dump-only|tl-dump+weave) tri_tlindex_mode="$tri_tlindex" ;;
    *) fail '--tri-tlindex mode must be tl-dump-only or tl-dump+weave' ;;
  esac
  tri_tlindex_expected=tl-dump-only
  [[ "$tri_tlindex_leg" == woven* ]] && tri_tlindex_expected='tl-dump+weave'
  # tlprod legs forbid the validation weave entirely: the production transformer owns
  # TriangleList; the aux agent's only job is the identical b()Lk; capture on both legs.
  [[ -n "$tri_tlprod_leg" ]] && tri_tlindex_expected='tl-dump-only'
  [[ "$tri_tlindex_mode" == "$tri_tlindex_expected" ]] \
    || fail "--tri-tlindex $tri_tlindex_mode inconsistent with leg label $run_label"
fi
# A real settings probe may supply the exact config it wrote. This is restricted to
# production legs, and the explicit Boolean must agree with their on/off identity.
# The common Runner freezes the file and its SHA in the prepared input inventory.
if [[ -n "$tri_tlprod_home_config" ]]; then
  [[ -n "$tri_tlprod_leg" && "$tri_tlindex_mode" == tl-dump-only ]] \
    || fail '--tri-tlindex-home-config requires a production capture leg'
  [[ "$tri_tlprod_home_config" = /* && -f "$tri_tlprod_home_config" && ! -L "$tri_tlprod_home_config" ]] \
    || fail 'production home config must be an absolute regular non-symlink file'
  tri_tlprod_home_config="$(realpath -e -- "$tri_tlprod_home_config")"
  python3 - "$tri_tlprod_home_config" "$tri_tlprod_leg" <<'PYTLCONFIG' || fail 'production home config does not match the leg'
import json, sys
from pathlib import Path

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate config key')
        result[key] = value
    return result

config = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'), object_pairs_hook=unique_object)
value = config.get('meshTriangulationEdgeIndex') if isinstance(config, dict) else None
if type(value) is not bool or value != sys.argv[2].startswith('on'):
    raise SystemExit('explicit edge-index Boolean must match the production leg')
PYTLCONFIG
  tri_tlprod_home_config_sha256="$(sha256sum "$tri_tlprod_home_config" | cut -d' ' -f1)"
fi

# One physical agent jar serves both namespaces (TRIAB and DWEAVE targets live in the
# same artifact). The jar arrives either as an explicit argument (queue-stable: the
# runner replays frozen args in a worker env without TURBOISM_TRI_WEAVE_AGENT) or via
# the env var (interactive/dry-run compatibility). Supplying both is ambiguous → fail;
# neither is fail-closed. Validation is identical for both sources and modes.
if [[ -n "$tri_weave_mode" || -n "$tri_dweave_mode" || -n "$tri_tlindex_mode" ]]; then
  if [[ -n "$tri_weave_agent_arg" && -n "${TURBOISM_TRI_WEAVE_AGENT:-}" ]]; then
    fail 'tri-weave agent supplied twice (argument and TURBOISM_TRI_WEAVE_AGENT)'
  fi
  tri_weave_agent="${tri_weave_agent_arg:-${TURBOISM_TRI_WEAVE_AGENT:-}}"
  [[ -n "$tri_weave_agent" ]] || fail '--tri-weave-ab/--tri-dweave/--tri-tlindex requires --tri-weave-agent or TURBOISM_TRI_WEAVE_AGENT'
  [[ "$tri_weave_agent" = /* ]] || fail 'tri-weave agent path must be absolute'
  [[ -f "$tri_weave_agent" && ! -L "$tri_weave_agent" ]] \
    || fail 'tri-weave agent is not a regular non-symlink file'
  tri_weave_agent="$(realpath -e -- "$tri_weave_agent")"
  [[ "$(basename -- "$tri_weave_agent")" == tri-weave-agent.jar ]] \
    || fail 'tri-weave agent basename must be tri-weave-agent.jar'
  tri_weave_agent_sha256="$(sha256sum "$tri_weave_agent" | cut -d' ' -f1)"
fi

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
# T029-IDENTITY: probe premains after production and before the scene driver.
if [[ -n "$tri_identity_probe" ]]; then
  runner_args+=(--aux-agent "$tri_identity_probe:tri-identity-probe.jar")
fi
# T029-TRIAB / T029-DWEAVE: the dump+weave aux agent premains after production and
# T039 and before the scene driver — the same physical jar serves both namespaces.
if [[ -n "$tri_weave_mode" || -n "$tri_dweave_mode" || -n "$tri_tlindex_mode" ]]; then
  runner_args+=(--aux-agent "$tri_weave_agent:tri-weave-agent.jar")
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

# The Runner's deferred GL gate samples the runtime log once right after launch when no
# ready marker is configured; the JVM is then typically still inside Proton startup, so an
# empty marker set turns admission into a race. Wait on the gate's own literal marker: the
# ready loop only exits once that line exists in the same runtime log the gate reads.
# This is not a new capability channel and weakens nothing — an INACTIVE or absent marker
# still fails closed through the bounded ready timeout, and the final gate check is
# unchanged. Should a future label ever stage a config that legitimately disables
# mesaGlThread (safeMode/hooks.disabledIds/launcher prefs), this unconditional wait would
# need review; none of the pinned configs do that today.
runner_args+=(--ready-marker 'TURBOISM_DEFERRED_GL_ERROR_CHECK deferred=ACTIVE')

# Only the production label/flag contract above admits heavy on 5203. The real
# scene driver uses the same explicit token and fixed name/hash pair; no generic
# fixture override is permitted on an ordinary 5203 scene.
if [[ -n "$tri_tlprod_leg" ]]; then
  runner_args+=(--jvm-option '-Dturboism.validation.atlasImageShadow.tlprodOptIn=TLPROD_EXPLICIT_OPT_IN')
fi

# T029-IDENTITY probe admission contract: exactly seven fixed properties — no fixture hash,
# no test knobs, no generic passthrough. runId is a bounded literal because the task id
# exceeds the probe's 32-char runId cap. expectCodeSource doubles % so the launch.bat
# `set` line leaves %20 for the JVM; this value is a first-leg safe-reject candidate —
# a gate reject or missing files means missing evidence, never a retry.
if [[ -n "$tri_identity_probe" ]]; then
  runner_args+=(
    --jvm-option '-Dturboism.validation.triIdentity.enabled=true'
    --jvm-option '-Dturboism.validation.triIdentity.phase=t029-identity-01'
    --jvm-option '-Dturboism.validation.triIdentity.runId=t029-id-01'
    --jvm-option '-Dturboism.validation.triIdentity.outputDir={HOME}/tri-identity'
    --jvm-option '-Dturboism.validation.triIdentity.expectClassSha256=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29'
    --jvm-option '-Dturboism.validation.triIdentity.expectLoader=jdk.internal.loader.ClassLoaders$AppClassLoader'
    --jvm-option '-Dturboism.validation.triIdentity.expectCodeSource=file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3.03/app/lib/Live2D_Cubism.jar'
  )
fi

# T029-TRIAB leg contract: nine fixed properties — no fixture hash, no test knobs, no
# generic passthrough. runId is a bounded literal derived from the leg token (the task id
# exceeds the 32-char runId cap). expectCodeSource doubles % so the launch.bat `set` line
# leaves %20 for the JVM — same %%20 contract as the identity probe; the launch chain
# reduced %% to % on the first leg (verified in the T029-IDENTITY host evidence). mode is
# the leg token the label declared; captureN pinned to the 4-record bound.
if [[ -n "$tri_weave_mode" ]]; then
  runner_args+=(
    --jvm-option '-Dturboism.validation.triWeave.enabled=true'
    --jvm-option "-Dturboism.validation.triWeave.mode=$tri_weave_mode"
    --jvm-option '-Dturboism.validation.triWeave.phase=t029-triab'
    --jvm-option "-Dturboism.validation.triWeave.runId=triab-$tri_weave_leg"
    --jvm-option '-Dturboism.validation.triWeave.outputDir={HOME}/tri-weave'
    --jvm-option '-Dturboism.validation.triWeave.expectClassSha256=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29'
    --jvm-option '-Dturboism.validation.triWeave.expectLoader=jdk.internal.loader.ClassLoaders$AppClassLoader'
    --jvm-option '-Dturboism.validation.triWeave.expectCodeSource=file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3.03/app/lib/Live2D_Cubism.jar'
    --jvm-option '-Dturboism.validation.triWeave.captureN=4'
  )
fi

# T029-DWEAVE leg contract: eleven fixed properties in the dmWeave namespace — the
# candidate weave is pinned on h.c()V (class sha 5aa7031e…, double shape pin inside
# the agent) while the equivalence witness stays the TriangleList.b() capture with
# its own independent digest (expectCaptureClassSha256=87835641…). profile is pinned
# explicitly to dm-official so admission cannot drift into a fixture profile.
if [[ -n "$tri_dweave_mode" ]]; then
  runner_args+=(
    --jvm-option '-Dturboism.validation.dmWeave.enabled=true'
    --jvm-option "-Dturboism.validation.dmWeave.mode=$tri_dweave_mode"
    --jvm-option '-Dturboism.validation.dmWeave.profile=dm-official'
    --jvm-option '-Dturboism.validation.dmWeave.phase=t029-dweave'
    --jvm-option "-Dturboism.validation.dmWeave.runId=dweave-$tri_dweave_leg"
    --jvm-option '-Dturboism.validation.dmWeave.outputDir={HOME}/dm-weave'
    --jvm-option '-Dturboism.validation.dmWeave.expectClassSha256=5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d'
    --jvm-option '-Dturboism.validation.dmWeave.expectCaptureClassSha256=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29'
    --jvm-option '-Dturboism.validation.dmWeave.expectLoader=jdk.internal.loader.ClassLoaders$AppClassLoader'
    --jvm-option '-Dturboism.validation.dmWeave.expectCodeSource=file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3.03/app/lib/Live2D_Cubism.jar'
    --jvm-option '-Dturboism.validation.dmWeave.captureN=4'
  )
fi

# T029-TLINDEX leg contract: twelve fixed properties in the tlWeave namespace —
# the candidate weave is the four-method TriangleList rewrite (class sha
# 87835641…, the SAME class the b()Lk; capture observes, so expectClassSha256
# and expectCaptureClassSha256 carry the identical digest). profile is pinned
# to tl-official; the Bridge helper ships inside the agent jar.
#
# T029-TLPROD shares the namespace but inverts the roles: the production
# transformer weaves first, the aux agent only captures. expectClassSha256
# therefore tracks what the aux observes at transform time — pristine bytes on
# -off legs, the deterministic production-patched bytes on -on legs — and the
# expectCodeSource value follows the per-version install dir.
tl_expect_class_sha=''
tl_expect_codesource='file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3.03/app/lib/Live2D_Cubism.jar'
tl_run_id=''
if [[ -n "$tri_tlindex_leg" ]]; then
  tl_expect_class_sha=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29
  tl_run_id="tlindex-$tri_tlindex_leg"
elif [[ -n "$tri_tlprod_leg" ]]; then
  case "$version" in
    5303)
      tl_pristine_sha=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29
      tl_patched_sha=fbac6e0d9a4015e0a81ea6349414edc177445c7766ebafb2420c5077abc3b5ec
      ;;
    5302)
      tl_pristine_sha=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29
      tl_patched_sha=fbac6e0d9a4015e0a81ea6349414edc177445c7766ebafb2420c5077abc3b5ec
      tl_expect_codesource='file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3/app/lib/Live2D_Cubism.jar'
      ;;
    5203)
      tl_pristine_sha=b0a11ffc8969e5a8d1266ca01db85dacb75ca4de64e14b9f282ba4c32169d920
      tl_patched_sha=97e7ca33aa45135a7b0a4c6acb8b82da8be9f7f761cc2b0611a4b19155751844
      tl_expect_codesource='file:/C:/Program%%20Files/Live2D%%20Cubism%%205.2/app/lib/Live2D_Cubism.jar'
      ;;
  esac
  tl_expect_class_sha="$tl_pristine_sha"
  [[ "$tri_tlprod_leg" == on* ]] && tl_expect_class_sha="$tl_patched_sha"
  tl_run_id="tlprod-$tri_tlprod_leg"
fi
if [[ -n "$tri_tlindex_mode" ]]; then
  runner_args+=(
    --jvm-option '-Dturboism.validation.tlWeave.enabled=true'
    --jvm-option "-Dturboism.validation.tlWeave.mode=$tri_tlindex_mode"
    --jvm-option '-Dturboism.validation.tlWeave.profile=tl-official'
    --jvm-option '-Dturboism.validation.tlWeave.phase=t029-tlindex'
    --jvm-option "-Dturboism.validation.tlWeave.runId=$tl_run_id"
    --jvm-option '-Dturboism.validation.tlWeave.outputDir={HOME}/tl-weave'
    --jvm-option "-Dturboism.validation.tlWeave.expectClassSha256=$tl_expect_class_sha"
    --jvm-option "-Dturboism.validation.tlWeave.expectCaptureClassSha256=$tl_expect_class_sha"
    --jvm-option '-Dturboism.validation.tlWeave.expectLoader=jdk.internal.loader.ClassLoaders$AppClassLoader'
    --jvm-option "-Dturboism.validation.tlWeave.expectCodeSource=$tl_expect_codesource"
    --jvm-option '-Dturboism.validation.tlWeave.captureN=4'
  )
fi

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
elif [[ -n "$tri_tlprod_home_config" ]]; then
  runner_args+=(--home-config "$tri_tlprod_home_config")
elif [[ "$tlindex_pref_off" == 1 ]]; then
  runner_args+=(--home-config "$tlindex_off_config")
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
if [[ -n "$tri_weave_mode" ]]; then
  printf 'triWeaveMode=%s\ntriWeaveAgentSha256=%s\ntriWeaveLeg=%s\n' \
    "$tri_weave_mode" "$tri_weave_agent_sha256" "$tri_weave_leg" >&2
fi
if [[ -n "$tri_dweave_mode" ]]; then
  printf 'dmWeaveMode=%s\ndmWeaveAgentSha256=%s\ndmWeaveLeg=%s\n' \
    "$tri_dweave_mode" "$tri_weave_agent_sha256" "$tri_dweave_leg" >&2
fi
if [[ -n "$tri_tlindex_mode" ]]; then
  printf 'tlWeaveMode=%s\ntlWeaveAgentSha256=%s\ntlWeaveLeg=%s\n' \
    "$tri_tlindex_mode" "$tri_weave_agent_sha256" "${tri_tlindex_leg:-$tri_tlprod_leg}" >&2
  printf 'tlWeaveExpectClassSha256=%s\ntlWeaveExpectCodeSource=%s\n' \
    "$tl_expect_class_sha" "$tl_expect_codesource" >&2
fi
if [[ -n "$tri_tlprod_home_config" ]]; then
  printf 'tlindexPreference=explicit-ui\ntlindexHomeConfigSha256=%s\n' "$tri_tlprod_home_config_sha256" >&2
elif [[ "$tlindex_pref_off" == 1 ]]; then
  printf 'tlindexPreference=off\ntlindexOffConfigSha256=%s\n' "$tlindex_off_config_sha256" >&2
elif [[ -n "$tri_tlprod_leg" ]]; then
  printf 'tlindexPreference=default-on\n' >&2
fi

# No hook, client, or collector option is permitted here; readiness is limited to the single
# fixed deferred-check marker above. The only staged home inputs are the hash-pinned
# -meshoff/-atlasoff/-reuseoff/tlprod-off configs or the leg-matching explicit UI config above.
exec bash "$runner" "${runner_args[@]}"
