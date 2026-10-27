#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
task_build="$(mktemp -d /tmp/extended-settings-build.XXXXXX)"
trap 'rm -rf "$task_build"' EXIT
javac --release 17 -d "$task_build/classes" "$root/validation/extended-interpolation-settings-host/SettingsHostProbe.java"
printf 'Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.extendedsettings.SettingsHostProbe\n\n' > "$task_build/manifest"
jar cfm "$root/build/extended-interpolation-settings-probe.jar" "$task_build/manifest" -C "$task_build/classes" .
