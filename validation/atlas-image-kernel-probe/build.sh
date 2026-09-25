#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/atlas-image-kernel-probe.XXXXXX")"
SNAPSHOT_DIR="$RUN_DIR/input"
CLASS_DIR="$RUN_DIR/classes"
LOG_FILE="$RUN_DIR/offline.log"
HASH_FILE="$RUN_DIR/sha256.txt"
SOURCE_REL="src/dev/turboism/validation/atlasimage/AtlasImageKernelProbe.java"
ASM_JAR_REL="deps/asm-9.7.1.jar"
ASM_POM_REL="deps/asm-9.7.1.pom"
SHADED_ASM_REL="deps/asm-9.7.1-shaded.jar"
TOOL_SOURCE_REL="tools/AsmNamespaceRelocator.java"
ASM_SOURCE="${HOME:-/nonexistent}/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/f0ed132a49244b042cd0e15702ab9f2ce3cc8436/asm-9.7.1.jar"
ASM_POM_SOURCE="${HOME:-/nonexistent}/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/381a4b6892969305d88ee13c53e88eabff82d382/asm-9.7.1.pom"
ASM_SHA256="8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281"
SHADED_PREFIX='dev/turboism/validation/atlasimage/shaded/asm97'
TOOL_CLASS_DIR="$RUN_DIR/tool-classes"
SHADED_UNPACK_DIR="$RUN_DIR/shaded-asm"
AGENT_JAR="$RUN_DIR/t039-shadow-agent.jar"
MISSING_AGENT_JAR="$RUN_DIR/t039-shadow-agent-missing-asm.jar"
WRONG_AGENT_JAR="$RUN_DIR/t039-shadow-agent-wrong-asm.jar"
FIXTURE_JAR="$RUN_DIR/t039-owned-target.jar"
FIXTURE_META="$RUN_DIR/t039-owned-target.properties"
AGENT_MANIFEST="$RUN_DIR/t039-agent-manifest.mf"
T039_INVENTORY="$RUN_DIR/t039-inventory.txt"

mapfile -t JAVA_RELS < <(
    cd "$SCRIPT_DIR"
    find src -type f -name '*.java' -print | LC_ALL=C sort
)
INPUT_RELS=(
    "build.sh"
    "parallel-build-regression.sh"
    "README.md"
    "${JAVA_RELS[@]}"
    "$TOOL_SOURCE_REL"
    "$ASM_JAR_REL"
    "$ASM_POM_REL"
    "$SHADED_ASM_REL"
)

hash_snapshot() {
    local relative_path
    for relative_path in "${INPUT_RELS[@]}"; do
        [[ -f "$SNAPSHOT_DIR/$relative_path" ]] || return 1
    done
    (
        cd "$SNAPSHOT_DIR"
        sha256sum "${INPUT_RELS[@]}" | LC_ALL=C sort
    ) > "$HASH_FILE"
}

verify_snapshot_hash() {
    [[ -f "$HASH_FILE" ]] || return 1
    (
        cd "$SNAPSHOT_DIR"
        sha256sum -c "$HASH_FILE"
    )
}

LOG_READY=0
finalize() {
    local status=$?
    local hash_status=0
    trap - EXIT
    set +e

    if [[ ! -f "$HASH_FILE" ]]; then
        if hash_snapshot; then
            printf '%s\n' 'snapshotHashCreatedAtExit=PASS'
        else
            printf '%s\n' 'snapshotHashCreatedAtExit=UNAVAILABLE'
            hash_status=1
        fi
    fi
    if [[ -f "$HASH_FILE" ]]; then
        if verify_snapshot_hash; then
            printf '%s\n' 'snapshotHashRecheck=PASS'
        else
            hash_status=$?
            printf 'snapshotHashRecheckExit=%d\n' "$hash_status"
        fi
    else
        hash_status=1
        printf '%s\n' 'snapshotHashRecheck=UNAVAILABLE'
    fi
    if ((status == 0 && hash_status != 0)); then
        status=$hash_status
    fi

    printf 'RUN_DIR=%s\n' "$RUN_DIR"
    printf 'SNAPSHOT_DIR=%s\n' "$SNAPSHOT_DIR"
    printf 'CLASS_DIR=%s\n' "$CLASS_DIR"
    printf 'LOG_FILE=%s\n' "$LOG_FILE"
    printf 'HASH_FILE=%s\n' "$HASH_FILE"
    printf 'exitStatus=%d\n' "$status"
printf 'T039_AGENT_JAR=%s\n' "$AGENT_JAR"
printf 'T039_FIXTURE_JAR=%s\n' "$FIXTURE_JAR"
printf 'T039_FIXTURE_META=%s\n' "$FIXTURE_META"
printf 'T039_AGENT_MANIFEST=%s\n' "$AGENT_MANIFEST"
printf 'T039_INVENTORY=%s\n' "$T039_INVENTORY"
    if [[ -f "$LOG_FILE" ]]; then
        if ((LOG_READY)); then
            cat "$LOG_FILE" >&3
        else
            cat "$LOG_FILE"
        fi
    fi
    exit "$status"
}

