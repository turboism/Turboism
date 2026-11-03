#!/usr/bin/env bash
# T029-KWEAVE offline: own-fixture ASM weaving + differential + isolation + bench.
# Official Cubism classes are never read/loaded/executed — only own fixture bytes.
# Deps (reviewed, sha-pinned): kotlin-stdlib 1.7.21 (real Intrinsics NPE) and ASM 9.7.1.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
stdlib="${TURBOISM_KOTLIN_STDLIB:-}"
[[ -n "$stdlib" ]] || { echo "TURBOISM_KOTLIN_STDLIB must point at kotlin-stdlib-1.7.21.jar"; exit 2; }
[[ -f "$stdlib" && ! -L "$stdlib" ]] || { echo "kotlin-stdlib-1.7.21 not a regular file"; exit 2; }
stdlib_sha256="$(sha256sum "$stdlib" | cut -d' ' -f1)"
[[ "$stdlib_sha256" == d46a9d773ffb9dee4ff1a748ac845dc8e50005c589302951760a2b5187bddd19 ]] \
  || { echo "kotlin-stdlib SHA-256 mismatch (expected reviewed 1.7.21 jar)"; exit 2; }

asm="${TURBOISM_ASM_JAR:-}"
[[ -n "$asm" ]] || { echo "TURBOISM_ASM_JAR must point at asm-9.7.1.jar"; exit 2; }
[[ -f "$asm" && ! -L "$asm" ]] || { echo "asm jar not a regular file"; exit 2; }
asm_sha256="$(sha256sum "$asm" | cut -d' ' -f1)"
[[ "$asm_sha256" == 8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281 ]] \
  || { echo "asm SHA-256 mismatch (expected reviewed 9.7.1 jar)"; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

src="$root/src/dev/turboism/validation/kmembership"

# stage 1: model + target classes (no Helper reference)
javac -cp "$stdlib" -d "$work/classes-main" "$src/Fixture.java" "$src/WeaveTarget.java"
# stage 2: Helper alone into its own output dir (real isolation possible)
javac -cp "$work/classes-main:$stdlib" -d "$work/classes-helper" "$src/Helper.java"
# stage 3: harness + weaver + bench + isolated driver
javac -cp "$work/classes-main:$work/classes-helper:$stdlib:$asm" -d "$work/classes-main" \
  "$src/SelfCheck.java" "$src/Weave.java" "$src/Bench.java" "$src/IsolatedRun.java"

# normal run: Helper present; -Xverify:all for verifier honesty on woven bytes
java -Xverify:all -cp "$work/classes-main:$work/classes-helper:$stdlib:$asm" \
  dev.turboism.validation.kmembership.SelfCheck

# isolation run: Helper physically absent — woven class must load and delegate
java -Xverify:all -cp "$work/classes-main:$stdlib:$asm" \
  dev.turboism.validation.kmembership.IsolatedRun

java -Xverify:all -cp "$work/classes-main:$work/classes-helper:$stdlib:$asm" \
  dev.turboism.validation.kmembership.Bench

echo "KWEAVE_RUN PASS stdlibSha256=$stdlib_sha256 asmSha256=$asm_sha256"
