#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local clipmask-viewer host validation exerciser plugin JAR
# against the already-built SDK jar. The probe is validation tooling only; it
# is never part of the production preview bundle or product build.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

probe_build_plugin_jar validation/clipmask-viewer-host-probe \
  build/clipmask-viewer-validation-exerciser.jar \
  validation/shared/src/dev/turboism/validation/NativeFreeStartup.java \
  validation/shared/src/dev/turboism/validation/SessionGate.java \
  validation/shared/src/dev/turboism/validation/shared/verdict/ProbeTerminal.java \
  validation/shared/src/dev/turboism/validation/shared/verdict/ProbeAssertions.java \
  validation/clipmask-viewer-host-probe/src/dev/turboism/validation/clipmaskviewer/ClipMaskViewerHostValidationPlugin.java
