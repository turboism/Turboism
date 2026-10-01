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
! grep -Eq -- '--(remote-pre-launch|remote-post-launch|remote-pre-cleanup|client-script|failure-marker)' "$wrapper"
# T029-READY: readiness is narrowed to exactly one fixed marker — the deferred GL gate's
# own literal. The gate samples the runtime log once when no marker is declared; any other
# marker would be an unreviewed wait condition.
[[ "$(grep -cF -- "--ready-marker 'TURBOISM_DEFERRED_GL_ERROR_CHECK deferred=ACTIVE'" "$wrapper")" -eq 1 ]] \
  || fail 'wrapper must pin the deferred-check ready marker exactly once'
[[ "$(grep -c -- '--ready-marker' "$wrapper")" -eq 1 ]] \
  || fail 'wrapper must not gain a second ready marker'

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

# T029-READY: the deferred GL gate only waits when a ready marker is declared; pin the
# single fixed marker through the prepared argv and prove no GL protection was disabled
# to get it. The marker is in the shared unconditional argv assembly, so both the 5303
# and 5203 profiles carry it; a 5203 prepare additionally needs the canonical opacity52
# fixture (hash-pinned), which is not synthesizable offline.
prepare_ready="$test_root/prepare-ready"
run_wrapper_label offline-nolayout --prepare-dir "$prepare_ready" \
  > "$test_root/prepare-ready.log" 2>&1 || fail 'ready-marker prepare failed'
python3 - "$prepare_ready/runner-request.json" <<'PY'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
markers = [argv[i + 1] for i, flag in enumerate(argv) if flag == "--ready-marker"]
assert markers == ["TURBOISM_DEFERRED_GL_ERROR_CHECK deferred=ACTIVE"], markers
for forbidden in ("--failure-marker", "--remote-pre-launch", "--remote-post-launch",
                  "--remote-pre-cleanup", "--client-script"):
    assert forbidden not in argv, forbidden
gl_off = [a for a in argv if ("mesaGlThread=false" in a or "mesa_glthread=false" in a
                              or "glGetErrorElision" in a)]
assert not gl_off, gl_off
print("T040_READY_MARKER_PLAN PASS marker=deferred-active onceOnly=true noGlDisable=true")
PY

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


# T029-IDENTITY: --tri-identity-probe is a strictly gated optional aux agent. The default
# argv must stay byte-identical to the reviewed profile, wrong sha/file/version/label reject
# closed, and only the exact frozen label with the pinned jar admits the probe between the
# production agent and the scene driver with exactly seven fixed properties.
tri_probe="${TURBOISM_TRI_IDENTITY_PROBE_JAR:-$root/build/tri-probe.OscfZj/tri-probe-agent.jar}"
[[ -f "$tri_probe" && ! -L "$tri_probe" ]] \
  || fail 'reviewed tri-identity probe jar required for offline wiring test (TURBOISM_TRI_IDENTITY_PROBE_JAR)'
[[ "$(sha256sum "$tri_probe" | cut -d' ' -f1)" == \
    9c4a4ccc37c630c8134428334cd4131faccf8f6250e6f45035c659b3bb10ab71 ]] \
  || fail 'reviewed tri-identity probe jar hash drifted'
# default dry-run keeps the reviewed argv: no probe aux, no triIdentity option.
! grep -Fq -- 'triIdentity' "$test_root/dry-run.log" || fail 'default run leaked triIdentity options'
grep -q '^auxAgentCount=2$' "$test_root/dry-run.log" || fail 'default aux agent count changed'
# The exact label is a -heavy profile: file/sha gates sit after fixture resolution, so the
# heavy fixture key is required for those rejects to fire.
heavy_fixture_real="${TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303:-}"
[[ -n "$heavy_fixture_real" && -f "$heavy_fixture_real" ]] \
  || fail 'set TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303 for the identity wiring test'
[[ "$(sha256sum "$heavy_fixture_real" | cut -d' ' -f1)" == \
    029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c ]] \
  || fail 'heavy fixture hash mismatch for identity wiring test'
manifest_heavy="$test_root/bundle-heavy.manifest"
sed -e "s|^fixture=.*|fixture=$heavy_fixture_real|" \
    -e 's|^fixtureName=.*|fixtureName=heavy.cmo3|' \
    -e 's|^fixtureSha256=.*|fixtureSha256=029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c|' \
    "$manifest" > "$manifest_heavy"
# wrong sha
bad_probe="$test_root/tri-identity-probe.jar"
printf 'not-the-pinned-probe\n' > "$bad_probe"
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-identity-01-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-identity-probe "$bad_probe" --dry-run \
    > "$test_root/probe-badsha.log" 2>&1; then
  fail 'wrapper accepted a tri-identity probe with the wrong sha'
fi
grep -q 'tri-identity probe SHA-256 mismatch' "$test_root/probe-badsha.log"
# missing file
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-identity-01-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-identity-probe "$test_root/no-such/tri-identity-probe.jar" --dry-run \
    > "$test_root/probe-missing.log" 2>&1; then
  fail 'wrapper accepted a missing tri-identity probe path'
fi
grep -q 'not a regular non-symlink file' "$test_root/probe-missing.log"
# symlink
ln -sf "$tri_probe" "$test_root/tri-identity-probe.jar"
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-identity-01-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-identity-probe "$test_root/tri-identity-probe.jar" --dry-run \
    > "$test_root/probe-link.log" 2>&1; then
  fail 'wrapper accepted a symlink tri-identity probe'
fi
grep -q 'not a regular non-symlink file' "$test_root/probe-link.log"
# wrong version
if env "${runner_env[@]}" "$wrapper" 5203 t029-identity-01-heavy-nolayout \
    --bundle-manifest "$manifest" --tri-identity-probe "$tri_probe" --dry-run \
    > "$test_root/probe-version.log" 2>&1; then
  fail 'wrapper accepted --tri-identity-probe under 5203'
fi
grep -q 'tri-identity-probe is 5303-only' "$test_root/probe-version.log"
# wrong label
if env "${runner_env[@]}" "$wrapper" 5303 offline-heavy \
    --bundle-manifest "$manifest" --tri-identity-probe "$tri_probe" --dry-run \
    > "$test_root/probe-label.log" 2>&1; then
  fail 'wrapper accepted --tri-identity-probe with the wrong label'
fi
grep -q 'exact label t029-identity-01-heavy-nolayout' "$test_root/probe-label.log"
prepare_id="$test_root/prepare-tri-identity"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  "$wrapper" 5303 t029-identity-01-heavy-nolayout \
  --bundle-manifest "$manifest_heavy" --tri-identity-probe "$tri_probe" \
  --prepare-dir "$prepare_id" > "$test_root/prepare-tri-identity.log" 2>&1 \
  || fail 'tri-identity prepare failed'
