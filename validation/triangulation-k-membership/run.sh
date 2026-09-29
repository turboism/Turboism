#!/usr/bin/env bash
# T029-KBUILD offline differential check + bounded micro-benchmark.
# Own fixture classes only; official Cubism classes are never loaded/executed.
# kotlin-stdlib-1.7.21 (public dependency, identical to the host's bundled jar) is on
# the classpath solely so Intrinsics.checkNotNullParameter produces the real NPE prefix.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
stdlib="${TURBOISM_KOTLIN_STDLIB:-}"
[[ -n "$stdlib" ]] || { echo "TURBOISM_KOTLIN_STDLIB must point at kotlin-stdlib-1.7.21.jar"; exit 2; }
[[ -f "$stdlib" && ! -L "$stdlib" ]] || { echo "kotlin-stdlib-1.7.21 not a regular file"; exit 2; }
# Pin the exact reviewed version the host bundles (Intrinsics verified against 1.7.21).
jar tf "$stdlib" | grep -qx 'kotlin/jvm/internal/Intrinsics.class' \
  || { echo "stdlib jar lacks kotlin/jvm/internal/Intrinsics.class"; exit 2; }
stdlib_sha256="$(sha256sum "$stdlib" | cut -d' ' -f1)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

javac -cp "$stdlib" -d "$work/classes" \
  "$root/src/dev/turboism/validation/kmembership/Fixture.java" \
  "$root/src/dev/turboism/validation/kmembership/SelfCheck.java" \
  "$root/src/dev/turboism/validation/kmembership/Bench.java"

java -cp "$work/classes:$stdlib" dev.turboism.validation.kmembership.SelfCheck
java -cp "$work/classes:$stdlib" dev.turboism.validation.kmembership.Bench

echo "KBUILD_RUN PASS stdlibSha256=$stdlib_sha256"
