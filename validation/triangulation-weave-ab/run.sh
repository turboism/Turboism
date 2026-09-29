#!/usr/bin/env bash
# Offline-only runner: builds the dump+weave agent and exercises every required control in
# fresh -Xverify:all JVMs. Never prepares, submits, launches the host, or defines/executes
# any official class — the shadow fixture supplies the verified byte shape under own names.
set -euo pipefail
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scene_dir="$root/validation/triangulation-weave-ab"
fail() { printf 'tri-weave-ab run: %s\n' "$*" >&2; exit 1; }

build_out="$(bash "$scene_dir/build.sh")" || { printf '%s\n' "$build_out" >&2; fail 'build failed'; }
printf '%s\n' "$build_out" | grep -E '^(work|agent|noHelper|shadow|badshape|badreturn|selfcheck|TRI)' || true

agent_jar="$(printf '%s\n' "$build_out" | sed -n 's/^agentJar=//p')"
noh_jar="$(printf '%s\n' "$build_out" | sed -n 's/^noHelperJar=//p')"
shadow_sha="$(printf '%s\n' "$build_out" | sed -n 's/^shadowClassSha256=//p')"
badshape_sha="$(printf '%s\n' "$build_out" | sed -n 's/^badshapeClassSha256=//p')"
badreturn_sha="$(printf '%s\n' "$build_out" | sed -n 's/^badreturnClassSha256=//p')"
sc="$(printf '%s\n' "$build_out" | sed -n 's/^selfcheckClasses=//p')"
fx="$(printf '%s\n' "$build_out" | sed -n 's/^shadowClasses=//p')"
noh="$(printf '%s\n' "$build_out" | sed -n 's/^nohelperClasses=//p')"
bs="$(printf '%s\n' "$build_out" | sed -n 's/^badshapeClasses=//p')"
br="$(printf '%s\n' "$build_out" | sed -n 's/^badreturnClasses=//p')"
ac="$(printf '%s\n' "$build_out" | sed -n 's/^agentClasses=//p')"
stdlib="$(printf '%s\n' "$build_out" | sed -n 's/^stdlibJar=//p')"
work="$(printf '%s\n' "$build_out" | sed -n 's/^workDir=//p')"
deps="$scene_dir/deps"

[[ -n "$agent_jar" && -n "$shadow_sha" && -n "$sc" && -n "$fx" && -n "$noh" && -n "$bs" ]] \
  || fail 'build output did not provide required artifacts'

P=turboism.validation.triWeave
SP=turboism.validation.triweave.shadow
LOADER=dev.turboism.validation.triweave.fixture.FixtureLoader

# Expectation derived from the real CodeSource.toExternalForm of the fixture dir URL.
fx_url="$(java -cp "$sc" dev.turboism.validation.triweave.CodeSourceUrl "$fx")"
noh_url="$(java -cp "$sc" dev.turboism.validation.triweave.CodeSourceUrl "$noh")"
bs_url="$(java -cp "$sc" dev.turboism.validation.triweave.CodeSourceUrl "$bs")"
br_url="$(java -cp "$sc" dev.turboism.validation.triweave.CodeSourceUrl "$br")"

# scenario jar mode expectSha expectCodeSource primaryFxDir outDir [extraFxDir] [extra -D...]
run_scenario() {
  local scenario="$1" jar="$2" mode="$3" sha="$4" src="$5" fxdir="$6" outdir="$7"
  shift 7
  local extra_fx='' extra_props=()
  for a in "$@"; do
    if [[ "$a" == -D* ]]; then extra_props+=("$a"); else extra_fx="$a"; fi
  done
  [[ -e "$outdir" ]] || mkdir -p "$outdir"   # writeFailure passes an existing FILE path
  java -Xverify:all ${jar:+-javaagent:"$jar"} \
    "-D$P.enabled=true" "-D$P.profile=shadow-selfcheck" "-D$P.mode=$mode" \
    "-D$P.phase=triWeaveSelfCheck" "-D$P.runId=$scenario" \
    "-D$P.outputDir=$outdir" "-D$P.expectClassSha256=$sha" \
    "-D$P.expectLoader=$LOADER" "-D$P.expectCodeSource=$src" \
    ${extra_props:+"${extra_props[@]}"} \
    -cp "$sc:$stdlib" dev.turboism.validation.triweave.WeaveAbSelfCheck \
    "$scenario" "$fxdir" \
    "$outdir/tri-weave-def-$scenario.log" "$outdir/tri-weave-dump-$scenario.log" \
    "$outdir/tri-weave-status-$scenario.txt" ${extra_fx:+"$extra_fx"}
}

passes=0
note() { passes=$((passes+1)); printf '[%s] %s\n' "$passes" "$1"; }

