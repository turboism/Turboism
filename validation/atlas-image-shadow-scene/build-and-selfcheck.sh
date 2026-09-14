#!/usr/bin/env bash
# Offline-only T040 build. It never prepares, submits, launches, or loads official classes.
set -euo pipefail
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scene_dir="$root/validation/atlas-image-shadow-scene"
# shellcheck source=/dev/null
source "$root/scripts/preview/host-validation-env.sh"
# The published manifest pins exactly one reviewed fixture pair. This mirrors the wrapper's
# run-label profiles and the driver's runtime allowlist; `--fixture-profile heavy` publishes the
# production-scale model instead of the default Circle100 fixture.
fixture_profile=circle100
circle100_fixture_env_key=TURBOISM_HOST_VALIDATION_FIXTURE_5303
circle100_fixture_name=atlas_mapping_100.cmo3
circle100_fixture_sha256=2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e
heavy_fixture_env_key=TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303
heavy_fixture_name=heavy.cmo3
heavy_fixture_sha256=029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c
fixture_name="$circle100_fixture_name"
fixture_sha256="$circle100_fixture_sha256"
fixture=""
official_jar_sha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166
t039_class_sha256=ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6
t039_shape_sha256=a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f
t039_runtime_source_binding=target-pd
# Runner 5303 launches the cloned prefix at this fixed Windows path; this is
# a task-prefix candidate, not the build-machine reference JAR path.
t039_trusted_source_path='C:\Program Files\Live2D Cubism 5.3.03\app\lib\Live2D_Cubism.jar'

publish=0
host_profile=5303
production_agent="${TURBOISM_ATLAS_IMAGE_SHADOW_PRODUCTION_AGENT:-}"
t039_agent="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_AGENT:-}"
t039_code_source="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE:-}"
t039_loader_class="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_LOADER_CLASS:-}"
t039_helper_sha256="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_HELPER_SHA256:-}"
t039_t038_helper_sha256="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_T038_HELPER_SHA256:-}"

