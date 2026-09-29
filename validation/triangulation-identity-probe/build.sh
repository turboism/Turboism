#!/usr/bin/env bash
# Offline-only build for the T029-IDENTITY probe agent. Never prepares, submits, launches the
# host, or defines/executes any official class.
set -euo pipefail
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scene_dir="$root/validation/triangulation-identity-probe"
deps="$scene_dir/deps"
asm_source="${HOME:-/nonexistent}/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/f0ed132a49244b042cd0e15702ab9f2ce3cc8436/asm-9.7.1.jar"
asm_sha256=8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281
gradle_lib="${HOME:-/nonexistent}/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/lib"
tool_asm="$gradle_lib/asm-9.7.jar"
tool_asm_commons="$gradle_lib/asm-commons-9.7.jar"
tool_asm_tree="$gradle_lib/asm-tree-9.7.jar"
shaded_prefix=dev/turboism/validation/triprobe/shaded/asm97

fail() { printf 'tri-identity-probe build: %s\n' "$*" >&2; exit 1; }

[[ -f "$asm_source" ]] || fail "ASM 9.7.1 not found at $asm_source"
[[ "$(sha256sum "$asm_source" | awk '{print $1}')" == "$asm_sha256" ]] \
  || fail "ASM jar digest mismatch"
mkdir -p "$deps"
cp "$asm_source" "$deps/asm-9.7.1.jar"

work="$(mktemp -d "$root/build/tri-probe.XXXXXX")"
printf 'work=%s\n' "$work"
mkdir -p "$work/classes" "$work/fixture" "$work/fixture-badshape" "$work/selfcheck"

# --- agent classes ------------------------------------------------------------
mapfile -t sources < <(find "$scene_dir/src" -name '*.java' | sort)
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$deps/asm-9.7.1.jar" -d "$work/classes" "${sources[@]}" \
  || fail 'agent classes did not compile'

# --- fixture classes (own same-named stand-ins; never executed as official) ----
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$work/fixture" $(find "$scene_dir/fixture" -name '*.java' | sort) \
  || fail 'fixture classes did not compile'
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$work/fixture-badshape" \
  $(find "$scene_dir/fixture-badshape" -name '*.java' | sort) \
  || fail 'badshape fixture did not compile'
fixture_sha256="$(sha256sum \
  "$work/fixture/com/live2d/graphics3d/editableMesh/triangulation/TriangleList.class" \
  | awk '{print $1}')"
printf 'fixtureClassSha256=%s\n' "$fixture_sha256"

# --- selfcheck harness ----------------------------------------------------------
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$work/classes:$work/fixture:$deps/asm-9.7.1.jar" -d "$work/selfcheck" \
  $(find "$scene_dir/selfcheck" -name '*.java' | sort) \
  || fail 'selfcheck did not compile'

# --- private ASM shading ---------------------------------------------------------
[[ -f "$tool_asm" && -f "$tool_asm_commons" && -f "$tool_asm_tree" ]] \
  || fail 'ASM 9.7 build-time tooling missing from the Gradle distribution'
mkdir -p "$work/tool"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$tool_asm:$tool_asm_commons:$tool_asm_tree" -d "$work/tool" \
  "$scene_dir/tools/dev/turboism/validation/triprobe/tools/RelocateJar.java" \
  || fail 'relocation tool did not compile'

jar --create --file "$work/agent-classes.jar" -C "$work/classes" .
mkdir -p "$work/shaded" "$work/relocated"
java -cp "$work/tool:$tool_asm:$tool_asm_commons:$tool_asm_tree" \
  dev.turboism.validation.triprobe.tools.RelocateJar \
  "$deps/asm-9.7.1.jar" "$work/shaded/asm-relocated.jar" \
  org/objectweb/asm "$shaded_prefix" || fail 'asm relocation failed'
java -cp "$work/tool:$tool_asm:$tool_asm_commons:$tool_asm_tree" \
  dev.turboism.validation.triprobe.tools.RelocateJar \
  "$work/agent-classes.jar" "$work/relocated/agent-relocated.jar" \
  org/objectweb/asm "$shaded_prefix" || fail 'agent relocation failed'

agent_dir="$work/agent"
mkdir -p "$agent_dir"
( cd "$agent_dir" && jar xf "$work/shaded/asm-relocated.jar" \
  && jar xf "$work/relocated/agent-relocated.jar" )
# OfficialShapeProbe is a classpath-side tool, not host payload.
find "$agent_dir" -name 'OfficialShapeProbe*' -delete

manifest="$work/manifest.mf"
{
  printf 'Manifest-Version: 1.0\n'
  printf 'Premain-Class: dev.turboism.validation.triprobe.IdentityProbeAgent\n'
  printf 'Agent-Class: dev.turboism.validation.triprobe.IdentityProbeAgent\n'
  printf 'Implementation-Title: triangulation-identity-probe validation agent\n'
  printf 'Implementation-Version: 1\n'
} > "$manifest"

agent_jar="$work/tri-probe-agent.jar"
jar --create --file "$agent_jar" --manifest "$manifest" -C "$agent_dir" .

# no-helper variant: identical jar minus the Probe helper (missing-helper negative control).
noh_jar="$work/tri-probe-agent-no-helper.jar"
mkdir -p "$work/agent-no-helper" && cp -a "$agent_dir/." "$work/agent-no-helper/"
find "$work/agent-no-helper" -name 'Probe.class' -delete
jar --create --file "$noh_jar" --manifest "$manifest" -C "$work/agent-no-helper" .

jar_list="$work/agent-list.txt"
jar tf "$agent_jar" > "$jar_list"
jar tf "$noh_jar" > "$work/noh-list.txt"
grep -q '^org/objectweb/asm' "$jar_list" && fail 'agent jar leaks the original ASM namespace'
unzip -p "$agent_jar" META-INF/MANIFEST.MF | grep -q 'Class-Path:' \
  && fail 'agent jar must not rely on a manifest Class-Path'
grep -Fqx "$shaded_prefix/ClassReader.class" "$jar_list" \
  || fail 'agent jar is missing its private ASM'
grep -Fqx 'dev/turboism/validation/triprobe/IdentityProbeAgent.class' "$jar_list" \
  || fail 'agent jar is missing the premain class'
grep -Fqx 'dev/turboism/validation/triprobe/Probe.class' "$work/noh-list.txt" \
  && fail 'no-helper jar still contains Probe'

printf 'agentJar=%s\nagentSha256=%s\nnoHelperJar=%s\nnoHelperSha256=%s\n' \
  "$agent_jar" "$(sha256sum "$agent_jar" | awk '{print $1}')" \
  "$noh_jar" "$(sha256sum "$noh_jar" | awk '{print $1}')"
printf 'selfcheckClasses=%s\nfixtureClasses=%s\nbadshapeClasses=%s\nworkDir=%s\n' \
  "$work/selfcheck" "$work/fixture" "$work/fixture-badshape" "$work"
printf 'TRI_PROBE_BUILD PASS fixtureSha256=%s\n' "$fixture_sha256"