# --- core A/B legs --------------------------------------------------------------------
run_scenario happyDump "$agent_jar" dump-only "$shadow_sha" "$fx_url" "$fx" "$work/out-dump" \
   && note happyDump || fail "scenario happyDump failed"
run_scenario happyWeave "$agent_jar" dump+weave "$shadow_sha" "$fx_url" "$fx" "$work/out-weave" \
   && note happyWeave || fail "scenario happyWeave failed"

# Cross-mode equivalence verdict: same call ordinals must carry identical SHA-256 values.
python3 - "$work/out-dump/tri-weave-dump-happyDump.log" \
        "$work/out-weave/tri-weave-dump-happyWeave.log" <<'PY'
import re
import sys
from pathlib import Path

def records(path):
    out = []
    for line in Path(path).read_text().splitlines():
        seq = re.search(r'\bseq=(\d+)', line)
        sha = re.search(r'\bsha256=([0-9a-f]{64})', line)
        mode = re.search(r'\bmode=(\S+)', line)
        out.append((seq.group(1), sha.group(1), mode.group(1)))
    return out

a = records(sys.argv[1])
b = records(sys.argv[2])
assert a and b, "missing dump records"
assert [x[:2] for x in a] == [x[:2] for x in b], f"per-seq sha mismatch: {a} vs {b}"
assert [x[2] for x in a] == ["dump-only"] * len(a)
assert [x[2] for x in b] == ["dump+weave"] * len(b)
print(f"TRI_WEAVE_SHA_PARITY PASS records={len(a)}")
PY
note shaParity

# --- LinkageError fallback + recovery ------------------------------------------------
run_scenario fallbackInit "$agent_jar" dump+weave "$shadow_sha" "$fx_url" "$fx" \
  "$work/out-failinit" "-D$SP.failNewBox=true"  && note fallbackInit || fail "scenario fallbackInit failed"
run_scenario failAtN "$agent_jar" dump+weave "$shadow_sha" "$fx_url" "$fx" \
  "$work/out-failn" "-D$SP.failQueryAt=5"  && note failAtN || fail "scenario failAtN failed"

# --- missing helper ------------------------------------------------------------------
run_scenario missingHelperDump "$agent_jar" dump-only "$shadow_sha" "$noh_url" "$noh" \
  "$work/out-noh-dump"  && note missingHelperDump || fail "scenario missingHelperDump failed"
run_scenario missingHelperWeave "$agent_jar" dump+weave "$shadow_sha" "$noh_url" "$noh" \
  "$work/out-noh-weave"  && note missingHelperWeave || fail "scenario missingHelperWeave failed"

# --- shape gates ----------------------------------------------------------------------
run_scenario shapeRejectWeave "$agent_jar" dump+weave "$badshape_sha" "$bs_url" "$bs" \
  "$work/out-badshape-weave" "$fx"  && note shapeRejectWeave || fail "scenario shapeRejectWeave failed"
run_scenario shapeRejectDump "$agent_jar" dump-only "$badshape_sha" "$bs_url" "$bs" \
  "$work/out-badshape-dump" "$fx"  && note shapeRejectDump || fail "scenario shapeRejectDump failed"
run_scenario badReturnDump "$agent_jar" dump-only "$badreturn_sha" "$br_url" "$br" \
  "$work/out-badreturn-dump" "$fx"  && note badReturnDump || fail "scenario badReturnDump failed"
run_scenario badReturnWeave "$agent_jar" dump+weave "$badreturn_sha" "$br_url" "$br" \
  "$work/out-badreturn-weave" "$fx"  && note badReturnWeave || fail "scenario badReturnWeave failed"

# --- identity gates -------------------------------------------------------------------
run_scenario wrongSha "$agent_jar" dump+weave \
  0000000000000000000000000000000000000000000000000000000000000000 "$fx_url" "$fx" \
  "$work/out-wrongsha"  && note wrongSha || fail "scenario wrongSha failed"
run_scenario wrongSource "$agent_jar" dump+weave "$shadow_sha" "file:/nonexistent-source" \
  "$fx" "$work/out-wrongsrc"  && note wrongSource || fail "scenario wrongSource failed"
run_scenario wrongLoader "$agent_jar" dump+weave "$shadow_sha" "$fx_url" "$fx" \
  "$work/out-wrongloader"  && note wrongLoader || fail "scenario wrongLoader failed"

# --- admission / lifecycle ------------------------------------------------------------
run_scenario observerBudget "$agent_jar" dump+weave "$shadow_sha" "$fx_url" "$fx" \
  "$work/out-observer"  && note observerBudget || fail "scenario observerBudget failed"
mkdir -p "$work/out-off"
java -Xverify:all -cp "$sc:$stdlib" dev.turboism.validation.triweave.WeaveAbSelfCheck \
  off "$fx" "$work/out-off/d.log" "$work/out-off/u.log" "$work/out-off/s.txt"  && note off || fail "scenario off failed"
