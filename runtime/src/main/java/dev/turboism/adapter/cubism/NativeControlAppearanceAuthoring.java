package dev.turboism.adapter.cubism;

import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.NativeControlAppearance;
import dev.turboism.sdk.ui.appearance.NativeControlBackground;

/**
 * Runtime-private generation-safe native-control authoring seam.
 *
 * <p>Implementations resolve the active model and the target fresh inside the caller's
 * session lease and reject missing, stale, or ambiguous identities before mutation.</p>
 */
public interface NativeControlAppearanceAuthoring {

    NativeControlAppearance snapshot(ControlAppearanceTarget target);

    void setNativeBackground(ControlAppearanceTarget target, NativeControlBackground background);
}
