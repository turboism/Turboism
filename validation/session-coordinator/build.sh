#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local multi-probe session coordinator plugin JAR against the
# already-built SDK jar. The coordinator is validation tooling only; it is never
# part of the production preview bundle or product build.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

probe_build_plugin_jar validation/session-coordinator \
  build/session-coordinator.jar \
  validation/shared/src/dev/turboism/validation/NativeFreeStartup.java \
  validation/session-coordinator/src/dev/turboism/validation/session/SessionPlan.java \
  validation/session-coordinator/src/dev/turboism/validation/session/SessionRunner.java \
  validation/session-coordinator/src/dev/turboism/validation/session/SessionCoordinatorPlugin.java