trap finalize EXIT
mkdir -p "$SNAPSHOT_DIR" "$CLASS_DIR"
: > "$LOG_FILE"
exec 3>&1
exec >"$LOG_FILE" 2>&1
LOG_READY=1

printf '%s\n' '== atlas-image-kernel-probe offline build =='
printf 'runDir=%s\n' "$RUN_DIR"
printf 'asmDependency=org.ow2.asm:asm:9.7.1\n'
printf 'asmSource=%s\n' "$ASM_SOURCE"
printf 'asmExpectedSha256=%s\n' "$ASM_SHA256"
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS CLASSPATH JAVA_OPTS JVM_OPTS 2>/dev/null || true
javac -version
java -version

[[ -f "$ASM_SOURCE" ]] || {
    printf 'asmDependency=missing\n'
    exit 2
}
actual_asm_sha256="$(sha256sum "$ASM_SOURCE" | awk '{print $1}')"
printf 'asmActualSha256=%s\n' "$actual_asm_sha256"
[[ "$actual_asm_sha256" == "$ASM_SHA256" ]] || {
    printf '%s\n' 'asmDependency=hash-mismatch'
    exit 2
}

printf '%s\n' '== snapshot inputs before compile =='
for relative_path in "${INPUT_RELS[@]}"; do
    if [[ "$relative_path" == "$SHADED_ASM_REL" ]]; then
        continue
    fi
    mkdir -p "$SNAPSHOT_DIR/$(dirname -- "$relative_path")"
    if [[ "$relative_path" == "$ASM_JAR_REL" ]]; then
        cp -- "$ASM_SOURCE" "$SNAPSHOT_DIR/$relative_path"
    elif [[ "$relative_path" == "$ASM_POM_REL" ]]; then
        cp -- "$ASM_POM_SOURCE" "$SNAPSHOT_DIR/$relative_path"
    else
        cp -- "$SCRIPT_DIR/$relative_path" "$SNAPSHOT_DIR/$relative_path"
    fi
done
mkdir -p "$TOOL_CLASS_DIR"
printf '%s\n' '== build private ASM namespace closure (pure JDK tool) =='
javac \
    --release 17 \
    -proc:none \
    -Xlint:all \
    -Werror \
    -d "$TOOL_CLASS_DIR" \
    "$SNAPSHOT_DIR/$TOOL_SOURCE_REL"
java \
    --class-path "$TOOL_CLASS_DIR" \
    AsmNamespaceRelocator \
    "$SNAPSHOT_DIR/$ASM_JAR_REL" \
    "$SNAPSHOT_DIR/$SHADED_ASM_REL" \
    "$SNAPSHOT_DIR/$ASM_POM_REL" \
    "$ASM_SHA256"
hash_snapshot
verify_snapshot_hash
printf 'snapshotSource=%s\n' "$SNAPSHOT_DIR/$SOURCE_REL"
printf 'snapshotAsmJar=%s\n' "$SNAPSHOT_DIR/$ASM_JAR_REL"
printf 'snapshotShadedAsmJar=%s\n' "$SNAPSHOT_DIR/$SHADED_ASM_REL"
printf '%s\n' '== javac --release 17 -proc:none -Xlint:all -Werror (snapshot source) =='
JAVA_SOURCES=()
for relative_path in "${JAVA_RELS[@]}"; do
    JAVA_SOURCES+=("$SNAPSHOT_DIR/$relative_path")
done
javac \
    --release 17 \
    -proc:none \
    -Xlint:all \
    -Werror \
    --class-path "$SNAPSHOT_DIR/$SHADED_ASM_REL" \
    -d "$CLASS_DIR" \
    "${JAVA_SOURCES[@]}"
