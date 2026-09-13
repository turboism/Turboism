#!/usr/bin/env bash
# Offline build + self-check + official-shape probe + live-JVM equivalence for
# the tile-scratch patch agent. Never launches or modifies Cubism.
set -euo pipefail
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scene_dir="$root/validation/atlas-image-tile-patch"
deps="$scene_dir/deps"
asm_source="${HOME:-/nonexistent}/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/f0ed132a49244b042cd0e15702ab9f2ce3cc8436/asm-9.7.1.jar"
asm_sha256=8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281
gradle_lib="${HOME:-/nonexistent}/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/lib"
tool_asm="$gradle_lib/asm-9.7.jar"
tool_asm_commons="$gradle_lib/asm-commons-9.7.jar"
tool_asm_tree="$gradle_lib/asm-tree-9.7.jar"
shaded_prefix=dev/turboism/validation/tilepatch/shaded/asm97

fail() { printf 'tile-patch build: %s\n' "$*" >&2; exit 1; }

[[ -f "$asm_source" ]] || fail "ASM 9.7.1 not found at $asm_source"
[[ "$(sha256sum "$asm_source" | awk '{print $1}')" == "$asm_sha256" ]] \
  || fail 'ASM 9.7.1 hash mismatch'

mkdir -p "$deps" "$root/build/atlas-image-tile-patch"
cp "$asm_source" "$deps/asm-9.7.1.jar"
work="$(mktemp -d "$root/build/atlas-image-tile-patch/compile.XXXXXX")"

classes="$work/classes"
fixture_classes="$work/fixture"
mkdir -p "$classes" "$fixture_classes"

# Host-name fixture replicas FIRST — the delegate compiles against them but they
# are never shipped in the agent jar (host provides the real ones at runtime).
mapfile -t fixture_sources < <(find "$scene_dir/fixture-agent" -name '*.java' | sort)
[[ "${#fixture_sources[@]}" -gt 0 ]] || fail 'no fixture sources'
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$fixture_classes" "${fixture_sources[@]}"

mapfile -t sources < <(find "$scene_dir/src" -name '*.java' | sort)
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$fixture_classes:$deps/asm-9.7.1.jar" -d "$classes" "${sources[@]}"

# --- self-check (offline, fixture bytes only) ----------------------------------
java -Xverify:all \
  -cp "$classes:$fixture_classes:$deps/asm-9.7.1.jar" \
  dev.turboism.validation.tilepatch.TilePatchSelfCheck \
  > "$work/selfcheck.log" 2>&1 || { cat "$work/selfcheck.log" >&2; fail 'self-check failed'; }
cat "$work/selfcheck.log"
grep -q '^SELFCHECK PASS' "$work/selfcheck.log" || fail 'self-check did not PASS'

# --- official JAR shape probe (read-only) --------------------------------------
official_jar="${TURBOISM_TILE_PATCH_OFFICIAL_JAR:-$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03/app/lib/Live2D_Cubism.jar}"
if [[ -f "$official_jar" ]]; then
  java -cp "$classes:$deps/asm-9.7.1.jar" \
    dev.turboism.validation.tilepatch.TilePatchOfficialProbe "$official_jar" \
    > "$work/official.log" 2>&1 || { cat "$work/official.log" >&2; fail 'official probe failed'; }
  cat "$work/official.log"
  grep -q '^PROBE PASS' "$work/official.log" || fail 'official probe did not PASS'
else
  printf 'officialProbe=skipped jar-not-found\n'
fi
# Same probe against the 5.2.03 owner (com/live2d/util/e/g) when that JAR is present.
official_jar_5203="${TURBOISM_TILE_PATCH_OFFICIAL_JAR_5203:-$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.2/app/lib/Live2D_Cubism.jar}"
if [[ -f "$official_jar_5203" ]]; then
  java -cp "$classes:$deps/asm-9.7.1.jar" \
    dev.turboism.validation.tilepatch.TilePatchOfficialProbe "$official_jar_5203" 5203 \
    > "$work/official-5203.log" 2>&1 || { cat "$work/official-5203.log" >&2; fail 'official 5203 probe failed'; }
  cat "$work/official-5203.log"
  grep -q '^PROBE PASS' "$work/official-5203.log" || fail 'official 5203 probe did not PASS'
else
  printf 'officialProbe5203=skipped jar-not-found\n'
fi

