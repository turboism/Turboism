#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local animation-timeline host validation probe plugin JAR
# against the already-built SDK jar. The probe is validation tooling only; it
# is never part of the production preview bundle or product build. The shared
# assertion recorder is compiled from its single source under validation/shared.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

probe_build_plugin_jar validation/animation-timeline-host-probe \
  build/animation-timeline-host-validation-probe.jar \
  validation/shared/src/dev/turboism/validation/shared/verdict/ProbeTerminal.java \
  validation/shared/src/dev/turboism/validation/shared/verdict/ProbeAssertions.java \
  validation/animation-timeline-host-probe/src/dev/turboism/validation/animationtimeline/AnimationTimelineHostValidationPlugin.java
