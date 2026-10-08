#!/usr/bin/env bash
# Offline-only build for the T029-TRIAB dump+weave agent. Never prepares, submits, launches
# the host, or defines/executes any official class. The official jar is used as a read-only
# javap/compile-classpath source only.
set -euo pipefail
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scene_dir="$root/validation/triangulation-weave-ab"
km_dir="$root/validation/triangulation-k-membership"
dw_dir="$root/validation/triangulation-dweave"
tli_dir="$root/validation/triangulation-tlindex"
deps="$scene_dir/deps"
asm_source="${TURBOISM_ASM_JAR:-${HOME:-/nonexistent}/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/f0ed132a49244b042cd0e15702ab9f2ce3cc8436/asm-9.7.1.jar}"
asm_sha256=8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281
stdlib="${TURBOISM_KOTLIN_STDLIB:-$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03/app/lib/kotlin-stdlib-1.7.21.jar}"
stdlib_sha256=d46a9d773ffb9dee4ff1a748ac845dc8e50005c589302951760a2b5187bddd19
official_jar="${TURBOISM_TRI_WEAVE_OFFICIAL_JAR:-$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03/app/lib/Live2D_Cubism.jar}"
official_jar_sha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166
gradle_lib="${HOME:-/nonexistent}/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/lib"
tool_asm="$gradle_lib/asm-9.7.jar"
tool_asm_commons="$gradle_lib/asm-commons-9.7.jar"
tool_asm_tree="$gradle_lib/asm-tree-9.7.jar"
shaded_prefix=dev/turboism/validation/triweave/shaded/asm97

fail() { printf 'tri-weave-ab build: %s\n' "$*" >&2; exit 1; }

# --- unified queue must be idle before building (freeze requirement) ---
QUEUE_PY="${TURBOISM_HOST_VALIDATION_PY:-$root/scripts/preview/host_validation.py}"
if [[ -f "$QUEUE_PY" ]]; then
  python3 - "$QUEUE_PY" <<'PYEOF'
import json, subprocess, sys, time
script = sys.argv[1]
# busy = jobs that can still consume the host: queued or in an ACTIVE
# (starting/running/cleaning/recovering/quarantined) state. Preserved
# 'abandoned' orphans are evidence, not load - they never resume.
busy_states = {"queued", "starting", "running", "cleaning", "recovering", "quarantined"}
while True:
    try:
        out = subprocess.run(["python3", script, "status", "--json"],
                             capture_output=True, text=True, timeout=120)
        doc = json.loads(out.stdout)
        busy = [j for j in doc.get("jobs", []) if j.get("state") in busy_states]
    except Exception as e:
        print(f"queue status probe failed ({e}); treating as busy, retrying", flush=True)
        time.sleep(15); continue
    if not busy:
        print("queue idle - proceeding", flush=True)
        break
    print(f"queue busy ({len(busy)} active job(s), first={busy[0].get('job_id','?')}:{busy[0].get('state')}); waiting", flush=True)
    time.sleep(15)
PYEOF
else
  echo "queue script not found at $QUEUE_PY; skipping idle check (documented)"
fi

[[ -f "$asm_source" && ! -L "$asm_source" ]] || fail "ASM 9.7.1 not found at $asm_source"
[[ "$(sha256sum "$asm_source" | awk '{print $1}')" == "$asm_sha256" ]] \
  || fail "ASM jar digest mismatch"
[[ -f "$stdlib" && ! -L "$stdlib" ]] || fail "kotlin-stdlib not found at $stdlib"
[[ "$(sha256sum "$stdlib" | awk '{print $1}')" == "$stdlib_sha256" ]] \
  || fail "kotlin-stdlib SHA-256 mismatch (expected reviewed 1.7.21 jar)"
[[ -f "$official_jar" && ! -L "$official_jar" ]] \
  || fail "official jar required for read-only compile classpath: $official_jar"
[[ "$(sha256sum "$official_jar" | awk '{print $1}')" == "$official_jar_sha256" ]] \
  || fail "official jar SHA-256 mismatch (expected reviewed 5303 jar)"
