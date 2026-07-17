#!/usr/bin/env bash
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

fail=0
while IFS=' ' read -r expected path; do
  actual=$(git hash-object -- "$path")
  if [[ "$actual" != "$expected" ]]; then
    printf 'phase1 normative drift: %s (expected %s, got %s)\n' \
      "$path" "$expected" "$actual" >&2
    fail=1
  fi
done <<'EOF'
ea067bc3cf724df5d3ebe47bf8263020ff5d31d9 docs/adr/0025-phase1-shared-plugin-services.md
1f9be131d0141b1bb7fb4982c9d13736839b125f docs/migration/phase1-shared-interface-baseline.md
7e9524f69e16c6c9cb0f4440248c0ed423e66a55 docs/migration/plans/phase1-shared-interface-design-freeze-plan.md
bdc19eee12774ef37d5c26cd129cf9612a7b31a6 docs/schema/preview-report-v1.md
e2238dd7b26d326d95256c880593196a86cb277b docs/sdk/phase1-shared-plugin-services-contract.md
EOF

if (( fail )); then
  exit 1
fi

printf 'Phase 1 frozen normative blobs match.\n'