printf '%s\n' '== offline T030/T033/T035/T038 self-test -Xverify:all; no host JAR/classpath =='
java \
    -Xverify:all \
    --class-path "$CLASS_DIR:$SNAPSHOT_DIR/$SHADED_ASM_REL" \
    dev.turboism.validation.atlasimage.t035.T038OfflineHarness
grep -Fqx 'T033_OFFLINE_PASS' "$LOG_FILE"
grep -Fqx 'T035_OFFLINE_PASS' "$LOG_FILE"
grep -Fqx 'T038_OFFLINE_PASS' "$LOG_FILE"
grep -Fqx 'OFFLINE_PASS' "$LOG_FILE"

printf '%s\n' '== offline T039 validation-only premain and owned JVM test =='
cat > "$AGENT_MANIFEST" <<'EOF'
Manifest-Version: 1.0
Premain-Class: dev.turboism.validation.atlasimage.t039.T039ShadowAgent
Can-Redefine-Classes: false
Can-Retransform-Classes: false
EOF
jar --create --file "$AGENT_JAR" --manifest "$AGENT_MANIFEST" -C "$CLASS_DIR" .
mkdir -p "$SHADED_UNPACK_DIR"
(
    cd "$SHADED_UNPACK_DIR"
    jar xf "$SNAPSHOT_DIR/$SHADED_ASM_REL"
)
jar --update --file "$AGENT_JAR" -C "$SHADED_UNPACK_DIR" .
jar tf "$AGENT_JAR" | grep -Fqx "$SHADED_PREFIX/ClassReader.class"
jar tf "$AGENT_JAR" | grep -Fqx 'META-INF/asm-9.7.1.pom'
jar tf "$AGENT_JAR" | grep -Fqx 'META-INF/NOTICE-ASM-9.7.1.txt'
if jar tf "$AGENT_JAR" | grep -Fq 'org/objectweb/asm'; then
    printf '%s\n' 'agentJarGate=FAIL original ASM namespace present'
    exit 1
fi
if unzip -p "$AGENT_JAR" META-INF/MANIFEST.MF | grep -Fq 'Class-Path:'; then
    printf '%s\n' 'agentJarGate=FAIL manifest Class-Path present'
    exit 1
fi
printf '%s\n' 'agentJarGate=PASS private-ASM/no-Class-Path'
java -Xverify:all --class-path "$AGENT_JAR" \
    dev.turboism.validation.atlasimage.t039.T039FixtureArtifact "$FIXTURE_JAR" "$FIXTURE_META"

FIXTURE_CLASS_SHA256="$(sed -n 's/^classSha256=//p' "$FIXTURE_META")"
FIXTURE_SHAPE_SHA256="$(sed -n 's/^shapeSha256=//p' "$FIXTURE_META")"
FIXTURE_JAR_SHA256="$(sed -n 's/^jarSha256=//p' "$FIXTURE_META")"
HELPER_CLASS_SHA256="$(sha256sum "$CLASS_DIR/dev/turboism/validation/atlasimage/t039/T039ShadowHelper.class" | awk '{print $1}')"
T038_HELPER_CLASS_SHA256="$(sha256sum "$CLASS_DIR/dev/turboism/validation/atlasimage/t038/T038ArrayHelper.class" | awk '{print $1}')"
LOADER_CLASS='dev.turboism.validation.atlasimage.t039.T039OwnedTargetLoader'
[[ "$FIXTURE_CLASS_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$FIXTURE_SHAPE_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$FIXTURE_JAR_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$HELPER_CLASS_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$T038_HELPER_CLASS_SHA256" =~ ^[0-9a-f]{64}$ ]]

java -Xverify:all -javaagent:"$AGENT_JAR" \
    -Dturboism.validation.t039.profile=owned \
    -Dturboism.validation.t039.shadowMode=owned \
    -Dturboism.validation.t039.runId=t039-owned \
    -Dturboism.validation.t039.codeSource="$FIXTURE_JAR" \
    -Dturboism.validation.t039.jarSha256="$FIXTURE_JAR_SHA256" \
    -Dturboism.validation.t039.classSha256="$FIXTURE_CLASS_SHA256" \
    -Dturboism.validation.t039.shapeSha256="$FIXTURE_SHAPE_SHA256" \
    -Dturboism.validation.t039.loaderClass="$LOADER_CLASS" \
    -Dturboism.validation.t039.helperSha256="$HELPER_CLASS_SHA256" \
    -Dturboism.validation.t039.t038HelperSha256="$T038_HELPER_CLASS_SHA256" \
    -Dturboism.validation.t039.maxEvents=16 \
    --class-path "$AGENT_JAR" \
    dev.turboism.validation.atlasimage.t039.T039OwnedJvmHarness "$FIXTURE_META"
