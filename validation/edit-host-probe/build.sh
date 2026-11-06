#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local edit-session host-validation probe jar. The probe is
# validation tooling only and is never part of the production preview bundle.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

probe_build_plugin_jar validation/edit-host-probe \
  build/edit-host-validation-probe.jar \
  validation/edit-host-probe/src/dev/turboism/validation/edithost/EditHostValidationPlugin.java
