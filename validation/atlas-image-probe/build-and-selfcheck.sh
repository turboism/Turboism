#!/usr/bin/env bash
# Offline-only build and self-check. Never launches Cubism or loads official classes.
set -euo pipefail
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# Machine-specific input paths come from the ignored .env/environment entry.
# shellcheck source=/dev/null
source "$root/scripts/preview/host-validation-env.sh"
turboism_select_fixture 5303 || exit 2
editor_jar=""
version=""
publish=0
production_agent="${TURBOISM_ATLAS_IMAGE_PRODUCTION_AGENT:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --editor-jar)
      [[ $# -ge 2 ]] || { echo 'missing --editor-jar value' >&2; exit 2; }
      editor_jar="$2"; shift 2 ;;
    --version)
      [[ $# -ge 2 ]] || { echo 'missing --version value' >&2; exit 2; }
      version="$2"; shift 2 ;;
    --publish)
      publish=1; shift ;;
    --production-agent)
      [[ $# -ge 2 ]] || { echo 'missing --production-agent value' >&2; exit 2; }
      production_agent="$2"; shift 2 ;;
    *)
      echo 'Usage: build-and-selfcheck.sh [--editor-jar <read-only JAR> --version <5203|5302|5303>] [--publish [--production-agent <JAR>]]' >&2
      exit 2 ;;
  esac
done
if [[ -n "$editor_jar" && -z "$version" ]] || [[ -z "$editor_jar" && -n "$version" ]]; then
  echo '--editor-jar and --version must be supplied together' >&2
  exit 2
fi
if [[ -n "$version" && "$version" != 5203 && "$version" != 5302 && "$version" != 5303 ]]; then
  echo "unsupported version: $version" >&2
  exit 2
fi

fixture="${fixture_src:-}"
fixture_sha256=2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e
fixture_name=atlas_mapping_100.cmo3
[[ -n "$fixture" ]] || { echo 'fixed Circle100 fixture requires TURBOISM_HOST_VALIDATION_FIXTURE_5303 in ignored .env/environment' >&2; exit 2; }
[[ "$(basename -- "$fixture")" == "$fixture_name" ]] || { echo "fixed Circle100 fixture basename mismatch: $fixture" >&2; exit 1; }
[[ "$fixture" = /* ]] || { echo "fixed Circle100 fixture path must be absolute: $fixture" >&2; exit 1; }
fixture="$(realpath -e -- "$fixture")"
[[ -f "$fixture" ]] || { echo "fixed Circle100 fixture missing: $fixture" >&2; exit 1; }
[[ "$(sha256sum "$fixture" | cut -d' ' -f1)" == "$fixture_sha256" ]] || {
  echo "fixed Circle100 fixture hash mismatch: $fixture" >&2; exit 1;
}

mkdir -p "$root/build/atlas-image-probe"
work="$(mktemp -d "$root/build/atlas-image-probe/compile.XXXXXX")"
observer_classes="$work/observer"
driver_classes="$work/driver"
selfcheck_classes="$work/selfcheck"
selfcheck_jar_classes="$work/selfcheck-jar-classes"
mkdir -p "$observer_classes" "$driver_classes" "$selfcheck_classes" "$selfcheck_jar_classes"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
jar_bin="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
package_dir="$root/validation/atlas-image-probe/src/dev/turboism/validation/atlasimage"
selfcheck_dir="$root/validation/atlas-image-probe/selfcheck/dev/turboism/validation/atlasimage"

observer_sources=(
  "$package_dir/AtlasImageObserveContract.java"
  "$package_dir/AtlasImageLoadProbeAgent.java"
  "$package_dir/ObservationSession.java"
  "$package_dir/ReportCompletion.java"
)
driver_sources=(
  "$package_dir/AtlasImageObserveContract.java"
  "$package_dir/SceneDriverState.java"
  "$package_dir/AtlasImageSceneDriverAgent.java"
)
"$javac_bin" --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$observer_classes" "${observer_sources[@]}"
"$javac_bin" --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$driver_classes" "${driver_sources[@]}"
"$javac_bin" --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$observer_classes:$driver_classes" -d "$selfcheck_classes" \
  "$selfcheck_dir"/*.java

observer_jar="$work/atlas-image-load-probe.jar"
driver_jar="$work/atlas-image-scene-driver.jar"
printf 'Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.atlasimage.AtlasImageLoadProbeAgent\nCan-Redefine-Classes: false\nCan-Retransform-Classes: false\n\n' > "$work/observer.mf"
"$jar_bin" --create --file "$observer_jar" --manifest "$work/observer.mf" -C "$observer_classes" .
printf 'Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.atlasimage.AtlasImageSceneDriverAgent\nCan-Redefine-Classes: false\nCan-Retransform-Classes: false\n\n' > "$work/driver.mf"
"$jar_bin" --create --file "$driver_jar" --manifest "$work/driver.mf" -C "$driver_classes" .

# Assemble one synthetic-only test jar without adding official source or host classes to its class path.
cp -a "$observer_classes/." "$selfcheck_jar_classes/"
cp -a "$driver_classes/." "$selfcheck_jar_classes/"
cp -a "$selfcheck_classes/." "$selfcheck_jar_classes/"
printf 'Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.atlasimage.AtlasImageLoadProbeSelfCheck\nMain-Class: dev.turboism.validation.atlasimage.AtlasImageLoadProbeSelfCheck\n\n' > "$work/selfcheck.mf"
"$jar_bin" --create --file "$work/selfcheck.jar" --manifest "$work/selfcheck.mf" \
  -C "$selfcheck_jar_classes" .

"$java_bin" -javaagent:"$work/selfcheck.jar" -jar "$work/selfcheck.jar" \
  | tee "$work/selfcheck.log"
"$java_bin" -cp "$work/selfcheck.jar" \
  dev.turboism.validation.atlasimage.AtlasImageLifecycleSelfCheck \
  2>&1 | tee "$work/lifecycle.log"
"$java_bin" -cp "$work/selfcheck.jar" \
  dev.turboism.validation.atlasimage.AtlasImageLifecycleSelfCheck \
  shutdown-race "$work/shutdown-race" > "$work/shutdown-race.log" 2>&1
grep -q '^completionReason=EXPLICIT_FINISH$' "$work/shutdown-race/result.properties"
grep -q '^reportAttempts=1$' "$work/shutdown-race/result.properties"
"$java_bin" -cp "$work/selfcheck.jar" \
  dev.turboism.validation.atlasimage.AtlasImageProbeConfigurationSelfCheck \
  | tee "$work/configuration-selfcheck.log"

printf 'version=unsupported\n' > "$work/rejected.properties"
"$java_bin" -javaagent:"$observer_jar=$work/rejected.properties" -version > "$work/rejected.log" 2>&1
grep -q 'ATLAS_IMAGE_LOAD_PROBE_BLOCKED' "$work/rejected.log"
printf 'version=5303\neditorJar=%s\noutputRoot=%s\nrunId=wrong-identity\n' \
  "$work/selfcheck.jar" "$work" > "$work/wrong-identity.properties"
"$java_bin" -javaagent:"$observer_jar=$work/wrong-identity.properties" -version \
  > "$work/wrong-identity.log" 2>&1
grep -q 'ATLAS_IMAGE_LOAD_PROBE_BLOCKED' "$work/wrong-identity.log"
test ! -e "$work/wrong-identity"

"$jar_bin" --list --file "$observer_jar" > "$work/observer-contents.txt"
"$jar_bin" --list --file "$driver_jar" > "$work/driver-contents.txt"
if grep -Eq '(SelfCheck|Synthetic|^com/live2d/|^jp/noids/|AtlasImageSceneDriver)' "$work/observer-contents.txt"; then
  echo 'FAIL: driver/test or proprietary classes leaked into validation observer' >&2
  exit 1
fi
if grep -Eq '(SelfCheck|Synthetic|^com/live2d/|^jp/noids/)' "$work/driver-contents.txt"; then
  echo 'FAIL: test fixture or proprietary classes leaked into fixed driver' >&2
  exit 1
fi

# Explicit Python protocol validation remains offline and optimization-independent.
for optimize in 0 1 2; do
  PYTHONOPTIMIZE="$optimize" python3 -B \
    "$root/validation/atlas-image-probe/selfcheck/premain_smoke_selfcheck.py" \
    > "$work/smoke-validator-$optimize.log" 2>&1
done

smoke=NOT_RUN
if [[ -n "$editor_jar" ]]; then
  python3 -B "$root/validation/atlas-image-probe/premain-smoke.py" \
    --java "$java_bin" --agent "$observer_jar" --editor-jar "$editor_jar" \
    --version "$version" --output "$work/smoke"
  smoke=PASS
fi
observer_sha256="$(sha256sum "$observer_jar" | cut -d' ' -f1)"
driver_sha256="$(sha256sum "$driver_jar" | cut -d' ' -f1)"
printf '%s  %s\n%s  %s\n' "$observer_sha256" "$observer_jar" \
  "$driver_sha256" "$driver_jar" | tee "$work/artifact.sha256"
{
  printf 'status=OFFLINE_PASS\n'
  printf 'observerArtifact=%s\nobserverSha256=%s\n' "$observer_jar" "$observer_sha256"
  printf 'driverArtifact=%s\ndriverSha256=%s\n' "$driver_jar" "$driver_sha256"
  printf 'premainReadOnlySmoke=%s\n' "$smoke"
} > "$work/verified.tmp"
mv "$work/verified.tmp" "$work/verified.properties"

if [[ "$publish" == 1 ]]; then
  worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" \
    bash "$root/scripts/dev/worktree-id.sh")"
  if [[ -z "$production_agent" ]]; then
    production_agent="$root/build/preview/$worktree_id/turboism-agent.jar"
  fi
  [[ -f "$production_agent" ]] || {
    echo "publish requires the explicit production agent: $production_agent" >&2
    exit 2
  }
  production_agent="$(realpath -e -- "$production_agent")"
  agent_root="$(realpath -m -- "$root/build/preview/$worktree_id")"
  [[ "$production_agent" == "$agent_root"/* ]] || {
    echo "publish production agent escapes this worktree preview root: $production_agent" >&2
    exit 1
  }
  production_sha256="$(sha256sum "$production_agent" | cut -d' ' -f1)"
  delivery_root="$root/build/preview/$worktree_id/atlas-image-observe"
  bundle_root="$delivery_root/bundle.$observer_sha256.$driver_sha256"
  manifest="$delivery_root/bundle.manifest"
  [[ ! -e "$manifest" ]] || { echo "refusing to overwrite published manifest: $manifest" >&2; exit 1; }
  mkdir -p "$delivery_root"
  mkdir "$bundle_root"
  cp -- "$observer_jar" "$bundle_root/atlas-image-load-probe.jar"
  cp -- "$driver_jar" "$bundle_root/atlas-image-scene-driver.jar"
  [[ "$(sha256sum "$bundle_root/atlas-image-load-probe.jar" | cut -d' ' -f1)" == "$observer_sha256" ]]
  [[ "$(sha256sum "$bundle_root/atlas-image-scene-driver.jar" | cut -d' ' -f1)" == "$driver_sha256" ]]
  manifest_tmp="$delivery_root/.bundle.manifest.$$.$RANDOM.tmp"
  cat > "$manifest_tmp" <<EOF
schemaVersion=1
scene=atlas-image-observe:5303
worktreeId=$worktree_id
bundleRoot=$bundle_root
agent=$production_agent
agentSha256=$production_sha256
observer=$bundle_root/atlas-image-load-probe.jar
observerSha256=$observer_sha256
driver=$bundle_root/atlas-image-scene-driver.jar
driverSha256=$driver_sha256
fixture=$fixture
fixtureName=$fixture_name
fixtureSha256=$fixture_sha256
officialJarSha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166
runnable=false
EOF
  python3 - "$manifest_tmp" "$manifest" <<'PY'
import os
import sys
source, target = sys.argv[1:]
try:
    os.link(source, target)
except FileExistsError:
    raise SystemExit(f"refusing to overwrite published manifest: {target}")
os.unlink(source)
PY
  printf 'publishedManifest=%s\n' "$manifest"
  printf 'publishedBundle=%s\n' "$bundle_root"
fi

printf 'OFFLINE_ONLY PASS evidence=%s observer=%s driver=%s premainReadOnlySmoke=%s\n' \
  "$work" "$observer_jar" "$driver_jar" "$smoke"
