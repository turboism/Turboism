#!/usr/bin/env bash
# Offline regressions for the blocked T040 shadow scene.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scene_dir="$root/validation/atlas-image-shadow-scene"
builder="$scene_dir/build-and-selfcheck.sh"
wrapper="$root/scripts/preview/run-atlas-image-shadow-host-validation.sh"
scheduler_manifest="$root/scripts/preview/host-validation-020-shadow.json"
# shellcheck source=/dev/null
source "$root/scripts/preview/host-validation-env.sh"
turboism_select_fixture 5303 || exit 2
fixture="${fixture_src:-}"
fixture_name=atlas_mapping_100.cmo3
fixture_sha256=2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e
official_jar="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE:-}"
t039_agent="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_AGENT:-}"
runtime_source_binding=target-pd
runtime_trusted_source_path='C:\Program Files\Live2D Cubism 5.3.03\app\lib\Live2D_Cubism.jar'

fail() {
  printf 'atlas-image-shadow offline test: %s\n' "$*" >&2
  exit 1
}

[[ -n "$fixture" ]] || fail 'set TURBOISM_HOST_VALIDATION_FIXTURE_5303 in ignored .env/environment'
[[ "$fixture" = /* ]] || fail "fixture path must be absolute: $fixture"
[[ "$(basename -- "$fixture")" == "$fixture_name" ]] || fail 'fixture source basename mismatch'
[[ -f "$fixture" && ! -L "$fixture" ]] || fail 'fixture source is not a regular non-symlink file'
fixture="$(realpath -e -- "$fixture")"
[[ "$(sha256sum "$fixture" | cut -d' ' -f1)" == "$fixture_sha256" ]] \
  || fail 'fixed Circle100 fixture hash mismatch'
[[ -n "$official_jar" ]] || fail 'set explicit TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE for offline manifest validation'
[[ -f "$official_jar" && ! -L "$official_jar" ]] || fail 'configured T039 code source is not a regular file'
official_jar="$(realpath -e -- "$official_jar")"
[[ "$(basename -- "$official_jar")" == Live2D_Cubism.jar ]] || fail 'configured T039 code source basename mismatch'
[[ "$(sha256sum "$official_jar" | cut -d' ' -f1)" == bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166 ]] \
  || fail 'configured T039 code source hash mismatch'
[[ -n "$t039_agent" ]] || fail 'set explicit TURBOISM_ATLAS_IMAGE_SHADOW_T039_AGENT for real T039 bridge regression'
[[ -f "$t039_agent" && ! -L "$t039_agent" ]] || fail 'configured T039 agent is not a regular file'
t039_agent="$(realpath -e -- "$t039_agent")"
[[ "$(basename -- "$t039_agent")" == t039-shadow-agent.jar ]] || fail 'configured T039 agent basename mismatch'
jar tf "$t039_agent" | grep -qx 'dev/turboism/validation/atlasimage/t039/T039ShadowAgent.class' \
  || fail 'configured T039 agent does not contain the fixed public class'

bash -n "$builder"
bash -n "$wrapper"
python3 -m json.tool "$scheduler_manifest" >/dev/null
! grep -Eq -- '--(remote-pre-launch|remote-post-launch|remote-pre-cleanup|client-script|ready-marker|failure-marker)' "$wrapper"

python3 - "$wrapper" \
  "$scene_dir/src/dev/turboism/validation/atlasimage/shadow/T040ShadowSceneDriverAgent.java" \
  "$scene_dir/src/dev/turboism/validation/atlasimage/shadow/T039FreezeBridge.java" \
  "$scene_dir/src/dev/turboism/validation/atlasimage/shadow/ShadowSceneContract.java" \
  "$scene_dir/src/dev/turboism/validation/atlasimage/shadow/ShadowPayloadStore.java" <<'PY'
from pathlib import Path
import sys

def require(condition, message):
    if not condition:
        raise SystemExit(message)

wrapper = Path(sys.argv[1]).read_text(encoding="utf-8")
driver = Path(sys.argv[2]).read_text(encoding="utf-8")
bridge = Path(sys.argv[3]).read_text(encoding="utf-8")
contract = Path(sys.argv[4]).read_text(encoding="utf-8")
payload = Path(sys.argv[5]).read_text(encoding="utf-8")
for token in ('runner_args+=(--prepare-dir "$prepare_dir")', 'runner_args+=(--dry-run)'):
    require(token in wrapper, f"wrapper branch missing: {token}")
for placeholder in ("{HOME}", "{TASK_ID}", "{FIXTURE}", "{FIXTURE_NAME}"):
    require(placeholder in wrapper, f"JVM placeholder missing: {placeholder}")
require("sourceBinding" in wrapper and "trustedSourcePaths" in wrapper,
        "runtime source binding JVM properties missing")
require("codeSource" not in wrapper, "build-host codeSource leaked into runtime wrapper")
for forbidden in ("System.exit", "Runtime.getRuntime().exec", "ProcessBuilder", "invokeAndWait"):
    require(forbidden not in driver, f"forbidden driver capability present: {forbidden}")
for marker in ("T039ShadowAgent", "freezeCapture", "SHADOW_READY", "removalStatus"):
    require(marker in bridge + contract, f"T039 bridge contract marker missing: {marker}")
require("collectionStatus=COMPLETE" in payload, "payload store missing COMPLETE marker")
require("collectionStatus=FAILED" in payload, "payload store missing FAILED marker")
print("T040_STATIC_CONTRACT PASS bounded-driver=true no-host-launch=true")
PY

build_log="$(mktemp)"
TURBOISM_HOST_VALIDATION_FIXTURE_5303="$fixture" bash "$builder" > "$build_log"
grep -q '^T040_SHADOW_SCENE_SELFCHECK PASS checks=' "$build_log"
grep -q '^T040_SHADOW_SCENE_BUILD PASS ' "$build_log"
grep -q 'hostExecuted=false' "$build_log"
driver="$(sed -n 's/.*driver=\([^ ]*\) driverSha256=.*/\1/p' "$build_log" | tail -n 1)"
driver_sha256="$(sed -n 's/.*driverSha256=\([0-9a-f]*\) hostExecuted=.*/\1/p' "$build_log" | tail -n 1)"
[[ -f "$driver" ]] || fail 'build did not report a driver artifact'
[[ "$(sha256sum "$driver" | cut -d' ' -f1)" == "$driver_sha256" ]] || fail 'reported driver hash mismatch'

# The fixed delivery manifest is blocked and the scheduler must reject planning it.
grep -q '"runnable": false' "$scheduler_manifest"
if python3 "$root/scripts/preview/host_validation.py" --manifest "$scheduler_manifest" \
    plan atlas-image-shadow:5303 > "$build_log.plan" 2>&1; then
  fail 'blocked T040 manifest was planned as runnable'
fi
grep -q 'blocked:' "$build_log.plan"

worktree_id="atlas-shadow-test-$BASHPID"
preview_root="$root/build/preview/$worktree_id"
test_root="$(mktemp -d)"
cat > "$test_root/T040ActualT039BridgeProbe.java" <<'JAVA'
package dev.turboism.validation.atlasimage.shadow;

public final class T040ActualT039BridgeProbe {
    private T040ActualT039BridgeProbe() {}

    public static void main(final String[] args) throws Exception {
        try {
            T039FreezeBridge.invokeReal("offline-unarmed", "5303");
            throw new IllegalStateException("unarmed real T039 bridge unexpectedly returned");
        } catch (IllegalStateException expected) {
            System.out.println("T040_T039_BRIDGE_REAL_ARTIFACT PASS publicStatic=true unarmedRejected=true");
        }
    }
}
JAVA
mkdir -p "$test_root/bridge-classes"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$driver:$t039_agent" -d "$test_root/bridge-classes" \
  "$test_root/T040ActualT039BridgeProbe.java"
java -cp "$test_root/bridge-classes:$driver:$t039_agent" \
  dev.turboism.validation.atlasimage.shadow.T040ActualT039BridgeProbe
trap 'rm -rf -- "$test_root" "$preview_root" "$build_log" "$build_log.plan"' EXIT
bundle="$preview_root/atlas-image-shadow/bundle.test"
mkdir -p "$bundle"
printf 'synthetic production agent\n' > "$bundle/turboism-agent.jar"
cp -- "$t039_agent" "$bundle/t039-shadow-agent.jar"
cp -- "$driver" "$bundle/atlas-image-shadow-scene-driver.jar"
production_sha256="$(sha256sum "$bundle/turboism-agent.jar" | cut -d' ' -f1)"
t039_agent_sha256="$(sha256sum "$bundle/t039-shadow-agent.jar" | cut -d' ' -f1)"
driver_sha256="$(sha256sum "$bundle/atlas-image-shadow-scene-driver.jar" | cut -d' ' -f1)"
manifest="$test_root/bundle.manifest"
cat > "$manifest" <<EOF
schemaVersion=1
scene=atlas-image-shadow:5303
worktreeId=$worktree_id
bundleRoot=$bundle
productionAgent=$bundle/turboism-agent.jar
productionAgentSha256=$production_sha256
t039Agent=$bundle/t039-shadow-agent.jar
t039AgentSha256=$t039_agent_sha256
driver=$bundle/atlas-image-shadow-scene-driver.jar
driverSha256=$driver_sha256
fixture=$fixture
fixtureName=$fixture_name
fixtureSha256=$fixture_sha256
officialJarSha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166
t039Profile=5303
t039SourceBinding=$runtime_source_binding
t039TrustedSourcePath=$runtime_trusted_source_path
t039JarSha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166
t039ClassSha256=ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6
t039ShapeSha256=a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f
t039LoaderClass=synthetic.Loader
t039HelperSha256=1111111111111111111111111111111111111111111111111111111111111111
t039T038HelperSha256=2222222222222222222222222222222222222222222222222222222222222222
t039ShadowMode=shadow-ready
t039ShadowOptIn=T039_SHADOW_EXPLICIT_OPT_IN
runnable=false
EOF

mkdir -p "$test_root/golden" "$test_root/host" "$test_root/bin"
printf '#!/usr/bin/env bash\nprintf unexpected-hook\nexit 99\n' > "$test_root/bin/shorin-proton-wrapper"
printf '#!/usr/bin/env bash\nprintf unexpected-runner\nexit 99\n' > "$test_root/proton-runner"
chmod +x "$test_root/bin/shorin-proton-wrapper" "$test_root/proton-runner"
runner_env=(
  "TURBOISM_WORKTREE_ID=$worktree_id"
  "TURBOISM_HOST_VALIDATION_FIXTURE_5303=$fixture"
  "TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX=$test_root/golden"
  "TURBOISM_HOST_VALIDATION_HOST_ROOT=$test_root/host"
  "TURBOISM_HOST_VALIDATION_PROTON_RUNNER=$test_root/proton-runner"
  "TURBOISM_HOST_VALIDATION_TRANSPORT=local"
  "TURBOISM_QUEUE_RUN_ID=atlas-image-shadow-5303-test-001"
  "PATH=$test_root/bin:$PATH"
)
run_wrapper_label() {
  local label="$1"; shift
  env "${runner_env[@]}" "$wrapper" 5303 "$label" --bundle-manifest "$manifest" "$@"
}
run_wrapper() {
  run_wrapper_label offline "$@"
}
run_wrapper --dry-run > "$test_root/dry-run.log"
task_id=atlas-image-shadow-5303-test-001
expected_fixture_path="$test_root/host/atlas-image-shadow/5303-offline/$task_id/$task_id-$fixture_name"
expected_cubism_path="$test_root/host/atlas-image-shadow/5303-offline/$task_id/prefix/pfx/drive_c/Program Files/Live2D Cubism 5.3.03"
grep -q '^name=atlas-image-shadow$' "$test_root/dry-run.log"
grep -q '^version=5303$' "$test_root/dry-run.log"
grep -q '^auxAgentCount=2$' "$test_root/dry-run.log"
grep -q "^fixtureName=$task_id-$fixture_name$" "$test_root/dry-run.log"
grep -q "^fixturePath=$expected_fixture_path$" "$test_root/dry-run.log"
grep -q "^clonedCubism=$expected_cubism_path$" "$test_root/dry-run.log"
grep -Fq -- "-Dturboism.validation.atlasImageShadow.home=Z:" "$test_root/dry-run.log"
grep -Fq -- "-Dturboism.validation.atlasImageShadow.taskId=$task_id" "$test_root/dry-run.log"
grep -Fq -- "-Dturboism.validation.atlasImageShadow.fixtureName=$task_id-$fixture_name" "$test_root/dry-run.log"
grep -Fq -- "-Dturboism.validation.t039.sourceBinding=$runtime_source_binding" "$test_root/dry-run.log"
grep -Fq -- "-Dturboism.validation.t039.trustedSourcePaths=$runtime_trusted_source_path" "$test_root/dry-run.log"
! grep -Fq -- "$official_jar" "$test_root/dry-run.log"
! grep -Fq -- 'trustedSourcePaths=Z:' "$test_root/dry-run.log"
grep -q '^remotePreLaunch=$' "$test_root/dry-run.log"
grep -q '^remotePostLaunch=$' "$test_root/dry-run.log"
grep -q '^remotePreCleanup=$' "$test_root/dry-run.log"
grep -q '^clientScript=$' "$test_root/dry-run.log"
[[ ! -e "$test_root/unexpected-hook.log" ]] || fail 'dry-run executed an external hook'

# The layout scale is the scene's only per-job parameter and must come from the strictly
# allowlisted run-label suffix, so both sides of the host's 0.45 kernel threshold are reachable
# without a code change while a typo fails closed. The widened T039 event window is fixed here too.
grep -Fq -- '-Dturboism.validation.atlasImageShadow.layoutScalePercent=40' "$test_root/dry-run.log"
grep -Fq -- '-Dturboism.validation.t039.maxEvents=64' "$test_root/dry-run.log"
run_wrapper_label offline-scale60 --dry-run > "$test_root/dry-run-scale60.log"
grep -Fq -- '-Dturboism.validation.atlasImageShadow.layoutScalePercent=60' "$test_root/dry-run-scale60.log"
if run_wrapper_label offline-scale55 --dry-run > "$test_root/dry-run-scale55.log" 2>&1; then
  fail 'wrapper accepted an unknown layout scale run-label suffix'
fi
grep -q 'unknown layout scale' "$test_root/dry-run-scale55.log"
! grep -Fq -- 'layoutScalePercent' "$test_root/dry-run-scale55.log"
if run_wrapper_label offline-scale40-scale60 --dry-run > "$test_root/dry-run-scale-twice.log" 2>&1; then
  fail 'wrapper accepted a run label selecting the layout scale twice'
fi
grep -q 'layout scale more than once' "$test_root/dry-run-scale-twice.log"
if run_wrapper_label offline-scalefoo --dry-run > "$test_root/dry-run-scale-malformed.log" 2>&1; then
  fail 'wrapper accepted a malformed layout scale suffix'
fi
grep -q 'malformed layout scale' "$test_root/dry-run-scale-malformed.log"

# JDK Flight Recording is opt-in through the same run-label mechanism, so a default run keeps
# its exact capture artifacts and only an explicit -jfr label adds a bounded recording.
! grep -Fq -- 'StartFlightRecording' "$test_root/dry-run.log"
! grep -Fq -- 'StartFlightRecording' "$test_root/dry-run-scale60.log"
run_wrapper_label offline-scale40-jfr --dry-run > "$test_root/dry-run-jfr.log"
grep -Fq -- '-XX:StartFlightRecording=filename=Z:' "$test_root/dry-run-jfr.log"
grep -Fq -- 'atlas-profiling.jfr' "$test_root/dry-run-jfr.log"
grep -Fq -- 'dumponexit=true' "$test_root/dry-run-jfr.log"
grep -Fq -- '-Dturboism.validation.atlasImageShadow.layoutScalePercent=40' "$test_root/dry-run-jfr.log"

# The production-scale profile is selected by the same run-label mechanism: it swaps the fixture
# pair and raises the wall-time budgets, and it must fail closed when its own fixture key is absent.
heavy_fixture="$test_root/heavy.cmo3"
printf 'synthetic-heavy-fixture\n' > "$heavy_fixture"
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture" \
    "$wrapper" 5303 offline-heavy --bundle-manifest "$manifest" --dry-run \
    > "$test_root/dry-run-heavy-badhash.log" 2>&1; then
  fail 'wrapper accepted a heavy fixture with the wrong hash'
fi
grep -q 'allowlisted heavy.cmo3 hash mismatch' "$test_root/dry-run-heavy-badhash.log"
# The heavy fixture key may legitimately live in the developer's .env; shadow it
# explicitly so this case really exercises the absent-key path.
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303= \
    "$wrapper" 5303 offline-heavy --bundle-manifest "$manifest" --dry-run \
    > "$test_root/dry-run-heavy-missing.log" 2>&1; then
  fail 'wrapper accepted the heavy profile without its fixture key'
fi
grep -q 'fixture requires TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303' \
  "$test_root/dry-run-heavy-missing.log"

# The preserved-layout profile must switch the driver away from the auto-layout dialog.
run_wrapper_label offline-nolayout --dry-run > "$test_root/dry-run-nolayout.log"
grep -Fq -- '-Dturboism.validation.atlasImageShadow.layoutMode=preserve' "$test_root/dry-run-nolayout.log"
grep -Fq -- '-Dturboism.validation.atlasImageShadow.layoutMode=auto-scale' "$test_root/dry-run.log"

# `-meshoff` stages the pinned preference-off home config and is only meaningful next to the
# observation-only mesh agent; without `-meshprobe` nothing can witness the passthrough, and a
# `-meshfix` agent would mask whether the production transformer ran.
mesh_agent_stub="$test_root/mesh-hash-agent.jar"
printf 'synthetic mesh agent\n' > "$mesh_agent_stub"
if run_wrapper_label offline-meshoff --dry-run > "$test_root/dry-run-meshoff-alone.log" 2>&1; then
  fail 'wrapper accepted -meshoff without -meshprobe'
fi
grep -q -- '-meshoff requires -meshprobe' "$test_root/dry-run-meshoff-alone.log"
if env "${runner_env[@]}" TURBOISM_MESH_HASH_AGENT="$mesh_agent_stub" \
    "$wrapper" 5303 offline-meshfix-meshoff --bundle-manifest "$manifest" --dry-run \
    > "$test_root/dry-run-meshoff-fix.log" 2>&1; then
  fail 'wrapper accepted -meshoff with -meshfix'
fi
grep -q -- '-meshoff requires -meshprobe' "$test_root/dry-run-meshoff-fix.log"
prepare_meshoff="$test_root/prepare-meshoff"
env "${runner_env[@]}" TURBOISM_MESH_HASH_AGENT="$mesh_agent_stub" \
  "$wrapper" 5303 offline-meshprobe-meshoff --bundle-manifest "$manifest" \
  --prepare-dir "$prepare_meshoff" > "$test_root/prepare-meshoff.log" 2>&1 \
  || fail 'meshprobe-meshoff prepare failed'
grep -q 'meshPreference=off' "$test_root/prepare-meshoff.log"
python3 - "$prepare_meshoff/runner-request.json" <<'PY'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
index = argv.index("--home-config")
assert argv[index + 1].endswith("home-config-meshoff.json"), argv[index + 1]
assert "--aux-agent" in argv
modes = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "meshHash.mode" in argv[i + 1]]
assert modes == ["-Dturboism.validation.meshHash.mode=probe"], modes
print("T040_MESH_OFF_PLAN PASS homeConfigPinned=true probeMode=true")
PY
prepare_meshon="$test_root/prepare-meshon"
env "${runner_env[@]}" TURBOISM_MESH_HASH_AGENT="$mesh_agent_stub" \
  "$wrapper" 5303 offline-meshprobe --bundle-manifest "$manifest" \
  --prepare-dir "$prepare_meshon" > "$test_root/prepare-meshon.log" 2>&1 \
  || fail 'meshprobe prepare failed'
python3 - "$prepare_meshon/runner-request.json" <<'PY'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
assert "--home-config" not in argv, "default meshprobe run must not stage a home config"
print("T040_MESH_ON_PLAN PASS noHomeConfig=true")
PY

# `-reuseoff` stages the tile-bbox-on / cache-reuse-off home config so runs can
# isolate the cache-reuse guard's contribution; it must not combine with -atlasoff.
timing_agent_stub="$test_root/atlas-timing-agent.jar"
printf 'synthetic timing agent\n' > "$timing_agent_stub"
prepare_reuseoff="$test_root/prepare-reuseoff"
env "${runner_env[@]}" TURBOISM_ATLAS_TIMING_AGENT="$timing_agent_stub" \
  "$wrapper" 5303 offline-reuseoff-atlastiming --bundle-manifest "$manifest" \
  --prepare-dir "$prepare_reuseoff" > "$test_root/prepare-reuseoff.log" 2>&1 \
  || fail 'reuseoff prepare failed'
grep -q 'atlasPreference=tile-bbox-only' "$test_root/prepare-reuseoff.log" \
  || fail 'reuseoff run must self-describe as tile-bbox-only'
python3 - "$prepare_reuseoff/runner-request.json" <<'PY'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
index = argv.index("--home-config")
assert argv[index + 1].endswith("home-config-atlasreuseoff.json"), argv[index + 1]
evidence = [argv[i + 1] for i, flag in enumerate(argv)
            if flag == "--jvm-option" and "atlasCacheReuse.evidenceFile" in argv[i + 1]]
assert len(evidence) == 1, evidence
print("T040_REUSE_OFF_PLAN PASS homeConfigPinned=true evidenceFileSet=true")
PY
if env "${runner_env[@]}" \
  "$wrapper" 5303 offline-atlasoff-reuseoff --bundle-manifest "$manifest" \
  --dry-run > "$test_root/dry-run-atlasoff-reuseoff.log" 2>&1; then
  fail 'wrapper accepted -atlasoff combined with -reuseoff'
fi

# The driver must accept exactly the launch property set this Runner plans.
cat > "$test_root/T040LaunchPropertyProbe.java" <<'JAVA'
package dev.turboism.validation.atlasimage.shadow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Offline launch-plan gate; loads a planned JVM property set and runs the real driver config. */
public final class T040LaunchPropertyProbe {
    private T040LaunchPropertyProbe() {}
    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalStateException("usage: T040LaunchPropertyProbe <launch.properties>");
        }
        final List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        for (final String line : lines) {
            if (line.isEmpty()) continue;
            final int separator = line.indexOf('=');
            if (separator <= 0) throw new IllegalStateException("malformed launch property: " + line);
            System.setProperty(line.substring(0, separator), line.substring(separator + 1));
        }
        try {
            T040ShadowSceneDriverAgent.DriverConfig.fromSystemProperties();
        } catch (Exception blocked) {
            System.out.println("T040_LAUNCH_PROPERTY_CONTRACT BLOCKED "
                + blocked.getClass().getSimpleName() + ": " + blocked.getMessage());
            System.exit(1);
        }
        System.out.println("T040_LAUNCH_PROPERTY_CONTRACT PASS keys=" + lines.size());
    }
}
JAVA
mkdir -p "$test_root/launch-classes"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$driver" -d "$test_root/launch-classes" "$test_root/T040LaunchPropertyProbe.java"
run_launch_probe() {
  java -cp "$test_root/launch-classes:$driver" \
    dev.turboism.validation.atlasimage.shadow.T040LaunchPropertyProbe "$1"
}