mkdir -p "$deps"
cp "$asm_source" "$deps/asm-9.7.1.jar"

work="$(mktemp -d "$root/build/tri-weave-ab.XXXXXX")"
printf 'work=%s\n' "$work"
mkdir -p "$work/classes" "$work/shadow" "$work/shadow-nohelper" "$work/badshape" \
  "$work/badreturn" "$work/badsite" "$work/tlbadshape" "$work/tlifx" \
  "$work/tlifx-noh" "$work/selfcheck"

# --- agent classes --------------------------------------------------------------
# group 1: weavers + agent plumbing (no official-type references). Both weavers are
# compiled from their reviewed sources — the single shared implementations, not copies:
# KWEAVE Weave.java (membership) and DWEAVE Weave.java (allocation-site) + MatchList
# (JDK-only helper woven into h.c()).
src="$scene_dir/src/dev/turboism/validation/triweave"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$deps/asm-9.7.1.jar" -d "$work/classes" \
  "$km_dir/src/dev/turboism/validation/kmembership/Weave.java" \
  "$dw_dir/src/dev/turboism/validation/dweave/Weave.java" \
  "$dw_dir/src/dev/turboism/validation/dweave/MatchList.java" \
  "$tli_dir/src/dev/turboism/validation/tlindex/TliWeave.java" \
  "$tli_dir/src/dev/turboism/validation/tlindex/Bridge.java" \
  "$src/WeaveAbAgent.java" "$src/WeaveAbConfig.java" "$src/AbTransformer.java" \
  "$src/CaptureWeave.java" "$src/Sink.java" "$src/Counters.java" \
  "$src/OfficialShapeProbe.java" \
  || fail 'agent classes (group 1) did not compile'
# group 2: official-type Helper + Capture — official jar on the compile classpath ONLY.
# No -Werror here: the obfuscated jar's Kotlin metadata emits an unavoidable
# "unknown enum constant" warning against javac.
javac --release 17 -proc:none -implicit:none -Xlint:all \
  -cp "$work/classes:$official_jar:$stdlib" -d "$work/classes" \
  "$src/Helper.java" "$src/Capture.java" \
  || fail 'official-typed helper/capture did not compile'

# --- shadow fixture (official byte shape, own names; test-time only, never in jar) ------
# ShadowCapture imports the agent's Sink — work/classes is compile classpath only; only
# shadow .class files land in work/shadow.
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$stdlib:$work/classes" -d "$work/shadow" \
  $(find "$scene_dir/shadow" -name '*.java' | sort) \
  "$tli_dir/src/dev/turboism/validation/tlindex/own/OwnTri.java" \
  || fail 'shadow fixture did not compile'
# tlindex shape-mutant fixture (same binary name, extra LinkedHashSet.add site)
# -> separate dir; the scenario merges its single TList class over the fixture.
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$stdlib" -d "$work/tlbadshape" \
  "$scene_dir/shadow-tlbadshape/dev/turboism/validation/tlindex/own/OwnTri.java" \
  || fail 'tl badshape fixture did not compile'
# tlindex fixture-side helper classpath: the REAL Bridge (single shared
# implementation), compiled standalone so the FixtureLoader resolves it via a
# second URL; the no-helper variant deletes it for the INVALID leg.
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -d "$work/tlifx" "$tli_dir/src/dev/turboism/validation/tlindex/Bridge.java" \
  || fail 'tlfx helper classes did not compile'
