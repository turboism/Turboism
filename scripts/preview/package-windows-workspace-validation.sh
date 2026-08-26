#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="${TURBOISM_WORKTREE_ID:-main}"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-workspace-validation}"
# The bundle must carry the agent freshly built by :bootstrap:jar for THIS worktree.
# The legacy build/preview/<worktree>/turboism-agent.jar copy is stale by design and must
# never be used: it silently misses record fixes until the preview bundle is regenerated.
bootstrap_libs="$repo_root/build/worktree/$worktree_id/bootstrap/libs"
agent_candidates=("$bootstrap_libs"/turboism-agent-*.jar)
if [ ! -e "${agent_candidates[0]:-}" ]; then
  printf 'error: no freshly built bootstrap agent jar in %s; run :bootstrap:jar first\n' "$bootstrap_libs" >&2
  exit 1
fi
if [ "${#agent_candidates[@]}" -gt 1 ]; then
  printf 'error: multiple bootstrap agent jars in %s; keep exactly one\n' "$bootstrap_libs" >&2
  exit 1
fi
agent_jar="${agent_candidates[0]}"
test_classes="$repo_root/build/worktree/$worktree_id/integration-tests/classes/java/test"
probe_class_rel="dev/turboism/tests/plugin/WindowsWorkspaceValidationProbe.class"
probe_class_dir_rel="dev/turboism/tests/plugin"
probe_descriptor="$repo_root/scripts/preview/windows-workspace-validation-plugin.json"
agent_class_rel="dev/turboism/tests/validation/WorkspaceValidationAgent.class"
agent_class_dir_rel="dev/turboism/tests/validation"
launcher_template="$repo_root/scripts/preview/launch-workspace-validation.bat.template"
command_helper_ps1="$repo_root/scripts/preview/submit-workspace-command.ps1"
readme="$repo_root/scripts/preview/README-workspace-validation.md"
record_52="$repo_root/compatibility/cubism/verification/cubism-5.2.03-workspace-control.json"
record_53="$repo_root/compatibility/cubism/verification/cubism-5.3.02-workspace-control.json"
record_overlay_52="$repo_root/compatibility/cubism/verification/cubism-5.2.03-ui-bounding-box-overlay.json"
record_overlay_53="$repo_root/compatibility/cubism/verification/cubism-5.3.02-ui-bounding-box-overlay.json"
record_project_52="$repo_root/compatibility/cubism/verification/cubism-5.2.03-project-workspace.json"
record_project_53="$repo_root/compatibility/cubism/verification/cubism-5.3.02-project-workspace.json"

for required in \
  "$agent_jar" \
  "$test_classes/$probe_class_rel" \
  "$test_classes/$agent_class_rel" \
  "$probe_descriptor" \
  "$launcher_template" \
  "$command_helper_ps1" \
  "$readme" \
  "$record_52" \
  "$record_53" \
  "$record_overlay_52" \
  "$record_overlay_53" \
  "$record_project_52" \
  "$record_project_53"; do
  [ -f "$required" ] || { printf 'error: required input not found: %s\n' "$required" >&2; exit 1; }
done
if ! ls "$test_classes"/dev/turboism/tests/plugin/WindowsWorkspaceValidationProbe\$*.class >/dev/null 2>&1; then
  printf 'error: validation probe nested classes are missing; run :testing:integration-tests:testClasses first\n' >&2
  exit 1
fi

# Freshness guard: the agent JAR must embed the CURRENT reviewed records byte for byte.
# A rebuild is the only way to refresh them; otherwise the harness would silently run
# against a stale agent (e.g. old bounding-box-overlay selectors that fail connect).
freshness_tmp="$(mktemp -d "$repo_root/build/.workspace-freshness.XXXXXX")"
trap 'rm -rf "${probe_tmp:-}" "${agent_tmp:-}" "${freshness_tmp:-}"' EXIT
(
  cd "$freshness_tmp"
  jar xf "$agent_jar" \
    META-INF/turboism/verification/cubism-5.2-workspace-control.json \
    META-INF/turboism/verification/cubism-5.3.02-workspace-control.json \
    META-INF/turboism/verification/cubism-5.2-ui-bounding-box-overlay.json \
    META-INF/turboism/verification/cubism-5.3.02-ui-bounding-box-overlay.json \
    META-INF/turboism/verification/cubism-5.2-project-workspace.json \
    META-INF/turboism/verification/cubism-5.3.02-project-workspace.json
)
for pair in \
  "$freshness_tmp/META-INF/turboism/verification/cubism-5.2-workspace-control.json:$record_52" \
  "$freshness_tmp/META-INF/turboism/verification/cubism-5.3.02-workspace-control.json:$record_53" \
  "$freshness_tmp/META-INF/turboism/verification/cubism-5.2-ui-bounding-box-overlay.json:$record_overlay_52" \
  "$freshness_tmp/META-INF/turboism/verification/cubism-5.3.02-ui-bounding-box-overlay.json:$record_overlay_53" \
  "$freshness_tmp/META-INF/turboism/verification/cubism-5.2-project-workspace.json:$record_project_52" \
  "$freshness_tmp/META-INF/turboism/verification/cubism-5.3.02-project-workspace.json:$record_project_53"; do
  embedded="${pair%%:*}"
  current="${pair#*:}"
  if ! cmp -s "$embedded" "$current"; then
    printf 'error: agent jar embeds a stale verification record (%s differs from %s); run :bootstrap:jar first\n' \
      "$(basename "$embedded")" "$current" >&2
    exit 1
  fi
