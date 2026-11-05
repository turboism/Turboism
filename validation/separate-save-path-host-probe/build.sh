#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local separate-save-path host validation probe plugin JAR
# against the already-built SDK jar. The probe is validation tooling only; it
# is never part of the production preview bundle or product build.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

probe_build_plugin_jar validation/separate-save-path-host-probe \
  build/separate-save-path-host-validation-probe.jar \
  validation/shared/src/dev/turboism/validation/SessionGate.java \
  validation/separate-save-path-host-probe/src/dev/turboism/validation/separatesavepath/SeparateSavePathHostValidationPlugin.java