fail() {
  printf 't040 shadow build: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --publish) publish=1; shift ;;
    --host-profile)
      [[ $# -ge 2 ]] || fail 'missing --host-profile value'
      case "$2" in
        5303|5203) host_profile="$2" ;;
        *) fail "unknown host profile: $2" ;;
      esac
      shift 2 ;;
    --fixture-profile)
      [[ $# -ge 2 ]] || fail 'missing --fixture-profile value'
      case "$2" in
        circle100|heavy) fixture_profile="$2" ;;
        *) fail "unknown fixture profile: $2" ;;
      esac
      shift 2 ;;
    --production-agent)
      [[ $# -ge 2 ]] || fail 'missing --production-agent value'
      production_agent="$2"; shift 2 ;;
    --t039-agent)
      [[ $# -ge 2 ]] || fail 'missing --t039-agent value'
      t039_agent="$2"; shift 2 ;;
    --t039-code-source)
      [[ $# -ge 2 ]] || fail 'missing --t039-code-source value'
      t039_code_source="$2"; shift 2 ;;
    --t039-loader-class)
      [[ $# -ge 2 ]] || fail 'missing --t039-loader-class value'
      t039_loader_class="$2"; shift 2 ;;
    --t039-helper-sha256)
      [[ $# -ge 2 ]] || fail 'missing --t039-helper-sha256 value'
      t039_helper_sha256="$2"; shift 2 ;;
    --t039-t038-helper-sha256)
      [[ $# -ge 2 ]] || fail 'missing --t039-t038-helper-sha256 value'
      t039_t038_helper_sha256="$2"; shift 2 ;;
    *) fail "unknown option: $1" ;;
  esac
done

# The 5203 profile has exactly one reviewed fixture (the opacity52 pair the mcp 5203 wrapper
# pins) and no heavy profile; its canonical bundle name is fixed regardless of the local name.
opacity52_fixture_name=part-opacity-fixture-52-final.cmo3
opacity52_fixture_sha256=331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028
official_jar_sha256_5203=bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd
if [[ "$host_profile" == 5203 ]]; then
  [[ "$fixture_profile" == circle100 ]] \
    || fail 'the 5203 profile has a single reviewed fixture; --fixture-profile does not apply'
  official_jar_sha256="$official_jar_sha256_5203"
fi
case "$fixture_profile" in
  heavy)
    fixture_env_key="$heavy_fixture_env_key"
    fixture="${!heavy_fixture_env_key:-}"
    fixture_name="$heavy_fixture_name"
    fixture_sha256="$heavy_fixture_sha256"
    ;;
  *)
    if [[ "$host_profile" == 5203 ]]; then
      fixture_env_key=TURBOISM_HOST_VALIDATION_FIXTURE_5203
      turboism_select_fixture 5203 || exit 2
      fixture="${fixture_src:-}"
      fixture_name="$opacity52_fixture_name"
      fixture_sha256="$opacity52_fixture_sha256"
    else
      fixture_env_key="$circle100_fixture_env_key"
      turboism_select_fixture 5303 || exit 2
      fixture="${fixture_src:-}"
      fixture_name="$circle100_fixture_name"
      fixture_sha256="$circle100_fixture_sha256"
    fi
    ;;
esac
[[ -n "$fixture" ]] || fail "$fixture_profile fixture requires $fixture_env_key in ignored .env/environment"
[[ "$fixture" = /* ]] || fail "fixture path must be absolute: $fixture"
if [[ "$host_profile" == 5303 ]]; then
  [[ "$(basename -- "$fixture")" == "$fixture_name" ]] \
    || fail "fixture source basename mismatch: $fixture"
fi
[[ -f "$fixture" && ! -L "$fixture" ]] || fail "fixture source is not a regular non-symlink file"
fixture="$(realpath -e -- "$fixture")"
[[ "$(sha256sum "$fixture" | cut -d' ' -f1)" == "$fixture_sha256" ]] \
  || fail "allowlisted $fixture_name hash mismatch: $fixture"

mkdir -p "$root/build/atlas-image-shadow-scene"
work="$(mktemp -d "$root/build/atlas-image-shadow-scene/compile.XXXXXX")"
prod_classes="$work/classes"
selfcheck_classes="$work/selfcheck-classes"
selfcheck_jar_classes="$work/selfcheck-jar-classes"
mkdir -p "$prod_classes" "$selfcheck_classes" "$selfcheck_jar_classes"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
jar_bin="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
prod_sources=("$scene_dir/src/dev/turboism/validation/atlasimage/shadow/ShadowSceneContract.java"
  "$scene_dir/src/dev/turboism/validation/atlasimage/shadow/T039FreezeBridge.java"
  "$scene_dir/src/dev/turboism/validation/atlasimage/shadow/FixedEdt.java"
  "$scene_dir/src/dev/turboism/validation/atlasimage/shadow/StageEvidence.java"
  "$scene_dir/src/dev/turboism/validation/atlasimage/shadow/ShadowPayloadStore.java"
  "$scene_dir/src/dev/turboism/validation/atlasimage/shadow/T040ShadowSceneDriverAgent.java")
selfcheck_sources=("$scene_dir/selfcheck/com/live2d/cubism/doc/modeling/ui/atlasEditor/f\$b.java"
  "$scene_dir/selfcheck/com/live2d/cubism/doc/modeling/ui/atlasEditor/a/f.java"
  "$scene_dir/selfcheck/com/live2d/ui/control/a/a/j.java"
  "$scene_dir/selfcheck/dev/turboism/validation/atlasimage/shadow/T040SelfCheck.java")

"$javac_bin" --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$prod_classes" "${prod_sources[@]}"
"$javac_bin" --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$prod_classes" -d "$selfcheck_classes" "${selfcheck_sources[@]}"

# Keep the official host JAR and all T039 sources out of both ordinary test classpaths.
driver_jar="$work/atlas-image-shadow-scene-driver.jar"
printf 'Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.atlasimage.shadow.T040ShadowSceneDriverAgent\nCan-Redefine-Classes: false\nCan-Retransform-Classes: false\n\n' > "$work/driver.mf"
"$jar_bin" --create --file "$driver_jar" --manifest "$work/driver.mf" -C "$prod_classes" .
cp -a "$prod_classes/." "$selfcheck_jar_classes/"
cp -a "$selfcheck_classes/." "$selfcheck_jar_classes/"
printf 'Manifest-Version: 1.0\nMain-Class: dev.turboism.validation.atlasimage.shadow.T040SelfCheck\n\n' > "$work/selfcheck.mf"
selfcheck_jar="$work/t040-selfcheck.jar"
"$jar_bin" --create --file "$selfcheck_jar" --manifest "$work/selfcheck.mf" \
  -C "$selfcheck_jar_classes" .

"$java_bin" -cp "$selfcheck_jar" \
  dev.turboism.validation.atlasimage.shadow.T040SelfCheck | tee "$work/selfcheck.log"

if "$jar_bin" --list --file "$driver_jar" | grep -Eq '(T040SelfCheck|^com/live2d/|^dev/turboism/validation/atlasimage/shadow/T040SelfCheck)'; then
  fail 'selfcheck or fake host class leaked into driver JAR'
fi
if "$jar_bin" --list --file "$driver_jar" | grep -Eq 'T039ShadowAgent|Live2D_Cubism'; then
  fail 'T039 or official host class leaked into driver JAR'
fi

source_hash_file="$work/source.sha256"
(
  cd "$scene_dir"
  find . -type f -not -path './build/*' -print0 | sort -z | xargs -0 sha256sum
) > "$source_hash_file"

artifact_sha256="$work/artifact.sha256"
driver_sha256="$(sha256sum "$driver_jar" | cut -d' ' -f1)"
printf '%s  %s\n' "$driver_sha256" "$driver_jar" | tee "$artifact_sha256"
printf 'status=OFFLINE_PASS\ndriverArtifact=%s\ndriverSha256=%s\n' \
  "$driver_jar" "$driver_sha256" > "$work/verified.properties"
printf 'T040_SHADOW_SCENE_BUILD PASS evidence=%s driver=%s driverSha256=%s hostExecuted=false\n' \
  "$work" "$driver_jar" "$driver_sha256"

if [[ "$publish" == 1 ]]; then
  [[ -n "$production_agent" ]] || fail '--publish requires explicit --production-agent'
  # The 5303 profile pins the T039 shadow agent into the bundle; the 5203 profile ships no
  # T039 artifact at all, so every --t039-* input is forbidden there rather than ignored.
  if [[ "$host_profile" == 5303 ]]; then
    [[ -n "$t039_agent" ]] || fail '--publish requires explicit --t039-agent'
    [[ -n "$t039_code_source" ]] || fail '--publish requires explicit --t039-code-source'
    [[ -n "$t039_loader_class" ]] || fail '--publish requires explicit --t039-loader-class'
    [[ "$t039_helper_sha256" =~ ^[0-9a-f]{64}$ ]] \
      || fail '--publish requires lowercase T039 helper SHA-256'
    [[ "$t039_t038_helper_sha256" =~ ^[0-9a-f]{64}$ ]] \
      || fail '--publish requires lowercase T038 helper SHA-256'
    [[ "$t039_code_source" == */Live2D_Cubism.jar ]] \
      || fail 'T039 code source must name Live2D_Cubism.jar'
    [[ -f "$t039_code_source" && ! -L "$t039_code_source" ]] \
      || fail 'T039 code source is not a regular non-symlink file'
    t039_code_source="$(realpath -e -- "$t039_code_source")"
    [[ "$(basename -- "$t039_code_source")" == Live2D_Cubism.jar ]] || fail 'T039 code source basename mismatch'
    [[ "$(sha256sum "$t039_code_source" | cut -d' ' -f1)" == "$official_jar_sha256" ]] \
      || fail 'T039 code source official JAR hash mismatch'
  else
    [[ -z "$t039_agent" && -z "$t039_code_source" && -z "$t039_loader_class"
      && -z "$t039_helper_sha256" && -z "$t039_t038_helper_sha256" ]] \
      || fail 'the 5203 profile forbids every --t039-* input'
  fi
  for artifact in "$production_agent" ${t039_agent:+"$t039_agent"}; do
    [[ -f "$artifact" && ! -L "$artifact" ]] || fail "publish artifact is not a regular non-symlink file: $artifact"
  done
  production_agent="$(realpath -e -- "$production_agent")"
  production_sha256="$(sha256sum "$production_agent" | cut -d' ' -f1)"
  t039_agent_sha256=''
  if [[ -n "$t039_agent" ]]; then
    t039_agent="$(realpath -e -- "$t039_agent")"
    t039_agent_sha256="$(sha256sum "$t039_agent" | cut -d' ' -f1)"
  fi
  worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" \
    bash "$root/scripts/dev/worktree-id.sh")"
  delivery_root="$root/build/preview/$worktree_id/atlas-image-shadow"
  if [[ "$host_profile" == 5303 ]]; then
    bundle_root="$delivery_root/bundle.$driver_sha256.$t039_agent_sha256.$production_sha256"
    manifest="$delivery_root/bundle.manifest"
  else
    bundle_root="$delivery_root/bundle.$driver_sha256.$production_sha256"
    manifest="$delivery_root/bundle-5203.manifest"
  fi
  [[ ! -e "$manifest" ]] || fail "refusing to overwrite published manifest: $manifest"
  [[ ! -e "$bundle_root" ]] || fail "refusing to overwrite published bundle: $bundle_root"
  mkdir -p "$delivery_root"
  bundle_tmp="$(mktemp -d "$delivery_root/.bundle.XXXXXX")"
  cleanup_bundle_tmp() { rm -rf -- "$bundle_tmp"; }
  trap cleanup_bundle_tmp EXIT
  cp -- "$production_agent" "$bundle_tmp/turboism-agent.jar"
  cp -- "$driver_jar" "$bundle_tmp/atlas-image-shadow-scene-driver.jar"
  [[ "$(sha256sum "$bundle_tmp/turboism-agent.jar" | cut -d' ' -f1)" == "$production_sha256" ]]
  [[ "$(sha256sum "$bundle_tmp/atlas-image-shadow-scene-driver.jar" | cut -d' ' -f1)" == "$driver_sha256" ]]
  if [[ -n "$t039_agent" ]]; then
    cp -- "$t039_agent" "$bundle_tmp/t039-shadow-agent.jar"
    [[ "$(sha256sum "$bundle_tmp/t039-shadow-agent.jar" | cut -d' ' -f1)" == "$t039_agent_sha256" ]]
  fi
  mv -- "$bundle_tmp" "$bundle_root"
  trap - EXIT
  manifest_tmp="$(mktemp "$delivery_root/.bundle.manifest.XXXXXX")"
  cat > "$manifest_tmp" <<EOF
schemaVersion=1
scene=atlas-image-shadow:$host_profile
worktreeId=$worktree_id
bundleRoot=$bundle_root
productionAgent=$bundle_root/turboism-agent.jar
productionAgentSha256=$production_sha256
driver=$bundle_root/atlas-image-shadow-scene-driver.jar
driverSha256=$driver_sha256
fixture=$fixture
fixtureName=$fixture_name
fixtureSha256=$fixture_sha256
officialJarSha256=$official_jar_sha256
EOF
  if [[ "$host_profile" == 5303 ]]; then
    cat >> "$manifest_tmp" <<EOF
t039Agent=$bundle_root/t039-shadow-agent.jar
t039AgentSha256=$t039_agent_sha256
t039Profile=5303
t039SourceBinding=$t039_runtime_source_binding
t039TrustedSourcePath=$t039_trusted_source_path
t039JarSha256=$official_jar_sha256
t039ClassSha256=$t039_class_sha256
t039ShapeSha256=$t039_shape_sha256
t039LoaderClass=$t039_loader_class
t039HelperSha256=$t039_helper_sha256
t039T038HelperSha256=$t039_t038_helper_sha256
t039ShadowMode=shadow-ready
t039ShadowOptIn=T039_SHADOW_EXPLICIT_OPT_IN
EOF
  fi
  printf 'runnable=false\n' >> "$manifest_tmp"
  ln -- "$manifest_tmp" "$manifest"
  rm -f -- "$manifest_tmp"
  printf 'publishedManifest=%s\npublishedBundle=%s\n' "$manifest" "$bundle_root"
fi
