#!/usr/bin/env bash
# Offline-only build and self-check for the atlas-path timing agent.
# It never prepares, submits, launches Cubism, or defines/executes any official class.
set -euo pipefail
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scene_dir="$root/validation/atlas-image-timing"
deps="$scene_dir/deps"
asm_source="${HOME:-/nonexistent}/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/f0ed132a49244b042cd0e15702ab9f2ce3cc8436/asm-9.7.1.jar"
asm_sha256=8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281
# The relocation tool runs at build time only; the agent never sees this namespace at runtime.
gradle_lib="${HOME:-/nonexistent}/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/lib"
tool_asm="$gradle_lib/asm-9.7.jar"
tool_asm_commons="$gradle_lib/asm-commons-9.7.jar"
tool_asm_tree="$gradle_lib/asm-tree-9.7.jar"
shaded_prefix=dev/turboism/validation/atlastiming/shaded/asm97

fail() {
  printf 'atlas-timing build: %s\n' "$*" >&2
  exit 1
}

[[ -f "$asm_source" ]] || fail "ASM 9.7.1 not found at $asm_source"
[[ "$(sha256sum "$asm_source" | awk '{print $1}')" == "$asm_sha256" ]] \
  || fail 'ASM 9.7.1 hash mismatch'

mkdir -p "$deps" "$root/build/atlas-image-timing"
cp "$asm_source" "$deps/asm-9.7.1.jar"
work="$(mktemp -d "$root/build/atlas-image-timing/compile.XXXXXX")"

classes="$work/classes"
fixture_classes="$work/fixture"
selfcheck_classes="$work/selfcheck"
mkdir -p "$classes" "$fixture_classes" "$selfcheck_classes"

mapfile -t sources < <(find "$scene_dir/src" -name '*.java' | sort)
[[ "${#sources[@]}" -gt 0 ]] || fail 'no sources found'

javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$deps/asm-9.7.1.jar" -d "$classes" "${sources[@]}"

# Compile the fixture into a standalone directory the self-check reads as raw bytes.
mapfile -t fixture_sources < <(find "$scene_dir/src" -path '*/fixture/*.java' | sort)
[[ "${#fixture_sources[@]}" -gt 0 ]] || fail 'no fixture sources found'
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$fixture_classes" "${fixture_sources[@]}"

javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$classes:$fixture_classes:$deps/asm-9.7.1.jar" -d "$selfcheck_classes" "${sources[@]}"

# Input for the relocation pass that produces the self-contained agent jar.
jar --create --file "$work/slice-classes.jar" -C "$classes" .

main_class=dev.turboism.validation.atlastiming.AtlasTimingSelfCheck
selfcheck_log="$work/selfcheck.log"
if ! java -Xverify:all \
    -cp "$selfcheck_classes:$fixture_classes:$deps/asm-9.7.1.jar" \
    "$main_class" "$fixture_classes" "$work/selfcheck-evidence" \
    > "$selfcheck_log" 2>&1; then
  cat "$selfcheck_log" >&2
  fail 'self-check failed'
fi
cat "$selfcheck_log"

grep -q '^ATLAS_TIMING_SELFCHECK PASS' "$selfcheck_log" || fail 'self-check did not report PASS'
grep -q 'officialClassLoaded=false' "$selfcheck_log" \
  || fail 'self-check did not disclaim official classes'

# Read-only verification of the official method identities; never defines or executes them.
official_jar="${TURBOISM_ATLAS_TIMING_OFFICIAL_JAR:-$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03/app/lib/Live2D_Cubism.jar}"
official_log="$work/official.log"
if [[ -f "$official_jar" ]]; then
  if java -cp "$selfcheck_classes:$deps/asm-9.7.1.jar" \
      dev.turboism.validation.atlastiming.AtlasTimingOfficialProbe "$official_jar" \
      > "$official_log" 2>&1; then
    cat "$official_log"
    grep -q '^ATLAS_TIMING_OFFICIAL_PROBE PASS' "$official_log" \
      || fail 'official probe did not report PASS'
  else
    cat "$official_log" >&2
    fail 'official probe was not applicable to this build'
  fi
else
  printf 'officialProbe=skipped jar-not-found\n'
fi

# The 5.2.03 profile is probed against its own reviewed jar when present.
official_jar_5203="${TURBOISM_ATLAS_TIMING_OFFICIAL_JAR_5203:-$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.2/app/lib/Live2D_Cubism.jar}"
official_log_5203="$work/official-5203.log"
if [[ -f "$official_jar_5203" ]]; then
  if java -cp "$selfcheck_classes:$deps/asm-9.7.1.jar" \
      dev.turboism.validation.atlastiming.AtlasTimingOfficialProbe "$official_jar_5203" 5203 \
      > "$official_log_5203" 2>&1; then
    cat "$official_log_5203"
    grep -q '^ATLAS_TIMING_OFFICIAL_PROBE PASS' "$official_log_5203" \
      || fail 'official 5203 probe did not report PASS'
  else
    cat "$official_log_5203" >&2
    fail 'official 5203 probe was not applicable to this build'
  fi
else
  printf 'officialProbe5203=skipped jar-not-found\n'
fi

# --- self-contained agent jar -------------------------------------------------
[[ -f "$tool_asm" && -f "$tool_asm_commons" && -f "$tool_asm_tree" ]] \
  || fail 'ASM 9.7 build-time tooling is missing from the Gradle distribution'

tool_classes="$work/tool"
mkdir -p "$tool_classes"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$tool_asm:$tool_asm_commons:$tool_asm_tree" -d "$tool_classes" \
  "$scene_dir/tools/dev/turboism/validation/atlastiming/tools/RelocateJar.java"

