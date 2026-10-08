#!/usr/bin/env bash
# Offline-only runner: builds the probe agent and exercises every required control in fresh JVMs.
# Never prepares, submits, launches the host, or defines/executes any official class.
set -euo pipefail
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scene_dir="$root/validation/triangulation-identity-probe"
fail() { printf 'tri-identity-probe run: %s\n' "$*" >&2; exit 1; }

build_out="$(bash "$scene_dir/build.sh")" || { printf '%s\n' "$build_out" >&2; fail 'build failed'; }
printf '%s\n' "$build_out" | grep -E '^(work|agentJar|agentSha256|noHelper|fixtureClassSha256|selfcheck|fixture|badshape|nolink|badinit|TRI)' || true

agent_jar="$(printf '%s\n' "$build_out" | sed -n 's/^agentJar=//p')"
noh_jar="$(printf '%s\n' "$build_out" | sed -n 's/^noHelperJar=//p')"
fixture_sha="$(printf '%s\n' "$build_out" | sed -n 's/^fixtureClassSha256=//p')"
sc="$(printf '%s\n' "$build_out" | sed -n 's/^selfcheckClasses=//p')"
fx="$(printf '%s\n' "$build_out" | sed -n 's/^fixtureClasses=//p')"
bs="$(printf '%s\n' "$build_out" | sed -n 's/^badshapeClasses=//p')"
nl="$(printf '%s\n' "$build_out" | sed -n 's/^nolinkClasses=//p')"
bi="$(printf '%s\n' "$build_out" | sed -n 's/^badinitClasses=//p')"
work="$(printf '%s\n' "$build_out" | sed -n 's/^workDir=//p')"
deps="$scene_dir/deps"

[[ -n "$agent_jar" && -n "$fixture_sha" && -n "$sc" && -n "$fx" && -n "$nl" && -n "$bi" ]] \
  || fail 'build output did not provide required artifacts'

P=turboism.validation.triIdentity
LOADER=dev.turboism.validation.shared.fixture.FixtureLoader

# Expectation derived from the real CodeSource.toExternalForm of the fixture dir URL —
# the same URL construction the fixture loader passes to the JVM.
fx_url="$(java -cp "$sc" dev.turboism.validation.shared.fixture.CodeSourceUrl "$fx")"

run_scenario() {
  local scenario="$1" jar="$2" fxdir="$3" outdir="$4" sarg="${5:-}"
  [[ -e "$outdir" ]] || mkdir -p "$outdir"  # writeFailure passes an existing FILE path
  java -Xverify:all ${jar:+-javaagent:"$jar"} \
    "-D$P.enabled=true" "-D$P.phase=triIdentitySelfCheck" "-D$P.runId=$scenario" \
    "-D$P.outputDir=$outdir" "-D$P.expectClassSha256=$fixture_sha" \
    "-D$P.expectLoader=$LOADER" "-D$P.expectCodeSource=$fx_url" \
    -cp "$sc:$fx" dev.turboism.validation.triprobe.IdentityProbeSelfCheck \
    "$scenario" "$fxdir" "$outdir/tri-identity-definition-$scenario.log" \
    "$outdir/tri-identity-usesite-$scenario.log" ${sarg:+"$sarg"}
}
# Variant with extra JVM props after the fixed -D set (later -D wins).
run_scenario_props() {
  local scenario="$1" jar="$2" fxdir="$3" outdir="$4"; shift 4
  [[ -e "$outdir" ]] || mkdir -p "$outdir"
  java -Xverify:all ${jar:+-javaagent:"$jar"} \
    "-D$P.enabled=true" "-D$P.phase=triIdentitySelfCheck" "-D$P.runId=$scenario" \
    "-D$P.outputDir=$outdir" "-D$P.expectClassSha256=$fixture_sha" \
    "-D$P.expectLoader=$LOADER" "-D$P.expectCodeSource=$fx_url" "$@" \
    -cp "$sc:$fx" dev.turboism.validation.triprobe.IdentityProbeSelfCheck \
    "$scenario" "$fxdir" "$outdir/tri-identity-definition-$scenario.log" \
    "$outdir/tri-identity-usesite-$scenario.log"
}

passes=0
total=0
note() { total=$((total+1)); passes=$((passes+1)); printf '[%s] %s\n' "$total" "$1"; }

# --- admission-valid scenarios --------------------------------------------------
run_scenario happy "$agent_jar" "$fx" "$work/out-happy" && note happy
# default-off: no javaagent, no probe props at all
mkdir -p "$work/out-off"
java -Xverify:all -cp "$sc:$fx" dev.turboism.validation.triprobe.IdentityProbeSelfCheck \
  off "$fx" "$work/out-off/tri-identity-definition-off.log" \
  "$work/out-off/tri-identity-usesite-off.log" && note off
# refused admission configs (premain safe-refusal; JVM+entrypoint continue)
mkdir -p "$work/out-missingcfg"
java -Xverify:all -javaagent:"$agent_jar" "-D$P.enabled=true" \
  -cp "$sc:$fx" dev.turboism.validation.triprobe.IdentityProbeSelfCheck \
  refusedConfig "$fx" "$work/out-missingcfg/d.log" "$work/out-missingcfg/u.log" \
  && note missingConfig
