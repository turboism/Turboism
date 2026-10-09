#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
bash validation/session-coordinator/build.sh
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 17 -d "$out" \
  validation/session-coordinator/src/dev/turboism/validation/session/SessionPlan.java \
  validation/session-coordinator/src/dev/turboism/validation/session/SessionRunner.java \
  validation/session-coordinator/test/dev/turboism/validation/session/SessionRunnerSelfTest.java
java -cp "$out" dev.turboism.validation.session.SessionRunnerSelfTest