home_dir="$(sed -n 's/^homeDir=//p' "$test_root/dry-run.log")"
planned_fixture="$(sed -n 's/^fixturePath=//p' "$test_root/dry-run.log")"
planned_version="$(sed -n 's/^version=//p' "$test_root/dry-run.log")"
planned_run_id="$(sed -n 's/^runId=//p' "$test_root/dry-run.log")"
[[ -n "$home_dir" && -n "$planned_fixture" && -n "$planned_version" && -n "$planned_run_id" ]] \
  || fail 'dry-run plan is missing home/fixture/version/runId'
mkdir -p -- "$home_dir" "$(dirname -- "$planned_fixture")"
cp -- "$fixture" "$planned_fixture"
launch_properties="$test_root/launch.properties"
{
  sed -n 's/^jvmOption\.[0-9]*=-D\([^=]*\)=\(.*\)$/\1=\2/p' "$test_root/dry-run.log"
  printf 'turboism.validation.runId=%s\nturboism.validation.hostVersion=%s\n' \
    "$planned_run_id" "$planned_version"
} \
  | grep -v -e '^turboism\.validation\.atlasImageShadow\.home=' \
           -e '^turboism\.validation\.atlasImageShadow\.fixture=' > "$launch_properties"
printf 'turboism.home=%s\nturboism.validation.atlasImageShadow.home=%s\nturboism.validation.atlasImageShadow.fixture=%s\n' \
  "$home_dir" "$home_dir" "$planned_fixture" >> "$launch_properties"
