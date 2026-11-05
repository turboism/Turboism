#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local host-locale host validation exerciser plugin JAR
# against the already-built SDK jar. The probe is validation tooling only; it
# is never part of the production preview bundle or product build.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

probe_build_plugin_jar validation/host-locale-host-probe \
  build/host-locale-validation-exerciser.jar \
  validation/host-locale-host-probe/src/dev/turboism/validation/hostlocale/HostLocaleHostValidationPlugin.java
