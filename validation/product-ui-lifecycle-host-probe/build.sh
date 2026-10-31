#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="$repo/build/product-ui-lifecycle-host-agent"
mkdir -p "$out"
javac --release 17 -d "$out" \
 "$repo/validation/product-ui-lifecycle-host-probe/src/dev/turboism/validation/productui/ProductUiHostAgent.java" \
 "$repo/validation/shared/src/dev/turboism/validation/NativeFreeStartup.java"
printf 'Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.productui.ProductUiHostAgent\n\n' > "$out/manifest.mf"
jar cfm "$repo/build/product-ui-lifecycle-host-agent.jar" "$out/manifest.mf" -C "$out" dev