! grep -q '^turboism\.validation\.t039\.codeSource=' "$launch_properties" \
  || fail 'launch plan pins a codeSource instead of the target-pd binding'

run_launch_probe "$launch_properties" > "$test_root/launch.log" 2>&1 \
  || fail 'driver rejected the planned launch properties'
grep -q '^T040_LAUNCH_PROPERTY_CONTRACT PASS keys=' "$test_root/launch.log" \
  || fail 'launch property contract did not report PASS'
for required in t039.sourceBinding t039.trustedSourcePaths; do
  missing_plan="$test_root/launch-missing-$required.properties"
  grep -v "^turboism\.validation\.$required=" "$launch_properties" > "$missing_plan"
  if run_launch_probe "$missing_plan" > "$test_root/launch-missing.log" 2>&1; then
    fail "driver accepted a launch plan without $required"
  fi
  grep -q "^T040_LAUNCH_PROPERTY_CONTRACT BLOCKED IllegalArgumentException: missing turboism.validation.$required$" \
    "$test_root/launch-missing.log" \
    || fail "unexpected rejection for a launch plan without $required"
done

bad_scale_plan="$test_root/launch-bad-scale.properties"
grep -q '^turboism\.validation\.atlasImageShadow\.layoutScalePercent=40$' "$launch_properties" \
  || fail 'planned launch properties are missing the default layout scale'
{
  grep -v '^turboism\.validation\.atlasImageShadow\.layoutScalePercent=' "$launch_properties"
  printf 'turboism.validation.atlasImageShadow.layoutScalePercent=55\n'
} > "$bad_scale_plan"
if run_launch_probe "$bad_scale_plan" > "$test_root/launch-bad-scale.log" 2>&1; then
  fail 'driver accepted an out-of-allowlist layout scale'
