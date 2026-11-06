#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local theme host validation exerciser plugin JAR against the
# already-built SDK jar. The probe is validation tooling only; it is never part
# of the production preview bundle or product build.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

probe_build_plugin_jar validation/theme-host-probe \
  build/theme-host-validation-exerciser.jar \
  validation/theme-host-probe/src/dev/turboism/validation/theme/ThemeHostValidationPlugin.java