python3 - "$prepare_id/runner-request.json" <<'PYEOF'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
aux = [argv[i + 1].split(":")[-1] for i, flag in enumerate(argv) if flag == "--aux-agent"]
assert aux == ["t039-shadow-agent.jar", "tri-identity-probe.jar",
               "atlas-image-shadow-scene-driver.jar"], aux
assert argv[argv.index("--aux-agent-before-main") + 1] == "t039-shadow-agent.jar"
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "triIdentity." in argv[i + 1]]
expected = [
    "-Dturboism.validation.triIdentity.enabled=true",
    "-Dturboism.validation.triIdentity.phase=t029-identity-01",
    "-Dturboism.validation.triIdentity.runId=t029-id-01",
    "-Dturboism.validation.triIdentity.outputDir={HOME}/tri-identity",
    "-Dturboism.validation.triIdentity.expectClassSha256=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29",
    "-Dturboism.validation.triIdentity.expectLoader=jdk.internal.loader.ClassLoaders$AppClassLoader",
    "-Dturboism.validation.triIdentity.expectCodeSource=file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3.03/app/lib/Live2D_Cubism.jar",
]
assert props == expected, props
assert not any("StartFlightRecording" in a for a in argv)
assert not any("atlasTiming" in a for a in argv)
assert not any("failInit" in a or "throwOnRecord" in a for a in argv)
print("T029_IDENTITY_PLAN PASS auxOrder=t039-probe-driver props=7 readyMarkerKept=true")
PYEOF

# T029-TRIAB: --tri-weave-ab <dump-only|dump+weave> is a strictly gated optional aux agent.
# The default argv must stay byte-identical to the reviewed profile, mode/label mismatches
# and file rejects fail closed, and only a t029-triab-<base|woven><N>-heavy-nolayout[-jfr]
# label admits the agent between the production agent and the scene driver.
! grep -Fq -- 'triWeave' "$test_root/dry-run.log" || fail 'default run leaked triWeave options'
grep -q '^auxAgentCount=2$' "$test_root/dry-run.log" || fail 'default aux agent count changed'
tri_weave_agent_stub="$test_root/tri-weave-agent.jar"
printf 'synthetic tri-weave agent\n' > "$tri_weave_agent_stub"
# label/mode mismatch: a base leg must not run dump+weave
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-triab-base1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-weave-ab 'dump+weave' --dry-run \
    > "$test_root/triab-mismatch.log" 2>&1; then
  fail 'wrapper accepted a base leg with dump+weave'
fi
grep -q 'inconsistent with leg label' "$test_root/triab-mismatch.log"
# unknown mode token
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-triab-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-weave-ab weave --dry-run \
    > "$test_root/triab-badmode.log" 2>&1; then
  fail 'wrapper accepted an unknown --tri-weave-ab mode'
fi
grep -q 'must be dump-only or dump+weave' "$test_root/triab-badmode.log"
# missing env jar
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-triab-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-weave-ab 'dump+weave' --dry-run \
    > "$test_root/triab-noenv.log" 2>&1; then
  fail 'wrapper accepted --tri-weave-ab without TURBOISM_TRI_WEAVE_AGENT'
fi
grep -q 'requires --tri-weave-agent or TURBOISM_TRI_WEAVE_AGENT' "$test_root/triab-noenv.log"
# argument-form agent jar (queue-stable: the runner replays frozen args in a worker
# env without TURBOISM_TRI_WEAVE_AGENT, so the argument form must work standalone)
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-triab-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-weave-ab 'dump+weave' \
    --tri-weave-agent "$tri_weave_agent_stub" --dry-run \
    > "$test_root/triab-argform.log" 2>&1; then
  :
else
  fail 'wrapper rejected the --tri-weave-agent argument form'
fi
grep -q '^auxAgentCount=3$' "$test_root/triab-argform.log"
# supplying both the argument and the env var is ambiguous
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-triab-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-weave-ab 'dump+weave' \
    --tri-weave-agent "$tri_weave_agent_stub" --dry-run \
    > "$test_root/triab-both.log" 2>&1; then
  fail 'wrapper accepted both the argument and the env tri-weave agent'
fi
grep -q 'supplied twice (argument' "$test_root/triab-both.log"
# wrong basename
bad_basename="$test_root/not-tri-weave.jar"
printf 'synthetic\n' > "$bad_basename"
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$bad_basename" \
    "$wrapper" 5303 t029-triab-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-weave-ab 'dump+weave' --dry-run \
    > "$test_root/triab-basename.log" 2>&1; then
  fail 'wrapper accepted a tri-weave agent with the wrong basename'
fi
grep -q 'basename must be tri-weave-agent.jar' "$test_root/triab-basename.log"
# symlink
ln -sf "$tri_weave_agent_stub" "$test_root/tri-weave-link.jar"
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$test_root/tri-weave-link.jar" \
    "$wrapper" 5303 t029-triab-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-weave-ab 'dump+weave' --dry-run \
    > "$test_root/triab-link.log" 2>&1; then
  fail 'wrapper accepted a symlink tri-weave agent'
fi
grep -q 'not a regular non-symlink file' "$test_root/triab-link.log"
# wrong version
if env "${runner_env[@]}" TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5203 t029-triab-woven1-heavy-nolayout \
    --bundle-manifest "$manifest" --tri-weave-ab 'dump+weave' --dry-run \
    > "$test_root/triab-version.log" 2>&1; then
  fail 'wrapper accepted --tri-weave-ab under 5203'
fi
grep -q 'tri-weave-ab is 5303-only' "$test_root/triab-version.log"
# triab label without the flag must not silently run uninstrumented
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-triab-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --dry-run \
    > "$test_root/triab-noflag.log" 2>&1; then
  fail 'wrapper accepted a t029-triab label without --tri-weave-ab'
fi
grep -q 'requires --tri-weave-ab' "$test_root/triab-noflag.log"
# flag with a non-triab label
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 offline-heavy \
    --bundle-manifest "$manifest_heavy" --tri-weave-ab 'dump-only' --dry-run \
    > "$test_root/triab-badlabel.log" 2>&1; then
  fail 'wrapper accepted --tri-weave-ab with a non-triab label'
fi
grep -q 'requires a label t029-triab-' "$test_root/triab-badlabel.log"
# woven leg with JFR: aux order + nine fixed props + the reviewed recording settings
prepare_wv="$test_root/prepare-tri-weave-woven"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5303 t029-triab-woven1-heavy-nolayout-jfr \
  --bundle-manifest "$manifest_heavy" --tri-weave-ab 'dump+weave' \
  --prepare-dir "$prepare_wv" > "$test_root/prepare-tri-weave-woven.log" 2>&1 \
  || fail 'tri-weave woven-leg prepare failed'
