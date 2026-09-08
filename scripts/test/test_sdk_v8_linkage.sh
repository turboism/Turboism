#!/usr/bin/env bash
# Compile against frozen v8; run only with the current SDK. Not a record-shape guarantee.
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "usage: $0 historical-v8.jar current-sdk.jar" >&2
  exit 2
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cat > "$work/SdkV8Client.java" <<'JAVA'
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.textureatlas.*;
import dev.turboism.sdk.runtime.RuntimeSettings;
import java.util.List;
import java.util.Optional;

public final class SdkV8Client {
    public static void main(String[] ignored) {
        var entry = new HistoryEntry(0, "Atlas layout", true,
            Optional.empty(), Optional.empty(), Optional.empty());
        if (!entry.label().equals("Atlas layout") || entry.index() != 0
            || entry.detailLevel() != HistoryAction.DetailLevel.LABEL_ONLY)
            throw new AssertionError("v8 history constructor/accessors changed");
        var settings = new RuntimeSettings(false, "INFO", 100, false, false, false, false, "en");
        if (settings.safeMode() || !settings.locale().equals("en") || settings.maxLogStorageMiB() != 100)
            throw new AssertionError("v8 runtime settings constructor/accessors changed");
        var constraints = TextureAtlasLayoutConstraints.currentPage(32, 32, 1, true, 0);
        if (constraints.singlePageOptions().requestedScale() != 0 || constraints.maxPages() != 1)
            throw new AssertionError("current-page contract changed");
        var placement = new TextureAtlasPlacement("image", 0, 1, 1, 4, 3, true);
        var plan = new TextureAtlasLayoutPlan(32, 32, 1, List.of("page"), List.of(placement), 0.5);
        if (plan.scale() != 0.5 || !plan.placements().get(0).rotated())
            throw new AssertionError("current-page absolute scale/rotation changed");
        System.out.println("v8-compiled history/settings/atlas client linked and ran successfully");
    }
}
JAVA
javac --release 17 -cp "$1" -d "$work" "$work/SdkV8Client.java"
java -cp "$work:$2" SdkV8Client
