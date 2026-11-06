#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local update-check host validation exerciser plugin JAR against
# the already-built SDK jar. The probe is validation tooling only; it is never
# part of the production preview bundle or product build.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

catalog='META-INF/turboism/i18n/messages.properties'
if [ ! -f "$PROBE_ROOT/validation/update-check-host-probe/src/$catalog" ]; then
  echo "error: declared probe i18n catalog is missing: validation/update-check-host-probe/src/$catalog" >&2
  exit 1
fi
# Exercise the packaged artifact, not merely the resource source directory.
PROBE_REQUIRED_ENTRIES=("$catalog")
probe_build_plugin_jar validation/update-check-host-probe \
  build/update-check-host-validation-exerciser.jar \
  validation/update-check-host-probe/src/dev/turboism/validation/updatecheck/UpdateCheckHostValidationPlugin.java