grep -q 'triWeaveMode=dump+weave' "$test_root/prepare-tri-weave-woven.log"
grep -q 'triWeaveLeg=woven1' "$test_root/prepare-tri-weave-woven.log"
python3 - "$prepare_wv/runner-request.json" <<'PYEOF'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
aux = [argv[i + 1].split(":")[-1] for i, flag in enumerate(argv) if flag == "--aux-agent"]
assert aux == ["t039-shadow-agent.jar", "tri-weave-agent.jar",
               "atlas-image-shadow-scene-driver.jar"], aux
assert argv[argv.index("--aux-agent-before-main") + 1] == "t039-shadow-agent.jar"
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "triWeave." in argv[i + 1]]
expected = [
    "-Dturboism.validation.triWeave.enabled=true",
    "-Dturboism.validation.triWeave.mode=dump+weave",
    "-Dturboism.validation.triWeave.phase=t029-triab",
    "-Dturboism.validation.triWeave.runId=triab-woven1",
    "-Dturboism.validation.triWeave.outputDir={HOME}/tri-weave",
    "-Dturboism.validation.triWeave.expectClassSha256=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29",
    "-Dturboism.validation.triWeave.expectLoader=jdk.internal.loader.ClassLoaders$AppClassLoader",
    "-Dturboism.validation.triWeave.expectCodeSource=file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3.03/app/lib/Live2D_Cubism.jar",
    "-Dturboism.validation.triWeave.captureN=4",
]
assert props == expected, props
jfr = [a for a in argv if "StartFlightRecording" in a]
assert len(jfr) == 1 and "settings=profile" in jfr[0] and "stackdepth=256" in jfr[0], jfr
assert not any("triIdentity." in a for a in argv)
assert not any("triWeave.profile" in a or "failNewBox" in a for a in argv)
print("T029_TRIAB_PLAN PASS auxOrder=t039-triweave-driver props=9 jfrReviewed=true")
PYEOF
# base leg (no -jfr suffix): mode prop flips to dump-only, no recording added
prepare_bs="$test_root/prepare-tri-weave-base"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5303 t029-triab-base2-heavy-nolayout \
  --bundle-manifest "$manifest_heavy" --tri-weave-ab 'dump-only' \
  --prepare-dir "$prepare_bs" > "$test_root/prepare-tri-weave-base.log" 2>&1 \
  || fail 'tri-weave base-leg prepare failed'
python3 - "$prepare_bs/runner-request.json" <<'PYEOF'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "triWeave." in argv[i + 1]]
assert "-Dturboism.validation.triWeave.mode=dump-only" in props, props
assert "-Dturboism.validation.triWeave.runId=triab-base2" in props, props
assert len(props) == 9, props
assert not any("StartFlightRecording" in a for a in argv)
print("T029_TRIAB_BASE_PLAN PASS mode=dump-only noJfr=true")
PYEOF

# T029-DWEAVE: --tri-dweave <dm-dump-only|dm-dump+weave> shares the same physical agent
# jar and the same bidirectional label↔mode contract, in the dmWeave namespace.
! grep -Fq -- 'dmWeave' "$test_root/dry-run.log" || fail 'default run leaked dmWeave options'
# label/mode mismatch: a base leg must not run dm-dump+weave
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-dweave-base1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump+weave' --dry-run \
    > "$test_root/dweave-mismatch.log" 2>&1; then
  fail 'wrapper accepted a dweave base leg with dm-dump+weave'
fi
grep -q 'inconsistent with leg label' "$test_root/dweave-mismatch.log"
# unknown mode token (a triab mode must not leak into the dm namespace)
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-dweave-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-dweave 'dump+weave' --dry-run \
    > "$test_root/dweave-badmode.log" 2>&1; then
  fail 'wrapper accepted a non-dm --tri-dweave mode'
fi
grep -q 'must be dm-dump-only or dm-dump+weave' "$test_root/dweave-badmode.log"
# missing agent (neither argument nor env)
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-dweave-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump+weave' --dry-run \
    > "$test_root/dweave-noenv.log" 2>&1; then
  fail 'wrapper accepted --tri-dweave without an agent'
fi
grep -q 'requires --tri-weave-agent or TURBOISM_TRI_WEAVE_AGENT' "$test_root/dweave-noenv.log"
# argument-form agent jar
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-dweave-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump+weave' \
    --tri-weave-agent "$tri_weave_agent_stub" --dry-run \
    > "$test_root/dweave-argform.log" 2>&1; then
  :
else
  fail 'wrapper rejected the argument form under --tri-dweave'
fi
grep -q '^auxAgentCount=3$' "$test_root/dweave-argform.log"
grep -q '^dmWeaveMode=dm-dump+weave$' "$test_root/dweave-argform.log" \
  || fail 'dry-run report missing dmWeaveMode'
# both argument and env forms together are ambiguous
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-dweave-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump+weave' \
    --tri-weave-agent "$tri_weave_agent_stub" --dry-run \
    > "$test_root/dweave-both.log" 2>&1; then
  fail 'wrapper accepted both agent forms under --tri-dweave'
fi
grep -q 'supplied twice (argument' "$test_root/dweave-both.log"
# wrong basename
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$bad_basename" \
    "$wrapper" 5303 t029-dweave-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump+weave' --dry-run \
    > "$test_root/dweave-basename.log" 2>&1; then
  fail 'wrapper accepted a dweave agent with the wrong basename'
fi
grep -q 'basename must be tri-weave-agent.jar' "$test_root/dweave-basename.log"
# symlink
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$test_root/tri-weave-link.jar" \
    "$wrapper" 5303 t029-dweave-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump+weave' --dry-run \
    > "$test_root/dweave-link.log" 2>&1; then
  fail 'wrapper accepted a symlink agent under --tri-dweave'
fi
grep -q 'not a regular non-symlink file' "$test_root/dweave-link.log"
# wrong version
if env "${runner_env[@]}" TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5203 t029-dweave-woven1-heavy-nolayout \
    --bundle-manifest "$manifest" --tri-dweave 'dm-dump+weave' --dry-run \
    > "$test_root/dweave-version.log" 2>&1; then
  fail 'wrapper accepted --tri-dweave under 5203'
fi
grep -q 'tri-dweave is 5303-only' "$test_root/dweave-version.log"
# dweave label without the flag must not silently run uninstrumented
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-dweave-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --dry-run \
    > "$test_root/dweave-noflag.log" 2>&1; then
  fail 'wrapper accepted a t029-dweave label without --tri-dweave'
