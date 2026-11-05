#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local model-update-skip host validation exerciser plugin JAR
# against the already-built SDK jar. The probe is validation tooling only; it
# is never part of the production preview bundle or product build.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

PROBE_JAVAC_FLAGS=(-encoding UTF-8)
base=validation/model-update-skip-host-probe/src/dev/turboism/validation/modelupdate
probe_build_plugin_jar validation/model-update-skip-host-probe \
  build/model-update-skip-host-validation-exerciser.jar \
  "$base/ModelUpdateSkipHostProbePlugin.java" \
  "$base/CanvasWheelWorkload.java" \
  "$base/HumanTrace.java" \
  "$base/PaletteReorderWorkload.java" \
  "$base/NativeDragSequence.java" \
  "$base/NativeParitySequence.java" \
  "$base/NativeInteractionHost.java" \
  "$base/NativeInteractionFocus.java" \
  "$base/NativeInteractionWorkload.java" \
  "$base/NativeDeformerWorkload.java" \
  "$base/PreparationWatchdog.java" \
  "$base/AllocationProfile.java" \
  "$base/NarrowUniformTrial.java" \
  "$base/GpuCompletionProbe.java" \
  "$base/GlSubmissionProbe.java" \
  "$base/GlCallCategory.java" \
  "$base/GlRedundancyTracker.java" \
  "$base/UploadPayloadObserver.java" \
  "$base/UniformLocationCache.java" \
  "$base/UniformValueCache.java" \
  "$base/UniformLocationTrial.java" \
  "$base/FrameReadback.java" \
  "$base/ProcessMemorySample.java" \
  "$base/BenchmarkResources.java"