printf '%s\n' '== independent T040 self-contained premain/definition/patch/helper/freeze JVM =='
java -Xverify:all -javaagent:"$AGENT_JAR" \
    -Dturboism.validation.t039.profile=owned \
    -Dturboism.validation.t039.shadowMode=owned \
    -Dturboism.validation.t039.runId=t039-owned \
    -Dturboism.validation.t039.codeSource="$FIXTURE_JAR" \
    -Dturboism.validation.t039.jarSha256="$FIXTURE_JAR_SHA256" \
    -Dturboism.validation.t039.classSha256="$FIXTURE_CLASS_SHA256" \
    -Dturboism.validation.t039.shapeSha256="$FIXTURE_SHAPE_SHA256" \
    -Dturboism.validation.t039.loaderClass="$LOADER_CLASS" \
    -Dturboism.validation.t039.helperSha256="$HELPER_CLASS_SHA256" \
    -Dturboism.validation.t039.t038HelperSha256="$T038_HELPER_CLASS_SHA256" \
    -Dturboism.validation.t039.maxEvents=16 \
    --class-path "$AGENT_JAR" \
    dev.turboism.validation.atlasimage.t039.T040SelfContainedJvmHarness "$FIXTURE_META"
grep -Fqx 't040SelfContainedJvm=PASS premain/first-definition/patch/helper/freeze/no-external-ASM' "$LOG_FILE"
grep -Fqx 'T040_SELF_CONTAINED_OFFLINE_PASS' "$LOG_FILE"

printf '%s\n' '== offline T040 immutable freeze bridge (official bytes only) =='
java -Xverify:all --class-path "$AGENT_JAR" \
    dev.turboism.validation.atlasimage.t039.T040FreezeJvmHarness
grep -Fqx 'T040_OFFLINE_PASS' "$LOG_FILE"
printf '%s\n' '== offline T040 runtime ProtectionDomain/source binding (official bytes only) =='
java -Xverify:all --class-path "$AGENT_JAR" \
    dev.turboism.validation.atlasimage.t039.T040RuntimeSourceBindingHarness
grep -Fqx 'T040_RUNTIME_SOURCE_OFFLINE_PASS' "$LOG_FILE"

make_dependency_variant() {
    local mode="$1"
    local output="$2"
    local directory="$RUN_DIR/agent-$mode"
    rm -rf "$directory"
    mkdir -p "$directory"
    (
        cd "$directory"
        jar xf "$AGENT_JAR"
    )
    rm -f "$directory/META-INF/MANIFEST.MF"
    if [[ "$mode" == "missing-asm" ]]; then
        rm -rf "$directory/$SHADED_PREFIX"
    else
        printf '%s\n' 'not-a-classfile' > "$directory/$SHADED_PREFIX/ClassReader.class"
    fi
    jar --create --file "$output" --manifest "$AGENT_MANIFEST" -C "$directory" .
}

dependency_negative() {
    local kind="$1"
    local agent="$2"
    java -Xverify:all -javaagent:"$agent" \
        -Dturboism.validation.t039.profile=owned \
        -Dturboism.validation.t039.shadowMode=owned \
        -Dturboism.validation.t039.runId="t040-dependency-$kind" \
        -Dturboism.validation.t039.codeSource="$FIXTURE_JAR" \
        -Dturboism.validation.t039.jarSha256="$FIXTURE_JAR_SHA256" \
        -Dturboism.validation.t039.classSha256="$FIXTURE_CLASS_SHA256" \
        -Dturboism.validation.t039.shapeSha256="$FIXTURE_SHAPE_SHA256" \
        -Dturboism.validation.t039.loaderClass="$LOADER_CLASS" \
        -Dturboism.validation.t039.helperSha256="$HELPER_CLASS_SHA256" \
        -Dturboism.validation.t039.t038HelperSha256="$T038_HELPER_CLASS_SHA256" \
        -Dturboism.validation.t039.maxEvents=16 \
        --class-path "$agent" \
        dev.turboism.validation.atlasimage.t039.T039NegativeJvmHarness "$kind" "$FIXTURE_META"
}