fi
grep -q 'requires --tri-dweave' "$test_root/dweave-noflag.log"
# flag with a non-dweave label
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 offline-heavy \
    --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump-only' --dry-run \
    > "$test_root/dweave-badlabel.log" 2>&1; then
  fail 'wrapper accepted --tri-dweave with a non-dweave label'
fi
grep -q 'requires a label t029-dweave-' "$test_root/dweave-badlabel.log"
# namespace confusion: a triab flag on a dweave label (or vice versa) fails closed
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-dweave-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-weave-ab 'dump+weave' --dry-run \
    > "$test_root/dweave-ns-triab.log" 2>&1; then
  fail 'wrapper accepted --tri-weave-ab on a dweave label'
fi
grep -q 'requires a label t029-triab-' "$test_root/dweave-ns-triab.log"
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-triab-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump+weave' --dry-run \
    > "$test_root/dweave-ns-dweave.log" 2>&1; then
  fail 'wrapper accepted --tri-dweave on a triab label'
fi
grep -q 'requires --tri-weave-ab' "$test_root/dweave-ns-dweave.log"
# env-form agent (same path the triab legs replay)
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-dweave-base1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump-only' --dry-run \
    > "$test_root/dweave-envform.log" 2>&1; then
  :
else
  fail 'wrapper rejected the env form under --tri-dweave'
fi
grep -q '^auxAgentCount=3$' "$test_root/dweave-envform.log"
# woven leg with JFR: aux order + eleven fixed dmWeave props + reviewed recording settings
prepare_dw="$test_root/prepare-tri-dweave-woven"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5303 t029-dweave-woven1-heavy-nolayout-jfr \
  --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump+weave' \
  --prepare-dir "$prepare_dw" > "$test_root/prepare-tri-dweave-woven.log" 2>&1 \
  || fail 'tri-dweave woven-leg prepare failed'
grep -q 'dmWeaveMode=dm-dump+weave' "$test_root/prepare-tri-dweave-woven.log"
grep -q 'dmWeaveLeg=woven1' "$test_root/prepare-tri-dweave-woven.log"
python3 - "$prepare_dw/runner-request.json" <<'PYEOF'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
aux = [argv[i + 1].split(":")[-1] for i, flag in enumerate(argv) if flag == "--aux-agent"]
assert aux == ["t039-shadow-agent.jar", "tri-weave-agent.jar",
               "atlas-image-shadow-scene-driver.jar"], aux
assert argv[argv.index("--aux-agent-before-main") + 1] == "t039-shadow-agent.jar"
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "dmWeave." in argv[i + 1]]
expected = [
    "-Dturboism.validation.dmWeave.enabled=true",
    "-Dturboism.validation.dmWeave.mode=dm-dump+weave",
    "-Dturboism.validation.dmWeave.profile=dm-official",
    "-Dturboism.validation.dmWeave.phase=t029-dweave",
    "-Dturboism.validation.dmWeave.runId=dweave-woven1",
    "-Dturboism.validation.dmWeave.outputDir={HOME}/dm-weave",
    "-Dturboism.validation.dmWeave.expectClassSha256=5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d",
    "-Dturboism.validation.dmWeave.expectCaptureClassSha256=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29",
    "-Dturboism.validation.dmWeave.expectLoader=jdk.internal.loader.ClassLoaders$AppClassLoader",
    "-Dturboism.validation.dmWeave.expectCodeSource=file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3.03/app/lib/Live2D_Cubism.jar",
    "-Dturboism.validation.dmWeave.captureN=4",
]
assert props == expected, props
jfr = [a for a in argv if "StartFlightRecording" in a]
assert len(jfr) == 1 and "settings=profile" in jfr[0] and "stackdepth=256" in jfr[0], jfr
assert not any("triWeave." in a for a in argv)
assert not any("triIdentity." in a for a in argv)
assert not any("failNewBox" in a or "shadow-selfcheck" in a for a in argv)
print("T029_DWEAVE_PLAN PASS auxOrder=t039-triweave-driver props=11 jfrReviewed=true")
PYEOF
# base leg (no -jfr suffix): mode flips to dm-dump-only, no recording added
prepare_db="$test_root/prepare-tri-dweave-base"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5303 t029-dweave-base2-heavy-nolayout \
  --bundle-manifest "$manifest_heavy" --tri-dweave 'dm-dump-only' \
  --prepare-dir "$prepare_db" > "$test_root/prepare-tri-dweave-base.log" 2>&1 \
  || fail 'tri-dweave base-leg prepare failed'
python3 - "$prepare_db/runner-request.json" <<'PYEOF'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "dmWeave." in argv[i + 1]]
assert "-Dturboism.validation.dmWeave.mode=dm-dump-only" in props, props
assert "-Dturboism.validation.dmWeave.runId=dweave-base2" in props, props
assert len(props) == 11, props
assert not any("StartFlightRecording" in a for a in argv)
assert not any("triWeave." in a for a in argv)
print("T029_DWEAVE_BASE_PLAN PASS mode=dm-dump-only noJfr=true")
PYEOF


# T029-TLINDEX: --tri-tlindex <tl-dump-only|tl-dump+weave> shares the same physical
# agent jar and the same bidirectional label<->mode contract, in the tlWeave
# namespace. The candidate rewrites four TriangleList methods on the SAME class
# the b()Lk; capture observes, so both class digests are the TriangleList sha.
! grep -Fq -- 'tlWeave' "$test_root/dry-run.log" || fail 'default run leaked tlWeave options'
# label/mode mismatch: a base leg must not run tl-dump+weave
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-tlindex-base1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump+weave' --dry-run \
    > "$test_root/tlindex-mismatch.log" 2>&1; then
  fail 'wrapper accepted a tlindex base leg with tl-dump+weave'
fi
grep -q 'inconsistent with leg label' "$test_root/tlindex-mismatch.log"
# unknown mode token
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-tlindex-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-tlindex 'weave' --dry-run \
    > "$test_root/tlindex-badmode.log" 2>&1; then
  fail 'wrapper accepted an unknown --tri-tlindex mode'
fi
grep -q 'must be tl-dump-only or tl-dump+weave' "$test_root/tlindex-badmode.log"
# missing env jar
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-tlindex-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump+weave' --dry-run \
    > "$test_root/tlindex-noenv.log" 2>&1; then
  fail 'wrapper accepted --tri-tlindex without TURBOISM_TRI_WEAVE_AGENT'
