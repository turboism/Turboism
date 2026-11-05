#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local Graal script host validation plugin and script set.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

probe_build_plugin_jar validation/graal-script-host-probe \
  build/graal-script-host-validation-exerciser.jar \
  validation/graal-script-host-probe/src/dev/turboism/validation/graalscript/GraalScriptHostValidationPlugin.java
