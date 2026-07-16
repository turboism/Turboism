#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
TOOL="$ROOT/scripts/test/sdk_api_baseline_cli.py"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() {
  printf 'SDK API baseline selftest: %s\n' "$*" >&2
  exit 1
}

compile_fixture() {
  local variant="$1"
  local out="$TMP/$variant"
  rm -rf "$out"
  mkdir -p "$out/src/sample/api" "$out/classes"

  cat >"$out/src/sample/api/Level.java" <<'JAVA'
package sample.api;
public enum Level { LOW, HIGH }
JAVA
  cat >"$out/src/sample/api/Nested.java" <<'JAVA'
package sample.api;
public @interface Nested { String name(); }
JAVA
  cat >"$out/src/sample/api/Marker.java" <<'JAVA'
package sample.api;
public @interface Marker {
    int number() default 7;
    String text() default "A😀";
    Class<?> type() default String.class;
    Level level() default Level.LOW;
    Nested nested() default @Nested(name = "inside");
    int[] values() default {1, 2};
}
JAVA
  cat >"$out/src/sample/api/package-info.java" <<'JAVA'
@sample.api.Marker
package sample.api;
JAVA
  cat >"$out/src/sample/api/Service.java" <<'JAVA'
package sample.api;
import java.io.IOException;
@Marker
public interface Service<T extends Number> {
    String NAME = "stable";
    int COUNT = 3;
    T read(String key) throws IOException;
}
JAVA
  cat >"$out/src/sample/api/Point.java" <<'JAVA'
package sample.api;
public record Point(int x, int y) {}
JAVA
  cat >"$out/src/sample/api/Shape.java" <<'JAVA'
package sample.api;
public sealed interface Shape permits Circle {}
JAVA
  cat >"$out/src/sample/api/Circle.java" <<'JAVA'
package sample.api;
public final class Circle implements Shape {}
JAVA

  case "$variant" in
    additive)
      cat >"$out/src/sample/api/Extra.java" <<'JAVA'
package sample.api;
public final class Extra {}
JAVA
      python3 - "$out/src/sample/api/Service.java" <<'PY'
from pathlib import Path
p = Path(__import__('sys').argv[1])
s = p.read_text()
s = s.replace('T read(String key) throws IOException;', 'T read(String key) throws IOException;\n    default boolean available() { return true; }')
p.write_text(s)
PY
      ;;
    reordered-fields)
      python3 - "$out/src/sample/api/Service.java" <<'PY'
from pathlib import Path
p = Path(__import__('sys').argv[1])
s = p.read_text()
s = s.replace('    String NAME = "stable";\n    int COUNT = 3;', '    int COUNT = 3;\n    String NAME = "stable";')
p.write_text(s)
PY
      ;;
    changed-constant)
      python3 - "$out/src/sample/api/Service.java" <<'PY'
from pathlib import Path
p = Path(__import__('sys').argv[1]); p.write_text(p.read_text().replace('"stable"', '"changed"'))
PY
      ;;
    changed-default)
      python3 - "$out/src/sample/api/Marker.java" <<'PY'
from pathlib import Path
p = Path(__import__('sys').argv[1]); p.write_text(p.read_text().replace('default 7', 'default 8'))
PY
      ;;
    changed-descriptor)
      python3 - "$out/src/sample/api/Service.java" <<'PY'
from pathlib import Path
p = Path(__import__('sys').argv[1]); p.write_text(p.read_text().replace('T read(String key)', 'T read(CharSequence key)'))
PY
      ;;
    forbidden)
      mkdir -p "$out/src/com/live2d/privateapi"
      cat >"$out/src/com/live2d/privateapi/Host.java" <<'JAVA'
package com.live2d.privateapi;
public final class Host {}
JAVA
      python3 - "$out/src/sample/api/Service.java" <<'PY'
from pathlib import Path
p = Path(__import__('sys').argv[1]); p.write_text(p.read_text().replace('T read(String key)', 'com.live2d.privateapi.Host read(String key)'))
PY
      ;;
  esac

  javac --release 17 -parameters -d "$out/classes" $(find "$out/src" -name '*.java' -print)
  jar --create --file "$out/sdk.jar" -C "$out/classes" .
}