fi
grep -q 'requires --tri-weave-agent or TURBOISM_TRI_WEAVE_AGENT' "$test_root/tlindex-noenv.log"
# argument-form agent jar
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-tlindex-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump+weave' \
    --tri-weave-agent "$tri_weave_agent_stub" --dry-run \
    > "$test_root/tlindex-argform.log" 2>&1; then
  :
else
  fail 'wrapper rejected --tri-tlindex argument-form agent'
fi
grep -q '^auxAgentCount=3$' "$test_root/tlindex-argform.log"
grep -q '^tlWeaveMode=tl-dump+weave$' "$test_root/tlindex-argform.log" \
  || fail 'dry-run report missing tlWeaveMode'
# wrong version
if env "${runner_env[@]}" TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5203 t029-tlindex-woven1-heavy-nolayout \
    --bundle-manifest "$manifest" --tri-tlindex 'tl-dump+weave' --dry-run \
    > "$test_root/tlindex-version.log" 2>&1; then
  fail 'wrapper accepted --tri-tlindex under 5203'
fi
grep -q 'weave legs are 5303-only' "$test_root/tlindex-version.log"
# tlindex label without the flag must not silently run uninstrumented
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-tlindex-woven1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --dry-run \
    > "$test_root/tlindex-noflag.log" 2>&1; then
  fail 'wrapper accepted a t029-tlindex label without --tri-tlindex'
fi
grep -q 'requires --tri-tlindex' "$test_root/tlindex-noflag.log"
# flag with a non-tlindex label
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 offline-heavy \
    --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump-only' --dry-run \
    > "$test_root/tlindex-badlabel.log" 2>&1; then
  fail 'wrapper accepted --tri-tlindex with a non-tlindex label'
fi
grep -q 'requires a label t029-tlindex-' "$test_root/tlindex-badlabel.log"
# woven leg with JFR: aux order + eleven fixed tlWeave props
prepare_tl="$test_root/prepare-tl-weave-woven"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5303 t029-tlindex-woven1-heavy-nolayout-jfr \
  --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump+weave' \
  --prepare-dir "$prepare_tl" > "$test_root/prepare-tl-weave-woven.log" 2>&1 \
  || fail 'tlindex woven-leg prepare failed'
grep -q 'tlWeaveMode=tl-dump+weave' "$test_root/prepare-tl-weave-woven.log"
grep -q 'tlWeaveLeg=woven1' "$test_root/prepare-tl-weave-woven.log"
python3 - "$prepare_tl/runner-request.json" <<'PYTL'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
aux = [argv[i + 1].split(":")[-1] for i, flag in enumerate(argv) if flag == "--aux-agent"]
assert aux == ["t039-shadow-agent.jar", "tri-weave-agent.jar",
               "atlas-image-shadow-scene-driver.jar"], aux
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "tlWeave." in argv[i + 1]]
expected = [
    "-Dturboism.validation.tlWeave.enabled=true",
    "-Dturboism.validation.tlWeave.mode=tl-dump+weave",
    "-Dturboism.validation.tlWeave.profile=tl-official",
    "-Dturboism.validation.tlWeave.phase=t029-tlindex",
    "-Dturboism.validation.tlWeave.runId=tlindex-woven1",
    "-Dturboism.validation.tlWeave.outputDir={HOME}/tl-weave",
    "-Dturboism.validation.tlWeave.expectClassSha256=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29",
    "-Dturboism.validation.tlWeave.expectCaptureClassSha256=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29",
    "-Dturboism.validation.tlWeave.expectLoader=jdk.internal.loader.ClassLoaders$AppClassLoader",
    "-Dturboism.validation.tlWeave.expectCodeSource=file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3.03/app/lib/Live2D_Cubism.jar",
    "-Dturboism.validation.tlWeave.captureN=4",
]
assert props == expected, props
jfr = [a for a in argv if "StartFlightRecording" in a]
assert len(jfr) == 1 and "settings=profile" in jfr[0] and "stackdepth=256" in jfr[0], jfr
assert not any("triWeave." in a or "dmWeave." in a for a in argv)
print("T029_TLINDEX_PLAN PASS auxOrder=t039-triweave-driver props=11 jfrReviewed=true")
PYTL
# base leg (no -jfr suffix): mode prop flips to tl-dump-only, no recording added
prepare_tlb="$test_root/prepare-tl-weave-base"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5303 t029-tlindex-base2-heavy-nolayout \
  --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump-only' \
  --prepare-dir "$prepare_tlb" > "$test_root/prepare-tl-weave-base.log" 2>&1 \
  || fail 'tlindex base-leg prepare failed'
python3 - "$prepare_tlb/runner-request.json" <<'PYTL2'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "tlWeave." in argv[i + 1]]
assert "-Dturboism.validation.tlWeave.mode=tl-dump-only" in props, props
assert "-Dturboism.validation.tlWeave.runId=tlindex-base2" in props, props
assert len(props) == 11, props
assert not any("StartFlightRecording" in a for a in argv)
print("T029_TLINDEX_BASE_PLAN PASS mode=tl-dump-only noJfr=true")
PYTL2

# T029-TLPROD: production-rollout legs. The aux agent stays tl-dump-only on both
# legs; the production transformer differs. -off stages the pinned
# meshTriangulationEdgeIndex=false home config and expects pristine class bytes;
# -on relies on the default-on preference and expects the production-patched
# digest. The family admits 5303/5302/5203 — the weave legs stay 5303-only.
manifest_5302="$test_root/bundle-5302.manifest"
sed -e 's|^scene=.*|scene=atlas-image-shadow:5302|' \
    -e "s|^fixture=.*|fixture=$heavy_fixture_real|" \
    -e 's|^fixtureName=.*|fixtureName=heavy.cmo3|' \
    -e 's|^fixtureSha256=.*|fixtureSha256=029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c|' \
    -e 's|^officialJarSha256=.*|officialJarSha256=988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21|' \
    -e '/^t039/d' \
    "$manifest_heavy" > "$manifest_5302"
manifest_5203="$test_root/bundle-5203.manifest"
sed -e 's|^scene=.*|scene=atlas-image-shadow:5203|' \
    -e "s|^fixture=.*|fixture=$heavy_fixture_real|" \
    -e 's|^fixtureName=.*|fixtureName=heavy.cmo3|' \
    -e 's|^fixtureSha256=.*|fixtureSha256=029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c|' \
    -e 's|^officialJarSha256=.*|officialJarSha256=bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd|' \
    -e '/^t039/d' \
    "$manifest_heavy" > "$manifest_5203"
# a tlprod label without the flag must not run uninstrumented
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    "$wrapper" 5303 t029-tlprod-on1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --dry-run \
    > "$test_root/tlprod-noflag.log" 2>&1; then
  fail 'wrapper accepted a t029-tlprod label without --tri-tlindex'