java -Xverify:all -javaagent:"$agent_jar" "-D$P.enabled=true" \
  "-D$P.outputDir=$work/out-badrunid" "-D$P.runId=bad/run:id" \
  "-D$P.expectClassSha256=$fixture_sha" "-D$P.expectLoader=$LOADER" \
  "-D$P.expectCodeSource=$fx_url" \
  -cp "$sc:$fx" dev.turboism.validation.triprobe.IdentityProbeSelfCheck \
  refusedConfig "$fx" "$work/out-badrunid/d.log" "$work/out-badrunid/u.log" \
  && note badRunId
java -Xverify:all -javaagent:"$agent_jar" "-D$P.enabled=true" \
  "-D$P.outputDir=relative/out" "-D$P.runId=reldir" \
  "-D$P.expectClassSha256=$fixture_sha" "-D$P.expectLoader=$LOADER" \
  "-D$P.expectCodeSource=$fx_url" \
  -cp "$sc:$fx" dev.turboism.validation.triprobe.IdentityProbeSelfCheck \
  refusedConfig "$fx" "relative/out/d.log" "relative/out/u.log" \
  && note relativeOutDir
# gate rejections (config admitted; per-class gate rejects)
run_scenario_props wrongSource "$agent_jar" "$fx" "$work/out-wrongsource" \
  "-D$P.expectCodeSource=file:/nonexistent-source" && note wrongSource
run_scenario_props wrongSha "$agent_jar" "$fx" "$work/out-wrongsha" \
  "-D$P.expectClassSha256=0000000000000000000000000000000000000000000000000000000000000000" \
  && note wrongSha
run_scenario wrongLoader "$agent_jar" "$fx" "$work/out-wrongloader" && note wrongLoader
badsha="$(sha256sum "$bs/com/live2d/graphics3d/editableMesh/triangulation/TriangleList.class" | awk '{print $1}')"
bs_url="$(java -cp "$sc" dev.turboism.validation.shared.fixture.CodeSourceUrl "$bs")"
run_scenario_props badShape "$agent_jar" "$bs" "$work/out-badshape" \
  "-D$P.expectClassSha256=$badsha" "-D$P.expectCodeSource=$bs_url" && note badShape
# helper failures: premain refusal (missing jar helper, broken clinit at warm)
run_scenario missingHelper "$noh_jar" "$fx" "$work/out-missinghelper" && note missingHelper
run_scenario_props failInit "$agent_jar" "$fx" "$work/out-failinit" \
  "-D$P.failInit=true" && note failInit
# weave-level call failure: helper warms, record() throws inside the catch
run_scenario_props throwOnRecord "$agent_jar" "$fx" "$work/out-throw" \
  "-D$P.throwOnRecord=true" && note throwOnRecord
# direct weave link/init failures: stub Probe visible only to the fixture loader
run_scenario directLinkFail "$agent_jar" "$fx" "$work/out-directlink" "$nl" && note directLinkFail
run_scenario directInitFail "$agent_jar" "$fx" "$work/out-directinit" "$bi" && note directInitFail
# original-behavior passthrough + concurrency budget
run_scenario passthrough "$agent_jar" "$fx" "$work/out-passthrough" && note passthrough
run_scenario concurrency "$agent_jar" "$fx" "$work/out-concurrency" && note concurrency
# write failure: outputDir is an existing regular file (admission still valid — absolute path)
touch "$work/blocking-file"
run_scenario writeFailure "$agent_jar" "$fx" "$work/blocking-file" && note writeFailure
# definition-observation bound
run_scenario observerBudget "$agent_jar" "$fx" "$work/out-observer" && note observerBudget
# hostile field content: newline + controls + overlong phase value
evil_phase="$(printf 'EVIL\nLIT\\nREAL\tTAB\x01END SP\sACE %s' "$(head -c 3000 < /dev/zero | tr '\0' 'x')")"
run_scenario_props maliciousFields "$agent_jar" "$fx" "$work/out-malicious" \
  "-D$P.phase=$evil_phase" && note maliciousFields
# CodeSource matching rules — pure static helper check, no agent needed.
java -Xverify:all -cp "$sc:$work/classes" dev.turboism.validation.triprobe.IdentityProbeSelfCheck \
  codeSourceUnit "$fx" "$work/out-csu/d.log" "$work/out-csu/u.log" && note codeSourceUnit

# official read-only shape verification (never defines/executes official bytes)
official_jar="${TURBOISM_TRI_IDENTITY_OFFICIAL_JAR:-$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03/app/lib/Live2D_Cubism.jar}"
if [[ -f "$official_jar" ]]; then
  java -cp "$work/classes:$deps/asm-9.7.1.jar" \
    dev.turboism.validation.triprobe.OfficialShapeProbe "$official_jar" || fail 'official probe failed'
  note officialShape
else
  printf 'officialProbe=skipped jar-not-found\n'
fi

printf 'TRI_IDENTITY_RUN PASS scenarios=%d hostExecuted=false officialClassLoaded=false\n' "$passes"