# --- live-JVM equivalence harness (fixture originals vs patched) ---------------
harness_cp="$classes:$fixture_classes:$deps/asm-9.7.1.jar"
java -Djava.awt.headless=true -Xverify:all -Xmx4g \
  -cp "$harness_cp" dev.turboism.validation.tilepatch.TilePatchHarness manual 400 \
  > "$work/harness.log" 2>&1 || { cat "$work/harness.log" >&2; fail 'live harness failed'; }
cat "$work/harness.log"
grep -q '^LIVE PASS' "$work/harness.log" || fail 'live harness did not PASS'

# --- self-contained agent jar ---------------------------------------------------
[[ -f "$tool_asm" && -f "$tool_asm_commons" && -f "$tool_asm_tree" ]] \
  || fail 'ASM 9.7 build-time tooling missing'

tool_classes="$work/tool"
mkdir -p "$tool_classes"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$tool_asm:$tool_asm_commons:$tool_asm_tree" -d "$tool_classes" \
  "$root/validation/atlas-image-timing/tools/dev/turboism/validation/atlastiming/tools/RelocateJar.java"

jar --create --file "$work/slice-classes.jar" -C "$classes" .

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
find "$agent_dir" -name 'TilePatchSelfCheck*' -delete
find "$agent_dir" -name 'TilePatchOfficialProbe*' -delete
find "$agent_dir" -name 'TilePatchHarness*' -delete

agent_manifest="$work/agent-manifest.mf"
{
  printf 'Manifest-Version: 1.0\n'
  printf 'Premain-Class: dev.turboism.validation.tilepatch.TilePatchAgent\n'
  printf 'Implementation-Title: atlas-image tile-patch validation agent\n'
  printf 'Implementation-Version: 1\n'
} > "$agent_manifest"
agent_jar="$work/tile-patch-agent.jar"
jar --create --file "$agent_jar" --manifest "$agent_manifest" -C "$agent_dir" .

jar tf "$agent_jar" | grep -q '^org/objectweb/asm' \
  && fail 'agent jar leaks original ASM namespace'
jar tf "$agent_jar" | grep -Fqx "$shaded_prefix/ClassReader.class" \
  || fail 'missing relocated ASM'
jar tf "$agent_jar" | grep -Fqx 'dev/turboism/validation/tilepatch/TilePatchAgent.class' \
  || fail 'missing premain class'
jar tf "$agent_jar" | grep -Fqx 'dev/turboism/validation/tilepatch/TiledDrawDelegate.class' \
  || fail 'missing delegate class'

# --- agent leg: opt-in gate ------------------------------------------------------
blocked_dir="$work/agent-evidence-blocked"
rm -rf "$blocked_dir"
java -Djava.awt.headless=true -Xverify:all -javaagent:"$agent_jar" \
  "-Dturboism.validation.tilePatch.output=$blocked_dir" \
  -cp "$harness_cp" dev.turboism.validation.tilepatch.TilePatchHarness manual 5 \
  > "$work/agent-blocked.log" 2>&1 || true
if [[ -f "$blocked_dir/tilepatch-summary.properties" ]]; then
  cat "$work/agent-blocked.log" >&2
  fail 'opt-in gate did not stop the agent'
fi
echo "TILE_PATCH_OPTIN_GATE PASS"

# --- agent leg: live patch under -javaagent --------------------------------------
agent_dir_evidence="$work/agent-evidence"
rm -rf "$agent_dir_evidence"
java -Djava.awt.headless=true -Xverify:all -Xmx4g -javaagent:"$agent_jar" \
  "-Dturboism.validation.tilePatch.optIn=TILE_PATCH_EXPLICIT_OPT_IN" \
  "-Dturboism.validation.tilePatch.output=$agent_dir_evidence" \
  -cp "$harness_cp" dev.turboism.validation.tilepatch.TilePatchHarness agent 200 \
  > "$work/agent.log" 2>&1 || { cat "$work/agent.log" >&2; fail 'agent leg failed'; }
cat "$work/agent.log"
grep -q '^LIVE PASS' "$work/agent.log" || fail 'agent leg did not PASS'
[[ -f "$agent_dir_evidence/tilepatch-summary.properties" ]] \
  || fail 'agent did not write summary'

agent_sha256="$(sha256sum "$agent_jar" | awk '{print $1}')"
printf 'agentJar=%s\nagentSha256=%s\n' "$agent_jar" "$agent_sha256"
printf 'asmDependency=org.ow2.asm:asm:9.7.1\nasmSha256=%s\n' "$asm_sha256"
printf 'TILE_PATCH_BUILD PASS evidence=%s hostExecuted=false\n' "$work"