fi
grep -q 'requires --tri-tlindex' "$test_root/tlprod-noflag.log"
# tlprod forbids the validation weave outright
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-tlprod-on1-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump+weave' --dry-run \
    > "$test_root/tlprod-badmode.log" 2>&1; then
  fail 'wrapper accepted tl-dump+weave on a tlprod leg'
fi
grep -q 'inconsistent with leg label' "$test_root/tlprod-badmode.log"
# 5302 remains closed to every other label family
if env "${runner_env[@]}" "$wrapper" 5302 offline \
    --bundle-manifest "$manifest_5302" --dry-run \
    > "$test_root/5302-label.log" 2>&1; then
  fail 'wrapper accepted a non-tlprod label under 5302'
fi
grep -q 'admits only t029-tlprod' "$test_root/5302-label.log"
# A production std leg cannot substitute an arbitrary 5203 environment hash/name.
# Circle100 is a real immutable test fixture, but it is not the pinned opacity52 pair.
if env "${runner_env[@]}" TURBOISM_HOST_VALIDATION_FIXTURE_5203="$fixture" \
    TURBOISM_HOST_VALIDATION_FIXTURE_5203_SHA256="$fixture_sha256" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5203 t029-tlprod-on1-std-nolayout \
    --bundle-manifest "$manifest_5203" --tri-tlindex tl-dump-only --dry-run \
    > "$test_root/tlprod-5203-unreviewed-std.log" 2>&1; then
  fail 'production std leg accepted an arbitrary 5203 environment fixture pair'
fi
grep -q 'allowlisted part-opacity-fixture-52-final.cmo3 hash mismatch' \
  "$test_root/tlprod-5203-unreviewed-std.log"
# the validation weave family still refuses non-5303 versions
if env "${runner_env[@]}" TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5302 t029-tlindex-base1-heavy-nolayout \
    --bundle-manifest "$manifest_5302" --tri-tlindex 'tl-dump-only' --dry-run \
    > "$test_root/tlindex-5302.log" 2>&1; then
  fail 'wrapper accepted a t029-tlindex leg under 5302'
fi
grep -q 'weave legs are 5303-only' "$test_root/tlindex-5302.log"
# 5303 on leg: patched digest + no staged config + t039 pairing kept
prepare_tlp_on="$test_root/prepare-tlprod-on"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5303 t029-tlprod-on1-heavy-nolayout-jfr \
  --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump-only' \
  --prepare-dir "$prepare_tlp_on" > "$test_root/prepare-tlprod-on.log" 2>&1 \
  || fail 'tlprod on-leg prepare failed'
grep -q 'tlindexPreference=default-on' "$test_root/prepare-tlprod-on.log"
grep -q 'tlWeaveExpectClassSha256=f3427cec0e5c0c7d93c8a2cf72351a82a39b635b15682afc291eb3a62dc888f5' \
  "$test_root/prepare-tlprod-on.log"
python3 - "$prepare_tlp_on/runner-request.json" <<'PYTP1'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
aux = [argv[i + 1].split(":")[-1] for i, flag in enumerate(argv) if flag == "--aux-agent"]
assert aux == ["t039-shadow-agent.jar", "tri-weave-agent.jar",
               "atlas-image-shadow-scene-driver.jar"], aux
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "tlWeave." in argv[i + 1]]
expected = [
    "-Dturboism.validation.tlWeave.enabled=true",
    "-Dturboism.validation.tlWeave.mode=tl-dump-only",
    "-Dturboism.validation.tlWeave.profile=tl-official",
    "-Dturboism.validation.tlWeave.phase=t029-tlindex",
    "-Dturboism.validation.tlWeave.runId=tlprod-on1",
    "-Dturboism.validation.tlWeave.outputDir={HOME}/tl-weave",
    "-Dturboism.validation.tlWeave.expectClassSha256=f3427cec0e5c0c7d93c8a2cf72351a82a39b635b15682afc291eb3a62dc888f5",
    "-Dturboism.validation.tlWeave.expectCaptureClassSha256=f3427cec0e5c0c7d93c8a2cf72351a82a39b635b15682afc291eb3a62dc888f5",
    "-Dturboism.validation.tlWeave.expectLoader=jdk.internal.loader.ClassLoaders$AppClassLoader",
    "-Dturboism.validation.tlWeave.expectCodeSource=file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3.03/app/lib/Live2D_Cubism.jar",
    "-Dturboism.validation.tlWeave.captureN=4",
]
assert props == expected, props
assert "--home-config" not in argv, argv
assert not any("triWeave." in a or "dmWeave." in a for a in argv)
print("T029_TLPROD_ON5303_PLAN PASS expectSha=patched props=11")
PYTP1
# Resource mode is an explicit, separately measured protocol; ordinary prepares omit it.
python3 - "$prepare_tlp_on/runner-request.json" <<'PYRESOURCEDEFAULT'
import json, sys
from pathlib import Path
assert not any("resourceObservation" in a for a in json.loads(Path(sys.argv[1]).read_text())["argv"])
PYRESOURCEDEFAULT
prepare_resources="$test_root/prepare-tlprod-resources"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  TURBOISM_ATLAS_IMAGE_SHADOW_RESOURCE_OBSERVATION=true \
  "$wrapper" 5303 t029-tlprod-on1-heavy-nolayout-jfr \
  --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump-only' \
  --prepare-dir "$prepare_resources" > "$test_root/prepare-resources.log" 2>&1 \
  || fail 'resource protocol prepare failed'
python3 - "$prepare_resources/runner-request.json" <<'PYRESOURCE'
import json, sys
from pathlib import Path
argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
assert argv.count("-Dturboism.validation.atlasImageShadow.resourceObservation=true") == 1
assert "-Dturboism.validation.atlasImageShadow.tlprodOptIn=TLPROD_EXPLICIT_OPT_IN" in argv
PYRESOURCE
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    TURBOISM_ATLAS_IMAGE_SHADOW_RESOURCE_OBSERVATION=maybe \
    "$wrapper" 5303 t029-tlprod-on1-heavy-nolayout-jfr \
    --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump-only' --dry-run \
    > "$test_root/resource-invalid.log" 2>&1; then
  fail 'resource protocol accepted invalid boolean'
