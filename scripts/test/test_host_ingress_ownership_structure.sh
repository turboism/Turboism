#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

"${REPO_ROOT}/gradlew" -p "${REPO_ROOT}" :runtime:test \
  --tests dev.turboism.bootstrap.HostIngressOwnershipStructureTest \
  --tests dev.turboism.adapter.host.HostSessionCompositionApiTest
