#!/usr/bin/env bash
set -euo pipefail

ROOT="${TURBOISM_ADMISSION_ROOT:-$(git rev-parse --show-toplevel)}"
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
HELPER="$ROOT/scripts/test/asm_admission_gate.py"

fail() { printf 'ASM supply-chain admission selftest: %s\n' "$*" >&2; exit 1; }

copy_source_tree() {
  local destination="$1"
  tar \
    --exclude='./.git' \
    --exclude='./.gradle' \
    --exclude='./build' \
    -C "$ROOT" -cf - . | tar -C "$destination" -xf -
}

python3 "$HELPER" static --root "$ROOT"
python3 "$HELPER" evidence --root "$ROOT" --gradle-home "$GRADLE_HOME"

if [[ "${TURBOISM_SKIP_GRADLE_MODEL:-0}" != 1 ]]; then
  "$ROOT/gradlew" -q checkAsmDependencyModel checkResolvedBytecodeDependencyGraph --offline -p "$ROOT"
fi

if [[ -n "${TURBOISM_PRODUCTION_CLASSES_DIRS:-}" ]]; then
  IFS=: read -r -a class_roots <<< "$TURBOISM_PRODUCTION_CLASSES_DIRS"
else
  worktree_id="${TURBOISM_WORKTREE_ID:-$("$ROOT/scripts/dev/worktree-id.sh")}"
  mapfile -t class_roots < <(
    find "$ROOT/build/worktree/$worktree_id" -path '*/classes/java/main' -type d 2>/dev/null | sort -u
  )
  [[ ${#class_roots[@]} -gt 0 ]] || fail 'production classesDirs are unavailable; run the Gradle admission task first'
fi
python3 "$HELPER" api "${class_roots[@]}"

mutation() {
  local name="$1" expected="$2" setup="$3" sandbox log
  sandbox="$(mktemp -d)"; log="$(mktemp)"
  copy_source_tree "$sandbox"
  eval "$setup"
  if TURBOISM_ADMISSION_ROOT="$sandbox" python3 "$HELPER" static --root "$sandbox" >"$log" 2>&1; then
    rm -rf "$sandbox" "$log"; fail "mutation unexpectedly passed: $name"
  fi
  grep -qiE "$expected" "$log" || { cat "$log" >&2; rm -rf "$sandbox" "$log"; fail "wrong failure for mutation: $name"; }
  rm -rf "$sandbox" "$log"
}

mutation 'root extra asm-tree' 'ASM may occur only|expected exactly' \
  'printf '\''\ndependencies { implementation("org.ow2.asm:asm-tree:9.7.1") }\n'\'' >> "$sandbox/build.gradle.kts"'
mutation 'SDK ASM' 'ASM may occur only' \
  'printf '\''\ndependencies { implementation("org.ow2.asm:asm:9.7.1") }\n'\'' >> "$sandbox/sdk/build.gradle.kts"'
mutation 'plugin ASM' 'ASM may occur only' \
  'printf '\''\ndependencies { implementation("org.ow2.asm:asm:9.7.1") }\n'\'' >> "$sandbox/plugins/demo/build.gradle.kts"'
mutation 'custom configuration ASM' 'ASM may occur only' \
  'printf '\''\nconfigurations { create("hiddenAsm") }; dependencies { add("hiddenAsm", "org.ow2.asm:asm:9.7.1") }\n'\'' >> "$sandbox/runtime/build.gradle.kts"'
mutation 'catalog Byte Buddy alias' 'Byte Buddy' \
  'mkdir -p "$sandbox/gradle"; printf '\''[libraries]\nagent = { module = "net.bytebuddy:byte-buddy", version = "1.15.0" }\n'\'' > "$sandbox/gradle/libs.versions.toml"'
mutation 'settings dependencyResolutionManagement mavenCentral' 'repository calls are forbidden' \
  'printf '\''\ndependencyResolutionManagement { repositories.mavenCentral() }\n'\'' >> "$sandbox/settings.gradle.kts"'
mutation 'settings repositories.maven block' 'repository calls are forbidden' \
  'printf '\''\ndependencyResolutionManagement { repositories.maven { url = uri("https://evil.invalid") } }\n'\'' >> "$sandbox/settings.gradle.kts"'
mutation 'pluginManagement repository' 'repository calls are forbidden' \
  'printf '\''\npluginManagement { repositories { mavenCentral() } }\n'\'' >> "$sandbox/settings.gradle.kts"'
mutation 'comment/newline repository bypass' 'repository calls are forbidden' \
  'printf '\''\nrepositories /* hidden */\n{\n maven /* hidden */ { url = uri("https://evil.invalid") }\n}\n'\'' >> "$sandbox/settings.gradle.kts"'
mutation 'untracked repository call' 'repository calls are forbidden' \
  'mkdir -p "$sandbox/gradle/hidden"; printf '\''repositories.mavenCentral()\n'\'' > "$sandbox/gradle/hidden/untracked.gradle.kts"'

api_tmp="$(mktemp -d)"
mkdir -p "$api_tmp/src/org/objectweb/asm" "$api_tmp/src/example" "$api_tmp/classes"
printf 'package org.objectweb.asm; public class Type {}\n' > "$api_tmp/src/org/objectweb/asm/Type.java"
printf 'package example; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface Marker { Class<?> value() default org.objectweb.asm.Type.class; }\n' > "$api_tmp/src/example/Marker.java"
printf 'package example; @Marker(org.objectweb.asm.Type.class) public class Leaky<T extends org.objectweb.asm.Type> { @Marker(org.objectweb.asm.Type.class) public org.objectweb.asm.Type field; protected org.objectweb.asm.Type method(@Marker(org.objectweb.asm.Type.class) org.objectweb.asm.Type p) throws org.objectweb.asm.TypeException { return p; } }\n' > "$api_tmp/src/example/Leaky.java"
printf 'package org.objectweb.asm; public class TypeException extends Exception {}\n' > "$api_tmp/src/org/objectweb/asm/TypeException.java"
javac -parameters -d "$api_tmp/classes" $(find "$api_tmp/src" -name '*.java' -print)
if python3 "$HELPER" api "$api_tmp/classes" >/dev/null 2>&1; then rm -rf "$api_tmp"; fail 'annotation/signature API mutation unexpectedly passed'; fi
rm -rf "$api_tmp/src" "$api_tmp/classes"; mkdir -p "$api_tmp/src/org/objectweb/asm" "$api_tmp/src/example" "$api_tmp/classes"
printf 'package org.objectweb.asm; public @interface Leak {}\n' > "$api_tmp/src/org/objectweb/asm/Leak.java"
printf 'package example; import java.lang.annotation.*; @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.CLASS) public @interface InvisibleTypeUse { Class<?> value(); }\n' > "$api_tmp/src/example/InvisibleTypeUse.java"
printf 'package example; import java.lang.annotation.*; @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) public @interface VisibleTypeUse { Class<?> value(); }\n' > "$api_tmp/src/example/VisibleTypeUse.java"
printf 'package example; import java.util.List; public class TypeUseLeaky<T extends @InvisibleTypeUse(org.objectweb.asm.Leak.class) Number> { public @VisibleTypeUse(org.objectweb.asm.Leak.class) String field; public @InvisibleTypeUse(org.objectweb.asm.Leak.class) String method(@VisibleTypeUse(org.objectweb.asm.Leak.class) String p) { return p; } public List<@InvisibleTypeUse(org.objectweb.asm.Leak.class) String> generic; }\n' > "$api_tmp/src/example/TypeUseLeaky.java"
javac -d "$api_tmp/classes" $(find "$api_tmp/src" -name '*.java' -print)
if python3 "$HELPER" api "$api_tmp/classes" >/dev/null 2>&1; then rm -rf "$api_tmp"; fail 'visible/invisible TYPE_USE API mutation unexpectedly passed'; fi
record_mutation() {
  local name="$1" record_source="$2"
  rm -rf "$api_tmp/src" "$api_tmp/classes"; mkdir -p "$api_tmp/src/org/objectweb/asm" "$api_tmp/src/example" "$api_tmp/classes"
  printf 'package org.objectweb.asm; public class Leak {}\n' > "$api_tmp/src/org/objectweb/asm/Leak.java"
  printf 'package org.objectweb.asm; public @interface LeakAnnotation { Class<?> value() default Leak.class; }\n' > "$api_tmp/src/org/objectweb/asm/LeakAnnotation.java"
  printf 'package example; import java.lang.annotation.*; @Target({ElementType.RECORD_COMPONENT,ElementType.TYPE_USE}) @Retention(RetentionPolicy.RUNTIME) public @interface Visible { Class<?> value(); }\n' > "$api_tmp/src/example/Visible.java"
  printf 'package example; import java.lang.annotation.*; @Target({ElementType.RECORD_COMPONENT,ElementType.TYPE_USE}) @Retention(RetentionPolicy.CLASS) public @interface Invisible { Class<?> value(); }\n' > "$api_tmp/src/example/Invisible.java"
  printf '%s\n' "$record_source" > "$api_tmp/src/example/LeakyRecord.java"
  javac -d "$api_tmp/classes" $(find "$api_tmp/src" -name '*.java' -print)
  if python3 "$HELPER" api "$api_tmp/classes" >/dev/null 2>&1; then rm -rf "$api_tmp"; fail "$name record mutation unexpectedly passed"; fi
}
record_mutation 'descriptor' 'package example; public record LeakyRecord(org.objectweb.asm.Leak component) {}'
record_mutation 'Signature' 'package example; import java.util.List; public record LeakyRecord(List<org.objectweb.asm.Leak> component) {}'
record_mutation 'RuntimeVisibleAnnotations' 'package example; public record LeakyRecord(@Visible(org.objectweb.asm.Leak.class) String component) {}'
record_mutation 'RuntimeInvisibleAnnotations' 'package example; public record LeakyRecord(@Invisible(org.objectweb.asm.Leak.class) String component) {}'
record_mutation 'RuntimeVisibleTypeAnnotations' 'package example; public record LeakyRecord(java.util.List<@Visible(org.objectweb.asm.Leak.class) String> component) {}'
record_mutation 'RuntimeInvisibleTypeAnnotations' 'package example; public record LeakyRecord(java.util.List<@Invisible(org.objectweb.asm.Leak.class) String> component) {}'
rm -rf "$api_tmp/src" "$api_tmp/classes"; mkdir -p "$api_tmp/src/example" "$api_tmp/classes"
printf 'package example; public class Constants { public static final String VALUE = "safe"; public static final int NUMBER = 7; }\n' > "$api_tmp/src/example/Constants.java"
javac -d "$api_tmp/classes" "$api_tmp/src/example/Constants.java"
python3 "$HELPER" api "$api_tmp/classes" >/dev/null || { rm -rf "$api_tmp"; fail 'legal ConstantValue class unexpectedly failed'; }
python3 - "$api_tmp/classes/example/Constants.class" <<'PY'
from pathlib import Path
import struct, sys
p = Path(sys.argv[1]); b = bytearray(p.read_bytes()); pos = 8
cp_count = struct.unpack_from('>H', b, pos)[0]; pos += 2; cp = [None] * cp_count; i = 1
while i < cp_count:
    tag = b[pos]; pos += 1
    if tag == 1:
        n = struct.unpack_from('>H', b, pos)[0]; pos += 2; cp[i] = (tag, pos, n, bytes(b[pos:pos+n])); pos += n
    elif tag in (3,4): cp[i] = (tag, pos, 4, None); pos += 4
    elif tag in (5,6): cp[i] = (tag, pos, 8, None); pos += 8; i += 1
    elif tag in (7,8,16,19,20): cp[i] = (tag, pos, 2, None); pos += 2
    elif tag in (9,10,11,12,17,18): cp[i] = (tag, pos, 4, None); pos += 4
    elif tag == 15: cp[i] = (tag, pos, 3, None); pos += 3
    else: raise SystemExit('unexpected cp tag')
    i += 1
def u2():
    global pos
    v=struct.unpack_from('>H',b,pos)[0]; pos+=2; return v
def skip_attrs():
    global pos
    for _ in range(u2()): pos += 2; n=struct.unpack_from('>I',b,pos)[0]; pos += 4+n
pos += 6
for _ in range(u2()): pos += 2
for _ in range(u2()):
    pos += 6
    for _ in range(u2()):
        name_index=u2(); n=struct.unpack_from('>I',b,pos)[0]; pos += 4
        name=cp[name_index][3].decode()
        if name == 'ConstantValue' and n == 2:
            value_pos=pos; value_index=struct.unpack_from('>H',b,pos)[0]
            if cp[value_index][0] == 8:
                utf_index=struct.unpack_from('>H',b,cp[value_index][1])[0]
                struct.pack_into('>H',b,cp[value_index][1],value_index)
                p.write_bytes(b); raise SystemExit(0)
        pos += n
raise SystemExit('String ConstantValue not found')
PY
if python3 "$HELPER" api "$api_tmp/classes" >/dev/null 2>&1; then rm -rf "$api_tmp"; fail 'malformed CONSTANT_String.string_index mutation unexpectedly passed'; fi
if python3 "$HELPER" api "$api_tmp/missing" >/dev/null 2>&1; then rm -rf "$api_tmp"; fail 'missing classesDir mutation unexpectedly passed'; fi
mkdir -p "$api_tmp/empty"
if python3 "$HELPER" api "$api_tmp/empty" >/dev/null 2>&1; then rm -rf "$api_tmp"; fail 'zero classes mutation unexpectedly passed'; fi
rm -rf "$api_tmp"

source_api_mutation() {
  local module="$1" sandbox log wt classes
  sandbox="$(mktemp -d)"; log="$(mktemp)"; wt="bt4-${module}-api-leak"
  copy_source_tree "$sandbox"
  mkdir -p "$sandbox/$module/src/main/java/org/objectweb/asm" "$sandbox/$module/src/main/java/dev/turboism/bt4mutation"
  printf 'package org.objectweb.asm; public @interface Leak {}\n' > "$sandbox/$module/src/main/java/org/objectweb/asm/Leak.java"
  printf 'package dev.turboism.bt4mutation; @org.objectweb.asm.Leak public class ProductionLeak {}\n' > "$sandbox/$module/src/main/java/dev/turboism/bt4mutation/ProductionLeak.java"
  if ! TURBOISM_WORKTREE_ID="$wt" "$sandbox/gradlew" -q ":$module:classes" --offline -p "$sandbox" >"$log" 2>&1; then
    cat "$log" >&2; rm -rf "$sandbox" "$log"; fail "$module clean sandbox source mutation did not compile"
  fi
  classes="$sandbox/build/worktree/$wt/$module/classes/java/main"
  if python3 "$HELPER" api "$classes" >"$log" 2>&1; then
    rm -rf "$sandbox" "$log"; fail "$module real production source API leak mutation unexpectedly passed"
  fi
  grep -q 'exposes ASM' "$log" || { cat "$log" >&2; rm -rf "$sandbox" "$log"; fail "$module source mutation failed for wrong reason"; }
  rm -rf "$sandbox" "$log"
}
source_api_mutation runtime
source_api_mutation sdk

resolved_graph_mutation() {
  local name="$1" group="$2" module="$3" version="$4" expected="$5" sandbox log wt repo_dir source_jar
  sandbox="$(mktemp -d)"; log="$(mktemp)"; wt="bt4-resolved-graph"; repo_dir="$sandbox/mutation-repo"
  copy_source_tree "$sandbox"
  mkdir -p "$repo_dir/${group//.//}/$module/$version"
  source_jar="$(find "$GRADLE_HOME/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1" -name 'asm-9.7.1.jar' -print -quit)"
  cp "$source_jar" "$repo_dir/${group//.//}/$module/$version/$module-$version.jar"
  printf '<project><modelVersion>4.0.0</modelVersion><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></project>\n' \
    "$group" "$module" "$version" > "$repo_dir/${group//.//}/$module/$version/$module-$version.pom"
  printf '\nallprojects { repositories { maven { url = uri("%s") } } }\ndependencies { implementation("%s:%s:%s") }\n' \
    "$repo_dir" "$group" "$module" "$version" >> "$sandbox/runtime/build.gradle.kts"
  if TURBOISM_WORKTREE_ID="$wt" "$sandbox/gradlew" -q checkResolvedBytecodeDependencyGraph --offline \
      --dependency-verification lenient -p "$sandbox" >"$log" 2>&1; then
    rm -rf "$sandbox" "$log"; fail "resolved graph mutation unexpectedly passed: $name"
  fi
  grep -qiE "$expected" "$log" || { cat "$log" >&2; rm -rf "$sandbox" "$log"; fail "wrong resolved graph failure: $name"; }
  rm -rf "$sandbox" "$log"
}
resolved_graph_mutation 'ASM tree module' 'org.ow2.asm' 'asm-tree' '9.7.1' 'unadmitted.*asm-tree'
resolved_graph_mutation 'ASM wrong version' 'org.ow2.asm' 'asm' '9.7' 'unadmitted|must contain exactly'
resolved_graph_mutation 'Byte Buddy' 'net.bytebuddy' 'byte-buddy' '1.15.0' 'Byte Buddy|forbidden'

fake_cache_mutation() {
  local name="$1" setup="$2" sandbox fake log source_cache
  sandbox="$(mktemp -d)"; fake="$(mktemp -d)"; log="$(mktemp)"
  mkdir -p "$sandbox/validation/supply-chain" "$fake/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1"
  cp "$ROOT/validation/supply-chain/asm-9.7.1-supply-chain-admission.tsv" "$sandbox/validation/supply-chain/"
  source_cache="$GRADLE_HOME/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1"
  cp -a "$source_cache/." "$fake/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/"
  eval "$setup"
  if [[ "$name" == POM\ coordinate || "$name" == POM\ license\ URL || "$name" == POM\ dependency || "$name" == POM\ SCM ]]; then
    mutated_pom="$(find "$fake" -name asm-9.7.1.pom)"
    mutated_sha="$(sha256sum "$mutated_pom" | cut -d' ' -f1)"
    perl -pi -e "s/7229b03b30a73ee91008072d9e4569a51d8547fae8c50f527841aef4c1b0baa8/$mutated_sha/" \
      "$sandbox/validation/supply-chain/asm-9.7.1-supply-chain-admission.tsv"
  fi
  if python3 "$HELPER" evidence --root "$sandbox" --gradle-home "$fake" >"$log" 2>&1; then
    rm -rf "$sandbox" "$fake" "$log"; fail "fake cache mutation unexpectedly passed: $name"
  fi
  rm -rf "$sandbox" "$fake" "$log"
}
fake_cache_mutation 'JAR bytes' 'printf x >> "$(find "$fake" -name asm-9.7.1.jar)"'
fake_cache_mutation 'POM checksum' 'printf "<!--x-->" >> "$(find "$fake" -name asm-9.7.1.pom)"'
fake_cache_mutation 'POM coordinate' 'perl -pi -e '\''s#<artifactId>asm</artifactId>#<artifactId>evil</artifactId>#'\'' "$(find "$fake" -name asm-9.7.1.pom)"'
fake_cache_mutation 'POM license URL' 'perl -pi -e '\''s#https://asm.ow2.io/license.html#https://evil.invalid/license#'\'' "$(find "$fake" -name asm-9.7.1.pom)"'
fake_cache_mutation 'POM dependency' 'perl -0777 -pi -e '\''s#</project>#<dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>1</version></dependency></dependencies></project>#'\'' "$(find "$fake" -name asm-9.7.1.pom)"'
fake_cache_mutation 'POM SCM' 'perl -pi -e '\''s#https://gitlab.ow2.org/asm/asm/#https://evil.invalid/asm/#g'\'' "$(find "$fake" -name asm-9.7.1.pom)"'

printf '%s\n' 'ASM supply-chain admission gate and mutation selftests passed.'
