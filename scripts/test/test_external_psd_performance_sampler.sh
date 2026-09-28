#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
package=dev/turboism/validation/externalpsd
javac --release 17 -Xlint:all -d "$out" \
  "$root/validation/external-psd-edit-host-probe/src/$package/ExternalPsdPerformanceSampler.java" \
  "$root/validation/external-psd-edit-host-probe/test/$package/ExternalPsdPerformanceSamplerTest.java"
java -Djava.awt.headless=true -cp "$out" \
  dev.turboism.validation.externalpsd.ExternalPsdPerformanceSamplerTest
