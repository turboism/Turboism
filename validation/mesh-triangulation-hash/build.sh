#!/usr/bin/env bash
# Offline-only build and self-check for the triangulation constant-hash fix.
# It never prepares, submits, launches Cubism, or defines/executes any official class.
set -euo pipefail
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scene_dir="$root/validation/mesh-triangulation-hash"
deps="$scene_dir/deps"
asm_source="${HOME:-/nonexistent}/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/f0ed132a49244b042cd0e15702ab9f2ce3cc8436/asm-9.7.1.jar"
asm_sha256=8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281
# The relocation tool runs at build time only; the agent never sees this namespace at runtime.
gradle_lib="${HOME:-/nonexistent}/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/lib"
tool_asm="$gradle_lib/asm-9.7.jar"
tool_asm_commons="$gradle_lib/asm-commons-9.7.jar"
tool_asm_tree="$gradle_lib/asm-tree-9.7.jar"
shaded_prefix=dev/turboism/validation/meshhash/shaded/asm97

fail() {
  printf 'mesh-hash build: %s\n' "$*" >&2
  exit 1
}

[[ -f "$asm_source" ]] || fail "ASM 9.7.1 not found at $asm_source"
[[ "$(sha256sum "$asm_source" | awk '{print $1}')" == "$asm_sha256" ]] \
  || fail 'ASM 9.7.1 hash mismatch'

mkdir -p "$deps" "$root/build/mesh-triangulation-hash"
cp "$asm_source" "$deps/asm-9.7.1.jar"
work="$(mktemp -d "$root/build/mesh-triangulation-hash/compile.XXXXXX")"

classes="$work/classes"
fixture_classes="$work/fixture"
selfcheck_classes="$work/selfcheck"
mkdir -p "$classes" "$fixture_classes" "$selfcheck_classes"

mapfile -t sources < <(find "$scene_dir/src" -name '*.java' | sort)
[[ "${#sources[@]}" -gt 0 ]] || fail 'no sources found'

javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$deps/asm-9.7.1.jar" -d "$classes" "${sources[@]}"