cp -a "$work/tlifx/." "$work/tlifx-noh/"
find "$work/tlifx-noh" -name 'Bridge*.class' -delete
cp -a "$work/shadow/." "$work/shadow-nohelper/"
rm -f "$work/shadow-nohelper/dev/turboism/validation/triweave/shadow/ShadowHelper.class" \
      "$work/shadow-nohelper/dev/turboism/validation/triweave/shadow/ShadowHelper\$Box.class" \
      "$work/shadow-nohelper/dev/turboism/validation/triweave/shadow/ShadowMatchList.class"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$work/shadow:$stdlib" -d "$work/badshape" \
  "$scene_dir/shadow-badshape/dev/turboism/validation/triweave/shadow/ShadowTriangleList.java" \
  || fail 'badshape fixture did not compile'
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$work/shadow:$stdlib" -d "$work/badreturn" \
  "$scene_dir/shadow-badreturn/dev/turboism/validation/triweave/shadow/ShadowTriangleList.java" \
  || fail 'badreturn fixture did not compile'
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$work/shadow:$stdlib" -d "$work/badsite" \
  "$scene_dir/shadow-badsite/dev/turboism/validation/triweave/shadow/ShadowH.java" \
  || fail 'badsite fixture did not compile'
shadow_sha256="$(sha256sum \
  "$work/shadow/dev/turboism/validation/triweave/shadow/ShadowTriangleList.class" \
  | awk '{print $1}')"
shadow_h_sha256="$(sha256sum \
  "$work/shadow/dev/turboism/validation/triweave/shadow/ShadowH.class" \
  | awk '{print $1}')"
badshape_sha256="$(sha256sum \
  "$work/badshape/dev/turboism/validation/triweave/shadow/ShadowTriangleList.class" \
  | awk '{print $1}')"
badreturn_sha256="$(sha256sum \
  "$work/badreturn/dev/turboism/validation/triweave/shadow/ShadowTriangleList.class" \
  | awk '{print $1}')"
badsite_sha256="$(sha256sum \
  "$work/badsite/dev/turboism/validation/triweave/shadow/ShadowH.class" \
  | awk '{print $1}')"
tl_own_sha256="$(sha256sum \
  "$work/shadow/dev/turboism/validation/tlindex/own/OwnTri\$TList.class" \
  | awk '{print $1}')"
tl_badsha256="$(sha256sum \
  "$work/tlbadshape/dev/turboism/validation/tlindex/own/OwnTri\$TList.class" \
  | awk '{print $1}')"
printf 'shadowClassSha256=%s\nshadowHClassSha256=%s\nbadshapeClassSha256=%s\nbadreturnClassSha256=%s\nbadsiteClassSha256=%s\ntlOwnClassSha256=%s\ntlBadshapeClassSha256=%s\n' \
  "$shadow_sha256" "$shadow_h_sha256" "$badshape_sha256" "$badreturn_sha256" "$badsite_sha256" \
  "$tl_own_sha256" "$tl_badsha256"

# --- selfcheck harness --------------------------------------------------------------
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$work/classes:$work/shadow:$deps/asm-9.7.1.jar" -d "$work/selfcheck" \
  $(find "$scene_dir/selfcheck" -name '*.java' | sort) \
  "$root/validation/shared/src/dev/turboism/validation/shared/fixture/FixtureLoader.java" \
  "$root/validation/shared/src/dev/turboism/validation/shared/fixture/CodeSourceUrl.java" \
  || fail 'selfcheck did not compile'

# --- private ASM shading ------------------------------------------------------------
[[ -f "$tool_asm" && -f "$tool_asm_commons" && -f "$tool_asm_tree" ]] \
  || fail 'ASM 9.7 build-time tooling missing from the Gradle distribution'
mkdir -p "$work/tool"
javac --release 17 -proc:none -implicit:none -Xlint:all -Werror \
  -cp "$tool_asm:$tool_asm_commons:$tool_asm_tree" -d "$work/tool" \
  "$root/validation/shared/src/dev/turboism/validation/shared/tools/RelocateJar.java" \
  || fail 'relocation tool did not compile'

jar --create --file "$work/agent-classes.jar" -C "$work/classes" .
mkdir -p "$work/shaded" "$work/relocated"
java -cp "$work/tool:$tool_asm:$tool_asm_commons:$tool_asm_tree" \
  dev.turboism.validation.shared.tools.RelocateJar \
  "$deps/asm-9.7.1.jar" "$work/shaded/asm-relocated.jar" \
  org/objectweb/asm "$shaded_prefix" || fail 'asm relocation failed'