done
rm -rf "$freshness_tmp"

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state" "$bundle_root/plugin-data"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"
cp "$launcher_template" "$bundle_root/launch-workspace-validation.bat.template"
cp "$command_helper_ps1" "$bundle_root/submit-workspace-command.ps1"
cp "$readme" "$bundle_root/README.md"
cp "$record_52" "$bundle_root/cubism-5.2-workspace-control.json"
cp "$record_53" "$bundle_root/cubism-5.3.02-workspace-control.json"

probe_tmp="$(mktemp -d "$repo_root/build/.workspace-probe.XXXXXX")"
agent_tmp=""
trap 'rm -rf "${probe_tmp:-}" "${agent_tmp:-}" "${freshness_tmp:-}"' EXIT
mkdir -p "$probe_tmp/$probe_class_dir_rel" "$probe_tmp/META-INF/turboism"
find "$test_classes/$probe_class_dir_rel" -maxdepth 1 -type f \
  \( -name 'WindowsWorkspaceValidationProbe.class' \
     -o -name 'WindowsWorkspaceValidationProbe$*.class' \) \
  -exec cp {} "$probe_tmp/$probe_class_dir_rel/" \;
cp "$probe_descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
(
  cd "$probe_tmp"
  mapfile -t probe_classes < <(
    find "$probe_class_dir_rel" -maxdepth 1 -type f \
      \( -name 'WindowsWorkspaceValidationProbe.class' \
         -o -name 'WindowsWorkspaceValidationProbe$*.class' \) \
      -printf '%p\n' | LC_ALL=C sort
  )
  jar --create --file "$bundle_root/plugins/workspace-validation-probe.jar" \
    "${probe_classes[@]}" \
    META-INF/turboism/plugin.json
)
if jar tf "$bundle_root/plugins/workspace-validation-probe.jar" \
  | grep -Eq 'WindowsWorkspaceValidationProbeTest|\.java$'; then
  printf 'error: validation probe package contains test/source artifacts\n' >&2
  exit 1
fi

agent_tmp="$(mktemp -d "$repo_root/build/.workspace-agent.XXXXXX")"
mkdir -p "$agent_tmp/$agent_class_dir_rel"
find "$test_classes/$agent_class_dir_rel" -maxdepth 1 -type f \
  \( -name 'WorkspaceValidationAgent.class' \
     -o -name 'WorkspaceValidationAgent$*.class' \) \
  -exec cp {} "$agent_tmp/$agent_class_dir_rel/" \;
(
  cd "$agent_tmp"
  cat > manifest.txt <<'EOF'
Manifest-Version: 1.0
Premain-Class: dev.turboism.tests.validation.WorkspaceValidationAgent
Class-Path: turboism-agent.jar

EOF
  jar --create --file "$bundle_root/workspace-validation-agent.jar" \
    --manifest manifest.txt \
    "$agent_class_dir_rel"
)
manifest_check_tmp="$(mktemp -d "$repo_root/build/.workspace-manifest.XXXXXX")"
(
  cd "$manifest_check_tmp"
  jar xf "$bundle_root/workspace-validation-agent.jar" META-INF/MANIFEST.MF
  grep -q 'Premain-Class: dev.turboism.tests.validation.WorkspaceValidationAgent' META-INF/MANIFEST.MF
  grep -q 'Class-Path: turboism-agent.jar' META-INF/MANIFEST.MF
)
rm -rf "$manifest_check_tmp"

if grep -Eiq 'java\.exe|JAVA_HOME|jvm\.dll' "$bundle_root/launch-workspace-validation.bat.template"; then
  printf 'error: launcher template must not launch java.exe directly\n' >&2
  exit 1
fi
if ! grep -Eq 'call "%CUBISM_BAT%"' "$bundle_root/launch-workspace-validation.bat.template"; then
  printf 'error: launcher template must call the official Cubism BAT\n' >&2
  exit 1
fi
if grep -Ein '\$home([^[:alnum:]_]|$)' "$bundle_root/launch-workspace-validation.bat.template"; then
  printf 'error: launcher template must not reference PowerShell $HOME\n' >&2
  exit 1
fi

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    workspace-validation-agent.jar \
    plugins/workspace-validation-probe.jar \
    cubism-5.2-workspace-control.json \
    cubism-5.3.02-workspace-control.json \
    launch-workspace-validation.bat.template \
    submit-workspace-command.ps1 \
    README.md > SHA256SUMS.txt
)

printf '[package] Windows workspace validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
