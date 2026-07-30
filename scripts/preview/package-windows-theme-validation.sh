#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="${TURBOISM_WORKTREE_ID:-$(git branch --show-current | tr '/_' '--')}"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-theme-validation}"
preview_root="$repo_root/build/preview/$worktree_id"

for file in \
  "$preview_root/turboism-agent.jar" \
  "$preview_root/plugins/ui-theme.jar" \
  "$repo_root/scripts/preview/launch-cubism-turboism.ps1"; do
  [ -f "$file" ] || { printf 'error: required theme validation artifact missing: %s\n' "$file" >&2; exit 1; }
done

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state"
cp "$preview_root/turboism-agent.jar" "$bundle_root/"
cp "$preview_root/plugins/ui-theme.jar" "$bundle_root/plugins/"
cp "$repo_root/scripts/preview/launch-cubism-turboism.ps1" "$bundle_root/"

cat > "$bundle_root/README.md" <<'EOF'
# Turboism theme validation bundle

Launch only through a task-scoped cloned Proton prefix and the exact official CubismEditor5.bat.
Pass the copied model with `-ProjectPath`. Verify the `Turboism/Theme Manager` top menu, built-in
catalog, apply/restore values, visible refresh, persisted selection, and disable/close cleanup.
EOF

(
  cd "$bundle_root"
  sha256sum turboism-agent.jar plugins/ui-theme.jar launch-cubism-turboism.ps1 README.md > SHA256SUMS.txt
)

printf '[package] Windows theme validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
