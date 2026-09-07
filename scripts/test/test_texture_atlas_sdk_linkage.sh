#!/usr/bin/env bash
# Compile only against historical v7, then link/run without that JAR on the live SDK.
# This covers retained texture-atlas entry points, NOT exact record-shape compatibility.
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "usage: $0 historical-v7.jar current-sdk.jar" >&2
  exit 2
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cat > "$work/TextureAtlasV7Client.java" <<'JAVA'
import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.List;

public final class TextureAtlasV7Client {
    public static void main(String[] ignored) {
        var constraints = new TextureAtlasLayoutConstraints(32, 16, 1, 2, 2, false, false);
        if (constraints.maxPages() != 2 || constraints.allowRotation() || constraints.allowScaling())
            throw new AssertionError("legacy constraints changed");
        var placement = new TextureAtlasPlacement("old-client", 0, 1, 1, 4, 3, false);
        var named = new TextureAtlasLayoutPlan(32, 16, 1, List.of("old page"), List.of(placement));
        var unnamed = new TextureAtlasLayoutPlan(32, 16, 1, List.of(placement));
        if (!named.placements().equals(unnamed.placements()) || named.pageCount() != 1
            || !named.pageNames().equals(List.of("old page"))) throw new AssertionError("legacy plan changed");
        if (!named.equals(new TextureAtlasLayoutPlan(32, 16, 1, List.of("old page"), List.of(placement))))
            throw new AssertionError("equal legacy constructor arguments must remain equal");
        try {
            new TextureAtlasLayoutConstraints(32, 16, 1, 2, 2, true, false);
            throw new AssertionError("complete-atlas rotation must remain disallowed");
        } catch (IllegalArgumentException expected) { }
        System.out.println("v7-compiled texture-atlas client linked and ran successfully");
    }
}
JAVA
javac --release 17 -cp "$1" -d "$work" "$work/TextureAtlasV7Client.java"
java -cp "$work:$2" TextureAtlasV7Client