fi
grep -q '^T040_LAUNCH_PROPERTY_CONTRACT BLOCKED IllegalArgumentException: layoutScalePercent must be 40' \
  "$test_root/launch-bad-scale.log" \
  || fail 'unexpected rejection for an out-of-allowlist layout scale'
printf 'T040_LAUNCH_PROPERTY_CONTRACT PASS planned-keys-accepted=true uncoupled-keys-rejected=true\n'

unknown="$test_root/unknown.manifest"
cp -- "$manifest" "$unknown"
printf 'unknownKey=must-fail\n' >> "$unknown"
if env "${runner_env[@]}" "$wrapper" 5303 offline --bundle-manifest "$unknown" --dry-run \
    > "$test_root/unknown.log" 2>&1; then
  fail 'wrapper accepted an unknown manifest key'
fi
grep -q 'unknown manifest key: unknownKey' "$test_root/unknown.log"

# PYTHONOPTIMIZE=1 must not erase explicit PreparedStore rejection.
if ! PYTHONOPTIMIZE=1 PYTHONPATH="$root/scripts/preview" python3 - <<'PY'
import tempfile
from pathlib import Path
import host_validation_queue as queue

with tempfile.TemporaryDirectory(prefix="t040-optimized-negative-") as temporary:
    store = queue.Store(Path(temporary) / "queue")
    request = {"schemaVersion": queue.SCHEMA + 1, "environment": {}, "argv": []}
    try:
        queue.PreparedStore(store).capture(request, Path(temporary) / "missing-source",
                                           "atlas-image-shadow:5303")
    except queue.QueueError:
        print("T040_PYTHONOPTIMIZE_NEGATIVE PASS invalidRequestRejected=true")
    else:
        raise SystemExit("invalid PreparedStore request was accepted")