mkdir -p "$work/out-refused"
java -Xverify:all -javaagent:"$agent_jar" "-D$P.enabled=true" "-D$P.mode=dump-only" \
  "-D$P.profile=shadow-selfcheck" \
  -cp "$sc:$stdlib" dev.turboism.validation.triweave.WeaveAbSelfCheck \
  refusedConfig "$fx" "$work/out-refused/d.log" "$work/out-refused/u.log" \
  "$work/out-refused/s.txt" 2> "$work/out-refused/stderr.log"  && note refusedConfig || fail "scenario refusedConfig failed"
grep -q 'admission=reject reason=outputDir-missing' "$work/out-refused/stderr.log" \
  || fail 'refusedConfig missing admission reject line'
java -Xverify:all -javaagent:"$agent_jar" "-D$P.enabled=true" "-D$P.mode=weave" \
  "-D$P.profile=shadow-selfcheck" "-D$P.outputDir=$work/out-badmode" "-D$P.runId=badmode" \
  "-D$P.expectClassSha256=$shadow_sha" "-D$P.expectLoader=$LOADER" \
  "-D$P.expectCodeSource=$fx_url" \
  -cp "$sc:$stdlib" dev.turboism.validation.triweave.WeaveAbSelfCheck \
  refusedConfig "$fx" "$work/out-badmode/d.log" "$work/out-badmode/u.log" \
  "$work/out-badmode/s.txt" 2> "$work/out-badmode-stderr.log"  && note badMode || fail "scenario badMode failed"
grep -q 'admission=reject reason=mode-invalid' "$work/out-badmode-stderr.log" \
  || fail 'badMode missing admission reject line'
touch "$work/blocking-file"
run_scenario writeFailure "$agent_jar" dump-only "$shadow_sha" "$fx_url" "$fx" \
  "$work/blocking-file"  && note writeFailure || fail "scenario writeFailure failed"
evil_phase="$(printf 'EVIL\nLIT\\nREAL\tTAB\x01END SP\sACE %s' "$(head -c 3000 < /dev/zero | tr '\0' 'x')")"
run_scenario maliciousFields "$agent_jar" dump-only "$shadow_sha" "$fx_url" "$fx" \
  "$work/out-malicious" "-D$P.phase=$evil_phase"  && note maliciousFields || fail "scenario maliciousFields failed"

# --- exception / edge behavior ---------------------------------------------------------
run_scenario getterFaultDump "$agent_jar" dump-only "$shadow_sha" "$fx_url" "$fx" \
  "$work/out-fault-dump"  && note getterFaultDump || fail "scenario getterFaultDump failed"
run_scenario getterFaultWeave "$agent_jar" dump+weave "$shadow_sha" "$fx_url" "$fx" \
  "$work/out-fault-weave"  && note getterFaultWeave || fail "scenario getterFaultWeave failed"
run_scenario emptyWeave "$agent_jar" dump+weave "$shadow_sha" "$fx_url" "$fx" \
  "$work/out-empty"  && note emptyWeave || fail "scenario emptyWeave failed"
run_scenario nullEdgeWeave "$agent_jar" dump+weave "$shadow_sha" "$fx_url" "$fx" \
  "$work/out-null"  && note nullEdgeWeave || fail "scenario nullEdgeWeave failed"

# --- in-JVM shape-pin negatives (no agent needed) ---------------------------------------
java -Xverify:all -cp "$sc:$ac:$stdlib:$deps/asm-9.7.1.jar" \
  dev.turboism.validation.triweave.WeaveAbSelfCheck \
  shapePins "$fx" "$work/out-pins/d.log" "$work/out-pins/u.log" "$work/out-pins/s.txt" \
   && note shapePins || fail "scenario shapePins failed"
java -Xverify:all -cp "$sc:$ac:$stdlib:$deps/asm-9.7.1.jar" \
  dev.turboism.validation.triweave.WeaveAbSelfCheck \
  codeSourceUnit "$fx" "$work/out-csu/d.log" "$work/out-csu/u.log" "$work/out-csu/s.txt" \
   && note codeSourceUnit || fail "scenario codeSourceUnit failed"

# --- official read-only shape verification (never defines/executes official bytes) -------
official_jar="${TURBOISM_TRI_WEAVE_OFFICIAL_JAR:-$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03/app/lib/Live2D_Cubism.jar}"
java -cp "$ac:$deps/asm-9.7.1.jar" \
  dev.turboism.validation.triweave.OfficialShapeProbe "$official_jar" \
  || fail 'official probe failed'
note officialProbe

printf 'TRI_WEAVE_AB_RUN PASS scenarios=%d hostExecuted=false officialClassLoaded=false\n' "$passes"