java -cp "$work/tool:$tool_asm:$tool_asm_commons:$tool_asm_tree" \
  dev.turboism.validation.shared.tools.RelocateJar \
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
  printf 'Premain-Class: dev.turboism.validation.triweave.WeaveAbAgent\n'
  printf 'Agent-Class: dev.turboism.validation.triweave.WeaveAbAgent\n'
  printf 'Implementation-Title: triangulation-weave-ab validation agent\n'
  printf 'Implementation-Version: 1\n'
} > "$manifest"

agent_jar="$work/tri-weave-agent.jar"
jar --create --file "$agent_jar" --manifest "$manifest" -C "$agent_dir" .

# no-helper variant: identical jar minus the official-type Helper (missing-helper control).
noh_jar="$work/tri-weave-agent-no-helper.jar"
mkdir -p "$work/agent-no-helper" && cp -a "$agent_dir/." "$work/agent-no-helper/"
find "$work/agent-no-helper" -path '*triweave/Helper*' -delete
find "$work/agent-no-helper" -path '*tlindex/Bridge*' -delete
jar --create --file "$noh_jar" --manifest "$manifest" -C "$work/agent-no-helper" .

jar_list="$work/agent-list.txt"
jar tf "$agent_jar" > "$jar_list"
jar tf "$noh_jar" > "$work/noh-list.txt"
grep -q '^org/objectweb/asm' "$jar_list" && fail 'agent jar leaks the original ASM namespace'
unzip -p "$agent_jar" META-INF/MANIFEST.MF | grep -q 'Class-Path:' \
  && fail 'agent jar must not rely on a manifest Class-Path'
grep -Fqx "$shaded_prefix/ClassReader.class" "$jar_list" \
  || fail 'agent jar is missing its private ASM'
grep -Fqx 'dev/turboism/validation/triweave/WeaveAbAgent.class' "$jar_list" \
  || fail 'agent jar is missing the premain class'
grep -Fqx 'dev/turboism/validation/kmembership/Weave.class' "$jar_list" \
  || fail 'agent jar is missing the shared weaver'
grep -Fqx 'dev/turboism/validation/dweave/Weave.class' "$jar_list" \
  || fail 'agent jar is missing the dweave weaver'
grep -Fqx 'dev/turboism/validation/dweave/MatchList.class' "$jar_list" \
  || fail 'agent jar is missing the MatchList helper'
grep -q 'dweave/InsnDiff' "$jar_list" \
  && fail 'agent jar must not contain the InsnDiff tool'
grep -q 'triweave/shadow' "$jar_list" && fail 'agent jar must not contain shadow classes'
grep -q '^com/live2d/' "$jar_list" && fail 'agent jar must not contain official classes'
grep -q 'triweave/Helper' "$work/noh-list.txt" \
  && fail 'no-helper jar still contains Helper'

printf 'agentJar=%s\nagentSha256=%s\nnoHelperJar=%s\nnoHelperSha256=%s\n' \
  "$agent_jar" "$(sha256sum "$agent_jar" | awk '{print $1}')" \
  "$noh_jar" "$(sha256sum "$noh_jar" | awk '{print $1}')"
printf 'selfcheckClasses=%s\nshadowClasses=%s\nnohelperClasses=%s\nbadshapeClasses=%s\nbadreturnClasses=%s\nbadsiteClasses=%s\ntlbadshapeClasses=%s\ntlfxClasses=%s\ntlfxNohelperClasses=%s\nagentClasses=%s\nstdlibJar=%s\nworkDir=%s\n' \
  "$work/selfcheck" "$work/shadow" "$work/shadow-nohelper" "$work/badshape" \
  "$work/badreturn" "$work/badsite" "$work/tlbadshape" "$work/tlifx" "$work/tlifx-noh" \
  "$work/classes" "$stdlib" "$work"
printf 'TRI_WEAVE_AB_BUILD PASS shadowSha256=%s\n' "$shadow_sha256"