printf '%s\n' '== offline T040 dependency closure negative processes =='
make_dependency_variant missing-asm "$MISSING_AGENT_JAR"
make_dependency_variant wrong-asm "$WRONG_AGENT_JAR"
dependency_negative missing-asm "$MISSING_AGENT_JAR"
dependency_negative wrong-asm "$WRONG_AGENT_JAR"
grep -Fqx 't039NegativeGate=PASS kind=missing-asm reason=bytecode-dependency-gate' "$LOG_FILE"
grep -Fqx 't039NegativeGate=PASS kind=wrong-asm reason=bytecode-dependency-gate' "$LOG_FILE"
auto_negative() {
    local kind="$1"
    local loader="$2"
    local class_sha="$3"
    local shape_sha="$4"
    local code_source="$5"
    local jar_sha="$6"
    java -Xverify:all -javaagent:"$AGENT_JAR" \
        -Dturboism.validation.t039.profile=owned \
        -Dturboism.validation.t039.runId="t039-negative-$kind" \
        -Dturboism.validation.t039.codeSource="$code_source" \
        -Dturboism.validation.t039.jarSha256="$jar_sha" \
        -Dturboism.validation.t039.classSha256="$class_sha" \
        -Dturboism.validation.t039.shapeSha256="$shape_sha" \
        -Dturboism.validation.t039.loaderClass="$loader" \
        -Dturboism.validation.t039.helperSha256="$HELPER_CLASS_SHA256" \
        -Dturboism.validation.t039.t038HelperSha256="$T038_HELPER_CLASS_SHA256" \
        -Dturboism.validation.t039.maxEvents=16 \
        --class-path "$AGENT_JAR" \
        dev.turboism.validation.atlasimage.t039.T039NegativeJvmHarness "$kind" "$FIXTURE_META"
}

WRONG_CLASS_SHA256="0${FIXTURE_CLASS_SHA256:1}"
WRONG_SHAPE_SHA256="0${FIXTURE_SHAPE_SHA256:1}"
WRONG_HELPER_SHA256="0${HELPER_CLASS_SHA256:1}"
WRONG_T038_HELPER_SHA256="0${T038_HELPER_CLASS_SHA256:1}"
auto_negative wrong-loader 'dev.turboism.validation.atlasimage.t039.WrongLoader' "$FIXTURE_CLASS_SHA256" "$FIXTURE_SHAPE_SHA256" "$FIXTURE_JAR" "$FIXTURE_JAR_SHA256"
auto_negative wrong-class "$LOADER_CLASS" "$WRONG_CLASS_SHA256" "$FIXTURE_SHAPE_SHA256" "$FIXTURE_JAR" "$FIXTURE_JAR_SHA256"
auto_negative wrong-shape "$LOADER_CLASS" "$FIXTURE_CLASS_SHA256" "$WRONG_SHAPE_SHA256" "$FIXTURE_JAR" "$FIXTURE_JAR_SHA256"
auto_negative wrong-source "$LOADER_CLASS" "$FIXTURE_CLASS_SHA256" "$FIXTURE_SHAPE_SHA256" "$SNAPSHOT_DIR/$ASM_JAR_REL" "$ASM_SHA256"

java -Xverify:all -javaagent:"$AGENT_JAR=forbidden" \
    --class-path "$AGENT_JAR" \
    dev.turboism.validation.atlasimage.t039.T039FailureHarness agent-arguments
java -Xverify:all -javaagent:"$AGENT_JAR" \
    -Dturboism.validation.t039.profile=owned \
    -Dturboism.validation.t039.runId=t039-failure-missing-helper \
    -Dturboism.validation.t039.codeSource="$FIXTURE_JAR" \
    -Dturboism.validation.t039.jarSha256="$FIXTURE_JAR_SHA256" \
    -Dturboism.validation.t039.classSha256="$FIXTURE_CLASS_SHA256" \
    -Dturboism.validation.t039.shapeSha256="$FIXTURE_SHAPE_SHA256" \
    -Dturboism.validation.t039.loaderClass="$LOADER_CLASS" \
    -Dturboism.validation.t039.maxEvents=16 \
    --class-path "$AGENT_JAR" \
    dev.turboism.validation.atlasimage.t039.T039FailureHarness missing-helper
