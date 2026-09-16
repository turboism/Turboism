#!/usr/bin/env bash
# Offline tests only: never starts Cubism, creates a GL context or reads a model.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
src=validation/model-update-skip-host-probe/src/dev/turboism/validation/modelupdate
tests=validation/model-update-skip-host-probe/test/dev/turboism/validation/modelupdate
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 17 -d "$out" \
  "$src/GlSubmissionProbe.java" "$src/UploadPayloadObserver.java" \
  "$src/ProcessMemorySample.java" "$src/BenchmarkResources.java" \
  "$src/UniformLocationCache.java" "$src/UniformValueCache.java" "$src/UniformLocationTrial.java" "$src/FrameReadback.java" \
  "$tests/GlSubmissionProbeTest.java" "$tests/UploadPayloadObserverTest.java" \
  "$tests/UniformLocationCacheTest.java" "$tests/UniformLocationTrialTest.java" "$tests/FrameReadbackTest.java" \
  "$tests/UniformValueCacheTest.java" "$tests/UniformValueAllocationTest.java" "$tests/BenchmarkResourcesTest.java"
for name in GlSubmissionProbeTest UploadPayloadObserverTest UniformLocationCacheTest UniformLocationTrialTest FrameReadbackTest UniformValueCacheTest UniformValueAllocationTest BenchmarkResourcesTest; do
  java -cp "$out" "dev.turboism.validation.modelupdate.$name"
done