fi
grep -q 'resource-observation flag must be true or false' "$test_root/resource-invalid.log"
for resource_case in non-production export; do
  resource_label=t029-tlprod-on1-heavy-nolayout-jfr
  resource_export=false
  if [[ "$resource_case" == non-production ]]; then
    resource_label=t029-tlindex-base1-heavy-nolayout-jfr
  else
    resource_export=true
  fi
  if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
      TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
      TURBOISM_ATLAS_IMAGE_SHADOW_RESOURCE_OBSERVATION=true \
      TURBOISM_ATLAS_IMAGE_SHADOW_EXPORT_PROBE="$resource_export" \
      "$wrapper" 5303 "$resource_label" --bundle-manifest "$manifest_heavy" \
      --tri-tlindex 'tl-dump-only' --dry-run > "$test_root/resource-$resource_case.log" 2>&1; then
    fail "resource protocol accepted $resource_case"
  fi
  grep -q 'resource observation requires explicit heavy TLPROD without layout/export' \
    "$test_root/resource-$resource_case.log"
done
# 5303 off leg: pristine digest + pinned home config staged
prepare_tlp_off="$test_root/prepare-tlprod-off"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5303 t029-tlprod-off1-heavy-nolayout \
  --bundle-manifest "$manifest_heavy" --tri-tlindex 'tl-dump-only' \
  --prepare-dir "$prepare_tlp_off" > "$test_root/prepare-tlprod-off.log" 2>&1 \
  || fail 'tlprod off-leg prepare failed'
grep -q 'tlindexPreference=off' "$test_root/prepare-tlprod-off.log"
grep -q 'tlindexOffConfigSha256=47bb572625150b9f6a78a75be1e1b62cf1df0b179b58a2beffd7b568c4ae8c43' \
  "$test_root/prepare-tlprod-off.log"
python3 - "$prepare_tlp_off/runner-request.json" <<'PYTP2'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
home = [argv[i + 1] for i, flag in enumerate(argv) if flag == "--home-config"]
assert len(home) == 1 and home[0].endswith("home-config-tlindexoff.json"), home
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "tlWeave." in argv[i + 1]]
assert "-Dturboism.validation.tlWeave.expectClassSha256=87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29" in props, props
assert "-Dturboism.validation.tlWeave.runId=tlprod-off1" in props, props
assert len(props) == 11, props
print("T029_TLPROD_OFF5303_PLAN PASS expectSha=pristine homeConfig=staged")
PYTP2
# 5302 on leg: patched digest + 5.3 codeSource + no t039 staging
prepare_tlp_5302="$test_root/prepare-tlprod-5302"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5302="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5302 t029-tlprod-on1-heavy-nolayout \
  --bundle-manifest "$manifest_5302" --tri-tlindex 'tl-dump-only' \
  --prepare-dir "$prepare_tlp_5302" > "$test_root/prepare-tlprod-5302.log" 2>&1 \
  || fail 'tlprod 5302-leg prepare failed'
python3 - "$prepare_tlp_5302/runner-request.json" <<'PYTP3'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
aux = [argv[i + 1].split(":")[-1] for i, flag in enumerate(argv) if flag == "--aux-agent"]
assert aux == ["tri-weave-agent.jar", "atlas-image-shadow-scene-driver.jar"], aux
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "tlWeave." in argv[i + 1]]
assert "-Dturboism.validation.tlWeave.expectClassSha256=f3427cec0e5c0c7d93c8a2cf72351a82a39b635b15682afc291eb3a62dc888f5" in props, props
assert "-Dturboism.validation.tlWeave.expectCodeSource=file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3/app/lib/Live2D_Cubism.jar" in props, props
assert not any("t039." in a or "aux-agent-before-main" in a for a in argv), argv
print("T029_TLPROD_5302_PLAN PASS expectSha=patched53 codeSource=5.3")
PYTP3
# 5203 on leg: patched digest + 5.2 codeSource under the heavy fixture
prepare_tlp_5203="$test_root/prepare-tlprod-5203"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5203="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5203 t029-tlprod-on1-heavy-nolayout \
  --bundle-manifest "$manifest_5203" --tri-tlindex 'tl-dump-only' \
  --prepare-dir "$prepare_tlp_5203" > "$test_root/prepare-tlprod-5203.log" 2>&1 \
  || fail 'tlprod 5203-leg prepare failed'
python3 - "$prepare_tlp_5203/runner-request.json" <<'PYTP4'
import json
import sys
from pathlib import Path

argv = json.loads(Path(sys.argv[1]).read_text())["argv"]
aux = [argv[i + 1].split(":")[-1] for i, flag in enumerate(argv) if flag == "--aux-agent"]
assert aux == ["tri-weave-agent.jar", "atlas-image-shadow-scene-driver.jar"], aux
props = [argv[i + 1] for i, flag in enumerate(argv)
         if flag == "--jvm-option" and "tlWeave." in argv[i + 1]]
assert "-Dturboism.validation.tlWeave.expectClassSha256=33bea8bc8bf437df71915b17a704a2283946424458b54848cdb8cdea35010cb4" in props, props
assert "-Dturboism.validation.tlWeave.expectCodeSource=file:/C:/Program%%20Files/Live2D%%20Cubism%%205.2/app/lib/Live2D_Cubism.jar" in props, props
assert not any("t039." in a or "aux-agent-before-main" in a for a in argv), argv
print("T029_TLPROD_5203_PLAN PASS expectSha=patched52 codeSource=5.2")
PYTP4

# The production opt-in token reaches the actual scene DriverConfig on all versions;
# no missing wrapper->driver admission seam can masquerade as a prepared production leg.
python3 - "$prepare_tlp_on" "$prepare_tlp_off" "$prepare_tlp_5302" "$prepare_tlp_5203" <<'PYTPTOKEN'
import json, sys
from pathlib import Path
for root in sys.argv[1:]:
    argv = json.loads((Path(root) / 'runner-request.json').read_text())['argv']
    assert argv.count('-Dturboism.validation.atlasImageShadow.tlprodOptIn=TLPROD_EXPLICIT_OPT_IN') == 1, argv
print('T029_TLPROD_DRIVER_TOKEN PASS allVersions=true')
PYTPTOKEN

# Replay those planned properties through the REAL DriverConfig, not just the
# pure pair allowlist. Use a task-owned CoW copy of the pinned heavy model; no host
# is launched and neither the original model nor the official installation changes.
driver_admission_home="$test_root/tlprod-driver-home"
driver_admission_fixture="$test_root/offline-tlprod-driver-heavy.cmo3"
mkdir -p "$driver_admission_home"
cp --reflink=auto -- "$heavy_fixture_real" "$driver_admission_fixture"
for version_and_plan in "5203:$prepare_tlp_5203" "5302:$prepare_tlp_5302" "5303:$prepare_tlp_on"; do
  version_under_test="${version_and_plan%%:*}"
  plan_under_test="${version_and_plan#*:}"
  property_file="$test_root/tlprod-driver-$version_under_test.properties"
  python3 - "$plan_under_test/runner-request.json" "$driver_admission_home" \
    "$driver_admission_fixture" "$version_under_test" "$property_file" <<'PYTPDRIVER'