java -Xverify:all -javaagent:"$AGENT_JAR" \
    -Dturboism.validation.t039.profile=owned \
    -Dturboism.validation.t039.runId=t039-failure-helper-hash \
    -Dturboism.validation.t039.codeSource="$FIXTURE_JAR" \
    -Dturboism.validation.t039.jarSha256="$FIXTURE_JAR_SHA256" \
    -Dturboism.validation.t039.classSha256="$FIXTURE_CLASS_SHA256" \
    -Dturboism.validation.t039.shapeSha256="$FIXTURE_SHAPE_SHA256" \
    -Dturboism.validation.t039.loaderClass="$LOADER_CLASS" \
    -Dturboism.validation.t039.helperSha256="$WRONG_HELPER_SHA256" \
    -Dturboism.validation.t039.t038HelperSha256="$T038_HELPER_CLASS_SHA256" \
    -Dturboism.validation.t039.maxEvents=16 \
    --class-path "$AGENT_JAR" \
    dev.turboism.validation.atlasimage.t039.T039FailureHarness helper-hash
java -Xverify:all -javaagent:"$AGENT_JAR" \
    -Dturboism.validation.t039.profile=owned \
    -Dturboism.validation.t039.runId=t039-failure-t038-helper-hash \
    -Dturboism.validation.t039.codeSource="$FIXTURE_JAR" \
    -Dturboism.validation.t039.jarSha256="$FIXTURE_JAR_SHA256" \
    -Dturboism.validation.t039.classSha256="$FIXTURE_CLASS_SHA256" \
    -Dturboism.validation.t039.shapeSha256="$FIXTURE_SHAPE_SHA256" \
    -Dturboism.validation.t039.loaderClass="$LOADER_CLASS" \
    -Dturboism.validation.t039.helperSha256="$HELPER_CLASS_SHA256" \
    -Dturboism.validation.t039.t038HelperSha256="$WRONG_T038_HELPER_SHA256" \
    -Dturboism.validation.t039.maxEvents=16 \
    --class-path "$AGENT_JAR" \
    dev.turboism.validation.atlasimage.t039.T039FailureHarness t038-helper-hash
java -Xverify:all --class-path "$AGENT_JAR" \
    dev.turboism.validation.atlasimage.t039.T039LoadedGateHarness "$FIXTURE_META"

{
    printf 'profile=owned-validation-only\n'
    printf 'targetClass=dev/turboism/validation/atlasimage/t035/T035OwnedAtlasFixture\n'
    printf 'targetMethod=render(II[III[IIIII)V\n'
    printf 'agentJarSha256='
    sha256sum "$AGENT_JAR" | awk '{print $1}'
    printf 'fixtureJarSha256='
    sha256sum "$FIXTURE_JAR" | awk '{print $1}'
    printf 'manifestSha256='
    sha256sum "$AGENT_MANIFEST" | awk '{print $1}'
    printf 'fixtureMetadataSha256='
    sha256sum "$FIXTURE_META" | awk '{print $1}'
    printf 'helperClassSha256=%s\n' "$HELPER_CLASS_SHA256"
    printf 't038HelperClassSha256=%s\n' "$T038_HELPER_CLASS_SHA256"
    printf 'asmSourceSha256=%s\n' "$ASM_SHA256"
    printf 'asmPomSha256='
    sha256sum "$SNAPSHOT_DIR/$ASM_POM_REL" | awk '{print $1}'
    printf 'asmShadedJarSha256='
    sha256sum "$SNAPSHOT_DIR/$SHADED_ASM_REL" | awk '{print $1}'
    printf 'asmLicense=BSD-3-Clause\n'
    printf 'asmPrivatePrefix=%s\n' "$SHADED_PREFIX"
    printf 'asmPrivateClassCount='
    jar tf "$SNAPSHOT_DIR/$SHADED_ASM_REL" | grep -c "^$SHADED_PREFIX/.*\\.class$"
    printf 'agentContainsOriginalAsm=false\n'
    printf 'agentManifestClassPath=false\n'
    printf 'agentEmbeddedAsmPom=true\n'
    printf 'agentEmbeddedAsmNotice=true\n'
    printf 'agentPrivateEntries:\n'
    jar tf "$AGENT_JAR" | grep "^$SHADED_PREFIX/" | LC_ALL=C sort
    printf 'dependencyNegativeVariants=missing-asm,wrong-asm\n'
    printf 'snapshotSourceManifestSha256='
    sha256sum "$HASH_FILE" | awk '{print $1}'
} > "$T039_INVENTORY"
printf 'T039_INVENTORY_SHA256='
sha256sum "$T039_INVENTORY" | awk '{print $1}'
grep -Fqx 'T039_OFFLINE_PASS' "$LOG_FILE"
printf '%s\n' 'T039_OFFLINE_PASS'
printf '%s\n' '== snapshot inputs remained hash-bound after test =='
verify_snapshot_hash
