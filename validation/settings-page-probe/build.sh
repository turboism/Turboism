#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local settings-page host validation probe plugin JAR against
# the already-built SDK jar. The probe is validation tooling only; it is never
# part of the production preview bundle or product build.
#
# Usage: build.sh [output.jar]   (default build/settings-page-host-probe.jar)
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

catalog='META-INF/turboism/i18n/messages.properties'
if [ ! -f "$PROBE_ROOT/validation/settings-page-probe/src/$catalog" ]; then
  echo "error: declared probe i18n catalog is missing: validation/settings-page-probe/src/$catalog" >&2
  exit 1
fi
PROBE_REQUIRED_ENTRIES=("$catalog")
probe_build_plugin_jar validation/settings-page-probe \
  "${1:-build/settings-page-host-probe.jar}" \
  validation/settings-page-probe/src/dev/turboism/validation/settingspage/SettingsPageProbePlugin.java \
  validation/settings-page-probe/src/dev/turboism/validation/settingspage/TaskConfigFixture.java