# Compile the fixture twice: once into a standalone directory the self-check can read as raw bytes.
fixture_sources=()
for file in "$scene_dir"/src/dev/turboism/validation/meshhash/fixture/*.java; do
  fixture_sources+=("$file")
done
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$fixture_classes" "${fixture_sources[@]}"

javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$classes:$deps/asm-9.7.1.jar" -d "$selfcheck_classes" "${sources[@]}"

# Input for the reference-rewrite pass that produces the self-contained agent jar.
jar --create --file "$work/slice-classes.jar" -C "$classes" .

main_class=dev.turboism.validation.meshhash.MeshHashSelfCheck
selfcheck_log="$work/selfcheck.log"
# The bucket-layout evidence requires reading HashMap internals; this is a validation harness only.
if ! java -Xverify:all --add-opens java.base/java.util=ALL-UNNAMED \
    -cp "$selfcheck_classes:$fixture_classes:$deps/asm-9.7.1.jar" \
    "$main_class" "$fixture_classes" > "$selfcheck_log" 2>&1; then
  cat "$selfcheck_log" >&2
  fail 'self-check failed'
fi
cat "$selfcheck_log"

grep -q '^MESH_HASH_SELFCHECK PASS' "$selfcheck_log" || fail 'self-check did not report PASS'
grep -q 'officialClassLoaded=false' "$selfcheck_log" || fail 'self-check did not disclaim official classes'

# Read-only verification of the official class shape; never defines or executes official bytes.
official_jar="${TURBOISM_MESH_HASH_OFFICIAL_JAR:-$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03/app/lib/Live2D_Cubism.jar}"
official_log="$work/official.log"
if [[ -f "$official_jar" ]]; then
  if java -cp "$selfcheck_classes:$fixture_classes:$deps/asm-9.7.1.jar" \
      dev.turboism.validation.meshhash.OfficialClassProbe "$official_jar" \
      > "$official_log" 2>&1; then
    cat "$official_log"
    grep -q '^MESH_TRIANGULATION_HASH_OFFICIAL_PROBE PASS' "$official_log" \
      || fail 'official probe did not report PASS'
  else
    cat "$official_log" >&2
    fail 'official probe was not applicable to this build'
  fi
else
  printf 'officialProbe=skipped jar-not-found\n'
fi

# --- self-contained agent jar -------------------------------------------------
# The agent carries its own bytecode library under a private prefix, so it never exposes or
# depends on the original ASM namespace inside the host JVM.
[[ -f "$tool_asm" && -f "$tool_asm_commons" && -f "$tool_asm_tree" ]] \
  || fail 'ASM 9.7 build-time tooling is missing from the Gradle distribution'

tool_classes="$work/tool"
mkdir -p "$tool_classes"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$tool_asm:$tool_asm_commons:$tool_asm_tree" -d "$tool_classes" \
  "$scene_dir/tools/dev/turboism/validation/meshhash/tools/RelocateJar.java"

shaded_dir="$work/shaded"
mkdir -p "$shaded_dir"
java -cp "$tool_classes:$tool_asm:$tool_asm_commons:$tool_asm_tree" \
  dev.turboism.validation.meshhash.tools.RelocateJar \
  "$deps/asm-9.7.1.jar" "$shaded_dir/asm-relocated.jar" \
  org/objectweb/asm "$shaded_prefix"

relocated_slice="$work/relocated-slice"
mkdir -p "$relocated_slice"
java -cp "$tool_classes:$tool_asm:$tool_asm_commons:$tool_asm_tree" \
  dev.turboism.validation.meshhash.tools.RelocateJar \
  "$work/slice-classes.jar" "$relocated_slice/slice-relocated.jar" \
  org/objectweb/asm "$shaded_prefix"

agent_dir="$work/agent"
mkdir -p "$agent_dir"
( cd "$agent_dir" && jar xf "$shaded_dir/asm-relocated.jar" && jar xf "$relocated_slice/slice-relocated.jar" )
# The agent jar must not shadow the harness/tool classes that the build runs from a classpath:
# an agent jar is appended to the system classpath, so a duplicate unrelocated copy would win.
find "$agent_dir" -name 'MeshHashSelfCheck*' -delete
find "$agent_dir" -name 'OfficialClassProbe*' -delete
find "$agent_dir" -name 'MeshHashAgentJvmHarness*' -delete

agent_manifest="$work/agent-manifest.mf"
{
  printf 'Manifest-Version: 1.0\n'
  printf 'Premain-Class: dev.turboism.validation.meshhash.MeshHashAgent\n'
  printf 'Implementation-Title: mesh-triangulation-hash validation agent\n'
  printf 'Implementation-Version: 1\n'
} > "$agent_manifest"
agent_jar="$work/mesh-hash-agent.jar"
jar --create --file "$agent_jar" --manifest "$agent_manifest" -C "$agent_dir" .

if jar tf "$agent_jar" | grep -q '^org/objectweb/asm'; then
  fail 'agent jar leaks the original ASM namespace'
fi
if unzip -p "$agent_jar" META-INF/MANIFEST.MF | grep -q 'Class-Path:'; then
  fail 'agent jar must not rely on a manifest Class-Path'
fi
jar tf "$agent_jar" | grep -Fqx "$shaded_prefix/ClassReader.class" \
  || fail 'agent jar is missing its private ASM'
jar tf "$agent_jar" | grep -Fqx 'dev/turboism/validation/meshhash/MeshHashAgent.class' \
  || fail 'agent jar is missing the premain class'
agent_sha256="$(sha256sum "$agent_jar" | awk '{print $1}')"
printf 'agentJar=%s\nagentSha256=%s\nagentAsmPrefix=%s\n' "$agent_jar" "$agent_sha256" "$shaded_prefix"

# --- live-JVM agent check (owner fixtures with the host's exact names) --------
fixture_agent_classes="$work/fixture-agent"
mkdir -p "$fixture_agent_classes"
mapfile -t agent_fixtures < <(find "$scene_dir/fixture-agent" -name '*.java' | sort)
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$fixture_agent_classes" "${agent_fixtures[@]}"

harness_classes="$work/harness"
mkdir -p "$harness_classes"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$harness_classes" \
  "$scene_dir/src/dev/turboism/validation/meshhash/MeshHashAgentJvmHarness.java"

triple_sha256="$(sha256sum \
  "$fixture_agent_classes/com/live2d/graphics3d/editableMesh/triangulation/l.class" | awk '{print $1}')"
harness_cp="$harness_classes:$fixture_agent_classes"
for agent_mode in probe fix; do
  evidence="$work/agent-evidence-$agent_mode.properties"
  rm -f "$evidence"
  java -Xverify:all \
    "-javaagent:$agent_jar" \
    "-Dturboism.validation.meshHash.mode=$agent_mode" \
    "-Dturboism.validation.meshHash.optIn=MESH_HASH_EXPLICIT_OPT_IN" \
    "-Dturboism.validation.meshHash.tripleSha256=$triple_sha256" \
    "-Dturboism.validation.meshHash.output=$evidence" \
    -cp "$harness_cp" dev.turboism.validation.meshhash.MeshHashAgentJvmHarness \
    "$agent_mode" "$evidence" > "$work/agent-$agent_mode.log" 2>&1 \
    || { cat "$work/agent-$agent_mode.log" >&2; fail "agent $agent_mode leg failed"; }
  cat "$work/agent-$agent_mode.log"
  grep -q "^MESH_HASH_AGENT_HARNESS PASS mode=$agent_mode" "$work/agent-$agent_mode.log" \
    || fail "agent $agent_mode leg did not report PASS"
done

# A wrong expected hash must leave the class alone: no patch, no crash.
wrong_evidence="$work/agent-evidence-wronghash.properties"
rm -f "$wrong_evidence"
java -Xverify:all -javaagent:"$agent_jar" \
  -Dturboism.validation.meshHash.mode=fix \
  -Dturboism.validation.meshHash.optIn=MESH_HASH_EXPLICIT_OPT_IN \
  "-Dturboism.validation.meshHash.tripleSha256=$(printf 'a%.0s' {1..64})" \
  "-Dturboism.validation.meshHash.output=$wrong_evidence" \
  -cp "$harness_cp" dev.turboism.validation.meshhash.MeshHashAgentJvmHarness \
  fix "$wrong_evidence" > "$work/agent-wronghash.log" 2>&1 || true
grep -q 'tripleState=HASH_MISMATCH' "$work/agent-wronghash.log" \
  || { cat "$work/agent-wronghash.log" >&2; fail 'identity gate did not stop the patch'; }
echo "MESH_HASH_AGENT_IDENTITY_GATE PASS tripleState=HASH_MISMATCH"

printf 'asmDependency=org.ow2.asm:asm:9.7.1\n'
printf 'asmSha256=%s\n' "$asm_sha256"
printf 'MESH_TRIANGULATION_HASH_BUILD PASS evidence=%s hostExecuted=false officialClassLoaded=false\n' "$work"