compile_fixture baseline
compile_fixture additive
compile_fixture reordered-fields
compile_fixture changed-constant
compile_fixture changed-default
compile_fixture changed-descriptor
compile_fixture forbidden

python3 "$TOOL" dump --input "$TMP/baseline/sdk.jar" --output "$TMP/dump-a.txt"
python3 "$TOOL" dump --input "$TMP/baseline/sdk.jar" --output "$TMP/dump-b.txt"
cmp -s "$TMP/dump-a.txt" "$TMP/dump-b.txt" || fail 'canonical dump is not deterministic'
grep -q $'^sdk-api-schema\t1$' "$TMP/dump-a.txt" || fail 'schema header missing'
grep -q 'annotation-default:value:' "$TMP/dump-a.txt" || fail 'annotation defaults missing'
grep -Eq $'^package\tname=sample/api\tannotations=list:[1-9]' "$TMP/dump-a.txt" || fail 'package annotations missing'
grep -q 'constant=string:6:' "$TMP/dump-a.txt" || fail 'String ConstantValue encoding missing'
grep -q 'permitted=' "$TMP/dump-a.txt" || fail 'sealed permitted subclasses missing'

python3 "$TOOL" capture \
  --input "$TMP/baseline/sdk.jar" \
  --role pre-phase \
  --commit 0123456789abcdef0123456789abcdef01234567 \
  --output "$TMP/baseline.json"

python3 "$TOOL" verify-compatible --input "$TMP/baseline/sdk.jar" --reference-input "$TMP/baseline/sdk.jar" --baseline "$TMP/baseline.json"
python3 "$TOOL" verify-compatible --input "$TMP/additive/sdk.jar" --reference-input "$TMP/baseline/sdk.jar" --baseline "$TMP/baseline.json"
python3 "$TOOL" verify-compatible --input "$TMP/reordered-fields/sdk.jar" --reference-input "$TMP/baseline/sdk.jar" --baseline "$TMP/baseline.json"

if python3 "$TOOL" verify-compatible --input "$TMP/changed-constant/sdk.jar" --reference-input "$TMP/baseline/sdk.jar" --baseline "$TMP/baseline.json" >"$TMP/fail.log" 2>&1; then
  fail 'changed constant unexpectedly passed compatibility gate'
fi
grep -Eq 'removed|changed|incompatible' "$TMP/fail.log" || fail 'changed constant failed without reviewable diagnostic'

if python3 "$TOOL" verify-compatible --input "$TMP/changed-default/sdk.jar" --reference-input "$TMP/baseline/sdk.jar" --baseline "$TMP/baseline.json" >/dev/null 2>&1; then
  fail 'changed annotation default unexpectedly passed compatibility gate'
fi
if python3 "$TOOL" verify-compatible --input "$TMP/changed-descriptor/sdk.jar" --reference-input "$TMP/baseline/sdk.jar" --baseline "$TMP/baseline.json" >/dev/null 2>&1; then
  fail 'changed descriptor unexpectedly passed compatibility gate'
fi
if python3 "$TOOL" verify-exact --input "$TMP/additive/sdk.jar" --reference-input "$TMP/baseline/sdk.jar" --baseline "$TMP/baseline.json" >/dev/null 2>&1; then
  fail 'additive API unexpectedly passed exact gate'
fi
if python3 "$TOOL" verify-compatible --input "$TMP/forbidden/sdk.jar" --reference-input "$TMP/baseline/sdk.jar" --baseline "$TMP/baseline.json" >/dev/null 2>&1; then
  fail 'forbidden host API leak unexpectedly passed'
fi
if python3 "$TOOL" verify-compatible --input "$TMP/baseline/sdk.jar" --reference-input "$TMP/baseline/sdk.jar" --baseline "$TMP/missing.json" >/dev/null 2>&1; then
  fail 'missing baseline unexpectedly passed'
fi
printf '{"format":"wrong"}\n' >"$TMP/malformed.json"
if python3 "$TOOL" verify-compatible --input "$TMP/baseline/sdk.jar" --reference-input "$TMP/baseline/sdk.jar" --baseline "$TMP/malformed.json" >/dev/null 2>&1; then
  fail 'malformed baseline unexpectedly passed'
fi

printf '%s\n' 'SDK API baseline selftest passed.'