PY
then
  fail 'PYTHONOPTIMIZE=1 negative control unexpectedly accepted invalid request'
fi

# Full PreparedStore capture -> remove only synthetic source/bundle -> replay snapshot.
PYTHONPATH="$root/scripts/preview" python3 - "$root" <<'PY'
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
import host_validation_queue as queue

def require(condition, message):
    if not condition:
        raise SystemExit(message)

root = Path(sys.argv[1])
required = (
    "run-cubism-host-validation.sh", "host-validation-env.sh", "host-validation-transport.sh",
    "archive-cubism-host-evidence.sh", "host_validation.py", "host_validation_queue.py",
    "host_validation_containment.py", "host_validation_evidence.py",
)
with tempfile.TemporaryDirectory(prefix="t040-prepared-replay-") as temporary:
    base = Path(temporary)
    source = base / "synthetic-source"
    preview = source / "scripts" / "preview"
    preview.mkdir(parents=True)
    for name in required:
        shutil.copy2(root / "scripts" / "preview" / name, preview / name)
    subprocess.run(["git", "init", "-q"], cwd=source, check=True)
    subprocess.run(["git", "config", "user.email", "offline@example.invalid"], cwd=source, check=True)
    subprocess.run(["git", "config", "user.name", "offline"], cwd=source, check=True)
    subprocess.run(["git", "add", "."], cwd=source, check=True)
    subprocess.run(["git", "commit", "-qm", "synthetic source"], cwd=source, check=True)
    bundle = base / "bundle"
    bundle.mkdir()
    (bundle / "marker.txt").write_bytes(b"synthetic bundle")
    agent = base / "synthetic-agent.jar"
    agent.write_bytes(b"synthetic agent")
    fixture = base / "synthetic-fixture.cmo3"
    fixture.write_bytes(b"synthetic fixture")
    request = {
        "schemaVersion": queue.SCHEMA,
        "environment": {},
        "argv": [
            "--name", "atlas-image-shadow", "--version", "5303",
            "--bundle-root", str(bundle), "--agent", str(agent),
            "--fixture-local", str(fixture), "--fixture-sha256", "0" * 64,
            "--fixture-name", "synthetic-fixture.cmo3",
            "--result-file", "state/atlas-image-shadow/result.txt",
            "--result-pass-line", "collectionStatus=COMPLETE",
            "--result-fail-line", "collectionStatus=FAILED",
            "--jvm-option", "-Dsynthetic.home={HOME}",
            "--jvm-option", "-Dturboism.validation.t039.sourceBinding=target-pd",
            "--jvm-option", r"-Dturboism.validation.t039.trustedSourcePaths=C:\Program Files\Live2D Cubism 5.3.03\app\lib\Live2D_Cubism.jar",
        ],
    }
    store = queue.Store(base / "queue")
    prepared = queue.PreparedStore(store).capture(request, source, "atlas-image-shadow:5303")
    digest = prepared["digest"]
    prepared_root = store.root / "prepared" / digest
    shutil.rmtree(source)
    shutil.rmtree(bundle)
    agent.unlink(missing_ok=True)
    fixture.unlink(missing_ok=True)
    loaded = queue.PreparedStore(store).load(digest)
    command = queue.PreparedStore(store).command(digest, base / "evidence")
    require("@INPUT@" not in command, "prepared command retained template input")
    require(str(source) not in command, "prepared command retained removed source")
    require("-Dturboism.validation.t039.sourceBinding=target-pd" in command,
            "prepared source binding option missing")
    require(r"-Dturboism.validation.t039.trustedSourcePaths=C:\Program Files\Live2D Cubism 5.3.03\app\lib\Live2D_Cubism.jar" in command,
            "prepared trusted source path option missing")
    require(len(command) > 1 and Path(command[1]).is_file(), "prepared runner missing")
    def after(flag):
        require(flag in command, f"prepared command missing {flag}")
        index = command.index(flag)
        require(index + 1 < len(command), f"prepared value missing after {flag}")
        return Path(command[index + 1])
    require((after("--bundle-root") / "marker.txt").read_bytes() == b"synthetic bundle",
            "prepared bundle replay mismatch")
    require(after("--agent").read_bytes() == b"synthetic agent", "prepared agent replay mismatch")
    require(after("--fixture-local").read_bytes() == b"synthetic fixture", "prepared fixture replay mismatch")
    require(prepared_root.is_dir(), "prepared snapshot directory missing")
    require(bool(loaded["sourceInputs"]), "prepared source inventory missing")
    print(f"T040_PREPARED_STORE_REPLAY PASS digest={digest} hostLaunched=false")
PY

printf 'ATLAS_IMAGE_SHADOW_HOST_VALIDATION_OFFLINE_TEST PASS hostLaunched=false\n'