shaded_dir="$work/shaded"
mkdir -p "$shaded_dir"
java -cp "$tool_classes:$tool_asm:$tool_asm_commons:$tool_asm_tree" \
  dev.turboism.validation.atlastiming.tools.RelocateJar \
  "$deps/asm-9.7.1.jar" "$shaded_dir/asm-relocated.jar" \
  org/objectweb/asm "$shaded_prefix"

relocated_slice="$work/relocated-slice"
mkdir -p "$relocated_slice"
java -cp "$tool_classes:$tool_asm:$tool_asm_commons:$tool_asm_tree" \
  dev.turboism.validation.atlastiming.tools.RelocateJar \
  "$work/slice-classes.jar" "$relocated_slice/slice-relocated.jar" \
  org/objectweb/asm "$shaded_prefix"

agent_dir="$work/agent"
mkdir -p "$agent_dir"
( cd "$agent_dir" && jar xf "$shaded_dir/asm-relocated.jar" && jar xf "$relocated_slice/slice-relocated.jar" )
# The agent jar is appended to the system classpath; it must not carry harness/probe-side classes
# that would shadow the copies the build runs from a classpath.
find "$agent_dir" -name 'AtlasTimingSelfCheck*' -delete
find "$agent_dir" -name 'AtlasTimingOfficialProbe*' -delete
find "$agent_dir" -name 'AtlasTimingAgentHarness*' -delete
find "$agent_dir" -path '*/fixture/*' -delete

agent_manifest="$work/agent-manifest.mf"
{
  printf 'Manifest-Version: 1.0\n'
  printf 'Premain-Class: dev.turboism.validation.atlastiming.AtlasTimingAgent\n'
  printf 'Implementation-Title: atlas-image-timing validation agent\n'
  printf 'Implementation-Version: 1\n'
} > "$agent_manifest"
agent_jar="$work/atlas-timing-agent.jar"
jar --create --file "$agent_jar" --manifest "$agent_manifest" -C "$agent_dir" .

if jar tf "$agent_jar" | grep -q '^org/objectweb/asm'; then
  fail 'agent jar leaks the original ASM namespace'
fi
if unzip -p "$agent_jar" META-INF/MANIFEST.MF | grep -q 'Class-Path:'; then
  fail 'agent jar must not rely on a manifest Class-Path'
fi
jar tf "$agent_jar" | grep -Fqx "$shaded_prefix/ClassReader.class" \
  || fail 'agent jar is missing its private ASM'
jar tf "$agent_jar" | grep -Fqx 'dev/turboism/validation/atlastiming/AtlasTimingAgent.class' \
  || fail 'agent jar is missing the premain class'
agent_sha256="$(sha256sum "$agent_jar" | awk '{print $1}')"
printf 'agentJar=%s\nagentSha256=%s\nagentAsmPrefix=%s\n' "$agent_jar" "$agent_sha256" "$shaded_prefix"

# --- live-JVM agent check (owner fixtures with the host's exact names) --------
fixture_agent_classes="$work/fixture-agent"
mkdir -p "$fixture_agent_classes"
mapfile -t agent_fixtures < <(find "$scene_dir/fixture-agent" -name '*.java' | sort)
[[ "${#agent_fixtures[@]}" -gt 0 ]] || fail 'no agent fixture sources found'
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$fixture_agent_classes" "${agent_fixtures[@]}"

harness_classes="$work/harness"
mkdir -p "$harness_classes"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$harness_classes" \
  "$scene_dir/src/dev/turboism/validation/atlastiming/AtlasTimingAgentHarness.java"

harness_cp="$harness_classes:$fixture_agent_classes"
evidence_dir="$work/agent-evidence"
rm -rf "$evidence_dir"
java -Xverify:all \
  "-javaagent:$agent_jar" \
  "-Dturboism.validation.atlasTiming.optIn=ATLAS_TIMING_EXPLICIT_OPT_IN" \
  "-Dturboism.validation.atlasTiming.output=$evidence_dir" \
  -cp "$harness_cp" dev.turboism.validation.atlastiming.AtlasTimingAgentHarness \
  "$evidence_dir" > "$work/agent.log" 2>&1 \
  || { cat "$work/agent.log" >&2; fail 'agent leg failed'; }
cat "$work/agent.log"
grep -q '^ATLAS_TIMING_AGENT_HARNESS PASS' "$work/agent.log" \
  || fail 'agent leg did not report PASS'

# A missing opt-in token must block the agent entirely.
blocked_dir="$work/agent-evidence-blocked"
rm -rf "$blocked_dir"
java -Xverify:all -javaagent:"$agent_jar" \
  "-Dturboism.validation.atlasTiming.output=$blocked_dir" \
  -cp "$harness_cp" dev.turboism.validation.atlastiming.AtlasTimingAgentHarness \
  "$blocked_dir" > "$work/agent-blocked.log" 2>&1 || true
if [[ -f "$blocked_dir/timing-calls.txt" ]]; then
  cat "$work/agent-blocked.log" >&2
  fail 'opt-in gate did not stop instrumentation'
fi
echo "ATLAS_TIMING_OPTIN_GATE PASS no instrumentation without token"

printf 'asmDependency=org.ow2.asm:asm:9.7.1\n'
printf 'asmSha256=%s\n' "$asm_sha256"
printf 'ATLAS_TIMING_BUILD PASS evidence=%s hostExecuted=false officialClassLoaded=false\n' "$work"