import json, sys
from pathlib import Path
argv = json.loads(Path(sys.argv[1]).read_text())['argv']
values = {}
for i, flag in enumerate(argv):
    if flag == '--jvm-option' and argv[i+1].startswith('-D'):
        key, value = argv[i+1][2:].split('=', 1)
        for token, replacement in [('{HOME}', sys.argv[2]), ('{TASK_ID}', 'offline-tlprod-driver'),
                                   ('{FIXTURE}', sys.argv[3]),
                                   ('{FIXTURE_NAME}', Path(sys.argv[3]).name)]:
            value = value.replace(token, replacement)
        values[key] = value
values.update({'turboism.home': sys.argv[2], 'turboism.validation.runId': 'offline-tlprod-driver',
               'turboism.validation.hostVersion': sys.argv[4]})
Path(sys.argv[5]).write_text(''.join(f'{k}={v}\n' for k, v in values.items()))
PYTPDRIVER
  run_launch_probe "$property_file" > "$test_root/tlprod-driver-$version_under_test.log" 2>&1 \
    || fail "real DriverConfig rejected the pinned production launch on $version_under_test"
  grep -q '^T040_LAUNCH_PROPERTY_CONTRACT PASS ' "$test_root/tlprod-driver-$version_under_test.log"
done
for token in absent wrong-token; do
  grep -v '^turboism.validation.atlasImageShadow.tlprodOptIn=' \
    "$test_root/tlprod-driver-5203.properties" > "$test_root/tlprod-driver-bad.properties"
  if [[ "$token" != absent ]]; then
    printf 'turboism.validation.atlasImageShadow.tlprodOptIn=%s\n' "$token" \
      >> "$test_root/tlprod-driver-bad.properties"
  fi
  if run_launch_probe "$test_root/tlprod-driver-bad.properties" \
      > "$test_root/tlprod-driver-bad.log" 2>&1; then
    fail 'real DriverConfig accepted heavy on 5203 without the exact production token'
  fi
  grep -q '^T040_LAUNCH_PROPERTY_CONTRACT BLOCKED IllegalArgumentException:' \
    "$test_root/tlprod-driver-bad.log"
done
printf 'T029_TLPROD_DRIVER_ADMISSION PASS realEntry=true allVersions=true noHost=true\n'
# Publishing admission must match the runtime seam; ordinary 5203 heavy builds
# remain blocked, while the explicit fixed token selects the per-version heavy key.
if "$builder" --host-profile 5203 --fixture-profile heavy \
    > "$test_root/tlprod-builder-no-token.log" 2>&1; then
  fail 'ordinary 5203 build accepted heavy without production opt-in'
fi
grep -q 'single reviewed fixture' "$test_root/tlprod-builder-no-token.log"
if "$builder" --host-profile 5203 --fixture-profile heavy --tlprod-opt-in wrong-token \
    > "$test_root/tlprod-builder-bad-token.log" 2>&1; then
  fail 'production builder accepted the wrong opt-in token'
fi
grep -q 'production opt-in token is invalid' "$test_root/tlprod-builder-bad-token.log"
TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5203="$heavy_fixture_real" \
  "$builder" --host-profile 5203 --fixture-profile heavy \
  --tlprod-opt-in TLPROD_EXPLICIT_OPT_IN > "$test_root/tlprod-builder-5203.log" 2>&1 \
  || fail 'explicit 5203 heavy build failed'
grep -q '^T040_SHADOW_SCENE_SELFCHECK PASS ' "$test_root/tlprod-builder-5203.log"
grep -q 'hostExecuted=false' "$test_root/tlprod-builder-5203.log"
printf 'T029_TLPROD_BUILDER_ADMISSION PASS explicitOnly=true noHost=true\n'

# Synthetic files exercise wrapper protocol only, not UI evidence. A real accepted
# host result is still required before using an exported UI config for final A/B.
ui_off="$test_root/ui-off.json"
printf '{"meshTriangulationEdgeIndex":false}\n' > "$ui_off"
prepare_tlp_ui="$test_root/prepare-tlprod-ui-off"
env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
  TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
  "$wrapper" 5303 t029-tlprod-off3-heavy-nolayout \
  --bundle-manifest "$manifest_heavy" --tri-tlindex tl-dump-only \
  --tri-tlindex-home-config "$ui_off" --prepare-dir "$prepare_tlp_ui" \
  > "$test_root/tlprod-ui-off.log" 2>&1 || fail 'UI off-config prepare failed'
python3 - "$prepare_tlp_ui/runner-request.json" "$ui_off" <<'PYTPUI'
import json, sys
from pathlib import Path
argv = json.loads(Path(sys.argv[1]).read_text())['argv']
home = [argv[i + 1] for i, flag in enumerate(argv) if flag == '--home-config']
assert home == [sys.argv[2]], home
print('T029_TLPROD_UI_CONFIG PASS stagedOnce=true')
PYTPUI
for bad in '{"meshTriangulationEdgeIndex":true}' \
           '{"meshTriangulationEdgeIndex":"false"}' \
           '{"meshTriangulationEdgeIndex":true,"meshTriangulationEdgeIndex":false}' \
           '{}'; do
  printf '%s\n' "$bad" > "$test_root/ui-bad.json"
  if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
      TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
      "$wrapper" 5303 t029-tlprod-off3-heavy-nolayout \
      --bundle-manifest "$manifest_heavy" --tri-tlindex tl-dump-only \
      --tri-tlindex-home-config "$test_root/ui-bad.json" --dry-run \
      > "$test_root/tlprod-ui-bad.log" 2>&1; then
    fail 'production leg accepted a missing, ambiguous or mismatched UI Boolean'
  fi
  grep -q 'production home config does not match the leg' "$test_root/tlprod-ui-bad.log"
done
if env "${runner_env[@]}" TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303="$heavy_fixture_real" \
    TURBOISM_TRI_WEAVE_AGENT="$tri_weave_agent_stub" \
    "$wrapper" 5303 t029-tlindex-base3-heavy-nolayout \
    --bundle-manifest "$manifest_heavy" --tri-tlindex tl-dump-only \
    --tri-tlindex-home-config "$ui_off" --dry-run > "$test_root/tlprod-ui-nonprod.log" 2>&1; then
  fail 'nonproduction weave label accepted the production UI config option'
fi
grep -q 'requires a production capture leg' "$test_root/tlprod-ui-nonprod.log"

printf 'ATLAS_IMAGE_SHADOW_HOST_VALIDATION_OFFLINE_TEST PASS hostLaunched=false\n'
