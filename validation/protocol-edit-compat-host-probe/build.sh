#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local edit-protocol host-validation probe jar. The probe is
# validation tooling only and is never part of the production preview bundle.
#
# The close helper depends on WindowsProbeHarness, which in turn uses the history
# manager snapshot helpers, so the whole helper closure is compiled here instead of
# relying on a transitive classpath.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

helper_root="testing/integration-tests/src/test/java/dev/turboism/tests/plugin"
probe_build_plugin_jar validation/protocol-edit-compat-host-probe \
  build/edit-protocol-host-validation-probe.jar \
  "$helper_root/WindowsProbeHarness.java" \
  "$helper_root/WindowsHistoryManagerValidationProbe.java" \
  "$helper_root/WindowsHistoryNativeUiHostClose.java" \
  "$helper_root/EditProtocolValidationHostClose.java" \
  validation/protocol-edit-compat-host-probe/src/dev/turboism/validation/editprotocol/EditProtocolHostValidationPlugin.java
