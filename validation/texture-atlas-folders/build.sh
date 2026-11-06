#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local texture-atlas folder probe jar against the already-built
# SDK jar. The probe is validation tooling only.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

probe_build_plugin_jar validation/texture-atlas-folders \
  build/folder-atlas-host-probe.jar \
  validation/texture-atlas-folders/src/dev/turboism/validation/atlasfolders/FolderAtlasHostProbe.java
