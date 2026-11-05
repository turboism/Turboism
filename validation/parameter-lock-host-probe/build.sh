#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local parameter-lock host validation probe plugin JAR against
# the already-built SDK jar. The probe is validation tooling only; it is never
# part of the production preview bundle or product build.
#
# SDK-only by design: the packaged descriptor preflight below needs no runtime jar,
# so this build keeps working in a checkout that has only :sdk:jar.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

probe_build_plugin_jar validation/parameter-lock-host-probe \
  build/parameter-lock-host-validation-probe.jar \
  validation/parameter-lock-host-probe/src/dev/turboism/validation/parameterlock/ParameterLockHostValidationPlugin.java

# Scenario facts of this probe, checked against the packaged jar: protocol schema 3,
# its exact id, declared permissions/capabilities, embedded UI and Cubism requirement.
python3 - "$PROBE_ROOT/build/parameter-lock-host-validation-probe.jar" <<'PY'
import json
import sys
import zipfile


def require(condition, message):
    if not condition:
        print(f"error: {message}", file=sys.stderr)
        sys.exit(1)


with zipfile.ZipFile(sys.argv[1]) as jar:
    names = set(jar.namelist())
    meta = json.loads(jar.read("META-INF/turboism/plugin.json"))
require(meta.get("format") == "turboism.plugin.meta", f"invalid format: {meta.get('format')}")
require(meta.get("schemaVersion") == 3, f"invalid schemaVersion: {meta.get('schemaVersion')}")
require(meta.get("id") == "dev.turboism.validation.parameter-lock",
        f"invalid id: {meta.get('id')}")
require(meta.get("permissions"), "permissions must not be empty")
require(meta.get("capabilities"), "capabilities must not be empty")
environment = meta.get("environment") or {}
require(environment.get("requiresCubism") is True, "requiresCubism must be true")
require(environment.get("ui") == "embedded", "ui must be embedded")
entrypoints = meta.get("entrypoints") or []
require(entrypoints, "no entrypoints defined")
for entrypoint in entrypoints:
    packaged = entrypoint.replace(".", "/") + ".class"
    require(packaged in names, f"entrypoint class {entrypoint} ({packaged}) not found in JAR")
print(f"[manifest-preflight] PASS: {meta['id']} v{meta.get('version')} "
      f"({len(entrypoints)} entrypoint verified in JAR)")
PY
